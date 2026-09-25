-- 禁飞区航线审查与高度层容量：H2（MODE=MySQL）建表脚本，应用启动时自动执行。
-- 数据仅保留在 JVM 生命周期内的内存库，不涉及跨进程恢复。

-- 全局空域版本（单行，初始版本 0；任一禁飞区创建/撤销或高度带配置变更使其加一）
CREATE TABLE IF NOT EXISTS airspace_meta (
    id              INT PRIMARY KEY COMMENT '固定为 1 的单行主键',
    global_version  BIGINT NOT NULL COMMENT '当前全局空域版本号，初始 0；禁飞区创建/撤销或高度带配置变更后加一'
) COMMENT = '全局空域版本元数据';

-- 事务协调锁：单行。审核、占用与区域变更事务都对该行做真实更新（touched 加一），
-- 借助行级排他锁（H2 MVStore 与 MySQL InnoDB 语义一致）串行化，
-- 保证审查/占用读取的空域版本与全部禁飞区、高度带来自同一已提交状态。
CREATE TABLE IF NOT EXISTS coord_lock (
    id       INT PRIMARY KEY COMMENT '固定为 1 的单行主键',
    touched  BIGINT NOT NULL COMMENT '仅用于产生真实行更新以加行级排他锁的计数器，无业务含义'
) COMMENT = '审核、占用与区域变更事务协调锁';

-- 禁飞区：zoneId 唯一；非退化轴对齐闭矩形；只能创建或撤销。
-- zone_version 为区域自身配置版本（初始 1），仅高度带配置成功后加一；
-- touch 仅用于配置/占用事务对该行加区域级排他锁。
CREATE TABLE IF NOT EXISTS no_fly_zone (
    zone_id          VARCHAR(64) PRIMARY KEY COMMENT '禁飞区唯一标识',
    x_min            INT NOT NULL COMMENT '矩形左边界（含），单位米',
    y_min            INT NOT NULL COMMENT '矩形下边界（含），单位米',
    x_max            INT NOT NULL COMMENT '矩形右边界（含），单位米，x_min < x_max',
    y_max            INT NOT NULL COMMENT '矩形上边界（含），单位米，y_min < y_max',
    status           VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效参与审核；REVOKED 已撤销不参与审核',
    created_version  BIGINT NOT NULL COMMENT '创建生效时的全局空域版本',
    revoked_version  BIGINT NULL COMMENT '撤销生效时的全局空域版本；NULL 表示仍有效',
    zone_version     INT NOT NULL COMMENT '区域配置版本，初始 1，每次高度带配置成功后加一',
    touch            BIGINT NOT NULL COMMENT '仅用于高度带配置/占用事务加区域级行锁的计数器，无业务含义'
) COMMENT = '禁飞区（非退化轴对齐闭矩形，只能创建或撤销；高度带配置以 zone_version 乐观控制）';

-- 高度带：从属于禁飞区；[lower_altitude, upper_altitude) 左闭右开，单位米。
-- 同一区域内带不得重叠（端点相接合法）由服务层在区域行锁内校验；
-- 带边界一经创建不可修改，容量只允许上调，占用永久引用 band_id，配置变更不追溯改写占用。
CREATE TABLE IF NOT EXISTS altitude_band (
    band_id          VARCHAR(64) PRIMARY KEY COMMENT '高度带唯一标识',
    zone_id          VARCHAR(64) NOT NULL COMMENT '所属禁飞区标识',
    lower_altitude   INT NOT NULL COMMENT '高度带下限（含），单位米',
    upper_altitude   INT NOT NULL COMMENT '高度带上限（不含），单位米，lower_altitude < upper_altitude',
    capacity         INT NOT NULL COMMENT '该高度带同时段 ACTIVE 占用容量，范围 1~50，只允许上调',
    created_version  BIGINT NOT NULL COMMENT '该带登记时的全局空域版本',
    touch            BIGINT NOT NULL COMMENT '仅用于占用创建/取消事务加带级行锁的计数器，无业务含义',
    CONSTRAINT ux_altitude_band_range UNIQUE (zone_id, lower_altitude, upper_altitude)
) COMMENT = '区域高度带（左闭右开，同区域不重叠，容量 1~50 只升不降）';

CREATE INDEX IF NOT EXISTS ix_altitude_band_zone ON altitude_band (zone_id);

-- 航线当前状态：routeId 唯一，版本从 1 开始，替换成功加一。
-- 携带单一巡航高度（米）与 UTC 半开时间窗 [start_utc, end_utc)，epoch 毫秒。
-- touch 仅用于审核/占用事务对该行产生真实更新以加行级写锁，与替换操作互斥。
CREATE TABLE IF NOT EXISTS route (
    route_id         VARCHAR(64) PRIMARY KEY COMMENT '航线唯一标识',
    version          INT NOT NULL COMMENT '当前航线版本，初始 1，每次成功替换加一',
    cruise_altitude  INT NOT NULL COMMENT '巡航高度，单位米',
    start_utc        BIGINT NOT NULL COMMENT '航线 UTC 起始时刻（含），epoch 毫秒',
    end_utc          BIGINT NOT NULL COMMENT '航线 UTC 结束时刻（不含），epoch 毫秒，start_utc < end_utc',
    touch            BIGINT NOT NULL COMMENT '仅用于审核/占用事务加行级写锁的计数器，无业务含义'
) COMMENT = '航线当前版本状态（含巡航高度与 UTC 时间窗）';

