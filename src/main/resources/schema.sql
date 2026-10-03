CREATE TABLE IF NOT EXISTS pilot (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(32) NOT NULL COMMENT '引航员工号，全局唯一',
  name VARCHAR(64) NOT NULL COMMENT '引航员姓名',
  qualification_level INT NOT NULL COMMENT '资格等级，数值越大等级越高，须不低于船舶要求等级',
  CONSTRAINT uk_pilot_code UNIQUE (code)
) COMMENT='引航员';

CREATE TABLE IF NOT EXISTS segment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  vessel_code VARCHAR(32) NOT NULL COMMENT '船舶代号',
  required_level INT NOT NULL COMMENT '船舶要求的引航员资格等级',
  est_start_utc TIMESTAMP NOT NULL COMMENT '预计航段开始时间（UTC）',
  est_end_utc TIMESTAMP NOT NULL COMMENT '预计航段结束时间（UTC），必须晚于开始时间',
  boarding_point VARCHAR(128) NOT NULL COMMENT '当前登轮点，登轮前可修改，原值保留在变更历史表',
  status VARCHAR(16) NOT NULL COMMENT '航段状态：REQUESTED/ASSIGNED/BOARDED/COMPLETED/CANCELLED',
  created_at_utc TIMESTAMP NOT NULL COMMENT '申请创建时间（UTC）',
  cancelled_at_utc TIMESTAMP NULL COMMENT '船舶取消时间（UTC），未取消为空'
) COMMENT='航段申请';

CREATE TABLE IF NOT EXISTS assignment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  segment_id BIGINT NOT NULL COMMENT '航段ID',
  pilot_id BIGINT NOT NULL COMMENT '引航员ID',
  request_id VARCHAR(64) NOT NULL COMMENT '指派幂等请求键，同参重放返回首次结果，异参返回409',
  request_fingerprint VARCHAR(256) NOT NULL COMMENT '指派请求参数指纹（航段+引航员）',
  status VARCHAR(16) NOT NULL COMMENT '指派状态：ACTIVE/RELEASED/COMPLETED',
  boarded_at_utc TIMESTAMP NULL COMMENT '实际登轮时间（UTC），未登轮为空',
  disembarked_at_utc TIMESTAMP NULL COMMENT '实际离轮时间（UTC），未离轮为空；登轮后占用延续到该时间',
  board_request_id VARCHAR(64) NULL COMMENT '登轮幂等请求键',
  board_request_fingerprint VARCHAR(256) NULL COMMENT '登轮请求参数指纹',
  created_at_utc TIMESTAMP NOT NULL COMMENT '指派创建时间（UTC）',
  released_at_utc TIMESTAMP NULL COMMENT '释放时间（UTC），未释放为空',
  release_reason VARCHAR(32) NULL COMMENT '释放原因：CANCELLED（撤销指派）/VESSEL_CANCELLED（船舶取消航段）',
  CONSTRAINT uk_assignment_request UNIQUE (request_id),
  CONSTRAINT fk_assignment_segment FOREIGN KEY (segment_id) REFERENCES segment(id),
  CONSTRAINT fk_assignment_pilot FOREIGN KEY (pilot_id) REFERENCES pilot(id)
) COMMENT='航段指派';

CREATE TABLE IF NOT EXISTS boarding_point_change (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  segment_id BIGINT NOT NULL COMMENT '航段ID',
  old_point VARCHAR(128) NOT NULL COMMENT '修改前登轮点',
  new_point VARCHAR(128) NOT NULL COMMENT '修改后登轮点',
  changed_at_utc TIMESTAMP NOT NULL COMMENT '修改时间（UTC）',
  CONSTRAINT fk_bpc_segment FOREIGN KEY (segment_id) REFERENCES segment(id)
) COMMENT='登轮点变更历史';