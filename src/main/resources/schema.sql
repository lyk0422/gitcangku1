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
-- kind 标识几何类型：LINE 直线段折线（点在 route_point）；
-- CURVE 二次曲线航线（每段三点 P0/P1/P2，在 route_curve_point）
-- touch 仅用于审核事务对该行产生真实更新以加行级写锁，与替换操作互斥
CREATE TABLE IF NOT EXISTS route (
    route_id  VARCHAR(64) PRIMARY KEY COMMENT '航线唯一标识',
    version   INT NOT NULL COMMENT '当前航线版本，初始 1，每次成功替换加一',
    kind      VARCHAR(16) NOT NULL COMMENT '几何类型：LINE 直线段折线；CURVE 二次贝塞尔曲线段航线',
    touch     BIGINT NOT NULL COMMENT '仅用于审核事务加行级写锁的计数器，无业务含义'
) COMMENT = '航线当前版本状态';

-- 航线点（LINE 类型当前版本，2~50 个，按 seq 顺序连接）
CREATE TABLE IF NOT EXISTS route_point (
    route_id  VARCHAR(64) NOT NULL COMMENT '所属航线标识',
    seq       INT NOT NULL COMMENT '点序号，从 0 开始按顺序连接',
    x         INT NOT NULL COMMENT '航点 X 坐标，单位米，范围 [-100000,100000]',
    y         INT NOT NULL COMMENT '航点 Y 坐标，单位米，范围 [-100000,100000]',
    PRIMARY KEY (route_id, seq)
) COMMENT = '直线航线当前版本有序航点';

-- 二次曲线航线控制点（CURVE 类型当前版本，1~5 段，每段固定 3 点：
-- pt_seq=0 为固定起点 P0、1 为控制点 P1、2 为固定终点 P2；
-- 相邻段要求第 i 段 P2 与第 i+1 段 P0 坐标相同，控制点可与端点共线退化为直线）
CREATE TABLE IF NOT EXISTS route_curve_point (
    route_id  VARCHAR(64) NOT NULL COMMENT '所属航线标识',
    seg_seq   INT NOT NULL COMMENT '曲线段序号，从 0 开始，最多 5 段',
    pt_seq    INT NOT NULL COMMENT '段内控制点序号：0=固定起点P0，1=控制点P1，2=固定终点P2',
    x         INT NOT NULL COMMENT '控制点 X 坐标，单位米，范围 [-100000,100000]',
    y         INT NOT NULL COMMENT '控制点 Y 坐标，单位米，范围 [-100000,100000]',
    PRIMARY KEY (route_id, seg_seq, pt_seq)
) COMMENT = '二次曲线航线当前版本有序控制点';

-- 审核不可变结果
CREATE TABLE IF NOT EXISTS review (
    review_id         VARCHAR(64) PRIMARY KEY COMMENT '审核记录唯一标识（不可变）',
    route_id          VARCHAR(64) NOT NULL COMMENT '被审核航线标识',
    route_kind        VARCHAR(16) NOT NULL COMMENT '审核时航线几何类型：LINE 直线；CURVE 二次曲线',
    route_version     INT NOT NULL COMMENT '审核明确指定的航线版本',
    airspace_version  BIGINT NOT NULL COMMENT '审核明确指定的空域版本',
    conclusion        VARCHAR(16) NOT NULL COMMENT '保存时的原结论：CLEAR 通过或 BLOCKED 命中，永不改变',
    hit_zone_ids      CLOB NOT NULL COMMENT '命中的全部 zoneId，字典序去重后逗号拼接；未命中为空串',
    points_snapshot   VARCHAR(4000) NULL COMMENT 'LINE 审核时航点不可变快照，格式 x,y;x,y；CURVE 类型为 NULL',
    curve_snapshot    CLOB NULL COMMENT 'CURVE 审核时全部曲线段控制点不可变快照 JSON（含完整 P0/P1/P2）；LINE 类型为 NULL',
    request_id        VARCHAR(64) NOT NULL COMMENT '提交审核的写操作请求标识',
    operator          VARCHAR(64) NOT NULL COMMENT '提交审核的操作者标识（非空）',
    created_at        BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）'
) COMMENT = '审核不可变结果';

CREATE UNIQUE INDEX IF NOT EXISTS ux_review_request ON review (request_id);

-- 写操作幂等去重：同键同参重放原结果，异参冲突；失败不占键
-- 结构化比较通过 request_kind（操作）+ 资源/全参数/期望版本的规范化哈希完成，
-- request_hash 对操作、资源标识、全部参数（含操作者、期望版本）逐字段比较，
-- 字段名无碰撞，文本原样、数值按十进制同值判定
CREATE TABLE IF NOT EXISTS request_dedup (
    request_id     VARCHAR(64) PRIMARY KEY COMMENT '写操作全局唯一请求标识',
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/ROUTE_CURVE_CREATE/ROUTE_CURVE_REPLACE/REVIEW',
    operator       VARCHAR(64) NOT NULL COMMENT '首次成功请求的非空操作者标识',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 十六进制摘要，结构化覆盖操作、资源、全参数与期望版本，用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';
