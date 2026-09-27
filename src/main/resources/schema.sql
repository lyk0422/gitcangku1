-- 固件灰度投放 schema（H2，MODE=MySQL）。
-- 发布单/任务的业务时间（暂停、恢复、阻断、上报、豁免等）均为 UTC，ISO-8601 字符串；
-- created_at/updated_at 为数据库审计时间。区间一律 UTC 左闭右开。

CREATE TABLE IF NOT EXISTS device (
  device_id VARCHAR(64) NOT NULL COMMENT '设备唯一标识',
  model VARCHAR(64) NOT NULL COMMENT '设备型号，登记后不可修改',
  current_version VARCHAR(64) NOT NULL COMMENT '设备当前固件版本，仅成功回执更新',
  bucket_no INT NOT NULL COMMENT '灰度分桶号，取值0~99，登记后不可修改',
  group_name VARCHAR(64) NULL COMMENT '设备组，登记后不可修改；NULL表示未分组，模块豁免按设备组匹配',
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
  round_failed INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入FAILED的任务数，重复回执不重复计数；MODULE_BLOCKED不计数',
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
  module_summary CLOB NULL COMMENT '下发时固化的设备模块摘要JSON（按模块名排序）；设备从未上报为NULL，历史任务不随后续上报改写',
  matrix_version INT NULL COMMENT '下发时目标固件兼容矩阵版本；目标固件未登记模块需求时为NULL，矩阵修订不改写历史任务',
  exemption_version INT NULL COMMENT '下发时生效的设备组模块豁免版本；未使用豁免为NULL，豁免后续修订不改写历史任务',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_task_release_device UNIQUE (release_id, device_id)
) COMMENT='设备投放任务，同设备同发布单最多一条；模块快照只增不改';

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

-- 固件兼容矩阵：每个目标固件版本一行当前版本号，每次全量重登记成功加一。
CREATE TABLE IF NOT EXISTS firmware_matrix (
  firmware_version VARCHAR(64) NOT NULL COMMENT '目标固件版本',
  matrix_version INT NOT NULL COMMENT '矩阵版本号，从1开始，该固件需求每次成功修订加一',
  updated_at_utc VARCHAR(40) NOT NULL COMMENT '最近修订时刻，UTC，ISO-8601',
  PRIMARY KEY (firmware_version)
) COMMENT='固件模块需求矩阵头';

CREATE TABLE IF NOT EXISTS firmware_module_requirement (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '需求ID',
  firmware_version VARCHAR(64) NOT NULL COMMENT '声明需求的固件版本',
  module_name VARCHAR(64) NOT NULL COMMENT '所需模块名',
  min_version VARCHAR(32) NOT NULL COMMENT '所需最小模块版本，公开点分数字版本（如1.2.0）',
  PRIMARY KEY (id),
  CONSTRAINT uk_fw_module_req UNIQUE (firmware_version, module_name)
) COMMENT='固件直接声明的模块需求，按固件版本全量替换';

CREATE TABLE IF NOT EXISTS module_dependency (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '依赖边ID',
  module_name VARCHAR(64) NOT NULL COMMENT '依赖发起模块名',
  depends_on VARCHAR(64) NOT NULL COMMENT '被依赖模块名',
  min_version VARCHAR(32) NOT NULL COMMENT '被依赖模块所需最小版本，公开点分数字版本',
  PRIMARY KEY (id),
  CONSTRAINT uk_module_dep_edge UNIQUE (module_name, depends_on)
) COMMENT='模块间依赖图（全局），登记时不得成环；重复登记取更高最小版本';

CREATE TABLE IF NOT EXISTS device_module_report (
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  report_version INT NOT NULL COMMENT '上报版本号，从1开始，每次全量重报加一',
  reported_at_utc VARCHAR(40) NOT NULL COMMENT '最近上报时刻，UTC，ISO-8601',
  PRIMARY KEY (device_id)
) COMMENT='设备模块清单上报头，按设备全量替换';

