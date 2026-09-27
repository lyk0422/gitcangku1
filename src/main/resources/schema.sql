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
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW/CORRIDOR_CREATE/CORRIDOR_REVOKE/OCCUPATION_CREATE/OCCUPATION_DEPART/OCCUPATION_REAPPLY',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 十六进制摘要，用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';

-- 应急走廊：授权调度员在指定区域、UTC 时段与高度范围预留，仅供应急航线使用。
-- 时间窗与高度范围均为左闭右开区间；撤销或自然到期只影响后续占用。
CREATE TABLE IF NOT EXISTS emergency_corridor (
    corridor_id  VARCHAR(64) PRIMARY KEY COMMENT '应急走廊唯一标识',
    x_min        INT NOT NULL COMMENT '区域左边界（含），单位米',
    y_min        INT NOT NULL COMMENT '区域下边界（含），单位米',
    x_max        INT NOT NULL COMMENT '区域右边界（含），单位米，x_min < x_max',
    y_max        INT NOT NULL COMMENT '区域上边界（含），单位米，y_min < y_max',
    start_utc    BIGINT NOT NULL COMMENT '生效起点（含），epoch 毫秒（UTC）',
    end_utc      BIGINT NOT NULL COMMENT '生效终点（不含），epoch 毫秒（UTC），start_utc < end_utc',
    alt_min      INT NOT NULL COMMENT '高度下限（含），单位米',
    alt_max      INT NOT NULL COMMENT '高度上限（不含），单位米，alt_min < alt_max',
    priority     INT NOT NULL COMMENT '走廊优先级；同优先级 ACTIVE 走廊时空不得重叠',
    status       VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效；REVOKED 已撤销（仅影响后续占用，已让渡不自动恢复）',
    version      INT NOT NULL COMMENT '走廊版本，创建为 1，撤销后加一',
    operator     VARCHAR(64) NOT NULL COMMENT '创建走廊的授权调度员标识',
    created_at   BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）',
    revoked_at   BIGINT NULL COMMENT '撤销时间，epoch 毫秒（UTC）；NULL 表示未撤销'
) COMMENT = '空域应急走廊预留（区域闭矩形 + 左闭右开时段与高度范围）';

-- 空域占用：航线在指定 UTC 时段与高度范围（均左闭右开）占用空域。
-- 创建时完成禁飞审查并固化审查记录与航点快照；状态机 ACTIVE→YIELDED/DEPARTED。
CREATE TABLE IF NOT EXISTS occupation (
    occupation_id    VARCHAR(64) PRIMARY KEY COMMENT '占用唯一标识',
    route_id         VARCHAR(64) NOT NULL COMMENT '占用航线标识',
    route_version    INT NOT NULL COMMENT '占用时审查的航线版本',
    kind             VARCHAR(16) NOT NULL COMMENT '占用类型：REGULAR 常规 / EMERGENCY 应急',
    start_utc        BIGINT NOT NULL COMMENT '占用起点（含），epoch 毫秒（UTC）',
    end_utc          BIGINT NOT NULL COMMENT '占用终点（不含），epoch 毫秒（UTC），start_utc < end_utc',
    alt_min          INT NOT NULL COMMENT '占用高度下限（含），单位米',
    alt_max          INT NOT NULL COMMENT '占用高度上限（不含），单位米，alt_min < alt_max',
    yieldable        BOOLEAN NOT NULL COMMENT '是否可被应急走廊强制让渡；FALSE 为不可让渡',
    status           VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 占用中；YIELDED 已被走廊让渡；DEPARTED 已起飞（不可让渡）',
    review_id        VARCHAR(64) NOT NULL COMMENT '授权本次占用的不可变审查记录标识',
    airspace_version BIGINT NOT NULL COMMENT '占用审查时的全局空域版本',
    points_snapshot  VARCHAR(4000) NOT NULL COMMENT '占用时航点不可变快照，格式 x,y;x,y',
    superseded_by    VARCHAR(64) NULL COMMENT '重新申请生成的新占用标识；NULL 表示未被接替',
    created_at       BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）',
    departed_at      BIGINT NULL COMMENT '起飞登记时间，epoch 毫秒（UTC）；NULL 表示未起飞'
) COMMENT = '空域占用（常规/应急），含审查证据快照';

-- 走廊让渡历史（不可变）：走廊创建时被强制让渡的常规占用，
-- 固化原审查版本、走廊版本与让渡原因，后续操作不得改写。
CREATE TABLE IF NOT EXISTS corridor_yield (
    yield_id         VARCHAR(64) PRIMARY KEY COMMENT '让渡记录唯一标识（不可变）',
    corridor_id      VARCHAR(64) NOT NULL COMMENT '触发让渡的应急走廊标识',
    corridor_version INT NOT NULL COMMENT '让渡发生时的走廊版本',
    occupation_id    VARCHAR(64) NOT NULL COMMENT '被让渡的常规占用标识',
    route_id         VARCHAR(64) NOT NULL COMMENT '被让渡航线标识',
    review_id        VARCHAR(64) NOT NULL COMMENT '原占用的授权审查记录标识',
    route_version    INT NOT NULL COMMENT '原审查固化的航线版本',
    reason           VARCHAR(256) NOT NULL COMMENT '让渡原因',
    created_at       BIGINT NOT NULL COMMENT '让渡时间，epoch 毫秒（UTC）'
) COMMENT = '应急走廊强制让渡历史（不可变）';

CREATE UNIQUE INDEX IF NOT EXISTS ux_corridor_yield_occupation ON corridor_yield (occupation_id);

-- 重新申请历史（不可变）：已让渡占用经重新审查后显式申请的新占用关联。
CREATE TABLE IF NOT EXISTS occupation_reapply (
    reapply_id           VARCHAR(64) PRIMARY KEY COMMENT '重新申请记录唯一标识（不可变）',
    source_occupation_id VARCHAR(64) NOT NULL COMMENT '被让渡的原占用标识',
    new_occupation_id    VARCHAR(64) NOT NULL COMMENT '重新申请生成的新占用标识',
    review_id            VARCHAR(64) NOT NULL COMMENT '重新审查的不可变审查记录标识',
    created_at           BIGINT NOT NULL COMMENT '重新申请时间，epoch 毫秒（UTC）'
) COMMENT = '已让渡占用重新申请历史（不可变）';

CREATE UNIQUE INDEX IF NOT EXISTS ux_reapply_new_occupation ON occupation_reapply (new_occupation_id);
