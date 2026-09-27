-- 联程行李装载交接 schema（H2 MySQL 兼容模式）
-- 普通时间字段为数据库会话时区（默认 JVM 时区 Asia/Shanghai）；
-- TIMESTAMP WITH TIME ZONE 字段统一保存 UTC 时刻，NULL 含义见各列注释。
-- 滞留相关时间精度为纳秒（TIMESTAMP(9)），所有时刻均为 UTC。

-- 中转点滞留配置：station 唯一，max_dwell_minutes 为该站允许的最大滞留分钟数
CREATE TABLE IF NOT EXISTS transit_point (
    station           VARCHAR(64) NOT NULL COMMENT '中转站点代码，全局唯一',
    max_dwell_minutes INT         NOT NULL COMMENT '该中转点允许的最大滞留时长（分钟，正整数）；区间左闭右开，到达后满该时长即判超时',
    created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (station)
);

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
    capacity        INT          NULL COMMENT '航段装载容量（件，正整数），NULL 表示不限容量；装载后总数不得超过该值',
    load_cutoff     TIMESTAMP(9) WITH TIME ZONE NULL COMMENT '截载时刻（UTC），NULL 表示不限制；区间左闭右开，到达该时刻起禁止装载与滞留解除',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (leg_id)
);

-- 行李：bag_tag 唯一，登记 1~5 个有序航段行程
CREATE TABLE IF NOT EXISTS bag (
    bag_tag             VARCHAR(64)  NOT NULL COMMENT '行李牌号，全局唯一',
    current_location    VARCHAR(64)  NOT NULL COMMENT '当前所在站点代码',
    next_leg_index      INT          NOT NULL DEFAULT 0 COMMENT '待乘航段在行程中的下标（0 起），等于行程长度表示已完成；短卸不推进',
    status              VARCHAR(16)  NOT NULL DEFAULT 'IN_TRANSIT' COMMENT '行李状态：IN_TRANSIT 在途/SHORT_UNLOADED 短卸待补/RECOVERED 已补到在途/DWELL_EXCEEDED 滞留超时被阻断/DELIVERED 已交付',
    loaded_leg_id       VARCHAR(64)  NULL COMMENT '当前已装载到的航段，未装载为 NULL',
    short_leg_id        VARCHAR(64)  NULL COMMENT '短卸缺失航段标识，仅 SHORT_UNLOADED 状态非 NULL',
    short_destination   VARCHAR(64)  NULL COMMENT '短卸应到站点代码，仅 SHORT_UNLOADED 状态非 NULL',
    short_registered_at TIMESTAMP WITH TIME ZONE NULL COMMENT '短卸登记时刻（UTC），仅 SHORT_UNLOADED 状态非 NULL',
    dwell_state         VARCHAR(16)  NULL COMMENT '当前中转访问滞留状态：NULL 未在中转点滞留/ACTIVE 已到达等待中/EXCEEDED 已超时阻断/RELEASED 已解除待离站；离站装载后清空',
    dwell_station       VARCHAR(64)  NULL COMMENT '当前滞留中转站点代码，仅 dwell_state 非 NULL 时非 NULL；历史证据见 dwell_event',
    dwell_started_at    TIMESTAMP(9) WITH TIME ZONE NULL COMMENT '当前中转访问到达时刻（UTC），滞留计时起点，仅 dwell_state 非 NULL 时非 NULL',
    dwell_limit_minutes INT          NULL COMMENT '当前中转点最大滞留分钟数（登记当时快照），仅 dwell_state 非 NULL 时非 NULL',
    dwell_exceeded_at   TIMESTAMP(9) WITH TIME ZONE NULL COMMENT '当前滞留超时判定时刻（UTC），仅 dwell_state=EXCEEDED 时非 NULL；解除后清空，历史证据不改写',
    dwell_next_leg_id   VARCHAR(64)  NULL COMMENT '复核解除时指定的下一航段，仅 dwell_state=RELEASED 时非 NULL，离站装载后清空',
    dwell_prev_status   VARCHAR(16)  NULL COMMENT '进入滞留访问前的行李状态（IN_TRANSIT/RECOVERED），解除阻断时原样恢复，离站装载后清空',
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
    event_type VARCHAR(32)  NOT NULL COMMENT '事件类型：REGISTERED 登记/LOADED 装载/UNLOADED 到达卸下/SHORT_UNLOADED 短卸/RECOVERED 补到/DWELL_ARRIVED 中转到达/DWELL_EXCEEDED 滞留超时/DWELL_RELEASED 滞留解除/DELIVERED 交付',
    leg_id     VARCHAR(64)  NULL COMMENT '关联航段标识，与航段无关的事件为 NULL',
    location   VARCHAR(64)  NULL COMMENT '事件发生后行李所在站点代码',
    event_time TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '事件发生时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, seq)
);

