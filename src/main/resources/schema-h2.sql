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

CREATE TABLE IF NOT EXISTS playout_lease (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    client_key        VARCHAR(64) NOT NULL,
    channel_id        VARCHAR(64) NOT NULL,
    business_day      DATE        NOT NULL,
    publication_id    BIGINT      NOT NULL,
    published_version BIGINT      NOT NULL,
    lease_epoch       BIGINT      NOT NULL,
    status            VARCHAR(16) NOT NULL,
    active_unique     TINYINT     NULL,
    ttl_ms            BIGINT      NOT NULL,
    expires_at_ms     BIGINT      NOT NULL,
    created_at_ms     BIGINT      NOT NULL,
    updated_at_ms     BIGINT      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_lease_active
    ON playout_lease (client_key, channel_id, business_day, active_unique);
CREATE INDEX IF NOT EXISTS idx_lease_publication
    ON playout_lease (publication_id, status, expires_at_ms);

CREATE TABLE IF NOT EXISTS playout_lease_segment (
    id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    lease_id      BIGINT      NOT NULL,
    seq           INT         NOT NULL,
    segment_id    VARCHAR(64) NOT NULL,
    asset_id      VARCHAR(64) NOT NULL,
    grant_id      BIGINT      NOT NULL,
    grant_revoked TINYINT(1)  NOT NULL,
    start_ms      BIGINT      NOT NULL,
    end_ms        BIGINT      NOT NULL,
    acked         TINYINT(1)  NOT NULL DEFAULT 0,
    ack_key       VARCHAR(64) NULL,
    played_at_ms  BIGINT      NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_lease_segment ON playout_lease_segment (lease_id, segment_id);
CREATE INDEX IF NOT EXISTS idx_lease_segment_seq ON playout_lease_segment (lease_id, seq);

CREATE TABLE IF NOT EXISTS playout_lease_override (
    id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    lease_id      BIGINT      NOT NULL,
    override_key  VARCHAR(64) NOT NULL,
    asset_id      VARCHAR(64) NOT NULL,
    grant_id      BIGINT      NOT NULL,
    grant_revoked TINYINT(1)  NOT NULL,
    priority      TINYINT     NOT NULL,
    start_ms      BIGINT      NOT NULL,
    end_ms        BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_lease_override ON playout_lease_override (lease_id);

CREATE TABLE IF NOT EXISTS playout_lease_ack (
    id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    lease_id      BIGINT      NOT NULL,
    ack_key       VARCHAR(64) NOT NULL,
    segment_id    VARCHAR(64) NOT NULL,
    lease_epoch   BIGINT      NOT NULL,
    played_at_ms  BIGINT      NOT NULL,
    acked_count   INT         NOT NULL,
    lease_status  VARCHAR(16) NOT NULL,
    created_at_ms BIGINT      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_lease_ack ON playout_lease_ack (lease_id, ack_key);
