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
    schedule_version  BIGINT      NOT NULL DEFAULT 0 COMMENT '频道编排版本（游标一致性基准）：每次成功发布在同事务内递增，切换时要求目标链路缓存版本与之相等',
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

-- ===== 主备播出链路与租约切换 =====

CREATE TABLE IF NOT EXISTS playout_channel_link (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '链路自增 ID',
    channel_id     VARCHAR(64) NOT NULL COMMENT '所属频道 ID',
    link_id        VARCHAR(64) NOT NULL COMMENT '链路 ID，每频道 PRIMARY / BACKUP 各一条',
    role           VARCHAR(16) NOT NULL COMMENT '链路角色：PRIMARY 主链路 / BACKUP 备链路',
    healthy        TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '健康上报：1 健康，0 不健康；切换激活时目标必须为 1',
    cached_version BIGINT      NOT NULL DEFAULT 0 COMMENT '链路已缓存的频道编排版本（schedule_version）；激活时必须与当前频道版本一致',
    updated_at_ms  BIGINT      NOT NULL COMMENT '最近健康/缓存上报时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_link (channel_id, link_id),
    UNIQUE KEY uk_role (channel_id, role)
) COMMENT = '频道主备链路表';

CREATE TABLE IF NOT EXISTS playout_link_lease (
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '租约自增 ID',
    channel_id        VARCHAR(64) NOT NULL COMMENT '所属频道 ID',
    link_id           VARCHAR(64) NOT NULL COMMENT '持约链路 ID',
    generation        BIGINT      NOT NULL COMMENT '租约世代：每频道从 1 起递增，切换即换世代',
    status            VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 持约中 / ENDED 已结束；同一频道至多一条 ACTIVE',
    confirmed_seq     BIGINT      NOT NULL DEFAULT 0 COMMENT '当前已确认 sequence（连续游标上沿，已确认 1..confirmed_seq）',
    schedule_version  BIGINT      NOT NULL COMMENT '该世代冻结的编排版本（激活时频道 schedule_version）',
    cutover_seq       BIGINT      NULL COMMENT '切点：该世代开始应播的第一条 sequence；NULL 表示频道初始世代，无切点',
    order_id          BIGINT      NULL COMMENT '产生该租约的切换单 ID；初始世代为 NULL',
    created_at_ms     BIGINT      NOT NULL COMMENT '创建（激活）时间，UTC 纪元毫秒',
    ended_at_ms       BIGINT      NULL COMMENT '结束时间，UTC 纪元毫秒；ACTIVE 时为 NULL',
    active_slot       VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN channel_id ELSE NULL END)
        COMMENT '生成列：ACTIVE 行等于 channel_id，其余为 NULL；唯一索引保证每频道至多一条 ACTIVE',
    PRIMARY KEY (id),
    UNIQUE KEY uk_lease_generation (channel_id, generation),
    UNIQUE KEY uk_active_slot (active_slot)
) COMMENT = '链路租约表：同一频道永远只有一条 ACTIVE 租约（uk_active_slot 数据库强制）';

CREATE TABLE IF NOT EXISTS playout_link_receipt (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '回执自增 ID',
    channel_id     VARCHAR(64) NOT NULL COMMENT '频道 ID',
    link_id        VARCHAR(64) NOT NULL COMMENT '回执来源链路 ID（按实际物理链路记录，含旧世代）',
    generation     BIGINT      NOT NULL COMMENT '回执声称的租约世代',
    seq            BIGINT      NOT NULL COMMENT '已播放确认的 sequence，从 1 起的连续整数',
    disposition    VARCHAR(16) NOT NULL COMMENT '裁决结果：SETTLED 结算并推进游标 / LATE 旧世代迟到仅存档 / DUPLICATE 本链路本世代重复 / STANDBY 非持约链路回执',
    received_at_ms BIGINT      NOT NULL COMMENT '回执到达时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_receipt (channel_id, link_id, generation, seq),
    KEY idx_receipt_query (channel_id, link_id, disposition, seq)
) COMMENT = '链路播放回执仲裁表：每条 (链路, 世代, seq) 只结算一次';

CREATE TABLE IF NOT EXISTS playout_link_override_sync (
    channel_id       VARCHAR(64) NOT NULL COMMENT '频道 ID',
    link_id          VARCHAR(64) NOT NULL COMMENT '链路 ID',
    override_key     VARCHAR(64) NOT NULL COMMENT '紧急插播键',
    synced           TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '该插播是否已同步到该链路：1 已同步，0 未同步；激活时所有未结束插播必须为 1',
    updated_at_ms    BIGINT      NOT NULL COMMENT '最近同步上报时间，UTC 纪元毫秒',
    PRIMARY KEY (channel_id, link_id, override_key)
) COMMENT = '紧急插播链路同步表；激活时存在未同步的未结束插播则阻止切换';

CREATE TABLE IF NOT EXISTS playout_failover_order (
    id                    BIGINT      NOT NULL AUTO_INCREMENT COMMENT '切换单自增 ID',
    failover_key          VARCHAR(64) NOT NULL COMMENT '切换单全局唯一键，客户端指定',
    channel_id            VARCHAR(64) NOT NULL COMMENT '频道 ID',
    channel_version       BIGINT      NOT NULL COMMENT '创建时提交的频道编排版本（schedule_version）',
    source_link_id        VARCHAR(64) NOT NULL COMMENT '源链路（当前 ACTIVE 租约持有链路）',
    target_link_id        VARCHAR(64) NOT NULL COMMENT '目标链路（新 ACTIVE 租约持有链路）',
    source_last_seq       BIGINT      NOT NULL COMMENT '创建时提交的源链路最后回执 sequence',
    target_last_seq       BIGINT      NOT NULL COMMENT '创建时提交的目标链路最后回执 sequence',
    cutover_at_ms         BIGINT      NOT NULL COMMENT '预定切换时刻，UTC 纪元毫秒',
    max_lag               BIGINT      NOT NULL COMMENT '允许的目标回执落后上限（条）；激活时源已确认与目标连续前缀之差超过该值不得激活',
    status                VARCHAR(16) NOT NULL COMMENT '状态：CREATED 已创建待激活 / ACTIVATED 已激活 / REJECTED 激活被拒（终结）',
    generation            BIGINT      NULL COMMENT '激活成功后的新租约世代；CREATED 时为 NULL',
    safe_cut_seq          BIGINT      NULL COMMENT '激活时冻结的安全切点（下一条应播 sequence）',
    schedule_version      BIGINT      NULL COMMENT '激活时冻结的编排版本',
    frozen_stack_json     TEXT        NULL COMMENT '激活时冻结的未决紧急插播栈快照 JSON（只读证据）',
    created_at_ms         BIGINT      NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    activated_at_ms       BIGINT      NULL COMMENT '激活时间，UTC 纪元毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_failover_key (failover_key),
    KEY idx_failover_channel (channel_id, status)
) COMMENT = '主备切换单：创建与激活两步；激活在单事务内重读全部证据，任一变化整单 409/422';
