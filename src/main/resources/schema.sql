-- 野生动物标签业务表结构（H2，MODE=MySQL）。所有时刻均为 UTC，按左闭右开区间解释。
CREATE TABLE IF NOT EXISTS tag_ownership (
    tag_code VARCHAR(64) NOT NULL COMMENT '标签编号（合成，全局唯一）',
    station_code VARCHAR(64) NOT NULL COMMENT '当前持有站点编号',
    registered_at TIMESTAMP NOT NULL COMMENT '登记时刻（UTC）',
    PRIMARY KEY (tag_code)
) COMMENT='标签登记与当前持有站点，行锁用于串行化并发操作';

CREATE TABLE IF NOT EXISTS attachment (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '附着记录主键',
    tag_code VARCHAR(64) NOT NULL COMMENT '标签编号',
    animal_code VARCHAR(64) NOT NULL COMMENT '动物编号（合成）',
    station_code VARCHAR(64) NOT NULL COMMENT '执行附着的站点编号',
    attached_at TIMESTAMP NOT NULL COMMENT '附着开始时刻（UTC，区间左闭端点）',
    detached_at TIMESTAMP NULL COMMENT '摘除时刻（UTC，区间右开端点；NULL 表示仍附着）',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键，同键同参重放',
    active_tag_key VARCHAR(64) NULL COMMENT '有效附着占位键：附着期等于标签编号，摘除置 NULL',
    active_animal_key VARCHAR(64) NULL COMMENT '有效附着占位键：附着期等于动物编号，摘除置 NULL',
    PRIMARY KEY (id),
    CONSTRAINT uk_attachment_request UNIQUE (request_id),
    CONSTRAINT uk_attachment_active_tag UNIQUE (active_tag_key),
    CONSTRAINT uk_attachment_active_animal UNIQUE (active_animal_key)
) COMMENT='标签附着记录，摘除时冻结为历史，不覆盖';

CREATE TABLE IF NOT EXISTS transfer (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '交接单主键',
    tag_code VARCHAR(64) NOT NULL COMMENT '标签编号',
    from_station VARCHAR(64) NOT NULL COMMENT '发起站点（交接时当前持有站点）',
    to_station VARCHAR(64) NOT NULL COMMENT '接收站点编号',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING 待确认 / CONFIRMED 已确认 / CANCELLED 已取消',
    initiated_at TIMESTAMP NOT NULL COMMENT '发起时刻（UTC）',
    confirmed_at TIMESTAMP NULL COMMENT '接收站点确认时刻（UTC）；未确认为 NULL',
    cancelled_at TIMESTAMP NULL COMMENT '取消时刻（UTC）；未取消为 NULL',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键，同键同参重放',
    PRIMARY KEY (id),
    CONSTRAINT uk_transfer_request UNIQUE (request_id)
) COMMENT='站点间标签交接单，确认后持有权才转移';

CREATE TABLE IF NOT EXISTS request_key (
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：ATTACH / TRANSFER',
    fingerprint VARCHAR(128) NOT NULL COMMENT '业务参数指纹，异参重放返回 409',
    response_body VARCHAR(2048) NULL COMMENT '成功时的响应快照，用于同参重放',
    created_at TIMESTAMP NOT NULL COMMENT '首次受理时刻（UTC）',
    PRIMARY KEY (request_id)
) COMMENT='幂等请求键，事务失败回滚时不消费';

CREATE TABLE IF NOT EXISTS rejection_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '拒绝记录主键',
    operation VARCHAR(32) NOT NULL COMMENT '被拒绝的操作类型',
    tag_code VARCHAR(64) NULL COMMENT '涉及标签编号；无则 NULL',
    animal_code VARCHAR(64) NULL COMMENT '涉及动物编号；无则 NULL',
    station_code VARCHAR(64) NULL COMMENT '发起站点编号；无则 NULL',
    reason VARCHAR(512) NOT NULL COMMENT '拒绝原因',
    occurred_at TIMESTAMP NOT NULL COMMENT '拒绝发生时刻（UTC）',
    PRIMARY KEY (id)
) COMMENT='业务拒绝日志，供时间线展示拒绝原因';