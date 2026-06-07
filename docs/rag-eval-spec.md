# RAG 效果评测体系 — 实现 Spec

> 基于方案文档的落地实施规格，可直接驱动开发。

---

## 概览

本 spec 将方案文档的 8 个阶段拆为 **6 个可独立交付的 Story**，每个 Story 列出具体要创建/修改的文件、完整的代码结构、配置项和验收标准。

**关键设计决策**（与方案文档一致）：

- 评测异步执行，不阻塞 SSE 主链路
- 复用现有 RocketMQ 模式（参考 MessageFeedbackEvent → Consumer）
- 复用现有 `RoutingLLMService` + `PromptTemplateLoader`
- 新建独立评测表 `t_rag_eval_result`，不污染 Trace 表
- 首期仅实现 `faithfulness` + `answer_relevancy`，`correctness` 在 Story 3 单独交付

---

## Story 1：评测数据模型与配置

### 1.1 新建数据库表

**文件**: `resources/database/upgrade_add_rag_eval.sql`

```sql
CREATE TABLE IF NOT EXISTS t_rag_eval_result (
    id              VARCHAR(24)  NOT NULL,
    trace_id        VARCHAR(24)  NOT NULL,
    conversation_id VARCHAR(24)  NOT NULL,
    message_id      VARCHAR(24)  NOT NULL,
    question        TEXT,
    answer          TEXT,
    metric_name     VARCHAR(32)  NOT NULL,   -- faithfulness / answer_relevancy / correctness
    score           NUMERIC(4,3) NOT NULL,   -- 0.000 ~ 1.000
    label           VARCHAR(8)   NOT NULL,   -- PASS / WARN / FAIL
    reason          TEXT,
    evidence        JSONB,                   -- {"unsupported_claims": [...], "supported_claims": [...]} 等
    judge_model     VARCHAR(64),
    judge_version   VARCHAR(32),
    cost_tokens     INTEGER      DEFAULT 0,
    eval_mode       VARCHAR(16)  NOT NULL,   -- ONLINE_SAMPLE / OFFLINE_BATCH
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id)
);

CREATE INDEX idx_eval_trace_id ON t_rag_eval_result(trace_id);
CREATE INDEX idx_eval_msg_id    ON t_rag_eval_result(message_id);
CREATE INDEX idx_eval_metric    ON t_rag_eval_result(metric_name);
CREATE INDEX idx_eval_label     ON t_rag_eval_result(label);
CREATE INDEX idx_eval_mode_time ON t_rag_eval_result(eval_mode, create_time);
```

### 1.2 新增 DO 和 Mapper

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/dao/entity/RagEvalResultDO.java`

```java
package com.nageoffer.ai.ragent.rag.eval.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_rag_eval_result")
public class RagEvalResultDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String traceId;
    private String conversationId;
    private String messageId;
    private String question;
    private String answer;
    private String metricName;
    private BigDecimal score;
    private String label;
    private String reason;
    private String evidence;       // JSONB → Java String
    private String judgeModel;
    private String judgeVersion;
    private Integer costTokens;
    private String evalMode;
    private Date createTime;
}
```

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/dao/mapper/RagEvalResultMapper.java`

```java
package com.nageoffer.ai.ragent.rag.eval.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.rag.eval.dao.entity.RagEvalResultDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RagEvalResultMapper extends BaseMapper<RagEvalResultDO> {
}
```

### 1.3 新增评测配置 Properties

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/config/RagEvalProperties.java`

```java
package com.nageoffer.ai.ragent.rag.eval.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "rag.eval")
public class RagEvalProperties {

    /** 总开关 */
    private boolean enabled = false;

    /** 在线抽样比例 (0.0 ~ 1.0) */
    private double sampleRate = 0.05;

    /** 默认 judge 模型 id */
    private String defaultModel = "qwen-plus";

    /** judge 模型候选列表，复用 AIModelProperties.ModelCandidate 结构 */
    private List<JudgeCandidate> candidates = List.of();

    /** 指标配置 */
    private MetricsConfig metrics = new MetricsConfig();

    @Data
    public static class MetricsConfig {
        private boolean faithfulness = true;
        private boolean answerRelevancy = true;
        private boolean correctness = false;
    }

    @Data
    public static class JudgeCandidate {
        private String id;
        private String provider;
        private String model;
        private Integer priority = 100;
    }
}
```

### 1.4 新增 application.yaml 配置段

**修改文件**: `bootstrap/src/main/resources/application.yaml`

在 `rag:` 配置块下新增：

```yaml
rag:
  eval:
    enabled: false
    sample-rate: 0.05          # 首期仅抽样 5%
    default-model: qwen-plus   # 使用轻量模型控制成本
    candidates:
      - id: qwen-plus
        provider: bailian
        model: qwen-plus-latest
        priority: 1
      - id: qwen3-local
        provider: ollama
        model: qwen3:8b-fp16
        priority: 2
    metrics:
      faithfulness: true
      answer-relevancy: true
      correctness: false
