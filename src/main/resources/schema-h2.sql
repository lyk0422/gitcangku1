-- 本地默认运行与测试使用的 H2（MySQL 兼容模式）表结构，与 src/main/resources/schema.sql 字段一一对应。
-- 列含义见 schema.sql 中的中文 COMMENT：*_ms 为 UTC 纪元毫秒，business_day 为 Asia/Shanghai 日历日。

CREATE TABLE IF NOT EXISTS playout_asset (
    id            VARCHAR(64) NOT NULL PRIMARY KEY,
    duration_ms   BIGINT      NOT NULL,
    version       BIGINT      NOT NULL DEFAULT 1,
    withdrawn     TINYINT(1)  NOT NULL DEFAULT 0,
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
    region_code       VARCHAR(32) NULL,
    valid_from_ms     BIGINT      NOT NULL,
    valid_to_ms       BIGINT      NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 1,
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

CREATE TABLE IF NOT EXISTS playout_splice_config (
    id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel_id    VARCHAR(64) NOT NULL,
    business_day  DATE        NOT NULL,
    segment_id    VARCHAR(64) NOT NULL,
    region_code   VARCHAR(32) NOT NULL,
    asset_id      VARCHAR(64) NOT NULL,
    grant_id      BIGINT      NOT NULL,
    grant_version BIGINT      NOT NULL,
    start_ms      BIGINT      NOT NULL,
    end_ms        BIGINT      NOT NULL,
    created_at_ms BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_splice_config
    ON playout_splice_config (channel_id, business_day, segment_id, region_code);

CREATE TABLE IF NOT EXISTS playout_blackout (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel_id        VARCHAR(64) NOT NULL,
    region_code       VARCHAR(32) NOT NULL,
    start_ms          BIGINT      NOT NULL,
    end_ms            BIGINT      NOT NULL,
    status            VARCHAR(16) NOT NULL,
    cancel_request_id VARCHAR(64) NULL,
    cancelled_at_ms   BIGINT      NULL,
    created_at_ms     BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_blackout_window
    ON playout_blackout (channel_id, region_code, status, start_ms, end_ms);

CREATE TABLE IF NOT EXISTS playout_publication_region (
    id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    publication_id   BIGINT      NOT NULL,
    segment_id       VARCHAR(64) NOT NULL,
    region_code      VARCHAR(32) NOT NULL,
    asset_id         VARCHAR(64) NOT NULL,
    grant_id         BIGINT      NOT NULL,
    grant_version    BIGINT      NOT NULL,
    splice_start_ms  BIGINT      NULL,
    splice_end_ms    BIGINT      NULL,
    source           VARCHAR(8)  NOT NULL,
    fallback_reason  VARCHAR(32) NULL,
    created_at_ms    BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pub_region
    ON playout_publication_region (publication_id, segment_id, region_code);

CREATE TABLE IF NOT EXISTS playout_receipt (
    id             BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel_id     VARCHAR(64) NOT NULL,
    region_code    VARCHAR(32) NOT NULL,
    at_ms          BIGINT      NOT NULL,
    asset_id       VARCHAR(64) NOT NULL,
    source         VARCHAR(16) NOT NULL,
    publication_id BIGINT      NULL,
    segment_id     VARCHAR(64) NULL,
    grant_id       BIGINT      NULL,
    grant_version  BIGINT      NULL,
    created_at_ms  BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_receipt
    ON playout_receipt (channel_id, region_code, at_ms);
