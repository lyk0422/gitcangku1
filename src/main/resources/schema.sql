-- 固件灰度投放 schema（H2，MODE=MySQL）。时间均为服务器本地时区（Asia/Shanghai）。

CREATE TABLE IF NOT EXISTS device (
  device_id VARCHAR(64) NOT NULL COMMENT '设备唯一标识',
  model VARCHAR(64) NOT NULL COMMENT '设备型号，登记后不可修改',
  current_version VARCHAR(64) NOT NULL COMMENT '设备当前固件版本',
  bucket_no INT NOT NULL COMMENT '灰度分桶号，取值0~99，登记后不可修改',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '设备状态：ACTIVE正常可投放；QUARANTINED异常隔离中，不拉新任务且进行中任务成功回执不改版本',
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
  round_success INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入SUCCESS的任务数，重复回执不重复计数；隔离设备被拒回执不计入',
  round_failed INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入FAILED的任务数，重复回执不重复计数；隔离设备被拒回执不计入',
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
  status VARCHAR(16) NOT NULL COMMENT 'PENDING已拉取未开始；STARTED设备已开始刷写进行中；SUCCESS成功；FAILED失败；CANCELLED已取消',
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

CREATE TABLE IF NOT EXISTS device_quarantine_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '隔离记录ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  action VARCHAR(16) NOT NULL COMMENT '操作类型：QUARANTINE运维提交隔离；RELEASE不同运维确认解除',
  reason_code VARCHAR(64) NOT NULL COMMENT '原因代码：隔离时为上报异常原因，解除时为原因已消除的确认代码',
  expected_version VARCHAR(64) NOT NULL COMMENT '操作提交时的设备当前版本快照；隔离期间版本保持不变',
  operator VARCHAR(64) NOT NULL COMMENT '操作者工号；解除人必须与最近一次隔离提交人不同',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (id)
) COMMENT='设备隔离/解除历史，只增不改';

CREATE TABLE IF NOT EXISTS task_cancel_reason (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '取消原因记录ID',
  task_id BIGINT NOT NULL COMMENT '被取消的任务ID',
  release_id BIGINT NOT NULL COMMENT '任务所属发布单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  reason_code VARCHAR(64) NOT NULL COMMENT '取消原因代码：DEVICE_QUARANTINED设备隔离；RELEASE_CANCELLED发布单取消',
  detail VARCHAR(256) NOT NULL COMMENT '取消原因明细，含触发隔离的原因代码等上下文',
  operator VARCHAR(64) NOT NULL COMMENT '触发取消的操作者；发布单取消路径记为SYSTEM',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '取消时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_cancel_reason_task UNIQUE (task_id)
) COMMENT='任务不可变取消原因，任务取消时在同一事务写入，历史不可改';

CREATE TABLE IF NOT EXISTS rejected_receipt (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '被拒回执记录ID',
  task_id BIGINT NOT NULL COMMENT '提交回执的任务ID',
  release_id BIGINT NOT NULL COMMENT '任务所属发布单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '提交回执的设备ID',
  submitted_result VARCHAR(16) NOT NULL COMMENT '设备提交的回执结果：SUCCESS或FAILED',
  reject_code VARCHAR(64) NOT NULL COMMENT '拒绝原因代码：DEVICE_QUARANTINED设备隔离中，进行中任务回执不被受理',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '回执被拒时间',
  PRIMARY KEY (id)
) COMMENT='隔离门禁被拒回执记录，只增不改；解除隔离后历史记录仍不可改写';

CREATE TABLE IF NOT EXISTS idempotency_record (
  request_id VARCHAR(64) NOT NULL COMMENT '全局唯一请求ID',
  api VARCHAR(64) NOT NULL COMMENT '接口标识',
  fingerprint VARCHAR(256) NOT NULL COMMENT '请求参数指纹，同键异参返回409',
  response_body CLOB NOT NULL COMMENT '成功响应快照（JSON），用于同键同参重放',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (request_id)
) COMMENT='写操作幂等去重记录，失败不占键';
