-- 固件灰度投放 schema（H2，MODE=MySQL）。时间均为服务器本地时区（Asia/Shanghai）。

CREATE TABLE IF NOT EXISTS device (
  device_id VARCHAR(64) NOT NULL COMMENT '设备唯一标识',
  model VARCHAR(64) NOT NULL COMMENT '设备型号，登记后不可修改',
  current_version VARCHAR(64) NOT NULL COMMENT '设备当前固件版本',
  bucket_no INT NOT NULL COMMENT '灰度分桶号，取值0~99，登记后不可修改',
  device_group VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '设备组，模块豁免按组匹配，登记后不可修改',
  module_digest VARCHAR(64) NULL COMMENT '设备模块清单摘要（SHA-256十六进制），从未上报为NULL',
  module_report_version INT NOT NULL DEFAULT 0 COMMENT '模块上报版本，每次上报加一，从未上报为0',
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
  exemption_version INT NOT NULL DEFAULT 0 COMMENT '模块豁免版本，从0开始，每次豁免修改加一',
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
  module_digest VARCHAR(64) NULL COMMENT '创建任务时设备模块摘要快照，设备从未上报为NULL；历史不改写',
  matrix_version INT NULL COMMENT '创建任务时兼容矩阵版本快照；历史不改写',
  exemption_version INT NULL COMMENT '创建任务时发布单豁免版本快照；历史不改写',
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

-- 固件模块兼容矩阵：固件需求、模块依赖、矩阵版本与登记历史。时间均为UTC，ISO-8601格式。

CREATE TABLE IF NOT EXISTS compat_matrix_meta (
  id INT NOT NULL COMMENT '单行标识，固定为1',
  version INT NOT NULL COMMENT '兼容矩阵当前版本，每次登记成功加一，未登记为0',
  PRIMARY KEY (id)
) COMMENT='模块兼容矩阵元信息（单行）';

-- 预置单行元信息，保证登记与拉取的行锁与版本读取总有目标行
INSERT INTO compat_matrix_meta (id, version)
SELECT 1, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM compat_matrix_meta WHERE id = 1);

CREATE TABLE IF NOT EXISTS firmware_requirement (
  firmware_version VARCHAR(64) NOT NULL COMMENT '固件版本（发布单目标版本）',
  module VARCHAR(64) NOT NULL COMMENT '所需模块名',
  min_version VARCHAR(64) NOT NULL COMMENT '最小模块版本，点分数字格式（如1.2.0）',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近登记时间',
  PRIMARY KEY (firmware_version, module)
) COMMENT='固件版本所需模块及最小版本（当前态，矩阵修订时覆盖）';

CREATE TABLE IF NOT EXISTS module_dependency (
  module VARCHAR(64) NOT NULL COMMENT '模块名',
  depends_on VARCHAR(64) NOT NULL COMMENT '依赖的模块名',
  min_version VARCHAR(64) NOT NULL COMMENT '依赖模块最小版本，点分数字格式',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近登记时间',
  PRIMARY KEY (module, depends_on)
) COMMENT='模块间依赖边（当前态），登记时校验合并后的图不得成环';

CREATE TABLE IF NOT EXISTS compat_matrix_revision (
  version INT NOT NULL COMMENT '矩阵版本，从1开始，与compat_matrix_meta.version对应',
  requirement_count INT NOT NULL COMMENT '本次登记固件需求条数',
  dependency_count INT NOT NULL COMMENT '本次登记模块依赖条数',
  registered_at_utc VARCHAR(40) NOT NULL COMMENT '登记时刻，UTC，ISO-8601格式',
  PRIMARY KEY (version)
) COMMENT='兼容矩阵登记历史，只增不改';

CREATE TABLE IF NOT EXISTS device_module (
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  module VARCHAR(64) NOT NULL COMMENT '模块名',
  version VARCHAR(64) NOT NULL COMMENT '模块版本，点分数字格式',
  PRIMARY KEY (device_id, module)
) COMMENT='设备当前模块清单，每次上报整体替换';

CREATE TABLE IF NOT EXISTS device_module_report (
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  report_version INT NOT NULL COMMENT '上报版本，从1开始，与device.module_report_version对应',
  module_digest VARCHAR(64) NOT NULL COMMENT '本次上报模块摘要（SHA-256十六进制）',
  reported_at_utc VARCHAR(40) NOT NULL COMMENT '上报时刻，UTC，ISO-8601格式',
  PRIMARY KEY (device_id, report_version)
) COMMENT='设备模块上报历史，只增不改';

CREATE TABLE IF NOT EXISTS release_exemption (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '豁免记录ID',
  release_id BIGINT NOT NULL COMMENT '发布单ID',
  exemption_version INT NOT NULL COMMENT '豁免版本，与release_order.exemption_version对应',
  device_group VARCHAR(64) NOT NULL COMMENT '适用设备组',
  module VARCHAR(64) NOT NULL COMMENT '豁免模块名，显式列出',
  expires_at_utc VARCHAR(40) NOT NULL COMMENT '到期时刻，UTC，ISO-8601；有效期左闭右开，到期自动失效',
  created_at_utc VARCHAR(40) NOT NULL COMMENT '本版豁免登记时刻，UTC，ISO-8601',
  PRIMARY KEY (id)
) COMMENT='发布单模块豁免，每次修改整体替换并加版本，历史版本只增不改';

CREATE INDEX IF NOT EXISTS idx_exemption_release ON release_exemption (release_id, exemption_version);

CREATE TABLE IF NOT EXISTS module_block_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '阻断记录ID',
  release_id BIGINT NOT NULL COMMENT '发布单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  matrix_version INT NOT NULL COMMENT '阻断时兼容矩阵版本快照',
  exemption_version INT NOT NULL COMMENT '阻断时发布单豁免版本快照',
  module_digest VARCHAR(64) NULL COMMENT '阻断时设备模块摘要快照，设备从未上报为NULL',
  blocked_at_utc VARCHAR(40) NOT NULL COMMENT '阻断时刻，UTC，ISO-8601格式',
  PRIMARY KEY (id)
) COMMENT='模块兼容阻断记录，只增不改；阻断不创建任务、不计入失败率';

CREATE TABLE IF NOT EXISTS module_block_item (
  block_id BIGINT NOT NULL COMMENT '阻断记录ID',
  module VARCHAR(64) NOT NULL COMMENT '缺失或低版本模块名',
  device_version VARCHAR(64) NULL COMMENT '设备当前模块版本，未安装为NULL',
  min_version VARCHAR(64) NOT NULL COMMENT '最低要求版本',
  PRIMARY KEY (block_id, module)
) COMMENT='阻断明细，按模块名升序写入，历史不改写';
