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

package com.nageoffer.ai.ragent.admin.controller;

import com.nageoffer.ai.ragent.admin.controller.vo.EvalOverviewVO;
import com.nageoffer.ai.ragent.admin.controller.vo.EvalTrendPointVO;
import com.nageoffer.ai.ragent.admin.controller.vo.LowScoreSampleVO;
import com.nageoffer.ai.ragent.admin.service.EvalStatsService;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/eval")
public class EvalStatsController {

    private final EvalStatsService evalStatsService;

    @GetMapping("/overview")
    public Result<EvalOverviewVO> overview(@RequestParam(defaultValue = "7d") String window) {
        return Results.success(evalStatsService.loadOverview(window));
    }

    @GetMapping("/trends")
    public Result<List<EvalTrendPointVO>> trends(
            @RequestParam String metric,
            @RequestParam(defaultValue = "7d") String window,
            @RequestParam(defaultValue = "1d") String granularity) {
        return Results.success(evalStatsService.loadTrends(metric, window, granularity));
    }

    @GetMapping("/low-score-samples")
    public Result<List<LowScoreSampleVO>> lowScoreSamples(
            @RequestParam(defaultValue = "faithfulness") String metric,
            @RequestParam(defaultValue = "50") int limit) {
        return Results.success(evalStatsService.loadLowScoreSamples(metric, limit));
    }
}
