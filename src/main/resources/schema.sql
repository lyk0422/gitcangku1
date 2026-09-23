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

-- 跨投放队列迁移 schema（H2，MODE=MySQL）。一个投放活动对应一张 release_order，活动内包含多个 cohort 队列。

CREATE TABLE IF NOT EXISTS cohort_region (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '区域配额ID',
  release_id BIGINT NOT NULL COMMENT '所属投放活动ID（release_order.id）',
  region_code VARCHAR(64) NOT NULL COMMENT '区域编码，同一活动内唯一',
  quota INT NOT NULL COMMENT '区域设备总配额，取值>=1；活动内同区域各队列设备数之和不得超过该值',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_region_release_code UNIQUE (release_id, region_code)
) COMMENT='投放活动的区域配额，活动内同区域队列共享';

CREATE TABLE IF NOT EXISTS cohort (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '队列ID',
  release_id BIGINT NOT NULL COMMENT '所属投放活动ID（release_order.id）',
  cohort_code VARCHAR(64) NOT NULL COMMENT '队列编码，同一活动内唯一',
  firmware_version VARCHAR(64) NOT NULL COMMENT '队列使用的固件版本；跨队列迁移要求源、目标队列版本相同',
  region_code VARCHAR(64) NOT NULL COMMENT '队列所属区域编码',
  device_cap INT NOT NULL COMMENT '队列设备上限，取值>=1；迁移完整后态设备数不得超过',
  canary_percent INT NOT NULL COMMENT '灰度百分比，取值0~100；完整后态设备数不得超过ceil(设备上限×百分比/100)',
  sample_floor INT NOT NULL COMMENT '失败率统计样本下限，取值2~100，队列级回执样本达到下限才评估暂停',
  failure_threshold_percent INT NOT NULL COMMENT '失败率阈值百分比，取值1~100；FAILED×100>=样本数×阈值时队列自动暂停',
  success_count INT NOT NULL DEFAULT 0 COMMENT '本队列当前监控轮次内首次SUCCESS的指令回执数，重复回执不重复计数',
  failed_count INT NOT NULL DEFAULT 0 COMMENT '本队列当前监控轮次内首次FAILED的指令回执数，重复回执不重复计数',
  paused BOOLEAN NOT NULL DEFAULT FALSE COMMENT '队列暂停状态：TRUE失败率自动暂停；迁移不搬移统计也不改变暂停位',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_cohort_release_code UNIQUE (release_id, cohort_code)
) COMMENT='投放队列（cohort），携带固件版本、区域配额维度、设备上限、灰度百分比与队列级统计';

CREATE TABLE IF NOT EXISTS cohort_assignment (
  release_id BIGINT NOT NULL COMMENT '所属投放活动ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  cohort_id BIGINT NOT NULL COMMENT '设备当前所属队列ID；迁移提交时原子切换为目标队列',
  assignment_version INT NOT NULL COMMENT '分配版本号，每次迁移成功加一；激活时必须与运营提交值一致，否则409',
  current_generation INT NOT NULL COMMENT '当前指令代次，从1开始，每次迁移（无论有无未决指令）加一',
  install_confirmed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已确认安装成功：TRUE的设备不得迁移',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次分配时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (release_id, device_id)
) COMMENT='设备在投放活动内的队列分配，同一活动内每台设备至多一条';

CREATE TABLE IF NOT EXISTS assignment_command (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '下发指令ID',
  release_id BIGINT NOT NULL COMMENT '所属投放活动ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  cohort_id BIGINT NOT NULL COMMENT '指令下发时所属队列ID；新代次指令指向目标队列',
  generation INT NOT NULL COMMENT '指令代次，与设备分配代次对应，同设备同活动同代次至多一条',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING未决；SUPERSEDED被迁移原子废弃；SUCCESS成功；FAILED失败',
  first_result VARCHAR(16) NULL COMMENT '首次回执结果（SUCCESS/FAILED），未回执或LATE为NULL',
  migration_id BIGINT NULL COMMENT '若非空，表示该指令由指定迁移单生成或废弃，用于代次证据串联',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_command_release_device_generation UNIQUE (release_id, device_id, generation)
) COMMENT='按代次下发的设备指令；迁移将旧PENDING指令置SUPERSEDED并生成新一代指令';

CREATE TABLE IF NOT EXISTS receipt_history (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '回执历史ID',
  release_id BIGINT NOT NULL COMMENT '所属投放活动ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  command_id BIGINT NOT NULL COMMENT '回执指向的指令ID',
  cohort_id BIGINT NOT NULL COMMENT '回执结算（或本应结算）的队列ID',
  generation INT NOT NULL COMMENT '回执携带的指令代次',
  result VARCHAR(16) NOT NULL COMMENT 'SUCCESS成功；FAILED失败；LATE迁移提交后到达的旧代次迟到回执，仅存档',
  settled BOOLEAN NOT NULL COMMENT '是否实际结算：TRUE计入队列统计并终结指令；FALSE为LATE存档，不改统计、失败率与暂停位',
  duplicate BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否为已结算指令的重复回执：TRUE时不重复计数',
  receipt_at VARCHAR(40) NOT NULL COMMENT '回执到达时刻，UTC，ISO-8601格式（如2026-09-22T07:00:00Z）',
  PRIMARY KEY (id)
) COMMENT='指令回执历史：结算回执与迟到（LATE）旧代次回执均留存作为证据';

CREATE TABLE IF NOT EXISTS cohort_migration_order (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '迁移单ID',
  migration_key VARCHAR(64) NOT NULL COMMENT '迁移单业务键，全局唯一；重复使用返回409',
  release_id BIGINT NOT NULL COMMENT '所属投放活动ID；整单只能在同一活动内迁移',
  status VARCHAR(16) NOT NULL COMMENT 'COMMITTED已提交（整单原子提交，无部分迁移）',
  device_count INT NOT NULL COMMENT '本单迁移设备数量，取值2~500',
  committed_at VARCHAR(40) NOT NULL COMMENT '提交时刻，UTC，ISO-8601格式',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_migration_key UNIQUE (migration_key)
) COMMENT='跨队列迁移单主表；预览不写数据，激活成功才落库';

CREATE TABLE IF NOT EXISTS cohort_migration_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '迁移设备项ID',
  migration_id BIGINT NOT NULL COMMENT '所属迁移单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '迁移设备ID',
  from_cohort_id BIGINT NOT NULL COMMENT '迁移前队列ID（提交时重新读取校验）',
  to_cohort_id BIGINT NOT NULL COMMENT '目标队列ID',
  expected_assignment_version INT NOT NULL COMMENT '运营提交时设备的分配版本，激活重读不一致则整单409',
  old_generation INT NOT NULL COMMENT '迁移前指令代次',
  new_generation INT NOT NULL COMMENT '迁移后新指令代次（必比旧代次加一）',
  old_command_id BIGINT NULL COMMENT '被废弃的旧未决指令ID，无未决指令时为NULL',
  new_command_id BIGINT NOT NULL COMMENT '为目标队列生成的新代次指令ID',
  PRIMARY KEY (id),
  CONSTRAINT uk_migration_item_device UNIQUE (migration_id, device_id)
) COMMENT='迁移单设备明细，记录迁移前后队列与指令代次，供查询与LATE证据串联';
