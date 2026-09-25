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
  status VARCHAR(16) NOT NULL COMMENT 'PENDING待回执；SUCCESS成功；FAILED失败；CANCELLED已取消；RELEASE_FROZEN冻结令冻结',
  first_result VARCHAR(16) NULL COMMENT '首次回执结果（SUCCESS/FAILED），未回执为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_task_release_device UNIQUE (release_id, device_id)
) COMMENT='设备投放任务，同设备同发布单最多一条';

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
  fingerprint VARCHAR(512) NOT NULL COMMENT '请求参数指纹，同键异参返回409',
  response_body CLOB NOT NULL COMMENT '成功响应快照（JSON），用于同键同参重放',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (request_id)
) COMMENT='写操作幂等去重记录，失败不占键';

-- 固件发布冻结令 schema（H2，MODE=MySQL）。窗口时间一律 UTC，左闭右开 [start_utc, end_utc)。

-- 冻结事务全局顺序闩：所有冻结/撤销/发布启动/拉取/回执/取消事务先锁该行，
-- 再按 ACTIVE 冻结令 id 升序加锁，形成“冻结先于业务写”的统一提交顺序，避免死锁。
CREATE TABLE IF NOT EXISTS freeze_guard_lock (
  id INT NOT NULL COMMENT '固定为1的单行顺序闩',
  dummy INT NOT NULL DEFAULT 0 COMMENT '占位字段',
  PRIMARY KEY (id)
) COMMENT='冻结相关写事务的全局顺序闩（单行表）';
INSERT INTO freeze_guard_lock (id, dummy)
SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM freeze_guard_lock);

CREATE TABLE IF NOT EXISTS freeze_order (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '冻结令ID',
  version INT NOT NULL COMMENT '冻结令版本号，从1开始；修订携带 expectedVersion 校验，成功后加一',
  status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE生效中（含窗口已过但未撤销），REVOKED已撤销（终态）',
  start_utc VARCHAR(40) NOT NULL COMMENT '生效窗口起点UTC，左闭，ISO-8601（如2026-09-26T00:00:00Z）',
  end_utc VARCHAR(40) NOT NULL COMMENT '生效窗口终点UTC，右开，必须晚于起点',
  scope_models VARCHAR(2048) NOT NULL COMMENT '硬件型号冻结范围快照，去重按字典序排序，逗号分隔；可空字符串',
  scope_release_ids VARCHAR(2048) NOT NULL COMMENT '发布单冻结范围快照，去重升序，逗号分隔；可空字符串',
  enforced_version INT NULL COMMENT '已执行开始冻结扫荡的版本；NULL表示当前版本尚未把命中PENDING任务转为RELEASE_FROZEN',
  revoked_at_utc VARCHAR(40) NULL COMMENT '撤销时刻UTC，未撤销为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近修订或撤销时间',
  PRIMARY KEY (id)
) COMMENT='固件发布冻结令，窗口UTC左闭右开，范围命中型号或发布单即冻结';

CREATE TABLE IF NOT EXISTS freeze_confirmer (
  name VARCHAR(64) NOT NULL COMMENT '紧急例外确认人姓名，登记后不可更名',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
  PRIMARY KEY (name)
) COMMENT='紧急例外已登记确认人，紧急放行须两名不同确认人';

CREATE TABLE IF NOT EXISTS freeze_emergency_exception (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '紧急例外记录ID',
  freeze_id BIGINT NOT NULL COMMENT '被该例外放行的冻结令ID',
  event_no VARCHAR(64) NOT NULL COMMENT '紧急事件号，发起例外时必填',
  confirmer1 VARCHAR(64) NOT NULL COMMENT '第一名已登记确认人',
  confirmer2 VARCHAR(64) NOT NULL COMMENT '第二名已登记确认人，必须与第一名不同且均已登记',
  operation VARCHAR(32) NOT NULL COMMENT '放行操作：RELEASE_START发布启动，TASK_PULL新任务拉取，BATCH_RELEASE_START批量启动',
  reference VARCHAR(128) NOT NULL COMMENT '放行对象引用（型号或设备ID/发布单ID），便于审计',
  request_id VARCHAR(64) NOT NULL COMMENT '触发放行的请求ID',
  created_at_utc VARCHAR(40) NOT NULL COMMENT '例外放行时刻UTC，ISO-8601',
  PRIMARY KEY (id)
) COMMENT='冻结窗口内紧急双人例外放行记录，历史不可改';

-- 任务在创建/完成时固化发布单快照，并在冻结开始时固化冻结令快照。
ALTER TABLE rollout_task ADD COLUMN IF NOT EXISTS release_version_at_create INT NULL
  COMMENT '任务创建时发布单版本快照；NULL为历史任务';
ALTER TABLE rollout_task ADD COLUMN IF NOT EXISTS release_version_at_complete INT NULL
  COMMENT '首次回执完成时发布单版本快照；未完成或被冻结/取消为NULL';
ALTER TABLE rollout_task ADD COLUMN IF NOT EXISTS frozen_freeze_id BIGINT NULL
  COMMENT '冻结该任务的冻结令ID；非RELEASE_FROZEN为NULL';
ALTER TABLE rollout_task ADD COLUMN IF NOT EXISTS frozen_freeze_version INT NULL
  COMMENT '冻结时刻冻结令版本快照';
ALTER TABLE rollout_task ADD COLUMN IF NOT EXISTS frozen_models VARCHAR(2048) NULL
  COMMENT '冻结时刻硬件型号范围快照，逗号分隔';
ALTER TABLE rollout_task ADD COLUMN IF NOT EXISTS frozen_release_ids VARCHAR(2048) NULL
  COMMENT '冻结时刻发布单范围快照，逗号分隔';
ALTER TABLE rollout_task ADD COLUMN IF NOT EXISTS frozen_start_utc VARCHAR(40) NULL
  COMMENT '冻结时刻冻结窗口起点UTC快照';
ALTER TABLE rollout_task ADD COLUMN IF NOT EXISTS frozen_end_utc VARCHAR(40) NULL
  COMMENT '冻结时刻冻结窗口终点UTC快照';
ALTER TABLE rollout_task ADD COLUMN IF NOT EXISTS frozen_at_utc VARCHAR(40) NULL
  COMMENT '任务被转为RELEASE_FROZEN的时刻UTC，ISO-8601';
ALTER TABLE rollout_task ADD COLUMN IF NOT EXISTS emergency_freeze_id BIGINT NULL
  COMMENT '紧急例外所放行的冻结令ID；其他冻结令开始时仍可冻结该任务，NULL为非例外任务';
