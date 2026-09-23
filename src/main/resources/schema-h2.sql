-- 本地默认运行与测试使用的 H2（MySQL 兼容模式）表结构，与 src/main/resources/schema.sql 字段一一对应。
-- 列含义见 schema.sql 中的中文 COMMENT：*_ms 为 UTC 纪元毫秒，business_day 为 Asia/Shanghai 日历日。

CREATE TABLE IF NOT EXISTS playout_asset (
    id            VARCHAR(64) NOT NULL PRIMARY KEY,
    duration_ms   BIGINT      NOT NULL,
    created_at_ms BIGINT      NOT NULL
);

CREATE TABLE IF NOT EXISTS playout_channel (
    id                VARCHAR(64) NOT NULL PRIMARY KEY,
    fallback_asset_id VARCHAR(64) NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at_ms     BIGINT      NOT NULL
);

CREATE TABLE IF NOT EXISTS playout_grant (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel_id        VARCHAR(64) NOT NULL,
    asset_id          VARCHAR(64) NOT NULL,
    valid_from_ms     BIGINT      NOT NULL,
    valid_to_ms       BIGINT      NOT NULL,
    revoked           TINYINT(1)  NOT NULL DEFAULT 0,
    revoke_request_id VARCHAR(64) NULL,
    revoked_at_ms     BIGINT      NULL,
    created_at_ms     BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_grant_coverage
    ON playout_grant (channel_id, asset_id, revoked, valid_from_ms, valid_to_ms);

CREATE TABLE IF NOT EXISTS playout_draft (
    channel_id    VARCHAR(64) NOT NULL,
    business_day  DATE        NOT NULL,
    version       BIGINT      NOT NULL,
    updated_at_ms BIGINT      NOT NULL,
    PRIMARY KEY (channel_id, business_day)
);

CREATE TABLE IF NOT EXISTS playout_draft_segment (
    id           VARCHAR(64) NOT NULL PRIMARY KEY,
    channel_id   VARCHAR(64) NOT NULL,
    business_day DATE        NOT NULL,
    asset_id     VARCHAR(64) NOT NULL,
    start_ms     BIGINT      NOT NULL,
    end_ms       BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_draft_segment ON playout_draft_segment (channel_id, business_day);

CREATE TABLE IF NOT EXISTS playout_publication (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel_id        VARCHAR(64) NOT NULL,
    business_day      DATE        NOT NULL,
    published_version BIGINT      NOT NULL,
    draft_version     BIGINT      NOT NULL,
    created_at_ms     BIGINT      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_publication_version
    ON playout_publication (channel_id, business_day, published_version);

CREATE TABLE IF NOT EXISTS playout_publication_segment (
    id             BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    publication_id BIGINT      NOT NULL,
    segment_id     VARCHAR(64) NOT NULL,
    asset_id       VARCHAR(64) NOT NULL,
    grant_id       BIGINT      NOT NULL,
    start_ms       BIGINT      NOT NULL,
    end_ms         BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pub_segment ON playout_publication_segment (publication_id);

CREATE TABLE IF NOT EXISTS playout_request (
    request_id    VARCHAR(64) NOT NULL PRIMARY KEY,
    operation     VARCHAR(32) NOT NULL,
    params_hash   VARCHAR(64) NOT NULL,
    response_body TEXT        NULL,
    created_at_ms BIGINT      NOT NULL
);

CREATE TABLE IF NOT EXISTS playout_emergency_override (
    override_key      VARCHAR(64) NOT NULL PRIMARY KEY,
    channel_id        VARCHAR(64) NOT NULL,
    asset_id          VARCHAR(64) NOT NULL,
    grant_id          BIGINT      NOT NULL,
    priority          TINYINT     NOT NULL,
    start_ms          BIGINT      NOT NULL,
    end_ms            BIGINT      NOT NULL,
    business_day      DATE        NOT NULL,
    status            VARCHAR(16) NOT NULL,
    cancel_request_id VARCHAR(64) NULL,
    cancelled_at_ms   BIGINT      NULL,
    created_at_ms     BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_override_playout
    ON playout_emergency_override (channel_id, status, start_ms, end_ms, priority);

-- 频道主备链路配置：每频道 PRIMARY / BACKUP 各一条，role 取值 PRIMARY/BACKUP。
CREATE TABLE IF NOT EXISTS playout_link (
    channel_id    VARCHAR(64) NOT NULL,
    role          VARCHAR(16) NOT NULL,
    link_id       VARCHAR(64) NOT NULL,
    healthy       TINYINT(1)  NOT NULL DEFAULT 1,
    cached_schedule_version BIGINT NOT NULL DEFAULT 0,
    cached_override_signature VARCHAR(128) NULL,
    updated_at_ms BIGINT      NOT NULL,
    PRIMARY KEY (channel_id, role)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_link_channel_link
    ON playout_link (channel_id, link_id);

-- 链路租约：active_marker 为活动标记（ACTIVE 行='A'，其余 NULL），由唯一索引保证每频道至多一条 ACTIVE。
CREATE TABLE IF NOT EXISTS playout_lease (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel_id    VARCHAR(64)  NOT NULL,
    link_id       VARCHAR(64)  NOT NULL,
    generation    BIGINT       NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    active_marker VARCHAR(8)   NULL,
    -- 冻结切点：切换时源链路已确认 sequence（新链路下一应播 sequence = cut_sequence + 1）。
    cut_sequence  BIGINT       NOT NULL,
    -- 冻结的编排发布版本（JSON: {"yyyy-MM-dd":version}）与插播栈（JSON 数组）。
    schedule_snapshot TEXT    NULL,
    override_snapshot TEXT    NULL,
    started_at_ms BIGINT       NOT NULL,
    ended_at_ms   BIGINT       NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_lease_generation
    ON playout_lease (channel_id, generation);
CREATE UNIQUE INDEX IF NOT EXISTS uk_lease_active
    ON playout_lease (channel_id, active_marker);

-- 切换单：failoverKey 全局唯一；status 为 ACTIVATED / REJECTED。
CREATE TABLE IF NOT EXISTS playout_failover_order (
    failover_key       VARCHAR(64) NOT NULL PRIMARY KEY,
    channel_id         VARCHAR(64) NOT NULL,
    channel_version    BIGINT      NOT NULL,
    source_link_id     VARCHAR(64) NOT NULL,
    target_link_id     VARCHAR(64) NOT NULL,
    source_last_sequence  BIGINT   NOT NULL,
    target_last_sequence  BIGINT   NOT NULL,
    cutover_at_ms      BIGINT      NOT NULL,
    status             VARCHAR(16) NOT NULL,
    reject_code        VARCHAR(48) NULL,
    new_generation     BIGINT      NULL,
    cut_sequence       BIGINT      NULL,
    created_request_id VARCHAR(64) NOT NULL,
    created_at_ms      BIGINT      NOT NULL,
    activated_at_ms    BIGINT      NULL
);

-- 链路回执：同一链路同一世代同一 sequence 唯一（源 CURRENT 与目标 CACHED 允许同 sequence 并存）。
CREATE TABLE IF NOT EXISTS playout_receipt (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel_id    VARCHAR(64)  NOT NULL,
    link_id       VARCHAR(64)  NOT NULL,
    generation    BIGINT       NOT NULL,
    sequence_no   BIGINT       NOT NULL,
    received_at_ms BIGINT      NOT NULL,
    disposition   VARCHAR(16)  NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_receipt_once
    ON playout_receipt (channel_id, link_id, generation, sequence_no);
CREATE INDEX IF NOT EXISTS idx_receipt_channel_gen
    ON playout_receipt (channel_id, generation, sequence_no);
