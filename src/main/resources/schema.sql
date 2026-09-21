-- 播出编排与授权回退：业务表结构。
-- 时间统一以 Unix 毫秒（BIGINT）存储，展示层使用 Asia/Shanghai、毫秒精度 ISO 8601。

CREATE TABLE IF NOT EXISTS assets (
    id          VARCHAR(128) PRIMARY KEY COMMENT '稳定素材 ID',
    duration_ms BIGINT       NOT NULL COMMENT '素材时长，单位毫秒，正整数',
    created_at  BIGINT       NOT NULL COMMENT '创建时间，Unix 毫秒'
) COMMENT = '素材';

CREATE TABLE IF NOT EXISTS channels (
    id                VARCHAR(128) PRIMARY KEY COMMENT '频道 ID',
    fallback_asset_id VARCHAR(128) NOT NULL COMMENT '保底素材 ID，创建后不可撤销',
    created_at        BIGINT       NOT NULL COMMENT '创建时间，Unix 毫秒'
) COMMENT = '频道';

CREATE TABLE IF NOT EXISTS grants (
    id         VARCHAR(64) PRIMARY KEY COMMENT '授权 ID，服务端生成',
    channel_id VARCHAR(128) NOT NULL COMMENT '频道 ID',
    asset_id   VARCHAR(128) NOT NULL COMMENT '普通素材 ID',
    valid_from BIGINT       NOT NULL COMMENT '生效时间（含），Unix 毫秒',
    valid_to   BIGINT       NOT NULL COMMENT '失效时间（不含），Unix 毫秒，区间左闭右开',
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '是否已撤销，撤销后不可恢复',
    created_at BIGINT       NOT NULL COMMENT '创建时间，Unix 毫秒'
) COMMENT = '授权';

CREATE TABLE IF NOT EXISTS drafts (
    channel_id   VARCHAR(128) NOT NULL COMMENT '频道 ID',
    business_day DATE         NOT NULL COMMENT '业务日（Asia/Shanghai 日历日）',
    version      BIGINT       NOT NULL COMMENT '草稿版本，从 1 开始，每次整份替换加 1',
    updated_at   BIGINT       NOT NULL COMMENT '最近替换时间，Unix 毫秒',
    PRIMARY KEY (channel_id, business_day)
) COMMENT = '编排草稿，每个频道+业务日一份';

CREATE TABLE IF NOT EXISTS draft_segments (
    channel_id   VARCHAR(128) NOT NULL COMMENT '频道 ID',
    business_day DATE         NOT NULL COMMENT '业务日（Asia/Shanghai 日历日）',
    segment_id   VARCHAR(128) NOT NULL COMMENT '片段独立 ID',
    asset_id     VARCHAR(128) NOT NULL COMMENT '素材 ID',
    start_ms     BIGINT       NOT NULL COMMENT '播出开始（含），Unix 毫秒',
    end_ms       BIGINT       NOT NULL COMMENT '播出结束（不含），Unix 毫秒',
    seq          INT          NOT NULL COMMENT '片段时间顺序号',
    PRIMARY KEY (channel_id, business_day, segment_id)
) COMMENT = '草稿片段，随草稿整份替换';

CREATE TABLE IF NOT EXISTS published (
    channel_id    VARCHAR(128) NOT NULL COMMENT '频道 ID',
    business_day  DATE         NOT NULL COMMENT '业务日（Asia/Shanghai 日历日）',
    version       BIGINT       NOT NULL COMMENT '发布版本，从 1 开始，每次发布加 1',
    draft_version BIGINT       NOT NULL COMMENT '生成本快照的草稿版本',
    published_at  BIGINT       NOT NULL COMMENT '发布时间，Unix 毫秒',
    PRIMARY KEY (channel_id, business_day)
) COMMENT = '已发布编排，每个频道+业务日一份';

CREATE TABLE IF NOT EXISTS published_segments (
    channel_id        VARCHAR(128) NOT NULL COMMENT '频道 ID',
    business_day      DATE         NOT NULL COMMENT '业务日（Asia/Shanghai 日历日）',
    published_version BIGINT       NOT NULL COMMENT '生成快照时的发布版本',
    segment_id        VARCHAR(128) NOT NULL COMMENT '片段独立 ID',
    asset_id          VARCHAR(128) NOT NULL COMMENT '素材 ID',
    start_ms          BIGINT       NOT NULL COMMENT '播出开始（含），Unix 毫秒',
    end_ms            BIGINT       NOT NULL COMMENT '播出结束（不含），Unix 毫秒',
    seq               INT          NOT NULL COMMENT '片段时间顺序号',
    PRIMARY KEY (channel_id, business_day, segment_id)
) COMMENT = '发布快照片段，只读，授权撤销不改写';

CREATE TABLE IF NOT EXISTS request_dedup (
    request_id  VARCHAR(128) PRIMARY KEY COMMENT '客户端请求 ID',
    operation   VARCHAR(32)   NOT NULL COMMENT '操作类型：REPLACE_DRAFT/PUBLISH/REVOKE_GRANT',
    fingerprint VARCHAR(2048) NOT NULL COMMENT '请求参数指纹，识别同 requestId 不同参数',
    result_json TEXT          NULL COMMENT '成功结果 JSON，重试时原样返回；与业务结果同事务提交',
    created_at  BIGINT        NOT NULL COMMENT '创建时间，Unix 毫秒'
) COMMENT = '请求去重记录，失败请求不留记录';
