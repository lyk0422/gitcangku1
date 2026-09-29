-- 固件灰度投放 schema（H2，MODE=MySQL）。
-- 投放业务的 created_at/updated_at 为数据库当前时间；回滚演练与门禁的业务时刻（*_utc 列）一律 UTC，ISO-8601。

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

-- 回滚演练：目标版本（待发布版本）在正式投放前必须完成一次回滚演练。
-- 时间区间一律 UTC，按左闭右开 [started_at_utc, finished_at_utc) 解释。
CREATE TABLE IF NOT EXISTS rollback_rehearsal (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '演练ID',
  operator VARCHAR(64) NOT NULL COMMENT '发起操作者，纳入幂等指纹',
  model VARCHAR(64) NOT NULL COMMENT '规范设备型号，所有演练设备型号必须一致',
  target_version VARCHAR(64) NOT NULL COMMENT '目标版本（待正式发布的新版本）；开始演练时设备必须处于该版本',
  rollback_version VARCHAR(64) NOT NULL COMMENT '回退版本（正式投放的来源版本），必须与目标版本不同；成功回退的设备结果提交后回到该版本',
  status VARCHAR(16) NOT NULL COMMENT '状态：RUNNING进行中；PASSED全部设备成功且结束不早于开始（终态）；FAILED存在失败结果或结束早于开始（终态）。终态不可变',
  device_count INT NOT NULL COMMENT '规范设备集合大小（要求设备数），开始时固定，至少为1',
  success_count INT NOT NULL DEFAULT 0 COMMENT '结果提交后回退成功的设备数（实际值），RUNNING时为0',
  failed_count INT NOT NULL DEFAULT 0 COMMENT '结果提交后回退失败的设备数（实际值），RUNNING时为0',
  started_at_utc VARCHAR(40) NOT NULL COMMENT '演练开始时刻，UTC，ISO-8601纳秒精度（如2026-09-29T07:00:00Z），区间左端点（含）',
  finished_at_utc VARCHAR(40) NULL COMMENT '演练结束时刻，UTC，ISO-8601；区间右端点（不含）。RUNNING时为NULL，终态后不可改',
  gate_denied_reason VARCHAR(48) NULL COMMENT '未授予门禁原因：DEVICE_FAILED存在失败设备；FINISHED_BEFORE_START结束早于开始。PASSED时为NULL',
  active_key VARCHAR(200) NULL COMMENT 'RUNNING时为 型号|目标版本|回退版本 组合键并建唯一索引（同版本组合至多一场进行中演练），终结后置NULL；NULL可重复',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id)
) COMMENT='固件回滚演练记录，历史只增不改';

CREATE UNIQUE INDEX IF NOT EXISTS uk_rehearsal_active_key ON rollback_rehearsal (active_key);

CREATE TABLE IF NOT EXISTS rollback_rehearsal_device (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '设备结果ID',
  rehearsal_id BIGINT NOT NULL COMMENT '所属演练ID',
  device_id VARCHAR(64) NOT NULL COMMENT '演练设备ID',
  ordinal INT NOT NULL COMMENT '规范化集合（device_id升序）中的位置，从0开始；明细按该字段稳定排序',
  result VARCHAR(16) NULL COMMENT '回退结果：SUCCESS成功（设备版本回到回退版本）；FAILED失败。结果提交前为NULL，提交后不可改',
  failure_reason VARCHAR(256) NULL COMMENT '失败原因；SUCCESS或尚未提交结果时为NULL',
  PRIMARY KEY (id),
  CONSTRAINT uk_rehearsal_device UNIQUE (rehearsal_id, device_id)
) COMMENT='回滚演练的每台设备结果，开始时按规范集合落库（结果为NULL），提交后不可改';

CREATE TABLE IF NOT EXISTS release_gate (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '门禁ID',
  model VARCHAR(64) NOT NULL COMMENT '门禁适用的设备型号',
  target_version VARCHAR(64) NOT NULL COMMENT '门禁对应的待发布目标版本；新的目标版本必须重新演练，不沿用旧门禁',
  rollback_version VARCHAR(64) NOT NULL COMMENT '门禁对应的回退（来源）版本',
  status VARCHAR(32) NOT NULL COMMENT '门禁结论，固定READY_FOR_RELEASE；授予后不可变',
  rehearsal_id BIGINT NOT NULL COMMENT '授予该门禁的演练ID（PASSED演练）',
  operator VARCHAR(64) NOT NULL COMMENT '门禁授予操作者（结果提交者），纳入幂等指纹',
  granted_at_utc VARCHAR(40) NOT NULL COMMENT '门禁授予时刻，UTC，ISO-8601，等于演练结束时刻',
  PRIMARY KEY (id),
  CONSTRAINT uk_gate_version UNIQUE (model, target_version, rollback_version)
) COMMENT='正式投放前门禁结论，READY_FOR_RELEASE不可变';
