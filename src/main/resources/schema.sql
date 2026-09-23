-- 联程行李装载交接 schema（H2 MySQL 兼容模式）
-- 普通时间字段为数据库会话时区（默认 JVM 时区 Asia/Shanghai）；
-- TIMESTAMP WITH TIME ZONE 字段统一保存 UTC 时刻，NULL 含义见各列注释。

-- 航段：leg_id 唯一，version 单调递增，状态 OPEN -> SEALED -> ARRIVED
CREATE TABLE IF NOT EXISTS leg (
    leg_id          VARCHAR(64)  NOT NULL COMMENT '航段唯一标识',
    origin          VARCHAR(64)  NOT NULL COMMENT '始发站代码',
    destination     VARCHAR(64)  NOT NULL COMMENT '到达站代码',
    departure_at    TIMESTAMP WITH TIME ZONE NULL COMMENT '计划出发时刻（UTC），未登记时刻为 NULL；错装恢复路径段必须非空且严格递增',
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
    current_location    VARCHAR(64)  NOT NULL COMMENT '当前所在站点代码；错装登记后为实际到达站（扫描站点）',
    next_leg_index      INT          NOT NULL DEFAULT 0 COMMENT '当前代次待乘航段下标（0 起），等于当前代次行程长度表示已完成；短卸不推进，改派后重置为 0',
    status              VARCHAR(16)  NOT NULL DEFAULT 'IN_TRANSIT' COMMENT '行李状态：IN_TRANSIT 在途/SHORT_UNLOADED 短卸待补/RECOVERED 已补到在途/MISLOADED 错装待改派/DELIVERED 已交付',
    version             INT          NOT NULL DEFAULT 1 COMMENT '行李乐观锁版本，每次装载/到达/短卸/补到/错装登记/改派后递增',
    path_generation     INT          NOT NULL DEFAULT 0 COMMENT '当前有效路径代次：0 为原始行程，每次错装改派原子成功后递增',
    open_incident_id    VARCHAR(64)  NULL COMMENT '未结错装事件标识，无未结错装为 NULL；唯一索引保证一件行李同一时间最多挂一个未结事件',
    loaded_leg_id       VARCHAR(64)  NULL COMMENT '当前已装载到的航段，未装载为 NULL',
    short_leg_id        VARCHAR(64)  NULL COMMENT '短卸缺失航段标识，仅 SHORT_UNLOADED 状态非 NULL',
    short_destination   VARCHAR(64)  NULL COMMENT '短卸应到站点代码，仅 SHORT_UNLOADED 状态非 NULL',
    short_registered_at TIMESTAMP WITH TIME ZONE NULL COMMENT '短卸登记时刻（UTC），仅 SHORT_UNLOADED 状态非 NULL',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (bag_tag)
);
-- 未结错装唯一性由 bag 行级锁（SELECT ... FOR UPDATE）与 open_incident_id 非空检查串行保证：
-- 并发登记同一件行李时后提交者在持锁复查阶段看到 open_incident_id 非空即整单 409。

-- 行李行程明细：有序航段列表，相邻航段首尾站衔接；generation 标识路径代次，
-- 改派后原代次行保留不改（血缘），新代次行另插，装载只认 bag.path_generation 对应行
CREATE TABLE IF NOT EXISTS bag_itinerary (
    bag_tag     VARCHAR(64) NOT NULL COMMENT '行李牌号',
    generation  INT         NOT NULL DEFAULT 0 COMMENT '路径代次：0 为原始行程，错装改派后为对应新代次',
    seq         INT         NOT NULL COMMENT '该代次内行程顺序，0 起递增',
    leg_id      VARCHAR(64) NOT NULL COMMENT '航段标识',
    origin      VARCHAR(64) NOT NULL COMMENT '该段始发站（冗余自航段，便于轨迹查询）',
    destination VARCHAR(64) NOT NULL COMMENT '该段到达站',
    PRIMARY KEY (bag_tag, generation, seq)
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
    event_type VARCHAR(32)  NOT NULL COMMENT '事件类型：REGISTERED 登记/LOADED 装载/UNLOADED 到达卸下/SHORT_UNLOADED 短卸/RECOVERED 补到/MISLOADED 错装登记/REROUTED 错装改派/DELIVERED 交付',
    leg_id     VARCHAR(64)  NULL COMMENT '关联航段标识，与航段无关的事件为 NULL',
    location   VARCHAR(64)  NULL COMMENT '事件发生后行李所在站点代码',
    event_time TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '事件发生时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, seq)
);

