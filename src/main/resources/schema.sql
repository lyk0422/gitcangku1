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
  profile_version INT NULL COMMENT '创建任务时冻结的设备画像版本号（从1开始）；未启用画像门禁的任务为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_task_release_device UNIQUE (release_id, device_id)
) COMMENT='设备投放任务，同设备同发布单最多一条；硬件画像门禁只引用创建时的冻结画像版本';

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

-- 固件版本硬件兼容要求：版本声明型号集合、最低内存与必需能力标签，只增不改。
CREATE TABLE IF NOT EXISTS hardware_requirement (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '硬件要求ID',
  firmware_version VARCHAR(64) NOT NULL COMMENT '固件目标版本号，与发布单to_version一致',
  min_memory_mb INT NULL COMMENT '最低运行内存，单位MB（1GB=1024MB）；NULL表示不限制内存',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '声明时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_hwreq_version UNIQUE (firmware_version)
) COMMENT='固件版本硬件兼容要求，同版本重复声明冲突';

CREATE TABLE IF NOT EXISTS hardware_requirement_model (
  requirement_id BIGINT NOT NULL COMMENT '硬件要求ID',
  model VARCHAR(64) NOT NULL COMMENT '允许投放的设备型号',
  PRIMARY KEY (requirement_id, model)
) COMMENT='硬件要求允许型号集合，设备画像型号须命中其一';

CREATE TABLE IF NOT EXISTS hardware_requirement_tag (
  requirement_id BIGINT NOT NULL COMMENT '硬件要求ID',
  capability_tag VARCHAR(64) NOT NULL COMMENT '必需能力标签，设备画像须包含全部',
  PRIMARY KEY (requirement_id, capability_tag)
) COMMENT='硬件要求必需能力标签集合';

-- 设备硬件画像：只追加版本，当前版本由 head 表指向；画像修改产生新版本，不追改已下发任务。
CREATE TABLE IF NOT EXISTS device_profile_head (
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  current_version INT NOT NULL COMMENT '当前画像版本号，从1开始，每次更新加一',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近更新时间',
  PRIMARY KEY (device_id)
) COMMENT='设备画像当前版本指针';

CREATE TABLE IF NOT EXISTS device_hardware_profile (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '画像版本记录ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  profile_version INT NOT NULL COMMENT '画像版本号，从1开始，只增不改',
  model VARCHAR(64) NULL COMMENT '画像登记型号；NULL表示画像缺少型号字段，按不兼容处理',
  memory_mb INT NULL COMMENT '画像登记运行内存，单位MB；NULL表示画像缺少内存字段，按不兼容处理',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '画像版本冻结时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_profile_device_version UNIQUE (device_id, profile_version)
) COMMENT='设备硬件画像版本快照，历史版本不可变';

CREATE TABLE IF NOT EXISTS device_profile_tag (
  profile_id BIGINT NOT NULL COMMENT '画像版本记录ID',
  capability_tag VARCHAR(64) NOT NULL COMMENT '该画像版本具备的能力标签',
  PRIMARY KEY (profile_id, capability_tag)
) COMMENT='画像版本能力标签，随版本冻结不可变';

-- 任务创建时冻结的完整画像快照：每个任务只引用一个完整画像版本，供失败率审计。
CREATE TABLE IF NOT EXISTS rollout_task_profile_snapshot (
  task_id BIGINT NOT NULL COMMENT '投放任务ID',
  profile_version INT NOT NULL COMMENT '冻结的设备画像版本号',
  model VARCHAR(64) NULL COMMENT '冻结画像型号；NULL表示当时画像缺少型号字段',
  memory_mb INT NULL COMMENT '冻结画像内存，单位MB；NULL表示当时画像缺少内存字段',
  capability_tags CLOB NOT NULL COMMENT '冻结画像能力标签JSON数组（如["BLE"]），不含标签时为空数组',
  snapshotted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照冻结时间',
  PRIMARY KEY (task_id)
) COMMENT='任务画像门禁冻结快照，画像后续更新不改变此快照';

-- 硬件不兼容排除记录：记录每次门禁判定的排除原因与所用画像版本；不兼容设备不创建任务、不计失败率。
CREATE TABLE IF NOT EXISTS rollout_exclusion (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '排除记录ID',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '被排除设备ID',
  reason_code VARCHAR(32) NOT NULL COMMENT '排除原因：PROFILE_MISSING无画像；MODEL_MISSING缺型号；MEMORY_MISSING缺内存；MODEL_NOT_REQUIRED型号不在声明集合；MEMORY_INSUFFICIENT内存不足；REQUIRED_TAG_MISSING缺必需标签',
  profile_version INT NULL COMMENT '判定时使用的设备画像版本；无画像时为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次排除时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_exclusion_release_device UNIQUE (release_id, device_id)
) COMMENT='发布单硬件不兼容排除清单，设备转为兼容并建任务后移除该记录';
