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

-- 气象元数据（单行）：气象标准版本、最新预测版本与值班版本。
-- 三个版本的变更事务都先更新 coord_lock 行，与审核事务互斥（同空域版本机制）。
CREATE TABLE IF NOT EXISTS weather_meta (
    id                INT PRIMARY KEY COMMENT '固定为 1 的单行主键',
    weather_version   BIGINT NOT NULL COMMENT '气象最低标准版本，初始 0；每次配置/修订区域标准后加一',
    forecast_version  BIGINT NOT NULL COMMENT '最新预测气象版本，初始 0；每次预测上传后加一',
    duty_version      BIGINT NOT NULL COMMENT '值班版本，初始 0；每次换班后加一'
) COMMENT = '气象标准/预测/值班版本元数据';

-- 区域气象最低标准：按 UTC 时段（一日内分钟，左闭右开 [start,end)）生效；
-- 同一区域的时段不得重叠；整组配置/修订一次生效并推进气象标准版本
CREATE TABLE IF NOT EXISTS weather_minima (
    zone_id           VARCHAR(64) NOT NULL COMMENT '所属区域标识',
    window_start      INT NOT NULL COMMENT '时段起始（UTC 一日内分钟，含），范围 [0,1440)',
    window_end        INT NOT NULL COMMENT '时段结束（UTC 一日内分钟，不含），范围 (0,1440]，window_start < window_end',
    min_visibility_m  INT NOT NULL COMMENT '该时段最低能见度标准，单位米，必须 >= 0',
    min_cloud_base_m  INT NOT NULL COMMENT '该时段最低云底高标准，单位米，必须 >= 0',
    minima_version    BIGINT NOT NULL COMMENT '该组标准生效时的气象标准版本',
    PRIMARY KEY (zone_id, window_start)
) COMMENT = '区域气象最低标准（按 UTC 时段生效，左闭右开）';

-- 预测气象：每次上传整批写入并分配新的全局预测版本；历史版本保留用于审核快照核对
CREATE TABLE IF NOT EXISTS weather_forecast (
    forecast_version  BIGINT NOT NULL COMMENT '本条预测所属的全局预测版本',
    zone_id           VARCHAR(64) NOT NULL COMMENT '预测覆盖的区域标识',
    window_start      INT NOT NULL COMMENT '预测时段起始（UTC 一日内分钟，含），范围 [0,1440)',
    window_end        INT NOT NULL COMMENT '预测时段结束（UTC 一日内分钟，不含），范围 (0,1440]',
    visibility_m      INT NOT NULL COMMENT '预测能见度，单位米，必须 >= 0',
    cloud_base_m      INT NOT NULL COMMENT '预测云底高，单位米，必须 >= 0',
    created_at        BIGINT NOT NULL COMMENT '上传时间，epoch 毫秒（UTC）',
    PRIMARY KEY (forecast_version, zone_id, window_start)
) COMMENT = '预测气象（按上传批次版本化，不可变）';

-- 审核不可变结果
CREATE TABLE IF NOT EXISTS review (
    review_id          VARCHAR(64) PRIMARY KEY COMMENT '审核记录唯一标识（不可变）',
    route_id           VARCHAR(64) NOT NULL COMMENT '被审核航线标识',
    route_version      INT NOT NULL COMMENT '审核明确指定的航线版本',
    airspace_version   BIGINT NOT NULL COMMENT '审核明确指定的空域版本',
    weather_version    BIGINT NOT NULL COMMENT '审核时固化的气象标准版本',
    forecast_version   BIGINT NULL COMMENT '审核时固化的预测气象版本；NULL 表示本次审核未涉及气象标准区域、未提交预测',
    duty_version       BIGINT NOT NULL COMMENT '审核时固化的值班版本',
    conclusion         VARCHAR(16) NOT NULL COMMENT '保存时的原结论：CLEAR 通过、BLOCKED 命中禁飞区或 WEATHER_BLOCKED 气象不达标，永不改变',
    hit_zone_ids       CLOB NOT NULL COMMENT '命中禁飞区的全部 zoneId，字典序去重后逗号拼接；未命中为空串',
    weather_violations CLOB NOT NULL COMMENT '气象不达标明细 JSON 数组（含区域、时段、指标、实际值、要求值、差额）；无则为空数组',
    weather_snapshot   CLOB NOT NULL COMMENT '审核时固化的相关区域标准与预测明细 JSON（含标准版本、预测版本、值班版本），用于时效诊断',
    points_snapshot    VARCHAR(4000) NOT NULL COMMENT '审核时航点不可变快照，格式 x,y;x,y',
    request_id         VARCHAR(64) NOT NULL COMMENT '提交审核的写操作请求标识',
    created_at         BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）'
) COMMENT = '审核不可变结果（快照固化标准、预测与值班版本）';

CREATE UNIQUE INDEX IF NOT EXISTS ux_review_request ON review (request_id);

-- 空域占用：仅允许引用审核时结论为 CLEAR 且未失效（非 STALE）的审核记录；
-- 占用固化审核时的航线/空域/气象标准/预测/值班版本，之后版本推进不影响已建占用
CREATE TABLE IF NOT EXISTS occupation (
    occupation_id     VARCHAR(64) PRIMARY KEY COMMENT '占用记录唯一标识（不可变）',
    review_id         VARCHAR(64) NOT NULL COMMENT '引用的审核记录标识，一个审核结果至多创建一次占用',
    route_id          VARCHAR(64) NOT NULL COMMENT '占用航线标识',
    route_version     INT NOT NULL COMMENT '占用固化的航线版本（取自审核快照）',
    airspace_version  BIGINT NOT NULL COMMENT '占用固化的空域版本（取自审核快照）',
    weather_version   BIGINT NOT NULL COMMENT '占用固化的气象标准版本（取自审核快照）',
    forecast_version  BIGINT NULL COMMENT '占用固化的预测气象版本（取自审核快照）；NULL 表示审核未涉及预测',
    duty_version      BIGINT NOT NULL COMMENT '占用固化的值班版本（取自审核快照）',
    request_id        VARCHAR(64) NOT NULL COMMENT '创建占用的写操作请求标识',
    created_at        BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）'
) COMMENT = '空域占用（基于审核时固化的气象版本，拒绝 STALE 结果）';

CREATE UNIQUE INDEX IF NOT EXISTS ux_occupation_review ON occupation (review_id);

-- 写操作幂等去重：同键同参重放原结果，异参冲突；失败不占键
CREATE TABLE IF NOT EXISTS request_dedup (
    request_id     VARCHAR(64) PRIMARY KEY COMMENT '写操作全局唯一请求标识',
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW/REVIEW_BATCH/WEATHER_MINIMA_CONFIG/FORECAST_UPLOAD/DUTY_ADVANCE/OCCUPATION_CREATE',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 十六进制摘要（含操作者与全部影响结果的字段，集合经 JSON 转义而非分隔符拼接），用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';
