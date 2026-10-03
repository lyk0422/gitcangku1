-- 引航员：资格等级数值越大资格越高
CREATE TABLE IF NOT EXISTS pilot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pilot_code VARCHAR(32) NOT NULL,
    pilot_name VARCHAR(64) NOT NULL,
    qualification_grade INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);
COMMENT ON TABLE pilot IS '引航员';
COMMENT ON COLUMN pilot.id IS '主键';
COMMENT ON COLUMN pilot.pilot_code IS '引航员工号，唯一';
COMMENT ON COLUMN pilot.pilot_name IS '引航员姓名';
COMMENT ON COLUMN pilot.qualification_grade IS '资格等级，数值越大资格越高，须不低于船舶要求等级';
COMMENT ON COLUMN pilot.active IS '是否在岗，TRUE 在岗';

-- 航段申请：UTC 预计起止与登轮点
CREATE TABLE IF NOT EXISTS segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vessel_code VARCHAR(32) NOT NULL,
    required_grade INT NOT NULL,
    est_start_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    est_end_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    boarding_point VARCHAR(128) NOT NULL,
    original_boarding_point VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    cancelled_at_utc TIMESTAMP WITH TIME ZONE NULL,
    created_at_utc TIMESTAMP WITH TIME ZONE NOT NULL
);
COMMENT ON TABLE segment IS '航段申请';
COMMENT ON COLUMN segment.id IS '主键';
COMMENT ON COLUMN segment.vessel_code IS '船舶代号';
COMMENT ON COLUMN segment.required_grade IS '船舶要求的引航员等级，不得高于被指派人资格等级';
COMMENT ON COLUMN segment.est_start_utc IS '预计航段开始时刻，UTC，含';
COMMENT ON COLUMN segment.est_end_utc IS '预计航段结束时刻，UTC，不含，须晚于开始时刻';
COMMENT ON COLUMN segment.boarding_point IS '当前登轮点，登轮前可修改';
COMMENT ON COLUMN segment.original_boarding_point IS '首次申请的登轮点，修改后仍保留原值';
COMMENT ON COLUMN segment.status IS '状态：REQUESTED 已申请，ASSIGNED 已指派，BOARDED 已登轮，COMPLETED 已完成，CANCELLED 已取消';
COMMENT ON COLUMN segment.cancelled_at_utc IS '取消时刻，UTC，未取消为 NULL；取消后仍保留登轮离轮事实';
COMMENT ON COLUMN segment.created_at_utc IS '申请创建时刻，UTC';

-- 指派：锁定预计区间，登轮后占用延续到实际离轮
CREATE TABLE IF NOT EXISTS assignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    segment_id BIGINT NOT NULL REFERENCES segment(id),
    pilot_id BIGINT NOT NULL REFERENCES pilot(id),
    status VARCHAR(16) NOT NULL,
    est_start_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    est_end_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    boarded_at_utc TIMESTAMP WITH TIME ZONE NULL,
    departed_at_utc TIMESTAMP WITH TIME ZONE NULL,
    revoked_at_utc TIMESTAMP WITH TIME ZONE NULL,
    boarding_request_id VARCHAR(64) NULL,
    created_at_utc TIMESTAMP WITH TIME ZONE NOT NULL
);
COMMENT ON TABLE assignment IS '引航员指派及其登轮、离轮、撤销事实';
COMMENT ON COLUMN assignment.id IS '主键';
COMMENT ON COLUMN assignment.request_id IS '指派幂等请求键，唯一；同参重放返回首次结果，异参返回 409';
COMMENT ON COLUMN assignment.segment_id IS '航段申请主键';
COMMENT ON COLUMN assignment.pilot_id IS '引航员主键';
COMMENT ON COLUMN assignment.status IS '状态：ACTIVE 已指派未登轮，BOARDED 已登轮，COMPLETED 已离轮完成，REVOKED 已撤销';
COMMENT ON COLUMN assignment.est_start_utc IS '指派时锁定的预计开始时刻，UTC，含';
COMMENT ON COLUMN assignment.est_end_utc IS '指派时锁定的预计结束时刻，UTC，不含';
COMMENT ON COLUMN assignment.boarded_at_utc IS '实际登轮时刻，UTC，未登轮为 NULL；登轮后占用延续到实际离轮';
COMMENT ON COLUMN assignment.departed_at_utc IS '实际离轮时刻，UTC，未离轮为 NULL；离轮后释放占用';
COMMENT ON COLUMN assignment.revoked_at_utc IS '撤销时刻，UTC，未撤销为 NULL；仅未登轮可撤销，已登轮不得撤销';
COMMENT ON COLUMN assignment.boarding_request_id IS '登轮确认幂等请求键，未登轮为 NULL';
COMMENT ON COLUMN assignment.created_at_utc IS '指派创建时刻，UTC';

CREATE UNIQUE INDEX IF NOT EXISTS uk_assignment_request_id ON assignment (request_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pilot_code ON pilot (pilot_code);
CREATE INDEX IF NOT EXISTS idx_assignment_pilot ON assignment (pilot_id, status);
CREATE INDEX IF NOT EXISTS idx_segment_vessel ON segment (vessel_code);