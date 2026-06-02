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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel.fusion;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RRF融合策略")
class RRFFusionStrategyTest {

    private final RRFFusionStrategy strategy = new RRFFusionStrategy();

    @Nested
    @DisplayName("基本融合功能")
    class BasicFusion {

        @Test
        @DisplayName("两路结果完整不重叠时应融合为总数")
        void shouldFuseTwoNonOverlappingChannels() {
            List<RetrievedChunk> vector = List.of(
                chunk("c1", "CNN基础", 0.95f),
                chunk("c2", "PyTorch安装", 0.87f),
                chunk("c3", "训练循环", 0.76f)
            );
            List<RetrievedChunk> keyword = List.of(
                chunk("c4", "GIN索引", 3.2f),
                chunk("c5", "B-tree对比", 2.8f)
            );

            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            inputs.put("vector", vector);
            inputs.put("keyword", keyword);

            List<RetrievedChunk> fused = strategy.fuse(inputs, null);

            assertEquals(5, fused.size());
            // 向量第一名排在第一位 (rank=1 → 1/61 ≈ 0.01639)
            assertEquals("c1", fused.get(0).getId());
        }

        @Test
        @DisplayName("两路完全重叠时应去重保留唯一")
        void shouldDeduplicateOverlappingChunks() {
            List<RetrievedChunk> vector = List.of(
                chunk("c1", "CNN卷积", 0.9f),
                chunk("c2", "池化层", 0.8f)
            );
            List<RetrievedChunk> keyword = List.of(
                chunk("c1", "CNN卷积", 0.9f),
                chunk("c3", "全连接层", 0.7f)
            );

            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            inputs.put("vector", vector);
            inputs.put("keyword", keyword);

            List<RetrievedChunk> fused = strategy.fuse(inputs, null);

            assertEquals(3, fused.size());
            // c1在两条通道中都排第一，RRF分数应该最高
            assertTrue(fused.get(0).getScore() > fused.get(1).getScore());
        }

        @Test
        @DisplayName("仅有一路结果时退化为单路排序")
        void shouldDegradeToSingleChannel() {
            List<RetrievedChunk> keyword = List.of(
                chunk("c1", "全文检索", 5.0f),
                chunk("c2", "中文分词", 3.0f),
                chunk("c3", "zhparser", 1.5f)
            );

            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            inputs.put("keyword", keyword);

            List<RetrievedChunk> fused = strategy.fuse(inputs, null);

            assertEquals(3, fused.size());
            assertEquals("c1", fused.get(0).getId());
        }

        @Test
        @DisplayName("空输入应返回空列表")
        void shouldReturnEmptyForEmptyInput() {
            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            List<RetrievedChunk> fused = strategy.fuse(inputs, null);
            assertTrue(fused.isEmpty());
        }

        @Test
        @DisplayName("某通道为空不影响其他通道")
        void shouldHandleEmptyChannel() {
            List<RetrievedChunk> vector = List.of(
                chunk("c1", "数据", 0.9f)
            );
            List<RetrievedChunk> keyword = List.of();

            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            inputs.put("vector", vector);
            inputs.put("keyword", keyword);

            List<RetrievedChunk> fused = strategy.fuse(inputs, null);
            assertEquals(1, fused.size());
            assertEquals("c1", fused.get(0).getId());
        }
    }

    @Nested
    @DisplayName("RRF公式验证")
    class FormulaVerification {

        @Test
        @DisplayName("RRF分数应满足 1/(K+rank+1) 公式")
        void shouldMatchRrfFormula() {
            // 单通道，3个chunk
            List<RetrievedChunk> single = List.of(
                chunk("c1", "t1", 1.0f),
                chunk("c2", "t2", 0.5f),
                chunk("c3", "t3", 0.1f)
            );

            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            inputs.put("ch1", single);

            List<RetrievedChunk> fused = strategy.fuse(inputs, null);

            // RRF score for rank 0: 1/(60+0+1) = 1/61 ≈ 0.01639
            assertEquals(1.0 / 61.0, fused.get(0).getScore(), 0.00001);
            // RRF score for rank 1: 1/(60+1+1) = 1/62 ≈ 0.01613
            assertEquals(1.0 / 62.0, fused.get(1).getScore(), 0.00001);
        }

        @Test
        @DisplayName("跨通道同名chunk的RRF分数应为各通道贡献之和")
        void shouldSumRrfScoresForSameChunk() {
            List<RetrievedChunk> vector = List.of(
                chunk("c1", "重叠文本", 0.9f)
            );
            List<RetrievedChunk> keyword = List.of(
                chunk("c1", "重叠文本", 0.9f),
                chunk("c2", "唯一文本", 0.5f)
            );

            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            inputs.put("vector", vector);
            inputs.put("keyword", keyword);

            List<RetrievedChunk> fused = strategy.fuse(inputs, null);

            // c1 score: 1/(60+0+1) + 1/(60+0+1) = 2/61 ≈ 0.03279
            double expectedC1 = 2.0 / 61.0;
            assertEquals(expectedC1, fused.get(0).getScore(), 0.00001);
            assertEquals("c1", fused.get(0).getId());
        }
    }

    @Nested
    @DisplayName("排序稳定性验证")
    class SortStability {

        @Test
        @DisplayName("结果应按RRF分数降序排列")
        void shouldSortDescendingByScore() {
            List<RetrievedChunk> vector = List.of(
                chunk("v1", "t1", 0.9f),
                chunk("v2", "t2", 0.8f),
                chunk("v3", "t3", 0.7f),
                chunk("v4", "t4", 0.6f),
                chunk("v5", "t5", 0.5f)
            );
            List<RetrievedChunk> keyword = List.of(
                chunk("k1", "t6", 3.0f),
                chunk("k2", "t7", 2.0f)
            );

            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            inputs.put("vector", vector);
            inputs.put("keyword", keyword);

            List<RetrievedChunk> fused = strategy.fuse(inputs, null);

            for (int i = 0; i < fused.size() - 1; i++) {
                assertTrue(
                    fused.get(i).getScore() >= fused.get(i + 1).getScore(),
                    "第" + i + "位的分数应 >= 第" + (i + 1) + "位"
                );
            }
        }

        @Test
        @DisplayName("chunk为null时使用hashCode作为key不抛出NPE")
        void shouldHandleNullId() {
            List<RetrievedChunk> vector = List.of(
                RetrievedChunk.builder().id(null).text("text1").score(0.9f).build()
            );

            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            inputs.put("vector", vector);

            List<RetrievedChunk> fused = strategy.fuse(inputs, null);

            assertEquals(1, fused.size());
            assertNotNull(fused.get(0).getScore());
        }
    }

    private static RetrievedChunk chunk(String id, String text, float score) {
        return RetrievedChunk.builder()
            .id(id)
            .text(text)
            .score(score)
            .build();
    }
}
