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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 联网搜索配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.websearch")
public class WebSearchProperties {

    /**
     * 是否启用联网搜索增强回答
     */
    private boolean enabled = false;

    /**
     * 搜索引擎：duckduckgo / serpapi / bing
     */
    private String provider = "duckduckgo";

    /**
     * API Key（SerpAPI / Bing 需要）
     */
    private String apiKey;

    /**
     * 最大返回结果数
     */
    private int maxResults = 5;
}
