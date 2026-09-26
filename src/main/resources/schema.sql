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
  status VARCHAR(16) NOT NULL COMMENT 'PENDING待分片接收/待回执；INSTALLABLE分片核验通过可安装；SUCCESS成功；FAILED失败；INTEGRITY_FAILED分片完整性失败；CANCELLED已取消',
  first_result VARCHAR(16) NULL COMMENT '首次回执结果（SUCCESS/FAILED），未回执为NULL',
  attempt INT NOT NULL DEFAULT 1 COMMENT '分片接收尝试代次，从1开始；INTEGRITY_FAILED后重新拉取加一并回到PENDING，旧代次证据保留',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_task_release_device UNIQUE (release_id, device_id)
) COMMENT='设备投放任务，同设备同发布单最多一条';

CREATE TABLE IF NOT EXISTS release_manifest (
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  firmware_version VARCHAR(64) NOT NULL COMMENT '目标固件版本快照，登记时固化，后续发布单变更不改写',
  chunk_count INT NOT NULL COMMENT '分片总数，>=1，分片序号为0~chunk_count-1连续',
  package_digest VARCHAR(64) NOT NULL COMMENT '完整包聚合摘要，64位小写十六进制SHA-256，等于按序号拼接全部分片摘要后的SHA-256',
  registered_at_utc VARCHAR(40) NOT NULL COMMENT '最近一次登记时刻，UTC，ISO-8601毫秒精度（如2026-09-26T07:00:00.000Z）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (release_id)
) COMMENT='发布版本分片清单；任一任务拉取后锁定不可修改';

CREATE TABLE IF NOT EXISTS release_manifest_chunk (
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  chunk_index INT NOT NULL COMMENT '分片序号，从0开始连续无缺口',
  digest VARCHAR(64) NOT NULL COMMENT '该序号分片摘要，64位小写十六进制SHA-256',
  PRIMARY KEY (release_id, chunk_index)
) COMMENT='发布版本分片摘要明细，按序号规范排序';

CREATE TABLE IF NOT EXISTS task_chunk_receipt (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '接收记录ID',
  task_id BIGINT NOT NULL COMMENT '任务ID',
  attempt INT NOT NULL COMMENT '接收时的尝试代次；重新拉取后的新代次另起记录，旧记录不改写',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID快照',
  firmware_version VARCHAR(64) NOT NULL COMMENT '接收时固化的目标固件版本',
  chunk_index INT NOT NULL COMMENT '分片序号',
  digest VARCHAR(64) NOT NULL COMMENT '设备上报的接收摘要，64位小写十六进制',
  received_at_utc VARCHAR(40) NOT NULL COMMENT '接收时刻，UTC，ISO-8601毫秒精度',
  PRIMARY KEY (id),
  CONSTRAINT uk_chunk_task_attempt_index UNIQUE (task_id, attempt, chunk_index)
) COMMENT='分片接收证据，只增不改；同任务同代次同序号最多一条';

CREATE TABLE IF NOT EXISTS task_integrity_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '核验记录ID',
  task_id BIGINT NOT NULL COMMENT '任务ID',
  attempt INT NOT NULL COMMENT '核验时的尝试代次',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID快照',
  firmware_version VARCHAR(64) NOT NULL COMMENT '核验时固化的目标固件版本',
  result VARCHAR(16) NOT NULL COMMENT '核验结果：INSTALLABLE可安装；INTEGRITY_FAILED完整性失败',
  reason VARCHAR(32) NULL COMMENT '失败原因：CHUNK_MISSING缺失/CHUNK_DUPLICATE重复/CHUNK_DIGEST_MISMATCH分片摘要不匹配/PACKAGE_DIGEST_MISMATCH聚合摘要不匹配；成功为NULL',
  received_count INT NOT NULL COMMENT '判定时该代次已接收分片数',
  required_count INT NOT NULL COMMENT '清单要求的分片总数',
  computed_package_digest VARCHAR(64) NULL COMMENT '按已接收完整集合计算的聚合摘要；集合不完整无法计算时为NULL',
  expected_package_digest VARCHAR(64) NOT NULL COMMENT '清单登记的完整包聚合摘要',
  decided_at_utc VARCHAR(40) NOT NULL COMMENT '判定时刻，UTC，ISO-8601毫秒精度',
  PRIMARY KEY (id)
) COMMENT='分片完整性判定记录，只增不改；每次可安装判定与完整性失败各落一条';

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
