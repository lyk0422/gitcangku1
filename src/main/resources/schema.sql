-- 联程行李装载交接 schema（H2 MySQL 兼容模式）
-- 普通时间字段为数据库会话时区（默认 JVM 时区 Asia/Shanghai）；
-- TIMESTAMP WITH TIME ZONE 字段统一保存 UTC 时刻，NULL 含义见各列注释。

-- 航段：leg_id 唯一，version 单调递增，状态 OPEN -> SEALED -> DEPARTED -> ARRIVED
-- origin_country 与 destination_country 不同即为国际航段，装载/补到/改派时受海关持续门禁约束
CREATE TABLE IF NOT EXISTS leg (
    leg_id              VARCHAR(64)  NOT NULL COMMENT '航段唯一标识',
    origin              VARCHAR(64)  NOT NULL COMMENT '始发站代码',
    destination         VARCHAR(64)  NOT NULL COMMENT '到达站代码',
    origin_country      VARCHAR(8)   NOT NULL DEFAULT 'CN' COMMENT '始发国 ISO 国家代码，默认 CN 中国；NULL 不适用',
    destination_country VARCHAR(8)   NOT NULL DEFAULT 'CN' COMMENT '到达国 ISO 国家代码，默认 CN 中国；与始发国不同表示跨境航段',
    status              VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT '航段状态：OPEN 未截载/SEALED 已截载封舱/DEPARTED 已起飞/ARRIVED 已到达',
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
    event_type VARCHAR(32)  NOT NULL COMMENT '事件类型：REGISTERED 登记/LOADED 装载/UNLOADED 到达卸下/SHORT_UNLOADED 短卸/RECOVERED 补到/DELIVERED 交付/DEPARTED 起飞/CUSTOMS_HELD 海关拦截/CUSTOMS_RELEASED 海关放行',
    leg_id     VARCHAR(64)  NULL COMMENT '关联航段标识，与航段无关的事件为 NULL',
    location   VARCHAR(64)  NULL COMMENT '事件发生后行李所在站点代码',
    event_time TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '事件发生时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, seq)
);

-- 海关检查链：每件跨境行李按国家登记放行/拦截终态，同一检查版本只能一个终态（UK 兜底）
-- 解除拦截必须以更高检查版本重新登记放行；历史行永不删除，形成完整检查链。
CREATE TABLE IF NOT EXISTS customs_inspection (
    id            BIGINT AUTO_INCREMENT NOT NULL COMMENT '检查记录自增主键',
    bag_tag       VARCHAR(64)  NOT NULL COMMENT '行李牌号',
    check_version INT          NOT NULL COMMENT '海关检查版本号，同一行李从 1 起严格递增',
    status        VARCHAR(16)  NOT NULL COMMENT '检查终态：RELEASED 放行/HELD 拦截',
    country       VARCHAR(8)   NOT NULL COMMENT '检查对应的航段目的国 ISO 国家代码',
    reason        VARCHAR(512) NULL COMMENT '检查原因：放行可空，拦截必填非空',
    checked_at    TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '检查登记时刻（UTC）',
    clearance_key VARCHAR(128) NOT NULL COMMENT '幂等指纹：SHA-256(bagTag|checkVersion|status|country|reason)',
    affected_legs CLOB         NULL COMMENT '本次终态实际影响的未起飞航段（JSON 数组）：拦截为被标记 CUSTOMS_HOLD 的航段，放行或无匹配航段为空数组，用于同指纹重放原结果',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, check_version),
    UNIQUE (clearance_key)
);

-- 行李持续门禁：国际航段的放行/拦截结果按提交顺序生效到具体未起飞航段
-- 拦截将航段标记为 CUSTOMS_HOLD 并冻结当时装载快照；放行清除门禁；起飞后只读不改写。
CREATE TABLE IF NOT EXISTS bag_gate (
    id             BIGINT AUTO_INCREMENT NOT NULL COMMENT '门禁记录自增主键',
    bag_tag        VARCHAR(64)  NOT NULL COMMENT '行李牌号',
    leg_id         VARCHAR(64)  NOT NULL COMMENT '受门禁约束的国际航段标识',
    country        VARCHAR(8)   NOT NULL COMMENT '门禁对应目的国 ISO 国家代码',
    gate_status    VARCHAR(16)  NOT NULL COMMENT '门禁状态：RELEASED 放行可载/HELD 海关暂扣',
    check_version  INT          NOT NULL COMMENT '产生当前门禁的检查版本',
    hold_snapshot  CLOB         NULL COMMENT '拦截时只读快照（JSON）：行李状态、已装载航段、后续航段列表与时刻，非 CUSTOMS_HOLD 为 NULL',
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '门禁最近一次变更时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, leg_id)
);

-- 幂等去重：仅记录成功请求；同 requestId 同参数重放原结果，异参数返回 409
CREATE TABLE IF NOT EXISTS request_log (
    request_id      VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    operation       VARCHAR(32)  NOT NULL COMMENT '操作类型：REGISTER_LEG/REGISTER_BAG/LOAD/SEAL/DEPART/ARRIVE/ARRIVE_DIFFERENCE/RECOVER/REROUTE/CUSTOMS_CHECK',
    request_hash    VARCHAR(64)  NOT NULL COMMENT '请求参数（不含 requestId）的 SHA-256 摘要',
    response_status INT          NOT NULL COMMENT '原成功响应的 HTTP 状态码',
    response_body   CLOB         NOT NULL COMMENT '原成功响应体（JSON）',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (request_id)
);
