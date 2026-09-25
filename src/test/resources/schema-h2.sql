-- 测试用 H2（MySQL 兼容模式）表结构，与 src/main/resources/schema.sql 字段一一对应。
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

-- 字幕文本版本：创建即 PENDING，审核后 APPROVED / REJECTED；版本内容不可变
CREATE TABLE IF NOT EXISTS playout_caption_text_version (
    version_id     VARCHAR(128) NOT NULL PRIMARY KEY,
    content        VARCHAR(4000) NOT NULL,
    review_status  VARCHAR(16)  NOT NULL,
    reviewed_at_ms BIGINT       NULL,
    created_at_ms  BIGINT       NOT NULL
);

-- 紧急字幕：整数优先级（越大越高）、UTC 左闭右开窗口、规范化区域集合
CREATE TABLE IF NOT EXISTS playout_emergency_caption (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    caption_key       VARCHAR(64)  NOT NULL,
    channel_id        VARCHAR(64)  NOT NULL,
    priority          INT          NOT NULL,
    text_version_id   VARCHAR(128) NOT NULL,
    start_ms          BIGINT       NOT NULL,
    end_ms            BIGINT       NOT NULL,
    regions_csv       VARCHAR(2048) NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    revoke_request_id VARCHAR(96)  NULL,
    revoked_at_ms     BIGINT       NULL,
    created_at_ms     BIGINT       NOT NULL,
    UNIQUE (caption_key)
);
CREATE INDEX IF NOT EXISTS idx_caption_playout
    ON playout_emergency_caption (channel_id, status, start_ms, end_ms, priority);

CREATE TABLE IF NOT EXISTS playout_emergency_caption_region (
    caption_id BIGINT      NOT NULL,
    region     VARCHAR(64) NOT NULL,
    PRIMARY KEY (caption_id, region)
);
CREATE INDEX IF NOT EXISTS idx_caption_region
    ON playout_emergency_caption_region (region, caption_id);

-- 黑屏窗口：按频道+区域声明 UTC 左闭右开窗口
CREATE TABLE IF NOT EXISTS playout_blackout_window (
    id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    blackout_key  VARCHAR(64) NOT NULL,
    channel_id    VARCHAR(64) NOT NULL,
    region        VARCHAR(64) NOT NULL,
    start_ms      BIGINT      NOT NULL,
    end_ms        BIGINT      NOT NULL,
    created_at_ms BIGINT      NOT NULL,
    UNIQUE (blackout_key)
);
CREATE INDEX IF NOT EXISTS idx_blackout_lookup
    ON playout_blackout_window (channel_id, region, start_ms, end_ms);

-- crawlKey 幂等去重表，语义同 playout_request：成功才占键，失败回滚
CREATE TABLE IF NOT EXISTS playout_crawl_record (
    crawl_key     VARCHAR(96) NOT NULL PRIMARY KEY,
    operation     VARCHAR(32) NOT NULL,
    params_hash   VARCHAR(64) NOT NULL,
    response_body TEXT        NULL,
    created_at_ms BIGINT      NOT NULL
);

-- 发布时固化的字幕决策（只读），含文本内容、优先级与解析原因
CREATE TABLE IF NOT EXISTS playout_publication_caption (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    publication_id  BIGINT       NOT NULL,
    region          VARCHAR(64)  NOT NULL,
    segment_id      VARCHAR(64)  NOT NULL,
    start_ms        BIGINT       NOT NULL,
    end_ms          BIGINT       NOT NULL,
    caption_id      BIGINT       NOT NULL,
    caption_key     VARCHAR(64)  NOT NULL,
    priority        INT          NOT NULL,
    text_version_id VARCHAR(128) NOT NULL,
    text_content    VARCHAR(4000) NOT NULL,
    reason          VARCHAR(48)  NOT NULL,
    UNIQUE (publication_id, region, start_ms, end_ms)
);
CREATE INDEX IF NOT EXISTS idx_pub_caption
    ON playout_publication_caption (publication_id, region);

-- 播放回执：按发布快照确认，caption_* 为 NULL 表示该时刻无字幕覆盖
CREATE TABLE IF NOT EXISTS playout_playout_receipt (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    crawl_key          VARCHAR(96)  NOT NULL,
    channel_id         VARCHAR(64)  NOT NULL,
    publication_id     BIGINT       NOT NULL,
    published_version  BIGINT       NOT NULL,
    region             VARCHAR(64)  NOT NULL,
    at_ms              BIGINT       NOT NULL,
    asset_id           VARCHAR(64)  NOT NULL,
    segment_id         VARCHAR(64)  NOT NULL,
    caption_record_id  BIGINT       NULL,
    caption_key        VARCHAR(64)  NULL,
    priority           INT          NULL,
    text_version_id    VARCHAR(128) NULL,
    caption_text       VARCHAR(4000) NULL,
    caption_start_ms   BIGINT       NULL,
    caption_end_ms     BIGINT       NULL,
    confirm_reason     VARCHAR(48)  NOT NULL,
    created_at_ms      BIGINT       NOT NULL,
    UNIQUE (crawl_key)
);
CREATE INDEX IF NOT EXISTS idx_receipt_publication
    ON playout_playout_receipt (publication_id, region);
