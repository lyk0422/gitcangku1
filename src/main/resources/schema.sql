-- 联程行李装载交接 schema（H2 MySQL 兼容模式）
-- 普通时间字段为数据库会话时区（默认 JVM 时区 Asia/Shanghai）；
-- TIMESTAMP WITH TIME ZONE 字段统一保存 UTC 时刻，NULL 含义见各列注释。
-- 时间区间一律左闭右开 [start, end)。

-- 航段：leg_id 唯一，version 单调递增，状态 OPEN -> SEALED -> ARRIVED
CREATE TABLE IF NOT EXISTS leg (
    leg_id          VARCHAR(64)  NOT NULL COMMENT '航段唯一标识',
    origin          VARCHAR(64)  NOT NULL COMMENT '始发站代码',
    destination     VARCHAR(64)  NOT NULL COMMENT '到达站代码',
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT '航段状态：OPEN/SEALED/ARRIVED',
    version         INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本，每次状态或装载变更后递增',
    capacity        INT          NULL COMMENT '航段容量上限（件），NULL 表示不限容量',
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
    next_leg_index      INT          NOT NULL DEFAULT 0 COMMENT '待乘航段在行程中的下标（0 起），等于行程长度表示已完成；短卸或认领冻结不推进',
    status              VARCHAR(16)  NOT NULL DEFAULT 'IN_TRANSIT' COMMENT '行李状态：IN_TRANSIT 在途/SHORT_UNLOADED 短卸待补/RECOVERED 已补到在途/CLAIM_HOLD 认领冻结中/DELIVERED 已交付/LOST 已丢失',
    loaded_leg_id       VARCHAR(64)  NULL COMMENT '当前已装载到的航段，未装载为 NULL；认领冻结移出 OPEN 清单后为 NULL',
    short_leg_id        VARCHAR(64)  NULL COMMENT '短卸缺失航段标识，仅 SHORT_UNLOADED 状态非 NULL；冻结期间保留以便解除后恢复',
    short_destination   VARCHAR(64)  NULL COMMENT '短卸应到站点代码，仅 SHORT_UNLOADED 状态非 NULL；冻结期间保留',
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
    event_type VARCHAR(32)  NOT NULL COMMENT '事件类型：REGISTERED 登记/LOADED 装载/UNLOADED 到达卸下/SHORT_UNLOADED 短卸/RECOVERED 补到/CLAIM_HELD 认领冻结/CLAIM_RELEASED 冻结解除/DELIVERED 交付',
    leg_id     VARCHAR(64)  NULL COMMENT '关联航段标识，与航段无关的事件为 NULL',
    location   VARCHAR(64)  NULL COMMENT '事件发生后行李所在站点代码',
    event_time TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '事件发生时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, seq)
);

