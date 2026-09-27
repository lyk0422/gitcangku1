-- 联程行李装载交接 schema（H2 MySQL 兼容模式）
-- 普通时间字段为数据库会话时区（默认 JVM 时区 Asia/Shanghai）；
-- TIMESTAMP WITH TIME ZONE 字段统一保存 UTC 时刻，NULL 含义见各列注释。

-- 航段：leg_id 唯一，version 单调递增，状态 OPEN -> SEALED -> ARRIVED
CREATE TABLE IF NOT EXISTS leg (
    leg_id          VARCHAR(64)  NOT NULL COMMENT '航段唯一标识',
    origin          VARCHAR(64)  NOT NULL COMMENT '始发站代码',
    destination     VARCHAR(64)  NOT NULL COMMENT '到达站代码',
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT '航段状态：OPEN/SEALED/ARRIVED',
    version         INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本，每次状态或装载变更后递增',
    sealed_manifest CLOB         NULL COMMENT '封舱时的只读装载清单（JSON 数组），未封舱为 NULL',
    arrival_type    VARCHAR(16)  NULL COMMENT '到达确认类型：NULL 未到达，EXACT 精确到达，DIFF 差异到达',
    arrival_actual  CLOB         NULL COMMENT '差异到达实际到达袋号只读快照（JSON 数组），未差异到达为 NULL',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (leg_id)
);

-- 行李：bag_tag 唯一，登记 1~5 个有序航段行程
CREATE TABLE IF NOT EXISTS bag (
    bag_tag             VARCHAR(64)  NOT NULL COMMENT '行李牌号，全局唯一',
    current_location    VARCHAR(64)  NOT NULL COMMENT '当前所在站点代码',
    next_leg_index      INT          NOT NULL DEFAULT 0 COMMENT '待乘航段在行程中的下标（0 起），等于行程长度表示已完成；短卸不推进',
    status              VARCHAR(16)  NOT NULL DEFAULT 'IN_TRANSIT' COMMENT '行李状态：IN_TRANSIT 在途/SHORT_UNLOADED 短卸待补/RECOVERED 已补到在途/DELIVERED 已交付/DWELL_EXCEEDED 滞留超时阻断',
    loaded_leg_id       VARCHAR(64)  NULL COMMENT '当前已装载到的航段，未装载为 NULL',
    short_leg_id        VARCHAR(64)  NULL COMMENT '短卸缺失航段标识，仅 SHORT_UNLOADED 状态非 NULL',
    short_destination   VARCHAR(64)  NULL COMMENT '短卸应到站点代码，仅 SHORT_UNLOADED 状态非 NULL',
    short_registered_at TIMESTAMP WITH TIME ZONE NULL COMMENT '短卸登记时刻（UTC），仅 SHORT_UNLOADED 状态非 NULL',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (bag_tag)
);

-- 行李行程明细：有序航段列表，相邻航段首尾站衔接
CREATE TABLE IF NOT EXISTS bag_itinerary (
    bag_tag     VARCHAR(64) NOT NULL COMMENT '行李牌号',
    seq         INT         NOT NULL COMMENT '行程顺序，0 起递增',
    leg_id      VARCHAR(64) NOT NULL COMMENT '航段标识',
    origin      VARCHAR(64) NOT NULL COMMENT '该段始发站（冗余自航段，便于轨迹查询）',
    destination VARCHAR(64) NOT NULL COMMENT '该段到达站',
    PRIMARY KEY (bag_tag, seq)
);

-- 装载明细：bag_tag 为主键，保证一件行李同一时间只在一个航段的装载清单中
CREATE TABLE IF NOT EXISTS load_record (
    bag_tag   VARCHAR(64) NOT NULL COMMENT '行李牌号',
    leg_id    VARCHAR(64) NOT NULL COMMENT '装载到的航段',
    loaded_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '装载时间',
    PRIMARY KEY (bag_tag)
);

-- 行李实际事件流：支撑完整轨迹查询，(bag_tag, seq) 唯一保证每件行李事件顺序不重复
CREATE TABLE IF NOT EXISTS bag_event (
    id         BIGINT AUTO_INCREMENT NOT NULL COMMENT '事件自增主键',
    bag_tag    VARCHAR(64)  NOT NULL COMMENT '行李牌号',
    seq        INT          NOT NULL COMMENT '该行李内事件顺序，0 起递增',
    event_type VARCHAR(32)  NOT NULL COMMENT '事件类型：REGISTERED 登记/LOADED 装载/UNLOADED 到达卸下/SHORT_UNLOADED 短卸/RECOVERED 补到/DELIVERED 交付/REROUTED 改派',
    leg_id     VARCHAR(64)  NULL COMMENT '关联航段标识，与航段无关的事件为 NULL',
    location   VARCHAR(64)  NULL COMMENT '事件发生后行李所在站点代码',
    event_time TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '事件发生时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, seq)
);

