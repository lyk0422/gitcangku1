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
  ratio INT NOT NULL COMMENT '当前生效投放比例，取值0~100，只增不减；金丝雀发布单等于当前已解锁最高级别的比例',
  status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE投放中，CANCELLED已取消，COMPLETED已完成（金丝雀最高级别推进后的终态）',
  active_model VARCHAR(64) NULL COMMENT 'ACTIVE时等于model，取消或完成后置NULL；用于同型号至多一张ACTIVE发布单的唯一约束',
  level_count INT NOT NULL DEFAULT 0 COMMENT '金丝雀验证级别总数，0表示非金丝雀发布单',
  unlocked_level INT NOT NULL DEFAULT 0 COMMENT '当前已解锁的最高金丝雀级别，0表示非金丝雀发布单',
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

CREATE TABLE IF NOT EXISTS canary_level (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  level_no INT NOT NULL COMMENT '级别序号，从1开始',
  ratio INT NOT NULL COMMENT '该级别投放比例，取值1~100，各级别严格递增',
  min_samples INT NOT NULL COMMENT '最小验证样本数，取值1~50',
  max_failure_rate INT NOT NULL COMMENT '失败率上限，单位百分之一（百分数），取值1~100',
  sample_count INT NOT NULL DEFAULT 0 COMMENT '首次进入SUCCESS/FAILED终态的回执样本数，推进不重置',
  fail_count INT NOT NULL DEFAULT 0 COMMENT '样本中结果为FAILED的数量',
  unlocked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已解锁，创建时仅第1级解锁',
  unlocked_at TIMESTAMP NULL COMMENT '解锁时间（服务器本地时区），未解锁为NULL',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_canary_release_level UNIQUE (release_id, level_no)
) COMMENT='金丝雀验证级别及样本统计，同发布单内级别序号唯一';

CREATE TABLE IF NOT EXISTS canary_promotion (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  from_level INT NOT NULL COMMENT '推进前已解锁的最高级别',
  to_level INT NOT NULL COMMENT '推进目标级别；等于级别总数+1表示发布单进入COMPLETED终态',
  promote_key VARCHAR(64) NOT NULL COMMENT '推进幂等键，全局唯一，失败不占键',
  sample_count INT NOT NULL COMMENT '推进成功时当前级别样本数快照',
  fail_count INT NOT NULL COMMENT '推进成功时当前级别失败样本数快照',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '推进时间（服务器本地时区）',
  PRIMARY KEY (id),
  CONSTRAINT uk_canary_promote_key UNIQUE (promote_key)
) COMMENT='金丝雀推进历史，只增不改';

CREATE TABLE IF NOT EXISTS idempotency_record (
  request_id VARCHAR(64) NOT NULL COMMENT '全局唯一请求ID',
  api VARCHAR(64) NOT NULL COMMENT '接口标识',
  fingerprint VARCHAR(256) NOT NULL COMMENT '请求参数指纹，同键异参返回409',
  response_body CLOB NOT NULL COMMENT '成功响应快照（JSON），用于同键同参重放',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (request_id)
) COMMENT='写操作幂等去重记录，失败不占键';