-- 认领冻结主记录：一件行李同一时间只允许一条生效冻结（ACTIVE/REVIEWED），解除后原样保留为 RELEASED 证据
CREATE TABLE IF NOT EXISTS claim_hold (
    claim_key         VARCHAR(64)  NOT NULL COMMENT '认领冻结业务键，全局唯一，登记后不可更改',
    bag_tag           VARCHAR(64)  NOT NULL COMMENT '被冻结行李牌号',
    prior_status      VARCHAR(16)  NOT NULL COMMENT '冻结前行李的可交接状态：IN_TRANSIT/SHORT_UNLOADED/RECOVERED，解除时恢复为该状态',
    leg_id            VARCHAR(64)  NULL COMMENT '冻结时同事务移出的 OPEN 航段标识；行李不在任何 OPEN 清单为 NULL（NULL 含义稳定）',
    passenger_digest  VARCHAR(128) NOT NULL COMMENT '乘客核验摘要，登记时固化，复核必须逐字一致',
    reason            VARCHAR(256) NOT NULL COMMENT '冻结原因，登记后不可改写',
    held_by           VARCHAR(64)  NOT NULL COMMENT '登记冻结的第一操作人（客服）工号',
    held_by_role      VARCHAR(32)  NOT NULL COMMENT '登记操作人角色：CS_AGENT 客服',
    held_at           TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '冻结生效时刻（UTC），冻结区间左闭起点',
    status            VARCHAR(16)  NOT NULL COMMENT '冻结状态：ACTIVE 生效中/REVIEWED 已复核待二次确认/RELEASED 已解除（证据保留）',
    reviewed_by       VARCHAR(64)  NULL COMMENT '复核的第二操作人工号，必须与 held_by 不同；未复核为 NULL',
    reviewed_by_role  VARCHAR(32)  NULL COMMENT '复核操作人角色：CS_REVIEWER 复核客服；未复核为 NULL',
    reviewed_at       TIMESTAMP WITH TIME ZONE NULL COMMENT '复核通过时刻（UTC）；未复核为 NULL',
    released_by       VARCHAR(64)  NULL COMMENT '二次确认解除的操作人工号（即复核人）；未解除为 NULL',
    released_at       TIMESTAMP WITH TIME ZONE NULL COMMENT '解除生效时刻（UTC），冻结区间右开终点（不含）；未解除为 NULL',
    discrepancy_count INT          NOT NULL DEFAULT 0 COMMENT '误交付等差异登记累计件数，只增不减',
    active_bag_tag    VARCHAR(64)  GENERATED ALWAYS AS (CASE WHEN status IN ('ACTIVE','REVIEWED') THEN bag_tag ELSE NULL END) COMMENT '生效冻结唯一约束辅助列：非生效状态为 NULL',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间（数据库会话时区）',
    PRIMARY KEY (claim_key),
    UNIQUE (active_bag_tag)
);

-- 认领冻结不可变事件链：只追加，不更新不删除，(claim_key, seq) 保证链内顺序稳定
CREATE TABLE IF NOT EXISTS claim_event (
    id            BIGINT AUTO_INCREMENT NOT NULL COMMENT '事件自增主键',
    claim_key     VARCHAR(64)  NOT NULL COMMENT '所属认领冻结业务键',
    bag_tag       VARCHAR(64)  NOT NULL COMMENT '行李牌号（冗余，便于按行李检索证据链）',
    seq           INT          NOT NULL COMMENT '该冻结链内事件顺序，0 起递增，只追加不改写',
    event_type    VARCHAR(32)  NOT NULL COMMENT '事件类型：HELD 冻结/MANIFEST_REMOVED 移出 OPEN 清单/REVIEWED 复核/RELEASED 解除/DELIVERY_DIFFERENCE 误交付差异登记',
    leg_id        VARCHAR(64)  NULL COMMENT '事件固化的航段标识；与航段无关的事件为 NULL（NULL 含义稳定）',
    reason        VARCHAR(256) NULL COMMENT 'HELD 为冻结原因，DELIVERY_DIFFERENCE 为差异说明，其余事件为 NULL',
    operator_id   VARCHAR(64)  NULL COMMENT '事件操作人工号；HELD/MANIFEST_REMOVED 为登记客服，REVIEWED/RELEASED 为复核客服',
    operator_role VARCHAR(32)  NULL COMMENT '事件操作人角色；与操作人无关时为 NULL',
    event_time    TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '事件发生时刻（UTC），精度由库类型固定，排序按 seq',
    PRIMARY KEY (id),
    UNIQUE (claim_key, seq)
);

-- 幂等去重：仅记录成功请求；同 requestId 同参数重放原结果，异参数返回 409
CREATE TABLE IF NOT EXISTS request_log (
    request_id      VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    operation       VARCHAR(32)  NOT NULL COMMENT '操作类型：REGISTER_LEG/REGISTER_BAG/LOAD/SEAL/ARRIVE/ARRIVE_DIFFERENCE/RECOVER/CLAIM_HOLD/CLAIM_REVIEW/CLAIM_RELEASE/DELIVERY_DIFFERENCE',
    request_hash    VARCHAR(64)  NOT NULL COMMENT '请求参数（不含 requestId）的 SHA-256 摘要',
    response_status INT          NOT NULL COMMENT '原成功响应的 HTTP 状态码',
    response_body   CLOB         NOT NULL COMMENT '原成功响应体（JSON）',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (request_id)
);
