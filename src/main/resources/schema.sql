-- 固件灰度投放 schema（H2，MODE=MySQL）。业务时刻统一存 UTC（ISO-8601 字符串），设备本地时刻由 UTC 偏移换算。

CREATE TABLE IF NOT EXISTS device (
  device_id VARCHAR(64) NOT NULL COMMENT '设备唯一标识',
  model VARCHAR(64) NOT NULL COMMENT '设备型号，登记后不可修改',
  current_version VARCHAR(64) NOT NULL COMMENT '设备当前固件版本',
  bucket_no INT NOT NULL COMMENT '灰度分桶号，取值0~99，登记后不可修改',
  utc_offset_minutes INT NOT NULL DEFAULT 0 COMMENT '设备时区相对UTC的偏移分钟，取值-720~840；本地分钟=UTC分钟+偏移',
  window_start_minute INT NOT NULL DEFAULT 0 COMMENT '每日维护窗口本地开始分钟，取值0~1439；与结束相等表示全天（仅旧客户端缺省登记）',
  window_end_minute INT NOT NULL DEFAULT 0 COMMENT '每日维护窗口本地结束分钟（左闭右开），取值0~1439；开始大于结束表示跨零点',
  device_version INT NOT NULL DEFAULT 1 COMMENT '设备乐观锁版本号，从1开始，偏移或窗口修改成功加一',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近窗口修订时间',
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
  respect_maintenance_window TINYINT NOT NULL DEFAULT 0 COMMENT '是否尊重设备维护窗口：1开启后窗口外拉取顺延，0保持原有拉取行为',
  monitor_round INT NOT NULL DEFAULT 1 COMMENT '当前监控轮次，从1开始，人工恢复后加一并清零统计',
  round_success INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入SUCCESS的任务数，重复回执不重复计数；顺延不计入样本',
  round_failed INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入FAILED的任务数，重复回执不重复计数；顺延不计入样本',
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

CREATE TABLE IF NOT EXISTS task_defer_state (
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID；与同发布单任务一一对应，任务可能尚未创建（仍在顺延期）',
  defer_count INT NOT NULL COMMENT '同一任务（同发布单同设备）累计顺延次数，窗口外拉取一次计一次，窗口修订与人工恢复均不清零',
  last_deferred_at_utc VARCHAR(40) NOT NULL COMMENT '最近顺延时刻，UTC，ISO-8601格式（如2026-09-24T07:00:00Z）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次顺延时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近顺延时间',
  PRIMARY KEY (release_id, device_id)
) COMMENT='任务顺延累计状态；只增不清零，窗口修订不改写';

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
