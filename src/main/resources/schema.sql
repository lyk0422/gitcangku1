-- 联程行李装载交接 schema（H2 MySQL 兼容模式）
-- 业务时间字段按 UTC 墙钟时间存储并在注释中说明，created_at 为数据库默认时间。

-- 航段：leg_id 唯一，version 单调递增，状态 OPEN -> SEALED -> ARRIVED
CREATE TABLE IF NOT EXISTS leg (
    leg_id           VARCHAR(64)  NOT NULL COMMENT '航段唯一标识',
    origin           VARCHAR(64)  NOT NULL COMMENT '始发站代码',
    destination      VARCHAR(64)  NOT NULL COMMENT '到达站代码',
    status           VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT '航段状态：OPEN/SEALED/ARRIVED',
    version          INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本，每次状态或装载变更后递增',
    sealed_manifest  CLOB         NULL COMMENT '封舱时的只读装载清单（JSON 数组），未封舱为 NULL',
    arrival_mode     VARCHAR(16)  NULL COMMENT '到达确认方式只读快照：EXACT 精确到达 / DISCREPANCY 差异到达，未到达为 NULL',
    actual_arrivals  CLOB         NULL COMMENT '实际到达袋号只读快照（JSON 数组）；精确到达等于封舱清单，未到达为 NULL',
    short_manifest   CLOB         NULL COMMENT '短卸缺失袋号只读快照（JSON 数组）；精确到达为空数组，未到达为 NULL',
    arrived_at       TIMESTAMP    NULL COMMENT '到达确认的 UTC 墙钟登记时刻（按 UTC 解释，无时区偏移），未到达为 NULL',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (leg_id)
);

-- 行李：bag_tag 唯一，登记 1~5 个有序航段行程
CREATE TABLE IF NOT EXISTS bag (
    bag_tag             VARCHAR(64) NOT NULL COMMENT '行李牌号，全局唯一',
    current_location    VARCHAR(64) NOT NULL COMMENT '当前所在站点代码；短卸期间停留于短卸前最后已知站，补到后移动到应到站',
    next_leg_index      INT         NOT NULL DEFAULT 0 COMMENT '待乘航段在行程中的下标（0 起），等于行程长度表示已完成；短卸不推进，补到后推进',
    status              VARCHAR(16) NOT NULL DEFAULT 'IN_TRANSIT' COMMENT '行李状态：IN_TRANSIT 在途 / SHORT_UNLOADED 短卸未补到 / RECOVERED 已补到待续运 / DELIVERED 已完成行程交付',
    loaded_leg_id       VARCHAR(64) NULL COMMENT '当前已装载到的航段，未装载为 NULL',
    short_leg_id        VARCHAR(64) NULL COMMENT '短卸缺失航段标识；仅 SHORT_UNLOADED 状态非空，其余状态为 NULL',
    short_destination   VARCHAR(64) NULL COMMENT '短卸应到站代码；仅 SHORT_UNLOADED 状态非空，其余状态为 NULL',
    short_registered_at TIMESTAMP   NULL COMMENT '短卸 UTC 登记时刻（按 UTC 解释的墙钟时间，无时区偏移）；未发生短卸为 NULL',
    created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
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

-- 行李轨迹事件：append-only，每件行李按 seq 递增，覆盖登记/装载/到达/短卸/补到/交付
CREATE TABLE IF NOT EXISTS bag_trace_event (
    bag_tag    VARCHAR(64) NOT NULL COMMENT '行李牌号',
    seq        INT         NOT NULL COMMENT '该行李的事件顺序，0 起递增',
    event_type VARCHAR(24) NOT NULL COMMENT '事件类型：REGISTERED 登记 / LOADED 装载 / ARRIVED 到达 / SHORT_UNLOADED 短卸登记 / RECOVERED 补到 / DELIVERED 交付',
    leg_id     VARCHAR(64) NULL COMMENT '关联航段标识；REGISTERED 事件为 NULL',
    station    VARCHAR(64) NULL COMMENT '事件发生站点代码',
    event_time TIMESTAMP   NOT NULL COMMENT '事件 UTC 墙钟时间（按 UTC 解释，无时区偏移）',
    PRIMARY KEY (bag_tag, seq)
);

-- 幂等去重：仅记录成功请求；同 requestId 同参数重放原结果，异参数返回 409
CREATE TABLE IF NOT EXISTS request_log (
    request_id      VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    operation       VARCHAR(32)  NOT NULL COMMENT '操作类型：REGISTER_LEG/REGISTER_BAG/LOAD/SEAL/ARRIVE/ARRIVE_DISCREPANCY/RECOVER',
    request_hash    VARCHAR(64)  NOT NULL COMMENT '请求参数（不含 requestId）的 SHA-256 摘要',
    response_status INT          NOT NULL COMMENT '原成功响应的 HTTP 状态码',
    response_body   CLOB         NOT NULL COMMENT '原成功响应体（JSON）',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (request_id)
);