```

### Story 1 验收标准

- [ ] SQL 脚本在 PostgreSQL 上执行成功，表和索引均创建
- [ ] Spring Boot 启动时 `RagEvalProperties` 正确绑定 YAML 配置
- [ ] `RagEvalResultMapper` 可通过 MyBatis-Plus 正常 CRUD

---

## Story 2：Judge 客户端与 Evaluator 实现

### 2.1 评测结果对象

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/model/RagEvalMetricResult.java`

```java
package com.nageoffer.ai.ragent.rag.eval.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class RagEvalMetricResult {

    private String metricName;        // faithfulness / answer_relevancy / correctness
    private BigDecimal score;         // 0.00 ~ 1.00
    private String label;             // PASS / WARN / FAIL
    private String reason;            // 自然语言理由
    private Map<String, Object> evidence;  // 结构化证据，序列化入 evidence JSONB
    private String judgeModel;
    private Integer costTokens;

    // ---- 阈值常量 ----
    public static final BigDecimal PASS_THRESHOLD = new BigDecimal("0.70");
    public static final BigDecimal WARN_THRESHOLD = new BigDecimal("0.40");

    public static String labelFromScore(BigDecimal score) {
        if (score == null) return "FAIL";
        if (score.compareTo(PASS_THRESHOLD) >= 0) return "PASS";
        if (score.compareTo(WARN_THRESHOLD) >= 0) return "WARN";
        return "FAIL";
    }
}
```

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/model/EvalInput.java`

```java
package com.nageoffer.ai.ragent.rag.eval.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EvalInput {

    private String traceId;
    private String conversationId;
    private String messageId;
    private String question;
    private String answer;
    private String kbContext;          // 检索到的知识库上下文（裁剪后）
    private String mcpContext;         // MCP 工具上下文（可选）
    private String referenceAnswer;    // 参考答案（correctness 评测时使用）
}
```

### 2.2 Judge Client

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/client/RagJudgeClient.java`

```java
package com.nageoffer.ai.ragent.rag.eval.client;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.eval.config.RagEvalProperties;
import com.nageoffer.ai.ragent.rag.eval.model.RagEvalMetricResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Judge 客户端：拼装 prompt → 调 LLM → 解析 JSON → 返回 RagEvalMetricResult
 * 复用现有 RoutingLLMService（非流式同步调用），不走 SSE
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagJudgeClient {

    private final LLMService llmService;
    private final PromptTemplateLoader promptLoader;
    private final RagEvalProperties evalProperties;
    private final ObjectMapper objectMapper;

    /**
     * 执行单次评测调用
     *
     * @param promptPath  prompt 模板路径，如 "prompt/eval-faithfulness.st"
     * @param metricName  指标名
     * @param slots       模板占位符填充
     * @param modelId     指定 judge 模型 id（null 则用默认）
     */
    public RagEvalMetricResult judge(String promptPath, String metricName,
                                      Map<String, String> slots, String modelId) {
        long start = System.currentTimeMillis();
        String model = StrUtil.blankToDefault(modelId, evalProperties.getDefaultModel());

        String systemPrompt = promptLoader.render(promptPath, slots);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(systemPrompt)))
                .temperature(0.0)          // 评测场景 temperature = 0，确保可重复
                .thinking(false)
                .build();

        String response;
        try {
            // 评测使用同步非流式调用，组装一个丢弃回调
            StringBuilder buf = new StringBuilder();
            StreamCallback cb = new StreamCallback() {
                @Override public void onContent(String chunk) { buf.append(chunk); }
                @Override public void onComplete() {}
                @Override public void onError(Throwable error) { buf.setLength(0); }
            };
            llmService.streamChat(request, model, cb);
            response = buf.toString();
        } catch (Exception e) {
            log.error("Judge 模型调用失败: metric={}, model={}", metricName, model, e);
            return RagEvalMetricResult.builder()
                    .metricName(metricName)
                    .score(BigDecimal.ZERO)
                    .label("FAIL")
                    .reason("Judge model call failed: " + e.getMessage())
                    .judgeModel(model)
                    .costTokens(0)
                    .build();
        }

        return parseResponse(response, metricName, model);
    }

    private RagEvalMetricResult parseResponse(String raw, String metricName, String model) {
        try {
            // 清理可能的 markdown code block 包裹
            String json = raw.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);

            BigDecimal score = toBigDecimal(map.get("score"));
            String label = (String) map.getOrDefault("label",
                    RagEvalMetricResult.labelFromScore(score));
            String reason = (String) map.getOrDefault("reason", "");

            RagEvalMetricResult.RagEvalMetricResultBuilder builder = RagEvalMetricResult.builder()
                    .metricName(metricName)
                    .score(score)
                    .label(label)
                    .reason(reason)
                    .judgeModel(model);

            // 透传额外结构化证据字段
            map.remove("score");
            map.remove("label");
            map.remove("reason");
            if (!map.isEmpty()) {
                builder.evidence(map);
            }

            return builder.build();

        } catch (Exception e) {
            log.warn("解析 judge 返回 JSON 失败, metric={}, raw={}", metricName,
                    raw.length() > 500 ? raw.substring(0, 500) : raw, e);
            return RagEvalMetricResult.builder()
                    .metricName(metricName)
                    .score(BigDecimal.ZERO)
                    .label("FAIL")
                    .reason("Failed to parse judge response: " + e.getMessage())
                    .judgeModel(model)
                    .build();
        }
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return BigDecimal.ZERO;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(val.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
```