-- 幂等去重：仅记录成功请求；同 requestId 同参数重放原结果，异参数返回 409
CREATE TABLE IF NOT EXISTS request_log (
    request_id      VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    operation       VARCHAR(32)  NOT NULL COMMENT '操作类型：REGISTER_LEG/REGISTER_BAG/LOAD/SEAL/ARRIVE/ARRIVE_DIFFERENCE/RECOVER/CONFIGURE_DWELL_LIMIT/CONFIGURE_LEG_OPS/TRANSIT_ARRIVE/DWELL_TIMEOUT_CHECK/RELEASE_DWELL/REROUTE',
    request_hash    VARCHAR(64)  NOT NULL COMMENT '请求参数（不含 requestId）的 SHA-256 摘要',
    response_status INT          NOT NULL COMMENT '原成功响应的 HTTP 状态码',
    response_body   CLOB         NOT NULL COMMENT '原成功响应体（JSON）',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (request_id)
);

-- 中转点滞留上限配置：station 唯一，可重复提交更新；滞留时长按 UTC 计算，区间左闭右开 [0, max)
CREATE TABLE IF NOT EXISTS dwell_config (
    station           VARCHAR(64) NOT NULL COMMENT '中转点站点代码',
    max_dwell_minutes BIGINT      NOT NULL COMMENT '最大滞留分钟数，滞留时长达到或超过该值判定超时',
    updated_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (station)
);

-- 航段运行配置：装载截止时刻与最大装载容量，复核解除阻断时重新校验
CREATE TABLE IF NOT EXISTS leg_ops_config (
    leg_id        VARCHAR(64) NOT NULL COMMENT '航段唯一标识',
    load_deadline TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '装载截止时刻（UTC），区间左闭右开：解除时刻须早于该值',
    max_capacity  INT         NOT NULL COMMENT '最大装载容量（件），当前装载数须小于该值',
    updated_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (leg_id)
);

-- 滞留记录：一次中转滞留一行；arrived_at/exceeded_at/released_at/reviewer/review_conclusion/next_leg_id
-- 为不可变证据字段，写入后不被后续操作改写；仅 status 与 closed_at 随生命周期推进
CREATE TABLE IF NOT EXISTS dwell_record (
    id                BIGINT AUTO_INCREMENT NOT NULL COMMENT '滞留记录自增主键',
    bag_tag           VARCHAR(64) NOT NULL COMMENT '行李牌号',
    station           VARCHAR(64) NOT NULL COMMENT '滞留中转点站点代码',
    arrived_at        TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '到达中转点时刻（UTC），写入后不可变',
    status            VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '滞留状态：OPEN 滞留中/EXCEEDED 已超时/RELEASED 已解除/CLOSED 已关闭（未超时即装载或改派离开）',
    exceeded_at       TIMESTAMP WITH TIME ZONE NULL COMMENT '超时判定时刻（UTC），未超时为 NULL，写入后不可变',
    released_at       TIMESTAMP WITH TIME ZONE NULL COMMENT '解除阻断时刻（UTC），未解除为 NULL，写入后不可变',
    reviewer          VARCHAR(64) NULL COMMENT '复核人，未解除为 NULL，写入后不可变',
    review_conclusion VARCHAR(256) NULL COMMENT '复核结论，未解除为 NULL，写入后不可变',
    next_leg_id       VARCHAR(64) NULL COMMENT '解除时指定的下一航段，未解除为 NULL，写入后不可变',
    closed_at         TIMESTAMP WITH TIME ZONE NULL COMMENT '离开滞留站点时刻（UTC，装载或改派），未离开为 NULL',
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_dwell_record_bag ON dwell_record (bag_tag, id);
CREATE INDEX IF NOT EXISTS idx_dwell_record_station ON dwell_record (station, status);

-- 滞留事件流：追加只写，任何后续操作不改写历史行；(bag_tag, seq) 唯一保证每件行李事件顺序稳定
CREATE TABLE IF NOT EXISTS dwell_event (
    id          BIGINT AUTO_INCREMENT NOT NULL COMMENT '事件自增主键，全局递增反映提交顺序',
    bag_tag     VARCHAR(64)  NOT NULL COMMENT '行李牌号',
    seq         INT          NOT NULL COMMENT '该行李内事件顺序，0 起递增',
    event_type  VARCHAR(16)  NOT NULL COMMENT '事件类型：ARRIVED 到达/EXCEEDED 超时/RELEASED 解除/CLOSED 关闭',
    station     VARCHAR(64)  NOT NULL COMMENT '滞留站点代码',
    operator    VARCHAR(64)  NULL COMMENT '操作者（到达登记人/判定人/复核人），系统关闭事件为 NULL',
    next_leg_id VARCHAR(64)  NULL COMMENT '解除时指定的下一航段，其余事件为 NULL',
    detail      VARCHAR(256) NULL COMMENT '补充说明（复核结论等），无补充为 NULL',
    event_time  TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '事件发生时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, seq)
);
