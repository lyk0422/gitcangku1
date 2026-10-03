-- 货运地磅争议结算 schema（H2 MODE=MySQL，本地与测试共用；重量单位：公斤；时间：Asia/Shanghai）
CREATE TABLE IF NOT EXISTS transport_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no VARCHAR(64) NOT NULL,
  vehicle_no VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_transport_order_no UNIQUE (order_no)
);
COMMENT ON TABLE transport_order IS '运输单：一辆货车一次运输任务，关联全部称量与结算';
COMMENT ON COLUMN transport_order.order_no IS '运输单号，业务唯一';
COMMENT ON COLUMN transport_order.vehicle_no IS '合成车辆号';
COMMENT ON COLUMN transport_order.created_at IS '创建时间（Asia/Shanghai）';

CREATE TABLE IF NOT EXISTS weighing_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  transport_order_id BIGINT NOT NULL,
  dispute_id BIGINT NULL,
  phase VARCHAR(16) NOT NULL,
  weight_kg INT NOT NULL,
  weighed_at TIMESTAMP NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_weighing_request UNIQUE (request_id),
  CONSTRAINT fk_weighing_order FOREIGN KEY (transport_order_id) REFERENCES transport_order (id)
);
COMMENT ON TABLE weighing_record IS '称量单：入场皮重/出场毛重/复称毛重记录，毛重必须不小于皮重';
COMMENT ON COLUMN weighing_record.dispute_id IS '复称来源的争议ID，原始称量为NULL';
COMMENT ON COLUMN weighing_record.phase IS '称量阶段：ENTRY_TARE入场皮重 / EXIT_GROSS出场毛重 / REWEIGH_GROSS复称毛重';
COMMENT ON COLUMN weighing_record.weight_kg IS '称量重量，单位公斤';
COMMENT ON COLUMN weighing_record.weighed_at IS '过磅时间（Asia/Shanghai），不得早于同单既有称量';
COMMENT ON COLUMN weighing_record.request_id IS '幂等请求ID，同参重放返回原结果，异参返回409';
COMMENT ON COLUMN weighing_record.request_hash IS '请求参数SHA-256摘要，用于幂等参数比对';

CREATE TABLE IF NOT EXISTS dispute (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  transport_order_id BIGINT NOT NULL,
  reason VARCHAR(512) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  CONSTRAINT uk_dispute_request UNIQUE (request_id),
  CONSTRAINT fk_dispute_order FOREIGN KEY (transport_order_id) REFERENCES transport_order (id)
);
COMMENT ON TABLE dispute IS '复称申请（争议）：出场后30分钟内提出，逾期拒绝';
COMMENT ON COLUMN dispute.reason IS '司机异议理由';
COMMENT ON COLUMN dispute.status IS '争议状态：OPEN待裁决 / ADJUDICATED已裁决';

CREATE TABLE IF NOT EXISTS adjudication (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  dispute_id BIGINT NOT NULL,
  adopted_weighing_id BIGINT NOT NULL,
  reason VARCHAR(512) NOT NULL,
  decided_by VARCHAR(64) NOT NULL,
  decided_at TIMESTAMP NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  CONSTRAINT uk_adjudication_dispute UNIQUE (dispute_id),
  CONSTRAINT uk_adjudication_request UNIQUE (request_id),
  CONSTRAINT fk_adjudication_weighing FOREIGN KEY (adopted_weighing_id) REFERENCES weighing_record (id)
);
COMMENT ON TABLE adjudication IS '争议裁决：裁决员从原称量或复称中明确采纳一条，过程与理由留痕；每争议仅一条（并发唯一）';
COMMENT ON COLUMN adjudication.adopted_weighing_id IS '被采纳的称量记录ID（原毛重或复称毛重）';
COMMENT ON COLUMN adjudication.reason IS '裁决理由，留痕';
COMMENT ON COLUMN adjudication.decided_by IS '裁决员标识';
COMMENT ON COLUMN adjudication.decided_at IS '裁决时间（Asia/Shanghai）';

CREATE TABLE IF NOT EXISTS net_weight_freeze (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  transport_order_id BIGINT NOT NULL,
  tare_weight_kg INT NOT NULL,
  adopted_weighing_id BIGINT NOT NULL,
  gross_weight_kg INT NOT NULL,
  net_weight_kg INT NOT NULL,
  weighing_version INT NOT NULL,
  frozen_at TIMESTAMP NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  CONSTRAINT uk_freeze_order UNIQUE (transport_order_id),
  CONSTRAINT uk_freeze_request UNIQUE (request_id)
);
COMMENT ON TABLE net_weight_freeze IS '最终净重冻结（结算快照）：每运输单仅一份，冻结后不可覆盖';
COMMENT ON COLUMN net_weight_freeze.tare_weight_kg IS '入场皮重，单位公斤';
COMMENT ON COLUMN net_weight_freeze.adopted_weighing_id IS '被采纳的称量记录ID（无争议时为原毛重）';
COMMENT ON COLUMN net_weight_freeze.gross_weight_kg IS '采纳毛重，单位公斤';
COMMENT ON COLUMN net_weight_freeze.net_weight_kg IS '净重=采纳毛重-皮重，单位公斤，不得为负';
COMMENT ON COLUMN net_weight_freeze.weighing_version IS '冻结时已提交称量记录数（版本集合标识），与复称提交互斥校验';
COMMENT ON COLUMN net_weight_freeze.frozen_at IS '冻结时间（Asia/Shanghai）';

CREATE TABLE IF NOT EXISTS errata_application (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  freeze_id BIGINT NOT NULL,
  weight_kg INT NOT NULL,
  weighed_at TIMESTAMP NOT NULL,
  reason VARCHAR(512) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  CONSTRAINT uk_errata_request UNIQUE (request_id),
  CONSTRAINT fk_errata_freeze FOREIGN KEY (freeze_id) REFERENCES net_weight_freeze (id)
);
COMMENT ON TABLE errata_application IS '勘误申请：冻结后的新称量只能形成勘误申请，不覆盖既有结算快照';
COMMENT ON COLUMN errata_application.weight_kg IS '勘误申报重量，单位公斤';
COMMENT ON COLUMN errata_application.status IS '勘误状态：PENDING待处理';