### 2.3 Evaluator 接口与实现

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/RagMetricEvaluator.java`

```java
package com.nageoffer.ai.ragent.rag.eval;

import com.nageoffer.ai.ragent.rag.eval.model.EvalInput;
import com.nageoffer.ai.ragent.rag.eval.model.RagEvalMetricResult;

/**
 * 单一评测指标的执行器，每个指标一个实现
 */
@FunctionalInterface
public interface RagMetricEvaluator {
    RagEvalMetricResult evaluate(EvalInput input);
}
```

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/impl/FaithfulnessEvaluator.java`

```java
package com.nageoffer.ai.ragent.rag.eval.impl;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.eval.RagMetricEvaluator;
import com.nageoffer.ai.ragent.rag.eval.client.RagJudgeClient;
import com.nageoffer.ai.ragent.rag.eval.model.EvalInput;
import com.nageoffer.ai.ragent.rag.eval.model.RagEvalMetricResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class FaithfulnessEvaluator implements RagMetricEvaluator {

    private static final String PROMPT_PATH = "prompt/eval-faithfulness.st";
    private static final String METRIC_NAME = "faithfulness";

    private final RagJudgeClient judgeClient;

    @Override
    public RagEvalMetricResult evaluate(EvalInput input) {
        Map<String, String> slots = Map.of(
                "question", StrUtil.blankToDefault(input.getQuestion(), ""),
                "answer", StrUtil.blankToDefault(input.getAnswer(), ""),
                "context", buildContext(input)
        );
        return judgeClient.judge(PROMPT_PATH, METRIC_NAME, slots, null);
    }

    private String buildContext(EvalInput input) {
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(input.getKbContext())) {
            sb.append("[知识库上下文]\n").append(input.getKbContext());
        }
        if (StrUtil.isNotBlank(input.getMcpContext())) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("[MCP工具上下文]\n").append(input.getMcpContext());
        }
        return sb.length() > 0 ? sb.toString() : "（无检索上下文）";
    }
}
```

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/impl/AnswerRelevancyEvaluator.java`

```java
package com.nageoffer.ai.ragent.rag.eval.impl;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.eval.RagMetricEvaluator;
import com.nageoffer.ai.ragent.rag.eval.client.RagJudgeClient;
import com.nageoffer.ai.ragent.rag.eval.model.EvalInput;
import com.nageoffer.ai.ragent.rag.eval.model.RagEvalMetricResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class AnswerRelevancyEvaluator implements RagMetricEvaluator {

    private static final String PROMPT_PATH = "prompt/eval-answer-relevancy.st";
    private static final String METRIC_NAME = "answer_relevancy";

    private final RagJudgeClient judgeClient;

    @Override
    public RagEvalMetricResult evaluate(EvalInput input) {
        Map<String, String> slots = Map.of(
                "question", StrUtil.blankToDefault(input.getQuestion(), ""),
                "answer", StrUtil.blankToDefault(input.getAnswer(), "")
        );
        return judgeClient.judge(PROMPT_PATH, METRIC_NAME, slots, null);
    }
}
```

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/impl/CorrectnessEvaluator.java`

```java
package com.nageoffer.ai.ragent.rag.eval.impl;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.eval.RagMetricEvaluator;
import com.nageoffer.ai.ragent.rag.eval.client.RagJudgeClient;
import com.nageoffer.ai.ragent.rag.eval.model.EvalInput;
import com.nageoffer.ai.ragent.rag.eval.model.RagEvalMetricResult;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.eval.metrics", name = "correctness", havingValue = "true")
public class CorrectnessEvaluator implements RagMetricEvaluator {

    private static final String PROMPT_PATH = "prompt/eval-correctness.st";
    private static final String METRIC_NAME = "correctness";

    private final RagJudgeClient judgeClient;

    @Override
    public RagEvalMetricResult evaluate(EvalInput input) {
        if (StrUtil.isBlank(input.getReferenceAnswer())) {
            // 无参考答案时跳过
            return RagEvalMetricResult.builder()
                    .metricName(METRIC_NAME)
                    .score(null)
                    .label("FAIL")
                    .reason("无参考答案，无法评测 correctness")
                    .build();
        }
        Map<String, String> slots = Map.of(
                "question", StrUtil.blankToDefault(input.getQuestion(), ""),
                "answer", StrUtil.blankToDefault(input.getAnswer(), ""),
                "reference_answer", input.getReferenceAnswer()
        );
        return judgeClient.judge(PROMPT_PATH, METRIC_NAME, slots, null);
    }
}
```

### 2.4 评测 Prompt 模板

**文件**: `bootstrap/src/main/resources/prompt/eval-faithfulness.st`