-- 航线点（当前版本，2~50 个，按 seq 顺序连接）
CREATE TABLE IF NOT EXISTS route_point (
    route_id  VARCHAR(64) NOT NULL COMMENT '所属航线标识',
    seq       INT NOT NULL COMMENT '点序号，从 0 开始按顺序连接',
    x         INT NOT NULL COMMENT '航点 X 坐标，单位米，范围 [-100000,100000]',
    y         INT NOT NULL COMMENT '航点 Y 坐标，单位米，范围 [-100000,100000]',
    PRIMARY KEY (route_id, seq)
) COMMENT = '航线当前版本有序航点';

-- 审核不可变结果。vertical_detail 为二维相交区域的垂直分离明细 JSON 快照：
-- 仅记录二维路径相交的区域，逐带标注巡航高度是否进入该左闭右开高度带。
CREATE TABLE IF NOT EXISTS review (
    review_id         VARCHAR(64) PRIMARY KEY COMMENT '审核记录唯一标识（不可变）',
    route_id          VARCHAR(64) NOT NULL COMMENT '被审核航线标识',
    route_version     INT NOT NULL COMMENT '审核明确指定的航线版本',
    airspace_version  BIGINT NOT NULL COMMENT '审核明确指定的空域版本',
    conclusion        VARCHAR(16) NOT NULL COMMENT '保存时的原结论：CLEAR 通过或 BLOCKED 命中，永不改变',
    hit_zone_ids      CLOB NOT NULL COMMENT '命中的全部 zoneId（二维相交且未登记高度带的纯禁飞区），字典序去重后逗号拼接；未命中为空串',
    points_snapshot   VARCHAR(4000) NOT NULL COMMENT '审核时航点不可变快照，格式 x,y;x,y',
    cruise_altitude   INT NOT NULL COMMENT '审核时航线巡航高度快照，单位米',
    start_utc         BIGINT NOT NULL COMMENT '审核时航线 UTC 起始时刻快照（含），epoch 毫秒',
    end_utc           BIGINT NOT NULL COMMENT '审核时航线 UTC 结束时刻快照（不含），epoch 毫秒',
    vertical_detail   CLOB NOT NULL COMMENT '二维相交区域逐高度带垂直分离明细 JSON 快照（含容量），无二维相交为空数组',
    request_id        VARCHAR(64) NOT NULL COMMENT '提交审核的写操作请求标识',
    created_at        BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）'
) COMMENT = '审核不可变结果（含垂直分离审查明细快照）';

CREATE UNIQUE INDEX IF NOT EXISTS ux_review_request ON review (request_id);

-- 高度层占用：仅对当前仍为 CLEAR 的审查创建；
-- ACTIVE 记录按 band_id + 半开时间窗重叠计数并受容量限制，取消置 CANCELLED 但行保留。
CREATE TABLE IF NOT EXISTS altitude_occupation (
    occupation_id    VARCHAR(64) PRIMARY KEY COMMENT '高度层占用记录唯一标识',
    review_id        VARCHAR(64) NOT NULL COMMENT '关联的审查记录标识（占用创建时必须仍为当前 CLEAR）',
    route_id         VARCHAR(64) NOT NULL COMMENT '占用航线标识（来自审查快照）',
    zone_id          VARCHAR(64) NOT NULL COMMENT '占用的禁飞区标识',
    band_id          VARCHAR(64) NOT NULL COMMENT '占用的高度带标识',
    start_utc        BIGINT NOT NULL COMMENT '占用 UTC 起始时刻（含），取自航线时间窗，epoch 毫秒',
    end_utc          BIGINT NOT NULL COMMENT '占用 UTC 结束时刻（不含），取自航线时间窗，start_utc < end_utc',
    cruise_altitude  INT NOT NULL COMMENT '创建时航线巡航高度快照，单位米',
    status           VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 计入容量；CANCELLED 已取消立即释放容量但历史保留',
    request_id       VARCHAR(64) NOT NULL COMMENT '创建占用的写操作请求标识',
    created_at       BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）',
    cancelled_at     BIGINT NULL COMMENT '取消时间，epoch 毫秒（UTC）；NULL 表示未取消'
) COMMENT = '高度层占用记录（ACTIVE 按区域高度带与时间重叠计入容量，取消历史保留）';

CREATE UNIQUE INDEX IF NOT EXISTS ux_occupation_request ON altitude_occupation (request_id);
CREATE INDEX IF NOT EXISTS ix_occupation_band_time
    ON altitude_occupation (band_id, status, start_utc, end_utc);
CREATE INDEX IF NOT EXISTS ix_occupation_zone_time
    ON altitude_occupation (zone_id, start_utc, end_utc);

-- 写操作幂等去重：同键同参重放原结果，异参冲突；失败不占键
CREATE TABLE IF NOT EXISTS request_dedup (
    request_id     VARCHAR(64) PRIMARY KEY COMMENT '写操作全局唯一请求标识',
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW/BAND_CONFIGURE/OCCUPATION_CREATE/OCCUPATION_CANCEL',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 十六进制摘要，用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';
