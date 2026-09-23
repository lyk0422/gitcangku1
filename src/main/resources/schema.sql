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

-- 容器：container_id 全局唯一，seal_no 全局唯一（未封舱为 NULL），状态 OPEN -> SEALED -> CLOSED_REPACKED
CREATE TABLE IF NOT EXISTS container (
    container_id   VARCHAR(64) NOT NULL COMMENT '容器编号，全局唯一',
    leg_id         VARCHAR(64) NOT NULL COMMENT '所属航段标识',
    handover_point VARCHAR(64) NOT NULL COMMENT '交接点代码',
    status         VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '容器状态：OPEN 可装箱/SEALED 已封签/CLOSED_REPACKED 重封后关闭',
    version        INT         NOT NULL DEFAULT 1 COMMENT '乐观锁版本，装箱/封签/重封关闭后递增',
    seal_no        VARCHAR(64) NULL COMMENT '封签号，全局唯一，SEALED 起非 NULL，关闭后保留作历史证据',
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (container_id),
    UNIQUE (seal_no)
);

-- 容器当前清单：bag_tag 为主键，保证一件行李任一时刻只属于一个有效容器
CREATE TABLE IF NOT EXISTS container_bag (
    bag_tag      VARCHAR(64) NOT NULL COMMENT '行李牌号',
    container_id VARCHAR(64) NOT NULL COMMENT '当前所属容器编号',
    assigned_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '进入当前容器时间',
    PRIMARY KEY (bag_tag)
);

-- 行李容器链：逐项保留行李经过的全部容器，(bag_tag, seq) 唯一保证链顺序不重复
CREATE TABLE IF NOT EXISTS bag_container_history (
    id           BIGINT AUTO_INCREMENT NOT NULL COMMENT '自增主键',
    bag_tag      VARCHAR(64) NOT NULL COMMENT '行李牌号',
    seq          INT         NOT NULL COMMENT '容器链顺序，0 起递增',
    container_id VARCHAR(64) NOT NULL COMMENT '经过的容器编号',
    entered_at   TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '进入该容器时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, seq)
);

-- 容器重封单：repack_key 唯一，操作人与复核人双人确认后在一个事务内原子激活
CREATE TABLE IF NOT EXISTS reseal_order (
    repack_key         VARCHAR(64) NOT NULL COMMENT '重封单唯一键',
    operator_id        VARCHAR(64) NOT NULL COMMENT '操作人标识',
    reviewer_id        VARCHAR(64) NOT NULL COMMENT '复核人标识，须与操作人不同',
    leg_id             VARCHAR(64) NOT NULL COMMENT '源容器共同所属航段',
    handover_point     VARCHAR(64) NOT NULL COMMENT '源容器共同交接点代码',
    status             VARCHAR(16) NOT NULL DEFAULT 'PENDING_CONFIRM' COMMENT '单状态：PENDING_CONFIRM 待双人确认/ACTIVATED 已激活',
    operator_confirmed BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '操作人是否已确认',
    reviewer_confirmed BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '复核人是否已确认',
    sources_json       CLOB        NOT NULL COMMENT '请求的源容器清单（含 expectedVersion 与旧封签，按容器号排序，JSON）',
    targets_json       CLOB        NOT NULL COMMENT '请求的目标精确分区（含新容器号、新封签与袋号集合，按容器号排序，JSON）',
    before_snapshot    CLOB        NULL COMMENT '激活前源容器实际清单快照（JSON），未激活为 NULL',
    after_snapshot     CLOB        NULL COMMENT '激活后目标容器清单快照（JSON），未激活为 NULL',
    created_at         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    activated_at       TIMESTAMP WITH TIME ZONE NULL COMMENT '激活时刻（UTC），未激活为 NULL',
    PRIMARY KEY (repack_key)
);

-- 幂等去重：仅记录成功请求；同 requestId 同参数重放原结果，异参数返回 409
CREATE TABLE IF NOT EXISTS request_log (
    request_id      VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    operation       VARCHAR(32)  NOT NULL COMMENT '操作类型：REGISTER_LEG/REGISTER_BAG/LOAD/SEAL/ARRIVE/ARRIVE_DIFFERENCE/RECOVER/CONTAINER_CREATE/CONTAINER_LOAD/CONTAINER_SEAL/RESEAL_CREATE/RESEAL_CONFIRM',
    request_hash    VARCHAR(64)  NOT NULL COMMENT '请求参数（不含 requestId）的 SHA-256 摘要',
    response_status INT          NOT NULL COMMENT '原成功响应的 HTTP 状态码',
    response_body   CLOB         NOT NULL COMMENT '原成功响应体（JSON）',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (request_id)
);
