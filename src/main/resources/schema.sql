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
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW/CAPACITY_CONFIG/OCCUPANCY_PLAN/TRANSFER_ACTIVATE',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 十六进制摘要，用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';

-- 时空桶容量配置：管理员按网格单元与 15 分钟 UTC 时间桶设置最大航班数
CREATE TABLE IF NOT EXISTS capacity_config (
    cell_x       INT NOT NULL COMMENT '网格单元 X 索引（坐标 floorDiv 20 米）',
    cell_y       INT NOT NULL COMMENT '网格单元 Y 索引（坐标 floorDiv 20 米）',
    bucket_start BIGINT NOT NULL COMMENT '时间桶起始时刻，epoch 毫秒（UTC），15 分钟对齐',
    max_flights  INT NOT NULL COMMENT '该时空桶允许的最大航班占用数，>= 0；未配置的桶按 0 处理',
    updated_at   BIGINT NOT NULL COMMENT '最近配置时间，epoch 毫秒（UTC）',
    PRIMARY KEY (cell_x, cell_y, bucket_start)
) COMMENT = '时空桶容量配置（网格单元 + 15 分钟 UTC 时间桶）';

-- 航线版本时空桶占用序列：每个版本一条有序序列，仅当前版本的序列计入容量占用；
-- 旧版本序列保留为历史（转配增版后前态可追溯）
CREATE TABLE IF NOT EXISTS route_occupancy_plan (
    route_id      VARCHAR(64) NOT NULL COMMENT '航线标识',
    route_version INT NOT NULL COMMENT '航线版本；仅等于 route 当前版本的序列计入占用',
    seq           INT NOT NULL COMMENT '序列序号，从 0 开始与航点一一对应',
    cell_x        INT NOT NULL COMMENT '占用网格单元 X 索引',
    cell_y        INT NOT NULL COMMENT '占用网格单元 Y 索引',
    bucket_start  BIGINT NOT NULL COMMENT '占用时间桶起始时刻，epoch 毫秒（UTC），15 分钟对齐',
    PRIMARY KEY (route_id, route_version, seq)
) COMMENT = '航线版本时空桶占用序列（仅当前版本计入容量）';

-- 容量转配单：仅激活成功才落库；任何校验失败整体回滚不留痕
CREATE TABLE IF NOT EXISTS capacity_transfer (
    transfer_key VARCHAR(64) PRIMARY KEY COMMENT '转配单唯一标识（transferKey，全局唯一）',
    request_id   VARCHAR(64) NOT NULL COMMENT '激活请求标识（幂等去重见 request_dedup）',
    item_count   INT NOT NULL COMMENT '转配项数量（2~20）',
    activated_at BIGINT NOT NULL COMMENT '激活时间，epoch 毫秒（UTC）'
) COMMENT = '容量转配单（闭环原子转配，激活成功才落库）';

-- 转配航线级证据：冻结转配前后穿越序列与审查依据，不可变
CREATE TABLE IF NOT EXISTS transfer_route_evidence (
    transfer_key            VARCHAR(64) NOT NULL COMMENT '所属转配单标识',
    route_id                VARCHAR(64) NOT NULL COMMENT '参与航线标识',
    from_version            INT NOT NULL COMMENT '转配前航线版本',
    to_version              INT NOT NULL COMMENT '转配后航线版本（from_version + 1）',
    review_id               VARCHAR(64) NOT NULL COMMENT '激活时的审查依据：当前 CLEAR 审核记录标识',
    review_route_version    INT NOT NULL COMMENT '审查依据对应的航线版本',
    review_airspace_version BIGINT NOT NULL COMMENT '审查依据对应的空域版本',
    before_plan             VARCHAR(4000) NOT NULL COMMENT '转配前穿越序列，格式 seq:cellX,cellY,bucketStart;...',
    after_plan              VARCHAR(4000) NOT NULL COMMENT '转配后穿越序列，格式 seq:cellX,cellY,bucketStart;...',
    PRIMARY KEY (transfer_key, route_id)
) COMMENT = '转配航线级冻结证据（前后序列与审查依据）';

-- 转配桶级证据：受影响时空桶的容量与全量占用前后快照，不可变
CREATE TABLE IF NOT EXISTS transfer_bucket_evidence (
    transfer_key VARCHAR(64) NOT NULL COMMENT '所属转配单标识',
    cell_x       INT NOT NULL COMMENT '网格单元 X 索引',
    cell_y       INT NOT NULL COMMENT '网格单元 Y 索引',
    bucket_start BIGINT NOT NULL COMMENT '时间桶起始时刻，epoch 毫秒（UTC）',
    max_flights  INT NOT NULL COMMENT '转配时配置的容量上限；未配置记 0',
    used_before  INT NOT NULL COMMENT '转配前该桶全量占用数（含未参与航线）',
    used_after   INT NOT NULL COMMENT '转配后该桶全量占用数（含未参与航线）',
    PRIMARY KEY (transfer_key, cell_x, cell_y, bucket_start)
) COMMENT = '转配桶级容量余量冻结证据';
