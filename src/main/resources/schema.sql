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
  version INT NOT NULL COMMENT '发布单版本号，从1开始，每次扩量成功加一',
  model VARCHAR(64) NOT NULL COMMENT '目标设备型号',
  from_version VARCHAR(64) NOT NULL COMMENT '来源固件版本',
  to_version VARCHAR(64) NOT NULL COMMENT '目标固件版本，必须与来源版本不同',
  ratio INT NOT NULL COMMENT '当前生效投放比例，取值0~100；分级发布单等于当前解锁级别比例，只增不减',
  status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE投放中，PAUSED失败自动暂停，COMPLETED已完成，CANCELLED已取消',
  current_level INT NOT NULL DEFAULT 1 COMMENT '当前已解锁最高金丝雀级别，从1开始；未配置分级时恒为1',
  active_model VARCHAR(64) NULL COMMENT 'ACTIVE/PAUSED时等于model，完成或取消后置NULL；用于同型号至多一张未终结发布单的唯一约束',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id)
) COMMENT='固件灰度发布单';

CREATE UNIQUE INDEX IF NOT EXISTS uk_release_active_model ON release_order (active_model);

CREATE TABLE IF NOT EXISTS canary_level (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '级别ID',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  level_no INT NOT NULL COMMENT '级别序号，从1开始递增',
  ratio INT NOT NULL COMMENT '该级别投放比例，取值1~100，同一发布单内逐级递增',
  min_samples INT NOT NULL COMMENT '推进所需最小验证样本数，取值1~50',
  max_failure_rate INT NOT NULL COMMENT '失败率上限，单位百分比，取值1~100；超过即触发失败自动暂停',
  sample_count INT NOT NULL DEFAULT 0 COMMENT '该级别已计入的首次终结回执样本数，推进后不重置',
  failed_count INT NOT NULL DEFAULT 0 COMMENT '该级别样本中首次结果为FAILED的数量',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_canary_level_release_no UNIQUE (release_id, level_no)
) COMMENT='金丝雀分级验证级别，按级别顺序解锁';

CREATE TABLE IF NOT EXISTS canary_promotion (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '推进记录ID',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  from_level INT NOT NULL COMMENT '推进前已解锁最高级别',
  to_level INT NULL COMMENT '新解锁级别；推进到终态COMPLETED时为NULL',
  action VARCHAR(16) NOT NULL COMMENT '动作：UNLOCK解锁下一级别，COMPLETE进入完成终态',
  sample_count INT NOT NULL COMMENT '推进时当前级别样本数快照',
  failed_count INT NOT NULL COMMENT '推进时当前级别失败样本数快照',
  promote_key VARCHAR(64) NOT NULL COMMENT '本次推进的幂等键（全局唯一）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '推进时间',
  PRIMARY KEY (id)
) COMMENT='金丝雀级别推进历史，只增不改';

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

CREATE TABLE IF NOT EXISTS idempotency_record (
  request_id VARCHAR(64) NOT NULL COMMENT '全局唯一请求ID',
  api VARCHAR(64) NOT NULL COMMENT '接口标识',
  fingerprint VARCHAR(256) NOT NULL COMMENT '请求参数指纹，同键异参返回409',
  response_body CLOB NOT NULL COMMENT '成功响应快照（JSON），用于同键同参重放',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (request_id)
) COMMENT='写操作幂等去重记录，失败不占键';
