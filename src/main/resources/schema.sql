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
    status              VARCHAR(16)  NOT NULL DEFAULT 'IN_TRANSIT' COMMENT '行李状态：IN_TRANSIT 在途/SHORT_UNLOADED 短卸待补/RECOVERED 已补到在途/READY_FOR_CLAIM 组成员末段到达待认领/DELIVERED 已交付',
    loaded_leg_id       VARCHAR(64)  NULL COMMENT '当前已装载到的航段，未装载为 NULL',
    group_key           VARCHAR(64)  NULL COMMENT '整组认领组标识；非组成员为 NULL，建组固定后不可更改',
    short_leg_id        VARCHAR(64)  NULL COMMENT '短卸缺失航段标识，仅 SHORT_UNLOADED 状态非 NULL',
    short_destination   VARCHAR(64)  NULL COMMENT '短卸应到站点代码，仅 SHORT_UNLOADED 状态非 NULL',
    short_registered_at TIMESTAMP WITH TIME ZONE NULL COMMENT '短卸登记时刻（UTC），仅 SHORT_UNLOADED 状态非 NULL',
    version         INT          NOT NULL DEFAULT 1 COMMENT '行李轨迹版本，每次装载/到达/短卸/补到/认领后递增，认领时做乐观并发校验',
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

-- 整组认领组：任一成员装载前固定 2~5 件同行程、同当前站行李，成员集合之后不可更改；
-- 末段全部到达后由显式认领一次交付，状态 FORMED -> CLAIMED
CREATE TABLE IF NOT EXISTS bag_group (
    group_key       VARCHAR(64) NOT NULL COMMENT '整组认领组唯一标识',
    status          VARCHAR(16) NOT NULL DEFAULT 'FORMED' COMMENT '组状态：FORMED 待认领/CLAIMED 已整组交付',
    final_leg_id    VARCHAR(64) NOT NULL COMMENT '组内成员共同行程的末段航段标识',
    final_station   VARCHAR(64) NOT NULL COMMENT '末段应到站点代码',
    created_by      VARCHAR(64) NOT NULL COMMENT '建组操作者标识，非空',
    claimed_by      VARCHAR(64) NULL COMMENT '认领操作者标识，仅 CLAIMED 状态非 NULL',
    claimed_at      TIMESTAMP WITH TIME ZONE NULL COMMENT '认领成功时刻（UTC），仅 CLAIMED 状态非 NULL',
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (group_key)
);

-- 组成员：建组时一次性写入，历史成员集合不可倒改；member_seq 为共同行程顺序（0 起）
CREATE TABLE IF NOT EXISTS bag_group_member (
    group_key  VARCHAR(64) NOT NULL COMMENT '组标识',
    bag_tag    VARCHAR(64) NOT NULL COMMENT '成员行李牌号',
    member_seq INT         NOT NULL COMMENT '成员在提交中的顺序（0 起），仅用于稳定排序展示',
    PRIMARY KEY (group_key, bag_tag),
    UNIQUE (bag_tag)
);

-- 行李实际事件流：支撑完整轨迹查询，(bag_tag, seq) 唯一保证每件行李事件顺序不重复
CREATE TABLE IF NOT EXISTS bag_event (
    id         BIGINT AUTO_INCREMENT NOT NULL COMMENT '事件自增主键',
    bag_tag    VARCHAR(64)  NOT NULL COMMENT '行李牌号',
    seq        INT          NOT NULL COMMENT '该行李内事件顺序，0 起递增',
    event_type VARCHAR(32)  NOT NULL COMMENT '事件类型：REGISTERED 登记/LOADED 装载/UNLOADED 到达卸下/SHORT_UNLOADED 短卸/RECOVERED 补到/READY_FOR_CLAIM 组成员待认领/CLAIMED 整组认领交付/DELIVERED 单件自动交付',
    leg_id     VARCHAR(64)  NULL COMMENT '关联航段标识，与航段无关的事件为 NULL',
    location   VARCHAR(64)  NULL COMMENT '事件发生后行李所在站点代码',
    event_time TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '事件发生时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, seq)
);

-- 幂等去重：仅记录成功请求；同 requestId 同参数重放原结果，异参数返回 409
CREATE TABLE IF NOT EXISTS request_log (
    request_id      VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    operation       VARCHAR(32)  NOT NULL COMMENT '操作类型：REGISTER_LEG/REGISTER_BAG/LOAD/SEAL/ARRIVE/ARRIVE_DIFFERENCE/RECOVER/FORM_GROUP/CLAIM_GROUP',
    resource        VARCHAR(128) NOT NULL COMMENT '结构化身份-资源标识（如航段/行李/组标识）',
    operator        VARCHAR(64)  NOT NULL COMMENT '操作者标识，非空',
    request_hash    VARCHAR(64)  NOT NULL COMMENT '请求参数（不含 requestId/operator，含期望版本，十进制按数值、文本原样）的 SHA-256 摘要',
    response_status INT          NOT NULL COMMENT '原成功响应的 HTTP 状态码',
    response_body   CLOB         NOT NULL COMMENT '原成功响应体（JSON）',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (request_id)
);
