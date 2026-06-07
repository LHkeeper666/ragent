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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
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

        if (evalProperties.getMetrics().isFaithfulness()) {
            RagMetricEvaluator e = evaluators.get("faithfulnessEvaluator");
            if (e != null) {
                results.add(toDO(e.evaluate(input), input, "ONLINE_SAMPLE"));
            }
        }
        if (evalProperties.getMetrics().isAnswerRelevancy()) {
            RagMetricEvaluator e = evaluators.get("answerRelevancyEvaluator");
            if (e != null) {
                results.add(toDO(e.evaluate(input), input, "ONLINE_SAMPLE"));
            }
        }
        if (evalProperties.getMetrics().isCorrectness()) {
            RagMetricEvaluator e = evaluators.get("correctnessEvaluator");
            if (e != null) {
                results.add(toDO(e.evaluate(input), input, "ONLINE_SAMPLE"));
            }
        }

        for (RagEvalResultDO resultDO : results) {
            try {
                evalResultMapper.insert(resultDO);
            } catch (Exception ex) {
                log.error("评测结果落库失败, traceId={}, metric={}",
                        resultDO.getTraceId(), resultDO.getMetricName(), ex);
            }
        }

        updateTraceExtraData(input.getTraceId(), results);

        log.info("评测完成: traceId={}, 指标数={}", input.getTraceId(), results.size());
    }

    private void updateTraceExtraData(String traceId, List<RagEvalResultDO> results) {
        if (results.isEmpty()) return;
        try {
            RagTraceRunDO traceRun = traceRunMapper.selectOne(
                    new LambdaQueryWrapper<RagTraceRunDO>()
                            .eq(RagTraceRunDO::getTraceId, traceId));
            if (traceRun == null) return;

            String existing = StrUtil.blankToDefault(traceRun.getExtraData(), "{}");
            @SuppressWarnings("unchecked")
            Map<String, Object> extra = objectMapper.readValue(existing, Map.class);

            Map<String, Object> evalSummary = new LinkedHashMap<>();
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