```text
你是一个严格的RAG答案忠实度评审专家。你的任务是判断"答案"中的每一个关键结论是否都能从"检索上下文"中找到依据。

## 评审标准
- score = 1.0：答案中所有关键事实/数据/结论都能在上下文中找到明确出处，没有无依据的补充。
- score = 0.7~0.9：有少量次要细节无依据，但主要结论均有支持。
- score = 0.4~0.6：部分关键结论无依据，或存在上下文拼接错误。
- score = 0.1~0.3：多数结论无依据。
- score = 0.0：答案完全捏造，与上下文无关。

## 标签规则
- PASS: score >= 0.7
- WARN: 0.4 <= score < 0.7
- FAIL: score < 0.4

## 输入
【用户问题】
{{question}}

【检索上下文】
{{context}}

【待评审答案】
{{answer}}

## 输出格式
请严格输出以下JSON格式，不要输出其他内容：
{
  "score": <0到1之间的数字>,
  "label": "<PASS | WARN | FAIL>",
  "reason": "<简要说明判断依据>",
  "unsupported_claims": ["<答案中无依据的声明1>", "<声明2>"],
  "supported_claims": ["<答案中有依据的声明1>", "<声明2>"]
}
```

**文件**: `bootstrap/src/main/resources/prompt/eval-answer-relevancy.st`

```text
你是一个严格的RAG答案相关性评审专家。你的任务是判断"答案"是否直接回应了"用户问题"，是否完整覆盖了问题的核心意图。

## 评审标准
- score = 1.0：完整、直接地回答了问题，无遗漏、无跑题。
- score = 0.7~0.9：基本回答了问题，有少量非核心点遗漏或轻微偏题。
- score = 0.4~0.6：部分回答了问题，但遗漏了重要方面或存在明显跑题。
- score = 0.1~0.3：仅蹭到问题边缘，答非所问。
- score = 0.0：完全未回答问题，或回复为"不知道"等无实质内容。

## 标签规则
- PASS: score >= 0.7
- WARN: 0.4 <= score < 0.7
- FAIL: score < 0.4

## 输入
【用户问题】
{{question}}

【待评审答案】
{{answer}}

## 输出格式
请严格输出以下JSON格式，不要输出其他内容：
{
  "score": <0到1之间的数字>,
  "label": "<PASS | WARN | FAIL>",
  "reason": "<简要说明判断依据>",
  "covered_points": ["<已覆盖的问题点>"],
  "missing_points": ["<遗漏的问题点>"],
  "off_topic_points": ["<跑题的内容>"]
}
```

**文件**: `bootstrap/src/main/resources/prompt/eval-correctness.st`

```text
你是一个严格的RAG答案正确性评审专家。你的任务是判断"答案"是否与"参考答案"一致（包括事实准确性和完整性）。

## 评审标准
- score = 1.0：与参考答案完全一致，无错误、无遗漏。
- score = 0.7~0.9：基本正确，有少量非关键细节差异。
- score = 0.4~0.6：部分正确，存在关键事实错误或重要遗漏。
- score = 0.1~0.3：大部分不正确。
- score = 0.0：完全错误或矛盾。

## 标签规则
- PASS: score >= 0.7
- WARN: 0.4 <= score < 0.7
- FAIL: score < 0.4

## 输入
【用户问题】
{{question}}

【参考答案】
{{reference_answer}}

【待评审答案】
{{answer}}

## 输出格式
请严格输出以下JSON格式，不要输出其他内容：
{
  "score": <0到1之间的数字>,
  "label": "<PASS | WARN | FAIL>",
  "reason": "<简要说明判断依据>",
  "errors": ["<错误点>"],
  "missing": ["<遗漏点>"]
}
```

### Story 2 验收标准

- [ ] `RagJudgeClient` 可成功调用 LLM 并解析返回的 JSON
- [ ] Judge 调用失败时返回 `FAIL` 兜底结果，不抛异常
- [ ] `FaithfulnessEvaluator` 正确拼接上下文并传入 prompt
- [ ] `AnswerRelevancyEvaluator` 正确传入 question + answer
- [ ] `CorrectnessEvaluator` 无参考答案时跳过

---

## Story 3：评测触发链路（在线抽样）

### 3.1 新增 Eval Event

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/mq/event/RagEvalEvent.java`

```java
package com.nageoffer.ai.ragent.rag.eval.mq.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvalEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Trace ID */
    private String traceId;

    /** 会话 ID */
    private String conversationId;

    /** 消息 ID（对应 assistant 回复） */
    private String messageId;

    /** 原始用户问题 */
    private String question;

    /** 最终答案 */
    private String answer;

    /** KB 检索上下文（关键：faithfulness 评测依据） */
    private String kbContext;

    /** MCP 工具上下文（可空） */
    private String mcpContext;
}
```

### 3.2 新增 Eval Consumer

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/mq/RagEvalConsumer.java`

