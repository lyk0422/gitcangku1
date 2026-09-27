-- 固件灰度投放 schema（H2，MODE=MySQL）。时间均为服务器本地时区（Asia/Shanghai）。

CREATE TABLE IF NOT EXISTS device (
  device_id VARCHAR(64) NOT NULL COMMENT '设备唯一标识',
  model VARCHAR(64) NOT NULL COMMENT '设备型号，登记后不可修改',
  current_version VARCHAR(64) NOT NULL COMMENT '设备当前固件版本',
  bucket_no INT NOT NULL COMMENT '灰度分桶号，取值0~99，登记后不可修改',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
  PRIMARY KEY (device_id)
) COMMENT='设备登记表';

CREATE TABLE IF NOT EXISTS release_order (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '发布单ID',
  version INT NOT NULL COMMENT '发布单版本号，从1开始，每次扩量或人工恢复成功加一',
  model VARCHAR(64) NOT NULL COMMENT '目标设备型号',
  from_version VARCHAR(64) NOT NULL COMMENT '来源固件版本',
  to_version VARCHAR(64) NOT NULL COMMENT '目标固件版本，必须与来源版本不同',
  ratio INT NOT NULL COMMENT '投放比例，取值0~100，只增不减',
  status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE投放中，PAUSED失败率自动暂停，CANCELLED已取消（终态）',
  sample_floor INT NOT NULL COMMENT '失败率统计样本下限，取值2~100；本轮样本数达到下限才评估暂停',
  failure_threshold_percent INT NOT NULL COMMENT '失败率阈值百分比，取值1~100；FAILED×100>=样本数×阈值时自动暂停',
  monitor_round INT NOT NULL DEFAULT 1 COMMENT '当前监控轮次，从1开始，人工恢复后加一并清零统计',
  round_success INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入SUCCESS的任务数，重复回执不重复计数',
  round_failed INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入FAILED的任务数，重复回执不重复计数',
  active_model VARCHAR(64) NULL COMMENT 'ACTIVE或PAUSED时等于model，取消后置NULL；用于同型号至多一张未终结发布单的唯一约束',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id)
) COMMENT='固件灰度发布单';

CREATE UNIQUE INDEX IF NOT EXISTS uk_release_active_model ON release_order (active_model);

CREATE TABLE IF NOT EXISTS rollout_task (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING待接收分片或待回执；INSTALLABLE完整性核验通过可安装；INTEGRITY_FAILED完整性失败（终态，禁止安装与成功回执）；SUCCESS成功；FAILED失败；CANCELLED已取消',
  first_result VARCHAR(16) NULL COMMENT '首次回执结果（SUCCESS/FAILED），未回执为NULL',
  current_attempt INT NOT NULL DEFAULT 1 COMMENT '当前尝试代次，从1开始，重新拉取加一',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_task_release_device UNIQUE (release_id, device_id)
) COMMENT='设备投放任务，同设备同发布单最多一条';

CREATE TABLE IF NOT EXISTS release_manifest (
  release_id BIGINT NOT NULL COMMENT '所属发布单ID，一个发布单至多一份清单',
  chunk_count INT NOT NULL COMMENT '分片总数，序号从0连续到chunk_count-1',
  aggregate_digest VARCHAR(64) NOT NULL COMMENT '完整包聚合摘要，64位小写十六进制SHA-256（按序号拼接分片摘要后计算）',
  registered_at_utc VARCHAR(40) NOT NULL COMMENT '登记时刻，UTC，ISO-8601格式（如2026-09-22T07:00:00Z）',
  PRIMARY KEY (release_id)
) COMMENT='发布版本分片清单；一经有任务拉取即不可修改';

CREATE TABLE IF NOT EXISTS release_manifest_chunk (
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  chunk_index INT NOT NULL COMMENT '分片序号，从0连续',
  digest VARCHAR(64) NOT NULL COMMENT '分片摘要，64位小写十六进制SHA-256',
  PRIMARY KEY (release_id, chunk_index)
) COMMENT='发布版本分片摘要明细，按序号升序为规范排序';

