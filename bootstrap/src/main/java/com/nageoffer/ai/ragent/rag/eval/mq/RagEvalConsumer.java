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

package com.nageoffer.ai.ragent.rag.eval.mq;

import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.rag.eval.RagEvaluationService;
import com.nageoffer.ai.ragent.rag.eval.mq.event.RagEvalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.eval", name = "enabled", havingValue = "true")
@RocketMQMessageListener(
    topic = "rag-eval_topic${unique-name:}",
    consumerGroup = "rag-eval_cg${unique-name:}"
)
public class RagEvalConsumer implements RocketMQListener<MessageWrapper<RagEvalEvent>> {

    private final RagEvaluationService evaluationService;

    @Override
    public void onMessage(MessageWrapper<RagEvalEvent> message) {
        RagEvalEvent event = message.getBody();
        log.info("[评测消费者] 收到评测事件, traceId={}, messageId={}",
                event.getTraceId(), event.getMessageId());
        try {
            evaluationService.evaluate(event);
        } catch (Exception e) {
            log.error("[评测消费者] 评测失败, traceId={}, messageId={}",
                    event.getTraceId(), event.getMessageId(), e);
        }
    }
}
