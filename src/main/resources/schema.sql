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
    status              VARCHAR(16)  NOT NULL DEFAULT 'IN_TRANSIT' COMMENT '行李状态：IN_TRANSIT 在途（含错装改派确认后待乘新路径）/SHORT_UNLOADED 短卸待补/RECOVERED 已补到在途/MISLOADED 已登记未结错装、冻结装载与补到/DELIVERED 已交付',
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
    event_type VARCHAR(32)  NOT NULL COMMENT '事件类型：REGISTERED 登记/LOADED 装载/UNLOADED 到达卸下/SHORT_UNLOADED 短卸/RECOVERED 补到/MISLOADED 错装登记/REROUTED 错装改派/DELIVERED 交付',
    leg_id     VARCHAR(64)  NULL COMMENT '关联航段标识，与航段无关的事件为 NULL',
    location   VARCHAR(64)  NULL COMMENT '事件发生后行李所在站点代码',
    event_time TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '事件发生时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, seq)
);

-- 错装追回与剩余路径原子改派（H2 MySQL 兼容模式）
-- 已存在表补充列：航段计划出发时间、行李乐观锁版本与路径代次
ALTER TABLE leg ADD COLUMN IF NOT EXISTS departure_time TIMESTAMP WITH TIME ZONE NULL
    COMMENT '计划出发时间（UTC），恢复路径出发时间严格递增校验使用，未登记为 NULL';
ALTER TABLE bag ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1
    COMMENT '行李乐观锁版本，每次装载/到达/短卸/补到/错装改派后递增';
ALTER TABLE bag ADD COLUMN IF NOT EXISTS path_generation INT NOT NULL DEFAULT 1
    COMMENT '行李路径代次：初始行程为 1，每次错装原子改派关闭事件后递增';

-- 错装事件批次：incident_key 唯一，OPEN 未结 -> CLOSED 已原子改派关闭
CREATE TABLE IF NOT EXISTS misload_incident (
    incident_key    VARCHAR(64)  NOT NULL COMMENT '错装事件唯一标识',
    actual_leg_id   VARCHAR(64)  NOT NULL COMMENT '行李实际到达、但不属于各自行程的实际航段标识',
    arrival_station VARCHAR(64)  NOT NULL COMMENT '实际航段到达站代码，逐件扫描站必须与此一致',
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT '事件状态：OPEN 未结（含已预览未确认）/CLOSED 已原子改派关闭',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '登记时刻（UTC）',
    closed_at       TIMESTAMP WITH TIME ZONE NULL COMMENT '原子改派关闭时刻（UTC），未关闭为 NULL',
    PRIMARY KEY (incident_key)
);

-- 错装事件逐件登记：冻结登记时提交的行李版本与扫描站
CREATE TABLE IF NOT EXISTS misload_item (
    incident_key VARCHAR(64) NOT NULL COMMENT '所属错装事件标识',
    bag_tag      VARCHAR(64) NOT NULL COMMENT '行李牌号',
    bag_version  INT         NOT NULL COMMENT '登记时客户端提交并冻结的行李当前版本',
    scan_station VARCHAR(64) NOT NULL COMMENT '到达站扫描站点代码（须等于实际航段到达站）',
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '逐件登记时刻（UTC）',
    PRIMARY KEY (incident_key, bag_tag)
);

-- 预览冻结：每件行李的行李版本、原剩余路径与恢复路径；同一事件再次预览整组替换
CREATE TABLE IF NOT EXISTS misload_reroute_plan (
    incident_key       VARCHAR(64)  NOT NULL COMMENT '所属错装事件标识',
    bag_tag            VARCHAR(64)  NOT NULL COMMENT '行李牌号',
    bag_version        INT          NOT NULL COMMENT '预览冻结的行李版本，确认时重新校验',
    current_station    VARCHAR(64)  NOT NULL COMMENT '预览时行李当前站（恢复路径首段起点须精确匹配）',
    final_destination  VARCHAR(64)  NOT NULL COMMENT '原最终目的地（恢复路径末段终点须精确匹配）',
    original_remaining CLOB         NOT NULL COMMENT '冻结的原剩余路径只读快照（有序 legId JSON 数组，不含已走完历史）',
    recovery_path      CLOB         NOT NULL COMMENT '调度员提交的恢复路径（有序 legId JSON 数组，顺序有意义）',
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '最近一次预览冻结时刻（UTC）',
    PRIMARY KEY (incident_key, bag_tag)
);

-- 路径血缘不可变快照：确认改派时同时落原路径与新路径，只追加不修改
CREATE TABLE IF NOT EXISTS path_snapshot (
    id           BIGINT AUTO_INCREMENT NOT NULL COMMENT '快照自增主键',
    incident_key VARCHAR(64) NOT NULL COMMENT '触发快照的错装事件标识',
    bag_tag      VARCHAR(64) NOT NULL COMMENT '行李牌号',
    generation   INT         NOT NULL COMMENT '快照对应路径代次：ORIGINAL 为改派前代次，NEW 为改派后代次',
    path_kind    VARCHAR(8) NOT NULL COMMENT '快照类型：ORIGINAL 原剩余路径/NEW 改派后恢复路径',
    seq          INT         NOT NULL COMMENT '该快照内路径顺序，0 起递增',
    leg_id       VARCHAR(64) NOT NULL COMMENT '航段标识',
    origin       VARCHAR(64) NOT NULL COMMENT '该段始发站',
    destination  VARCHAR(64) NOT NULL COMMENT '该段到达站',
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '快照生成时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE (incident_key, bag_tag, path_kind, seq)
);

-- 幂等去重：仅记录成功请求；同 requestId 同参数重放原结果，异参数返回 409
CREATE TABLE IF NOT EXISTS request_log (
    request_id      VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    operation       VARCHAR(32)  NOT NULL COMMENT '操作类型：REGISTER_LEG/REGISTER_BAG/LOAD/SEAL/ARRIVE/ARRIVE_DIFFERENCE/RECOVER/REGISTER_MISLOAD/PREVIEW_REROUTE/CONFIRM_REROUTE',
    request_hash    VARCHAR(64)  NOT NULL COMMENT '请求参数（不含 requestId）的 SHA-256 摘要',
    response_status INT          NOT NULL COMMENT '原成功响应的 HTTP 状态码',
    response_body   CLOB         NOT NULL COMMENT '原成功响应体（JSON）',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (request_id)
);