```java
package com.nageoffer.ai.ragent.rag.eval.mq;

import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.rag.eval.RagEvaluationService;
import com.nageoffer.ai.ragent.rag.eval.mq.event.RagEvalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.eval", name = "enabled", havingValue = "true")
@RocketMQMessageListener(
    topic = "rag-eval_topic${unique-name:}",
    consumerGroup = "rag-eval_cg${unique-name:}"
)
public class RagEvalConsumer implements RocketMQListener<MessageWrapper<RagEvalEvent>> {

    private final RagEvaluationService evaluationService;

    @Override
    public void onMessage(MessageWrapper<RagEvalEvent> message) {
        RagEvalEvent event = message.getBody();
        log.info("[评测消费者] 收到评测事件, traceId={}, messageId={}",
                event.getTraceId(), event.getMessageId());
        try {
            evaluationService.evaluate(event);
        } catch (Exception e) {
            log.error("[评测消费者] 评测失败, traceId={}, messageId={}",
                    event.getTraceId(), event.getMessageId(), e);
            // 不抛异常，避免 MQ 无限重试
        }
    }
}
```

### 3.3 新增 EvaluationService

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/RagEvaluationService.java`

```java
package com.nageoffer.ai.ragent.rag.eval;

import com.nageoffer.ai.ragent.rag.eval.mq.event.RagEvalEvent;

/**
 * 评测编排服务：接收评测事件，串联多个 Evaluator，持久化结果
 */
public interface RagEvaluationService {
    void evaluate(RagEvalEvent event);
}
```

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/impl/RagEvaluationServiceImpl.java`

```java
package com.nageoffer.ai.ragent.rag.eval.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.rag.eval.RagEvaluationService;
import com.nageoffer.ai.ragent.rag.eval.RagMetricEvaluator;
import com.nageoffer.ai.ragent.rag.eval.config.RagEvalProperties;
import com.nageoffer.ai.ragent.rag.eval.dao.entity.RagEvalResultDO;
import com.nageoffer.ai.ragent.rag.eval.dao.mapper.RagEvalResultMapper;
import com.nageoffer.ai.ragent.rag.eval.model.EvalInput;
import com.nageoffer.ai.ragent.rag.eval.model.RagEvalMetricResult;
import com.nageoffer.ai.ragent.rag.eval.mq.event.RagEvalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagEvaluationServiceImpl implements RagEvaluationService {

    private final RagEvalProperties evalProperties;
    private final RagEvalResultMapper evalResultMapper;
    private final RagTraceRunMapper traceRunMapper;
    private final ObjectMapper objectMapper;

    /** Spring 自动注入所有 RagMetricEvaluator 实现 */
    private final Map<String, RagMetricEvaluator> evaluators;

    @Override
    public void evaluate(RagEvalEvent event) {
        EvalInput input = EvalInput.builder()
                .traceId(event.getTraceId())
                .conversationId(event.getConversationId())
                .messageId(event.getMessageId())
                .question(event.getQuestion())
                .answer(event.getAnswer())
                .kbContext(event.getKbContext())
                .mcpContext(event.getMcpContext())
                .build();

        List<RagEvalResultDO> results = new ArrayList<>();

        // 遍历所有启用的指标
        if (evalProperties.getMetrics().isFaithfulness()) {
            RagMetricEvaluator e = evaluators.get("faithfulnessEvaluator");
            if (e != null) {
                RagEvalMetricResult r = e.evaluate(input);
                results.add(toDO(r, input, "ONLINE_SAMPLE"));
            }
        }
        if (evalProperties.getMetrics().isAnswerRelevancy()) {
            RagMetricEvaluator e = evaluators.get("answerRelevancyEvaluator");
            if (e != null) {
                RagEvalMetricResult r = e.evaluate(input);
                results.add(toDO(r, input, "ONLINE_SAMPLE"));
            }
        }
        if (evalProperties.getMetrics().isCorrectness()) {
            RagMetricEvaluator e = evaluators.get("correctnessEvaluator");
            if (e != null) {
                RagEvalMetricResult r = e.evaluate(input);
                if (r.getScore() != null) { // correctness 可能因无参考答案而跳过
                    results.add(toDO(r, input, "ONLINE_SAMPLE"));
                }
            }
        }

        // 批量持久化
        for (RagEvalResultDO resultDO : results) {
            try {
                evalResultMapper.insert(resultDO);
            } catch (Exception e) {
                log.error("评测结果落库失败, traceId={}, metric={}",
                        resultDO.getTraceId(), resultDO.getMetricName(), e);
            }
        }

        // 同时更新 Trace 扩展字段（可选，便于 Dashboard 快速读取）
        updateTraceExtraData(input.getTraceId(), results);

        log.info("评测完成: traceId={}, 指标数={}", input.getTraceId(), results.size());
    }

    /**
     * 将评测结果摘要写入 t_rag_trace_run.extra_data，便于 Dashboard 查询
     */
    private void updateTraceExtraData(String traceId, List<RagEvalResultDO> results) {
        if (results.isEmpty()) return;
        try {
            RagTraceRunDO traceRun = traceRunMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RagTraceRunDO>()
                            .eq(RagTraceRunDO::getTraceId, traceId));
            if (traceRun == null) return;

            // 读取现有 extraData
            String existing = StrUtil.blankToDefault(traceRun.getExtraData(), "{}");
            @SuppressWarnings("unchecked")
            Map<String, Object> extra = objectMapper.readValue(existing, Map.class);

            // 写入评测摘要
            @SuppressWarnings("unchecked")
            Map<String, Object> evalSummary = new java.util.LinkedHashMap<>();
            for (RagEvalResultDO r : results) {
                evalSummary.put(r.getMetricName(), Map.of(
                        "score", r.getScore(),
                        "label", r.getLabel()
                ));
            }
            extra.put("evaluation", evalSummary);

            traceRun.setExtraData(objectMapper.writeValueAsString(extra));
            traceRunMapper.updateById(traceRun);
        } catch (Exception e) {
            log.warn("更新 trace extraData 失败, traceId={}", traceId, e);
        }
    }

    private RagEvalResultDO toDO(RagEvalMetricResult result, EvalInput input, String evalMode) {
        String evidenceJson = null;
        if (result.getEvidence() != null && !result.getEvidence().isEmpty()) {
            try {
                evidenceJson = objectMapper.writeValueAsString(result.getEvidence());
            } catch (Exception ignored) {}
        }

        return RagEvalResultDO.builder()
                .traceId(input.getTraceId())
                .conversationId(input.getConversationId())
                .messageId(input.getMessageId())
                .question(truncate(input.getQuestion(), 4096))
                .answer(truncate(input.getAnswer(), 8192))
                .metricName(result.getMetricName())
                .score(result.getScore())
                .label(result.getLabel())
                .reason(truncate(result.getReason(), 2048))
                .evidence(evidenceJson)
                .judgeModel(result.getJudgeModel())
                .costTokens(result.getCostTokens())
                .evalMode(evalMode)
                .createTime(new Date())
                .build();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
```

