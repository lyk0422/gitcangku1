-- 联程行李装载交接 schema（H2 MySQL 兼容模式）
-- 普通时间字段为数据库会话时区（默认 JVM 时区 Asia/Shanghai）；
-- TIMESTAMP WITH TIME ZONE 字段统一保存 UTC 时刻，NULL 含义见各列注释。

-- 航段：leg_id 唯一，version 单调递增，状态 OPEN -> SEALED -> ARRIVED
CREATE TABLE IF NOT EXISTS leg (
    leg_id          VARCHAR(64)  NOT NULL COMMENT '航段唯一标识',
    origin          VARCHAR(64)  NOT NULL COMMENT '始发站代码',
    destination     VARCHAR(64)  NOT NULL COMMENT '到达站代码',
    cutoff_time     TIMESTAMP WITH TIME ZONE NOT NULL COMMENT 'UTC 装载截止时刻；时刻恰好到达（now=cutoff）即关闭装载，仅 OPEN 航段可修改',
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT '航段状态：OPEN/SEALED/ARRIVED',
    version         INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本，每次状态、截止时刻或装载变更后递增',
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

-- 装载明细：各航段独立留痕，到达后不删除；bag 行锁 + loaded_leg_id 保证一件行李同一时间只在一个航段清单中，
-- (bag_tag, leg_id) 唯一约束兜底防止同件行李同一航段重复插入
CREATE TABLE IF NOT EXISTS load_record (
    id              BIGINT AUTO_INCREMENT NOT NULL COMMENT '装载记录自增主键',
    bag_tag         VARCHAR(64) NOT NULL COMMENT '行李牌号',
    leg_id          VARCHAR(64) NOT NULL COMMENT '装载到的航段',
    cutoff_snapshot TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '装载成功时该航段 UTC 截止时刻的只读快照，后续修改截止时刻不影响本记录',
    loaded_at       TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '装载成功时刻（UTC），必须早于截止时刻',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, leg_id)
);
CREATE INDEX IF NOT EXISTS idx_load_record_leg ON load_record (leg_id);

-- 装载拒绝留痕：仅记录业务拒绝（截止到达、航段已封舱/到达、交接状态不满足等 422），
-- 版本冲突 409 与幂等异参 409 不记录；批量装载任一行李被拒时整批回滚，合法行李以连带原因留痕。
-- 拒绝不占用装载名额（不写 load_record、不推进航段版本），也不占用幂等 requestId。
CREATE TABLE IF NOT EXISTS load_rejection (
    id            BIGINT AUTO_INCREMENT NOT NULL COMMENT '拒绝记录自增主键',
    bag_tag       VARCHAR(64)  NOT NULL COMMENT '被拒行李牌号',
    leg_id        VARCHAR(64)  NOT NULL COMMENT '申请装载的航段',
    request_id    VARCHAR(128) NULL COMMENT '触发拒绝的装载请求标识；NULL 表示批量内其他行李不满足导致的连带整批拒绝',
    reason_code   VARCHAR(32)  NOT NULL COMMENT '拒绝原因代码：CUTOFF_REACHED 截止已到/LEG_NOT_OPEN 航段已封舱或到达/BAG_SHORT_UNLOADED 短卸未补/BAG_ALREADY_LOADED 已在其他航段/ITINERARY_COMPLETED 行程已完成/WRONG_WAITING_LEG 非当前待乘航段/NOT_AT_ORIGIN 不在始发站/BAG_NOT_FOUND 行李不存在/BATCH_COMPANION_REJECTED 同批其他行李被拒连带回滚',
    reason_detail VARCHAR(255) NOT NULL COMMENT '拒绝原因中文明细',
    rejected_at   TIMESTAMP WITH TIME ZONE NOT NULL COMMENT '拒绝发生时刻（UTC）',
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_load_rejection_bag_leg ON load_rejection (bag_tag, leg_id, rejected_at);

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
    operation       VARCHAR(32)  NOT NULL COMMENT '操作类型：REGISTER_LEG/REGISTER_BAG/LOAD/UPDATE_CUTOFF/SEAL/ARRIVE/ARRIVE_DIFFERENCE/RECOVER',
    request_hash    VARCHAR(64)  NOT NULL COMMENT '请求参数（不含 requestId）的 SHA-256 摘要',
    response_status INT          NOT NULL COMMENT '原成功响应的 HTTP 状态码',
    response_body   CLOB         NOT NULL COMMENT '原成功响应体（JSON）',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (request_id)
);
