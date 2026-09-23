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

-- 豁免包：permitKey 唯一，精确绑定一个 routeVersion；签发后不可修改，只能整包撤销
CREATE TABLE IF NOT EXISTS permit_package (
    permit_key     VARCHAR(64) PRIMARY KEY COMMENT '豁免包唯一标识，签发后不可修改',
    route_version  INT NOT NULL COMMENT '绑定的精确航线版本；仅审核该版本航线时可核销',
    status         VARCHAR(16) NOT NULL COMMENT '状态：ISSUED 已签发可核销；REVOKED 已撤销整包失效',
    permit_version INT NOT NULL COMMENT '豁免包版本：不可变，恒为 1，审核快照冻结该版本',
    request_id     VARCHAR(64) NOT NULL COMMENT '签发写操作请求标识（唯一）',
    revoked_at     BIGINT NULL COMMENT '撤销时间，epoch 毫秒（UTC）；NULL 表示未撤销',
    created_at     BIGINT NOT NULL COMMENT '签发时间，epoch 毫秒（UTC）',
    touch          BIGINT NOT NULL COMMENT '仅用于审核/撤销事务加行级排他锁的计数器，无业务含义'
) COMMENT = '豁免包（精确绑定一个 routeVersion，签发不可改，可撤销未使用余额）';

CREATE UNIQUE INDEX IF NOT EXISTS ux_permit_request ON permit_package (request_id);

-- 豁免包区域项：同一豁免包内 regionKey 唯一；UTC 半开有效区间 [valid_from, valid_to)
CREATE TABLE IF NOT EXISTS permit_item (
    permit_key     VARCHAR(64) NOT NULL COMMENT '所属豁免包标识',
    region_key     VARCHAR(64) NOT NULL COMMENT '区域（禁飞区）标识，同包内不可重复',
    region_version BIGINT NOT NULL COMMENT '区域版本，须与命中区域的生效空域版本一致',
    valid_from     BIGINT NOT NULL COMMENT '有效起始时间（含），epoch 毫秒（UTC）',
    valid_to       BIGINT NOT NULL COMMENT '有效结束时间（不含），epoch 毫秒（UTC），须晚于 valid_from',
    quota          INT NOT NULL COMMENT '签发额度，范围 1~100 次',
    remaining      INT NOT NULL COMMENT '当前剩余额度（次），每次成功核销扣 1；撤销不回写历史核销',
    touch          BIGINT NOT NULL COMMENT '仅用于审核事务对该项加行级排他锁的计数器，无业务含义',
    PRIMARY KEY (permit_key, region_key)
) COMMENT = '豁免包区域项（1~100 次额度，UTC 半开有效区间，同包 regionKey 唯一）';

-- 豁免额度核销流水（不可变）：一次 CLEAR 审核对每个命中区域各一条
CREATE TABLE IF NOT EXISTS permit_consumption (
    consumption_id  VARCHAR(64) PRIMARY KEY COMMENT '核销流水唯一标识（不可变）',
    review_id       VARCHAR(64) NOT NULL COMMENT '触发核销的航班审核标识',
    flight_key      VARCHAR(64) NOT NULL COMMENT '触发核销的航班业务标识',
    permit_key      VARCHAR(64) NOT NULL COMMENT '使用的豁免包标识',
    region_key      VARCHAR(64) NOT NULL COMMENT '被核销的命中区域标识',
    region_version  BIGINT NOT NULL COMMENT '核销时命中区域的生效空域版本',
    balance_before  INT NOT NULL COMMENT '扣减前剩余额度（次）',
    balance_after   INT NOT NULL COMMENT '扣减后剩余额度（次）',
    seq             INT NOT NULL COMMENT '同一次审核内流水顺序，从 0 开始',
    created_at      BIGINT NOT NULL COMMENT '核销时间，epoch 毫秒（UTC）'
) COMMENT = '豁免额度核销流水（不可变，撤销与后续审核不回写）';

CREATE INDEX IF NOT EXISTS ix_consumption_permit ON permit_consumption (permit_key);
CREATE INDEX IF NOT EXISTS ix_consumption_review ON permit_consumption (review_id);

-- 航班豁免审核不可变记录（仅 CLEAR 成功审核落库；BLOCKED 整体回滚不占用 flightKey）
CREATE TABLE IF NOT EXISTS flight_review (
    review_id        VARCHAR(64) PRIMARY KEY COMMENT '航班审核记录唯一标识（不可变）',
    flight_key       VARCHAR(64) NOT NULL COMMENT '航班业务标识，全局只能形成一次成功审核',
    route_id         VARCHAR(64) NOT NULL COMMENT '被审核航线标识',
    route_version    INT NOT NULL COMMENT '提交时一致视图中的航线版本',
    airspace_version BIGINT NOT NULL COMMENT '提交时一致视图中的空域版本',
    review_at        BIGINT NOT NULL COMMENT '客户端指定的审核时刻，epoch 毫秒（UTC）',
    conclusion       VARCHAR(16) NOT NULL COMMENT '落库记录恒为 CLEAR；BLOCKED 不形成记录',
    hit_region_keys  CLOB NOT NULL COMMENT '命中的全部 regionKey，字典序去重后逗号拼接；未命中为空串',
    permit_key       VARCHAR(64) NULL COMMENT '完成全部核销的同一豁免包标识；未命中任何区域时为 NULL',
    snapshot_json    CLOB NOT NULL COMMENT '审核快照 JSON：空域/航线/permit 版本、核销前后余额与几何命中',
    request_id       VARCHAR(64) NOT NULL COMMENT '审核写操作请求标识',
    request_hash     VARCHAR(64) NOT NULL COMMENT '审核请求规范化参数哈希，同 flightKey 异参 409 判定',
    created_at       BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）'
) COMMENT = '航班豁免审核不可变记录（同 flightKey 仅一次 CLEAR，重放不重复扣额）';

CREATE UNIQUE INDEX IF NOT EXISTS ux_flight_review_flight ON flight_review (flight_key);
CREATE UNIQUE INDEX IF NOT EXISTS ux_flight_review_request ON flight_review (request_id);
CREATE INDEX IF NOT EXISTS ix_flight_review_route ON flight_review (route_id);
