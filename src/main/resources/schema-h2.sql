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

CREATE TABLE IF NOT EXISTS playout_live_override (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    override_key      VARCHAR(64) NOT NULL,
    channel_id        VARCHAR(64) NOT NULL,
    source_id         VARCHAR(64) NOT NULL,
    grant_id          BIGINT      NOT NULL,
    start_ms          BIGINT      NOT NULL,
    end_ms            BIGINT      NOT NULL,
    status            VARCHAR(16) NOT NULL,
    started_at_ms     BIGINT      NULL,
    ended_at_ms       BIGINT      NULL,
    create_request_id VARCHAR(64) NOT NULL,
    operator          VARCHAR(64) NOT NULL,
    created_at_ms     BIGINT      NOT NULL,
    revoke_request_id VARCHAR(64) NULL,
    revoke_operator   VARCHAR(64) NULL,
    revoked_at_ms     BIGINT      NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_live_override_key
    ON playout_live_override (override_key);
CREATE INDEX IF NOT EXISTS idx_live_override_channel_time
    ON playout_live_override (channel_id, status, start_ms, end_ms);

CREATE TABLE IF NOT EXISTS playout_live_affected (
    id                 BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    live_override_id   BIGINT      NOT NULL,
    channel_id         VARCHAR(64) NOT NULL,
    business_day       DATE        NOT NULL,
    publication_id     BIGINT      NOT NULL,
    segment_id         VARCHAR(64) NOT NULL,
    asset_id           VARCHAR(64) NOT NULL,
    scheduled_start_ms BIGINT      NOT NULL,
    scheduled_end_ms   BIGINT      NOT NULL,
    result             VARCHAR(24) NOT NULL,
    decided_at_ms      BIGINT      NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_live_affected
    ON playout_live_affected (live_override_id, publication_id, segment_id);
CREATE INDEX IF NOT EXISTS idx_live_affected_segment
    ON playout_live_affected (channel_id, publication_id, segment_id, result);

CREATE TABLE IF NOT EXISTS playout_live_grant_snapshot (
    id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    live_override_id BIGINT      NOT NULL,
    grant_id         BIGINT      NOT NULL,
    channel_id       VARCHAR(64) NOT NULL,
    asset_id         VARCHAR(64) NOT NULL,
    valid_from_ms    BIGINT      NOT NULL,
    valid_to_ms      BIGINT      NOT NULL,
    revoked          TINYINT(1)  NOT NULL,
    captured_at_ms   BIGINT      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_live_grant_snapshot
    ON playout_live_grant_snapshot (live_override_id, grant_id);

CREATE TABLE IF NOT EXISTS playout_live_recovery (
    id                 BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    live_override_id   BIGINT      NOT NULL,
    channel_id         VARCHAR(64) NOT NULL,
    business_day       DATE        NOT NULL,
    publication_id     BIGINT      NOT NULL,
    segment_id         VARCHAR(64) NOT NULL,
    result             VARCHAR(24) NOT NULL,
    scheduled_start_ms BIGINT      NOT NULL,
    scheduled_end_ms   BIGINT      NOT NULL,
    recovered_at_ms    BIGINT      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_live_recovery
    ON playout_live_recovery (live_override_id, publication_id, segment_id);
CREATE INDEX IF NOT EXISTS idx_live_recovery_channel
    ON playout_live_recovery (channel_id, business_day);
