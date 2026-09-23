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
    schedule_version  BIGINT      NOT NULL DEFAULT 0,
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

-- ===== 主备播出链路与租约切换 =====

CREATE TABLE IF NOT EXISTS playout_channel_link (
    id             BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel_id     VARCHAR(64) NOT NULL,
    link_id        VARCHAR(64) NOT NULL,
    role           VARCHAR(16) NOT NULL,
    healthy        TINYINT(1)  NOT NULL DEFAULT 0,
    cached_version BIGINT      NOT NULL DEFAULT 0,
    updated_at_ms  BIGINT      NOT NULL,
    UNIQUE (channel_id, link_id),
    UNIQUE (channel_id, role)
);

CREATE TABLE IF NOT EXISTS playout_link_lease (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel_id        VARCHAR(64) NOT NULL,
    link_id           VARCHAR(64) NOT NULL,
    generation       BIGINT      NOT NULL,
    status            VARCHAR(16) NOT NULL,
    confirmed_seq     BIGINT      NOT NULL DEFAULT 0,
    schedule_version  BIGINT      NOT NULL,
    cutover_seq       BIGINT      NULL,
    order_id          BIGINT      NULL,
    created_at_ms     BIGINT      NOT NULL,
    ended_at_ms       BIGINT      NULL,
    active_slot       VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN channel_id ELSE NULL END),
    UNIQUE (channel_id, generation),
    UNIQUE (active_slot)
);

CREATE TABLE IF NOT EXISTS playout_link_receipt (
    id             BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel_id     VARCHAR(64) NOT NULL,
    link_id        VARCHAR(64) NOT NULL,
    generation     BIGINT      NOT NULL,
    seq            BIGINT      NOT NULL,
    disposition    VARCHAR(16) NOT NULL,
    received_at_ms BIGINT      NOT NULL,
    UNIQUE (channel_id, link_id, generation, seq)
);
CREATE INDEX IF NOT EXISTS idx_receipt_query
    ON playout_link_receipt (channel_id, link_id, disposition, seq);

CREATE TABLE IF NOT EXISTS playout_link_override_sync (
    channel_id       VARCHAR(64) NOT NULL,
    link_id          VARCHAR(64) NOT NULL,
    override_key     VARCHAR(64) NOT NULL,
    synced           TINYINT(1)  NOT NULL DEFAULT 0,
    updated_at_ms    BIGINT      NOT NULL,
    PRIMARY KEY (channel_id, link_id, override_key)
);

CREATE TABLE IF NOT EXISTS playout_failover_order (
    id                    BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    failover_key          VARCHAR(64) NOT NULL,
    channel_id            VARCHAR(64) NOT NULL,
    channel_version       BIGINT      NOT NULL,
    source_link_id        VARCHAR(64) NOT NULL,
    target_link_id        VARCHAR(64) NOT NULL,
    source_last_seq       BIGINT      NOT NULL,
    target_last_seq       BIGINT      NOT NULL,
    cutover_at_ms         BIGINT      NOT NULL,
    max_lag               BIGINT      NOT NULL,
    status                VARCHAR(16) NOT NULL,
    generation            BIGINT      NULL,
    safe_cut_seq          BIGINT      NULL,
    schedule_version      BIGINT      NULL,
    frozen_stack_json     TEXT        NULL,
    created_at_ms         BIGINT      NOT NULL,
    activated_at_ms       BIGINT      NULL,
    UNIQUE (failover_key)
);
CREATE INDEX IF NOT EXISTS idx_failover_channel ON playout_failover_order (channel_id, status);
