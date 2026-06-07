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

package com.nageoffer.ai.ragent.rag.eval;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class EvalPendingStore {

    private final ConcurrentHashMap<String, PendingData> store = new ConcurrentHashMap<>();

    public void put(String taskId, PendingData data) {
        store.put(taskId, data);
    }

    public PendingData remove(String taskId) {
        return store.remove(taskId);
    }

    @Data
    @AllArgsConstructor
    public static class PendingData {
        private String traceId;
        private String question;
        private String kbContext;
        private String mcpContext;
    }
}