### 3.4 修改 StreamChatEventHandler：投递评测事件

**修改文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java`

改动点（在 `onComplete()` 中消息落库成功后）：

```java
// 新增字段
private final MessageQueueProducer mqProducer;
private final RagEvalProperties evalProperties;
private final RagTraceProperties traceProperties;

// 在 onComplete() 中，memoryService.append() 返回 messageId 之后新增：
if (evalProperties.isEnabled()) {
    tryEvalDispatch(messageId);
}

private void tryEvalDispatch(String messageId) {
    try {
        // 抽样判断
        if (ThreadLocalRandom.current().nextDouble() >= evalProperties.getSampleRate()) {
            return;
        }
        // 从 trace 上下文中获取所需数据
        String traceId = RagTraceContext.getTraceId();
        // kbContext 需要从 pipeline 上下文获取 — 见 3.5 节
        RagEvalEvent event = RagEvalEvent.builder()
                .traceId(traceId)
                .conversationId(conversationId)
                .messageId(messageId)
                .question(originalQuestion)    // 需要保存原始问题
                .answer(fullAnswer.toString())
                .kbContext(retrievalKbContext) // 需要保存检索上下文
                .mcpContext(retrievalMcpContext)
                .build();
        mqProducer.send("rag-eval_topic${unique-name:}", messageId, "RAG在线评测", event);
    } catch (Exception e) {
        log.warn("投递评测事件失败（不影响主链路）, messageId={}", messageId, e);
    }
}
```

### 3.5 修改 StreamChatPipeline：传递检索上下文到 EventHandler

**修改文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/pipeline/StreamChatPipeline.java`

在 `streamRagResponse()` 中，将 `retrievalCtx` 的摘要写入 `StreamChatContext`，供 EventHandler 读取：

```java
// streamRagResponse 中新增：
ctx.setRetrievalKbContext(truncateContext(retrievalCtx.getKbContext(), 6000));
ctx.setRetrievalMcpContext(truncateContext(retrievalCtx.getMcpContext(), 3000));

private String truncateContext(String ctx, int maxChars) {
    if (ctx == null) return null;
    return ctx.length() <= maxChars ? ctx : ctx.substring(0, maxChars);
}
```

**修改文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/pipeline/StreamChatContext.java`

新增字段：

```java
private String retrievalKbContext;    // 裁剪后的 KB 上下文
private String retrievalMcpContext;   // 裁剪后的 MCP 上下文
```

### Story 3 验收标准

- [ ] 评测关闭时（`rag.eval.enabled=false`），主问答链路行为不变
- [ ] 评测开启后，抽样比例正确（可通过日志或落库数量验证）
- [ ] `RagEvalEvent` 成功投递到 RocketMQ，Consumer 成功消费
- [ ] 评测失败（LLM 调用异常、JSON 解析失败）时，不影响消息落库和 SSE 完成
- [ ] 评测结果正确写入 `t_rag_eval_result` 表

---

## Story 4：Dashboard 评测数据接入

### 4.1 新增评测统计 Service

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/service/EvalStatsService.java`

