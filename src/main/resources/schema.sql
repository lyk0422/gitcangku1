-- 固件灰度投放 schema（H2，MODE=MySQL）。
-- 设备表 created_at/updated_at 为登记时刻；业务事件（暂停、恢复、分片接收、完整性判定）一律存 UTC ISO-8601 字符串。

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
  round_success INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入SUCCESS的任务数，重复回执不重复计数；INTEGRITY_FAILED不计入',
  round_failed INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入FAILED的任务数，重复回执不重复计数；INTEGRITY_FAILED不计入',
  active_model VARCHAR(64) NULL COMMENT 'ACTIVE或PAUSED时等于model，取消后置NULL；用于同型号至多一张未终结发布单的唯一约束',
  package_digest VARCHAR(64) NULL COMMENT '完整包摘要，64位小写十六进制SHA-256；NULL表示未登记分片清单的遗留发布单，不做完整性闸门',
  manifest_locked INT NOT NULL DEFAULT 0 COMMENT '分片摘要是否已锁定：0未锁定（首任务拉取前可整体替换），1已锁定不可修改',
  manifest_locked_at_utc VARCHAR(40) NULL COMMENT '摘要首次锁定时刻，UTC，ISO-8601格式；未锁定为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id)
) COMMENT='固件灰度发布单';

CREATE UNIQUE INDEX IF NOT EXISTS uk_release_active_model ON release_order (active_model);

CREATE TABLE IF NOT EXISTS release_shard (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分片登记ID',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  shard_index INT NOT NULL COMMENT '分片序号，从0开始连续递增，不可重复或缺口',
  shard_digest VARCHAR(64) NOT NULL COMMENT '分片摘要，64位小写十六进制SHA-256，格式固定',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_release_shard_index UNIQUE (release_id, shard_index)
) COMMENT='发布单分片摘要登记，序号0连续；发布单被任务拉取后整表冻结';

CREATE TABLE IF NOT EXISTS rollout_task (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  status VARCHAR(20) NOT NULL COMMENT 'PENDING分片接收中（遗留发布单为待回执）；INTEGRITY_FAILED完整性失败；INSTALLABLE分片齐全可安装；SUCCESS安装成功；FAILED安装失败；CANCELLED已取消',
  first_result VARCHAR(16) NULL COMMENT '首次安装回执结果（SUCCESS/FAILED），未回执为NULL；INTEGRITY_FAILED不写该字段',
  attempt_no INT NOT NULL DEFAULT 1 COMMENT '当前尝试代次，从1开始；INTEGRITY_FAILED后重新拉取加一，旧代次证据保留',
  failure_reason VARCHAR(32) NULL COMMENT '当前代次完整性失败原因码（IntegrityFailureReason），未失败为NULL',
  required_shards INT NOT NULL DEFAULT 0 COMMENT '当前代次要求分片数（拉取时快照）；0为遗留发布单',
  received_shards INT NOT NULL DEFAULT 0 COMMENT '当前代次已接收的不同分片序号数（实际值）',
  package_digest VARCHAR(64) NULL COMMENT '当前代次完整包摘要快照，拉取时固化；遗留发布单为NULL',
  attempt_finalized_at_utc VARCHAR(40) NULL COMMENT '当前代次完整性判定时刻（INSTALLABLE或INTEGRITY_FAILED），UTC，ISO-8601；接收中为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_task_release_device UNIQUE (release_id, device_id)
) COMMENT='设备投放任务，同设备同发布单最多一条；尝试代次历史见task_attempt';

CREATE TABLE IF NOT EXISTS task_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '尝试代次记录ID',
  task_id BIGINT NOT NULL COMMENT '所属任务ID',
  attempt_no INT NOT NULL COMMENT '代次号，从1开始，逐次重拉加一',
  release_id BIGINT NOT NULL COMMENT '代次建立时所属发布单ID（快照）',
  release_version INT NOT NULL COMMENT '代次建立时发布单版本号（快照，后续扩量/恢复不改写）',
  status VARCHAR(20) NOT NULL COMMENT 'RECEIVING接收中；INTEGRITY_FAILED完整性失败（终态）；INSTALLABLE可安装（终态，等待安装回执）；CANCELLED任务撤销（终态）',
  required_shards INT NOT NULL COMMENT '要求分片数（快照）',
  received_shards INT NOT NULL COMMENT '已接收的不同分片序号数（实际值）',
  package_digest VARCHAR(64) NOT NULL COMMENT '完整包摘要（快照）',
  aggregate_digest VARCHAR(64) NULL COMMENT '完整集合的聚合摘要（有序分片摘要拼接后SHA-256），仅终态判定时固化，接收中为NULL',
  failure_reason VARCHAR(32) NULL COMMENT '完整性失败原因码，仅INTEGRITY_FAILED时非NULL',
  created_at_utc VARCHAR(40) NOT NULL COMMENT '代次建立时刻，UTC，ISO-8601',
  finalized_at_utc VARCHAR(40) NULL COMMENT '完整性判定时刻，UTC，ISO-8601；接收中为NULL',
  PRIMARY KEY (id),
  CONSTRAINT uk_task_attempt_no UNIQUE (task_id, attempt_no)
) COMMENT='任务尝试代次历史，只增不改，重拉不影响旧代次';

CREATE TABLE IF NOT EXISTS task_shard_receipt (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分片接收证据ID',
  task_id BIGINT NOT NULL COMMENT '所属任务ID',
  attempt_no INT NOT NULL COMMENT '接收时的尝试代次',
  release_id BIGINT NOT NULL COMMENT '接收时所属发布单ID（快照）',
  release_version INT NOT NULL COMMENT '接收时发布单版本号（快照）',
  shard_index INT NOT NULL COMMENT '设备提交的分片序号，越界提交也原样固化为证据',
  expected_digest VARCHAR(64) NULL COMMENT '登记的期望分片摘要；越界序号没有期望摘要时为NULL',
  received_digest VARCHAR(64) NOT NULL COMMENT '设备实际提交的分片摘要，原样固化',
  digest_matched INT NOT NULL COMMENT '摘要是否匹配：1匹配，0不匹配',
  received_at_utc VARCHAR(40) NOT NULL COMMENT '接收时刻，UTC，ISO-8601',
  PRIMARY KEY (id),
  CONSTRAINT uk_task_attempt_shard UNIQUE (task_id, attempt_no, shard_index)
) COMMENT='分片接收证据，只增不改；同任务同代次同序号唯一，并发最多一笔成功';

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
  fingerprint VARCHAR(64) NOT NULL COMMENT '请求参数指纹（api+原始指纹的SHA-256十六进制），同键异参返回409',
  response_body CLOB NOT NULL COMMENT '成功响应快照（JSON），用于同键同参重放',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (request_id)
) COMMENT='写操作幂等去重记录，失败不占键';
