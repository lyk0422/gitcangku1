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
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW/CORRIDOR_CREATE/CORRIDOR_REVOKE/REGULAR_OCCUPANCY/EMERGENCY_OCCUPANCY/OCCUPANCY_REAPPLY/DEPARTURE_REGISTER',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 十六进制摘要，用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';

-- 空域应急走廊：授权调度员建立，有效期 [start_utc, end_utc) 左闭右开、
-- 高度 [alt_min, alt_max) 左闭右开；覆盖路径段在有效期内只允许应急航线使用。
-- 只能创建（ACTIVE）或撤销（REVOKED）；自然到期不改状态，按时间窗判定有效。
CREATE TABLE IF NOT EXISTS corridor (
    corridor_id              VARCHAR(64) PRIMARY KEY COMMENT '应急走廊唯一标识',
    priority                 INT NOT NULL COMMENT '走廊优先级（非负整数）；同优先级走廊不得在时空高度上重叠',
    x_min                    INT NOT NULL COMMENT '走廊矩形左边界（含），单位米',
    y_min                    INT NOT NULL COMMENT '走廊矩形下边界（含），单位米',
    x_max                    INT NOT NULL COMMENT '走廊矩形右边界（含），单位米，x_min < x_max',
    y_max                    INT NOT NULL COMMENT '走廊矩形上边界（含），单位米，y_min < y_max',
    start_utc                BIGINT NOT NULL COMMENT '生效起点，epoch 毫秒（UTC），区间含该时刻',
    end_utc                  BIGINT NOT NULL COMMENT '生效终点，epoch 毫秒（UTC），区间不含该时刻，start_utc < end_utc',
    alt_min                  INT NOT NULL COMMENT '最低高度（含），单位米',
    alt_max                  INT NOT NULL COMMENT '最高高度（不含），单位米，alt_min < alt_max',
    status                   VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 未撤销（是否当前有效另按时间窗判定）；REVOKED 已撤销',
    reason                   VARCHAR(255) NOT NULL COMMENT '建立走廊并要求相关常规占用让渡的原因（让渡记录固化同一文案）',
    created_by               VARCHAR(64) NOT NULL COMMENT '建立走廊的授权调度员标识',
    created_airspace_version BIGINT NOT NULL COMMENT '走廊版本：创建生效时的全局空域版本',
    revoked_airspace_version BIGINT NULL COMMENT '撤销生效时的全局空域版本；NULL 表示未撤销',
    created_at               BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）',
    revoked_at               BIGINT NULL COMMENT '撤销时间，epoch 毫秒（UTC）；NULL 表示未撤销'
) COMMENT = '空域应急走廊（时间、高度区间左闭右开，只能创建或撤销）';

