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

-- 航线版本合成能量档案（表头）：随航线创建/修订登记，绑定具体航线版本；
-- 修订航线生成新版本时旧版本档案被替换，历史审核不受影响（其快照已冻结在 review 中）
CREATE TABLE IF NOT EXISTS route_energy_profile (
    route_id         VARCHAR(64) NOT NULL COMMENT '所属航线标识',
    route_version    INT NOT NULL COMMENT '档案生效的航线版本（与 route.version 一致）',
    departure_energy BIGINT NOT NULL COMMENT '出发可用能量，非负整数，单位与段耗能一致',
    reserve_energy   BIGINT NOT NULL COMMENT '必留余量，非负整数；审查要求每点 累计主航线耗能+该点备降耗能+余量 <= 出发能量',
    operator         VARCHAR(64) NOT NULL COMMENT '登记该档案的操作者，非空',
    PRIMARY KEY (route_id, route_version)
) COMMENT = '航线版本合成能量档案（表头）';

-- 每段主航线耗能：段 seq 连接航点 seq 与 seq+1，条数须等于坐标段数且最多 10 段
CREATE TABLE IF NOT EXISTS route_segment_energy (
    route_id       VARCHAR(64) NOT NULL COMMENT '所属航线标识',
    route_version  INT NOT NULL COMMENT '档案生效的航线版本',
    seq            INT NOT NULL COMMENT '段序号，从 0 开始，连接航点 seq 与 seq+1',
    energy         BIGINT NOT NULL COMMENT '该段主航线耗能，正整数',
    PRIMARY KEY (route_id, route_version, seq)
) COMMENT = '航线版本每段主航线耗能';

-- 每航点备降选项的额外耗能：必须覆盖全部航点
CREATE TABLE IF NOT EXISTS route_alternate_energy (
    route_id       VARCHAR(64) NOT NULL COMMENT '所属航线标识',
    route_version  INT NOT NULL COMMENT '档案生效的航线版本',
    point_seq      INT NOT NULL COMMENT '航点序号，从 0 开始，与 route_point.seq 对应',
    energy         BIGINT NOT NULL COMMENT '该航点备降选项的额外耗能，非负整数',
    PRIMARY KEY (route_id, route_version, point_seq)
) COMMENT = '航线版本每航点备降额外耗能';

-- 审核不可变结果
CREATE TABLE IF NOT EXISTS review (
    review_id         VARCHAR(64) PRIMARY KEY COMMENT '审核记录唯一标识（不可变）',
    route_id          VARCHAR(64) NOT NULL COMMENT '被审核航线标识',
    route_version     INT NOT NULL COMMENT '审核明确指定的航线版本',
    airspace_version  BIGINT NOT NULL COMMENT '审核明确指定的空域版本',
    conclusion        VARCHAR(16) NOT NULL COMMENT '保存时的原结论：CLEAR 通过、BLOCKED 几何命中或 ENERGY_BLOCKED 能量不足，永不改变',
    hit_zone_ids      CLOB NOT NULL COMMENT '命中的全部 zoneId，字典序去重后逗号拼接；未命中为空串；ENERGY_BLOCKED 时仍保留几何命中',
    points_snapshot   VARCHAR(4000) NOT NULL COMMENT '审核时航点不可变快照，格式 x,y;x,y',
    energy_snapshot   CLOB NULL COMMENT '能量审查不可变快照（JSON）：出发能量、必留余量、每点累计/备降/需求能量及首个不足点号与缺口；NULL 表示该航线版本无能量档案',
    request_id        VARCHAR(64) NOT NULL COMMENT '提交审核的写操作请求标识',
    created_at        BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）'
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