```java
package com.nageoffer.ai.ragent.admin.service;

import com.nageoffer.ai.ragent.admin.vo.EvalOverviewVO;
import com.nageoffer.ai.ragent.admin.vo.EvalTrendVO;
import com.nageoffer.ai.ragent.admin.vo.LowScoreSampleVO;

import java.util.List;

public interface EvalStatsService {

    /** 评测总览：各指标平均分、低分样本数、通过率 */
    EvalOverviewVO loadOverview(String window);

    /** 评测趋势：按天/小时的趋势数据 */
    List<EvalTrendVO> loadTrends(String metric, String window, String granularity);

    /** 低分样本列表 */
    List<LowScoreSampleVO> loadLowScoreSamples(String metric, int limit);
}
```

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/service/impl/EvalStatsServiceImpl.java`

核心逻辑：
- `loadOverview()`: 按 `create_time` 窗口查询 `t_rag_eval_result`，GROUP BY `metric_name`，计算 AVG(score)、COUNT(CASE WHEN label='FAIL')、COUNT(CASE WHEN label='PASS')/COUNT(*)
- `loadTrends()`: 按时间粒度 GROUP BY，画趋势线
- `loadLowScoreSamples()`: 按 label='FAIL' 且 eval_mode='ONLINE_SAMPLE' 查询，JOIN `t_message` 取完整问答对，按 score ASC 排序

### 4.2 新增 VO

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/vo/EvalOverviewVO.java`

```java
package com.nageoffer.ai.ragent.admin.vo;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class EvalOverviewVO {
    private BigDecimal avgFaithfulness;
    private BigDecimal avgAnswerRelevancy;
    private BigDecimal correctnessPassRate;   // PASS / TOTAL
    private long lowScoreCount;               // label='FAIL' 总数
    private long totalEvalCount;              // 评测总次数
    private BigDecimal lowScoreRatio;         // lowScoreCount / totalEvalCount
}
```

### 4.3 新增 Controller

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/admin/controller/EvalStatsController.java`

```java
@RestController
@RequestMapping("/admin/eval")
@RequiredArgsConstructor
public class EvalStatsController {

    private final EvalStatsService evalStatsService;

    @GetMapping("/overview")
    public Result<EvalOverviewVO> overview(@RequestParam(defaultValue = "7d") String window) { ... }

    @GetMapping("/trends")
    public Result<List<EvalTrendVO>> trends(@RequestParam String metric,
                                            @RequestParam(defaultValue = "7d") String window,
                                            @RequestParam(defaultValue = "1d") String granularity) { ... }

