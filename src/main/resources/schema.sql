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

-- 禁飞区：zoneId 唯一；非退化轴对齐闭矩形；只能创建或撤销。
-- zone_version 为区域高度带配置的乐观锁版本：初始 1，每次高度带配置修改成功后加一。
CREATE TABLE IF NOT EXISTS no_fly_zone (
    zone_id          VARCHAR(64) PRIMARY KEY COMMENT '禁飞区唯一标识',
    x_min            INT NOT NULL COMMENT '矩形左边界（含），单位米',
    y_min            INT NOT NULL COMMENT '矩形下边界（含），单位米',
    x_max            INT NOT NULL COMMENT '矩形右边界（含），单位米，x_min < x_max',
    y_max            INT NOT NULL COMMENT '矩形上边界（含），单位米，y_min < y_max',
    status           VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效参与审核；REVOKED 已撤销不参与审核',
    created_version  BIGINT NOT NULL COMMENT '创建生效时的全局空域版本',
    revoked_version  BIGINT NULL COMMENT '撤销生效时的全局空域版本；NULL 表示仍有效',
    zone_version     INT NOT NULL COMMENT '区域高度带配置版本，初始 1，每次高度带配置修改成功后加一；修改请求携带 expectedVersion 做乐观锁'
) COMMENT = '禁飞区（非退化轴对齐闭矩形，只能创建或撤销）';

-- 航线当前状态：routeId 唯一，版本从 1 开始，替换成功加一
-- touch 仅用于审核事务对该行产生真实更新以加行级写锁，与替换操作互斥
-- cruise_altitude 为巡航高度（米）；start_time/end_time 为 UTC 起止时刻（epoch 毫秒，左闭右开）
CREATE TABLE IF NOT EXISTS route (
    route_id         VARCHAR(64) PRIMARY KEY COMMENT '航线唯一标识',
    version          INT NOT NULL COMMENT '当前航线版本，初始 1，每次成功替换加一',
    touch            BIGINT NOT NULL COMMENT '仅用于审核事务加行级写锁的计数器，无业务含义',
    cruise_altitude  INT NULL COMMENT '巡航高度，单位米；NULL 表示历史未登记高度的航线（仅二维审查）',
    start_time       BIGINT NULL COMMENT 'UTC 占用时段起始，epoch 毫秒（含）；NULL 表示未登记时段',
    end_time         BIGINT NULL COMMENT 'UTC 占用时段结束，epoch 毫秒（不含）；NULL 表示未登记时段'
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
    cruise_altitude   INT NULL COMMENT '审核时航线巡航高度快照，单位米；NULL 表示该航线未登记高度',
    start_time        BIGINT NULL COMMENT '审核时航线 UTC 时段起始快照，epoch 毫秒（含）；NULL 表示未登记时段',
    end_time          BIGINT NULL COMMENT '审核时航线 UTC 时段结束快照，epoch 毫秒（不含）；NULL 表示未登记时段',
    request_id        VARCHAR(64) NOT NULL COMMENT '提交审核的写操作请求标识',
    created_at        BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）'
) COMMENT = '审核不可变结果';

CREATE UNIQUE INDEX IF NOT EXISTS ux_review_request ON review (request_id);

-- 区域高度带（下限、上限，单位米，左闭右开）；同一区域高度带不得重叠，端点相接合法。
-- 容量只允许上调；高度带只能新增不重叠带，不允许下调容量、删除或改界，历史占用不追溯改写。
CREATE TABLE IF NOT EXISTS altitude_band (
    zone_id       VARCHAR(64) NOT NULL COMMENT '所属禁飞区标识',
    band_lower    INT NOT NULL COMMENT '高度带下限（含），单位米',
    band_upper    INT NOT NULL COMMENT '高度带上限（不含），单位米，band_lower < band_upper',
    capacity      INT NOT NULL COMMENT '同时容量，范围 1~50，只允许上调',
    PRIMARY KEY (zone_id, band_lower)
) COMMENT = '区域高度带配置（左闭右开，同区域不得重叠，端点相接合法）';

-- 航线垂直分离审查明细：每条审核对每个二维相交区域的高度判定，不可变。
CREATE TABLE IF NOT EXISTS review_vertical_detail (
    review_id        VARCHAR(64) NOT NULL COMMENT '所属审核记录标识',
    zone_id          VARCHAR(64) NOT NULL COMMENT '二维路径相交的区域标识',
    band_lower       INT NULL COMMENT '命中高度带下限（含），单位米；NULL 表示二维相交但高度不相交（垂直分离）',
    band_upper       INT NULL COMMENT '命中高度带上限（不含），单位米；NULL 表示垂直分离',
    vertical_hit     BOOLEAN NOT NULL COMMENT 'true 表示二维相交且巡航高度落入某高度带；false 表示二维相交但高度不相交',
    detail_seq       INT NOT NULL COMMENT '明细序号，按 zoneId 字典序排列，从 0 开始',
    PRIMARY KEY (review_id, zone_id)
) COMMENT = '航线垂直分离审查不可变明细';

-- 高度层占用记录：仅对 CLEAR 审查可创建；ACTIVE 为当前占用，CANCELLED 历史保留。
-- 唯一键保证同一审核的同一高度带占用不会因重放/并发产生两条 ACTIVE。
CREATE TABLE IF NOT EXISTS altitude_occupancy (
    occupancy_id      VARCHAR(64) PRIMARY KEY COMMENT '占用记录唯一标识',
    review_id         VARCHAR(64) NOT NULL COMMENT '关联的 CLEAR 审核记录标识',
    route_id          VARCHAR(64) NOT NULL COMMENT '占用航线标识（冗余自审核记录，便于查询）',
    zone_id           VARCHAR(64) NOT NULL COMMENT '占用的区域标识',
    band_lower        INT NOT NULL COMMENT '占用高度带下限（含），单位米',
    band_upper        INT NOT NULL COMMENT '占用高度带上限（不含），单位米（创建时快照，不随后续改带改写）',
    start_time        BIGINT NOT NULL COMMENT '占用 UTC 时段起始，epoch 毫秒（含）',
    end_time          BIGINT NOT NULL COMMENT '占用 UTC 时段结束，epoch 毫秒（不含），start_time < end_time',
    status            VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 计入容量；CANCELLED 已取消立即释放容量但历史保留',
    created_at        BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）',
    cancelled_at      BIGINT NULL COMMENT '取消时间，epoch 毫秒（UTC）；NULL 表示未取消'
) COMMENT = '高度层占用记录（ACTIVE 计容量，CANCELLED 历史保留）';

CREATE INDEX IF NOT EXISTS ix_occupancy_band_time
    ON altitude_occupancy (zone_id, band_lower, status, start_time, end_time);

-- 写操作幂等去重：同键同参重放原结果，异参冲突；失败不占键
CREATE TABLE IF NOT EXISTS request_dedup (
    request_id     VARCHAR(64) PRIMARY KEY COMMENT '写操作全局唯一请求标识',
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 十六进制摘要，用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';
