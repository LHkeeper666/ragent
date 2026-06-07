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

package com.nageoffer.ai.ragent.ingestion.mq;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.ingestion.mq.event.IngestionTaskEvent;
import com.nageoffer.ai.ragent.ingestion.service.IngestionTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "ingestion-task-low_topic${unique-name:}",
        consumerGroup = "ingestion-task-low_cg${unique-name:}",
        consumeThreadNumber = 1
)
public class IngestionTaskLowConsumer implements RocketMQListener<MessageWrapper<IngestionTaskEvent>> {

    private final IngestionTaskService taskService;

    @Override
    public void onMessage(MessageWrapper<IngestionTaskEvent> message) {
        IngestionTaskEvent event = message.getBody();
        log.info("[consumer] start low priority ingestion task, taskId={}, keys={}", event.getTaskId(), message.getKeys());
        UserContext.set(LoginUser.builder().username(event.getOperator()).build());
        try {
            taskService.executeQueued(event.getTaskId());
        } finally {
            UserContext.clear();
        }
    }
}
