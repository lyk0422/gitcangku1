-- 播出编排与授权回退业务表结构（MySQL 8）。
-- 时间约定：所有 *_ms 列为 UTC 纪元毫秒（BIGINT），API 层统一使用 Asia/Shanghai、毫秒精度 ISO 8601；
-- 业务日 business_day 为 Asia/Shanghai 时区下的日历日。

CREATE TABLE IF NOT EXISTS playout_asset (
    id           VARCHAR(64)  NOT NULL COMMENT '素材稳定 ID，客户端指定或系统生成，创建后不变',
    sponsor_key  VARCHAR(64)  NULL COMMENT '关联赞助商键；NULL 表示无赞助素材，不参与排他冲突'
    duration_ms  BIGINT       NOT NULL COMMENT '素材时长，单位毫秒，正整数',
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
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE 生效 / REVOKED 已撤销；仅尚未开始的发布可撤销，撤销为终态',
    revoke_request_id VARCHAR(64)  NULL COMMENT '撤销发布的幂等请求 ID；未撤销时为 NULL',
    revoked_at_ms     BIGINT       NULL COMMENT '撤销时间，UTC 纪元毫秒；未撤销时为 NULL',
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
    sponsor_key    VARCHAR(64) NULL COMMENT '发布时固化的素材赞助商键快照；NULL 表示无赞助素材，不随素材后续变化改写'
    start_ms       BIGINT      NOT NULL COMMENT '播出开始（含），UTC 纪元毫秒',
    end_ms         BIGINT      NOT NULL COMMENT '播出结束（不含），UTC 纪元毫秒',
    PRIMARY KEY (id),
    KEY idx_pub_segment (publication_id)
) COMMENT = '发布快照片段表，只读';

CREATE TABLE IF NOT EXISTS playout_request (
    request_id    VARCHAR(64)  NOT NULL COMMENT '幂等请求 ID，客户端生成',
    operation     VARCHAR(32)  NOT NULL COMMENT '操作类型：REPLACE_DRAFT / PUBLISH / REVOKE_GRANT / CREATE_OVERRIDE / CANCEL_OVERRIDE',
    params_hash   VARCHAR(64)  NOT NULL COMMENT '业务参数（不含 requestId）的 SHA-256，十六进制',
    response_body TEXT         NULL COMMENT '成功时的响应 JSON；失败请求回滚不占用 requestId',
    created_at_ms BIGINT       NOT NULL COMMENT '记录创建时间，UTC 纪元毫秒',
    PRIMARY KEY (request_id)
) COMMENT = '幂等去重表，与业务结果同事务提交';

CREATE TABLE IF NOT EXISTS playout_emergency_override (
    override_key      VARCHAR(64)  NOT NULL COMMENT '紧急插播全局唯一键，客户端指定，创建后不变',
    channel_id        VARCHAR(64) NOT NULL COMMENT '插播频道 ID',
    asset_id          VARCHAR(64) NOT NULL COMMENT '插播普通素材 ID（不得为频道保底素材）',
    grant_id          BIGINT       NOT NULL COMMENT '创建时指定的授权 ID，须属于该频道与素材并完整覆盖区间；撤销后不自动换绑',
    priority          TINYINT      NOT NULL COMMENT '优先级，1～9，数字越大优先级越高',
    start_ms          BIGINT       NOT NULL COMMENT '插播开始（含），UTC 纪元毫秒',
    end_ms            BIGINT       NOT NULL COMMENT '插播结束（不含），UTC 纪元毫秒；区间左闭右开',
    business_day      DATE         NOT NULL COMMENT '插播所在业务日，Asia/Shanghai 日历日；区间不跨日',
    status            VARCHAR(16)  NOT NULL COMMENT '状态：ACTIVE 生效中 / CANCELLED 已取消；创建即 ACTIVE，只能取消不可改写',
    cancel_request_id VARCHAR(64)  NULL COMMENT '取消操作的幂等请求 ID；未取消时为 NULL',
    cancelled_at_ms   BIGINT       NULL COMMENT '取消时间，UTC 纪元毫秒；未取消时为 NULL',
    created_at_ms     BIGINT       NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    PRIMARY KEY (override_key),
    KEY idx_override_playout (channel_id, status, start_ms, end_ms, priority)
) COMMENT = '限时紧急插播表；不改写日草稿与发布快照，同频道同优先级 ACTIVE 区间不得重叠';
CREATE TABLE IF NOT EXISTS playout_sponsor_window (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '排他窗自增 ID',
    window_key     VARCHAR(64)  NOT NULL COMMENT '排他窗键，客户端指定或系统生成；同一键修订产生新版本',
    version        BIGINT       NOT NULL COMMENT '窗口版本，同一 window_key 从 1 起递增，修订生成新版本',
    sponsor_key    VARCHAR(64)  NOT NULL COMMENT '享有排他的赞助商键',
    business_day   DATE         NOT NULL COMMENT '运营日，Asia/Shanghai 日历日；窗口区间不跨日',
    start_ms       BIGINT       NOT NULL COMMENT '排他开始（含），UTC 纪元毫秒，区间左闭右开',
    end_ms         BIGINT       NOT NULL COMMENT '排他结束（不含），UTC 纪元毫秒，区间左闭右开',
    status         VARCHAR(16)  NOT NULL COMMENT '状态：ACTIVE 生效 / SUPERSEDED 已被修订取代 / DELETED 已删除；仅 ACTIVE 参与校验',
    created_at_ms  BIGINT       NOT NULL COMMENT '登记时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_window_key_version (window_key, version),
    KEY idx_window_sponsor (sponsor_key, status, start_ms, end_ms)
) COMMENT = '赞助排他窗表；同一赞助商在同一频道的 ACTIVE 窗口区间不得重叠';

CREATE TABLE IF NOT EXISTS playout_sponsor_window_channel (
    window_id  BIGINT      NOT NULL COMMENT '所属排他窗 ID',
    channel_id VARCHAR(64) NOT NULL COMMENT '排他覆盖的频道 ID；窗口频道集合非空',
    PRIMARY KEY (window_id, channel_id)
) COMMENT = '排他窗频道范围表，随窗口版本整体重建';

CREATE TABLE IF NOT EXISTS playout_publication_window_hit (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '命中记录自增 ID',
    publication_id BIGINT      NOT NULL COMMENT '所属发布快照 ID',
    window_key     VARCHAR(64) NOT NULL COMMENT '命中的排他窗键',
    window_version BIGINT      NOT NULL COMMENT '发布时命中的窗口版本，固化后不随窗口修订或删除改写',
    window_id      BIGINT      NOT NULL COMMENT '命中的排他窗 ID（对应版本行）',
    sponsor_key    VARCHAR(64) NOT NULL COMMENT '命中窗口的赞助商键快照',
    channel_id     VARCHAR(64) NOT NULL COMMENT '命中频道 ID',
    start_ms       BIGINT      NOT NULL COMMENT '命中窗口开始（含），UTC 纪元毫秒快照',
    end_ms         BIGINT      NOT NULL COMMENT '命中窗口结束（不含），UTC 纪元毫秒快照',
    created_at_ms  BIGINT      NOT NULL COMMENT '记录创建时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    KEY idx_hit_publication (publication_id)
) COMMENT = '发布命中排他窗历史表；撤销发布保留命中历史，旧记录不可回写';