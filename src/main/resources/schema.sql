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
  failure_reason VARCHAR(512) NULL COMMENT '失败原因，FAILED首次回执原样记录；SUCCESS或未回执为NULL',
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

CREATE TABLE IF NOT EXISTS device_quarantine_round (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '隔离轮次ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  target_version VARCHAR(64) NOT NULL COMMENT '本轮目标固件版本，即失败发布单的to_version',
  round_no INT NOT NULL COMMENT '同一设备同一目标版本的投放轮次序号，从1开始；解除隔离开新一轮',
  consecutive_failures INT NOT NULL COMMENT '本轮连续失败次数，取值0~2；失败重置为0，成功后不再参与计数',
  status VARCHAR(16) NOT NULL COMMENT 'TRACKING计数中；QUARANTINED已隔离（终态前不可拉取）；RELEASED已解除并开启新一轮',
  first_task_id BIGINT NULL COMMENT '本轮第一次失败任务ID，首次失败时固化',
  first_release_id BIGINT NULL COMMENT '本轮第一次失败所属发布单ID',
  first_reason VARCHAR(512) NULL COMMENT '本轮第一次失败原因，首次失败回执原样记录；回执未填为NULL',
  first_failed_at_utc VARCHAR(40) NULL COMMENT '本轮第一次失败时刻，UTC，ISO-8601；未失败为NULL',
  second_task_id BIGINT NULL COMMENT '本轮第二次失败任务ID，隔离提交时固化',
  second_release_id BIGINT NULL COMMENT '本轮第二次失败所属发布单ID',
  second_reason VARCHAR(512) NULL COMMENT '本轮第二次失败原因，隔离提交时固化；回执未填为NULL',
  second_failed_at_utc VARCHAR(40) NULL COMMENT '本轮第二次失败时刻，UTC，ISO-8601；未隔离为NULL',
  confirmed_by VARCHAR(64) NULL COMMENT '隔离确认人，取第二次失败回执提交操作人；未隔离为NULL',
  quarantined_at_utc VARCHAR(40) NULL COMMENT '隔离生效时刻，UTC，ISO-8601；未隔离为NULL',
  released_by VARCHAR(64) NULL COMMENT '解除隔离审核人，必须不同于confirmed_by；未解除为NULL',
  released_at_utc VARCHAR(40) NULL COMMENT '解除隔离时刻，UTC，ISO-8601；未解除为NULL',
  release_reason VARCHAR(256) NULL COMMENT '解除隔离审核原因；未解除为NULL',
  next_round_id BIGINT NULL COMMENT '解除时开启的下一轮记录ID；未解除为NULL',
  active_slot VARCHAR(129) NULL COMMENT '活跃槽位=设备ID|目标版本，仅TRACKING/QUARANTINED行有值；RELEASED置NULL。唯一索引保证同设备同版本至多一条活跃轮次，NULL多行不冲突',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本轮记录创建时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_quarantine_device_version_round UNIQUE (device_id, target_version, round_no)
) COMMENT='设备按目标固件版本维度的连续失败与隔离轮次；版本变化轮次独立，历史不重算、不删除';

CREATE UNIQUE INDEX IF NOT EXISTS uk_quarantine_active_slot ON device_quarantine_round (active_slot);

