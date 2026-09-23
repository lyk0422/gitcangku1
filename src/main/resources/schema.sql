-- 禁飞区航线审查：H2（MODE=MySQL）建表脚本，应用启动时自动执行。
-- 数据仅保留在 JVM 生命周期内的内存库，不涉及跨进程恢复。

-- 全局空域版本（单行，初始版本 0；任一禁飞区创建/撤销使其加一）
CREATE TABLE IF NOT EXISTS airspace_meta (
    id              INT PRIMARY KEY COMMENT '固定为 1 的单行主键',
    global_version  BIGINT NOT NULL COMMENT '当前全局空域版本号，初始 0；禁飞区每次创建或撤销后加一'
) COMMENT = '全局空域版本元数据';

-- 事务协调锁：单行。审核与禁飞区变更事务都对该行做真实更新（touched 加一），
-- 借助行级排他锁（H2 MVStore 与 MySQL InnoDB 语义一致）串行化，
-- 保证审核读取的空域版本与全部禁飞区来自同一已提交状态。
CREATE TABLE IF NOT EXISTS coord_lock (
    id       INT PRIMARY KEY COMMENT '固定为 1 的单行主键',
    touched  BIGINT NOT NULL COMMENT '仅用于产生真实行更新以加行级排他锁的计数器，无业务含义'
) COMMENT = '审核与区域变更事务协调锁';

-- 禁飞区：zoneId 唯一；非退化轴对齐闭矩形；只能创建或撤销
CREATE TABLE IF NOT EXISTS no_fly_zone (
    zone_id          VARCHAR(64) PRIMARY KEY COMMENT '禁飞区唯一标识',
    x_min            INT NOT NULL COMMENT '矩形左边界（含），单位米',
    y_min            INT NOT NULL COMMENT '矩形下边界（含），单位米',
    x_max            INT NOT NULL COMMENT '矩形右边界（含），单位米，x_min < x_max',
    y_max            INT NOT NULL COMMENT '矩形上边界（含），单位米，y_min < y_max',
    status           VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效参与审核；REVOKED 已撤销不参与审核',
    created_version  BIGINT NOT NULL COMMENT '创建生效时的全局空域版本',
    revoked_version  BIGINT NULL COMMENT '撤销生效时的全局空域版本；NULL 表示仍有效'
) COMMENT = '禁飞区（非退化轴对齐闭矩形，只能创建或撤销）';

-- 航线当前状态：routeId 唯一，版本从 1 开始，替换成功加一
-- touch 仅用于审核事务对该行产生真实更新以加行级写锁，与替换操作互斥
CREATE TABLE IF NOT EXISTS route (
    route_id  VARCHAR(64) PRIMARY KEY COMMENT '航线唯一标识',
    version   INT NOT NULL COMMENT '当前航线版本，初始 1，每次成功替换加一',
    touch     BIGINT NOT NULL COMMENT '仅用于审核事务加行级写锁的计数器，无业务含义'
) COMMENT = '航线当前版本状态';

-- 航线点（当前版本，2~50 个，按 seq 顺序连接）
CREATE TABLE IF NOT EXISTS route_point (
    route_id  VARCHAR(64) NOT NULL COMMENT '所属航线标识',
    seq       INT NOT NULL COMMENT '点序号，从 0 开始按顺序连接',
    x         INT NOT NULL COMMENT '航点 X 坐标，单位米，范围 [-100000,100000]',
    y         INT NOT NULL COMMENT '航点 Y 坐标，单位米，范围 [-100000,100000]',
    PRIMARY KEY (route_id, seq)
) COMMENT = '航线当前版本有序航点';

