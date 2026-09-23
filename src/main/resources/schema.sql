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
    container_no        VARCHAR(64)  NULL COMMENT '当前所属有效容器编号（SEALED 容器），未装入容器或离容器后为 NULL；任何时刻一件行李最多属于一个有效容器',
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
    operation       VARCHAR(32)  NOT NULL COMMENT '操作类型：REGISTER_LEG/REGISTER_BAG/LOAD/SEAL/ARRIVE/ARRIVE_DIFFERENCE/RECOVER/CREATE_REPACK/ACTIVATE_REPACK',
    request_hash    VARCHAR(64)  NOT NULL COMMENT '请求参数（不含 requestId）的 SHA-256 摘要',
    response_status INT          NOT NULL COMMENT '原成功响应的 HTTP 状态码',
    response_body   CLOB         NOT NULL COMMENT '原成功响应体（JSON）',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (request_id)
);

-- 行李容器：编号全局唯一，封签号全局唯一；同一航段同一交接点内成组
-- 状态 SEALED 已封舱（有效容器，行李当前绑定）-> CLOSED_REPACKED 经重封关闭；
-- UNLOADED 卸载扫描后关闭。version 乐观锁，每次封签/清单变更后递增。
CREATE TABLE IF NOT EXISTS baggage_container (
    container_no    VARCHAR(64)  NOT NULL COMMENT '容器编号，全局唯一',
    leg_id          VARCHAR(64)  NOT NULL COMMENT '所属航段标识',
    handover_point  VARCHAR(64)  NOT NULL COMMENT '交接点（站点）代码，源容器与目标容器必须一致',
    seal_no         VARCHAR(64)  NOT NULL COMMENT '当前封签号，全局唯一',
    status          VARCHAR(32)  NOT NULL DEFAULT 'SEALED' COMMENT '容器状态：SEALED 已封舱有效/CLOSED_REPACKED 重封关闭/UNLOADED 卸载关闭',
    version         INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本，重封提交时递增',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (container_no),
    UNIQUE (seal_no)
);

-- 行李容器归属链：每件行李每进入一个容器追加一行，重封改绑时保留原容器链，不做物理删除
-- (bag_tag, container_no) 唯一防止重复入链；链按 seq 递增，最新一行即当前归属
CREATE TABLE IF NOT EXISTS bag_container_chain (
    id           BIGINT AUTO_INCREMENT NOT NULL COMMENT '自增主键',
    bag_tag      VARCHAR(64) NOT NULL COMMENT '行李牌号',
    seq          INT         NOT NULL COMMENT '该行李容器链顺序，0 起递增',
    container_no VARCHAR(64) NOT NULL COMMENT '进入的容器编号',
    leg_id       VARCHAR(64) NOT NULL COMMENT '进入容器时所属航段',
    entered_at   TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '入容器时刻（UTC）',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, seq),
    UNIQUE (bag_tag, container_no)
);

-- 容器重封单：PREVIEW 预览（创建时只读差异快照）-> ACTIVE 双人确认激活；失败不产生 ACTIVE 单
-- repack_key 全局唯一；源容器须同航段、同交接点、状态 SEALED
CREATE TABLE IF NOT EXISTS repack_order (
    repack_key           VARCHAR(64)  NOT NULL COMMENT '重封单业务键，全局唯一',
    leg_id               VARCHAR(64)  NOT NULL COMMENT '源容器共同所属航段',
    handover_point       VARCHAR(64)  NOT NULL COMMENT '源容器共同交接点代码',
    status               VARCHAR(16)  NOT NULL DEFAULT 'PREVIEW' COMMENT '重封单状态：PREVIEW 仅预览/ACTIVE 已激活完成',
    operator_id          VARCHAR(64)  NULL COMMENT '激活操作人员工号，PREVIEW 为 NULL',
    reviewer_id          VARCHAR(64)  NULL COMMENT '激活复核人员工号（必须与操作人不同），PREVIEW 为 NULL',
    source_containers    CLOB         NOT NULL COMMENT '源容器编号只读有序快照（JSON 数组，1~10 个）',
    source_versions      CLOB         NOT NULL COMMENT '各源容器提交时 expectedVersion 只读快照（JSON 整数数组，与源容器顺序一致）',
    old_seals            CLOB         NOT NULL COMMENT '各源容器旧封签号只读快照（JSON 数组，与源容器顺序一致）',
    partition_targets    CLOB         NOT NULL COMMENT '目标容器编号有序快照（JSON 数组，1~10 个）',
    partition_seals      CLOB         NOT NULL COMMENT '各目标容器新封签号快照（JSON 数组，与目标容器顺序一致）',
    partition_bag_tags   CLOB         NOT NULL COMMENT '精确分区快照（JSON 数组的数组，外层与目标容器顺序一致，内层为完整 bagTag 集合，排序存储）',
    before_snapshot      CLOB         NOT NULL COMMENT '激活前清单快照（JSON：容器编号 -> 排序 bagTag 数组），含全部源容器',
    after_snapshot       CLOB         NULL COMMENT '激活后清单快照（JSON：目标容器编号 -> 排序 bagTag 数组），仅 ACTIVE 非 NULL',
    preview_differences  CLOB         NOT NULL COMMENT '创建时清单差异预览（JSON：目标容器编号 -> {added,removed}，相对源并集）',
    scan_statuses        CLOB         NOT NULL COMMENT '创建时各行李当前扫描状态快照（JSON：bagTag -> 状态）',
    created_request_id   VARCHAR(128) NOT NULL COMMENT '创建重封单的 requestId',
    activated_request_id VARCHAR(128) NULL COMMENT '激活重封单的 requestId，PREVIEW 为 NULL',
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    activated_at         TIMESTAMP WITH TIME ZONE NULL COMMENT '激活完成时刻（UTC），PREVIEW 为 NULL',
    PRIMARY KEY (repack_key)
);

-- 重封证据明细（只读、稳定排序）：每行记录一件行李在某张重封单中的源->目标去向
CREATE TABLE IF NOT EXISTS repack_evidence (
    id           BIGINT AUTO_INCREMENT NOT NULL COMMENT '自增主键',
    repack_key   VARCHAR(64) NOT NULL COMMENT '重封单业务键',
    bag_tag      VARCHAR(64) NOT NULL COMMENT '行李牌号',
    source_container VARCHAR(64) NOT NULL COMMENT '重封前源容器编号',
    target_container VARCHAR(64) NOT NULL COMMENT '重封后目标容器编号',
    old_seal     VARCHAR(64) NOT NULL COMMENT '源容器旧封签号',
    new_seal     VARCHAR(64) NOT NULL COMMENT '目标容器新封签号',
    PRIMARY KEY (id),
    UNIQUE (repack_key, bag_tag)
);
