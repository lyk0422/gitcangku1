-- 固件灰度投放 schema（H2，MODE=MySQL）。created_at/updated_at 为服务器本地时区（Asia/Shanghai），
-- 业务时刻（暂停/恢复/试启动/回滚）一律以 UTC ISO-8601 字符串存储，取自服务端可注入 Clock。

CREATE TABLE IF NOT EXISTS device (
  device_id VARCHAR(64) NOT NULL COMMENT '设备唯一标识',
  model VARCHAR(64) NOT NULL COMMENT '设备型号，登记后不可修改',
  current_version VARCHAR(64) NOT NULL COMMENT '设备当前固件版本（当前启动分区上的版本）',
  bucket_no INT NOT NULL COMMENT '灰度分桶号，取值0~99，登记后不可修改',
  active_partition VARCHAR(1) NOT NULL DEFAULT 'A' COMMENT '当前启动分区：A或B；双分区试启动确认后切换，回滚保持不变',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
  PRIMARY KEY (device_id)
) COMMENT='设备登记表';

CREATE TABLE IF NOT EXISTS release_order (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '发布单ID',
  version INT NOT NULL COMMENT '发布单版本号，从1开始，每次扩量或人工恢复成功加一',
  kind VARCHAR(16) NOT NULL DEFAULT 'GRAY' COMMENT '发布类型：GRAY普通灰度发布，DUAL_PARTITION双分区试启动发布',
  model VARCHAR(64) NOT NULL COMMENT '目标设备型号',
  from_version VARCHAR(64) NOT NULL COMMENT '来源固件版本',
  to_version VARCHAR(64) NOT NULL COMMENT '目标固件版本，必须与来源版本不同',
  ratio INT NOT NULL COMMENT '投放比例，取值0~100，只增不减',
  status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE投放中，PAUSED失败率自动暂停，CANCELLED已取消（终态）',
  sample_floor INT NOT NULL COMMENT '失败率统计样本下限，取值2~100；本轮样本数达到下限才评估暂停',
  failure_threshold_percent INT NOT NULL COMMENT '失败率阈值百分比，取值1~100；FAILED×100>=样本数×阈值时自动暂停',
  monitor_round INT NOT NULL DEFAULT 1 COMMENT '当前监控轮次，从1开始，人工恢复后加一并清零统计',
  round_success INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入成功终态的任务数（普通SUCCESS/双分区COMMITTED），重复回执不重复计数',
  round_failed INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入失败终态的任务数（普通FAILED/双分区ROLLED_BACK），重复回执不重复计数',
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
  status VARCHAR(16) NOT NULL COMMENT '普通任务：PENDING待回执、SUCCESS成功、FAILED失败、CANCELLED已取消；双分区任务：PENDING待试启动、TRIAL试启动中、COMMITTED已确认切换、ROLLED_BACK已回滚、CANCELLED已取消',
  first_result VARCHAR(16) NULL COMMENT '首次回执结果（SUCCESS/FAILED），未回执为NULL；双分区COMMITTED记SUCCESS，ROLLED_BACK记FAILED',
  attempt_no INT NOT NULL DEFAULT 1 COMMENT '投放尝试序号，同设备同发布单从1开始；双分区任务回滚后重试加一并产生新任务ID',
  target_partition VARCHAR(1) NULL COMMENT '试启动目标分区（A/B），仅双分区任务进入TRIAL时记录，之后不可改；非试启动任务为NULL',
  target_version VARCHAR(64) NULL COMMENT '试启动目标版本，仅双分区任务进入TRIAL时记录，之后不可改；非试启动任务为NULL',
  trial_started_at_utc VARCHAR(40) NULL COMMENT '试启动时刻，UTC，ISO-8601格式（如2026-09-22T07:00:00Z），由服务端时钟记录；非TRIAL任务为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_task_release_device UNIQUE (release_id, device_id, attempt_no)
) COMMENT='设备投放任务，同设备同发布单同尝试序号最多一条';

CREATE TABLE IF NOT EXISTS rollout_rollback_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '回滚记录ID',
  task_id BIGINT NOT NULL COMMENT '回滚的双分区任务ID',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  kept_partition VARCHAR(1) NOT NULL COMMENT '回滚后保留的旧启动分区（A/B）',
  kept_version VARCHAR(64) NOT NULL COMMENT '回滚后保留的旧固件版本',
  reason VARCHAR(16) NOT NULL COMMENT '回滚原因：EXPIRED试启动60秒内未收到健康确认，FAILED设备回报失败',
  rolled_back_at_utc VARCHAR(40) NOT NULL COMMENT '回滚时刻，UTC，ISO-8601格式（如2026-09-22T07:00:00Z）',
  PRIMARY KEY (id),
  CONSTRAINT uk_rollback_task UNIQUE (task_id)
) COMMENT='双分区试启动回滚记录，每个任务至多一条，历史不可改';

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
  fingerprint VARCHAR(512) NOT NULL COMMENT '请求身份指纹（操作、操作者、资源及全部参数的结构化编码），同键异参返回409',
  response_body CLOB NOT NULL COMMENT '成功响应快照（JSON），用于同键同参重放',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (request_id)
) COMMENT='写操作幂等去重记录，失败不占键';