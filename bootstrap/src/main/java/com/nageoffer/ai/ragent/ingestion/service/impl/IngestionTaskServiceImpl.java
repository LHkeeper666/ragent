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

package com.nageoffer.ai.ragent.ingestion.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.common.queue.QueueStatus;
import com.nageoffer.ai.ragent.common.queue.TaskPriority;
import com.nageoffer.ai.ragent.common.queue.TaskQueueService;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.rag.controller.request.DocumentSourceRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionTaskCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskNodeVO;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskVO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskDO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskNodeDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskNodeMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.context.NodeLog;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.domain.result.IngestionResult;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.mq.event.IngestionTaskEvent;
import com.nageoffer.ai.ragent.ingestion.util.MimeTypeDetector;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.ingestion.service.IngestionTaskService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据摄入任务服务实现
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IngestionTaskServiceImpl implements IngestionTaskService {

    private final IngestionEngine engine;
    private final IngestionPipelineService pipelineService;
    private final IngestionTaskMapper taskMapper;
    private final IngestionTaskNodeMapper taskNodeMapper;
    private final ObjectMapper objectMapper;
    private final MessageQueueProducer messageQueueProducer;
    private final TaskQueueService taskQueueService;
    private final FileStorageService fileStorageService;
    private final S3Client s3Client;

    @Value("ingestion-task-high_topic${unique-name:}")
    private String ingestionHighTopic;

    @Value("ingestion-task-medium_topic${unique-name:}")
    private String ingestionMediumTopic;

    @Value("ingestion-task-low_topic${unique-name:}")
    private String ingestionLowTopic;

    @Value("${rag.ingestion.bucket-name:rag-ingestion}")
    private String ingestionBucketName;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestionResult execute(IngestionTaskCreateRequest request) {
        Assert.notNull(request, () -> new ClientException("请求不能为空"));
        DocumentSource source = toSource(request.getSource());
        return enqueueInternal(request.getPipelineId(), source, null, null, null,
                request.getMetadata(), request.getVectorSpaceId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestionResult upload(String pipelineId, MultipartFile file) {
        Assert.notNull(file, () -> new ClientException("文件不能为空"));
        try {
            ensureIngestionBucket();
            String fileName = file.getOriginalFilename();
            if (!StringUtils.hasText(fileName)) {
                fileName = "upload.bin";
            }
            StoredFileDTO stored = fileStorageService.upload(ingestionBucketName, file);
            DocumentSource source = DocumentSource.builder()
                    .type(SourceType.FILE)
                    .location(stored.getUrl())
                    .fileName(fileName)
                    .build();
            return enqueueInternal(pipelineId, source, stored.getUrl(), stored.getSize(),
                    stored.getDetectedType(), null, null);
        } catch (Exception e) {
            throw new ClientException("读取上传文件失败: " + e.getMessage());
        }
    }

    @Override
    public IngestionTaskVO get(String taskId) {
        IngestionTaskDO task = taskMapper.selectById(taskId);
        Assert.notNull(task, () -> new ClientException("未找到任务"));
        return toVO(task);
    }

    @Override
    public IPage<IngestionTaskVO> page(Page<IngestionTaskVO> page, String status, String priority) {
        Page<IngestionTaskDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        String normalizedStatus = normalizeStatus(status);
        LambdaQueryWrapper<IngestionTaskDO> qw = new LambdaQueryWrapper<IngestionTaskDO>()
                .eq(IngestionTaskDO::getDeleted, 0)
                .eq(StringUtils.hasText(normalizedStatus), IngestionTaskDO::getStatus, normalizedStatus)
                .eq(StringUtils.hasText(priority), IngestionTaskDO::getPriority, priority)
                .orderByDesc(IngestionTaskDO::getCreateTime);
        IPage<IngestionTaskDO> result = taskMapper.selectPage(mpPage, qw);
        Page<IngestionTaskVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    @Override
    public List<IngestionTaskNodeVO> listNodes(String taskId) {
        LambdaQueryWrapper<IngestionTaskNodeDO> qw = new LambdaQueryWrapper<IngestionTaskNodeDO>()
                .eq(IngestionTaskNodeDO::getDeleted, 0)
                .eq(IngestionTaskNodeDO::getTaskId, taskId)
                .orderByAsc(IngestionTaskNodeDO::getNodeOrder)
                .orderByAsc(IngestionTaskNodeDO::getId);
        List<IngestionTaskNodeDO> nodes = taskNodeMapper.selectList(qw);
        return nodes.stream().map(this::toNodeVO).toList();
    }

    @Override
    public void executeQueued(String taskId) {
        int claimed = taskMapper.update(
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<IngestionTaskDO>()
                        .set(IngestionTaskDO::getStatus, IngestionStatus.RUNNING.getValue())
                        .set(IngestionTaskDO::getQueueStatus, QueueStatus.RUNNING.getValue())
                        .set(IngestionTaskDO::getQueueStartedAt, new Date())
                        .set(IngestionTaskDO::getStartedAt, new Date())
                        .eq(IngestionTaskDO::getId, taskId)
                        .eq(IngestionTaskDO::getStatus, IngestionStatus.PENDING.getValue())
                        .eq(IngestionTaskDO::getQueueStatus, QueueStatus.QUEUED.getValue())
        );
        if (claimed == 0) {
            IngestionTaskDO current = taskMapper.selectById(taskId);
            if (current == null) {
                return;
            }
            if (!IngestionStatus.RUNNING.getValue().equals(current.getStatus())) {
                return;
            }
        }

        IngestionTaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        PipelineDefinition pipeline = pipelineService.getDefinition(task.getPipelineId());
        Map<String, Object> metadata = readMap(task.getMetadataJson());
        DocumentSource source = DocumentSource.builder()
                .type(SourceType.fromValue(task.getSourceType()))
                .location(task.getSourceLocation())
                .fileName(task.getSourceFileName())
                .credentials(readCredentials(metadata))
                .build();

        byte[] rawBytes = null;
        if (StringUtils.hasText(task.getFileUrl())) {
            try (InputStream inputStream = fileStorageService.openStream(task.getFileUrl())) {
                rawBytes = inputStream.readAllBytes();
            } catch (Exception e) {
                task.setStatus(IngestionStatus.FAILED.getValue());
                task.setQueueStatus(QueueStatus.FAILED.getValue());
                task.setErrorMessage(e.getMessage());
                task.setCompletedAt(new Date());
                task.setUpdatedBy(UserContext.getUsername());
                taskMapper.updateById(task);
                throw new ClientException("Read queued ingestion file failed: " + e.getMessage());
            }
        }

        VectorSpaceId vectorSpaceId = readVectorSpaceId(metadata);
        IngestionContext context = IngestionContext.builder()
                .taskId(String.valueOf(task.getId()))
                .pipelineId(task.getPipelineId())
                .source(source)
                .rawBytes(rawBytes)
                .mimeType(task.getMimeType())
                .vectorSpaceId(vectorSpaceId)
                .logs(new ArrayList<>())
                .build();

        try {
            IngestionContext result = engine.execute(pipeline, context);
            saveNodeLogs(task, pipeline, result.getLogs());
            updateTaskFromContext(task, result);
        } catch (Exception e) {
            task.setStatus(IngestionStatus.FAILED.getValue());
            task.setQueueStatus(QueueStatus.FAILED.getValue());
            task.setErrorMessage(e.getMessage());
            task.setCompletedAt(new Date());
            task.setUpdatedBy(UserContext.getUsername());
            taskMapper.updateById(task);
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(e);
        }
    }

    private IngestionResult enqueueInternal(String pipelineId,
                                            DocumentSource source,
                                            String fileUrl,
                                            Long fileSize,
                                            String mimeType,
                                            Map<String, Object> metadata,
                                            VectorSpaceId vectorSpaceId) {
        String resolvedPipelineId = resolvePipelineId(pipelineId);
        pipelineService.getDefinition(resolvedPipelineId);
        TaskPriority priority = taskQueueService.evaluatePriority(fileSize);
        Date queuedAt = new Date();
        Map<String, Object> storedMetadata = new HashMap<>();
        if (metadata != null) {
            storedMetadata.putAll(metadata);
        }
        if (source.getCredentials() != null && !source.getCredentials().isEmpty()) {
            storedMetadata.put("credentials", source.getCredentials());
        }
        if (vectorSpaceId != null) {
            storedMetadata.put("vectorSpaceId", vectorSpaceId);
        }

        IngestionTaskDO task = IngestionTaskDO.builder()
                .pipelineId(resolvedPipelineId)
                .sourceType(source.getType() == null ? null : source.getType().getValue())
                .sourceLocation(source.getLocation())
                .sourceFileName(source.getFileName())
                .fileUrl(fileUrl)
                .fileSize(fileSize)
                .mimeType(mimeType)
                .status(IngestionStatus.PENDING.getValue())
                .priority(priority.getValue())
                .queueStatus(QueueStatus.QUEUED.getValue())
                .queuedAt(queuedAt)
                .chunkCount(0)
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .metadataJson(writeJson(storedMetadata))
                .build();
        taskMapper.insert(task);

        IngestionTaskEvent event = IngestionTaskEvent.builder()
                .taskId(task.getId())
                .priority(priority.getValue())
                .operator(UserContext.getUsername())
                .build();
        messageQueueProducer.send(resolveIngestionTopic(priority), task.getId(), "ingestion task", event);

        return IngestionResult.builder()
                .taskId(task.getId())
                .pipelineId(task.getPipelineId())
                .status(IngestionStatus.PENDING)
                .chunkCount(0)
                .message("QUEUED")
                .build();
    }

    private IngestionResult executeInternal(String pipelineId,
                                            DocumentSource source,
                                            byte[] rawBytes,
                                            String mimeType,
                                            VectorSpaceId vectorSpaceId) {
        String resolvedPipelineId = resolvePipelineId(pipelineId);
        PipelineDefinition pipeline = pipelineService.getDefinition(resolvedPipelineId);

        IngestionTaskDO task = IngestionTaskDO.builder()
                .pipelineId(resolvedPipelineId)
                .sourceType(source.getType() == null ? null : source.getType().getValue())
                .sourceLocation(source.getLocation())
                .sourceFileName(source.getFileName())
                .status(IngestionStatus.RUNNING.getValue())
                .chunkCount(0)
                .startedAt(new Date())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        taskMapper.insert(task);

        IngestionContext context = IngestionContext.builder()
                .taskId(String.valueOf(task.getId()))
                .pipelineId(resolvedPipelineId)
                .source(source)
                .rawBytes(rawBytes)
                .mimeType(mimeType)
                .vectorSpaceId(vectorSpaceId)
                .logs(new ArrayList<>())
                .build();

        IngestionContext result = engine.execute(pipeline, context);
        saveNodeLogs(task, pipeline, result.getLogs());
        updateTaskFromContext(task, result);
        return IngestionResult.builder()
                .taskId(result.getTaskId())
                .pipelineId(result.getPipelineId())
                .status(result.getStatus())
                .chunkCount(result.getChunks() == null ? 0 : result.getChunks().size())
                .message(result.getError() == null ? "OK" : result.getError().getMessage())
                .build();
    }

    private void updateTaskFromContext(IngestionTaskDO task, IngestionContext context) {
        task.setStatus(context.getStatus() == null ? IngestionStatus.FAILED.getValue() : context.getStatus().getValue());
        task.setQueueStatus(IngestionStatus.COMPLETED == context.getStatus()
                ? QueueStatus.COMPLETED.getValue()
                : QueueStatus.FAILED.getValue());
        task.setChunkCount(context.getChunks() == null ? 0 : context.getChunks().size());
        task.setErrorMessage(context.getError() == null ? null : context.getError().getMessage());
        task.setCompletedAt(new Date());
        task.setUpdatedBy(UserContext.getUsername());
        task.setLogsJson(writeJson(buildLogSummary(context.getLogs())));
        task.setMetadataJson(writeJson(buildTaskMetadata(context)));
        taskMapper.updateById(task);
    }

    private void saveNodeLogs(IngestionTaskDO task, PipelineDefinition pipeline, List<NodeLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        Map<String, Integer> nodeOrderMap = buildNodeOrderMap(pipeline);
        for (NodeLog log : logs) {
            String status = resolveNodeStatus(log);
            String outputJson = truncateOutputJson(log.getOutput());
            IngestionTaskNodeDO nodeDO = IngestionTaskNodeDO.builder()
                    .taskId(task.getId())
                    .pipelineId(task.getPipelineId())
                    .nodeId(log.getNodeId())
                    .nodeType(log.getNodeType())
                    .nodeOrder(nodeOrderMap.getOrDefault(log.getNodeId(), 0))
                    .status(status)
                    .durationMs(log.getDurationMs())
                    .message(log.getMessage())
                    .errorMessage(log.getError())
                    .outputJson(outputJson)
                    .build();
            taskNodeMapper.insert(nodeDO);
        }
    }

    private Map<String, Integer> buildNodeOrderMap(PipelineDefinition pipeline) {
        Map<String, Integer> orderMap = new HashMap<>();
        if (pipeline == null || pipeline.getNodes() == null || pipeline.getNodes().isEmpty()) {
            return orderMap;
        }
        Map<String, NodeConfig> nodeMap = new LinkedHashMap<>();
        for (NodeConfig node : pipeline.getNodes()) {
            if (node == null || !StringUtils.hasText(node.getNodeId())) {
                continue;
            }
            nodeMap.putIfAbsent(node.getNodeId(), node);
        }
        if (nodeMap.isEmpty()) {
            return orderMap;
        }
        Set<String> referenced = new HashSet<>();
        for (NodeConfig node : nodeMap.values()) {
            if (StringUtils.hasText(node.getNextNodeId())) {
                referenced.add(node.getNextNodeId());
            }
        }
        int order = 1;
        Set<String> visited = new HashSet<>();
        for (String nodeId : nodeMap.keySet()) {
            if (referenced.contains(nodeId)) {
                continue;
            }
            String current = nodeId;
            while (StringUtils.hasText(current) && !visited.contains(current)) {
                orderMap.put(current, order++);
                visited.add(current);
                NodeConfig config = nodeMap.get(current);
                if (config == null) {
                    break;
                }
                current = config.getNextNodeId();
            }
        }
        for (String nodeId : nodeMap.keySet()) {
            if (!visited.contains(nodeId)) {
                orderMap.put(nodeId, order++);
            }
        }
        return orderMap;
    }

    private String resolveNodeStatus(NodeLog log) {
        if (log == null) {
            return "failed";
        }
        if (!log.isSuccess()) {
            return "failed";
        }
        String message = log.getMessage();
        if (message != null && message.startsWith("Skipped:")) {
            return "skipped";
        }
        return "success";
    }

    private Map<String, Object> buildTaskMetadata(IngestionContext context) {
        Map<String, Object> data = new HashMap<>();
        if (context.getMetadata() != null) {
            data.putAll(context.getMetadata());
        }
        if (context.getKeywords() != null && !context.getKeywords().isEmpty()) {
            data.put("keywords", context.getKeywords());
        }
        if (context.getQuestions() != null && !context.getQuestions().isEmpty()) {
            data.put("questions", context.getQuestions());
        }
        return data;
    }

    private String resolvePipelineId(String pipelineId) {
        if (StringUtils.hasText(pipelineId)) {
            return pipelineId;
        }
        throw new ClientException("必须传流水线ID");
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        try {
            return IngestionStatus.fromValue(status).getValue();
        } catch (IllegalArgumentException ex) {
            return status;
        }
    }

    private DocumentSource toSource(DocumentSourceRequest request) {
        Assert.notNull(request, () -> new ClientException("文档来源不能为空"));
        DocumentSource source = DocumentSource.builder()
                .type(request.getType())
                .location(request.getLocation())
                .fileName(request.getFileName())
                .credentials(request.getCredentials())
                .build();
        if (source.getType() == null) {
            throw new ClientException("文档来源类型不能为空");
        }
        return source;
    }

    private IngestionTaskVO toVO(IngestionTaskDO task) {
        return IngestionTaskVO.builder()
                .id(String.valueOf(task.getId()))
                .pipelineId(String.valueOf(task.getPipelineId()))
                .sourceType(normalizeSourceType(task.getSourceType()))
                .sourceLocation(task.getSourceLocation())
                .sourceFileName(task.getSourceFileName())
                .fileSize(task.getFileSize())
                .status(normalizeStatus(task.getStatus()))
                .priority(task.getPriority())
                .queueStatus(task.getQueueStatus())
                .queuePosition(resolveQueuePosition(task))
                .estimatedWaitSeconds(resolveEstimatedWaitSeconds(task))
                .chunkCount(task.getChunkCount())
                .errorMessage(task.getErrorMessage())
                .logs(readLogs(task.getLogsJson()))
                .metadata(readMap(task.getMetadataJson()))
                .queuedAt(task.getQueuedAt())
                .queueStartedAt(task.getQueueStartedAt())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .createdBy(task.getCreatedBy())
                .createTime(task.getCreateTime())
                .updateTime(task.getUpdateTime())
                .build();
    }

    private Integer resolveQueuePosition(IngestionTaskDO task) {
        if (!IngestionStatus.PENDING.getValue().equals(task.getStatus())
                || !QueueStatus.QUEUED.getValue().equals(task.getQueueStatus())) {
            return null;
        }
        return taskQueueService.ingestionQueuePosition(task.getPriority(), task.getQueuedAt(), task.getId());
    }

    private Long resolveEstimatedWaitSeconds(IngestionTaskDO task) {
        Integer position = resolveQueuePosition(task);
        return taskQueueService.estimateWaitSeconds(task.getPriority(), position);
    }

    private IngestionTaskNodeVO toNodeVO(IngestionTaskNodeDO node) {
        return IngestionTaskNodeVO.builder()
                .id(String.valueOf(node.getId()))
                .taskId(String.valueOf(node.getTaskId()))
                .pipelineId(String.valueOf(node.getPipelineId()))
                .nodeId(node.getNodeId())
                .nodeType(normalizeNodeType(node.getNodeType()))
                .nodeOrder(node.getNodeOrder())
                .status(normalizeNodeStatus(node.getStatus()))
                .durationMs(node.getDurationMs())
                .message(node.getMessage())
                .errorMessage(node.getErrorMessage())
                .output(BeanUtil.beanToMap(node.getOutputJson()))
                .createTime(node.getCreateTime())
                .updateTime(node.getUpdateTime())
                .build();
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> readMap(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readCredentials(Map<String, Object> metadata) {
        Object value = metadata.get("credentials");
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, String> credentials = new HashMap<>();
            rawMap.forEach((k, v) -> {
                if (k != null && v != null) {
                    credentials.put(String.valueOf(k), String.valueOf(v));
                }
            });
            return credentials;
        }
        return null;
    }

    private VectorSpaceId readVectorSpaceId(Map<String, Object> metadata) {
        Object value = metadata.get("vectorSpaceId");
        if (value == null) {
            return null;
        }
        return objectMapper.convertValue(value, VectorSpaceId.class);
    }

    private String resolveIngestionTopic(TaskPriority priority) {
        return switch (priority) {
            case HIGH -> ingestionHighTopic;
            case MEDIUM -> ingestionMediumTopic;
            case LOW -> ingestionLowTopic;
        };
    }

    @SneakyThrows
    private void ensureIngestionBucket() {
        try {
            s3Client.createBucket(builder -> builder.bucket(ingestionBucketName));
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
            log.debug("Ingestion bucket already exists: {}", ingestionBucketName);
        }
    }

    private List<NodeLog> buildLogSummary(List<NodeLog> logs) {
        if (logs == null) {
            return List.of();
        }
        return logs.stream()
                .map(log -> NodeLog.builder()
                        .nodeId(log.getNodeId())
                        .nodeType(log.getNodeType())
                        .message(log.getMessage())
                        .durationMs(log.getDurationMs())
                        .success(log.isSuccess())
                        .error(log.getError())
                        .output(null)
                        .build())
                .toList();
    }

    private List<NodeLog> readLogs(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<NodeLog>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String normalizeSourceType(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return sourceType;
        }
        try {
            return SourceType.fromValue(sourceType).getValue();
        } catch (IllegalArgumentException ex) {
            return sourceType;
        }
    }

    private String normalizeNodeType(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            return nodeType;
        }
    }

    private String normalizeNodeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        String trimmed = status.trim();
        String lower = trimmed.toLowerCase();
        return lower.replace('-', '_');
    }

    /**
     * 截断过大的输出 JSON，防止超过数据库的 max_allowed_packet 限制
     * 默认限制为 1MB
     */
    private String truncateOutputJson(Object output) {
        if (output == null) {
            return null;
        }
        String json = writeJson(output);
        if (json == null) {
            return null;
        }
        // 限制为 1MB (1,048,576 字节)，留有余量避免接近 4MB 上限
        int maxSize = 1024 * 1024;
        if (json.length() <= maxSize) {
            return json;
        }
        // 截断并添加提示信息
        String truncated = json.substring(0, maxSize - 100);
        return truncated + "... [输出过大，已截断，原始大小: " + json.length() + " 字节]";
    }
}
