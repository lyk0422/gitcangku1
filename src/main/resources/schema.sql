-- 固件灰度投放 schema（H2，MODE=MySQL）。业务时刻一律使用 UTC（ISO-8601 字符串）。

CREATE TABLE IF NOT EXISTS device (
  device_id VARCHAR(64) NOT NULL COMMENT '设备唯一标识',
  model VARCHAR(64) NOT NULL COMMENT '设备型号，登记后不可修改',
  current_version VARCHAR(64) NOT NULL COMMENT '设备当前固件版本',
  bucket_no INT NOT NULL COMMENT '灰度分桶号，取值0~99，登记后不可修改',
  device_group VARCHAR(64) NULL COMMENT '设备组名，登记后不可修改；为空表示不属于任何组，不享受任何模块豁免',
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
  round_success INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入SUCCESS的任务数，重复回执不重复计数；模块阻断不计入',
  round_failed INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入FAILED的任务数，重复回执不重复计数；模块阻断不计入',
  active_model VARCHAR(64) NULL COMMENT 'ACTIVE或PAUSED时等于model，取消后置NULL；用于同型号至多一张未终结发布单的唯一约束',
  exemption_version INT NOT NULL DEFAULT 0 COMMENT '模块豁免版本号，从0开始（无豁免），每次豁免整体修改成功加一；任务与判定证据固化下发时版本',
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
  matrix_version INT NOT NULL DEFAULT 0 COMMENT '下发时兼容矩阵版本号，无任何登记为0；矩阵后续修订不改写本值',
  exemption_version INT NOT NULL DEFAULT 0 COMMENT '下发时发布单模块豁免版本号，无豁免为0；豁免后续修订不改写本值',
  granted_via VARCHAR(16) NOT NULL DEFAULT 'DIRECT' COMMENT '下发途径：DIRECT模块全部满足；EXEMPTION存在模块依靠有效豁免放行',
  device_modules CLOB NULL COMMENT '下发时设备模块清单快照JSON（模块名、版本、摘要，按模块名排序）；设备后续上报不改写',
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

-- 兼容矩阵：单行状态保存当前矩阵版本，登记固件需求或模块依赖成功一次加一。
CREATE TABLE IF NOT EXISTS matrix_state (
  id INT NOT NULL COMMENT '固定为0的单行主键',
  version INT NOT NULL DEFAULT 0 COMMENT '兼容矩阵版本号，从0开始（无任何登记），每次成功登记加一',
  updated_at_utc VARCHAR(40) NULL COMMENT '最近一次矩阵登记时刻，UTC，ISO-8601；未登记为NULL',
  PRIMARY KEY (id)
) COMMENT='兼容矩阵版本状态单行表';
INSERT INTO matrix_state (id, version)
SELECT 0, 0 WHERE NOT EXISTS (SELECT 1 FROM matrix_state WHERE id = 0);

CREATE TABLE IF NOT EXISTS firmware_requirement (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '固件需求ID',
  model VARCHAR(64) NOT NULL COMMENT '设备型号',
  firmware_version VARCHAR(64) NOT NULL COMMENT '固件版本，公开的点分数字版本',
  module_name VARCHAR(64) NOT NULL COMMENT '所需模块名',
  min_version VARCHAR(64) NOT NULL COMMENT '最低模块版本，点分数字；设备版本低于此值即阻断',
  updated_at_utc VARCHAR(40) NOT NULL COMMENT '最近登记时刻，UTC，ISO-8601',
  PRIMARY KEY (id),
  CONSTRAINT uk_firmware_requirement UNIQUE (model, firmware_version, module_name)
) COMMENT='固件模块需求：某型号某固件版本直接声明的所需模块及最低版本';

CREATE TABLE IF NOT EXISTS module_dependency (
  module_name VARCHAR(64) NOT NULL COMMENT '模块名',
  depends_on VARCHAR(64) NOT NULL COMMENT '被依赖模块名，随模块一并纳入固件完整依赖集合；不得形成依赖环',
  min_version VARCHAR(64) NOT NULL COMMENT '被依赖模块最低版本，点分数字，无版本要求登记为0',
  updated_at_utc VARCHAR(40) NOT NULL COMMENT '最近登记时刻，UTC，ISO-8601',
  PRIMARY KEY (module_name, depends_on)
) COMMENT='模块间依赖关系（有向边），登记后态不得成环（含自依赖）';

CREATE TABLE IF NOT EXISTS device_module (
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  module_name VARCHAR(64) NOT NULL COMMENT '设备上报的模块名',
  version VARCHAR(64) NOT NULL COMMENT '设备上报的模块版本，点分数字',
  digest VARCHAR(128) NOT NULL COMMENT '模块版本摘要（构建/哈希摘要），原样保存，用于下发与阻断证据固化',
  reported_at_utc VARCHAR(40) NOT NULL COMMENT '本次上报时刻，UTC，ISO-8601；同设备后提交的上报整体覆盖此前清单',
  PRIMARY KEY (device_id, module_name)
) COMMENT='设备上报的模块清单及版本摘要，全量替换';

CREATE TABLE IF NOT EXISTS module_exemption (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '豁免条目ID',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  version INT NOT NULL COMMENT '豁免版本号，同一发布单从1开始，每次整体修改成功加一；任务固化下发时版本',
  device_group VARCHAR(64) NOT NULL COMMENT '被豁免设备组，必须显式指定；仅组名完全相等的设备适用',
  module_name VARCHAR(64) NOT NULL COMMENT '被豁免模块，必须逐模块显式列出，不支持通配',
  expires_at_utc VARCHAR(40) NOT NULL COMMENT '到期时刻，UTC，ISO-8601；时间区间左闭右开，到达此时刻自动失效',
  created_at_utc VARCHAR(40) NOT NULL COMMENT '本版本豁免登记时刻，UTC，ISO-8601',
  PRIMARY KEY (id),
  CONSTRAINT uk_exemption_version_entry UNIQUE (release_id, version, device_group, module_name)
) COMMENT='发布单按设备组的模块豁免；历史版本保留供任务证据回溯';

CREATE TABLE IF NOT EXISTS compatibility_check (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '兼容判定记录ID',
  release_id BIGINT NOT NULL COMMENT '判定所属发布单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '判定设备ID',
  outcome VARCHAR(16) NOT NULL COMMENT '判定结果：GRANTED模块全部满足直接下发；EXEMPTION有模块靠有效豁免放行后下发；BLOCKED存在缺失或低版本模块，阻断下发',
  matrix_version INT NOT NULL COMMENT '判定时兼容矩阵版本，无登记为0',
  exemption_version INT NOT NULL COMMENT '判定时发布单豁免版本，无豁免为0',
  device_modules CLOB NOT NULL COMMENT '判定时设备模块清单快照JSON（模块名、版本、摘要，按模块名排序）；设备后续上报与矩阵修订均不改写',
  blocked_items CLOB NULL COMMENT '阻断明细JSON，按模块名稳定排序，每项含模块、设备版本（缺失为null）、最低要求版本、原因（MISSING/VERSION_TOO_LOW）；无阻断为NULL',
  exempted_modules CLOB NOT NULL COMMENT '本次命中有效豁免的模块名JSON（按模块名排序），无命中为空数组[]',
  request_id VARCHAR(64) NOT NULL COMMENT '触发判定的拉取请求ID；同参重放返回首次响应且不新增记录',
  checked_at_utc VARCHAR(40) NOT NULL COMMENT '判定时刻，UTC，ISO-8601',
  PRIMARY KEY (id),
  CONSTRAINT uk_check_request UNIQUE (request_id)
) COMMENT='拉取兼容判定证据，每次下发、阻断或豁免放行各固化一条，只增不改';
