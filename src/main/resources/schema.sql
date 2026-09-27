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
    status              VARCHAR(16)  NOT NULL DEFAULT 'IN_TRANSIT' COMMENT '行李状态：IN_TRANSIT 在途/SHORT_UNLOADED 短卸待补/RECOVERED 已补到在途/DELIVERED 已交付',
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
    event_type VARCHAR(32)  NOT NULL COMMENT '事件类型：REGISTERED 登记/LOADED 装载/UNLOADED 到达卸下/SHORT_UNLOADED 短卸/RECOVERED 补到/DELIVERED 交付',
    leg_id     VARCHAR(64)  NULL COMMENT '关联航段标识，与航段无关的事件为 NULL',
    location   VARCHAR(64)  NULL COMMENT '事件发生后行李所在站点代码',
    event_time TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '事件发生时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, seq)
);

-- 幂等去重：仅记录成功请求；同 requestId 同参数重放原结果，异参数返回 409
CREATE TABLE IF NOT EXISTS request_log (
    request_id      VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    operation       VARCHAR(32)  NOT NULL COMMENT '操作类型：REGISTER_LEG/REGISTER_BAG/LOAD/SEAL/ARRIVE/ARRIVE_DIFFERENCE/RECOVER/CONFIG_TRANSIT_POINT/CONFIG_LEG_CONSTRAINT/TRANSIT_ARRIVAL/DWELL_CHECK/DWELL_RELEASE/REASSIGN',
    request_hash    VARCHAR(64)  NOT NULL COMMENT '请求指纹（SHA-256，含操作者、操作类型、规范化对象集合及全部影响结果字段的结构化 JSON）',
    response_status INT          NOT NULL COMMENT '原成功响应的 HTTP 状态码',
    response_body   CLOB         NOT NULL COMMENT '原成功响应体（JSON），重放返回该首次完整快照',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (request_id)
);

-- 中转点滞留配置：每个中转点最大滞留分钟数；滞留区间按 [到达时刻, 到达时刻+max_dwell_minutes) 左闭右开解释
CREATE TABLE IF NOT EXISTS transit_point_config (
    station_code      VARCHAR(64) NOT NULL COMMENT '中转点站点代码，全局唯一',
    max_dwell_minutes INT         NOT NULL COMMENT '最大允许滞留分钟数（正整数，要求值）；到达时刻起算，UTC',
    created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (station_code)
);

-- 航段装载约束（可选）：未配置行表示该航段不限制容量与截单时刻
CREATE TABLE IF NOT EXISTS leg_constraint (
    leg_id           VARCHAR(64) NOT NULL COMMENT '航段标识',
    capacity         INT         NULL COMMENT '航段容量（件，要求值），NULL 不限制；须满足 已装件数+待装件数 <= 容量',
    checkin_deadline TIMESTAMP WITH TIME ZONE NULL COMMENT '截单时刻（UTC），装载/解除时刻须早于该点（左闭右开），NULL 不限制',
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (leg_id)
);

-- 行李当前开放的滞留周期：到达中转点登记后写入，正常装载离站或解除阻断后删除；超时证据另存不可变的 dwell_record
CREATE TABLE IF NOT EXISTS bag_dwell_state (
    bag_tag           VARCHAR(64) NOT NULL COMMENT '行李牌号，一件行李同一时间至多一个开放滞留周期',
    station           VARCHAR(64) NOT NULL COMMENT '滞留中转点站点代码',
    dwell_status      VARCHAR(16) NOT NULL DEFAULT 'WITHIN_LIMIT' COMMENT '当前滞留状态：WITHIN_LIMIT 未超上限（不阻断）/DWELL_EXCEEDED 已超时并阻断装载与改派',
    arrived_at        TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '到达中转点时刻（UTC，区间起点，左闭）',
    max_dwell_minutes INT         NOT NULL COMMENT '本周期适用的滞留上限（分钟，登记时配置快照，要求值）',
    PRIMARY KEY (bag_tag)
);

-- 滞留不可变记录：到达、超时、解除、改派追加写入；后续航段变更不改写既有超时证据
CREATE TABLE IF NOT EXISTS dwell_record (
    id                     BIGINT AUTO_INCREMENT NOT NULL COMMENT '记录自增主键',
    bag_tag                VARCHAR(64)  NOT NULL COMMENT '行李牌号',
    seq                    INT          NOT NULL COMMENT '该行李滞留记录顺序，0 起递增，历史按 (bag_tag, seq) 稳定排序',
    record_type            VARCHAR(16)  NOT NULL COMMENT '记录类型：ARRIVAL 到达/DWELL_EXCEEDED 超时/RELEASED 解除/REASSIGNED 改派',
    station                VARCHAR(64)  NOT NULL COMMENT '滞留中转点站点代码',
    arrived_at             TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '本滞留周期到达时刻（UTC）；超时/解除记录冗余同一快照，不改写',
    event_time             TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '记录产生时刻（UTC）：ARRIVAL 取到达时刻，超时/解除/改派取可注入时钟裁决时刻',
    max_dwell_minutes      BIGINT       NULL COMMENT '要求滞留上限（分钟）；REASSIGNED 为 NULL',
    actual_dwell_minutes   BIGINT       NULL COMMENT '实际滞留分钟数=event_time-arrived_at 向下取整；ARRIVAL 为 0，REASSIGNED 为 NULL',
    difference_minutes     BIGINT       NULL COMMENT '差额（分钟）：未超时/解除为剩余=max-actual，超时为超出=actual-max；REASSIGNED 为 NULL',
    deadline_at            TIMESTAMP WITH TIME ZONE NULL COMMENT '滞留截止=arrived_at+max_dwell_minutes（UTC，右端点开）；REASSIGNED 为 NULL',
    next_leg_id            VARCHAR(64)  NULL COMMENT '解除/改派指定的下一航段标识；ARRIVAL/DWELL_EXCEEDED 为 NULL',
    reviewer               VARCHAR(64)  NULL COMMENT '解除阻断的复核人工号；其余记录为 NULL',
    conclusion             VARCHAR(256) NULL COMMENT '复核结论；其余记录为 NULL',
    operator               VARCHAR(64)  NULL COMMENT '操作者工号（改派登记人；解除以复核人为准）；其余记录为 NULL',
    leg_capacity           INT          NULL COMMENT '解除时下一航段容量要求快照（件），NULL 表示该航段未配置容量',
    leg_loaded_count       INT          NULL COMMENT '解除时下一航段已装载件数（实际值），未配置容量行也为 NULL',
    leg_remaining_capacity INT          NULL COMMENT '解除时下一航段剩余容量差额=capacity-loaded_count；NULL 表示未配置容量',
    checkin_deadline       TIMESTAMP WITH TIME ZONE NULL COMMENT '解除时下一航段截单时刻快照（UTC），NULL 表示未配置',
    created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间（数据库时钟，仅审计用）',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, seq)
);