-- 空域占用：审查通过的航线对指定时间窗、高度窗与路径快照的占用。
-- 常规占用可被应急走廊让渡（ACTIVE -> YIELDED）；应急占用不参与让渡。
CREATE TABLE IF NOT EXISTS occupancy (
    occupancy_id      VARCHAR(64) PRIMARY KEY COMMENT '占用唯一标识（登记时由客户端指定）',
    route_id          VARCHAR(64) NOT NULL COMMENT '占用对应的航线标识',
    review_id         VARCHAR(64) NOT NULL COMMENT '占用所依据的不可变审查标识（必须为 CLEAR）',
    route_version     INT NOT NULL COMMENT '登记时固化的航线版本（取自审查记录）',
    airspace_version  BIGINT NOT NULL COMMENT '登记时固化的空域版本（取自审查记录）',
    kind              VARCHAR(16) NOT NULL COMMENT '占用类型：REGULAR 常规；EMERGENCY 应急',
    start_utc         BIGINT NOT NULL COMMENT '占用起点，epoch 毫秒（UTC），区间含该时刻',
    end_utc           BIGINT NOT NULL COMMENT '占用终点，epoch 毫秒（UTC），区间不含该时刻',
    alt_min           INT NOT NULL COMMENT '占用最低高度（含），单位米',
    alt_max           INT NOT NULL COMMENT '占用最高高度（不含），单位米',
    status            VARCHAR(16) NOT NULL COMMENT 'ACTIVE 有效占用；YIELDED 已让渡给应急走廊（不自动恢复）',
    yieldable         BOOLEAN NOT NULL COMMENT '常规占用是否允许被应急走廊让渡；应急占用恒为 TRUE 且不适用',
    departed          BOOLEAN NOT NULL COMMENT '是否已登记起飞；TRUE 后占用不可让渡',
    corridor_id       VARCHAR(64) NULL COMMENT '让渡本占用的走廊标识；NULL 表示未让渡',
    parent_occupancy_id VARCHAR(64) NULL COMMENT '重新申请时原占用标识；NULL 表示原始登记而非重新申请',
    points_snapshot   VARCHAR(4000) NOT NULL COMMENT '审查时航点不可变快照，格式 x,y;x,y',
    created_at        BIGINT NOT NULL COMMENT '登记时间，epoch 毫秒（UTC）',
    departed_at       BIGINT NULL COMMENT '起飞登记时间，epoch 毫秒（UTC）；NULL 表示尚未起飞'
) COMMENT = '航线时空高度占用（常规可让渡，应急优先）';

-- 让渡不可变历史：走廊创建事务内对每个让渡占用追加一行，
-- 固化原审查版本、走廊版本与让渡原因，永不更新或删除。
CREATE TABLE IF NOT EXISTS yield_record (
    seq               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增序号，仅用于稳定排序',
    occupancy_id      VARCHAR(64) NOT NULL COMMENT '被让渡的常规占用标识',
    corridor_id       VARCHAR(64) NOT NULL COMMENT '受让渡的应急走廊标识',
    corridor_version  BIGINT NOT NULL COMMENT '让渡时固化的走廊版本（走廊创建时的全局空域版本）',
    review_id         VARCHAR(64) NOT NULL COMMENT '让渡时固化的原审查标识',
    route_version     INT NOT NULL COMMENT '让渡时固化的原航线版本',
    airspace_version  BIGINT NOT NULL COMMENT '让渡时固化的原空域版本',
    reason            VARCHAR(255) NOT NULL COMMENT '让渡原因（与走廊创建请求一致）',
    created_at        BIGINT NOT NULL COMMENT '让渡时间，epoch 毫秒（UTC）'
) COMMENT = '应急走廊让渡不可变历史';

-- 占用处置事件不可变流：登记/让渡/重新申请/起飞各追加一行，永不更新或删除。
-- 排序固定按 seq 升序；时间精度为 epoch 毫秒；可空列的 NULL 语义见各列注释。
CREATE TABLE IF NOT EXISTS occupancy_event (
    seq                   BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增序号，事件固定按此升序排序',
    occupancy_id          VARCHAR(64) NOT NULL COMMENT '事件所属占用标识',
    event_type            VARCHAR(16) NOT NULL COMMENT '事件类型：CREATED 登记；YIELDED 让渡；REAPPLIED 原占用被重新申请；DEPARTED 起飞',
    event_time            BIGINT NOT NULL COMMENT '事件时间，epoch 毫秒（UTC）',
    corridor_id           VARCHAR(64) NULL COMMENT 'YIELDED 事件关联的走廊标识；其余事件为 NULL',
    related_occupancy_id  VARCHAR(64) NULL COMMENT 'REAPPLIED 事件关联的新占用标识；其余事件为 NULL',
    review_id             VARCHAR(64) NULL COMMENT 'CREATED 事件依据的审查标识；其余事件为 NULL',
    reason                VARCHAR(255) NULL COMMENT 'YIELDED 事件的让渡原因；其余事件为 NULL'
) COMMENT = '占用处置不可变事件流（登记/让渡/重新申请/起飞）';
