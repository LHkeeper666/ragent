
package com.nageoffer.ai.ragent.knowledge.mq;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "knowledge-document-chunk-low_topic${unique-name:}",
        consumerGroup = "knowledge-document-chunk-low_cg${unique-name:}",
        consumeThreadNumber = 1
)
public class KnowledgeDocumentChunkLowConsumer implements RocketMQListener<MessageWrapper<KnowledgeDocumentChunkEvent>> {

    private final KnowledgeDocumentService documentService;

    @Override
    public void onMessage(MessageWrapper<KnowledgeDocumentChunkEvent> message) {
        KnowledgeDocumentChunkEvent event = message.getBody();
        log.info("[consumer] start low priority document chunk task, docId={}, keys={}", event.getDocId(), message.getKeys());
        UserContext.set(LoginUser.builder().username(event.getOperator()).build());
        try {
            documentService.executeChunk(event.getDocId());
        } finally {
            UserContext.clear();
        }
    }
}
