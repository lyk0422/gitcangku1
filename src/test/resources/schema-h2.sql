-- 测试用 H2（MySQL 兼容模式）表结构，与 src/main/resources/schema.sql 字段一一对应。
-- 列含义见 schema.sql 中的中文 COMMENT：*_ms 为 UTC 纪元毫秒，business_day 为 Asia/Shanghai 日历日。

CREATE TABLE IF NOT EXISTS playout_asset (
    id            VARCHAR(64) NOT NULL PRIMARY KEY,
    duration_ms   BIGINT      NOT NULL,
    rating        VARCHAR(8)  NULL,
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

CREATE TABLE IF NOT EXISTS playout_rating_window (
    id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel_id    VARCHAR(64) NOT NULL,
    start_minute  INT         NOT NULL,
    end_minute    INT         NOT NULL,
    max_rating    VARCHAR(8)  NOT NULL,
    version       BIGINT      NOT NULL DEFAULT 1,
    revoked       TINYINT(1)  NOT NULL DEFAULT 0,
    created_at_ms BIGINT      NOT NULL,
    updated_at_ms BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rating_window_channel
    ON playout_rating_window (channel_id, revoked, start_minute, end_minute);

CREATE TABLE IF NOT EXISTS playout_publication_rating_check (
    id                  BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    publication_id      BIGINT      NOT NULL,
    channel_id          VARCHAR(64) NOT NULL,
    business_day        DATE        NOT NULL,
    published_version   BIGINT      NOT NULL,
    segment_id          VARCHAR(64) NOT NULL,
    asset_id            VARCHAR(64) NOT NULL,
    asset_rating        VARCHAR(8)  NOT NULL,
    start_ms            BIGINT      NOT NULL,
    end_ms              BIGINT      NOT NULL,
    window_id           BIGINT      NULL,
    window_start_minute INT         NULL,
    window_end_minute   INT         NULL,
    allowed_rating      VARCHAR(8)  NULL,
    created_at_ms       BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pub_rating_check_pub
    ON playout_publication_rating_check (publication_id);
CREATE INDEX IF NOT EXISTS idx_pub_rating_check_channel
    ON playout_publication_rating_check (channel_id, business_day, published_version);

CREATE TABLE IF NOT EXISTS playout_interruption (
    id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel_id    VARCHAR(64) NOT NULL,
    asset_id      VARCHAR(64) NOT NULL,
    at_ms         BIGINT      NOT NULL,
    asset_rating  VARCHAR(8)  NOT NULL,
    window_id     BIGINT      NULL,
    created_at_ms BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_interruption_channel
    ON playout_interruption (channel_id, at_ms);
