-- 固件灰度投放 schema（H2，MODE=MySQL）。
-- created_at/updated_at 为服务器本地时区（Asia/Shanghai）；
-- 业务证据时间（*_at_utc 字段及 API 响应中的时间）统一为 UTC，ISO-8601 格式，区间端点左闭右开。

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

CREATE TABLE IF NOT EXISTS device_tag (
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  tag VARCHAR(64) NOT NULL COMMENT '规范化标签：去除首尾空白并转小写，长度1~64',
  PRIMARY KEY (device_id, tag)
) COMMENT='设备规范化标签集合，同设备标签唯一';

CREATE TABLE IF NOT EXISTS tag_dictionary (
  id INT NOT NULL COMMENT '单行标识，固定为1',
  version BIGINT NOT NULL COMMENT '标签字典版本：设备登记与标签批量变更每次成功提交加一，发布单创建时固化该版本',
  updated_at_utc VARCHAR(40) NOT NULL COMMENT '最近变更时刻，UTC，ISO-8601格式（如2026-09-27T07:00:00Z）',
  PRIMARY KEY (id)
) COMMENT='标签字典版本（单行），其行锁用于串行化标签变更与发布单目标快照解析';

INSERT INTO tag_dictionary (id, version, updated_at_utc)
SELECT 1, 0, '1970-01-01T00:00:00Z'
WHERE NOT EXISTS (SELECT 1 FROM tag_dictionary WHERE id = 1);

CREATE TABLE IF NOT EXISTS release_target_policy (
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  include_all CLOB NOT NULL COMMENT '必须包含的标签，JSON数组，规范化升序；无该条件为空数组',
  exclude_any CLOB NOT NULL COMMENT '不得包含的标签，JSON数组，规范化升序；无该条件为空数组',
  include_any CLOB NOT NULL COMMENT '至少包含一项的标签，JSON数组，规范化升序；无该条件为空数组',
  tag_dict_version BIGINT NOT NULL COMMENT '解析目标快照时的标签字典版本',
  resolved_at_utc VARCHAR(40) NOT NULL COMMENT '目标快照解析时刻，UTC，ISO-8601格式',
  target_count INT NOT NULL COMMENT '目标快照设备数，创建时必大于0',
  PRIMARY KEY (release_id)
) COMMENT='发布单目标标签策略，创建时固化，后续标签修订不影响本记录';

CREATE TABLE IF NOT EXISTS release_target_snapshot (
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  seq INT NOT NULL COMMENT '规范排序序号，从0开始，按device_id升序',
  device_id VARCHAR(64) NOT NULL COMMENT '目标设备ID',
  PRIMARY KEY (release_id, seq),
  CONSTRAINT uk_snapshot_release_device UNIQUE (release_id, device_id)
) COMMENT='发布单目标设备快照，仅创建时生成一次，后续不增不减';

CREATE TABLE IF NOT EXISTS tag_change_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '变更记录ID',
  dict_version BIGINT NOT NULL COMMENT '本次变更后的标签字典版本',
  operator VARCHAR(64) NOT NULL COMMENT '操作者',
  device_count INT NOT NULL COMMENT '本次变更涉及的设备数',
  changes CLOB NOT NULL COMMENT '规范化变更内容快照（JSON数组，按device_id升序，标签升序）',
  changed_at_utc VARCHAR(40) NOT NULL COMMENT '变更时刻，UTC，ISO-8601格式',
  PRIMARY KEY (id),
  CONSTRAINT uk_tag_change_version UNIQUE (dict_version)
) COMMENT='设备标签批量变更历史，历史只增不改';
