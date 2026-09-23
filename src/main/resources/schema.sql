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

-- 多跳版本回退计划：针对一次已结束（CANCELLED）投放，按设备成功投放历史构造连续反向路径。
CREATE TABLE IF NOT EXISTS rollback_plan (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '回退计划ID',
  plan_key VARCHAR(64) NOT NULL COMMENT '计划业务键，全局唯一，由调用方提供',
  source_release_id BIGINT NOT NULL COMMENT '来源投放发布单ID，必须为已结束（CANCELLED）状态',
  model VARCHAR(64) NOT NULL COMMENT '目标设备型号，与来源发布单一一致',
  target_version VARCHAR(64) NOT NULL COMMENT '回退目标旧固件版本',
  status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE波次执行中，PAUSED失败率自动暂停，COMPLETED全部设备到达目标（终态），CANCELLED已取消（终态）',
  sample_floor INT NOT NULL COMMENT '每跳失败率统计样本下限，取值2~100；该跳本轮样本数达到下限才评估暂停',
  failure_threshold_percent INT NOT NULL COMMENT '每跳失败率阈值百分比，取值1~100；FAILED×100>=样本数×阈值时自动暂停',
  monitor_round INT NOT NULL DEFAULT 1 COMMENT '当前监控轮次，从1开始，人工恢复后加一并清零本轮统计',
  paused_hop_index INT NULL COMMENT '当前暂停所在跳次（1~5）；非PAUSED时为NULL',
  round_success INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入SUCCESS的回跳任务数，重复回执不重复计数',
  round_failed INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入FAILED的回跳任务数，重复回执不重复计数',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_rollback_plan_key UNIQUE (plan_key)
) COMMENT='多跳版本回退计划';

CREATE TABLE IF NOT EXISTS rollback_plan_device (
  plan_id BIGINT NOT NULL COMMENT '所属回退计划ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  hop_count INT NOT NULL COMMENT '该设备反向路径跳数，取值1~5，不同设备可不同',
  active_device_id VARCHAR(64) NULL COMMENT '计划非终态（ACTIVE/PAUSED）时等于device_id，终态置NULL；用于设备同时只参与一个未终结回退计划的唯一约束',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (plan_id, device_id),
  CONSTRAINT uk_active_plan_device UNIQUE (active_device_id)
) COMMENT='回退计划设备成员与路径长度快照';

CREATE TABLE IF NOT EXISTS rollback_plan_hop (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '冻结跳定义ID',
  plan_id BIGINT NOT NULL COMMENT '所属回退计划ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  hop_index INT NOT NULL COMMENT '跳次，从1开始，按反向路径顺序编号',
  expected_version VARCHAR(64) NOT NULL COMMENT '本跳冻结的设备期望起始版本（回退前版本）',
  target_version VARCHAR(64) NOT NULL COMMENT '本跳冻结的目标版本（回退后版本）',
  source_release_id BIGINT NOT NULL COMMENT '本跳逆向对应的原正向投放发布单ID（来源投放记录）',
  PRIMARY KEY (id),
  CONSTRAINT uk_plan_hop UNIQUE (plan_id, device_id, hop_index)
) COMMENT='回退计划逐跳冻结定义：期望版本、目标版本与来源投放记录，创建后不可改';

CREATE TABLE IF NOT EXISTS rollback_hop_task (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '回跳任务ID',
  receipt_key VARCHAR(64) NOT NULL COMMENT '回执凭证，派发时生成，全局唯一；回执必须原样携带，不匹配返回409',
  plan_id BIGINT NOT NULL COMMENT '所属回退计划ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  hop_index INT NOT NULL COMMENT '跳次，从1开始；同一设备仅上一跳SUCCESS后才派发下一跳',
  round_no INT NOT NULL COMMENT '该任务所属监控轮次；人工恢复后未成功设备在新轮次生成新任务',
  expected_version VARCHAR(64) NOT NULL COMMENT '本跳期望起始版本快照，创建后冻结',
  target_version VARCHAR(64) NOT NULL COMMENT '本跳目标版本快照，创建后冻结',
  source_release_id BIGINT NOT NULL COMMENT '本跳逆向对应的原正向投放发布单ID快照',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING待回执；SUCCESS成功（原子切换设备版本）；FAILED失败（保留设备版本）；CANCELLED已取消',
  first_result VARCHAR(16) NULL COMMENT '首次回执结果（SUCCESS/FAILED），未回执为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '派发时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_hop_receipt_key UNIQUE (receipt_key),
  CONSTRAINT uk_hop_attempt UNIQUE (plan_id, device_id, hop_index, round_no)
) COMMENT='回退计划逐跳派发任务，同设备同跳同一轮次至多一条';

CREATE TABLE IF NOT EXISTS rollback_plan_pause_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '暂停记录ID',
  plan_id BIGINT NOT NULL COMMENT '所属回退计划ID',
  monitor_round INT NOT NULL COMMENT '触发暂停的监控轮次',
  hop_index INT NOT NULL COMMENT '触发暂停的跳次',
  trigger_hop_task_id BIGINT NOT NULL COMMENT '触发暂停的回执回跳任务ID',
  success_count INT NOT NULL COMMENT '暂停时刻该跳本轮成功样本数',
  failed_count INT NOT NULL COMMENT '暂停时刻该跳本轮失败样本数',
  paused_at_utc VARCHAR(40) NOT NULL COMMENT '暂停时刻，UTC，ISO-8601格式（如2026-09-22T07:00:00Z）',
  PRIMARY KEY (id),
  CONSTRAINT uk_plan_pause_round UNIQUE (plan_id, monitor_round)
) COMMENT='回退计划自动暂停记录，每轮至多一条，历史不可改';
