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

package com.nageoffer.ai.ragent.rag.eval.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "rag.eval")
public class RagEvalProperties {

    private boolean enabled = false;

    private double sampleRate = 0.05;

    private String defaultModel = "qwen-plus";

    private String topic = "rag-eval_topic";

    private List<JudgeCandidate> candidates = new ArrayList<>();

    private MetricsConfig metrics = new MetricsConfig();

    @Data
    public static class MetricsConfig {
        private boolean faithfulness = true;
        private boolean answerRelevancy = true;
        private boolean correctness = true;
    }

    @Data
    public static class JudgeCandidate {
        private String id;
        private String provider;
        private String model;
        private Integer priority = 100;
    }
}