CREATE TABLE IF NOT EXISTS task_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '尝试记录ID',
  task_id BIGINT NOT NULL COMMENT '任务ID',
  attempt_no INT NOT NULL COMMENT '尝试代次，从1开始，重新拉取建立新代次',
  release_id BIGINT NOT NULL COMMENT '固化：代次建立时所属发布单ID',
  to_version VARCHAR(64) NOT NULL COMMENT '固化：代次建立时发布单目标版本',
  expected_aggregate VARCHAR(64) NULL COMMENT '固化：代次建立时登记的聚合摘要；发布单无分片清单时为NULL',
  required_count INT NOT NULL DEFAULT 0 COMMENT '固化：要求的分片总数；无分片清单时为0',
  received_count INT NOT NULL DEFAULT 0 COMMENT '本代次已接收分片数（去重后）',
  verdict VARCHAR(20) NULL COMMENT '完整性判定结果：INSTALLABLE可安装；INTEGRITY_FAILED完整性失败；未判定为NULL',
  verdict_reason VARCHAR(256) NULL COMMENT '判定原因（缺失/重复/摘要不匹配/聚合不匹配及差额），未判定为NULL',
  actual_aggregate VARCHAR(64) NULL COMMENT '判定时按已接收分片计算的聚合摘要，未判定为NULL',
  decided_at_utc VARCHAR(40) NULL COMMENT '判定时刻，UTC，ISO-8601格式；未判定为NULL',
  created_at_utc VARCHAR(40) NOT NULL COMMENT '代次建立时刻，UTC，ISO-8601格式',
  PRIMARY KEY (id),
  CONSTRAINT uk_attempt_task_no UNIQUE (task_id, attempt_no)
) COMMENT='任务尝试代次，判定证据固化后不可改，旧代次失败记录保留';

CREATE TABLE IF NOT EXISTS task_chunk_receipt (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '接收记录ID',
  task_id BIGINT NOT NULL COMMENT '任务ID',
  attempt_no INT NOT NULL COMMENT '接收时任务所处尝试代次',
  chunk_index INT NOT NULL COMMENT '分片序号',
  digest VARCHAR(64) NOT NULL COMMENT '设备上报的分片摘要',
  expected_digest VARCHAR(64) NOT NULL COMMENT '固化：接收时登记的分片摘要',
  matched BOOLEAN NOT NULL COMMENT '上报摘要与登记摘要是否一致',
  received_at_utc VARCHAR(40) NOT NULL COMMENT '接收时刻，UTC，ISO-8601格式',
  PRIMARY KEY (id),
  CONSTRAINT uk_receipt_task_attempt_chunk UNIQUE (task_id, attempt_no, chunk_index)
) COMMENT='分片接收证据，同任务同代次同序号最多一笔，历史不可改';

CREATE TABLE IF NOT EXISTS release_pause_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '暂停记录ID',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  monitor_round INT NOT NULL COMMENT '触发暂停的监控轮次',
  trigger_task_id BIGINT NOT NULL COMMENT '触发暂停的回执任务ID',
  success_count INT NOT NULL COMMENT '暂停时刻本轮成功样本数',
  failed_count INT NOT NULL COMMENT '暂停时刻本轮失败样本数',
  paused_at_utc VARCHAR(40) NOT NULL COMMENT '暂停时刻，UTC，ISO-8601格式（如2026-09-22T07:00:00Z）',
  PRIMARY KEY (id),
  CONSTRAINT uk_pause_release_round UNIQUE (release_id, monitor_round)
) COMMENT='发布单自动暂停记录，每轮至多一条，历史不可改';

CREATE TABLE IF NOT EXISTS release_resume_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '恢复记录ID',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  new_round INT NOT NULL COMMENT '本次恢复开启的新监控轮次',
  reason VARCHAR(256) NOT NULL COMMENT '人工恢复原因',
  resumed_at_utc VARCHAR(40) NOT NULL COMMENT '恢复时刻，UTC，ISO-8601格式（如2026-09-22T07:00:00Z）',
  PRIMARY KEY (id)
) COMMENT='发布单人工恢复记录，历史不可改';

CREATE TABLE IF NOT EXISTS idempotency_record (
  request_id VARCHAR(64) NOT NULL COMMENT '全局唯一请求ID',
  api VARCHAR(64) NOT NULL COMMENT '接口标识',
  fingerprint VARCHAR(256) NOT NULL COMMENT '请求参数指纹，同键异参返回409',
  response_body CLOB NOT NULL COMMENT '成功响应快照（JSON），用于同键同参重放',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (request_id)
) COMMENT='写操作幂等去重记录，失败不占键';
