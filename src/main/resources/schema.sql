-- 地热井注入/采出切换审核 schema（H2，MODE=MySQL；时刻一律 UTC）
CREATE TABLE IF NOT EXISTS well_lock (
  well_no VARCHAR(64) NOT NULL,
  CONSTRAINT pk_well_lock PRIMARY KEY (well_no)
);
COMMENT ON TABLE well_lock IS '井级并发控制行锁表';
COMMENT ON COLUMN well_lock.well_no IS '井号；同一井的写操作先 SELECT ... FOR UPDATE 锁定该行以串行化';

CREATE TABLE IF NOT EXISTS run_segment (
  id BIGINT AUTO_INCREMENT NOT NULL,
  well_no VARCHAR(64) NOT NULL,
  mode VARCHAR(16) NOT NULL,
  start_time TIMESTAMP NOT NULL,
  end_time TIMESTAMP NULL,
  request_id VARCHAR(64) NOT NULL,
  CONSTRAINT pk_run_segment PRIMARY KEY (id)
);
COMMENT ON TABLE run_segment IS '地热井运行段';
COMMENT ON COLUMN run_segment.id IS '运行段主键';
COMMENT ON COLUMN run_segment.well_no IS '井号';
COMMENT ON COLUMN run_segment.mode IS '运行模式：INJECTION 注入，PRODUCTION 采出';
COMMENT ON COLUMN run_segment.start_time IS '段开始时刻（UTC）；新段不得早于旧段结束时刻';
COMMENT ON COLUMN run_segment.end_time IS '段结束时刻（UTC）；NULL 表示进行中';
COMMENT ON COLUMN run_segment.request_id IS '创建该段的幂等请求编号（切换审核生成时为 switch:<申请id>）';

CREATE TABLE IF NOT EXISTS pressure_sample (
  id BIGINT AUTO_INCREMENT NOT NULL,
  well_no VARCHAR(64) NOT NULL,
  segment_id BIGINT NOT NULL,
  pressure_mpa DECIMAL(6,3) NOT NULL,
  sampled_at TIMESTAMP NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  voided BOOLEAN NOT NULL DEFAULT FALSE,
  void_reason VARCHAR(255) NULL,
  voided_at TIMESTAMP NULL,
  replaced_by_id BIGINT NULL,
  CONSTRAINT pk_pressure_sample PRIMARY KEY (id),
  CONSTRAINT fk_sample_segment FOREIGN KEY (segment_id) REFERENCES run_segment (id)
);
COMMENT ON TABLE pressure_sample IS '井口压力采样（只追加，错误读数以作废标记加替代读数纠正）';
COMMENT ON COLUMN pressure_sample.id IS '采样主键';
COMMENT ON COLUMN pressure_sample.well_no IS '井号';
COMMENT ON COLUMN pressure_sample.segment_id IS '所属运行段主键';
COMMENT ON COLUMN pressure_sample.pressure_mpa IS '井口压力读数，单位 MPa';
COMMENT ON COLUMN pressure_sample.sampled_at IS '采样时刻（UTC）';
COMMENT ON COLUMN pressure_sample.request_id IS '采样的幂等请求编号；替代读数为 <作废请求编号>#replacement';
COMMENT ON COLUMN pressure_sample.voided IS '是否已作废；作废不删除行，仅追加标记';
COMMENT ON COLUMN pressure_sample.void_reason IS '作废原因；NULL 表示未作废';
COMMENT ON COLUMN pressure_sample.voided_at IS '作废时刻（UTC）；NULL 表示未作废';
COMMENT ON COLUMN pressure_sample.replaced_by_id IS '替代读数的采样主键；NULL 表示无替代';

