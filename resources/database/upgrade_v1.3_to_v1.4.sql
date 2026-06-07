-- RAG 效果评测体系 - 数据库升级脚本
-- 新增评测结果表，存储每轮对话的 faithfulness / answer_relevancy / correctness 评测结果

CREATE TABLE IF NOT EXISTS t_rag_eval_result (
    id              VARCHAR(24)  NOT NULL,
    trace_id        VARCHAR(24)  NOT NULL,
    conversation_id VARCHAR(24)  NOT NULL,
    message_id      VARCHAR(24)  NOT NULL,
    question        TEXT,
    answer          TEXT,
    metric_name     VARCHAR(32)  NOT NULL,
    score           NUMERIC(4,3) NOT NULL,
    label           VARCHAR(8)   NOT NULL,
    reason          TEXT,
    evidence        JSONB,
    judge_model     VARCHAR(64),
    judge_version   VARCHAR(32),
    cost_tokens     INTEGER      DEFAULT 0,
    eval_mode       VARCHAR(16)  NOT NULL,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_eval_trace_id ON t_rag_eval_result(trace_id);
CREATE INDEX IF NOT EXISTS idx_eval_msg_id    ON t_rag_eval_result(message_id);
CREATE INDEX IF NOT EXISTS idx_eval_metric    ON t_rag_eval_result(metric_name);
CREATE INDEX IF NOT EXISTS idx_eval_label     ON t_rag_eval_result(label);
CREATE INDEX IF NOT EXISTS idx_eval_mode_time ON t_rag_eval_result(eval_mode, create_time);
