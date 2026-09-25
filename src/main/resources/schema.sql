-- 播出编排与授权回退业务表结构（MySQL 8）。
-- 时间约定：所有 *_ms 列为 UTC 纪元毫秒（BIGINT），API 层统一使用 Asia/Shanghai、毫秒精度 ISO 8601；
-- 业务日 business_day 为 Asia/Shanghai 时区下的日历日。

CREATE TABLE IF NOT EXISTS playout_asset (
    id           VARCHAR(64)  NOT NULL COMMENT '素材稳定 ID，客户端指定或系统生成，创建后不变',
    duration_ms  BIGINT       NOT NULL COMMENT '素材时长，单位毫秒，正整数',
    rating       VARCHAR(8)   NULL COMMENT '内容分级：G / PG / MATURE；NULL 表示登记时未声明，分级校验按 MATURE 处理',
    created_at_ms BIGINT      NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    PRIMARY KEY (id)
) COMMENT = '素材表';

CREATE TABLE IF NOT EXISTS playout_channel (
    id                VARCHAR(64) NOT NULL COMMENT '频道 ID，客户端指定或系统生成',
    fallback_asset_id VARCHAR(64) NOT NULL COMMENT '保底素材 ID，创建时指定且不会被撤销',
    created_at_ms     BIGINT      NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    PRIMARY KEY (id)
) COMMENT = '频道表';

CREATE TABLE IF NOT EXISTS playout_grant (
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '授权自增 ID',
    channel_id        VARCHAR(64) NOT NULL COMMENT '授权频道 ID',
    asset_id          VARCHAR(64) NOT NULL COMMENT '授权普通素材 ID（不得为频道保底素材）',
    valid_from_ms     BIGINT      NOT NULL COMMENT '授权生效起点（含），UTC 纪元毫秒',
    valid_to_ms       BIGINT      NOT NULL COMMENT '授权生效终点（不含），UTC 纪元毫秒，区间左闭右开',
    revoked           TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否已撤销：0 未撤销，1 已撤销；撤销为终态',
    revoke_request_id VARCHAR(64) NULL COMMENT '撤销操作的幂等请求 ID；未撤销时为 NULL',
    revoked_at_ms     BIGINT      NULL COMMENT '撤销时间，UTC 纪元毫秒；未撤销时为 NULL',
    created_at_ms     BIGINT      NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    KEY idx_grant_coverage (channel_id, asset_id, revoked, valid_from_ms, valid_to_ms)
) COMMENT = '素材授权表，授权创建后只能撤销';

CREATE TABLE IF NOT EXISTS playout_draft (
    channel_id    VARCHAR(64) NOT NULL COMMENT '频道 ID',
    business_day  DATE        NOT NULL COMMENT '业务日，Asia/Shanghai 日历日',
    version       BIGINT      NOT NULL COMMENT '草稿乐观锁版本，每次整份替换递增，初始为 1',
    updated_at_ms BIGINT      NOT NULL COMMENT '最近替换时间，UTC 纪元毫秒',
    PRIMARY KEY (channel_id, business_day)
) COMMENT = '播出草稿表，每个频道+业务日一份';

CREATE TABLE IF NOT EXISTS playout_draft_segment (
    id           VARCHAR(64) NOT NULL COMMENT '片段 ID，客户端指定或系统生成',
    channel_id   VARCHAR(64) NOT NULL COMMENT '所属频道 ID',
    business_day DATE        NOT NULL COMMENT '所属业务日，Asia/Shanghai 日历日',
    asset_id     VARCHAR(64) NOT NULL COMMENT '播出素材 ID',
    start_ms     BIGINT      NOT NULL COMMENT '播出开始（含），UTC 纪元毫秒，不得跨日',
    end_ms       BIGINT      NOT NULL COMMENT '播出结束（不含），UTC 纪元毫秒，不得跨日',
    PRIMARY KEY (id),
    KEY idx_draft_segment (channel_id, business_day)
) COMMENT = '草稿片段表，随草稿整份替换而整体重建';

CREATE TABLE IF NOT EXISTS playout_publication (
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '发布快照自增 ID',
    channel_id        VARCHAR(64) NOT NULL COMMENT '频道 ID',
    business_day      DATE        NOT NULL COMMENT '业务日，Asia/Shanghai 日历日',
    published_version BIGINT      NOT NULL COMMENT '发布版本，每频道+业务日从 1 起递增',
    draft_version     BIGINT      NOT NULL COMMENT '发布时对应的草稿版本',
    created_at_ms     BIGINT      NOT NULL COMMENT '发布时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_publication_version (channel_id, business_day, published_version)
) COMMENT = '发布快照表，快照只读，授权撤销不改写历史快照';

