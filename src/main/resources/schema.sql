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
  status VARCHAR(16) NOT NULL COMMENT 'PENDING待回执；SUCCESS成功；FAILED失败；CANCELLED已取消',
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
  fingerprint VARCHAR(256) NOT NULL COMMENT '请求参数指纹，同键异参返回409',
  response_body CLOB NOT NULL COMMENT '成功响应快照（JSON），用于同键同参重放',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (request_id)
) COMMENT='写操作幂等去重记录，失败不占键';

CREATE TABLE IF NOT EXISTS rollback_plan (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '回退计划ID',
  plan_key VARCHAR(64) NOT NULL COMMENT '计划业务键，全局唯一',
  source_release_id BIGINT NOT NULL COMMENT '来源已结束（CANCELLED）投放发布单ID',
  target_version VARCHAR(64) NOT NULL COMMENT '目标旧固件版本',
  status VARCHAR(16) NOT NULL COMMENT 'ACTIVE波次执行中，PAUSED失败率自动暂停，COMPLETED全部到达目标（终态），CANCELLED已取消（终态）',
  current_hop INT NOT NULL COMMENT '当前执行跳号，从0开始；人工恢复不变，进入下一跳加一',
  max_hop INT NOT NULL COMMENT '计划内最大跳号（所有设备最长反向路径长度减1），取值0~4',
  sample_floor INT NOT NULL COMMENT '每跳失败率统计样本下限，取值2~100；本跳本轮样本达到下限才评估暂停',
  failure_threshold_percent INT NOT NULL COMMENT '每跳失败率阈值百分比，取值1~100；FAILED×100>=样本数×阈值时自动暂停',
  current_round INT NOT NULL DEFAULT 1 COMMENT '当前跳内波次轮次，从1开始，人工恢复加一，进入下一跳重置为1',
  round_success INT NOT NULL DEFAULT 0 COMMENT '当前（跳,轮）内首次SUCCESS回执的任务数',
  round_failed INT NOT NULL DEFAULT 0 COMMENT '当前（跳,轮）内首次FAILED回执的任务数',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_rollback_plan_key UNIQUE (plan_key)
) COMMENT='多跳版本回退计划';

CREATE TABLE IF NOT EXISTS rollback_plan_hop (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '路径行ID',
  plan_id BIGINT NOT NULL COMMENT '所属回退计划ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  hop_index INT NOT NULL COMMENT '跳号，从0开始，每设备路径长度取值1~5',
  expected_version VARCHAR(64) NOT NULL COMMENT '该跳开始时设备应处版本，创建时冻结',
  to_version VARCHAR(64) NOT NULL COMMENT '该跳目标版本，创建时冻结',
  source_release_id BIGINT NOT NULL COMMENT '该跳反向对应的正向投放发布单ID，创建时冻结',
  source_task_id BIGINT NOT NULL COMMENT '该跳反向对应的正向投放任务ID，创建时冻结',
  PRIMARY KEY (id),
  CONSTRAINT uk_rb_hop_plan_device_hop UNIQUE (plan_id, device_id, hop_index)
) COMMENT='回退计划设备逐跳冻结路径，历史不可改';

CREATE INDEX IF NOT EXISTS idx_rb_hop_plan_hop ON rollback_plan_hop (plan_id, hop_index);

CREATE TABLE IF NOT EXISTS rollback_task (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '回退波次任务ID',
  plan_id BIGINT NOT NULL COMMENT '所属回退计划ID',
  hop_index INT NOT NULL COMMENT '跳号，从0开始',
  round INT NOT NULL COMMENT '波次轮次，首轮为1，人工恢复生成新round时加一',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  expected_version VARCHAR(64) NOT NULL COMMENT '派发时冻结的设备应处版本，SUCCESS回执据此原子条件切换',
  to_version VARCHAR(64) NOT NULL COMMENT '派发时冻结的该跳目标版本',
  source_release_id BIGINT NOT NULL COMMENT '派发时冻结的来源正向投放发布单ID',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING待回执；SUCCESS成功；FAILED失败；CANCELLED已取消',
  first_result VARCHAR(16) NULL COMMENT '首次回执结果（SUCCESS/FAILED），未回执为NULL',
  receipt_key VARCHAR(64) NULL COMMENT '终结本任务的回执业务键，全局唯一；未回执为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_rb_task_plan_hop_round_device UNIQUE (plan_id, hop_index, round, device_id),
  CONSTRAINT uk_rb_task_receipt_key UNIQUE (receipt_key)
) COMMENT='回退波次任务，同设备同跳同轮最多一条；receiptKey全局唯一';

CREATE INDEX IF NOT EXISTS idx_rb_task_plan_hop_round ON rollback_task (plan_id, hop_index, round);

-- 设备任务占用登记：回退计划创建/回执/恢复与正向投放并发互斥，同设备同时只能进入一个冲突任务。
CREATE TABLE IF NOT EXISTS device_task_occupation (
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  scope VARCHAR(16) NOT NULL COMMENT '占用方类型：FORWARD正向投放；ROLLBACK回退计划',
  ref_id BIGINT NOT NULL COMMENT '占用方ID：正向发布单ID或回退计划ID',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用开始时间',
  PRIMARY KEY (device_id)
) COMMENT='设备冲突任务占用表，同设备同时至多一个未终结冲突任务，完成或取消后释放';
