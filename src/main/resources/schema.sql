-- 潜水作业记录与审核（合成训练数据，仅用于复盘，不构成医疗建议或真实潜水方案）
CREATE TABLE IF NOT EXISTS dive_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    planned_start_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    planned_end_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    max_depth_m DECIMAL(5,2) NOT NULL,
    max_duration_min INT NOT NULL,
    point_version INT NOT NULL DEFAULT 0,
    created_at_utc TIMESTAMP WITH TIME ZONE NOT NULL
);
COMMENT ON TABLE dive_session IS '潜水作业（训练计划与点位版本）';
COMMENT ON COLUMN dive_session.id IS '作业ID，自增';
COMMENT ON COLUMN dive_session.name IS '作业名称（合成训练数据）';
COMMENT ON COLUMN dive_session.planned_start_utc IS '计划作业开始时刻，UTC，首点须不晚于该时刻';
COMMENT ON COLUMN dive_session.planned_end_utc IS '计划作业结束时刻，UTC，末点须不晚于该时刻之后（末点须覆盖到该时刻）';
COMMENT ON COLUMN dive_session.max_depth_m IS '训练计划最大深度，单位米，审核阈值含边界';
COMMENT ON COLUMN dive_session.max_duration_min IS '连续作业上限，单位分钟，按首末点间隔计算';
COMMENT ON COLUMN dive_session.point_version IS '点位版本号，从0开始，每次追加点位批次后递增，审核按版本冻结';
COMMENT ON COLUMN dive_session.created_at_utc IS '记录创建时刻，UTC';

CREATE TABLE IF NOT EXISTS depth_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    seq INT NOT NULL,
    recorded_at_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    depth_m DECIMAL(5,2) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_point_session FOREIGN KEY (session_id) REFERENCES dive_session (id),
    CONSTRAINT uk_point_session_seq UNIQUE (session_id, seq)
);
COMMENT ON TABLE depth_point IS '深度点位，审核后冻结，只允许追加不允许修改';
COMMENT ON COLUMN depth_point.id IS '点位ID，自增';
COMMENT ON COLUMN depth_point.session_id IS '所属作业ID';
COMMENT ON COLUMN depth_point.seq IS '作业内点位序号，从1开始严格递增，追加只增不改';
COMMENT ON COLUMN depth_point.recorded_at_utc IS '点位记录时刻，UTC，业务要求严格递增且相邻间隔不超过5分钟';
COMMENT ON COLUMN depth_point.depth_m IS '深度，单位米，非负，超过计划最大深度构成超深异常';
COMMENT ON COLUMN depth_point.request_id IS '上传该点位的幂等请求ID';
COMMENT ON COLUMN depth_point.created_at_utc IS '落库时刻，UTC';

CREATE TABLE IF NOT EXISTS point_upload (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    point_count INT NOT NULL,
    first_seq INT NOT NULL,
    last_seq INT NOT NULL,
    point_version INT NOT NULL,
    created_at_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_upload_session FOREIGN KEY (session_id) REFERENCES dive_session (id),
    CONSTRAINT uk_upload_session_request UNIQUE (session_id, request_id)
);
COMMENT ON TABLE point_upload IS '点位上传幂等批次，同requestId同参重放、异参冲突';
COMMENT ON COLUMN point_upload.id IS '批次ID，自增';
COMMENT ON COLUMN point_upload.session_id IS '所属作业ID';
COMMENT ON COLUMN point_upload.request_id IS '客户端幂等请求ID';
COMMENT ON COLUMN point_upload.payload_hash IS '请求参数（作业+requestId+点位列表）的SHA-256十六进制';
COMMENT ON COLUMN point_upload.point_count IS '本批次点位数';
COMMENT ON COLUMN point_upload.first_seq IS '本批次首个点位序号';
COMMENT ON COLUMN point_upload.last_seq IS '本批次末个点位序号';
COMMENT ON COLUMN point_upload.point_version IS '本批次落库后的点位版本号';
COMMENT ON COLUMN point_upload.created_at_utc IS '落库时刻，UTC';

CREATE TABLE IF NOT EXISTS dive_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    point_version INT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_id VARCHAR(64) NULL,
    idem_key VARCHAR(64) NULL,
    passed_key INT NULL,
    anomalies_json VARCHAR(4000) NOT NULL,
    supersedes_id BIGINT NULL,
    created_at_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_review_session FOREIGN KEY (session_id) REFERENCES dive_session (id),
    CONSTRAINT uk_review_idem UNIQUE (session_id, idem_key),
    CONSTRAINT uk_review_passed UNIQUE (session_id, passed_key)
);
COMMENT ON TABLE dive_review IS '审核与复核记录，按点位版本冻结结果，追加点位后原记录过期';
COMMENT ON COLUMN dive_review.id IS '审核记录ID，自增';
COMMENT ON COLUMN dive_review.session_id IS '所属作业ID';
COMMENT ON COLUMN dive_review.point_version IS '审核时点位的版本号';
COMMENT ON COLUMN dive_review.kind IS '记录类型：INITIAL=首次审核，RE_REVIEW=追加勘误点后的复核';
COMMENT ON COLUMN dive_review.decision IS '审核结论：PASSED=通过，REJECTED=拒绝';
COMMENT ON COLUMN dive_review.status IS '记录状态：ACTIVE=当前有效，EXPIRED=因追加点位已过期';
COMMENT ON COLUMN dive_review.request_id IS '审核请求的幂等ID，系统复核为空';
COMMENT ON COLUMN dive_review.idem_key IS '幂等键，仅通过决定占用（等于request_id），失败不占键，空值不参与唯一约束';
COMMENT ON COLUMN dive_review.passed_key IS '通过唯一键，仅通过决定占用（等于point_version），保证同一版本最多一条通过决定';
COMMENT ON COLUMN dive_review.anomalies_json IS '异常明细JSON数组，含类型、说明与涉及点位序号/时刻/深度';
COMMENT ON COLUMN dive_review.supersedes_id IS '复核链：本记录取代的上一条审核记录ID，首条为空';
COMMENT ON COLUMN dive_review.created_at_utc IS '审核时刻，UTC';