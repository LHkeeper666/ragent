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

package com.nageoffer.ai.ragent.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.admin.controller.vo.EvalOverviewVO;
import com.nageoffer.ai.ragent.admin.controller.vo.EvalTrendPointVO;
import com.nageoffer.ai.ragent.admin.controller.vo.LowScoreSampleVO;
import com.nageoffer.ai.ragent.admin.service.EvalStatsService;
import com.nageoffer.ai.ragent.rag.eval.dao.entity.RagEvalResultDO;
import com.nageoffer.ai.ragent.rag.eval.dao.mapper.RagEvalResultMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvalStatsServiceImpl implements EvalStatsService {

    private final RagEvalResultMapper evalResultMapper;

    @Override
    public EvalOverviewVO loadOverview(String window) {
        List<RagEvalResultDO> all = queryByWindow(window);

        Map<String, List<RagEvalResultDO>> byMetric = all.stream()
                .collect(Collectors.groupingBy(RagEvalResultDO::getMetricName));

        BigDecimal avgFaith = avgScore(byMetric.get("faithfulness"));
        BigDecimal avgRelevancy = avgScore(byMetric.get("answer_relevancy"));
        BigDecimal avgCorrectness = avgScore(byMetric.get("correctness"));

        long lowScoreCount = all.stream()
                .filter(r -> "FAIL".equals(r.getLabel()))
                .count();
        long totalCount = all.size();
        BigDecimal lowScoreRatio = totalCount > 0
                ? BigDecimal.valueOf(lowScoreCount).divide(BigDecimal.valueOf(totalCount), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return EvalOverviewVO.builder()
                .avgFaithfulness(avgFaith)
                .avgAnswerRelevancy(avgRelevancy)
                .avgCorrectness(avgCorrectness)
                .lowScoreCount(lowScoreCount)
                .totalEvalCount(totalCount)
                .lowScoreRatio(lowScoreRatio)
                .build();
    }

    @Override
    public List<EvalTrendPointVO> loadTrends(String metric, String window, String granularity) {
        List<RagEvalResultDO> all = queryByWindowAndMetric(window, metric);
        if (all.isEmpty()) {
            return List.of();
        }

        DateTimeFormatter fmt = "1h".equals(granularity)
                ? DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00")
                : DateTimeFormatter.ofPattern("yyyy-MM-dd");

        Map<String, List<RagEvalResultDO>> byBucket = all.stream()
                .collect(Collectors.groupingBy(r -> {
                    LocalDateTime t = r.getCreateTime().toInstant()
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
                    return t.format(fmt);
                }));

        List<EvalTrendPointVO> result = new ArrayList<>();
        for (Map.Entry<String, List<RagEvalResultDO>> entry : byBucket.entrySet()) {
            List<RagEvalResultDO> bucket = entry.getValue();
            result.add(EvalTrendPointVO.builder()
                    .time(entry.getKey())
                    .avgScore(avgScore(bucket))
                    .totalCount((long) bucket.size())
                    .failCount(bucket.stream().filter(r -> "FAIL".equals(r.getLabel())).count())
                    .build());
        }
        result.sort(java.util.Comparator.comparing(EvalTrendPointVO::getTime));
        return result;
    }

    @Override
    public List<LowScoreSampleVO> loadLowScoreSamples(String metric, int limit) {
        LambdaQueryWrapper<RagEvalResultDO> wrapper = new LambdaQueryWrapper<RagEvalResultDO>()
                .eq(RagEvalResultDO::getMetricName, metric)
                .eq(RagEvalResultDO::getLabel, "FAIL")
                .eq(RagEvalResultDO::getEvalMode, "ONLINE_SAMPLE")
                .orderByAsc(RagEvalResultDO::getScore)
                .last("LIMIT " + limit);

        return evalResultMapper.selectList(wrapper).stream()
                .map(r -> LowScoreSampleVO.builder()
                        .id(r.getId())
                        .traceId(r.getTraceId())
                        .conversationId(r.getConversationId())
                        .messageId(r.getMessageId())
                        .question(truncate(r.getQuestion(), 500))
                        .answer(truncate(r.getAnswer(), 1000))
                        .metricName(r.getMetricName())
                        .score(r.getScore())
                        .label(r.getLabel())
                        .reason(r.getReason())
                        .createTime(r.getCreateTime())
                        .build())
                .collect(Collectors.toList());
    }

    private List<RagEvalResultDO> queryByWindow(String window) {
        LambdaQueryWrapper<RagEvalResultDO> wrapper = new LambdaQueryWrapper<RagEvalResultDO>()
                .eq(RagEvalResultDO::getEvalMode, "ONLINE_SAMPLE");
        applyWindow(wrapper, window);
        return evalResultMapper.selectList(wrapper);
    }

    private List<RagEvalResultDO> queryByWindowAndMetric(String window, String metric) {
        LambdaQueryWrapper<RagEvalResultDO> wrapper = new LambdaQueryWrapper<RagEvalResultDO>()
                .eq(RagEvalResultDO::getEvalMode, "ONLINE_SAMPLE")
                .eq(RagEvalResultDO::getMetricName, metric);
        applyWindow(wrapper, window);
        return evalResultMapper.selectList(wrapper);
    }

    private void applyWindow(LambdaQueryWrapper<RagEvalResultDO> wrapper, String window) {
        if (window == null || window.isBlank()) {
            return;
        }
        LocalDateTime since = resolveWindowStart(window);
        if (since != null) {
            wrapper.ge(RagEvalResultDO::getCreateTime,
                    java.sql.Timestamp.valueOf(since));
        }
    }

    private LocalDateTime resolveWindowStart(String window) {
        return switch (window) {
            case "24h" -> LocalDateTime.now().minusHours(24);
            case "7d" -> LocalDateTime.now().minusDays(7);
            case "30d" -> LocalDateTime.now().minusDays(30);
            case "90d" -> LocalDateTime.now().minusDays(90);
            default -> null;
        };
    }

    private BigDecimal avgScore(List<RagEvalResultDO> list) {
        if (list == null || list.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (RagEvalResultDO r : list) {
            if (r.getScore() != null) {
                sum = sum.add(r.getScore());
                count++;
            }
        }
        return count > 0 ? sum.divide(BigDecimal.valueOf(count), 3, RoundingMode.HALF_UP) : null;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
