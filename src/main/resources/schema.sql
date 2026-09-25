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
    seq               BIGINT NOT NULL COMMENT '审核提交单调序号（插入方持全局协调锁，按 MAX(seq)+1 生成），用于“最新审核”的确定性排序，避免同毫秒创建时依赖随机 reviewId',
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
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW/PRIORITY_REVIEW/DEPARTURE',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 十六进制摘要，用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';

-- ======================== 紧急备降优先级与时空容量抢占 ========================

-- 单元格容量配置：未配置的单元格按缺省容量 1 裁决；同一格网单元在每个时间桶上共享该容量
CREATE TABLE IF NOT EXISTS cell_capacity (
    cell_x      INT NOT NULL COMMENT '格网单元 X 下标，floor(坐标米 / 1000)',
    cell_y      INT NOT NULL COMMENT '格网单元 Y 下标，floor(坐标米 / 1000)',
    capacity    INT NOT NULL COMMENT '该单元每个时间桶的最大同时在飞/已批准航线数，至少 1；未配置行视为 1',
    updated_at  BIGINT NOT NULL COMMENT '最近一次容量设置时间，epoch 毫秒（UTC）',
    PRIMARY KEY (cell_x, cell_y),
    CONSTRAINT chk_cell_capacity CHECK (capacity >= 1)
) COMMENT = '格网单元容量配置（缺省容量 1）';

-- 审查批件：优先级审查通过（CLEAR）后产生，持有航线的时空桶占用；
-- 紧急抢占事务把被置换批件置为 DISPLACED 并删除其占用行，快照永不改写
CREATE TABLE IF NOT EXISTS clearance (
    clearance_id        VARCHAR(64) PRIMARY KEY COMMENT '批件唯一标识（不可变）',
    route_id            VARCHAR(64) NOT NULL COMMENT '航线标识',
    route_version       INT NOT NULL COMMENT '批准时的航线版本',
    airspace_version    BIGINT NOT NULL COMMENT '批准时的空域版本',
    priority            VARCHAR(16) NOT NULL COMMENT '备降优先级：NORMAL 普通；EMERGENCY 紧急（必须附事件编号）',
    event_no            VARCHAR(64) NULL COMMENT 'EMERGENCY 事件编号；NORMAL 为 NULL',
    status              VARCHAR(16) NOT NULL COMMENT '状态：APPROVED 已批准未起飞；DEPARTED 已起飞；DISPLACED 被紧急抢占置换；SUPERSEDED 同航线重新提交后作旧',
    review_id           VARCHAR(64) NOT NULL COMMENT '关联的不可变审核结论记录 review_id',
    segments_canonical  VARCHAR(4000) NOT NULL COMMENT '规范化时空桶不可变快照，格式 cellX,cellY,timeBucket;...（字典序去重）',
    request_id          VARCHAR(64) NOT NULL COMMENT '提交该批件的写操作请求标识',
    created_at          BIGINT NOT NULL COMMENT '批准时间，epoch 毫秒（UTC）',
    departed_at         BIGINT NULL COMMENT '起飞登记时间，epoch 毫秒（UTC）；NULL 表示未起飞',
    displaced_at        BIGINT NULL COMMENT '被抢占置换时间，epoch 毫秒（UTC）；NULL 表示未被置换',
    touch               BIGINT NOT NULL COMMENT '仅用于事务对批件行产生真实更新以加行级写锁的计数器，无业务含义'
) COMMENT = '航线审查批件（含备降优先级与时空占用状态）';

