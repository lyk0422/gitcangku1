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
    review_kind       VARCHAR(16) NOT NULL COMMENT '审查种类：REGULAR 常规；EMERGENCY 应急（不能绕过禁飞区）'
) COMMENT = '审核不可变结果';

CREATE UNIQUE INDEX IF NOT EXISTS ux_review_request ON review (request_id);

-- 写操作幂等去重：同键同参重放原结果，异参冲突；失败不占键
CREATE TABLE IF NOT EXISTS request_dedup (
    request_id     VARCHAR(64) PRIMARY KEY COMMENT '写操作全局唯一请求标识',
    request_kind   VARCHAR(32) NOT NULL COMMENT '请求类型：ZONE_CREATE/ZONE_REVOKE/ROUTE_CREATE/ROUTE_REPLACE/REVIEW/CORRIDOR_CREATE/CORRIDOR_REVOKE/REGULAR_OCCUPANCY/EMERGENCY_OCCUPANCY/DEPARTURE/YIELDED_REAPPLY',
    request_hash   VARCHAR(64) NOT NULL COMMENT '规范化参数的 SHA-256 十六进制摘要，用于同键异参冲突判定',
    response_json  CLOB NOT NULL COMMENT '首次成功响应 JSON，重放时原样返回',
    created_at     BIGINT NOT NULL COMMENT '首次成功时间，epoch 毫秒（UTC）'
) COMMENT = '写操作幂等去重记录（与业务变更同事务原子提交）';

-- 空域应急走廊：授权调度员在指定矩形区域、UTC 时间窗（左闭右开）与高度范围（左闭右开）建立。
-- 同一走廊只能建立一次、撤销一次；同优先级走廊的时空体积不得重叠（由服务串行化判定）。
CREATE TABLE IF NOT EXISTS emergency_corridor (
    corridor_id    VARCHAR(64) PRIMARY KEY COMMENT '应急走廊唯一标识',
    x_min          INT NOT NULL COMMENT '覆盖矩形左边界（含），单位米',
    y_min          INT NOT NULL COMMENT '覆盖矩形下边界（含），单位米',
    x_max          INT NOT NULL COMMENT '覆盖矩形右边界（含），单位米，x_min < x_max',
    y_max          INT NOT NULL COMMENT '覆盖矩形上边界（含），单位米，y_min < y_max',
    start_ms       BIGINT NOT NULL COMMENT '有效期起点（含），epoch 毫秒（UTC）',
    end_ms         BIGINT NOT NULL COMMENT '有效期终点（不含），epoch 毫秒（UTC），start_ms < end_ms',
    alt_min        INT NOT NULL COMMENT '高度下界（含），单位米',
    alt_max        INT NOT NULL COMMENT '高度上界（不含），单位米，alt_min < alt_max',
    priority       INT NOT NULL COMMENT '走廊优先级，整数越大优先级越高；同优先级时空重叠被拒绝',
    status         VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 已建立（是否有效另看时间窗）；REVOKED 已撤销',
    version        INT NOT NULL COMMENT '走廊版本，建立为 1，撤销加一',
    revoke_reason  VARCHAR(500) NULL COMMENT '撤销原因；NULL 表示尚未撤销',
    revoked_ms     BIGINT NULL COMMENT '撤销时刻，epoch 毫秒（UTC）；NULL 表示尚未撤销',
    created_ms     BIGINT NOT NULL COMMENT '建立事务提交时的记录时刻，epoch 毫秒（UTC）'
) COMMENT = '空域应急走廊（矩形区域 + UTC 左闭右开时间窗 + 左闭右开高度范围）';

-- 走廊不可变事件历史：建立与撤销各追加一条，旧证据永不回写。
CREATE TABLE IF NOT EXISTS corridor_event (
    seq           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '单调递增事件序号，决定历史排序',
    corridor_id   VARCHAR(64) NOT NULL COMMENT '所属走廊标识',
    version       INT NOT NULL COMMENT '事件对应的走廊版本：建立为 1，撤销为 2',
    action        VARCHAR(16) NOT NULL COMMENT '事件动作：CREATED 建立；REVOKED 撤销',
    reason        VARCHAR(500) NULL COMMENT '动作原因：建立时为让渡/建立说明，撤销时为撤销原因；NULL 表示未提供',
    request_id    VARCHAR(64) NOT NULL COMMENT '触发该事件的写操作请求标识',
    event_ms      BIGINT NOT NULL COMMENT '事件记录时刻，epoch 毫秒（UTC）',
    x_min         INT NOT NULL COMMENT '事件时走廊覆盖矩形左边界（含），单位米',
    y_min         INT NOT NULL COMMENT '覆盖矩形下边界（含），单位米',
    x_max         INT NOT NULL COMMENT '覆盖矩形右边界（含），单位米',
    y_max         INT NOT NULL COMMENT '覆盖矩形上边界（含），单位米',
    start_ms      BIGINT NOT NULL COMMENT '有效期起点（含），epoch 毫秒（UTC）',
    end_ms        BIGINT NOT NULL COMMENT '有效期终点（不含），epoch 毫秒（UTC）',
    alt_min       INT NOT NULL COMMENT '高度下界（含），单位米',
    alt_max       INT NOT NULL COMMENT '高度上界（不含），单位米',
    priority      INT NOT NULL COMMENT '走廊优先级'
) COMMENT = '应急走廊不可变事件历史（建立/撤销追加，永不修改）';

