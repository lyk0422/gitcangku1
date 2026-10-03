-- 禁飞区航线审查与空域气象最低标准：H2（MODE=MySQL）建表脚本，应用启动时自动执行。
-- 数据仅保留在 JVM 生命周期内的内存库，不涉及跨进程恢复。
-- 时间统一为 UTC epoch 毫秒；气象时段区间左闭右开 [window_start, window_end)。

-- 全局空域版本（单行，初始版本 0；任一禁飞区创建/撤销使其加一）
CREATE TABLE IF NOT EXISTS airspace_meta (
    id              INT PRIMARY KEY COMMENT '固定为 1 的单行主键',
    global_version  BIGINT NOT NULL COMMENT '当前全局空域版本号，初始 0；禁飞区每次创建或撤销后加一'
) COMMENT = '全局空域版本元数据';

-- 事务协调锁：单行。审核、禁飞区变更、气象标准配置、预测上传与占用创建事务
-- 都对该行做真实更新（touched 加一），借助行级排他锁（H2 MVStore 与 MySQL InnoDB
-- 语义一致）按提交顺序串行化，保证审核读取的版本号与数据来自同一已提交状态。
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

-- 气象版本元数据（单行）：值班版本与最新预测版本
CREATE TABLE IF NOT EXISTS weather_meta (
    id               INT PRIMARY KEY COMMENT '固定为 1 的单行主键',
    duty_version     BIGINT NOT NULL COMMENT '当前气象标准值班版本，初始 0；每次标准配置或修订后加一',
    forecast_version BIGINT NOT NULL COMMENT '当前最新预测气象版本，初始 0；每次预测上传后加一'
) COMMENT = '气象标准值班版本与预测版本元数据';

-- 气象区域：非退化轴对齐闭矩形；重复配置同一 regionId 视为修订（整体替换几何与窗口）
CREATE TABLE IF NOT EXISTS weather_region (
    region_id     VARCHAR(64) PRIMARY KEY COMMENT '气象区域唯一标识',
    x_min         INT NOT NULL COMMENT '矩形左边界（含），单位米',
    y_min         INT NOT NULL COMMENT '矩形下边界（含），单位米',
    x_max         INT NOT NULL COMMENT '矩形右边界（含），单位米，x_min < x_max',
    y_max         INT NOT NULL COMMENT '矩形上边界（含），单位米，y_min < y_max',
    duty_version  BIGINT NOT NULL COMMENT '本次配置生效时的气象值班版本',
    created_at    BIGINT NOT NULL COMMENT '本次配置时间，epoch 毫秒（UTC）'
) COMMENT = '气象区域（可配置按 UTC 时段生效的能见度与云底最低标准）';

-- 气象区域最低标准窗口：同一区域内时段不得重叠，区间左闭右开
CREATE TABLE IF NOT EXISTS weather_minima_window (
    region_id        VARCHAR(64) NOT NULL COMMENT '所属气象区域标识',
    window_start     BIGINT NOT NULL COMMENT '时段起点，epoch 毫秒（UTC），左闭',
    window_end       BIGINT NOT NULL COMMENT '时段终点，epoch 毫秒（UTC），右开，必须大于起点',
    visibility_min_m INT NOT NULL COMMENT '能见度最低标准，单位米，必须非负',
    cloud_base_min_m INT NOT NULL COMMENT '云底最低标准，单位米，必须非负',
    duty_version     BIGINT NOT NULL COMMENT '该窗口生效时的气象值班版本',
    PRIMARY KEY (region_id, window_start)
) COMMENT = '气象区域按 UTC 时段生效的能见度与云底最低标准';

-- 预测气象：按上传版本整体固化，版本内条目不可变
CREATE TABLE IF NOT EXISTS weather_forecast (
    forecast_version BIGINT NOT NULL COMMENT '预测气象版本，上传时由系统递增分配',
    region_id        VARCHAR(64) NOT NULL COMMENT '气象区域标识',
    window_start     BIGINT NOT NULL COMMENT '时段起点，epoch 毫秒（UTC），左闭，与标准窗口对齐',
    window_end       BIGINT NOT NULL COMMENT '时段终点，epoch 毫秒（UTC），右开',
    visibility_m     INT NOT NULL COMMENT '预测能见度，单位米，必须非负',
    cloud_base_m     INT NOT NULL COMMENT '预测云底，单位米，必须非负',
    PRIMARY KEY (forecast_version, region_id, window_start)
) COMMENT = '预测气象条目（按版本固化，不可变）';

