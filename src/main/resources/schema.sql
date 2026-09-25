-- 固件灰度投放 schema（H2，MODE=MySQL）。时间均为服务器本地时区（Asia/Shanghai）。

CREATE TABLE IF NOT EXISTS device (
  device_id VARCHAR(64) NOT NULL COMMENT '设备唯一标识',
  model VARCHAR(64) NOT NULL COMMENT '设备型号，登记后不可修改',
  current_version VARCHAR(64) NOT NULL COMMENT '设备当前固件版本',
  bucket_no INT NOT NULL COMMENT '灰度分桶号，取值0~99，登记后不可修改',
  region VARCHAR(64) NOT NULL COMMENT '区域标识，登记后不可修改，用于区域带宽限流',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
  PRIMARY KEY (device_id)
) COMMENT='设备登记表';

CREATE TABLE IF NOT EXISTS release_order (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '发布单ID',
  version INT NOT NULL COMMENT '发布单版本号，从1开始，每次扩量成功加一',
  model VARCHAR(64) NOT NULL COMMENT '目标设备型号',
  from_version VARCHAR(64) NOT NULL COMMENT '来源固件版本',
  to_version VARCHAR(64) NOT NULL COMMENT '目标固件版本，必须与来源版本不同',
  ratio INT NOT NULL COMMENT '投放比例，取值0~100，只增不减',
  status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE投放中，CANCELLED已取消',
  active_model VARCHAR(64) NULL COMMENT 'ACTIVE时等于model，取消后置NULL；用于同型号至多一张ACTIVE发布单的唯一约束',
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

CREATE TABLE IF NOT EXISTS idempotency_record (
  request_id VARCHAR(64) NOT NULL COMMENT '全局唯一请求ID',
  api VARCHAR(64) NOT NULL COMMENT '接口标识',
  fingerprint VARCHAR(256) NOT NULL COMMENT '请求参数指纹，同键异参返回409',
  response_body CLOB NOT NULL COMMENT '成功响应快照（JSON），用于同键同参重放',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (request_id)
) COMMENT='写操作幂等去重记录，失败不占键';

CREATE TABLE IF NOT EXISTS release_region_limit (
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  region VARCHAR(64) NOT NULL COMMENT '区域标识',
  max_in_flight INT NOT NULL COMMENT '该区域同时进行中（已下发未完成）任务数上限，取值1~1000',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (release_id, region)
) COMMENT='发布单区域带宽限流上限配置，未配置的区域不限流';

CREATE TABLE IF NOT EXISTS region_wait_record (
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '被限流的设备ID',
  region VARCHAR(64) NOT NULL COMMENT '设备所属区域标识（冗余自设备表，便于按区域查询）',
  waited_at TIMESTAMP(6) NOT NULL COMMENT '最近一次被限流时刻（服务器本地时区），公平排队按此时刻升序、同刻按设备ID字典序',
  PRIMARY KEY (release_id, device_id)
) COMMENT='区域限流等待记录，设备成功领取任务后删除';

CREATE TABLE IF NOT EXISTS region_throttle_event (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '限流事件ID',
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  region VARCHAR(64) NOT NULL COMMENT '区域标识',
  device_id VARCHAR(64) NOT NULL COMMENT '被限流的设备ID',
  throttled_at TIMESTAMP(6) NOT NULL COMMENT '限流发生时刻（服务器本地时区）',
  PRIMARY KEY (id)
) COMMENT='区域限流历史事件，每次返回THROTTLED追加一条';

CREATE INDEX IF NOT EXISTS idx_throttle_event_release_region ON region_throttle_event (release_id, region);
