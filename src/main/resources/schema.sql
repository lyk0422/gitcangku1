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
    touch     BIGINT NOT NULL COMMENT '仅用于审核事务加行级写锁的计数器，无业务含义',
    status    VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待批准；APPROVED 已批准未起飞；DISPLACED 被紧急航线置换；DEPARTED 已起飞',
    priority  VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '最近一次审查声明的备降优先级：NORMAL 或 EMERGENCY',
    event_no  VARCHAR(64) NULL COMMENT 'EMERGENCY 优先级对应的事件编号；NORMAL 或尚未审查时为 NULL'
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
    priority          VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '本次审查声明的备降优先级：NORMAL 或 EMERGENCY',
    event_no          VARCHAR(64) NULL COMMENT 'EMERGENCY 审查的事件编号；NORMAL 为 NULL',
    bucket_key        VARCHAR(160) NULL COMMENT '规范化时空桶键 cellX:cellY:windowStartMin:windowEndMin；未声明时空段为 NULL',
    request_id        VARCHAR(64) NOT NULL COMMENT '提交审核的写操作请求标识',
    created_at        BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）'
) COMMENT = '审核不可变结果';

CREATE UNIQUE INDEX IF NOT EXISTS ux_review_request ON review (request_id);

-- 时空容量桶：bucketKey 唯一；未建桶的时空段不限制容量
CREATE TABLE IF NOT EXISTS capacity_bucket (
    bucket_key        VARCHAR(160) PRIMARY KEY COMMENT '规范化时空桶键：cellX:cellY:windowStartMin:windowEndMin',
    cell_x            INT NOT NULL COMMENT '空间单元 X 索引，无量纲',
    cell_y            INT NOT NULL COMMENT '空间单元 Y 索引，无量纲',
    window_start_min  BIGINT NOT NULL COMMENT '时间窗起始，epoch 分钟（UTC），由毫秒向下取整规范化',
    window_end_min    BIGINT NOT NULL COMMENT '时间窗结束，epoch 分钟（UTC），须大于起始',
    capacity          INT NOT NULL COMMENT '桶内容量上限（可同时占用的航线数），>= 1',
    created_at        BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）'
) COMMENT = '时空容量桶（未建桶的时空段容量不受限）';

-- 桶占用：一行表示一条航线占用一个桶；DISPLACED 时删除行释放容量
CREATE TABLE IF NOT EXISTS bucket_occupancy (
    bucket_key  VARCHAR(160) NOT NULL COMMENT '所属时空桶键',
    route_id    VARCHAR(64) NOT NULL COMMENT '占用航线标识',
    review_id   VARCHAR(64) NOT NULL COMMENT '批准该占用的审核记录标识',
    priority    VARCHAR(16) NOT NULL COMMENT '占用航线的备降优先级：NORMAL 或 EMERGENCY',
    event_no    VARCHAR(64) NULL COMMENT 'EMERGENCY 占用的事件编号；NORMAL 为 NULL',
    status      VARCHAR(16) NOT NULL COMMENT 'APPROVED 未起飞（NORMAL 可被抢占）；DEPARTED 已起飞（不可抢占）',
    created_at  BIGINT NOT NULL COMMENT '占用创建时间，epoch 毫秒（UTC）',
    PRIMARY KEY (bucket_key, route_id)
) COMMENT = '时空桶占用（航线被置换时删除对应行）';

-- 抢占不可变快照：一条记录对应一条被置换的 NORMAL 航线，创建后不改写业务字段
CREATE TABLE IF NOT EXISTS preemption (
    preemption_id           VARCHAR(64) PRIMARY KEY COMMENT '抢占记录唯一标识（不可变）',
    bucket_key              VARCHAR(160) NOT NULL COMMENT '发生抢占的时空桶键',
    route_id                VARCHAR(64) NOT NULL COMMENT '被置换的 NORMAL 航线标识',
    displaced_route_version INT NOT NULL COMMENT '被置换时航线的版本',
    emergency_route_id      VARCHAR(64) NOT NULL COMMENT '发起抢占的 EMERGENCY 航线标识',
    emergency_event_no      VARCHAR(64) NOT NULL COMMENT '紧急事件编号',
    emergency_review_id     VARCHAR(64) NOT NULL COMMENT '批准紧急航线的审核记录标识',
    state                   VARCHAR(16) NOT NULL COMMENT 'PENDING 未处理；RESUBMITTED 被置换航线已重新提交审查',
    open_key                VARCHAR(64) NULL COMMENT 'state=PENDING 时等于 route_id，处理后置 NULL；唯一索引保证同一航线仅一条未处理记录',
    created_at              BIGINT NOT NULL COMMENT '抢占时间，epoch 毫秒（UTC）',
    processed_at            BIGINT NULL COMMENT '被置换航线重新提交审查的时间，epoch 毫秒（UTC）；未处理为 NULL'
) COMMENT = '紧急抢占不可变快照（区域版本更新不改写）';

CREATE UNIQUE INDEX IF NOT EXISTS ux_preemption_open ON preemption (open_key);

-- 写操作幂等去重：同键同参重放原结果，异参冲突；失败不占键
CREATE TABLE IF NOT EXISTS request_dedup (
    request_id     VARCHAR(64) PRIMARY KEY COMMENT '写操作全局唯一请求标识',
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW/ROUTE_DEPART/CAPACITY_BUCKET_CREATE',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 十六进制摘要，用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';
