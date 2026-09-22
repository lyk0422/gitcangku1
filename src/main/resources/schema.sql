-- 联程行李装载交接 schema（H2 MySQL 兼容模式；数据仅保留在 JVM 生命周期内）

CREATE TABLE IF NOT EXISTS leg (
    leg_id      VARCHAR(64)  NOT NULL,
    origin      VARCHAR(64)  NOT NULL,
    destination VARCHAR(64)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    version     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (leg_id)
);
COMMENT ON TABLE leg IS '航段：legId 唯一，状态 OPEN -> SEALED -> ARRIVED，版本随业务变更递增';
COMMENT ON COLUMN leg.leg_id IS '航段标识，全局唯一';
COMMENT ON COLUMN leg.origin IS '始发站';
COMMENT ON COLUMN leg.destination IS '到达站';
COMMENT ON COLUMN leg.status IS '航段状态：OPEN 可装载 / SEALED 已封舱 / ARRIVED 已到达';
COMMENT ON COLUMN leg.version IS '乐观锁版本号，从 0 开始，每次状态或装载变更递增';

CREATE TABLE IF NOT EXISTS bag (
    bag_tag         VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'IN_TRANSIT',
    current_station VARCHAR(64) NOT NULL,
    next_leg_index  INT         NOT NULL DEFAULT 0,
    loaded_leg_id   VARCHAR(64) NULL,
    itinerary_size  INT         NOT NULL,
    PRIMARY KEY (bag_tag)
);
COMMENT ON TABLE bag IS '行李：bagTag 唯一，按有序行程依次乘坐航段';
COMMENT ON COLUMN bag.bag_tag IS '行李牌号，全局唯一';
COMMENT ON COLUMN bag.status IS '行李状态：IN_TRANSIT 运输中 / DELIVERED 已完成全部行程';
COMMENT ON COLUMN bag.current_station IS '当前所在站（初始为首段始发站，到达确认后更新为到达站）';
COMMENT ON COLUMN bag.next_leg_index IS '待乘航段索引（0 起），到达确认后递增；等于 itinerary_size 表示行程完成';
COMMENT ON COLUMN bag.loaded_leg_id IS '当前已装载的航段；NULL 表示未装载，到达确认后清空';
COMMENT ON COLUMN bag.itinerary_size IS '行程航段总数（1~5）';

CREATE TABLE IF NOT EXISTS bag_itinerary (
    bag_tag VARCHAR(64) NOT NULL,
    seq     INT         NOT NULL,
    leg_id  VARCHAR(64) NOT NULL,
    PRIMARY KEY (bag_tag, seq)
);
COMMENT ON TABLE bag_itinerary IS '行李有序行程：每件行李 1~5 个不重复航段，相邻航段首尾站衔接';
COMMENT ON COLUMN bag_itinerary.bag_tag IS '行李牌号';
COMMENT ON COLUMN bag_itinerary.seq IS '行程顺序号（0 起）';
COMMENT ON COLUMN bag_itinerary.leg_id IS '该顺序对应的航段标识';

CREATE TABLE IF NOT EXISTS load_entry (
    leg_id VARCHAR(64) NOT NULL,
    bag_tag VARCHAR(64) NOT NULL,
    PRIMARY KEY (leg_id, bag_tag)
);
COMMENT ON TABLE load_entry IS '装载明细：OPEN 航段上当前已装载的行李，封舱时转入封舱清单';
COMMENT ON COLUMN load_entry.leg_id IS '航段标识';
COMMENT ON COLUMN load_entry.bag_tag IS '已装载行李牌号';

CREATE TABLE IF NOT EXISTS manifest_entry (
    leg_id VARCHAR(64) NOT NULL,
    bag_tag VARCHAR(64) NOT NULL,
    PRIMARY KEY (leg_id, bag_tag)
);
COMMENT ON TABLE manifest_entry IS '封舱清单：封舱时固化的只读装载清单，到达确认后保留供查询';
COMMENT ON COLUMN manifest_entry.leg_id IS '航段标识';
COMMENT ON COLUMN manifest_entry.bag_tag IS '封舱时装载的行李牌号';

CREATE TABLE IF NOT EXISTS request_log (
    request_id  VARCHAR(128)  NOT NULL,
    fingerprint VARCHAR(4096) NOT NULL,
    body        CLOB          NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (request_id)
);
COMMENT ON TABLE request_log IS '写操作幂等去重：requestId 全局唯一，仅记录成功结果，失败不占键';
COMMENT ON COLUMN request_log.request_id IS '调用方提供的全局唯一请求标识';
COMMENT ON COLUMN request_log.fingerprint IS '请求参数指纹（规范化 JSON），同键异参返回 409';
COMMENT ON COLUMN request_log.body IS '成功响应报文，用于同键同参重放';
COMMENT ON COLUMN request_log.created_at IS '记录创建时间（应用服务器时区）';