-- 审核不可变结果
CREATE TABLE IF NOT EXISTS review (
    seq               BIGINT NOT NULL AUTO_INCREMENT UNIQUE COMMENT '单调递增序号，同毫秒提交时的稳定排序依据',
    review_id         VARCHAR(64) PRIMARY KEY COMMENT '审核记录唯一标识（不可变）',
    route_id          VARCHAR(64) NOT NULL COMMENT '被审核航线标识',
    route_version     INT NOT NULL COMMENT '审核明确指定的航线版本',
    airspace_version  BIGINT NOT NULL COMMENT '审核明确指定的空域版本',
    conclusion        VARCHAR(16) NOT NULL COMMENT '保存时的原结论：CLEAR 通过、BLOCKED 命中禁飞、WEATHER_BLOCKED 气象低于标准，永不改变',
    hit_zone_ids      CLOB NOT NULL COMMENT '命中的全部 zoneId，字典序去重后逗号拼接；未命中为空串',
    points_snapshot   VARCHAR(4000) NOT NULL COMMENT '审核时航点不可变快照，格式 x,y;x,y',
    duty_version      BIGINT NOT NULL COMMENT '审核时固化的气象标准值班版本（尚无气象配置时为 0）',
    forecast_version  BIGINT NULL COMMENT '审核时固化的预测气象版本；NULL 表示审核时不存在任何预测版本',
    weather_snapshot  CLOB NULL COMMENT '审核时固化的气象标准与预测快照 JSON（含违规明细）；NULL 表示路径不经过任何气象区域',
    request_id        VARCHAR(64) NOT NULL COMMENT '提交审核的写操作请求标识',
    created_at        BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）'
) COMMENT = '审核不可变结果';

-- 批量审核的多条结果共用同一提交 requestId，此处仅保留普通索引；幂等唯一性由 request_dedup 保证
CREATE INDEX IF NOT EXISTS idx_review_request ON review (request_id);

-- 空域占用：只能通过仍为当前结论的 CLEAR 审核创建，固化审核时的气象版本
CREATE TABLE IF NOT EXISTS occupancy (
    occupancy_id      VARCHAR(64) PRIMARY KEY COMMENT '占用记录唯一标识（不可变）',
    review_id         VARCHAR(64) NOT NULL COMMENT '来源审核记录标识，一条审核最多创建一次占用',
    route_id          VARCHAR(64) NOT NULL COMMENT '占用航线标识',
    route_version     INT NOT NULL COMMENT '占用时固化的航线版本（来自审核快照）',
    airspace_version  BIGINT NOT NULL COMMENT '占用时固化的空域版本（来自审核快照）',
    duty_version      BIGINT NOT NULL COMMENT '占用时固化的气象值班版本（来自审核快照）',
    forecast_version  BIGINT NULL COMMENT '占用时固化的预测气象版本（来自审核快照）；NULL 表示无预测',
    operator          VARCHAR(64) NOT NULL COMMENT '创建占用的操作者',
    request_id        VARCHAR(64) NOT NULL COMMENT '创建占用的写操作请求标识',
    created_at        BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）'
) COMMENT = '空域占用（必须使用审核时固化的气象版本）';

CREATE UNIQUE INDEX IF NOT EXISTS ux_occupancy_review ON occupancy (review_id);

-- 写操作幂等去重：同键同参重放原结果，异参冲突；失败不占键
CREATE TABLE IF NOT EXISTS request_dedup (
    request_id     VARCHAR(64) PRIMARY KEY COMMENT '写操作全局唯一请求标识',
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW/REVIEW_BATCH/WEATHER_REGION_CONFIGURE/FORECAST_UPLOAD/OCCUPANCY_CREATE',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数（含操作者、操作类型、规范化集合与全部结果相关字段）的 SHA-256 十六进制摘要，用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';