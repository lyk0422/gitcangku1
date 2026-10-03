-- 潜水训练作业记录库表结构（H2 MODE=MySQL 自动建表，时间一律存 UTC 瞬时值）

CREATE TABLE IF NOT EXISTS dive_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_max_depth_m INT NOT NULL,
    plan_max_duration_min INT NOT NULL,
    operation_start_utc TIMESTAMP NOT NULL,
    operation_end_utc TIMESTAMP NOT NULL,
    point_version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE dive_log IS '潜水训练作业主表，记录计划限制与作业起止（UTC）';
COMMENT ON COLUMN dive_log.plan_max_depth_m IS '计划最大深度，单位米，审核阈值';
COMMENT ON COLUMN dive_log.plan_max_duration_min IS '连续作业上限，单位分钟，审核阈值';
COMMENT ON COLUMN dive_log.operation_start_utc IS '作业开始时刻，UTC，首点须覆盖';
COMMENT ON COLUMN dive_log.operation_end_utc IS '作业结束时刻，UTC，末点须覆盖';
COMMENT ON COLUMN dive_log.point_version IS '点位版本号，每次追加点位递增，审核按版本冻结';
COMMENT ON COLUMN dive_log.created_at IS '记录创建时刻，UTC';

CREATE TABLE IF NOT EXISTS depth_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dive_log_id BIGINT NOT NULL REFERENCES dive_log(id),
    seq INT NOT NULL,
    observed_at_utc TIMESTAMP NOT NULL,
    depth_m DOUBLE NOT NULL,
    log_version INT NOT NULL,
    errata BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (dive_log_id, seq)
);
COMMENT ON TABLE depth_point IS '原始深度点位，审核后冻结不可修改，仅允许追加';
COMMENT ON COLUMN depth_point.seq IS '作业内点位序号，从1开始严格递增';
COMMENT ON COLUMN depth_point.observed_at_utc IS '观测时刻，UTC，要求严格递增且相邻间隔不超过5分钟';
COMMENT ON COLUMN depth_point.depth_m IS '深度，单位米，非负，超过计划上限判超深';
COMMENT ON COLUMN depth_point.log_version IS '追加该点时的点位版本号，用于一致性核对';
COMMENT ON COLUMN depth_point.errata IS '是否勘误点：TRUE表示在已有审核之后追加';

CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation VARCHAR(32) NOT NULL,
    resource_key VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_status INT NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (operation, resource_key, request_id)
);
COMMENT ON TABLE idempotency_record IS '幂等键登记表，同requestId同参重放、异参冲突，失败回滚不占键';
COMMENT ON COLUMN idempotency_record.operation IS '业务操作类型：CREATE_LOG/APPEND_POINTS/REVIEW';
COMMENT ON COLUMN idempotency_record.resource_key IS '幂等作用域，如log:1';
COMMENT ON COLUMN idempotency_record.request_id IS '客户端请求标识，作用域内唯一';
COMMENT ON COLUMN idempotency_record.request_hash IS '请求参数SHA-256摘要，用于同参校验';
COMMENT ON COLUMN idempotency_record.response_body IS '成功响应快照JSON，重放时原样返回';

CREATE TABLE IF NOT EXISTS dive_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dive_log_id BIGINT NOT NULL REFERENCES dive_log(id),
    point_version INT NOT NULL,
    point_count INT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    anomalies CLOB,
    status VARCHAR(16) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (dive_log_id, point_version, decision)
);
COMMENT ON TABLE dive_review IS '上浮审核版本表，按点位版本冻结审核结论';
COMMENT ON COLUMN dive_review.point_version IS '被审核的点位版本号';
COMMENT ON COLUMN dive_review.point_count IS '审核时读取的点位总数，用于一致性核对';
COMMENT ON COLUMN dive_review.decision IS '审核结论：APPROVED通过/REJECTED拒绝';
COMMENT ON COLUMN dive_review.anomalies IS '异常明细JSON数组，含类型、说明与关联点位';
COMMENT ON COLUMN dive_review.status IS '版本状态：CURRENT当前/STALE已被勘误复核过期';

CREATE TABLE IF NOT EXISTS re_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dive_log_id BIGINT NOT NULL REFERENCES dive_log(id),
    stale_review_id BIGINT NOT NULL REFERENCES dive_review(id),
    from_point_version INT NOT NULL,
    to_point_version INT NOT NULL,
    appended_points INT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE re_review IS '复核链记录：审核后追加勘误点时生成，标记原审核过期';
COMMENT ON COLUMN re_review.stale_review_id IS '被置为过期(STALE)的原审核记录ID';
COMMENT ON COLUMN re_review.from_point_version IS '追加前点位版本号';
COMMENT ON COLUMN re_review.to_point_version IS '追加后点位版本号';
COMMENT ON COLUMN re_review.appended_points IS '本次追加的勘误点数量';