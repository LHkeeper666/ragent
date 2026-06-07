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

import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Date;

class TaskQueueServiceTest {

    private final KnowledgeDocumentMapper knowledgeDocumentMapper = Mockito.mock(KnowledgeDocumentMapper.class);
    private final IngestionTaskMapper ingestionTaskMapper = Mockito.mock(IngestionTaskMapper.class);
    private final TaskPriorityProperties properties = new TaskPriorityProperties();
    private final TaskQueueService service = new TaskQueueService(
            properties,
            knowledgeDocumentMapper,
            ingestionTaskMapper);

    @Test
    void evaluatesHighPriorityForSmallFileWhenBacklogIsSmall() {
        Mockito.when(knowledgeDocumentMapper.countQueueBacklog()).thenReturn(1L);
        Mockito.when(ingestionTaskMapper.countQueueBacklog()).thenReturn(1L);

        TaskPriority priority = service.evaluatePriority(512L * 1024);

        Assertions.assertEquals(TaskPriority.HIGH, priority);
    }

    @Test
    void evaluatesLowPriorityForLargeFile() {
        Mockito.when(knowledgeDocumentMapper.countQueueBacklog()).thenReturn(0L);
        Mockito.when(ingestionTaskMapper.countQueueBacklog()).thenReturn(0L);

        TaskPriority priority = service.evaluatePriority(150L * 1024 * 1024);

        Assertions.assertEquals(TaskPriority.LOW, priority);
    }

    @Test
    void evaluatesLowPriorityForLargeBacklog() {
        Mockito.when(knowledgeDocumentMapper.countQueueBacklog()).thenReturn(10L);
        Mockito.when(ingestionTaskMapper.countQueueBacklog()).thenReturn(10L);

        TaskPriority priority = service.evaluatePriority(512L * 1024);

        Assertions.assertEquals(TaskPriority.LOW, priority);
    }

    @Test
    void evaluatesMediumPriorityForNormalWork() {
        Mockito.when(knowledgeDocumentMapper.countQueueBacklog()).thenReturn(4L);
        Mockito.when(ingestionTaskMapper.countQueueBacklog()).thenReturn(0L);

        TaskPriority priority = service.evaluatePriority(2L * 1024 * 1024);

        Assertions.assertEquals(TaskPriority.MEDIUM, priority);
    }

    @Test
    void delegatesQueuePositionAndEstimatesWaitSeconds() {
        Date queuedAt = new Date();
        Mockito.when(ingestionTaskMapper.queuePosition("medium", queuedAt, "1")).thenReturn(3L);

        Integer position = service.ingestionQueuePosition("medium", queuedAt, "1");
        Long waitSeconds = service.estimateWaitSeconds("medium", position);

        Assertions.assertEquals(3, position);
        Assertions.assertEquals(60L, waitSeconds);
    }
}
