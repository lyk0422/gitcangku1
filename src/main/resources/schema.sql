-- 播出编排与授权回退业务表结构（MySQL 8）。
-- 时间约定：所有 *_ms 列为 UTC 纪元毫秒（BIGINT），API 层统一使用 Asia/Shanghai、毫秒精度 ISO 8601；
-- 业务日 business_day 为 Asia/Shanghai 时区下的日历日。

CREATE TABLE IF NOT EXISTS playout_asset (
    id           VARCHAR(64)  NOT NULL COMMENT '素材稳定 ID，客户端指定或系统生成，创建后不变',
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
    operation     VARCHAR(32)  NOT NULL COMMENT '操作类型：REPLACE_DRAFT / PUBLISH / REVOKE_GRANT / CREATE_OVERRIDE / CANCEL_OVERRIDE / PULL_LEASE / RENEW_LEASE',
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

CREATE TABLE IF NOT EXISTS playout_edge_client (
    client_key    VARCHAR(64) NOT NULL COMMENT '播出端客户端键，客户端指定，创建后不变',
    created_at_ms BIGINT      NOT NULL COMMENT '首次登记时间，UTC 纪元毫秒',
    PRIMARY KEY (client_key)
) COMMENT = '播出端客户端表，用于按客户端串行化租约拉取与续租';

CREATE TABLE IF NOT EXISTS playout_lease (
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '租约自增 ID',
    client_key        VARCHAR(64) NOT NULL COMMENT '播出端客户端键',
    channel_id        VARCHAR(64) NOT NULL COMMENT '绑定频道 ID',
    business_day      DATE        NOT NULL COMMENT '业务日，Asia/Shanghai 日历日',
    publication_id    BIGINT      NOT NULL COMMENT '绑定的发布快照 ID，租约存续期间不变，新发布不替换',
    published_version BIGINT      NOT NULL COMMENT '绑定的发布版本号，冗余便于查询',
    lease_epoch       BIGINT      NOT NULL COMMENT '租约纪元，每客户端+业务日从 1 起递增，续租推进',
    status            VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 生效中 / COMPLETED 全部确认 / EXPIRED 已过期',
    expires_at_ms     BIGINT      NOT NULL COMMENT '租约到期时间（不含），UTC 纪元毫秒',
    created_at_ms     BIGINT      NOT NULL COMMENT '租约创建时间，UTC 纪元毫秒',
    renewed_at_ms     BIGINT      NULL COMMENT '最近续租时间，UTC 纪元毫秒；未续租为 NULL',
    completed_at_ms   BIGINT      NULL COMMENT '全部分段确认完成时间，UTC 纪元毫秒；未完成为 NULL',
    PRIMARY KEY (id),
    KEY idx_lease_client (client_key, business_day, status),
    KEY idx_lease_publication (publication_id, status, expires_at_ms)
) COMMENT = '播出端版本租约表，每客户端+业务日最多一个 ACTIVE 租约';

CREATE TABLE IF NOT EXISTS playout_lease_segment (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '租约分段快照自增 ID',
    lease_id      BIGINT      NOT NULL COMMENT '所属租约 ID',
    seq           INT         NOT NULL COMMENT '分段顺序，从 1 开始，按播出开始时间升序',
    segment_id    VARCHAR(64) NOT NULL COMMENT '发布快照片段 ID',
    asset_id      VARCHAR(64) NOT NULL COMMENT '播出素材 ID',
    grant_id      BIGINT      NOT NULL COMMENT '发布时选定的授权 ID',
    grant_revoked TINYINT(1)  NOT NULL COMMENT '拉取快照时刻的授权判定：0 未撤销，1 已撤销；快照后不回写',
    start_ms      BIGINT      NOT NULL COMMENT '分段开始（含），UTC 纪元毫秒',
    end_ms        BIGINT      NOT NULL COMMENT '分段结束（不含），UTC 纪元毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_lease_segment_seq (lease_id, seq)
) COMMENT = '租约分段快照表，创建后只读不回写';

CREATE TABLE IF NOT EXISTS playout_lease_override (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '租约插播快照自增 ID',
    lease_id     BIGINT      NOT NULL COMMENT '所属租约 ID',
    override_key VARCHAR(64) NOT NULL COMMENT '紧急插播键',
    asset_id     VARCHAR(64) NOT NULL COMMENT '插播素材 ID',
    grant_id     BIGINT      NOT NULL COMMENT '插播创建时指定的授权 ID',
    priority     TINYINT     NOT NULL COMMENT '优先级，1～9，数字越大优先级越高',
    start_ms     BIGINT      NOT NULL COMMENT '插播开始（含），UTC 纪元毫秒',
    end_ms       BIGINT      NOT NULL COMMENT '插播结束（不含），UTC 纪元毫秒',
    PRIMARY KEY (id),
    KEY idx_lease_override (lease_id)
) COMMENT = '租约插播快照表，记录拉取时刻 ACTIVE 插播，创建后只读不回写';

CREATE TABLE IF NOT EXISTS playout_lease_ack (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '分段确认自增 ID',
    ack_key       VARCHAR(64) NOT NULL COMMENT '确认幂等键，客户端生成，全局唯一；失败回滚不占键',
    lease_id      BIGINT      NOT NULL COMMENT '所属租约 ID',
    lease_epoch   BIGINT      NOT NULL COMMENT '确认时的租约纪元，须与租约当前纪元一致',
    seq           INT         NOT NULL COMMENT '确认的分段顺序号，只能按顺序确认下一个未确认分段',
    segment_id    VARCHAR(64) NOT NULL COMMENT '确认的分段 ID',
    played_at_ms  BIGINT      NOT NULL COMMENT '播出端上报的实际播出时刻，UTC 纪元毫秒，须落在分段时窗内',
    created_at_ms BIGINT      NOT NULL COMMENT '确认受理时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_lease_ack_key (ack_key),
    UNIQUE KEY uk_lease_ack_seq (lease_id, seq)
) COMMENT = '分段确认表，按分段顺序递增确认，确认后不回写';
