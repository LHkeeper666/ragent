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

package com.nageoffer.ai.ragent.rag.eval.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_rag_eval_result")
public class RagEvalResultDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String traceId;
    private String conversationId;
    private String messageId;
    private String question;
    private String answer;
    private String metricName;
    private BigDecimal score;
    private String label;
    private String reason;
    @TableField(typeHandler = com.nageoffer.ai.ragent.knowledge.dao.handler.JsonbTypeHandler.class)
    private String evidence;
    private String judgeModel;
    private String judgeVersion;
    private Integer costTokens;
    private String evalMode;
    private Date createTime;
}
