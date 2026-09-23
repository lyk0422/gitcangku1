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
    version           BIGINT      NOT NULL DEFAULT 0 COMMENT '频道版本，每次编排发布、插播开始/结束等频道级状态变化时递增，切换单据此乐观仲裁',
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

-- ========== 主备播出链路、租约、回执与切换仲裁 ==========

CREATE TABLE IF NOT EXISTS playout_link (
    channel_id    VARCHAR(64) NOT NULL COMMENT '频道 ID',
    role          VARCHAR(16) NOT NULL COMMENT '链路角色：PRIMARY 主 / BACKUP 备',
    link_id       VARCHAR(64) NOT NULL COMMENT '链路 ID，同频道内主备不同',
    healthy       TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否健康：1 健康可被选为目标，0 不健康',
    cached_schedule_version BIGINT NOT NULL DEFAULT 0 COMMENT '该链路已缓存的频道当前编排版本；无发布时为 0',
    updated_at_ms BIGINT      NOT NULL COMMENT '最近变更时间，UTC 纪元毫秒',
    PRIMARY KEY (channel_id, role),
    UNIQUE KEY uk_link_channel_link (channel_id, link_id)
) COMMENT = '主备链路配置表，每频道 PRIMARY/BACKUP 各一条';

CREATE TABLE IF NOT EXISTS playout_lease (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '租约自增 ID',
    channel_id    VARCHAR(64)  NOT NULL COMMENT '频道 ID',
    link_id       VARCHAR(64)  NOT NULL COMMENT '持有该租约的链路 ID',
    generation    BIGINT       NOT NULL COMMENT '租约世代，同一频道单调递增，每次切换 +1',
    status        VARCHAR(16)  NOT NULL COMMENT '状态：ACTIVE 活动中 / ENDED 已结束；任一时刻每频道至多一条 ACTIVE',
    active_marker VARCHAR(8)   NULL COMMENT '活动标记：ACTIVE 时由应用置为固定值 A，ENDED 时置 NULL，配合唯一索引保证每频道至多一条 ACTIVE',
    cut_sequence  BIGINT       NOT NULL COMMENT '冻结切点：该租约创建时冻结的已确认游标，新链路从此 sequence+1 续播',
    schedule_snapshot TEXT     NULL COMMENT '冻结的各业务日编排发布版本，JSON 对象 {"业务日":版本}；非切换产生的首代租约为 NULL',
    override_snapshot TEXT     NULL COMMENT '冻结的未决紧急插播栈 JSON 数组；首代租约为 NULL',
    started_at_ms BIGINT       NOT NULL COMMENT '租约开始时间，UTC 纪元毫秒',
    ended_at_ms   BIGINT       NULL COMMENT '租约结束时间，UTC 纪元毫秒；ACTIVE 时为 NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uk_lease_generation (channel_id, generation),
    UNIQUE KEY uk_lease_active (channel_id, active_marker)
) COMMENT = '链路活动租约，切换在同一事务内结束旧租约并创建新世代租约，不出现两条活动租约或空窗';
CREATE TABLE IF NOT EXISTS playout_failover_order (
    failover_key       VARCHAR(64) NOT NULL COMMENT '切换单全局唯一键，客户端指定，创建后不变',
    channel_id         VARCHAR(64) NOT NULL COMMENT '频道 ID',
    channel_version    BIGINT      NOT NULL COMMENT '创建切换单时提交并冻结的频道版本',
    source_link_id     VARCHAR(64) NOT NULL COMMENT '源链路 ID（当前 ACTIVE 所属链路）',
    target_link_id     VARCHAR(64) NOT NULL COMMENT '目标链路 ID（拟切换到的健康备链路）',
    source_last_sequence  BIGINT   NOT NULL COMMENT '创建时双方提交的源链路最后回执 sequence',
    target_last_sequence  BIGINT   NOT NULL COMMENT '创建时双方提交的目标链路最后回执 sequence',
    cutover_at_ms      BIGINT      NOT NULL COMMENT '计划切换时刻，UTC 纪元毫秒',
    status             VARCHAR(16)  NOT NULL COMMENT '状态：ACTIVATED 已激活 / REJECTED 已拒绝（校验或重验失败）',
    reject_code        VARCHAR(48) NULL COMMENT '拒绝错误码；ACTIVATED 时为 NULL',
    new_generation     BIGINT      NULL COMMENT '激活成功后新租约世代；拒绝时为 NULL',
    cut_sequence       BIGINT      NULL COMMENT '激活成功后冻结的切点 sequence；拒绝时为 NULL',
    created_request_id VARCHAR(64) NOT NULL COMMENT '创建/激活该切换单的幂等 requestId',
    created_at_ms      BIGINT      NOT NULL COMMENT '创建时间，UTC 纪元毫秒',
    activated_at_ms    BIGINT      NULL COMMENT '激活时间，UTC 纪元毫秒；未激活时为 NULL',
    PRIMARY KEY (failover_key)
) COMMENT = '主备切换单，failoverKey 唯一；预览不落单，激活事务内整体重验，任一变化 409/422';

CREATE TABLE IF NOT EXISTS playout_receipt (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '回执自增 ID',
    channel_id     VARCHAR(64)  NOT NULL COMMENT '频道 ID',
    link_id        VARCHAR(64)  NOT NULL COMMENT '回执来源链路 ID',
    generation     BIGINT       NOT NULL COMMENT '回执声称的租约世代',
    sequence_no    BIGINT       NOT NULL COMMENT '已确认播出 sequence，从 1 起连续递增',
    received_at_ms BIGINT       NOT NULL COMMENT '回执到达时间，UTC 纪元毫秒',
    disposition    VARCHAR(16)  NOT NULL COMMENT '归类：CURRENT 属于当前世代并推进游标 / LATE 旧世代或非活动链路的迟到回执，仅存档不推进频道游标；同链路同代重复回执命中唯一键不再结算',
    PRIMARY KEY (id),
    UNIQUE KEY uk_receipt_once (channel_id, link_id, generation, sequence_no)
) COMMENT = '链路播出回执，同频道同链路同世代同 sequence 唯一，重复回执只结算一次；源 CURRENT 与目标 CACHED 可同 sequence 并存';
CREATE INDEX idx_receipt_lease ON playout_receipt (channel_id, generation, sequence_no);