CREATE TABLE IF NOT EXISTS playout_publication_segment (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '快照片段自增 ID',
    publication_id BIGINT      NOT NULL COMMENT '所属发布快照 ID',
    segment_id     VARCHAR(64) NOT NULL COMMENT '来源草稿片段 ID',
    asset_id       VARCHAR(64) NOT NULL COMMENT '播出素材 ID',
    grant_id       BIGINT      NOT NULL COMMENT '发布时完整覆盖该片段的授权 ID，播出查询据此实时判定撤销状态',
    start_ms       BIGINT      NOT NULL COMMENT '播出开始（含），UTC 纪元毫秒',
    end_ms         BIGINT      NOT NULL COMMENT '播出结束（不含），UTC 纪元毫秒',
    PRIMARY KEY (id),
    KEY idx_pub_segment (publication_id)
) COMMENT = '发布快照片段表，只读';

CREATE TABLE IF NOT EXISTS playout_request (
    request_id    VARCHAR(64)  NOT NULL COMMENT '幂等请求 ID，客户端生成',
    operation     VARCHAR(32)  NOT NULL COMMENT '操作类型：REPLACE_DRAFT / PUBLISH / REVOKE_GRANT / CREATE_RATING_WINDOW / UPDATE_RATING_WINDOW / BREAKIN',
    params_hash   VARCHAR(64)  NOT NULL COMMENT '业务参数（不含 requestId）的 SHA-256，十六进制',
    response_body TEXT         NULL COMMENT '成功时的响应 JSON；失败请求回滚不占用 requestId',
    created_at_ms BIGINT       NOT NULL COMMENT '记录创建时间，UTC 纪元毫秒',
    PRIMARY KEY (request_id)
) COMMENT = '幂等去重表，与业务结果同事务提交';

CREATE TABLE IF NOT EXISTS playout_rating_window (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '管控时段自增 ID',
    channel_id    VARCHAR(64) NOT NULL COMMENT '所属频道 ID',
    business_day  DATE        NOT NULL COMMENT '运营日，Asia/Shanghai 日历日',
    start_ms      BIGINT      NOT NULL COMMENT '管控开始（含），UTC 纪元毫秒，须落在运营日内',
    end_ms        BIGINT      NOT NULL COMMENT '管控结束（不含），UTC 纪元毫秒，区间左闭右开，须落在运营日内',
    max_rating    VARCHAR(8)  NOT NULL COMMENT '时段内允许的最高分级：G / PG / MATURE',
    created_at_ms BIGINT      NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    updated_at_ms BIGINT      NOT NULL COMMENT '最近修改时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    KEY idx_rating_window (channel_id, business_day)
) COMMENT = '内容分级管控时段表，同频道同运营日时段不得重叠（端点相接合法）；未落入任何时段的时间不限制分级';

CREATE TABLE IF NOT EXISTS playout_rating_check (
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '校验记录自增 ID',
    publication_id    BIGINT      NULL COMMENT '所属发布快照 ID；校验未通过、发布被拦截时为 NULL',
    channel_id        VARCHAR(64) NOT NULL COMMENT '频道 ID',
    business_day      DATE        NOT NULL COMMENT '业务日，Asia/Shanghai 日历日',
    segment_id        VARCHAR(64) NOT NULL COMMENT '被校验的草稿片段 ID',
    asset_id          VARCHAR(64) NOT NULL COMMENT '片段素材 ID',
    rating            VARCHAR(8)  NOT NULL COMMENT '参与校验的素材分级；未声明分级的素材按 MATURE 记录',
    window_id         BIGINT      NULL COMMENT '命中的管控时段 ID；片段未落入任何时段（无限制）时为 NULL',
    window_max_rating VARCHAR(8)  NULL COMMENT '命中时段允许的最高分级；未命中时段时为 NULL',
    verdict           VARCHAR(8)  NOT NULL COMMENT '校验结论：PASS 通过 / FAIL 越级',
    created_at_ms     BIGINT      NOT NULL COMMENT '校验时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    KEY idx_rating_check (channel_id, business_day)
) COMMENT = '发布分级校验记录表；通过记录随发布快照同事务写入，拦截记录在发布回滚后独立事务补写，只读';

CREATE TABLE IF NOT EXISTS playout_breakin (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '插播自增 ID',
    channel_id    VARCHAR(64) NOT NULL COMMENT '频道 ID',
    asset_id      VARCHAR(64) NOT NULL COMMENT '插播素材 ID',
    at_ms         BIGINT      NOT NULL COMMENT '插播时刻，UTC 纪元毫秒',
    request_id    VARCHAR(64) NOT NULL COMMENT '创建插播的幂等请求 ID',
    created_at_ms BIGINT      NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    KEY idx_breakin (channel_id, at_ms)
) COMMENT = '紧急插播表，创建前须通过插播时刻所属管控时段的分级校验';
