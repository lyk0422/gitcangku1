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

CREATE TABLE IF NOT EXISTS firmware_version (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '固件版本ID',
  name VARCHAR(64) NOT NULL COMMENT '版本名，全局唯一',
  models VARCHAR(512) NOT NULL COMMENT '兼容型号集合，逗号分隔，至少一个',
  min_memory_mb INT NOT NULL COMMENT '最低内存要求，单位MB，取值>=0',
  required_tags VARCHAR(512) NOT NULL COMMENT '必需能力标签，逗号分隔，空串表示无要求',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_firmware_version_name UNIQUE (name)
) COMMENT='固件版本兼容声明：型号集合、最低内存与必需能力标签';

CREATE TABLE IF NOT EXISTS device_profile (
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID，对应device表',
  model VARCHAR(64) NULL COMMENT '画像型号，NULL表示缺少该字段，筛选时按不兼容处理',
  memory_mb INT NULL COMMENT '画像内存，单位MB，NULL表示缺少该字段，筛选时按不兼容处理',
  tags VARCHAR(512) NULL COMMENT '能力标签，逗号分隔，NULL表示缺少该字段，筛选时按不兼容处理',
  profile_version INT NOT NULL COMMENT '画像版本，从1开始，每次更新加一；任务只引用一个完整画像版本',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近更新时间',
  PRIMARY KEY (device_id)
) COMMENT='设备硬件画像，更新只影响后续新任务，不追改已下发任务';

CREATE TABLE IF NOT EXISTS profile_launch (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '投放单ID',
  request_id VARCHAR(64) NOT NULL COMMENT '投放请求ID，全局唯一；同键重放首次目标清单，异参返回409',
  version_id BIGINT NOT NULL COMMENT '发布人选择的固件版本ID',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_launch_request UNIQUE (request_id)
) COMMENT='硬件画像投放单：一次按当次冻结画像筛选目标的发布动作';

CREATE TABLE IF NOT EXISTS profile_task (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  launch_id BIGINT NOT NULL COMMENT '所属投放单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  profile_version INT NOT NULL COMMENT '筛选时冻结的画像版本，供失败率审计，下发后不可改',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING待回执；SUCCESS成功；FAILED失败',
  first_result VARCHAR(16) NULL COMMENT '首次回执结果（SUCCESS/FAILED），未回执为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_ptask_launch_device UNIQUE (launch_id, device_id)
) COMMENT='画像投放任务，仅兼容设备创建；同设备同投放单最多一条';

CREATE TABLE IF NOT EXISTS profile_exclusion (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '排除记录ID',
  launch_id BIGINT NOT NULL COMMENT '所属投放单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  profile_version INT NULL COMMENT '评估时画像版本，设备无画像为NULL',
  reason VARCHAR(256) NOT NULL COMMENT '排除原因（无画像、缺字段、型号不匹配、内存不足、缺必需标签）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_pex_launch_device UNIQUE (launch_id, device_id)
) COMMENT='投放排除记录：不兼容设备及原因，不计入失败率';

CREATE TABLE IF NOT EXISTS idempotency_record (
  request_id VARCHAR(64) NOT NULL COMMENT '全局唯一请求ID',
  api VARCHAR(64) NOT NULL COMMENT '接口标识',
  fingerprint VARCHAR(256) NOT NULL COMMENT '请求参数指纹，同键异参返回409',
  response_body CLOB NOT NULL COMMENT '成功响应快照（JSON），用于同键同参重放',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (request_id)
) COMMENT='写操作幂等去重记录，失败不占键';