    @GetMapping("/low-score-samples")
    public Result<List<LowScoreSampleVO>> lowScoreSamples(
            @RequestParam(defaultValue = "faithfulness") String metric,
            @RequestParam(defaultValue = "50") int limit) { ... }
}
```

### Story 4 验收标准

- [ ] `/admin/eval/overview` 返回正确的评测总览数据
- [ ] `/admin/eval/trends` 返回按时间粒度的评测趋势
- [ ] `/admin/eval/low-score-samples` 返回低分样本列表，包含问题和答案
- [ ] 无评测数据时各接口返回空/零值，不报错
- [ ] 现有 Dashboard 接口行为不受影响

---

## Story 5：离线评测能力

### 5.1 离线评测数据集表

**SQL 追加到** `resources/database/upgrade_add_rag_eval.sql`：

```sql
CREATE TABLE IF NOT EXISTS t_rag_eval_dataset (
    id              VARCHAR(24)  NOT NULL,
    question        TEXT         NOT NULL,
    expected_answer TEXT,
    expected_kb     TEXT,                    -- 期望检索到的知识库文档ID列表
    tags            VARCHAR(256),            -- 逗号分隔标签
    difficulty      VARCHAR(16)  DEFAULT 'MEDIUM',  -- EASY / MEDIUM / HARD
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
```

### 5.2 离线评测任务 Service

**文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/eval/OfflineEvalService.java`

```java
package com.nageoffer.ai.ragent.rag.eval;

/**
 * 离线批量评测服务
 */
public interface OfflineEvalService {

    /**
     * 对指定数据集的全部样本执行评测
     * @param datasetId 数据集 ID 列表（null 表示全部）
     * @param modelId   指定回答模型 id（null 表示用当前默认）
     * @return 任务 ID，用于追踪进度
     */
    String runBatchEval(List<String> datasetIds, String modelId);

    /**
     * 对比两个版本的评测结果
     * @param baselineRunId 基线版本的评测批次 ID
     * @param candidateRunId 候选版本的评测批次 ID
     */
    EvalComparison compare(String baselineRunId, String candidateRunId);
}
```

核心流程：
1. 从 `t_rag_eval_dataset` 加载样本
2. 逐条调用 RAG 管道获取答案（复用 `RAGChatService` 或直接调 `StreamChatPipeline`）
3. 对每条答案执行全部启用指标的评测
4. 汇总结果写入 `t_rag_eval_result`（eval_mode='OFFLINE_BATCH'）

### Story 5 验收标准

- [ ] 数据集可导入和查询
- [ ] 离线评测任务可触发、可追踪进度
- [ ] 版本对比返回各指标差异值

---

## Story 6：反馈闭环联动

### 6.1 修改 MessageFeedbackServiceImpl：联动分析

**修改文件**: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/MessageFeedbackServiceImpl.java`

在 `submitFeedbackByEvent()` 中，点踩时异步触发关联查询：

```java
if (event.getVote() != null && event.getVote() < 0) {
    // 点踩 → 查询该消息的评测结果
    List<RagEvalResultDO> evalResults = evalResultMapper.selectList(
        new LambdaQueryWrapper<RagEvalResultDO>()
            .eq(RagEvalResultDO::getMessageId, event.getMessageId()));
    if (!evalResults.isEmpty()) {
        log.warn("[反馈闭环] 点踩消息存在评测结果: messageId={}, 评测摘要={}",
            event.getMessageId(),
            evalResults.stream()
                .map(r -> r.getMetricName() + "=" + r.getScore() + "(" + r.getLabel() + ")")
                .collect(Collectors.joining(", ")));
    }
}
```

### Story 6 验收标准

- [ ] 点踩事件能关联到对应评测结果并输出日志
- [ ] Dashboard 中能展示低分且点踩的重叠样本

---

## 涉及文件完整清单

### 新建文件（17 个）

| # | 文件 | Story |
|---|------|-------|
| 1 | `resources/database/upgrade_add_rag_eval.sql` | S1 |
| 2 | `bootstrap/.../rag/eval/dao/entity/RagEvalResultDO.java` | S1 |
| 3 | `bootstrap/.../rag/eval/dao/mapper/RagEvalResultMapper.java` | S1 |
| 4 | `bootstrap/.../rag/eval/config/RagEvalProperties.java` | S1 |
| 5 | `bootstrap/.../rag/eval/model/EvalInput.java` | S2 |
| 6 | `bootstrap/.../rag/eval/model/RagEvalMetricResult.java` | S2 |
| 7 | `bootstrap/.../rag/eval/client/RagJudgeClient.java` | S2 |
| 8 | `bootstrap/.../rag/eval/RagMetricEvaluator.java` | S2 |
| 9 | `bootstrap/.../rag/eval/impl/FaithfulnessEvaluator.java` | S2 |
| 10 | `bootstrap/.../rag/eval/impl/AnswerRelevancyEvaluator.java` | S2 |
| 11 | `bootstrap/.../rag/eval/impl/CorrectnessEvaluator.java` | S2 |
| 12 | `bootstrap/.../rag/eval/RagEvaluationService.java` | S3 |
| 13 | `bootstrap/.../rag/eval/impl/RagEvaluationServiceImpl.java` | S3 |
| 14 | `bootstrap/.../rag/eval/mq/event/RagEvalEvent.java` | S3 |
| 15 | `bootstrap/.../rag/eval/mq/RagEvalConsumer.java` | S3 |
| 16 | `resources/prompt/eval-faithfulness.st` | S2 |
| 17 | `resources/prompt/eval-answer-relevancy.st` | S2 |
| 18 | `resources/prompt/eval-correctness.st` | S2 |
| 19 | `bootstrap/.../admin/service/EvalStatsService.java` | S4 |
| 20 | `bootstrap/.../admin/service/impl/EvalStatsServiceImpl.java` | S4 |
| 21 | `bootstrap/.../admin/controller/EvalStatsController.java` | S4 |
| 22 | `bootstrap/.../rag/eval/OfflineEvalService.java` | S5 |

### 修改文件（6 个）

| # | 文件 | 改动摘要 | Story |
|---|------|----------|-------|
| 1 | `bootstrap/src/main/resources/application.yaml` | 新增 `rag.eval` 配置段 | S1 |
| 2 | `bootstrap/.../rag/service/pipeline/StreamChatContext.java` | 新增 `retrievalKbContext`、`retrievalMcpContext` 字段 | S3 |
| 3 | `bootstrap/.../rag/service/pipeline/StreamChatPipeline.java` | 将检索上下文写入 `StreamChatContext` | S3 |
| 4 | `bootstrap/.../rag/service/handler/StreamChatEventHandler.java` | `onComplete()` 中投递 `RagEvalEvent` | S3 |
| 5 | `bootstrap/.../rag/service/impl/MessageFeedbackServiceImpl.java` | 点踩时关联查询评测结果 | S6 |
| 6 | `bootstrap/.../rag/dao/entity/RagTraceRunDO.java` | 无需改动（extraData 已存在，JSON 足够灵活） | — |

---

## 配置开关总览

```yaml
rag:
  eval:
    enabled: false          # 总开关
    sample-rate: 0.05       # 在线抽样比例
    default-model: qwen-plus
    candidates: [...]       # judge 模型候选
    metrics:
      faithfulness: true    # 启用 faithfulness 评测
      answer-relevancy: true
      correctness: false    # 首期关闭，需要参考答案
```

---

## 风险缓解措施实现

| 风险 | 实现措施 |
|------|----------|
| 评测阻塞主链路 | MQ 异步投递 + Consumer 独立消费 |
| LLM Judge 波动 | temperature=0，固定 judge 模型版本 |
| 评测失败影响可用性 | Consumer 内 catch 所有异常，不 rethrow（避免 MQ 无限重试） |
| 上下文截断误判 | `StreamChatPipeline` 中裁剪到 6000 字符 |
| 成本失控 | `sample-rate` 控制抽样比例，首期 5% |
| Judge 调用超时 | 复用 RoutingLLMService 超时/fallback 机制 |