-- 航线时空占用：走廊、让渡与起飞登记的裁决对象。
-- 同一航线历史上可有多条占用（让渡后重新申请产生新占用），占用记录不可变；
-- 仅 status 发生 ACTIVE→YIELDED / ACTIVE→DEPARTED 的一次性状态迁移。
CREATE TABLE IF NOT EXISTS route_occupancy (
    occupancy_id      VARCHAR(64) PRIMARY KEY COMMENT '占用唯一标识（不可变）',
    route_id          VARCHAR(64) NOT NULL COMMENT '占用航线标识',
    kind              VARCHAR(16) NOT NULL COMMENT '占用种类：REGULAR 常规；EMERGENCY 应急',
    status            VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效；YIELDED 已让渡；DEPARTED 已起飞',
    yieldable         BOOLEAN NOT NULL COMMENT '常规占用是否可让渡：TRUE 时空被新走廊命中时可置 YIELDED；FALSE 致走廊创建整次 422；应急占用恒为 FALSE',
    start_ms          BIGINT NOT NULL COMMENT '占用时间窗起点（含），epoch 毫秒（UTC）',
    end_ms            BIGINT NOT NULL COMMENT '占用时间窗终点（不含），epoch 毫秒（UTC），start_ms < end_ms',
    alt_min           INT NOT NULL COMMENT '占用高度下界（含），单位米',
    alt_max           INT NOT NULL COMMENT '占用高度上界（不含），单位米，alt_min < alt_max',
    review_id         VARCHAR(64) NOT NULL COMMENT '占用引用的不可变审查标识（应急须为 EMERGENCY/CLEAR）',
    corridor_id       VARCHAR(64) NULL COMMENT '应急占用命中的走廊标识；常规占用为 NULL；让渡后回填原走廊',
    request_id        VARCHAR(64) NOT NULL COMMENT '创建占用的写操作请求标识',
    created_ms        BIGINT NOT NULL COMMENT '占用创建时刻，epoch 毫秒（UTC）',
    yielded_ms        BIGINT NULL COMMENT '让渡时刻，epoch 毫秒（UTC）；NULL 表示未让渡',
    yield_corridor_id VARCHAR(64) NULL COMMENT '导致让渡的走廊标识；NULL 表示未让渡',
    yield_reason      VARCHAR(500) NULL COMMENT '让渡原因（固化自走廊建立请求）；NULL 表示未让渡',
    departed_ms       BIGINT NULL COMMENT '起飞登记时刻，epoch 毫秒（UTC）；NULL 表示未起飞'
) COMMENT = '航线时空占用（常规/应急，状态机 ACTIVE→YIELDED/DEPARTED）';

CREATE INDEX IF NOT EXISTS ix_occupancy_route ON route_occupancy (route_id);
CREATE INDEX IF NOT EXISTS ix_occupancy_status ON route_occupancy (status);

-- 让渡不可变历史：走廊建立事务内对每个让渡占用追加一条，固化原审查版本与让渡原因。
-- 走廊撤销或到期不改变本表；已让渡航线不自动恢复。
CREATE TABLE IF NOT EXISTS yield_history (
    seq                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '单调递增让渡序号，决定历史排序',
    corridor_id          VARCHAR(64) NOT NULL COMMENT '导致让渡的应急走廊标识',
    corridor_version     INT NOT NULL COMMENT '让渡时走廊版本（建立为 1）',
    occupancy_id         VARCHAR(64) NOT NULL COMMENT '被让渡的原占用标识',
    route_id             VARCHAR(64) NOT NULL COMMENT '被让渡的航线标识',
    review_id            VARCHAR(64) NOT NULL COMMENT '原占用引用的审查标识（原审查版本被固化，不回写）',
    route_version        INT NOT NULL COMMENT '原审查时的航线版本（固化）',
    airspace_version     BIGINT NOT NULL COMMENT '原审查时的空域版本（固化）',
    reason               VARCHAR(500) NOT NULL COMMENT '让渡原因（固化自走廊建立请求）',
    request_id           VARCHAR(64) NOT NULL COMMENT '走廊建立写操作请求标识',
    yielded_ms           BIGINT NOT NULL COMMENT '让渡时刻，epoch 毫秒（UTC）'
) COMMENT = '占用让渡不可变历史（固化原审查版本与让渡原因，不自动恢复）';

-- 让渡航线重新申请不可变历史：必须重新审查并显式申请，处置结果与新占用一并固化。
CREATE TABLE IF NOT EXISTS reapply_history (
    seq                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '单调递增申请序号，决定历史排序',
    corridor_id        VARCHAR(64) NOT NULL COMMENT '原让渡走廊标识（走廊已撤销/到期仍保留关联）',
    old_occupancy_id   VARCHAR(64) NOT NULL COMMENT '原被让渡占用标识',
    new_occupancy_id   VARCHAR(64) NOT NULL COMMENT '重新申请成功后新建占用标识',
    route_id           VARCHAR(64) NOT NULL COMMENT '重新申请的航线标识',
    review_id          VARCHAR(64) NOT NULL COMMENT '重新审查产生的不可变审查标识',
    result             VARCHAR(16) NOT NULL COMMENT '处置结果：APPROVED 通过（失败不写历史、不占键）',
    request_id         VARCHAR(64) NOT NULL COMMENT '重新申请写操作请求标识',
    applied_ms         BIGINT NOT NULL COMMENT '重新申请事务提交时刻，epoch 毫秒（UTC）'
) COMMENT = '让渡航线显式重新申请不可变历史';
