-- 固件灰度投放 schema（H2，MODE=MySQL）。业务时刻统一存 UTC，ISO-8601 字符串。

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
  cert_chain_version INT NULL COMMENT '拉取创建时固化的证书链版本；未纳管证书的固件为NULL，之后链轮换/撤销不改变该快照',
  cert_fingerprint VARCHAR(256) NULL COMMENT '创建时固化的证书链指纹；未纳管固件为NULL',
  review_status VARCHAR(32) NULL COMMENT '回执复核状态：NULL无需复核；CERTIFICATE_REVOKED_REVIEW撤销链回执待人工复核；CONFIRMED人工确认；REJECTED人工拒绝',
  review_reason VARCHAR(256) NULL COMMENT '复核原因（如证书链已于某UTC时刻撤销）或人工拒绝原因；无复核为NULL',
  reviewed_by VARCHAR(64) NULL COMMENT '最近一次人工复核操作者；未复核为NULL',
  reviewed_at_utc VARCHAR(40) NULL COMMENT '最近一次人工复核时刻UTC ISO-8601；未复核为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_task_release_device UNIQUE (release_id, device_id)
) COMMENT='设备投放任务，同设备同发布单最多一条；证书纳管任务固化拉取时刻的证书链版本';

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
  fingerprint VARCHAR(512) NOT NULL COMMENT '请求参数指纹（证书操作称certKey，含固件版本/链版本/生效时刻/设备集合/操作者），同键异参返回409',
  response_body CLOB NOT NULL COMMENT '成功响应快照（JSON），用于同键同参重放',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (request_id)
) COMMENT='写操作幂等去重记录，失败不占键';

CREATE TABLE IF NOT EXISTS firmware_version (
  model VARCHAR(64) NOT NULL COMMENT '固件适用设备型号',
  version VARCHAR(64) NOT NULL COMMENT '固件版本号，如2.0.0；登记后该版本纳入签名证书管理',
  created_by VARCHAR(64) NOT NULL COMMENT '登记操作者',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
  PRIMARY KEY (model, version)
) COMMENT='固件版本登记表，行锁用于串行化同固件的证书轮换与撤销';

CREATE TABLE IF NOT EXISTS firmware_cert_chain (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '证书链ID',
  model VARCHAR(64) NOT NULL COMMENT '固件适用设备型号',
  firmware_version VARCHAR(64) NOT NULL COMMENT '所属固件版本',
  chain_version INT NOT NULL COMMENT '链版本号，同一固件版本内从1开始递增；轮换显式指定旧版本与新版本',
  cert_fingerprint VARCHAR(256) NOT NULL COMMENT '证书链指纹（合成业务标识，非真实加密证书内容）',
  valid_from_utc VARCHAR(40) NOT NULL COMMENT '生效时刻UTC ISO-8601，闭区间起点（含该时刻）',
  valid_to_utc VARCHAR(40) NULL COMMENT '失效时刻UTC ISO-8601，半开区间终点（不含该时刻）；NULL表示开放区间，仅最新链可为NULL',
  status VARCHAR(16) NOT NULL COMMENT 'ACTIVE在区间内有效；REVOKED已撤销（终态），撤销后其在途任务回执须人工复核',
  revoked_at_utc VARCHAR(40) NULL COMMENT '撤销时刻UTC ISO-8601；未撤销为NULL',
  revoke_reason VARCHAR(256) NULL COMMENT '撤销原因；未撤销为NULL',
  created_by VARCHAR(64) NOT NULL COMMENT '登记或轮换操作者',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_cert_firmware_chain UNIQUE (model, firmware_version, chain_version)
) COMMENT='固件签名证书链；同一固件版本任意时刻至多一条有效链，轮换首尾相接区间不重叠';

CREATE TABLE IF NOT EXISTS firmware_compat (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '兼容矩阵记录ID',
  model VARCHAR(64) NOT NULL COMMENT '设备型号',
  from_version VARCHAR(64) NOT NULL COMMENT '来源固件版本',
  to_version VARCHAR(64) NOT NULL COMMENT '目标固件版本',
  allowed BOOLEAN NOT NULL COMMENT 'TRUE允许投放；FALSE明确拒绝（启动与拉取返回可区分的拒绝原因）',
  updated_by VARCHAR(64) NOT NULL COMMENT '最近操作者',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_compat_triple UNIQUE (model, from_version, to_version)
) COMMENT='固件兼容矩阵，启动发布与设备拉取时按型号+来源+目标校验';

CREATE TABLE IF NOT EXISTS device_trust (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '信任关系ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  model VARCHAR(64) NOT NULL COMMENT '固件适用型号',
  firmware_version VARCHAR(64) NOT NULL COMMENT '固件版本',
  chain_version INT NOT NULL COMMENT '设备信任的证书链版本，仅当任务使用的当前链在该集合内才允许新建任务',
  created_by VARCHAR(64) NOT NULL COMMENT '添加操作者（轮换时可随设备集合批量授权）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '添加时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_device_trust_chain UNIQUE (device_id, model, firmware_version, chain_version)
) COMMENT='设备信任集合：设备对具体固件版本具体证书链版本的信任关系';
