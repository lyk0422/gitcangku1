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
    created_at        BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）',
    weather_version   BIGINT NULL COMMENT '审查时固化的气象（值班）版本；NULL 表示未提交气象版本的纯禁飞区审查',
    weather_snapshot  CLOB NULL COMMENT '审查时固化的气象标准与预测快照 JSON；NULL 表示该审查无气象评估'
) COMMENT = '审核不可变结果';

CREATE UNIQUE INDEX IF NOT EXISTS ux_review_request ON review (request_id);

-- 写操作幂等去重：同键同参重放原结果，异参冲突；失败不占键
CREATE TABLE IF NOT EXISTS request_dedup (
    request_id     VARCHAR(64) PRIMARY KEY COMMENT '写操作全局唯一请求标识',
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 十六进制摘要，用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';

-- 全局气象（值班）版本：单行，初始 0；标准配置或预测上传每次成功提交使其加一。
-- 审查提交的气象版本必须等于该当前版本；审查后任何气象修订都会使旧审查结论失效（STALE），但不改写原记录。
CREATE TABLE IF NOT EXISTS weather_meta (
    id              INT PRIMARY KEY COMMENT '固定为 1 的单行主键',
    weather_version BIGINT NOT NULL COMMENT '当前全局气象（值班）版本号，初始 0；标准配置或预测上传每次提交后加一'
) COMMENT = '全局气象（值班）版本元数据';

-- 区域气象最低标准：按 UTC 时段生效，区间左闭右开 [window_start_utc, window_end_utc)。
-- 同一区域的时段集合整体替换：先校验完整最终状态（无负值、无重叠）再事务提交。
CREATE TABLE IF NOT EXISTS weather_minima (
    zone_id          VARCHAR(64) NOT NULL COMMENT '所属区域（禁飞区）标识',
    window_start_utc BIGINT NOT NULL COMMENT '时段起点（含），epoch 毫秒（UTC）',
    window_end_utc   BIGINT NOT NULL COMMENT '时段终点（不含），epoch 毫秒（UTC），必须大于起点',
    min_visibility_m INT NOT NULL COMMENT '该时段最低能见度标准，单位米，必须 >= 0',
    min_cloud_base_m INT NOT NULL COMMENT '该时段最低云底高度标准，单位米，必须 >= 0',
    weather_version  BIGINT NOT NULL COMMENT '本次标准配置生效时的全局气象版本',
    PRIMARY KEY (zone_id, window_start_utc)
) COMMENT = '区域分时段气象最低标准（能见度与云底，UTC 左闭右开）';

-- 预测气象：按上传版本固化，历史版本保留不可变；审查按“截至提交版本的最新预测”取值。
CREATE TABLE IF NOT EXISTS weather_forecast (
    forecast_version BIGINT NOT NULL COMMENT '预测上传生效的全局气象版本',
    zone_id          VARCHAR(64) NOT NULL COMMENT '预测所属区域标识',
    window_start_utc BIGINT NOT NULL COMMENT '预测时段起点（含），epoch 毫秒（UTC）',
    window_end_utc   BIGINT NOT NULL COMMENT '预测时段终点（不含），epoch 毫秒（UTC）',
    visibility_m     INT NOT NULL COMMENT '预测能见度，单位米，必须 >= 0',
    cloud_base_m     INT NOT NULL COMMENT '预测云底高度，单位米，必须 >= 0',
    created_at       BIGINT NOT NULL COMMENT '上传时间，epoch 毫秒（UTC）',
    PRIMARY KEY (forecast_version, zone_id, window_start_utc)
) COMMENT = '分版本预测气象（上传后不可变，供审查快照固化）';

-- 占用：审查通过后创建，固化审查时的航线/空域/气象版本；STALE 或非 CLEAR 审查拒绝创建。
CREATE TABLE IF NOT EXISTS occupancy (
    occupancy_id     VARCHAR(64) PRIMARY KEY COMMENT '占用记录唯一标识（不可变）',
    review_id        VARCHAR(64) NOT NULL COMMENT '所依据的审查记录标识',
    route_id         VARCHAR(64) NOT NULL COMMENT '占用航线标识',
    route_version    INT NOT NULL COMMENT '审查时固化的航线版本',
    airspace_version BIGINT NOT NULL COMMENT '审查时固化的空域版本',
    weather_version  BIGINT NULL COMMENT '审查时固化的气象版本；NULL 表示审查未提交气象版本',
    request_id       VARCHAR(64) NOT NULL COMMENT '创建占用的写操作请求标识',
    created_at       BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）'
) COMMENT = '航线空域占用（基于未失效的 CLEAR 审查）';

CREATE UNIQUE INDEX IF NOT EXISTS ux_occupancy_request ON occupancy (request_id);