CREATE INDEX IF NOT EXISTS ix_clearance_route ON clearance (route_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_clearance_request ON clearance (request_id);

-- 时空桶占用行：仅在批件 APPROVED/DEPARTED 期间存在；DISPLACED/SUPERSEDED 时随事务删除。
-- time_bucket 为 floor(进入时刻毫秒 / 600000) 的 10 分钟桶下标
CREATE TABLE IF NOT EXISTS clearance_bucket (
    clearance_id  VARCHAR(64) NOT NULL COMMENT '持有占用的批件标识',
    route_id      VARCHAR(64) NOT NULL COMMENT '占用航线标识（冗余便于查询）',
    cell_x        INT NOT NULL COMMENT '格网单元 X 下标',
    cell_y        INT NOT NULL COMMENT '格网单元 Y 下标',
    time_bucket   BIGINT NOT NULL COMMENT '时间桶下标，floor(epoch 毫秒 / 600000)，UTC',
    priority      VARCHAR(16) NOT NULL COMMENT '占用时的备降优先级：NORMAL/EMERGENCY',
    PRIMARY KEY (clearance_id, cell_x, cell_y, time_bucket)
) COMMENT = '批件时空桶占用（同一批件在同一桶至多一行）';

CREATE INDEX IF NOT EXISTS ix_cb_bucket ON clearance_bucket (cell_x, cell_y, time_bucket);

-- 紧急抢占不可变快照（头）：一次紧急批准对应一条，内容永不改写
CREATE TABLE IF NOT EXISTS preemption (
    preemption_id           VARCHAR(64) PRIMARY KEY COMMENT '抢占记录唯一标识（不可变）',
    emergency_clearance_id  VARCHAR(64) NOT NULL COMMENT '紧急航线批件标识',
    emergency_route_id      VARCHAR(64) NOT NULL COMMENT '紧急航线标识',
    event_no                VARCHAR(64) NOT NULL COMMENT '紧急事件编号',
    airspace_version        BIGINT NOT NULL COMMENT '抢占裁决时的空域版本（冻结）',
    displaced_ids_canonical  VARCHAR(1000) NOT NULL COMMENT '全部被置换航线标识，字典序去重逗号拼接（冻结）',
    request_id              VARCHAR(64) NOT NULL COMMENT '紧急提交的写操作请求标识',
    created_at              BIGINT NOT NULL COMMENT '抢占裁决时间，epoch 毫秒（UTC）'
) COMMENT = '紧急抢占不可变快照头';

CREATE UNIQUE INDEX IF NOT EXISTS ux_preemption_request ON preemption (request_id);

-- 抢占快照明细：每个被置换 NORMAL 航线一条；快照字段不可变，
-- 仅 status/pending_key/resolved_* 随该航线重新提交审查而推进。
-- pending_key 仅在 PENDING 时等于航线标识，借助唯一索引保证同一 NORMAL 航线至多一条未处理抢占
CREATE TABLE IF NOT EXISTS preemption_item (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    preemption_id             VARCHAR(64) NOT NULL COMMENT '所属抢占快照标识',
    displaced_route_id        VARCHAR(64) NOT NULL COMMENT '被置换 NORMAL 航线标识',
    displaced_clearance_id    VARCHAR(64) NOT NULL COMMENT '被置换批件标识（冻结）',
    route_version_snapshot    INT NOT NULL COMMENT '被置换时的航线版本（冻结）',
    segments_canonical        VARCHAR(4000) NOT NULL COMMENT '被置换航线的规范化时空桶快照（冻结）',
    status                    VARCHAR(16) NOT NULL COMMENT '处理状态：PENDING 未处理（航线尚未重新批准）；RESOLVED 已重新提交并批准',
    pending_key               VARCHAR(64) NULL COMMENT 'PENDING 时等于 displaced_route_id，否则 NULL；唯一索引保证每航线至多一条未处理记录',
    created_at                BIGINT NOT NULL COMMENT '抢占发生时间，epoch 毫秒（UTC）',
    resolved_at               BIGINT NULL COMMENT '该航线重新批准时间，epoch 毫秒（UTC）；NULL 表示未处理',
    resolved_clearance_id     VARCHAR(64) NULL COMMENT '重新提交产生的新批件标识；NULL 表示未处理'
) COMMENT = '紧急抢占不可变快照明细（含每航线处理状态）';

CREATE UNIQUE INDEX IF NOT EXISTS ux_preemption_item_pending ON preemption_item (pending_key);
CREATE INDEX IF NOT EXISTS ix_preemption_item_preemption ON preemption_item (preemption_id);
