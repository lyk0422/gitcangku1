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

-- 航路走廊：corridorId 唯一；非退化轴对齐闭矩形；容量 1～50，只能上调
-- touch 仅用于预约创建/取消事务对该行产生真实更新以加行级写锁，按提交顺序串行化容量裁决
CREATE TABLE IF NOT EXISTS corridor (
    corridor_id  VARCHAR(64) PRIMARY KEY COMMENT '走廊唯一标识',
    x_min        INT NOT NULL COMMENT '矩形左边界（含），单位米',
    y_min        INT NOT NULL COMMENT '矩形下边界（含），单位米',
    x_max        INT NOT NULL COMMENT '矩形右边界（含），单位米，x_min < x_max',
    y_max        INT NOT NULL COMMENT '矩形上边界（含），单位米，y_min < y_max',
    capacity     INT NOT NULL COMMENT '同时容量上限，1～50；仅可上调不可下调',
    touch        BIGINT NOT NULL COMMENT '仅用于容量裁决事务加行级写锁的计数器，无业务含义',
    created_at   BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）',
    updated_at   BIGINT NOT NULL COMMENT '最近容量调整时间，epoch 毫秒（UTC）'
) COMMENT = '航路走廊（矩形区域与同时容量上限）';

-- 走廊预约：reservationKey 全局唯一；时间窗 UTC 左闭右开，时长 1～120 分钟
-- 取消仅置状态并记录取消时间，行保留作为历史
CREATE TABLE IF NOT EXISTS corridor_reservation (
    reservation_key  VARCHAR(64) PRIMARY KEY COMMENT '预约全局唯一业务键',
    corridor_id      VARCHAR(64) NOT NULL COMMENT '所属走廊标识',
    start_time       BIGINT NOT NULL COMMENT '预约开始时刻，epoch 毫秒（UTC），含（左闭）',
    end_time         BIGINT NOT NULL COMMENT '预约结束时刻，epoch 毫秒（UTC），不含（右开）；start_time < end_time，时长 1～120 分钟',
    review_id        VARCHAR(64) NOT NULL COMMENT '关联的已 CLEAR 审核结果标识，仅引用不消费不改写',
    status           VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 生效参与容量计数；CANCELLED 已取消立即移出容量计数但保留历史',
    request_id       VARCHAR(64) NOT NULL COMMENT '创建预约的写操作请求标识',
    created_at       BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）',
    cancelled_at     BIGINT NULL COMMENT '取消时间，epoch 毫秒（UTC）；NULL 表示未取消'
) COMMENT = '走廊时段预约（取消后保留历史）';

CREATE INDEX IF NOT EXISTS ix_reservation_corridor_time
    ON corridor_reservation (corridor_id, status, start_time, end_time);
CREATE UNIQUE INDEX IF NOT EXISTS ux_reservation_request ON corridor_reservation (request_id);

-- 写操作幂等去重：同键同参重放原结果，异参冲突；失败不占键
CREATE TABLE IF NOT EXISTS request_dedup (
    request_id     VARCHAR(64) PRIMARY KEY COMMENT '写操作全局唯一请求标识',
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 十六进制摘要，用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';
