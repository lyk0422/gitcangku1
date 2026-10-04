-- 剧目提示点编排与排练执行 schema（H2 MODE=MySQL 与 MySQL 通用语法，时间均为 UTC）
CREATE TABLE IF NOT EXISTS cue_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    play_code VARCHAR(64) NOT NULL COMMENT '剧目编码',
    version_no INT NOT NULL COMMENT '编排版本号，同一剧目内从1递增',
    status VARCHAR(16) NOT NULL COMMENT '版本状态：DRAFT草稿，FROZEN已冻结可绑定',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（UTC）',
    frozen_at TIMESTAMP NULL COMMENT '冻结时间（UTC），未冻结为NULL',
    CONSTRAINT uk_cue_version_play UNIQUE (play_code, version_no)
);

CREATE TABLE IF NOT EXISTS cue_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id BIGINT NOT NULL COMMENT '所属编排版本ID',
    seq_no INT NOT NULL COMMENT '场次内递增提示点序号',
    cue_type VARCHAR(32) NOT NULL COMMENT '提示点类型，如LIGHT灯光、SOUND音效',
    trigger_phrase VARCHAR(255) NOT NULL COMMENT '触发口令',
    duration_seconds INT NOT NULL COMMENT '持续秒数，冻结时校验1至600',
    CONSTRAINT fk_cue_item_version FOREIGN KEY (version_id) REFERENCES cue_version (id)
);

CREATE TABLE IF NOT EXISTS rehearsal_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_name VARCHAR(128) NOT NULL COMMENT '排练场次名称',
    version_id BIGINT NOT NULL COMMENT '绑定的编排版本ID，开始后快照不再改变',
    status VARCHAR(16) NOT NULL COMMENT '场次状态：NOT_STARTED未开始，IN_PROGRESS排练中，FINISHED已结束',
    last_executed_seq INT NOT NULL DEFAULT 0 COMMENT '已成功执行到的提示点序号，0表示尚未执行',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（UTC）',
    started_at TIMESTAMP NULL COMMENT '开始时间（UTC），未开始为NULL',
    finished_at TIMESTAMP NULL COMMENT '结束时间（UTC），未结束为NULL',
    CONSTRAINT fk_session_version FOREIGN KEY (version_id) REFERENCES cue_version (id)
);

CREATE TABLE IF NOT EXISTS session_cue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL COMMENT '所属排练场次ID',
    seq_no INT NOT NULL COMMENT '冻结快照中的提示点序号',
    cue_type VARCHAR(32) NOT NULL COMMENT '提示点类型快照',
    trigger_phrase VARCHAR(255) NOT NULL COMMENT '触发口令快照',
    duration_seconds INT NOT NULL COMMENT '持续秒数快照',
    CONSTRAINT uk_session_cue_seq UNIQUE (session_id, seq_no),
    CONSTRAINT fk_session_cue_session FOREIGN KEY (session_id) REFERENCES rehearsal_session (id)
);

CREATE TABLE IF NOT EXISTS cue_success (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL COMMENT '所属排练场次ID',
    seq_no INT NOT NULL COMMENT '已成功执行的提示点序号',
    request_id VARCHAR(64) NOT NULL COMMENT '执行回报幂等键，仅成功回报消耗',
    operator VARCHAR(64) NOT NULL COMMENT '回报操作员',
    params_fingerprint VARCHAR(64) NOT NULL COMMENT '回报参数指纹（SHA-256），用于同参重放与异参冲突判定',
    reported_at TIMESTAMP NOT NULL COMMENT '回报时间（UTC）',
    CONSTRAINT uk_cue_success_seq UNIQUE (session_id, seq_no),
    CONSTRAINT uk_cue_success_request UNIQUE (session_id, request_id),
    CONSTRAINT fk_cue_success_session FOREIGN KEY (session_id) REFERENCES rehearsal_session (id)
);

CREATE TABLE IF NOT EXISTS cue_failure (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL COMMENT '所属排练场次ID',
    seq_no INT NOT NULL COMMENT '执行失败的提示点序号',
    request_id VARCHAR(64) NOT NULL COMMENT '回报请求ID，失败不消耗幂等键，可重复使用',
    operator VARCHAR(64) NOT NULL COMMENT '回报操作员',
    fail_reason VARCHAR(512) NOT NULL COMMENT '失败原因',
    reported_at TIMESTAMP NOT NULL COMMENT '回报时间（UTC）',
    CONSTRAINT fk_cue_failure_session FOREIGN KEY (session_id) REFERENCES rehearsal_session (id)
);