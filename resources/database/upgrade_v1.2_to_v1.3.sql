
ALTER TABLE t_knowledge_document
    ADD COLUMN IF NOT EXISTS priority VARCHAR(16),
    ADD COLUMN IF NOT EXISTS queue_status VARCHAR(16),
    ADD COLUMN IF NOT EXISTS queued_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS queue_started_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_knowledge_document_queue
    ON t_knowledge_document (status, priority, queued_at);

ALTER TABLE t_ingestion_task
    ADD COLUMN IF NOT EXISTS file_url VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS file_size BIGINT,
    ADD COLUMN IF NOT EXISTS mime_type VARCHAR(128),
    ADD COLUMN IF NOT EXISTS priority VARCHAR(16),
    ADD COLUMN IF NOT EXISTS queue_status VARCHAR(16),
    ADD COLUMN IF NOT EXISTS queued_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS queue_started_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_ingestion_task_queue
    ON t_ingestion_task (status, priority, queued_at);
