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
    sealed_manifest CLOB         NULL COMMENT '封舱时的只读装载清单（JSON 数组，元素为 {bagId, bagTag, tagGeneration}，冻结封舱时刻的稳定标识、当时标签与代次），未封舱为 NULL',
    arrival_type    VARCHAR(16)  NULL COMMENT '到达确认类型：NULL 未到达，EXACT 精确到达，DIFF 差异到达',
    arrival_actual  CLOB         NULL COMMENT '差异到达实际到达袋号只读快照（JSON 数组，存清单冻结标签），未差异到达为 NULL',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (leg_id)
);

-- 行李实物：bag_id 为稳定实物标识，换标不变；bag_tag 为当前标签，tag_generation 为当前代次
CREATE TABLE IF NOT EXISTS bag (
    bag_id              VARCHAR(64)  NOT NULL COMMENT '行李稳定实物标识，全局唯一，换标不变',
    bag_tag             VARCHAR(64)  NOT NULL COMMENT '当前行李牌号，全局唯一，换标后更新为最新号',
    tag_generation      INT          NOT NULL DEFAULT 1 COMMENT '当前标签代次，初始 1，每次换标加一',
    current_location    VARCHAR(64)  NOT NULL COMMENT '当前所在站点代码',
    next_leg_index      INT          NOT NULL DEFAULT 0 COMMENT '待乘航段在行程中的下标（0 起），等于行程长度表示已完成；短卸不推进',
    status              VARCHAR(16)  NOT NULL DEFAULT 'IN_TRANSIT' COMMENT '行李状态：IN_TRANSIT 在途/SHORT_UNLOADED 短卸待补/RECOVERED 已补到在途/DELIVERED 已交付',
    loaded_leg_id       VARCHAR(64)  NULL COMMENT '当前已装载到的航段，未装载为 NULL',
    short_leg_id        VARCHAR(64)  NULL COMMENT '短卸缺失航段标识，仅 SHORT_UNLOADED 状态非 NULL',
    short_destination   VARCHAR(64)  NULL COMMENT '短卸应到站点代码，仅 SHORT_UNLOADED 状态非 NULL',
    short_registered_at TIMESTAMP WITH TIME ZONE NULL COMMENT '短卸登记时刻（UTC），仅 SHORT_UNLOADED 状态非 NULL',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (bag_id),
    UNIQUE (bag_tag)
);

-- 标签别名：每件行李使用过的全部牌号（含当前号）永久保留，不再分配给另一实物
CREATE TABLE IF NOT EXISTS bag_tag_alias (
    bag_tag        VARCHAR(64) NOT NULL COMMENT '历史或当前行李牌号，全局唯一，永久保留不复用',
    bag_id         VARCHAR(64) NOT NULL COMMENT '该牌号归属的行李稳定实物标识',
    tag_generation INT         NOT NULL COMMENT '该牌号启用时的标签代次',
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '别名登记时间',
    PRIMARY KEY (bag_tag)
);

-- 行李行程明细：按稳定实物标识关联，有序航段列表，相邻航段首尾站衔接；换标不影响既有行程
CREATE TABLE IF NOT EXISTS bag_itinerary (
    bag_id      VARCHAR(64) NOT NULL COMMENT '行李稳定实物标识',
    seq         INT         NOT NULL COMMENT '行程顺序，0 起递增',
    leg_id      VARCHAR(64) NOT NULL COMMENT '航段标识',
    origin      VARCHAR(64) NOT NULL COMMENT '该段始发站（冗余自航段，便于轨迹查询）',
    destination VARCHAR(64) NOT NULL COMMENT '该段到达站',
    PRIMARY KEY (bag_id, seq)
);

-- 装载明细：bag_id 为主键，保证一件行李同一时间只在一个航段的装载清单中；
-- bag_tag 冗余记录装载时刻的当前标签（换标只允许未装舱时发生，故与封舱清单冻结号一致）
CREATE TABLE IF NOT EXISTS load_record (
    bag_id    VARCHAR(64) NOT NULL COMMENT '行李稳定实物标识',
    leg_id    VARCHAR(64) NOT NULL COMMENT '装载到的航段',
    bag_tag   VARCHAR(64) NOT NULL COMMENT '装载时刻的当前行李牌号',
    loaded_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '装载时间',
    PRIMARY KEY (bag_id)
);

-- 行李实际事件流：支撑完整轨迹查询，(bag_id, seq) 唯一保证每件行李事件顺序不重复；
-- bag_tag 记录事件发生时刻的当前标签，历史证据不随换标倒改
CREATE TABLE IF NOT EXISTS bag_event (
    id         BIGINT AUTO_INCREMENT NOT NULL COMMENT '事件自增主键',
    bag_id     VARCHAR(64)  NOT NULL COMMENT '行李稳定实物标识',
    seq        INT          NOT NULL COMMENT '该行李内事件顺序，0 起递增',
    event_type VARCHAR(32)  NOT NULL COMMENT '事件类型：REGISTERED 登记/LOADED 装载/UNLOADED 到达卸下/SHORT_UNLOADED 短卸/RECOVERED 补到/DELIVERED 交付/RETAGGED 换标',
    leg_id     VARCHAR(64)  NULL COMMENT '关联航段标识，与航段无关的事件为 NULL',
    location   VARCHAR(64)  NULL COMMENT '事件发生后行李所在站点代码',
    bag_tag    VARCHAR(64)  NOT NULL COMMENT '事件发生时刻的当前行李牌号（历史证据，不随后续换标倒改）',
    event_time TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '事件发生时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE (bag_id, seq)
);

-- 幂等去重：仅记录成功请求；同 requestId 同参数重放原结果，异参数返回 409
CREATE TABLE IF NOT EXISTS request_log (
    request_id      VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    operation       VARCHAR(32)  NOT NULL COMMENT '操作类型：REGISTER_LEG/REGISTER_BAG/LOAD/SEAL/ARRIVE/ARRIVE_DIFFERENCE/RECOVER/RETAG',
    request_hash    VARCHAR(64)  NOT NULL COMMENT '请求身份（操作+资源+全参数+期望版本，不含 requestId）的 SHA-256 摘要',
    response_status INT          NOT NULL COMMENT '原成功响应的 HTTP 状态码',
    response_body   CLOB         NOT NULL COMMENT '原成功响应体（JSON）',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (request_id)
);
