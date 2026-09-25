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
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW/RUNWAY_CREATE/CLOSURE_CREATE/FLIGHT_CREATE/FLIGHT_REVIEW/FLIGHT_DEPART/FLIGHT_REROUTE/FLIGHT_CANCEL/FLIGHT_EMERGENCY_CONVERT',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 十六进制摘要，用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';

-- 跑道：runwayId 唯一，版本从 0 开始，每次关闭窗口登记成功后加一
CREATE TABLE IF NOT EXISTS runway (
    runway_id       VARCHAR(64) PRIMARY KEY COMMENT '跑道唯一标识',
    version         INT NOT NULL COMMENT '当前跑道版本，初始 0，每次关闭窗口登记成功后加一；登记关闭窗口须携带该版本',
    hourly_capacity INT NOT NULL COMMENT '每 UTC 小时起降容量（起飞与落地各计一次），必须 >= 1',
    touch           BIGINT NOT NULL COMMENT '仅用于事务加行级写锁的计数器，无业务含义'
) COMMENT = '机场跑道（含版本与小时容量）';

-- 跑道关闭窗口：UTC 左闭右开 [start_utc, end_utc)，同一跑道窗口不得重叠，端点相接合法
CREATE TABLE IF NOT EXISTS runway_closure (
    closure_id       VARCHAR(64) PRIMARY KEY COMMENT '关闭窗口唯一标识（服务端生成）',
    runway_id        VARCHAR(64) NOT NULL COMMENT '所属跑道标识',
    start_utc        BIGINT NOT NULL COMMENT '关闭开始时刻，epoch 毫秒（UTC），左闭',
    end_utc          BIGINT NOT NULL COMMENT '关闭结束时刻，epoch 毫秒（UTC），右开，必须大于 start_utc',
    allow_emergency  BOOLEAN NOT NULL COMMENT '是否允许紧急例外：TRUE 时 EMERGENCY 航班附事件号可通过该窗口',
    operator         VARCHAR(64) NOT NULL COMMENT '登记操作者标识',
    closure_key      VARCHAR(64) NOT NULL COMMENT '幂等键：指纹含跑道版本、规范化时段、例外标志与操作者；同键重放，失败不占键',
    runway_version   INT NOT NULL COMMENT '本次窗口登记生效后的跑道版本',
    created_at       BIGINT NOT NULL COMMENT '登记时间，epoch 毫秒（UTC）'
) COMMENT = '跑道关闭窗口（UTC 左闭右开，同跑道不重叠，端点相接合法）';

CREATE INDEX IF NOT EXISTS ix_closure_runway ON runway_closure (runway_id);

-- closureKey 指纹唯一：同键重放原窗口，失败不占键
CREATE UNIQUE INDEX IF NOT EXISTS ux_closure_key ON runway_closure (closure_key);

-- 航班：航线起降段计划与状态
CREATE TABLE IF NOT EXISTS flight (
    flight_id      VARCHAR(64) PRIMARY KEY COMMENT '航班唯一标识',
    route_id       VARCHAR(64) NOT NULL COMMENT '关联航线标识',
    route_type     VARCHAR(16) NOT NULL COMMENT '航线类型：NORMAL 普通；EMERGENCY 紧急',
    event_no       VARCHAR(64) NULL COMMENT '紧急事件号；EMERGENCY 经关闭窗口例外通过时必填，否则为 NULL',
    dep_runway_id  VARCHAR(64) NOT NULL COMMENT '起飞跑道标识',
    dep_time_utc   BIGINT NOT NULL COMMENT '计划起飞时刻，epoch 毫秒（UTC）',
    arr_runway_id  VARCHAR(64) NOT NULL COMMENT '落地跑道标识',
    arr_time_utc   BIGINT NOT NULL COMMENT '计划落地时刻，epoch 毫秒（UTC）',
    status         VARCHAR(16) NOT NULL COMMENT '状态：PENDING 待审查；APPROVED 已批准；RUNWAY_RISK 跑道风险；DEPARTED 已起飞；CANCELLED 已取消',
    approved_at    BIGINT NULL COMMENT '批准时间，epoch 毫秒（UTC）；未批准为 NULL',
    touch          BIGINT NOT NULL COMMENT '仅用于事务加行级写锁的计数器，无业务含义'
) COMMENT = '航班起降段计划与状态';

-- 跑道风险固化快照：新关闭窗口生效时命中且未起飞的已批准 NORMAL 航班
CREATE TABLE IF NOT EXISTS flight_risk (
    flight_id       VARCHAR(64) NOT NULL COMMENT '风险航班标识',
    closure_id      VARCHAR(64) NOT NULL COMMENT '触发风险的关闭窗口标识',
    runway_id       VARCHAR(64) NOT NULL COMMENT '关闭窗口所属跑道标识（快照）',
    start_utc       BIGINT NOT NULL COMMENT '关闭开始时刻快照，epoch 毫秒（UTC），左闭',
    end_utc         BIGINT NOT NULL COMMENT '关闭结束时刻快照，epoch 毫秒（UTC），右开',
    allow_emergency BOOLEAN NOT NULL COMMENT '关闭窗口紧急例外标志快照',
    operator        VARCHAR(64) NOT NULL COMMENT '登记操作者快照',
    runway_version  INT NOT NULL COMMENT '窗口生效跑道版本快照',
    snapshot_at     BIGINT NOT NULL COMMENT '快照固化时间，epoch 毫秒（UTC）',
    PRIMARY KEY (flight_id, closure_id)
) COMMENT = '航班跑道风险快照（窗口内容固化，不随后续变更）';

-- 航班批量审查结论（不可变）
CREATE TABLE IF NOT EXISTS flight_review (
    review_id   VARCHAR(64) PRIMARY KEY COMMENT '批量审查记录唯一标识（不可变）',
    request_id  VARCHAR(64) NOT NULL COMMENT '提交审查的写操作请求标识',
    approved    BOOLEAN NOT NULL COMMENT '整批是否批准：任一航线拒绝则为 FALSE 且无任何航班状态变更',
    created_at  BIGINT NOT NULL COMMENT '审查时间，epoch 毫秒（UTC）'
) COMMENT = '航班批量审查结论（不可变）';

-- 航班批量审查逐航线明细（不可变，原因码可区分）
CREATE TABLE IF NOT EXISTS flight_review_item (
    review_id  VARCHAR(64) NOT NULL COMMENT '所属批量审查记录标识',
    flight_id  VARCHAR(64) NOT NULL COMMENT '被审查航班标识',
    result     VARCHAR(16) NOT NULL COMMENT '单航线结果：APPROVED 通过；REJECTED 拒绝',
    reason     VARCHAR(64) NULL COMMENT '拒绝原因码：RUNWAY_CLOSED/EMERGENCY_EXCEPTION_NOT_ALLOWED/MISSING_EVENT_NO/CAPACITY_EXCEEDED/FLIGHT_NOT_REVIEWABLE；通过为 NULL',
    detail     VARCHAR(512) NULL COMMENT '拒绝细节（涉及窗口或容量槽位）；通过为 NULL',
    PRIMARY KEY (review_id, flight_id)
) COMMENT = '航班批量审查逐航线明细（不可变）';