-- 滞留不可变证据流：到达、超时、解除只追加不改写；(bag_tag, seq) 唯一，按 (bag_tag, seq, id) 稳定排序
-- 后续航段变更、再次到达或解除均不改写既有记录；时间精度纳秒，NULL 语义见各列注释。
CREATE TABLE IF NOT EXISTS dwell_event (
    id               BIGINT AUTO_INCREMENT NOT NULL COMMENT '证据自增主键',
    bag_tag          VARCHAR(64)  NOT NULL COMMENT '行李牌号',
    seq              INT          NOT NULL COMMENT '该行李滞留证据顺序，0 起递增，只追加',
    event_type       VARCHAR(32)  NOT NULL COMMENT '证据类型：ARRIVED 中转到达/TIMEOUT 超时判定/RELEASED 复核解除',
    station          VARCHAR(64)  NOT NULL COMMENT '中转站点代码',
    arrived_at       TIMESTAMP(9) WITH TIME ZONE NULL COMMENT '本次中转访问到达时刻（UTC）：ARRIVED 记录等于 event_time，TIMEOUT/RELEASED 为当时到达时刻副本',
    limit_minutes    INT          NULL COMMENT '本次中转访问最大滞留分钟数（要求值），ARRIVED 时写入，后续记录复制',
    deadline         TIMESTAMP(9) WITH TIME ZONE NULL COMMENT '滞留截止时刻（UTC）= arrived_at + limit_minutes，左闭右开；等于该时刻即超时',
    event_time       TIMESTAMP(9) WITH TIME ZONE NOT NULL COMMENT '该证据产生时刻（UTC）：到达时刻/判定时刻/解除时刻',
    elapsed_minutes  BIGINT       NULL COMMENT '判定或解除时实际滞留分钟数（实际值，向下取整，非负）；ARRIVED 为 NULL',
    excess_minutes   BIGINT       NULL COMMENT '超出上限的分钟数（差额，max(0, elapsed-limit)）；ARRIVED 为 NULL',
    next_leg_id      VARCHAR(64)  NULL COMMENT '解除时复核人指定的下一航段；ARRIVED/TIMEOUT 为 NULL',
    reviewer         VARCHAR(64)  NULL COMMENT '解除阻断的处理人（操作者）；ARRIVED/TIMEOUT 为 NULL',
    review_conclusion VARCHAR(256) NULL COMMENT '解除时登记的复核结论；ARRIVED/TIMEOUT 为 NULL',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, seq)
);

-- 幂等去重：仅记录成功请求；同 requestId 同参数重放原结果，异参数返回 409
CREATE TABLE IF NOT EXISTS request_log (
    request_id      VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    operation       VARCHAR(32)  NOT NULL COMMENT '操作类型：REGISTER_LEG/REGISTER_BAG/LOAD/SEAL/ARRIVE/ARRIVE_DIFFERENCE/RECOVER/REGISTER_TRANSIT_POINT/TRANSIT_ARRIVAL/DWELL_CHECK/DWELL_RELEASE',
    request_hash    VARCHAR(64)  NOT NULL COMMENT '操作类型与规范化请求参数（含操作者、规范化集合，不含 requestId）的 SHA-256 摘要',
    response_status INT          NOT NULL COMMENT '原成功响应的 HTTP 状态码',
    response_body   CLOB         NOT NULL COMMENT '原成功响应体（JSON）',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (request_id)
);
