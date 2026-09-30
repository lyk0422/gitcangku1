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
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW/DIVERSION_AIRPORT_CREATE/DIVERSION_RESERVE/DIVERSION_DEPART',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 十六进制摘要，用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';

-- ============================ 备降机场预留 ============================

-- 备降机场：airportId 唯一；capacity 为同一时刻窗口容量（>=1）。
-- touch 仅用于预留事务对机场行产生真实更新以加行级排他锁：
-- 同机场的预留/起飞事务经该行锁串行化，竞争申请按提交时容量裁决。
CREATE TABLE IF NOT EXISTS diversion_airport (
    airport_id  VARCHAR(64) PRIMARY KEY COMMENT '备降机场唯一标识',
    capacity    INT NOT NULL COMMENT '同一窗口内可同时容纳的预留数量，恒 >=1',
    touch       BIGINT NOT NULL COMMENT '仅用于预留事务加行级写锁的计数器，无业务含义',
    created_at  BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）'
) COMMENT = '备降机场（窗口容量配置，只能创建一次）';

-- 备降预留：一条航线对某机场一个 UTC 左闭右开窗口 [window_start, window_end) 的占用申请。
-- priority 数值越大优先级越高；status=ACTIVE 时占用窗口容量，DISPLACED 为被高优先级整体挤出、
-- 不再占用容量；departed=TRUE 表示已起飞，已起飞预留占用保留且不可被挤出。
CREATE TABLE IF NOT EXISTS diversion_reservation (
    reservation_id  VARCHAR(64) PRIMARY KEY COMMENT '预留唯一标识（不可变）',
    airport_id      VARCHAR(64) NOT NULL COMMENT '备降机场标识',
    route_id        VARCHAR(64) NOT NULL COMMENT '申请预留的航线标识',
    priority        INT NOT NULL COMMENT '申请优先级，数值越大越高；高优先级可挤出尚未起飞的低优先级，同级不得互挤',
    window_start    BIGINT NOT NULL COMMENT '窗口起点，epoch 毫秒（UTC），左闭区间，恒 < window_end',
    window_end      BIGINT NOT NULL COMMENT '窗口终点，epoch 毫秒（UTC），右开区间',
    status          VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 占用窗口容量；DISPLACED 已被高优先级整体挤出、不再占用',
    departed        BOOLEAN NOT NULL COMMENT '是否已起飞：TRUE 时占用保留且任何申请都不可将其挤出',
    request_id      VARCHAR(64) NOT NULL COMMENT '创建预留的写操作请求标识（幂等键）',
    created_at      BIGINT NOT NULL COMMENT '创建时间，epoch 毫秒（UTC）'
) COMMENT = '备降机场预留（UTC 左闭右开窗口占用，优先级挤出后整体置 DISPLACED）';

CREATE UNIQUE INDEX IF NOT EXISTS ux_diversion_reservation_request
    ON diversion_reservation (request_id);
CREATE INDEX IF NOT EXISTS ix_diversion_reservation_airport
    ON diversion_reservation (airport_id, status);

-- 预留替换链：每行是一次不可变挤出事件。高优先级申请成功时，与新预留记录在同一事务内
-- 一次写入全部被挤出预留的事件行及新占用；事件永不更新或删除，后续起飞状态不改写旧替换结果。
-- 多跳链：replacement_reservation_id 自身也可能出现在另一条事件的 replaced_reservation_id 中。
CREATE TABLE IF NOT EXISTS reservation_replacement (
    replacement_id            VARCHAR(64) PRIMARY KEY COMMENT '替换事件唯一标识（不可变）',
    airport_id                VARCHAR(64) NOT NULL COMMENT '发生替换的备降机场标识',
    replaced_reservation_id   VARCHAR(64) NOT NULL COMMENT '被挤出的预留标识（事件后状态为 DISPLACED）',
    replaced_route_id         VARCHAR(64) NOT NULL COMMENT '被挤出预留所属航线标识',
    replaced_priority         INT NOT NULL COMMENT '被挤出预留的优先级（不可变快照），低于 replacement_priority',
    replacement_reservation_id VARCHAR(64) NOT NULL COMMENT '挤出方（新预留）标识',
    replacement_route_id      VARCHAR(64) NOT NULL COMMENT '挤出方（新预留）所属航线标识',
    replacement_priority      INT NOT NULL COMMENT '挤出方（新预留）优先级（不可变快照）',
    window_start              BIGINT NOT NULL COMMENT '被挤出预留窗口起点，epoch 毫秒（UTC），左闭（不可变快照）',
    window_end                BIGINT NOT NULL COMMENT '被挤出预留窗口终点，epoch 毫秒（UTC），右开（不可变快照）',
    created_at                BIGINT NOT NULL COMMENT '替换事件发生时间，epoch 毫秒（UTC）'
) COMMENT = '备降预留不可变替换（挤出）事件链';

CREATE INDEX IF NOT EXISTS ix_replacement_airport
    ON reservation_replacement (airport_id);
CREATE INDEX IF NOT EXISTS ix_replacement_replaced
    ON reservation_replacement (replaced_reservation_id);
CREATE INDEX IF NOT EXISTS ix_replacement_replacement
    ON reservation_replacement (replacement_reservation_id);
