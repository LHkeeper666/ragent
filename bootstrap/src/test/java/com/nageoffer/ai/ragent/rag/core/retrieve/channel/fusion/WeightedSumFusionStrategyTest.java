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

@DisplayName("加权求和融合策略")
class WeightedSumFusionStrategyTest {

    private final WeightedSumFusionStrategy strategy = new WeightedSumFusionStrategy();

    @Nested
    @DisplayName("基本融合功能")
    class BasicFusion {

        @Test
        @DisplayName("两路等权重融合应综合排序")
        void shouldFuseWithEqualWeights() {
            List<RetrievedChunk> vector = List.of(
                chunk("c1", "语义匹配", 0.95f),
                chunk("c2", "相关内容", 0.80f)
            );
            List<RetrievedChunk> keyword = List.of(
                chunk("c3", "关键词命中", 0.90f),
                chunk("c4", "全文匹配", 0.60f)
            );

            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            inputs.put("vector", vector);
            inputs.put("keyword", keyword);

            Map<String, Float> weights = Map.of("vector", 0.5f, "keyword", 0.5f);

            List<RetrievedChunk> fused = strategy.fuse(inputs, weights);

            assertEquals(4, fused.size());
            // 归一化后加权排序: c1归一化=1.0*0.5 + c3归一化=1.0*0.5
            // 排序稳定
            assertNotNull(fused.get(0).getScore());
        }

        @Test
        @DisplayName("权重0.9:0.1时向量应主导排序")
        void shouldLetVectorDominate() {
            List<RetrievedChunk> vector = List.of(
                chunk("v1", "向量第一", 0.9f),
                chunk("v2", "向量第二", 0.5f)
            );
            List<RetrievedChunk> keyword = List.of(
                chunk("k1", "关键词高分", 100.0f)
            );

            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            inputs.put("vector", vector);
            inputs.put("keyword", keyword);

            Map<String, Float> weights = Map.of("vector", 0.9f, "keyword", 0.1f);

            List<RetrievedChunk> fused = strategy.fuse(inputs, weights);

            // v1归一化=1.0*0.9=0.9, k1归一化=1.0*0.1=0.1
            assertEquals("v1", fused.get(0).getId());
        }

        @Test
        @DisplayName("权重0.0:1.0时退化为纯关键词通道")
        void shouldDegradeToPureKeyword() {
            List<RetrievedChunk> vector = List.of(
                chunk("v1", "向量", 0.9f)
            );
            List<RetrievedChunk> keyword = List.of(
                chunk("k1", "关键词A", 100.0f),
                chunk("k2", "关键词B", 50.0f)
            );

            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            inputs.put("vector", vector);
            inputs.put("keyword", keyword);

            Map<String, Float> weights = Map.of("vector", 0.0f, "keyword", 1.0f);

            List<RetrievedChunk> fused = strategy.fuse(inputs, weights);

            assertEquals(3, fused.size());
            assertEquals("k1", fused.get(0).getId());
            assertEquals("k2", fused.get(1).getId());
        }
    }

    @Nested
    @DisplayName("归一化验证")
    class NormalizationVerification {

        @Test
        @DisplayName("min-max归一化后分数应在[0,1]范围")
        void shouldNormalizeToZeroOne() {
            List<RetrievedChunk> vector = List.of(
                chunk("v1", "高", 0.95f),
                chunk("v2", "低", 0.20f)
            );

            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            inputs.put("vector", vector);

            Map<String, Float> weights = Map.of("vector", 1.0f);

            List<RetrievedChunk> fused = strategy.fuse(inputs, weights);

            // v1归一化=(0.95-0.20)/(0.95-0.20)=1.0
            assertEquals(1.0f, fused.get(0).getScore(), 0.0001f);
            // v2归一化=(0.20-0.20)/(0.95-0.20)=0.0
            assertEquals(0.0f, fused.get(1).getScore(), 0.0001f);
        }

        @Test
        @DisplayName("所有分数相同时归一化不除零")
        void shouldHandleAllSameScores() {
            List<RetrievedChunk> vector = List.of(
                chunk("v1", "相同", 0.5f),
                chunk("v2", "相同", 0.5f)
            );

            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            inputs.put("vector", vector);

            Map<String, Float> weights = Map.of("vector", 1.0f);

            List<RetrievedChunk> fused = strategy.fuse(inputs, weights);

            assertEquals(2, fused.size());
            // range=0 → 设为1.0, 归一化: (0.5-0.5)/1.0=0.0
            assertEquals(0.0f, fused.get(0).getScore(), 0.01f);
        }

        @Test
        @DisplayName("单个chunk的归一化应正常工作")
        void shouldHandleSingleChunk() {
            List<RetrievedChunk> vector = List.of(
                chunk("v1", "唯一", 0.99f)
            );

            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            inputs.put("vector", vector);

            Map<String, Float> weights = Map.of("vector", 1.0f);

            List<RetrievedChunk> fused = strategy.fuse(inputs, weights);

            assertEquals(1, fused.size());
            assertEquals(0.0f, fused.get(0).getScore(), 0.0001f);
        }
    }

    @Nested
    @DisplayName("边界条件")
    class EdgeCases {

        @Test
        @DisplayName("空输入应返回空列表")
        void shouldReturnEmptyForEmptyInput() {
            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            List<RetrievedChunk> fused = strategy.fuse(inputs, null);
            assertTrue(fused.isEmpty());
        }

        @Test
        @DisplayName("null权重应使用默认1.0")
        void shouldUseDefaultWeightForNull() {
            List<RetrievedChunk> vector = List.of(
                chunk("v1", "测试", 0.9f)
            );

            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            inputs.put("vector", vector);

            // null weights → weight defaults to 1.0
            List<RetrievedChunk> fused = strategy.fuse(inputs, null);

            assertEquals(1, fused.size());
            assertNotNull(fused.get(0).getScore());
        }

        @Test
        @DisplayName("重叠chunk应进行加权累加")
        void shouldSumWeightsForOverlapping() {
            List<RetrievedChunk> vector = List.of(
                chunk("c1", "重叠内容", 0.9f)
            );
            List<RetrievedChunk> keyword = List.of(
                chunk("c1", "重叠内容", 0.9f),
                chunk("c2", "仅关键词", 0.5f)
            );

            Map<String, List<RetrievedChunk>> inputs = new LinkedHashMap<>();
            inputs.put("vector", vector);
            inputs.put("keyword", keyword);

            Map<String, Float> weights = Map.of("vector", 0.7f, "keyword", 0.3f);

            List<RetrievedChunk> fused = strategy.fuse(inputs, weights);

            // c1出现在两路中，归一化后加权求和应排在前面
            assertEquals("c1", fused.get(0).getId());
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
