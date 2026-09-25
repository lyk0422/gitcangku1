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
  status VARCHAR(16) NOT NULL COMMENT 'PENDING待回执；SUCCESS成功；FAILED失败；CANCELLED已取消；RELEASE_FROZEN冻结令冻结中',
  first_result VARCHAR(16) NULL COMMENT '首次回执结果（SUCCESS/FAILED），未回执为NULL',
  from_version VARCHAR(64) NULL COMMENT '创建时发布快照：来源固件版本',
  to_version VARCHAR(64) NULL COMMENT '创建时发布快照：目标固件版本',
  receipt_from_version VARCHAR(64) NULL COMMENT '完成时发布快照：来源固件版本，未回执为NULL',
  receipt_to_version VARCHAR(64) NULL COMMENT '完成时发布快照：目标固件版本，未回执为NULL',
  freeze_order_id BIGINT NULL COMMENT '冻结该任务的冻结令ID，未冻结为NULL',
  freeze_snapshot CLOB NULL COMMENT '冻结时刻冻结令快照（JSON），解冻后清空',
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
  fingerprint VARCHAR(256) NOT NULL COMMENT '请求参数指纹，同键异参返回409',
  response_body CLOB NOT NULL COMMENT '成功响应快照（JSON），用于同键同参重放',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (request_id)
) COMMENT='写操作幂等去重记录，失败不占键';

CREATE TABLE IF NOT EXISTS freeze_approver (
  approver_id VARCHAR(64) NOT NULL COMMENT '确认人唯一标识',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
  PRIMARY KEY (approver_id)
) COMMENT='冻结紧急例外已登记确认人';

CREATE TABLE IF NOT EXISTS freeze_order (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '冻结令ID',
  version INT NOT NULL COMMENT '冻结令版本，从1开始，每次修订加一',
  freeze_key VARCHAR(64) NOT NULL COMMENT '冻结令幂等键，同键同参重放、异参409',
  models_csv VARCHAR(2048) NOT NULL COMMENT '规范化排序后的硬件型号集合，逗号分隔，空串表示未指定',
  release_ids_csv VARCHAR(2048) NOT NULL COMMENT '规范化排序后的发布单ID集合，逗号分隔，空串表示未指定',
  start_utc VARCHAR(40) NOT NULL COMMENT '生效窗口开始，UTC，ISO-8601格式，左闭',
  end_utc VARCHAR(40) NOT NULL COMMENT '生效窗口结束，UTC，ISO-8601格式，右开，必须晚于开始',
  status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE生效中，REVOKED已撤销（终态）',
  exception_incident_id VARCHAR(64) NULL COMMENT '紧急例外事件号，无例外为NULL',
  exception_approvers_csv VARCHAR(256) NULL COMMENT '紧急例外确认人，规范化排序逗号分隔，无例外为NULL',
  revoked_at_utc VARCHAR(40) NULL COMMENT '撤销时刻，UTC，ISO-8601格式，未撤销为NULL',
  revoke_affected_task_ids_csv VARCHAR(4096) NULL COMMENT '撤销时解冻的任务ID，逗号分隔，未撤销为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_freeze_order_key UNIQUE (freeze_key)
) COMMENT='固件发布冻结令，UTC左闭右开窗口内冻结命中范围的发布启动与任务拉取';

CREATE TABLE IF NOT EXISTS freeze_exception_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '例外放行记录ID',
  api VARCHAR(32) NOT NULL COMMENT '放行接口：release.create发布启动，task.pull任务拉取',
  incident_id VARCHAR(64) NOT NULL COMMENT '紧急例外事件号',
  approvers_csv VARCHAR(256) NOT NULL COMMENT '两名已登记确认人，规范化排序逗号分隔',
  ref VARCHAR(64) NOT NULL COMMENT '放行对象标识：发布单ID或设备ID',
  created_at_utc VARCHAR(40) NOT NULL COMMENT '放行时刻，UTC，ISO-8601格式',
  PRIMARY KEY (id)
) COMMENT='紧急例外放行记录，历史只增不改';

CREATE TABLE IF NOT EXISTS freeze_guard (
  id INT NOT NULL COMMENT '单行锁占位，恒为1',
  PRIMARY KEY (id)
) COMMENT='冻结裁决单行锁：冻结、撤销、发布启动、拉取在同一事务内先锁本行，按提交顺序裁决';

INSERT INTO freeze_guard (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM freeze_guard);