-- 错装批次：到达站以 incidentKey 登记 2~50 件在同一实际航段到达但该航段不属于各自行程的行李
CREATE TABLE IF NOT EXISTS misload_incident (
    incident_id   VARCHAR(64)  NOT NULL COMMENT '错装事件唯一标识（incidentKey）',
    actual_leg_id VARCHAR(64)  NOT NULL COMMENT '行李实际到达的航段标识（不属于各行李行程）',
    scan_station  VARCHAR(64)  NOT NULL COMMENT '登记扫描站点代码，必须等于实际航段到达站',
    status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT '事件状态：OPEN 未结（待改派）/CONFIRMED 已原子改派关闭',
    generation    INT          NOT NULL DEFAULT 0 COMMENT '确认后批次内各行李新路径代次的最大值（各件代次见 misload_item.new_generation）；OPEN 时为 0',
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '错装登记时刻（UTC）',
    confirmed_at  TIMESTAMP WITH TIME ZONE NULL COMMENT '改派确认时刻（UTC），仅 CONFIRMED 状态非 NULL',
    PRIMARY KEY (incident_id)
);

-- 错装批次逐件登记：冻结登记时的行李版本与扫描站点
CREATE TABLE IF NOT EXISTS misload_item (
    incident_id    VARCHAR(64) NOT NULL COMMENT '错装事件标识',
    bag_tag        VARCHAR(64) NOT NULL COMMENT '行李牌号',
    frozen_version INT         NOT NULL COMMENT '冻结行李版本：登记成功后的版本，预览与确认均要求行李仍处于该版本',
    scan_station   VARCHAR(64) NOT NULL COMMENT '该件提交的扫描站点代码，须等于实际航段到达站',
    new_generation INT         NULL COMMENT '改派确认后该件进入的新路径代次；OPEN 未确认为 NULL',
    PRIMARY KEY (incident_id, bag_tag)
);

-- 错装恢复提案：预览时逐件冻结的 1~5 段恢复路径，确认据此重新校验
CREATE TABLE IF NOT EXISTS misload_proposal (
    incident_id  VARCHAR(64) NOT NULL COMMENT '错装事件标识',
    bag_tag      VARCHAR(64) NOT NULL COMMENT '行李牌号',
    seq          INT         NOT NULL COMMENT '恢复路径顺序，0 起递增',
    leg_id       VARCHAR(64) NOT NULL COMMENT '恢复段引用的航段标识',
    origin       VARCHAR(64) NOT NULL COMMENT '预览冻结时该段始发站',
    destination  VARCHAR(64) NOT NULL COMMENT '预览冻结时该段到达站',
    departure_at TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '预览冻结时该段计划出发时刻（UTC）',
    PRIMARY KEY (incident_id, bag_tag, seq)
);

-- 路径血缘不可变快照：每次错装改派确认保存原剩余路径（ORIGINAL）与新恢复路径（NEW）
CREATE TABLE IF NOT EXISTS bag_path_snapshot (
    id           BIGINT AUTO_INCREMENT NOT NULL COMMENT '快照自增主键',
    incident_id  VARCHAR(64) NOT NULL COMMENT '错装事件标识',
    bag_tag      VARCHAR(64) NOT NULL COMMENT '行李牌号',
    snapshot_kind VARCHAR(16) NOT NULL COMMENT '快照类型：ORIGINAL 被替换的原剩余路径/NEW 改派后新路径',
    generation   INT         NOT NULL COMMENT 'ORIGINAL 为被替换路径代次，NEW 为改派后新代次',
    seq          INT         NOT NULL COMMENT '该快照内路径顺序，0 起递增',
    leg_id       VARCHAR(64) NOT NULL COMMENT '航段标识',
    origin       VARCHAR(64) NOT NULL COMMENT '该段始发站',
    destination  VARCHAR(64) NOT NULL COMMENT '该段到达站',
    departure_at TIMESTAMP WITH TIME ZONE NULL COMMENT '该段计划出发时刻（UTC），原始行程未登记时刻时为 NULL',
    PRIMARY KEY (id),
    UNIQUE (incident_id, bag_tag, snapshot_kind, seq)
);

-- 幂等去重：仅记录成功请求；同 requestId 同参数重放原结果，异参数返回 409
CREATE TABLE IF NOT EXISTS request_log (
    request_id      VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    operation       VARCHAR(32)  NOT NULL COMMENT '操作类型：REGISTER_LEG/REGISTER_BAG/LOAD/SEAL/ARRIVE/ARRIVE_DIFFERENCE/RECOVER/REGISTER_MISLOAD/PREVIEW_MISLOAD/CONFIRM_MISLOAD',
    request_hash    VARCHAR(64)  NOT NULL COMMENT '请求参数（不含 requestId）的 SHA-256 摘要',
    response_status INT          NOT NULL COMMENT '原成功响应的 HTTP 状态码',
    response_body   CLOB         NOT NULL COMMENT '原成功响应体（JSON）',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (request_id)
);
