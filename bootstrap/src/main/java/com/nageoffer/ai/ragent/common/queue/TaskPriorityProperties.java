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

package com.nageoffer.ai.ragent.common.queue;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "rag.task-priority")
public class TaskPriorityProperties {

    @Valid
    private High high = new High();

    @Valid
    private Low low = new Low();

    @Valid
    private WaitEstimate waitEstimate = new WaitEstimate();

    @Data
    public static class High {
        @Min(0)
        private long maxFileCount = 3;

        @Min(0)
        private long maxFileSize = 1024 * 1024;
    }

    @Data
    public static class Low {
        @Min(0)
        private long minFileCount = 20;

        @Min(0)
        private long minFileSize = 100L * 1024 * 1024;
    }

    @Data
    public static class WaitEstimate {
        @Min(1)
        private long highSecondsPerTask = 5;

        @Min(1)
        private long mediumSecondsPerTask = 30;

        @Min(1)
        private long lowSecondsPerTask = 120;
    }
}
