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
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW/CAPACITY_CONFIG/ROUTE_ACTIVATE/ROUTE_DEACTIVATE/TRANSFER_ACTIVATE',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 十六进制摘要，用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';

-- 时空桶容量配置：管理员按空域单元 + 15 分钟 UTC 时间桶配置最大航班数。
-- 空域单元为 1000 米 x 1000 米固定网格，cell_id 格式 C<gx>_<gy>（gx/gy 可负）。
CREATE TABLE IF NOT EXISTS capacity_config (
    cell_id       VARCHAR(32) NOT NULL COMMENT '空域单元标识，格式 C<gx>_<gy>，单元为 1000 米边长网格',
    bucket_start  BIGINT NOT NULL COMMENT '15 分钟 UTC 时间桶起始时刻，epoch 毫秒（UTC），必须对齐 900000 毫秒',
    max_flights   INT NOT NULL COMMENT '该时空桶允许的最大航班占用数，>= 0；未配置的桶视为不限容量',
    updated_at    BIGINT NOT NULL COMMENT '最近配置/调整时间，epoch 毫秒（UTC）',
    PRIMARY KEY (cell_id, bucket_start)
) COMMENT = '时空桶容量配置（按空域单元与 15 分钟 UTC 时间桶）';

-- 航线版本时空桶占用：审查通过且激活的航线版本按穿越单元序列占用容量。
-- 同一 (route_id, route_version) 的 seq 从 0 连续编号，时间桶随 seq 依次推进 15 分钟。
CREATE TABLE IF NOT EXISTS route_occupancy (
    route_id       VARCHAR(64) NOT NULL COMMENT '航线标识',
    route_version  INT NOT NULL COMMENT '占用所属的航线版本（激活或转配成功时的版本）',
    seq            INT NOT NULL COMMENT '穿越序列序号，从 0 开始连续编号',
    cell_id        VARCHAR(32) NOT NULL COMMENT '占用的空域单元标识',
    bucket_start   BIGINT NOT NULL COMMENT '占用的 15 分钟 UTC 时间桶起始，epoch 毫秒（UTC）',
    review_id      VARCHAR(64) NOT NULL COMMENT '激活所依据的审查通过记录标识（转配时原样保留）',
    PRIMARY KEY (route_id, seq)
) COMMENT = 'ACTIVE 航线版本的时空桶占用（穿越序列）';

CREATE INDEX IF NOT EXISTS ix_occupancy_bucket ON route_occupancy (cell_id, bucket_start);

-- 容量转配单：transfer_key 全局唯一；成功激活后不可变
CREATE TABLE IF NOT EXISTS capacity_transfer (
    transfer_key  VARCHAR(64) PRIMARY KEY COMMENT '转配单唯一业务标识',
    request_id    VARCHAR(64) NOT NULL COMMENT '激活写操作请求标识（幂等去重键）',
    created_at    BIGINT NOT NULL COMMENT '激活时间，epoch 毫秒（UTC）'
) COMMENT = '容量转配单（成功激活后冻结，不可变）';

-- 转配项：逐项记录源桶与目标桶；seq 为请求内顺序（语义与顺序无关）
CREATE TABLE IF NOT EXISTS capacity_transfer_item (
    transfer_key     VARCHAR(64) NOT NULL COMMENT '所属转配单标识',
    seq              INT NOT NULL COMMENT '项在请求中的顺序号，从 0 开始',
    route_id         VARCHAR(64) NOT NULL COMMENT '参与航线标识',
    expected_version INT NOT NULL COMMENT '激活时校验的航线版本',
    source_cell      VARCHAR(32) NOT NULL COMMENT '源桶空域单元标识',
    source_bucket    BIGINT NOT NULL COMMENT '源桶 15 分钟 UTC 时间桶起始，epoch 毫秒（UTC）',
    target_cell      VARCHAR(32) NOT NULL COMMENT '目标桶空域单元标识',
    target_bucket    BIGINT NOT NULL COMMENT '目标桶 15 分钟 UTC 时间桶起始，epoch 毫秒（UTC）',
    PRIMARY KEY (transfer_key, seq)
) COMMENT = '容量转配项（源桶到目标桶的占用转移）';

-- 转配前后逐航线冻结：版本、审查依据与完整穿越路径
CREATE TABLE IF NOT EXISTS capacity_transfer_route (
    transfer_key  VARCHAR(64) NOT NULL COMMENT '所属转配单标识',
    route_id      VARCHAR(64) NOT NULL COMMENT '参与航线标识',
    old_version   INT NOT NULL COMMENT '转配前航线版本',
    new_version   INT NOT NULL COMMENT '转配后航线版本（old_version + 1）',
    review_id     VARCHAR(64) NOT NULL COMMENT '转配时冻结的审查依据记录标识',
    before_path   CLOB NOT NULL COMMENT '转配前完整穿越序列，格式 cell@bucketStart;...',
    after_path    CLOB NOT NULL COMMENT '转配后完整穿越序列，格式 cell@bucketStart;...',
    PRIMARY KEY (transfer_key, route_id)
) COMMENT = '转配前后逐航线冻结证据（版本、审查依据、穿越路径）';

-- 转配前后受影响时空桶的余量冻结（仅占用发生变化的桶）
CREATE TABLE IF NOT EXISTS capacity_transfer_bucket (
    transfer_key  VARCHAR(64) NOT NULL COMMENT '所属转配单标识',
    cell_id       VARCHAR(32) NOT NULL COMMENT '受影响空域单元标识',
    bucket_start  BIGINT NOT NULL COMMENT '受影响 15 分钟 UTC 时间桶起始，epoch 毫秒（UTC）',
    max_flights   INT NULL COMMENT '转配时该桶容量上限；NULL 表示未配置（不限容量）',
    before_count  INT NOT NULL COMMENT '转配前该桶占用数（含未参与航线）',
    after_count   INT NOT NULL COMMENT '转配后该桶占用数（含未参与航线）',
    PRIMARY KEY (transfer_key, cell_id, bucket_start)
) COMMENT = '转配前后受影响时空桶余量冻结证据';
