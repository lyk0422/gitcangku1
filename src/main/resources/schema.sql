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

CREATE TABLE IF NOT EXISTS campaign (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '投放活动ID',
  name VARCHAR(64) NOT NULL COMMENT '活动名称',
  status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE进行中，ENDED已结束（终态，结束后禁止入组、回执、恢复与迁移）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id)
) COMMENT='投放活动，队列迁移的作用域';

CREATE TABLE IF NOT EXISTS cohort (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '队列ID',
  campaign_id BIGINT NOT NULL COMMENT '所属投放活动ID',
  code VARCHAR(64) NOT NULL COMMENT '队列编码，活动内唯一',
  firmware_version VARCHAR(64) NOT NULL COMMENT '队列固件版本，迁移要求源队列与目标队列一致',
  region VARCHAR(64) NOT NULL COMMENT '所属区域',
  region_quota INT NOT NULL COMMENT '区域配额：本队列允许容纳的设备数上限（按区域口径）',
  device_cap INT NOT NULL COMMENT '设备上限：本队列允许容纳的设备总数上限',
  gray_percent INT NOT NULL COMMENT '灰度百分比，取值1~100；队列规模不得超过活动内设备总数×gray_percent/100',
  device_count INT NOT NULL DEFAULT 0 COMMENT '当前队列规模（设备数），只在持有活动行锁的事务内增减',
  status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE投放中，PAUSED失败率自动暂停',
  success_count INT NOT NULL DEFAULT 0 COMMENT '累计确认安装成功的设备数，迟到回执不计入',
  monitor_round INT NOT NULL DEFAULT 1 COMMENT '当前监控轮次，从1开始，人工恢复后加一并清零轮次统计',
  round_success INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内按当前代次结算的SUCCESS回执数',
  round_failed INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内按当前代次结算的FAILED回执数',
  sample_floor INT NOT NULL COMMENT '失败率统计样本下限，取值2~100；本轮样本数达到下限才评估暂停',
  failure_threshold_percent INT NOT NULL COMMENT '失败率阈值百分比，取值1~100；FAILED×100>=样本数×阈值时自动暂停',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_cohort_campaign_code UNIQUE (campaign_id, code)
) COMMENT='投放队列，携带固件版本、区域配额、设备上限与灰度百分比策略';

CREATE TABLE IF NOT EXISTS device_assignment (
  campaign_id BIGINT NOT NULL COMMENT '所属投放活动ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  cohort_id BIGINT NOT NULL COMMENT '当前所属队列ID',
  assignment_version INT NOT NULL COMMENT '分配版本号，从1开始，每次迁移成功加一；迁移单按此做乐观校验',
  assignment_generation INT NOT NULL COMMENT '指令代次，从1开始，每次迁移成功加一；回执按代次隔离结算',
  install_status VARCHAR(16) NOT NULL COMMENT '安装状态：NONE未确认，SUCCESS已确认安装成功（不可再迁移）',
  settled_generation INT NOT NULL DEFAULT 0 COMMENT '已结算的最高回执代次，0表示尚未结算；同代次回执只结算一次',
  settled_result VARCHAR(16) NULL COMMENT '已结算代次的回执结果（SUCCESS/FAILED），未结算为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入组时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (campaign_id, device_id)
) COMMENT='设备在活动内的队列分配，同设备同活动至多一条';

CREATE TABLE IF NOT EXISTS dispatch_command (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '下发指令ID',
  campaign_id BIGINT NOT NULL COMMENT '所属投放活动ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  cohort_id BIGINT NOT NULL COMMENT '指令目标队列ID',
  generation INT NOT NULL COMMENT '指令代次，与下达时的分配代次一致',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING未决；SUPERSEDED被迁移废弃；SETTLED已按回执结算',
  pending_device VARCHAR(64) NULL COMMENT 'PENDING时等于device_id，否则为NULL；用于同活动同设备至多一条未决指令的唯一约束',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id)
) COMMENT='设备下发指令，同活动同设备至多一条未决指令';

CREATE UNIQUE INDEX IF NOT EXISTS uk_command_pending_device ON dispatch_command (campaign_id, pending_device);

CREATE TABLE IF NOT EXISTS cohort_receipt (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '回执记录ID',
  campaign_id BIGINT NOT NULL COMMENT '所属投放活动ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  cohort_id BIGINT NOT NULL COMMENT '回执对应指令的队列ID',
  generation INT NOT NULL COMMENT '回执代次；同设备同代次至多一条记录',
  result VARCHAR(16) NOT NULL COMMENT '回执结果（SUCCESS/FAILED）',
  disposition VARCHAR(16) NOT NULL COMMENT '处置：SETTLED按当前代次结算；LATE迟到旧代次仅存档，不改变任何统计',
  received_at_utc VARCHAR(40) NOT NULL COMMENT '回执入账时刻，UTC，ISO-8601格式（如2026-09-23T07:00:00Z）',
  PRIMARY KEY (id),
  CONSTRAINT uk_receipt_device_generation UNIQUE (campaign_id, device_id, generation)
) COMMENT='队列回执入账记录，同设备同代次只入账一次，迟到回执留作证据';

CREATE TABLE IF NOT EXISTS migration_order (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '迁移单ID',
  migration_key VARCHAR(64) NOT NULL COMMENT '迁移单业务键，全局唯一',
  campaign_id BIGINT NOT NULL COMMENT '所属投放活动ID',
  status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVATED已激活（迁移单只在激活时落库）',
  device_count INT NOT NULL COMMENT '本单迁移设备数，取值2~500',
  request_id VARCHAR(64) NOT NULL COMMENT '激活请求的幂等键',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '激活时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_migration_key UNIQUE (migration_key)
) COMMENT='设备跨队列迁移单，整单原子生效';

CREATE TABLE IF NOT EXISTS migration_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '迁移明细ID',
  migration_id BIGINT NOT NULL COMMENT '所属迁移单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  from_cohort_id BIGINT NOT NULL COMMENT '迁移前队列ID',
  to_cohort_id BIGINT NOT NULL COMMENT '迁移后队列ID',
  from_generation INT NOT NULL COMMENT '迁移前指令代次',
  to_generation INT NOT NULL COMMENT '迁移后指令代次（from_generation+1）',
  superseded_command_id BIGINT NULL COMMENT '被废弃的未决指令ID，无未决指令为NULL',
  new_command_id BIGINT NULL COMMENT '为目标队列新签发的未决指令ID，无未决指令时为NULL',
  PRIMARY KEY (id),
  CONSTRAINT uk_migration_item_device UNIQUE (migration_id, device_id)
) COMMENT='迁移单设备明细，记录迁移前后队列与指令代次';

CREATE TABLE IF NOT EXISTS idempotency_record (
  request_id VARCHAR(64) NOT NULL COMMENT '全局唯一请求ID',
  api VARCHAR(64) NOT NULL COMMENT '接口标识',
  fingerprint VARCHAR(256) NOT NULL COMMENT '请求参数指纹，同键异参返回409',
  response_body CLOB NOT NULL COMMENT '成功响应快照（JSON），用于同键同参重放',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (request_id)
) COMMENT='写操作幂等去重记录，失败不占键';
