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

-- ========== 紧急字幕相关 ==========

CREATE TABLE IF NOT EXISTS playout_blackout (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel_id    VARCHAR(64)  NOT NULL,
    start_ms      BIGINT       NOT NULL,
    end_ms        BIGINT       NOT NULL,
    regions_text  VARCHAR(2000) NOT NULL,
    request_id    VARCHAR(64)  NOT NULL,
    created_at_ms BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_blackout_channel
    ON playout_blackout (channel_id, start_ms, end_ms);

CREATE TABLE IF NOT EXISTS playout_blackout_region (
    blackout_id BIGINT      NOT NULL,
    region      VARCHAR(64) NOT NULL,
    PRIMARY KEY (blackout_id, region)
);

CREATE TABLE IF NOT EXISTS playout_subtitle_text (
    text_key           VARCHAR(64)  NOT NULL,
    version            INT          NOT NULL,
    content            VARCHAR(2000) NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    approve_request_id VARCHAR(64)  NULL,
    created_at_ms      BIGINT       NOT NULL,
    approved_at_ms     BIGINT       NULL,
    PRIMARY KEY (text_key, version)
);

CREATE TABLE IF NOT EXISTS playout_emergency_subtitle (
    subtitle_key      VARCHAR(64)  NOT NULL PRIMARY KEY,
    channel_id        VARCHAR(64)  NOT NULL,
    priority          INT          NOT NULL,
    text_key          VARCHAR(64)  NOT NULL,
    text_version      INT          NOT NULL,
    start_ms          BIGINT       NOT NULL,
    end_ms            BIGINT       NOT NULL,
    regions_text      VARCHAR(2000) NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    revoke_request_id VARCHAR(64)  NULL,
    revoked_at_ms     BIGINT       NULL,
    created_at_ms     BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_subtitle_playout
    ON playout_emergency_subtitle (channel_id, status, start_ms, end_ms, priority);

CREATE TABLE IF NOT EXISTS playout_emergency_subtitle_region (
    subtitle_key VARCHAR(64) NOT NULL,
    region       VARCHAR(64) NOT NULL,
    PRIMARY KEY (subtitle_key, region)
);
CREATE INDEX IF NOT EXISTS idx_subtitle_region
    ON playout_emergency_subtitle_region (region);

CREATE TABLE IF NOT EXISTS playout_publication_subtitle (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    publication_id BIGINT       NOT NULL,
    region         VARCHAR(64)  NOT NULL,
    segment_id     VARCHAR(64)  NOT NULL,
    start_ms       BIGINT       NOT NULL,
    end_ms         BIGINT       NOT NULL,
    subtitle_key   VARCHAR(64)  NOT NULL,
    text_key       VARCHAR(64)  NOT NULL,
    text_version   INT          NOT NULL,
    text_content   VARCHAR(2000) NOT NULL,
    priority       INT          NOT NULL,
    reason         VARCHAR(1000) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pub_subtitle
    ON playout_publication_subtitle (publication_id, region, start_ms, end_ms);

CREATE TABLE IF NOT EXISTS playout_receipt (
    crawl_key        VARCHAR(64) NOT NULL PRIMARY KEY,
    channel_id       VARCHAR(64) NOT NULL,
    business_day     DATE        NOT NULL,
    publication_id   BIGINT      NOT NULL,
    published_version BIGINT     NOT NULL,
    region           VARCHAR(64) NOT NULL,
    at_ms            BIGINT      NOT NULL,
    asset_id         VARCHAR(64) NOT NULL,
    segment_id       VARCHAR(64) NOT NULL,
    subtitle_key     VARCHAR(64) NULL,
    text_key         VARCHAR(64) NULL,
    text_version     INT         NULL,
    priority         INT         NULL,
    overlay_start_ms BIGINT      NULL,
    overlay_end_ms   BIGINT      NULL,
    fingerprint      VARCHAR(64) NOT NULL,
    created_at_ms    BIGINT      NOT NULL
);

CREATE TABLE IF NOT EXISTS playout_publish_block (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel_id    VARCHAR(64)  NOT NULL,
    business_day  DATE         NOT NULL,
    request_id    VARCHAR(64)  NOT NULL,
    code          VARCHAR(48)  NOT NULL,
    detail_json   TEXT         NOT NULL,
    created_at_ms BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_publish_block
    ON playout_publish_block (channel_id, business_day, id);
