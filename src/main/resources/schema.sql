-- 联程行李装载交接 schema（H2 MySQL 兼容模式）
-- 所有时间字段为数据库会话时区（默认 JVM 时区 Asia/Shanghai），NULL 含义见各列注释。

-- 航段：leg_id 唯一，version 单调递增，状态 OPEN -> SEALED -> ARRIVED
CREATE TABLE IF NOT EXISTS leg (
    leg_id            VARCHAR(64)  NOT NULL COMMENT '航段唯一标识',
    origin            VARCHAR(64)  NOT NULL COMMENT '始发站代码',
    destination       VARCHAR(64)  NOT NULL COMMENT '到达站代码',
    status            VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT '航段状态：OPEN/SEALED/ARRIVED',
    version           INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本，每次状态或装载变更后递增',
    sealed_manifest   CLOB         NULL COMMENT '封舱时的只读装载清单（JSON 数组），未封舱为 NULL',
    max_load_weight_kg INT         NOT NULL DEFAULT 1000 COMMENT '批量装载总重上限（千克）：清单内行李当前重量之和不得超过；登记时未指定取默认 1000',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (leg_id)
);

-- 行李：bag_tag 唯一，登记 1~5 个有序航段行程
CREATE TABLE IF NOT EXISTS bag (
    bag_tag           VARCHAR(64) NOT NULL COMMENT '行李牌号，全局唯一',
    current_location  VARCHAR(64) NOT NULL COMMENT '当前所在站点代码',
    next_leg_index    INT         NOT NULL DEFAULT 0 COMMENT '待乘航段在行程中的下标（0 起），等于行程长度表示已完成',
    status            VARCHAR(16) NOT NULL DEFAULT 'IN_TRANSIT' COMMENT '行李状态：IN_TRANSIT/DELIVERED',
    loaded_leg_id     VARCHAR(64) NULL COMMENT '当前已装载到的航段，未装载为 NULL',
    weight_kg         INT         NOT NULL DEFAULT 23 COMMENT '当前记录重量（千克，整数 1~50）；复重纠偏后原子更新为实测值',
    free_allowance_kg INT         NOT NULL DEFAULT 23 COMMENT '登记的旅客免费行李限额（千克，正整数）；整程累计重量超过即触发 OVERWEIGHT 提醒',
    overweight_active BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '是否存在未清除的 OVERWEIGHT 提醒：TRUE 存在，FALSE 无活动提醒',
    created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
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

-- 复重记录：只增不改不删，固化每次复重事件的原重量、新重量、称重站与时刻
CREATE TABLE IF NOT EXISTS reweigh_record (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    bag_tag          VARCHAR(64)  NOT NULL COMMENT '行李牌号',
    seq              INT          NOT NULL COMMENT '同件行李复重事件序号，0 起递增',
    reweigh_key      VARCHAR(128) NOT NULL COMMENT '客户端提交的复重事件标识（称重单键）',
    old_weight_kg    INT          NOT NULL COMMENT '复重前行李记录重量（千克）',
    new_weight_kg    INT          NOT NULL COMMENT '复重后行李记录重量（千克，即实测值；未变化时与原重量相同）',
    weight_changed   BOOLEAN      NOT NULL COMMENT '本次复重是否改变了记录重量：TRUE 原子更新，FALSE 仅记录事件',
    station_id       VARCHAR(64)  NOT NULL COMMENT '称重站标识',
    weighed_at       TIMESTAMP    NOT NULL COMMENT '称重时刻（会话时区）',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录写入时间',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, seq)
);

-- 超重提醒：复重后整程累计重量超限时生成；提交超额说明后置 CLEARED，同一提醒清除不可逆
CREATE TABLE IF NOT EXISTS overweight_alert (
    id                   BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    bag_tag              VARCHAR(64) NOT NULL COMMENT '行李牌号',
    seq                  INT         NOT NULL COMMENT '同件行李提醒序号，0 起递增；清除后再次超限生成新序号',
    reweigh_id           BIGINT      NOT NULL COMMENT '触发本提醒的复重记录主键（reweigh_record.id）',
    journey_weight_kg    INT         NOT NULL COMMENT '触发提醒时整程累计重量（千克）',
    free_allowance_kg    INT         NOT NULL COMMENT '触发提醒时旅客免费限额（千克）',
    status               VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '提醒状态：ACTIVE 未清除/CLEARED 已清除（清除不可逆）',
    raised_at            TIMESTAMP   NOT NULL COMMENT '提醒生成时刻（会话时区）',
    cleared_explanation  CLOB        NULL COMMENT '清除提醒时提交的超额说明；状态 ACTIVE 时为 NULL',
    cleared_at           TIMESTAMP   NULL COMMENT '提醒清除时刻（会话时区）；ACTIVE 时为 NULL',
    PRIMARY KEY (id),
    UNIQUE (bag_tag, seq)
);

-- 幂等去重：仅记录成功请求；同 requestId 同参数重放原结果，异参数返回 409
CREATE TABLE IF NOT EXISTS request_log (
    request_id      VARCHAR(128) NOT NULL COMMENT '全局唯一请求标识',
    operation       VARCHAR(32)  NOT NULL COMMENT '操作类型：REGISTER_LEG/REGISTER_BAG/LOAD/SEAL/ARRIVE/REWEIGH/CLEAR_OVERWEIGHT',
    request_hash    VARCHAR(64)  NOT NULL COMMENT '请求参数（不含 requestId）的 SHA-256 摘要',
    response_status INT          NOT NULL COMMENT '原成功响应的 HTTP 状态码',
    response_body   CLOB         NOT NULL COMMENT '原成功响应体（JSON）',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (request_id)
);
