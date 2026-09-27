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
  frozen_chain_id BIGINT NULL COMMENT '任务创建时刻固化的签名证书链ID；该固件未启用证书时为NULL，事后链撤销/轮换不改变本快照',
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

-- 固件版本、签名证书链、设备信任与兼容矩阵。证书相关时刻一律为 UTC，以 ISO-8601 字符串（VARCHAR）存储，
-- 区间采用半开 [valid_from_utc, valid_to_utc)：valid_to_utc 为 NULL 表示开放区间（至撤销或轮换截断为止）。

CREATE TABLE IF NOT EXISTS firmware_version (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '固件版本ID',
  model VARCHAR(64) NOT NULL COMMENT '固件适用设备型号',
  version VARCHAR(64) NOT NULL COMMENT '固件版本号，同型号内唯一',
  package_uri VARCHAR(512) NOT NULL COMMENT '固件包下载地址（合成示例）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_firmware_model_version UNIQUE (model, version)
) COMMENT='固件版本登记表';

CREATE TABLE IF NOT EXISTS firmware_compatibility (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '兼容关系ID',
  model VARCHAR(64) NOT NULL COMMENT '设备型号',
  from_version VARCHAR(64) NOT NULL COMMENT '可升级的来源固件版本',
  to_version VARCHAR(64) NOT NULL COMMENT '目标固件版本',
  allowed TINYINT NOT NULL COMMENT '是否允许该升级路径：1允许，0拒绝（显式不兼容）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_compat_path UNIQUE (model, from_version, to_version)
) COMMENT='固件兼容矩阵；未配置的升级路径沿用历史放行，存在显式0记录则拒绝';

CREATE TABLE IF NOT EXISTS firmware_cert_chain (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '证书链ID',
  firmware_id BIGINT NOT NULL COMMENT '所属固件版本ID',
  chain_version INT NOT NULL COMMENT '链版本号，同一固件内从1开始递增',
  subject_dn VARCHAR(256) NOT NULL COMMENT '签名证书主题（合成元数据）',
  issuer_dn VARCHAR(256) NOT NULL COMMENT '签发者主题（合成元数据）',
  fingerprint_sha256 VARCHAR(96) NOT NULL COMMENT '签名证书SHA-256指纹（十六进制，合成值）',
  valid_from_utc VARCHAR(40) NOT NULL COMMENT '生效时刻，UTC，ISO-8601；本时刻起链可用于新拉取/新建任务',
  valid_to_utc VARCHAR(40) NULL COMMENT '到期时刻，UTC，半开区间：该时刻起不可新建任务；NULL表示开放区间。撤销或被轮换时写入截止时刻',
  status VARCHAR(16) NOT NULL COMMENT 'ACTIVE有效（在区间内才是当前链）；REVOKED已撤销（终态，区间提前截止）',
  revoked_at_utc VARCHAR(40) NULL COMMENT '撤销时刻，UTC；未撤销为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_cert_firmware_chain UNIQUE (firmware_id, chain_version)
) COMMENT='固件签名证书链；同一固件任意UTC时刻至多一条ACTIVE链的半开区间覆盖该时刻';

CREATE TABLE IF NOT EXISTS cert_chain_device (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '授权记录ID',
  chain_id BIGINT NOT NULL COMMENT '证书链ID',
  device_id VARCHAR(64) NOT NULL COMMENT '被授权使用该链的设备ID',
  PRIMARY KEY (id),
  CONSTRAINT uk_chain_device UNIQUE (chain_id, device_id)
) COMMENT='证书链授权设备集合；集合为空表示对该链适用设备全开放，非空时仅集合内设备在信任校验中通过';

CREATE TABLE IF NOT EXISTS device_trust (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '信任记录ID',
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
  anchor_fingerprint VARCHAR(96) NOT NULL COMMENT '设备信任锚（根证书SHA-256指纹，合成值）',
  trusted TINYINT NOT NULL COMMENT '最终信任状态：1信任，0不信任；批量启动任一设备为0即整批422',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_trust_device UNIQUE (device_id)
) COMMENT='设备信任集合/最终信任状态';

CREATE TABLE IF NOT EXISTS receipt_review (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '复核记录ID',
  task_id BIGINT NOT NULL COMMENT '关联任务ID',
  chain_id BIGINT NOT NULL COMMENT '任务冻结的被撤销证书链ID',
  status VARCHAR(32) NOT NULL COMMENT 'PENDING_REVIEW待人工确认；CONFIRMED已确认（设备版本更新）；REJECTED已拒绝（不更新）',
  reject_reason VARCHAR(256) NULL COMMENT '拒绝原因；确认或待复核时为NULL',
  reviewed_by VARCHAR(64) NULL COMMENT '复核操作者；待复核时为NULL',
  reviewed_at_utc VARCHAR(40) NULL COMMENT '复核时刻，UTC；待复核时为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次被标记复核的时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_review_task UNIQUE (task_id)
) COMMENT='撤销链任务回执的人工复核单，每任务至多一条';
