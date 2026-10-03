-- 野外标签交接业务建表脚本（H2，MODE=MySQL；时间一律为 UTC，TIMESTAMP 不带时区）
CREATE TABLE IF NOT EXISTS station (
    station_code VARCHAR(64) PRIMARY KEY,
    station_name VARCHAR(128) NOT NULL
);
COMMENT ON TABLE station IS '野外站点（合成数据，不含真实位置）';
COMMENT ON COLUMN station.station_code IS '站点编号，如 ST-ALPHA';
COMMENT ON COLUMN station.station_name IS '站点显示名';

CREATE TABLE IF NOT EXISTS animal (
    animal_code VARCHAR(64) PRIMARY KEY,
    created_at TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE animal IS '动物个体（合成编号）';
COMMENT ON COLUMN animal.animal_code IS '动物编号，如 AN-1001';
COMMENT ON COLUMN animal.created_at IS '登记时刻（UTC）';

CREATE TABLE IF NOT EXISTS tag (
    tag_code VARCHAR(64) PRIMARY KEY,
    holder_station_code VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    registered_at TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE tag IS '可复用标签及其当前持有站点';
COMMENT ON COLUMN tag.tag_code IS '标签编号，如 L-01';
COMMENT ON COLUMN tag.holder_station_code IS '当前持有站点编号；交接确认后才变更，保证同一时刻只有一个持有站点';
COMMENT ON COLUMN tag.status IS '标签状态：DETACHED=已摘除可交接/可附着，ATTACHED=附着中';
COMMENT ON COLUMN tag.registered_at IS '登记时刻（UTC）';

CREATE TABLE IF NOT EXISTS attachment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tag_code VARCHAR(64) NOT NULL,
    animal_code VARCHAR(64) NOT NULL,
    station_code VARCHAR(64) NOT NULL,
    start_at TIMESTAMP(6) NOT NULL,
    end_at TIMESTAMP(6) NULL
);
COMMENT ON TABLE attachment IS '附着记录：标签-动物-站点的有效期关联，左闭右开，摘除只写 end_at 不覆盖历史';
COMMENT ON COLUMN attachment.tag_code IS '标签编号';
COMMENT ON COLUMN attachment.animal_code IS '动物编号';
COMMENT ON COLUMN attachment.station_code IS '附着发生的站点编号';
COMMENT ON COLUMN attachment.start_at IS '有效期起（UTC，含该时刻）';
COMMENT ON COLUMN attachment.end_at IS '有效期止（UTC，不含该时刻）；NULL 表示仍附着';

CREATE TABLE IF NOT EXISTS tag_transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tag_code VARCHAR(64) NOT NULL,
    from_station_code VARCHAR(64) NOT NULL,
    to_station_code VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    initiated_at TIMESTAMP(6) NOT NULL,
    confirmed_at TIMESTAMP(6) NULL,
    request_id VARCHAR(64) NOT NULL
);
COMMENT ON TABLE tag_transfer IS '站点间交接单：当前持有站点发起、目标站点确认后持有权才转移';
COMMENT ON COLUMN tag_transfer.tag_code IS '标签编号';
COMMENT ON COLUMN tag_transfer.from_station_code IS '发起站点（必须为当前持有站点）';
COMMENT ON COLUMN tag_transfer.to_station_code IS '接收站点';
COMMENT ON COLUMN tag_transfer.status IS 'PENDING=待确认，CONFIRMED=已确认，CANCELLED=已取消（仅未确认可取消）';
COMMENT ON COLUMN tag_transfer.initiated_at IS '发起时刻（UTC）';
COMMENT ON COLUMN tag_transfer.confirmed_at IS '确认时刻（UTC）；NULL 表示未确认';
COMMENT ON COLUMN tag_transfer.request_id IS '幂等请求键';

CREATE TABLE IF NOT EXISTS idempotency_record (
    operation VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (operation, request_id)
);
COMMENT ON TABLE idempotency_record IS '幂等请求键：同参重放返回首次结果，异参重放判 409；仅随业务事务成功提交';
COMMENT ON COLUMN idempotency_record.operation IS '操作类型：ATTACH / TRANSFER_INIT';
COMMENT ON COLUMN idempotency_record.request_id IS '客户端请求键';
COMMENT ON COLUMN idempotency_record.payload_hash IS '请求参数 SHA-256 摘要';
COMMENT ON COLUMN idempotency_record.response_body IS '首次成功响应快照（JSON）';
COMMENT ON COLUMN idempotency_record.created_at IS '记录时刻（UTC）';

CREATE TABLE IF NOT EXISTS rejection_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tag_code VARCHAR(64) NULL,
    animal_code VARCHAR(64) NULL,
    operation VARCHAR(32) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE rejection_event IS '被拒绝的业务操作审计，用于时间线展示拒绝原因；独立于业务事务提交';
COMMENT ON COLUMN rejection_event.tag_code IS '相关标签编号，可为空';
COMMENT ON COLUMN rejection_event.animal_code IS '相关动物编号，可为空';
COMMENT ON COLUMN rejection_event.operation IS '操作类型';
COMMENT ON COLUMN rejection_event.reason IS '拒绝原因';
COMMENT ON COLUMN rejection_event.occurred_at IS '拒绝发生时刻（UTC）';