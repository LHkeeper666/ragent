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
import com.nageoffer.ai.ragent.rag.eval.RagMetricEvaluator;
import com.nageoffer.ai.ragent.rag.eval.client.RagJudgeClient;
import com.nageoffer.ai.ragent.rag.eval.model.EvalInput;
import com.nageoffer.ai.ragent.rag.eval.model.RagEvalMetricResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class CorrectnessEvaluator implements RagMetricEvaluator {

    private static final String PROMPT_PATH = "prompt/eval-correctness.st";
    private static final String METRIC_NAME = "correctness";

    private final RagJudgeClient judgeClient;

    @Override
    public RagEvalMetricResult evaluate(EvalInput input) {
        // correctness 不强制依赖参考答案，通过检索上下文 + 通用知识做判断
        Map<String, String> slots = Map.of(
                "question", StrUtil.blankToDefault(input.getQuestion(), ""),
                "answer", StrUtil.blankToDefault(input.getAnswer(), ""),
                "context", buildContext(input),
                "reference_answer", StrUtil.blankToDefault(input.getReferenceAnswer(), "无参考答案，请纯粹基于知识库上下文判断答案的事实正确性")
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