CREATE TABLE IF NOT EXISTS device_module (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '上报项ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  module_name VARCHAR(64) NOT NULL COMMENT '设备已安装模块名',
  version VARCHAR(32) NOT NULL COMMENT '模块版本摘要，公开点分数字版本',
  PRIMARY KEY (id),
  CONSTRAINT uk_device_module UNIQUE (device_id, module_name)
) COMMENT='设备最新模块清单，旧版本不保留，被引用的历史快照固化在任务/阻断记录中';

CREATE TABLE IF NOT EXISTS module_exemption (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '豁免ID',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  group_name VARCHAR(64) NOT NULL COMMENT '豁免设备组，必须与设备登记分组完全一致；未分组设备不享受豁免',
  version INT NOT NULL COMMENT '豁免版本号，从1开始，同一发布单+设备组每次修改加一',
  modules CLOB NOT NULL COMMENT '显式豁免模块名JSON数组（按模块名排序），仅列出的模块可豁免，空数组不允许',
  expires_at_utc VARCHAR(40) NOT NULL COMMENT '豁免到期时刻，UTC，ISO-8601；区间左闭右开，当前时刻>=该值即失效',
  created_at_utc VARCHAR(40) NOT NULL COMMENT '当前版本创建时刻，UTC，ISO-8601',
  PRIMARY KEY (id),
  CONSTRAINT uk_exemption_release_group UNIQUE (release_id, group_name)
) COMMENT='发布单设备组模块豁免当前态，不绕过签名/失败率暂停等既有门禁';

CREATE TABLE IF NOT EXISTS module_exemption_history (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '豁免历史ID',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  group_name VARCHAR(64) NOT NULL COMMENT '豁免设备组',
  version INT NOT NULL COMMENT '豁免版本号',
  modules CLOB NOT NULL COMMENT '该版本显式豁免模块名JSON数组',
  expires_at_utc VARCHAR(40) NOT NULL COMMENT '该版本豁免到期时刻，UTC，ISO-8601',
  created_at_utc VARCHAR(40) NOT NULL COMMENT '该版本创建时刻，UTC，ISO-8601',
  superseded_at_utc VARCHAR(40) NULL COMMENT '被下一版本替代的时刻，UTC，ISO-8601；NULL表示当前版本',
  PRIMARY KEY (id),
  CONSTRAINT uk_exemption_history_version UNIQUE (release_id, group_name, version)
) COMMENT='模块豁免历史，只增不改，按版本排序';

CREATE TABLE IF NOT EXISTS module_block_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '阻断记录ID',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '被阻断设备ID',
  request_id VARCHAR(64) NOT NULL COMMENT '触发阻断的拉取请求ID，用于幂等重放与诊断',
  matrix_version INT NULL COMMENT '阻断时目标固件矩阵版本；目标固件未登记需求时为NULL',
  exemption_version INT NULL COMMENT '阻断时设备组有效豁免版本；无有效豁免为NULL',
  device_summary CLOB NULL COMMENT '阻断时设备模块摘要JSON（按模块名排序）；设备从未上报为NULL',
  missing_modules CLOB NOT NULL COMMENT '缺失/低版本模块明细JSON，按模块名稳定排序；元素含模块名、设备版本（缺失为NULL）、最低要求版本',
  blocked_at_utc VARCHAR(40) NOT NULL COMMENT '阻断时刻，UTC，ISO-8601',
  PRIMARY KEY (id)
) COMMENT='MODULE_BLOCKED诊断记录；不创建任务、不计入失败率，历史不可改';

CREATE INDEX IF NOT EXISTS idx_block_release ON module_block_record (release_id);
CREATE INDEX IF NOT EXISTS idx_block_device ON module_block_record (device_id);

-- 业务命名锁：含模块依赖边的矩阵登记事务先持有该行锁，串行化依赖图修订，杜绝并发登记成环。
CREATE TABLE IF NOT EXISTS schema_lock (
  lock_name VARCHAR(64) NOT NULL COMMENT '串行化锁名',
  PRIMARY KEY (lock_name)
) COMMENT='业务串行化命名锁';
MERGE INTO schema_lock (lock_name) KEY (lock_name) VALUES ('module_dependency_graph');
