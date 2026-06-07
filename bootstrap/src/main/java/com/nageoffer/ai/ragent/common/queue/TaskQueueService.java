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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
@RequiredArgsConstructor
public class TaskQueueService {

    private final TaskPriorityProperties properties;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final IngestionTaskMapper ingestionTaskMapper;

    public TaskPriority evaluatePriority(Long fileSize) {
        long pendingCount = countPendingWork();
        long size = fileSize == null ? 0 : fileSize;
        if (pendingCount >= properties.getLow().getMinFileCount()
                || size >= properties.getLow().getMinFileSize()) {
            return TaskPriority.LOW;
        }
        if (pendingCount <= properties.getHigh().getMaxFileCount()
                && size <= properties.getHigh().getMaxFileSize()) {
            return TaskPriority.HIGH;
        }
        return TaskPriority.MEDIUM;
    }

    public long countPendingWork() {
        return knowledgeDocumentMapper.countQueueBacklog() + ingestionTaskMapper.countQueueBacklog();
    }

    public Integer knowledgeQueuePosition(String priority, Date queuedAt, String id) {
        if (priority == null || queuedAt == null || id == null) {
            return null;
        }
        Long position = knowledgeDocumentMapper.queuePosition(priority, queuedAt, id);
        return position == null ? null : Math.toIntExact(position);
    }

    public Integer ingestionQueuePosition(String priority, Date queuedAt, String id) {
        if (priority == null || queuedAt == null || id == null) {
            return null;
        }
        Long position = ingestionTaskMapper.queuePosition(priority, queuedAt, id);
        return position == null ? null : Math.toIntExact(position);
    }

    public Long estimateWaitSeconds(String priority, Integer queuePosition) {
        if (priority == null || queuePosition == null || queuePosition <= 0) {
            return null;
        }
        TaskPriority taskPriority = TaskPriority.fromValue(priority);
        long secondsPerTask = switch (taskPriority) {
            case HIGH -> properties.getWaitEstimate().getHighSecondsPerTask();
            case MEDIUM -> properties.getWaitEstimate().getMediumSecondsPerTask();
            case LOW -> properties.getWaitEstimate().getLowSecondsPerTask();
        };
        return Math.max(0, queuePosition - 1L) * secondsPerTask;
    }
}