-- 审核不可变结果
CREATE TABLE IF NOT EXISTS review (
    review_id         VARCHAR(64) PRIMARY KEY COMMENT '审核记录唯一标识（不可变）',
    route_id          VARCHAR(64) NOT NULL COMMENT '被审核航线标识',
    route_version     INT NOT NULL COMMENT '审核明确指定的航线版本',
    airspace_version  BIGINT NOT NULL COMMENT '审核明确指定的空域版本',
    conclusion        VARCHAR(16) NOT NULL COMMENT '保存时的原结论：CLEAR 通过或 BLOCKED 命中，永不改变',
    hit_zone_ids      CLOB NOT NULL COMMENT '命中的全部 zoneId，字典序去重后逗号拼接；未命中为空串',
    points_snapshot   VARCHAR(4000) NOT NULL COMMENT '审核时航点不可变快照，格式 x,y;x,y',
    request_id        VARCHAR(64) NOT NULL COMMENT '提交审核的写操作请求标识',
    created_at        BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）'
) COMMENT = '审核不可变结果';

CREATE UNIQUE INDEX IF NOT EXISTS ux_review_request ON review (request_id);

-- 写操作幂等去重：同键同参重放原结果，异参冲突；失败不占键
CREATE TABLE IF NOT EXISTS request_dedup (
    request_id     VARCHAR(64) PRIMARY KEY COMMENT '写操作全局唯一请求标识',
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW/PERMIT_ISSUE/PERMIT_REVOKE/FLIGHT_REVIEW',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 十六进制摘要，用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';

-- 豁免包：permitKey 唯一；为一个精确航线版本签发；签发后不可修改，只能整体撤销未使用余额
CREATE TABLE IF NOT EXISTS permit (
    permit_id      VARCHAR(64) PRIMARY KEY COMMENT '豁免包唯一标识 permitKey',
    route_id       VARCHAR(64) NOT NULL COMMENT '豁免包绑定的航线标识',
    route_version  INT NOT NULL COMMENT '豁免包绑定的精确航线版本；航线修订后旧包不再匹配',
    status         VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效可核销；REVOKED 已撤销，未使用余额作废',
    version        INT NOT NULL COMMENT '豁免包版本：签发为 1，撤销推进为 2；审核快照冻结该版本',
    touch          BIGINT NOT NULL COMMENT '仅用于核销/撤销事务产生真实行更新以加行级写锁的计数器，无业务含义',
    request_id     VARCHAR(64) NOT NULL COMMENT '签发操作的请求标识',
    issued_at      BIGINT NOT NULL COMMENT '签发时间，epoch 毫秒（UTC）',
    revoked_at     BIGINT NULL COMMENT '撤销时间，epoch 毫秒（UTC）；NULL 表示未撤销'
) COMMENT = '豁免包（绑定精确航线版本，签发后不可修改，可撤销）';

CREATE INDEX IF NOT EXISTS ix_permit_route ON permit (route_id, route_version, status);

-- 豁免包区域项：同一豁免包内 regionKey 不可重复；含 UTC 有效区间与 1~100 次额度
CREATE TABLE IF NOT EXISTS permit_item (
    permit_id      VARCHAR(64) NOT NULL COMMENT '所属豁免包标识',
    region_key     VARCHAR(64) NOT NULL COMMENT '豁免区域标识（对应禁飞区 zoneId）',
    region_version BIGINT NOT NULL COMMENT '审批时区域版本（禁飞区创建生效的全局空域版本）；核销时必须仍匹配',
    valid_from     BIGINT NOT NULL COMMENT 'UTC 有效区间起点，epoch 毫秒（含端点）',
    valid_to       BIGINT NOT NULL COMMENT 'UTC 有效区间终点，epoch 毫秒（含端点），valid_from < valid_to',
    quota          INT NOT NULL COMMENT '签发额度，1~100 次',
    remaining      INT NOT NULL COMMENT '剩余额度，初始等于 quota；每次命中核销扣 1，撤销后冻结',
    PRIMARY KEY (permit_id, region_key)
) COMMENT = '豁免包区域项（每包 1~10 项，regionKey 包内唯一）';

-- 核销流水：每次 CLEAR 核销对每个命中区域写一条，历史核销不可回写或回滚删除
CREATE TABLE IF NOT EXISTS permit_redeem (
    redeem_id      VARCHAR(64) PRIMARY KEY COMMENT '核销流水唯一标识',
    permit_id      VARCHAR(64) NOT NULL COMMENT '本次核销使用的豁免包标识',
    region_key     VARCHAR(64) NOT NULL COMMENT '被扣减额度的区域标识（未命中区域不消费）',
    flight_key     VARCHAR(64) NOT NULL COMMENT '触发核销的飞行审核标识',
    balance_before INT NOT NULL COMMENT '扣减前该项剩余额度',
    balance_after  INT NOT NULL COMMENT '扣减后该项剩余额度（=balance_before-1）',
    review_at      BIGINT NOT NULL COMMENT '审核时刻，epoch 毫秒（UTC）',
    created_at     BIGINT NOT NULL COMMENT '核销落库时间，epoch 毫秒（UTC）'
) COMMENT = '豁免额度核销流水（只追加，不可变）';

CREATE INDEX IF NOT EXISTS ix_redeem_permit ON permit_redeem (permit_id, created_at);

-- 带豁免核销的飞行审核：每次评估写一条不可变快照（含 BLOCKED 失败尝试）；
-- 同 flightKey 的 CLEAR 最终审核只能有一次（final_key 唯一，BLOCKED 行该列为 NULL）。
-- BLOCKED 不扣额度且不锁定 flightKey，修正（补发豁免包、改用新航线版本等）后可同键复用；
-- CLEAR 已存在后，同键同参重放原快照、不重复扣额，异参 409。
CREATE TABLE IF NOT EXISTS flight_review (
    review_id        VARCHAR(64) PRIMARY KEY COMMENT '审核快照唯一标识（代理主键，每次评估一条）',
    flight_key       VARCHAR(64) NOT NULL COMMENT '飞行审核标识；同键 CLEAR 成功只能一次，BLOCKED 失败后可修正复用',
    final_key       VARCHAR(64) NULL COMMENT '仅 CLEAR 最终审核填写为 flight_key，BLOCKED 为 NULL；唯一约束保证同键只形成一次成功审核',
    finalized        BOOLEAN NOT NULL COMMENT '是否为最终成功审核：CLEAR 为 TRUE，BLOCKED 尝试为 FALSE',
    request_id       VARCHAR(64) NOT NULL COMMENT '审核写操作的请求标识（幂等用）',
    route_id         VARCHAR(64) NOT NULL COMMENT '被审核航线标识',
    route_version    INT NOT NULL COMMENT '一致视图中冻结的航线版本',
    airspace_version BIGINT NOT NULL COMMENT '一致视图中冻结的全局空域版本',
    permit_id        VARCHAR(64) NULL COMMENT 'CLEAR 核销使用的豁免包标识；无命中或 BLOCKED 时为 NULL',
    permit_version   INT NULL COMMENT '核销时豁免包版本（冻结）；未使用豁免时为 NULL',
    review_at        BIGINT NOT NULL COMMENT '审核指定的 UTC 时刻，epoch 毫秒',
    conclusion       VARCHAR(16) NOT NULL COMMENT '冻结结论：CLEAR（含豁免核销或无命中）或 BLOCKED',
    response_json    CLOB NOT NULL COMMENT '首次审核完整响应 JSON 快照（几何命中、缺失/过期/耗尽项、各项核销前后余额），重放原样返回',
    created_at       BIGINT NOT NULL COMMENT '审核落库时间，epoch 毫秒（UTC）'
) COMMENT = '飞行审核不可变快照（空域/航线/permit 版本、几何命中与核销前后余额）';

CREATE UNIQUE INDEX IF NOT EXISTS ux_flight_review_final ON flight_review (final_key);
CREATE INDEX IF NOT EXISTS ix_flight_review_flight ON flight_review (flight_key, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS ux_flight_review_request ON flight_review (request_id);
