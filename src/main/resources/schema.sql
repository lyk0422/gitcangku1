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
  shard_count INT NULL COMMENT '已登记分片清单的分片总数；NULL表示尚未登记分片摘要，此时不允许创建投放任务',
  full_digest VARCHAR(64) NULL COMMENT '完整包聚合摘要：按分片序号升序拼接各分片摘要后的SHA-256（小写十六进制64字符）；NULL表示未登记',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id)
) COMMENT='固件灰度发布单';

CREATE UNIQUE INDEX IF NOT EXISTS uk_release_active_model ON release_order (active_model);

CREATE TABLE IF NOT EXISTS rollout_task (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务尝试ID，每次拉取尝试一代，id即尝试代次内任务主键',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  attempt_no INT NOT NULL COMMENT '尝试代次，从1开始；完整性失败后重新拉取加一，旧代次记录保留不改写',
  status VARCHAR(20) NOT NULL COMMENT 'PENDING分片接收中；INSTALLABLE完整性核验通过可安装；INTEGRITY_FAILED完整性失败（禁止安装与成功回执，不计设备执行失败率）；SUCCESS安装成功；FAILED安装失败；CANCELLED已取消',
  first_result VARCHAR(16) NULL COMMENT '首次安装回执结果（SUCCESS/FAILED），未回执为NULL',
  aggregate_digest VARCHAR(64) NULL COMMENT '可安装判定时刻固化的聚合摘要（小写十六进制SHA-256），未判定为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_task_release_device_attempt UNIQUE (release_id, device_id, attempt_no)
) COMMENT='设备投放任务尝试，同设备同发布单每代尝试一条，历史代次只增不改';

CREATE TABLE IF NOT EXISTS release_shard (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分片登记记录ID',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  shard_no INT NOT NULL COMMENT '分片序号，从0开始连续',
  shard_digest VARCHAR(64) NOT NULL COMMENT '分片摘要，小写十六进制SHA-256（64字符），登记后不可修改',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_shard_release_no UNIQUE (release_id, shard_no)
) COMMENT='发布版本分片摘要清单，按发布单整体登记，一经登记且被任务拉取即不可修改';

CREATE TABLE IF NOT EXISTS shard_receipt (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分片接收证据ID',
  task_id BIGINT NOT NULL COMMENT '接收该分片的任务尝试ID（rollout_task.id）',
  attempt_no INT NOT NULL COMMENT '尝试代次，固化自任务当时代次',
  release_id BIGINT NOT NULL COMMENT '发布单ID快照，发布单撤回不影响证据',
  release_version INT NOT NULL COMMENT '接收时刻发布单版本号快照（扩量/恢复会推高版本）',
  shard_no INT NOT NULL COMMENT '分片序号，从0开始',
  shard_digest VARCHAR(64) NOT NULL COMMENT '设备实际提交的分片摘要（小写十六进制64字符），原样固化',
  received_at_utc VARCHAR(40) NOT NULL COMMENT '接收时刻，UTC，ISO-8601格式（如2026-09-26T07:00:00Z）',
  request_id VARCHAR(64) NOT NULL COMMENT '提交该批分片的请求ID',
  PRIMARY KEY (id),
  CONSTRAINT uk_receipt_task_attempt_shard UNIQUE (task_id, attempt_no, shard_no)
) COMMENT='分片接收证据，只增不改；同任务同代次同序号并发提交至多一笔成功';

CREATE TABLE IF NOT EXISTS integrity_event (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '完整性判定事件ID',
  task_id BIGINT NOT NULL COMMENT '任务尝试ID',
  attempt_no INT NOT NULL COMMENT '尝试代次',
  release_id BIGINT NOT NULL COMMENT '发布单ID快照',
  release_version INT NOT NULL COMMENT '判定时刻发布单版本号快照',
  result VARCHAR(20) NOT NULL COMMENT '判定结果：INSTALLABLE核验通过可安装；INTEGRITY_FAILED核验失败',
  reason VARCHAR(48) NOT NULL COMMENT '失败原因码：SHARD_OUT_OF_RANGE/DUPLICATE_SHARD/SHARD_DIGEST_MISMATCH/FULL_DIGEST_MISMATCH；INSTALLABLE时为OK',
  required_shard_count INT NOT NULL COMMENT '要求分片总数（发布单登记值）',
  received_shard_count INT NOT NULL COMMENT '判定时实际接收分片数（按序号去重计）',
  missing_shard_count INT NOT NULL COMMENT '缺失分片数=要求-实际覆盖序号数',
  missing_shards VARCHAR(512) NOT NULL COMMENT '缺失分片序号逗号分隔升序（如0,3），无缺失为空串',
  duplicate_shards VARCHAR(512) NOT NULL COMMENT '重复提交的分片序号逗号分隔升序，无重复为空串',
  mismatched_shards VARCHAR(512) NOT NULL COMMENT '摘要不匹配的分片序号逗号分隔升序，无不匹配为空串',
  expected_full_digest VARCHAR(64) NOT NULL COMMENT '登记的完整包聚合摘要快照',
  actual_full_digest VARCHAR(64) NULL COMMENT '按接收分片升序聚合的实际摘要；集合不完整无法聚合时为NULL',
  decided_at_utc VARCHAR(40) NOT NULL COMMENT '判定时刻，UTC，ISO-8601格式（如2026-09-26T07:00:00Z）',
  PRIMARY KEY (id)
) COMMENT='完整性失败与可安装判定事件，只增不改，固化发布版本、分片摘要聚合结果与时刻';

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
  fingerprint CLOB NOT NULL COMMENT '请求参数指纹，同键异参返回409；分片清单指纹较长故用CLOB',
  response_body CLOB NOT NULL COMMENT '成功响应快照（JSON），用于同键同参重放',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (request_id)
) COMMENT='写操作幂等去重记录，失败不占键';
