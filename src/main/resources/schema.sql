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
    capacity        INT          NULL COMMENT '航段容量上限（封舱前清单允许的最大行李件数）；NULL 表示不限容量，装载时按当前清单实时校验',
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
    next_leg_index      INT          NOT NULL DEFAULT 0 COMMENT '待乘航段在行程中的下标（0 起），等于行程长度表示已完成；短卸/认领冻结不推进',
    status              VARCHAR(16)  NOT NULL DEFAULT 'IN_TRANSIT' COMMENT '行李状态：IN_TRANSIT 在途/SHORT_UNLOADED 短卸待补/RECOVERED 已补到在途/CLAIM_HOLD 认领冻结中/DELIVERED 已交付/LOST 已丢失',
    loaded_leg_id       VARCHAR(64)  NULL COMMENT '当前已装载到的航段，未装载为 NULL；认领冻结移出 OPEN 清单后置 NULL，解除冻结不自动恢复',
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
    event_type VARCHAR(32)  NOT NULL COMMENT '事件类型：REGISTERED 登记/LOADED 装载/UNLOADED 到达卸下/SHORT_UNLOADED 短卸/RECOVERED 补到/CLAIM_HELD 认领冻结/CLAIM_RELEASED 冻结解除/DELIVERED 交付',
    leg_id     VARCHAR(64)  NULL COMMENT '关联航段标识，与航段无关的事件为 NULL',
    location   VARCHAR(64)  NULL COMMENT '事件发生后行李所在站点代码',
    event_time TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '事件发生时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, seq)
);

-- 认领冻结当前状态：claim_key 唯一；一件行李同时只允许一个未解除（ACTIVE/REVIEWED）冻结，
-- active_bag_tag 生成列 + 唯一索引在数据库层兜底（RELEASED 行为 NULL，H2 唯一索引允许多个 NULL）。
CREATE TABLE IF NOT EXISTS claim_hold (
    claim_key                     VARCHAR(96)  NOT NULL COMMENT '认领冻结申请唯一标识，由授权客服登记',
    bag_tag                       VARCHAR(64)  NOT NULL COMMENT '被冻结行李牌号',
    passenger_verification_summary VARCHAR(512) NOT NULL COMMENT '乘客核验摘要：登记时固化，复核/二次确认必须逐字一致',
    reason                        VARCHAR(512) NOT NULL COMMENT '冻结原因，登记后不可修改',
    hold_agent_id                 VARCHAR(64)  NOT NULL COMMENT '登记冻结的客服工号（第一位操作人）',
    hold_agent_role               VARCHAR(32)  NOT NULL COMMENT '登记冻结的客服角色；复核人角色必须与之不同',
    status                        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '冻结状态：ACTIVE 生效中/REVIEWED 复核通过待二次确认/RELEASED 已解除',
    status_before_hold            VARCHAR(16)  NOT NULL COMMENT '冻结前行李状态快照（如 IN_TRANSIT/SHORT_UNLOADED/RECOVERED），解除时原样恢复',
    frozen_leg_id                 VARCHAR(64)  NULL COMMENT '冻结时移出的 OPEN 航段标识；行李当时不在任何 OPEN 清单中为 NULL',
    reviewer_id                   VARCHAR(64)  NULL COMMENT '复核客服工号（第二位操作人），仅 REVIEWED/RELEASED 状态非 NULL',
    reviewer_role                 VARCHAR(32)  NULL COMMENT '复核客服角色，仅 REVIEWED/RELEASED 状态非 NULL，且与 hold_agent_role 不同',
    hold_at                       TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '冻结生效时刻（UTC，区间起点，左闭）',
    reviewed_at                   TIMESTAMP WITH TIME ZONE NULL COMMENT '复核通过时刻（UTC），未复核为 NULL',
    released_at                   TIMESTAMP WITH TIME ZONE NULL COMMENT '解除生效时刻（UTC，区间终点，右开；时长 = released_at - hold_at），未解除为 NULL',
    created_at                    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    active_bag_tag                VARCHAR(64)  GENERATED ALWAYS AS (CASE WHEN status <> 'RELEASED' THEN bag_tag ELSE NULL END) COMMENT '未解除冻结对应的行李牌号生成列，RELEASED 为 NULL',
    PRIMARY KEY (claim_key),
    UNIQUE (active_bag_tag)
);

