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
        topic = "ingestion-task-high_topic${unique-name:}",
        consumerGroup = "ingestion-task-high_cg${unique-name:}",
        consumeThreadNumber = 8
)
public class IngestionTaskHighConsumer implements RocketMQListener<MessageWrapper<IngestionTaskEvent>> {

    private final IngestionTaskService taskService;

    @Override
    public void onMessage(MessageWrapper<IngestionTaskEvent> message) {
        IngestionTaskEvent event = message.getBody();
        log.info("[consumer] start high priority ingestion task, taskId={}, keys={}", event.getTaskId(), message.getKeys());
        UserContext.set(LoginUser.builder().username(event.getOperator()).build());
        try {
            taskService.executeQueued(event.getTaskId());
        } finally {
            UserContext.clear();
        }
    }
}
