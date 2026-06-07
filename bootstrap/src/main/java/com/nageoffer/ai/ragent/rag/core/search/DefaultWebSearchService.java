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

package com.nageoffer.ai.ragent.rag.core.search;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.rag.config.WebSearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 联网搜索默认实现
 * 支持 Tavily Search API（推荐）和 DuckDuckGo Instant Answer API（免费兜底）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultWebSearchService implements WebSearchService {

    private static final String TAVILY_API_URL = "https://api.tavily.com/search";

    private final WebSearchProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public List<WebSearchResult> search(String query, int maxResults) {
        String provider = properties.getProvider();
        String apiKey = properties.getApiKey();

        if ("tavily".equalsIgnoreCase(provider)) {
            if (apiKey != null && !apiKey.isBlank()) {
                return searchTavily(query, maxResults, apiKey);
            }
            log.info("Tavily 未配置 apiKey，降级使用 DuckDuckGo");
            return searchDuckDuckGo(query, maxResults);
        }

        if ("duckduckgo".equalsIgnoreCase(provider)) {
            return searchDuckDuckGo(query, maxResults);
        }

        log.warn("不支持的搜索引擎: {}", provider);
        return Collections.emptyList();
    }

    private List<WebSearchResult> searchTavily(String query, int maxResults, String apiKey) {
        try {
            JSONObject body = JSONUtil.createObj();
            body.set("api_key", apiKey);
            body.set("query", query);
            body.set("search_depth", "basic");
            body.set("max_results", Math.min(maxResults, 10));
            body.set("include_answer", true);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TAVILY_API_URL))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Tavily 搜索失败，HTTP {}: {}", response.statusCode(), response.body());
                return Collections.emptyList();
            }
            return parseTavilyResponse(response.body(), maxResults);
        } catch (Exception e) {
            log.error("Tavily 搜索异常", e);
            return Collections.emptyList();
        }
    }

    private List<WebSearchResult> parseTavilyResponse(String body, int maxResults) {
        JSONObject json = JSONUtil.parseObj(body);
        List<WebSearchResult> results = new ArrayList<>();

        // answer: Tavily 的总结性回答
        String answer = json.getStr("answer");
        if (answer != null && !answer.isBlank()) {
            results.add(new WebSearchResult("AI 总结", "", answer));
        }

        // results: 详细搜索结果
        JSONArray items = json.getJSONArray("results");
        if (items != null && !items.isEmpty()) {
            for (int i = 0; i < items.size() && results.size() < maxResults + 1; i++) {
                JSONObject item = items.getJSONObject(i);
                String title = item.getStr("title");
                String url = item.getStr("url");
                String content = item.getStr("content");
                if (title != null && content != null) {
                    results.add(new WebSearchResult(title, url != null ? url : "", content));
                }
            }
        }

        return results;
    }

    private List<WebSearchResult> searchDuckDuckGo(String query, int maxResults) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://api.duckduckgo.com/?q=" + encoded + "&format=json&no_html=1&skip_disambig=1";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("DuckDuckGo 搜索失败，HTTP {}", response.statusCode());
                return Collections.emptyList();
            }
            return parseDuckDuckGoResponse(response.body(), maxResults);
        } catch (Exception e) {
            log.error("联网搜索异常", e);
            return Collections.emptyList();
        }
    }

    private List<WebSearchResult> parseDuckDuckGoResponse(String body, int maxResults) {
        JSONObject json = JSONUtil.parseObj(body);
        List<WebSearchResult> results = new ArrayList<>();

        // AbstractText: DuckDuckGo 的即时答案摘要
        String abstractText = json.getStr("AbstractText");
        String abstractUrl = json.getStr("AbstractURL");
        if (abstractText != null && !abstractText.isBlank()) {
            results.add(new WebSearchResult(
                    json.getStr("Heading", ""),
                    abstractUrl != null ? abstractUrl : "",
                    abstractText
            ));
        }

        // RelatedTopics: 相关主题
        JSONArray relatedTopics = json.getJSONArray("RelatedTopics");
        if (relatedTopics != null && !relatedTopics.isEmpty()) {
            for (int i = 0; i < relatedTopics.size() && results.size() < maxResults; i++) {
                JSONObject topic = relatedTopics.getJSONObject(i);
                String text = topic.getStr("Text");
                String firstUrl = topic.getStr("FirstURL");
                if (text != null && !text.isBlank()) {
                    // RelatedTopics 的 Text 格式为 "描述 — 来源"
                    String[] parts = text.split(" — ", 2);
                    String snippet = parts.length > 0 ? parts[0].trim() : text;
                    String title = parts.length > 1 ? parts[1].trim() : "";
                    results.add(new WebSearchResult(title, firstUrl != null ? firstUrl : "", snippet));
                }
            }
        }

        return results;
    }
}