-- 认领冻结不可变证据链：只追加，不更新不删除；(claim_key, seq) 保证同一冻结内顺序稳定。
-- 每行按事件进度固化航段、原因、两位操作人及各自时刻，尚未发生的操作人/时刻为 NULL（NULL 含义固定）。
CREATE TABLE IF NOT EXISTS claim_chain (
    id               BIGINT AUTO_INCREMENT NOT NULL COMMENT '证据链自增主键',
    claim_key        VARCHAR(96)  NOT NULL COMMENT '所属认领冻结申请标识',
    seq              INT          NOT NULL COMMENT '该冻结内事件顺序，0 起递增：HOLD=0、LIST_REMOVAL=1、REVIEW 次之、RELEASE 最后',
    event_type       VARCHAR(32)  NOT NULL COMMENT '证据事件类型：HOLD 冻结/LIST_REMOVAL 移出 OPEN 清单/REVIEW 复核/RELEASE 解除',
    bag_tag          VARCHAR(64)  NOT NULL COMMENT '被冻结行李牌号',
    leg_id           VARCHAR(64)  NULL COMMENT '固化航段：HOLD/LIST_REMOVAL 为移出的 OPEN 航段（不在清单中则为 NULL）；REVIEW/RELEASE 沿用冻结航段',
    reason           VARCHAR(512) NOT NULL COMMENT '冻结原因（逐字固化自登记内容）',
    hold_agent_id    VARCHAR(64)  NOT NULL COMMENT '登记冻结的客服工号（第一位操作人）',
    hold_agent_role  VARCHAR(32)  NOT NULL COMMENT '登记冻结的客服角色',
    review_agent_id  VARCHAR(64)  NULL COMMENT '复核客服工号（第二位操作人），HOLD/LIST_REMOVAL 时为 NULL',
    review_agent_role VARCHAR(32) NULL COMMENT '复核客服角色，HOLD/LIST_REMOVAL 时为 NULL；存在时与 hold_agent_role 不同',
    hold_at          TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '冻结生效时刻（UTC），全部事件行一致固化',
    reviewed_at      TIMESTAMP WITH TIME ZONE NULL COMMENT '复核通过时刻（UTC），HOLD/LIST_REMOVAL 为 NULL',
    released_at      TIMESTAMP WITH TIME ZONE NULL COMMENT '解除生效时刻（UTC），仅 RELEASE 行非 NULL',
    event_time       TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '本条证据写入时刻（UTC，按 seq 单调不减，纳秒精度随数据库）',
    PRIMARY KEY (id),
    UNIQUE (claim_key, seq)
);

-- 误交付差异：冻结解除后行李被错误交付时只追加登记，不删除认领记录、不改写证据链
CREATE TABLE IF NOT EXISTS delivery_difference (
    difference_id        BIGINT AUTO_INCREMENT NOT NULL COMMENT '差异登记自增主键',
    claim_key            VARCHAR(96)  NOT NULL COMMENT '关联认领冻结申请标识（记录保留，不随差异删除）',
    bag_tag              VARCHAR(64)  NOT NULL COMMENT '被误交付的行李牌号',
    delivered_to_station VARCHAR(64)  NOT NULL COMMENT '实际交付站点代码',
    expected_station     VARCHAR(64)  NOT NULL COMMENT '应交付站点代码',
    registered_by        VARCHAR(64)  NOT NULL COMMENT '差异登记人工号',
    note                 VARCHAR(512) NOT NULL COMMENT '差异说明',
    registered_at        TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '差异登记时刻（UTC）',
    PRIMARY KEY (difference_id)
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
