-- 雪崩控制区积雪与封闭解除领域建表脚本（H2 MODE=MySQL 与 MySQL 兼容语法）
-- 时间字段均为 UTC  epoch 秒级 TIMESTAMP，空值含义见各列 COMMENT。

CREATE TABLE IF NOT EXISTS zone_state (
    zone_id VARCHAR(64) NOT NULL PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    reading_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE zone_state IS '控制区当前状态行，每区一行，用于行锁串行化上报与审核';
COMMENT ON COLUMN zone_state.zone_id IS '控制区标识';
COMMENT ON COLUMN zone_state.status IS '区域状态：OPEN=开放，CLOSED=封闭';
COMMENT ON COLUMN zone_state.reading_version IS '读数版本号，每新增一条整点读数加一，用于审核一致性校验';
COMMENT ON COLUMN zone_state.updated_at IS '最近一次状态变更时间（UTC）';

CREATE TABLE IF NOT EXISTS snow_reading (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    zone_id VARCHAR(64) NOT NULL,
    hour_utc TIMESTAMP NOT NULL,
    snow_depth_cm INT NOT NULL,
    new_snow_cm INT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_reading_zone_hour UNIQUE (zone_id, hour_utc),
    CONSTRAINT uk_reading_zone_request UNIQUE (zone_id, request_id)
);
COMMENT ON TABLE snow_reading IS '合成雪况记录：每个控制区每个整点一条，仅可追加勘误不可修改';
COMMENT ON COLUMN snow_reading.hour_utc IS '整点时刻（UTC，分秒必须为 0）';
COMMENT ON COLUMN snow_reading.snow_depth_cm IS '该整点雪深，单位厘米';
COMMENT ON COLUMN snow_reading.new_snow_cm IS '该整点过去一小时新增积雪，单位厘米；大于 5 视为超限';
COMMENT ON COLUMN snow_reading.request_id IS '上报幂等键，同参重放返回原记录，异参冲突返回 409';
COMMENT ON COLUMN snow_reading.created_at IS '记录入库时间（UTC）';

CREATE TABLE IF NOT EXISTS reading_errata (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    reading_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    note VARCHAR(512) NOT NULL,
    review_result VARCHAR(32) NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_errata_reading_request UNIQUE (reading_id, request_id)
);
COMMENT ON TABLE reading_errata IS '旧读数的勘误与复核结果，只允许追加，不回改原读数';
COMMENT ON COLUMN reading_errata.reading_id IS '关联的 snow_reading.id';
COMMENT ON COLUMN reading_errata.request_id IS '勘误幂等键，按读数维度去重';
COMMENT ON COLUMN reading_errata.note IS '勘误说明';
COMMENT ON COLUMN reading_errata.review_result IS '复核结论，如 CONFIRMED/CORRECTED，可空表示仅勘误未复核';
COMMENT ON COLUMN reading_errata.created_at IS '勘误追加时间（UTC）';

CREATE TABLE IF NOT EXISTS closure_command (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    zone_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NULL,
    source VARCHAR(8) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    status VARCHAR(8) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    lifted_at TIMESTAMP NULL,
    CONSTRAINT uk_closure_zone_request UNIQUE (zone_id, request_id)
);
COMMENT ON TABLE closure_command IS '封闭命令：超限读数自动生成或人工下发；解除后保留不删除';
COMMENT ON COLUMN closure_command.request_id IS '人工封闭幂等键；AUTO 自动封闭为空';
COMMENT ON COLUMN closure_command.source IS '来源：AUTO=超限读数自动触发，MANUAL=人工命令';
COMMENT ON COLUMN closure_command.reason IS '封闭原因说明';
COMMENT ON COLUMN closure_command.status IS '状态：ACTIVE=生效中，LIFTED=已解除';
COMMENT ON COLUMN closure_command.created_at IS '封闭生效时间（UTC）';
COMMENT ON COLUMN closure_command.lifted_at IS '解除时间（UTC），空表示尚未解除';

CREATE TABLE IF NOT EXISTS release_review (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    zone_id VARCHAR(64) NOT NULL,
    closure_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    first_reviewer VARCHAR(64) NOT NULL,
    second_reviewer VARCHAR(64) NULL,
    first_request_id VARCHAR(64) NOT NULL,
    second_request_id VARCHAR(64) NULL,
    reading_version BIGINT NOT NULL,
    window_head_reading_id BIGINT NOT NULL,
    invalidate_reason VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NULL,
    CONSTRAINT uk_review_zone_first_request UNIQUE (zone_id, first_request_id),
    CONSTRAINT uk_review_zone_second_request UNIQUE (zone_id, second_request_id)
);
COMMENT ON TABLE release_review IS '解除封闭双人审核单；第二人确认前任何超限读数都会使其失效';
COMMENT ON COLUMN release_review.closure_id IS '针对的封闭命令 id';
COMMENT ON COLUMN release_review.status IS '状态：IN_PROGRESS=等待第二人，COMPLETED=已完成，INVALIDATED=已失效';
COMMENT ON COLUMN release_review.first_reviewer IS '第一名审核员';
COMMENT ON COLUMN release_review.second_reviewer IS '第二名审核员，空表示尚未确认；不得与第一人相同';
COMMENT ON COLUMN release_review.reading_version IS '发起时的读数版本，第二人确认时必须一致';
COMMENT ON COLUMN release_review.window_head_reading_id IS '发起时三小时窗口最新读数 id，用于一致性核对';
COMMENT ON COLUMN release_review.invalidate_reason IS '失效原因，如 OVER_LIMIT_READING；空表示未失效';
COMMENT ON COLUMN release_review.completed_at IS '第二人确认完成时间（UTC），空表示未完成';

CREATE TABLE IF NOT EXISTS release_decision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    review_id BIGINT NOT NULL,
    zone_id VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_decision_review UNIQUE (review_id)
);
COMMENT ON TABLE release_decision IS '解除决定版本：第二人确认后生成，永久保留，后续重新封闭不删除';
COMMENT ON COLUMN release_decision.review_id IS '来源审核单 id，唯一约束保证并发确认只形成一个版本';
COMMENT ON COLUMN release_decision.version_no IS '该控制区的解除版本序号，从 1 递增';
COMMENT ON COLUMN release_decision.created_at IS '解除决定生成时间（UTC）';