CREATE TABLE IF NOT EXISTS switch_request (
  id BIGINT AUTO_INCREMENT NOT NULL,
  well_no VARCHAR(64) NOT NULL,
  from_segment_id BIGINT NOT NULL,
  target_mode VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  reject_reason VARCHAR(255) NULL,
  new_segment_id BIGINT NULL,
  new_mode_start TIMESTAMP NULL,
  decision_outcome VARCHAR(24) NULL,
  request_id VARCHAR(64) NOT NULL,
  decision_request_id VARCHAR(64) NULL,
  created_at TIMESTAMP NOT NULL,
  decided_at TIMESTAMP NULL,
  CONSTRAINT pk_switch_request PRIMARY KEY (id),
  CONSTRAINT fk_switch_from_segment FOREIGN KEY (from_segment_id) REFERENCES run_segment (id)
);
COMMENT ON TABLE switch_request IS '注入/采出切换申请与审核决定';
COMMENT ON COLUMN switch_request.id IS '切换申请主键';
COMMENT ON COLUMN switch_request.well_no IS '井号';
COMMENT ON COLUMN switch_request.from_segment_id IS '申请时进行中的运行段主键';
COMMENT ON COLUMN switch_request.target_mode IS '目标模式：INJECTION 注入，PRODUCTION 采出';
COMMENT ON COLUMN switch_request.status IS '状态：PENDING 待审核，APPROVED 已批准，REJECTED 已拒绝';
COMMENT ON COLUMN switch_request.reject_reason IS '拒绝原因；NULL 表示未拒绝';
COMMENT ON COLUMN switch_request.new_segment_id IS '批准后生成的新运行段主键；NULL 表示未批准';
COMMENT ON COLUMN switch_request.new_mode_start IS '批准后冻结的新模式起点（UTC）；NULL 表示未批准';
COMMENT ON COLUMN switch_request.decision_outcome IS '决定类型：APPROVED 批准，OPERATOR_REJECTED 审核人拒绝，CONDITION_FAILED 条件不满足被拒；NULL 表示未决定';
COMMENT ON COLUMN switch_request.request_id IS '申请的幂等请求编号';
COMMENT ON COLUMN switch_request.decision_request_id IS '审核决定的幂等请求编号；NULL 表示未审核';
COMMENT ON COLUMN switch_request.created_at IS '申请时刻（UTC）';
COMMENT ON COLUMN switch_request.decided_at IS '审核决定时刻（UTC）；NULL 表示未审核';

CREATE TABLE IF NOT EXISTS switch_request_sample (
  switch_request_id BIGINT NOT NULL,
  sample_id BIGINT NOT NULL,
  seq INT NOT NULL,
  CONSTRAINT pk_switch_request_sample PRIMARY KEY (switch_request_id, sample_id),
  CONSTRAINT fk_srs_request FOREIGN KEY (switch_request_id) REFERENCES switch_request (id),
  CONSTRAINT fk_srs_sample FOREIGN KEY (sample_id) REFERENCES pressure_sample (id)
);
COMMENT ON TABLE switch_request_sample IS '审核批准时冻结的采样快照（四个及以上）';
COMMENT ON COLUMN switch_request_sample.switch_request_id IS '切换申请主键';
COMMENT ON COLUMN switch_request_sample.sample_id IS '冻结的采样主键；快照不随后续作废改变';
COMMENT ON COLUMN switch_request_sample.seq IS '快照内按采样时刻排序的序号，从 1 开始';

CREATE TABLE IF NOT EXISTS idempotency_record (
  scope VARCHAR(80) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  entity_id BIGINT NOT NULL,
  http_status INT NOT NULL,
  error_message VARCHAR(255) NULL,
  CONSTRAINT pk_idempotency PRIMARY KEY (scope, request_id)
);
COMMENT ON TABLE idempotency_record IS '幂等请求记录：同参重放返回原结果，异参返回 409';
COMMENT ON COLUMN idempotency_record.scope IS '幂等作用域，形如 segment:<井号>、sample:<井号>、void:<井号>、switch:<井号>、decision:<申请id>';
COMMENT ON COLUMN idempotency_record.request_id IS '客户端请求编号';
COMMENT ON COLUMN idempotency_record.payload_hash IS '请求参数规范化后的 SHA-256 摘要（十六进制）';
COMMENT ON COLUMN idempotency_record.entity_id IS '该请求产生或决定的实体主键';
COMMENT ON COLUMN idempotency_record.http_status IS '首次处理返回的 HTTP 状态码，重放时保持一致';
COMMENT ON COLUMN idempotency_record.error_message IS '首次处理返回的错误消息；NULL 表示成功';