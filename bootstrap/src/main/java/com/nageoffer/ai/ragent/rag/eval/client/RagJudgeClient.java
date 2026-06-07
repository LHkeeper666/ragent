/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.eval.client;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.eval.config.RagEvalProperties;
import com.nageoffer.ai.ragent.rag.eval.model.RagEvalMetricResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagJudgeClient {

    private final LLMService llmService;
    private final PromptTemplateLoader promptLoader;
    private final RagEvalProperties evalProperties;
    private final ObjectMapper objectMapper;

    public RagEvalMetricResult judge(String promptPath, String metricName,
                                      Map<String, String> slots, String modelId) {
        String model = StrUtil.blankToDefault(modelId, evalProperties.getDefaultModel());

        String systemPrompt = promptLoader.render(promptPath, slots);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(systemPrompt)))
                .temperature(0.0)
                .thinking(false)
                .build();

        String response;
        try {
            response = llmService.chat(request, model);
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

            map.remove("score");
            map.remove("label");
            map.remove("reason");
            if (!map.isEmpty()) {
                builder.evidence(map);
            }

            return builder.build();

        } catch (Exception e) {
            log.warn("解析 judge 返回 JSON 失败, metric={}, raw={}",
                    metricName, raw.length() > 500 ? raw.substring(0, 500) : raw, e);
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
