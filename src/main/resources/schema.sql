-- 固件灰度投放 schema（H2，MODE=MySQL）。
-- created_at/updated_at 为数据库当前时间戳；业务证据时刻（暂停、恢复、分片接收与完整性判定）
-- 一律使用独立的 *_at_utc 列，保存 UTC ISO-8601 字符串（如 2026-09-28T07:00:00Z）。

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
  status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE投放中，PAUSED失败率自动暂停，CANCELLED已撤回（终态）',
  sample_floor INT NOT NULL COMMENT '失败率统计样本下限，取值2~100；本轮样本数达到下限才评估暂停',
  failure_threshold_percent INT NOT NULL COMMENT '失败率阈值百分比，取值1~100；FAILED×100>=样本数×阈值时自动暂停',
  monitor_round INT NOT NULL DEFAULT 1 COMMENT '当前监控轮次，从1开始，人工恢复后加一并清零统计',
  round_success INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次安装回执SUCCESS的任务数，重复回执不重复计数；完整性失败不计入',
  round_failed INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次安装回执FAILED的任务数，重复回执不重复计数；完整性失败不计入',
  package_digest VARCHAR(64) NOT NULL COMMENT '完整包摘要，64位小写十六进制SHA-256；须等于按分片序号排序的分片摘要换行拼接后再求SHA-256',
  fragment_count INT NOT NULL COMMENT '登记分片数量，取值1~1024，分片序号必须为0~count-1连续',
  active_model VARCHAR(64) NULL COMMENT 'ACTIVE或PAUSED时等于model，撤回后置NULL；用于同型号至多一张未终结发布单的唯一约束',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id)
) COMMENT='固件灰度发布单';

CREATE UNIQUE INDEX IF NOT EXISTS uk_release_active_model ON release_order (active_model);

CREATE TABLE IF NOT EXISTS release_fragment (
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  seq_no INT NOT NULL COMMENT '分片序号，从0开始连续，0~分片数量-1',
  digest VARCHAR(64) NOT NULL COMMENT '分片摘要，64位小写十六进制SHA-256',
  PRIMARY KEY (release_id, seq_no)
) COMMENT='发布分片登记，按(release_id,seq_no)排序即为规范顺序；发布单被任务拉取后不可修改';

CREATE TABLE IF NOT EXISTS rollout_task (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  status VARCHAR(20) NOT NULL COMMENT 'PENDING分片接收中；INSTALLABLE分片完整可安装；INTEGRITY_FAILED完整性失败；SUCCESS安装成功；FAILED安装失败；CANCELLED已撤销',
  attempt INT NOT NULL DEFAULT 1 COMMENT '接收尝试代次，从1开始；仅完整性失败后重新拉取加一，旧代次证据保留',
  first_result VARCHAR(16) NULL COMMENT '首次安装回执结果（SUCCESS/FAILED），未回执为NULL；完整性失败不写该字段',
  integrity_reason VARCHAR(64) NULL COMMENT '最近一次完整性失败原因代码（如MISMATCH_DIGEST），PENDING/可安装时为NULL',
  installable_at_utc VARCHAR(40) NULL COMMENT '最近一次判定可安装的UTC时刻ISO-8601；从未可安装为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_task_release_device UNIQUE (release_id, device_id)
) COMMENT='设备投放任务，同设备同发布单最多一条；完整性流转与安装回执均按代次留证';

CREATE TABLE IF NOT EXISTS task_fragment_receipt (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '接收证据ID',
  task_id BIGINT NOT NULL COMMENT '所属任务ID',
  attempt INT NOT NULL COMMENT '接收尝试代次，重新拉取后旧代次证据不删除不改写',
  seq_no INT NOT NULL COMMENT '分片序号，从0开始',
  received_digest VARCHAR(64) NOT NULL COMMENT '设备提交的接收摘要，64位小写十六进制SHA-256',
  expected_digest VARCHAR(64) NOT NULL COMMENT '登记的期望分片摘要快照，64位小写十六进制SHA-256',
  release_version INT NOT NULL COMMENT '接收时刻发布单版本号快照，后续扩量/撤回不改写',
  received_at_utc VARCHAR(40) NOT NULL COMMENT '接收时刻，UTC，ISO-8601格式（如2026-09-28T07:00:00Z）',
  request_id VARCHAR(64) NOT NULL COMMENT '提交该分片的请求ID，用于追溯幂等裁决',
  PRIMARY KEY (id),
  CONSTRAINT uk_task_attempt_seq UNIQUE (task_id, attempt, seq_no)
) COMMENT='任务分片接收证据，同任务同代次同序号唯一（并发最多一笔写入），只增不改';

CREATE TABLE IF NOT EXISTS task_integrity_event (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '事件ID',
  task_id BIGINT NOT NULL COMMENT '所属任务ID',
  attempt INT NOT NULL COMMENT '接收尝试代次，旧代次事件随代次永久保留',
  event_type VARCHAR(20) NOT NULL COMMENT 'RECEIVED逐片接收；INTEGRITY_FAILED完整性判定失败；INSTALLABLE完整性判定通过可安装',
  release_id BIGINT NOT NULL COMMENT '事件时刻所属发布单ID快照',
  release_version INT NOT NULL COMMENT '事件时刻发布单版本号快照，后续撤回或重拉不改写',
  seq_no INT NULL COMMENT 'RECEIVED事件对应分片序号；完整性判定事件为NULL',
  expected_digest VARCHAR(64) NULL COMMENT 'RECEIVED事件的期望分片摘要快照；判定事件为NULL',
  received_digest VARCHAR(64) NULL COMMENT 'RECEIVED事件设备实际提交摘要；判定事件为NULL',
  aggregate_expected VARCHAR(64) NULL COMMENT '判定时刻登记的完整包摘要（聚合期望值）；RECEIVED事件为NULL',
  aggregate_actual VARCHAR(64) NULL COMMENT '判定时刻按已接收分片聚合出的实际摘要；RECEIVED事件为NULL',
  reason VARCHAR(64) NULL COMMENT 'INTEGRITY_FAILED原因代码：MISMATCH_DIGEST摘要不匹配/ DUPLICATE_DIGEST重复序号异摘要/AGGREGATE_MISMATCH聚合不符；其余事件为NULL',
  created_at_utc VARCHAR(40) NOT NULL COMMENT '事件时刻，UTC，ISO-8601格式（如2026-09-28T07:00:00Z）',
  PRIMARY KEY (id)
) COMMENT='任务完整性事件流水，只增不改；查询时间区间左闭右开';

CREATE INDEX IF NOT EXISTS idx_integrity_event_task ON task_integrity_event (task_id, id);

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
