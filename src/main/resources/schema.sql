-- 固件灰度投放 schema（H2，MODE=MySQL）。业务时刻字段（暂停/恢复/快照解析/标签修订）均为 UTC，ISO-8601 字符串。

CREATE TABLE IF NOT EXISTS device (
  device_id VARCHAR(64) NOT NULL COMMENT '设备唯一标识',
  model VARCHAR(64) NOT NULL COMMENT '设备型号，登记后不可修改',
  current_version VARCHAR(64) NOT NULL COMMENT '设备当前固件版本',
  bucket_no INT NOT NULL COMMENT '灰度分桶号，取值0~99，登记后不可修改',
  labels CLOB NOT NULL DEFAULT '[]' COMMENT '规范化标签集合JSON数组（trim、去空白、去重并按字典序排序）；无标签为[]，可随时整体替换，不追溯历史发布单快照',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
  PRIMARY KEY (device_id)
) COMMENT='设备登记表';

CREATE TABLE IF NOT EXISTS release_order (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '发布单ID',
  version INT NOT NULL COMMENT '发布单版本号，从1开始，每次扩量或人工恢复成功加一',
  model VARCHAR(64) NOT NULL COMMENT '目标设备型号',
  from_version VARCHAR(64) NOT NULL COMMENT '来源固件版本',
  to_version VARCHAR(64) NOT NULL COMMENT '目标固件版本，必须与来源版本不同',
  ratio INT NOT NULL COMMENT '投放比例，取值0~100，只增不减；在目标快照成员内部按分桶号生效',
  status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE投放中，PAUSED失败率自动暂停，CANCELLED已取消（终态）',
  sample_floor INT NOT NULL COMMENT '失败率统计样本下限，取值2~100；本轮样本数达到下限才评估暂停',
  failure_threshold_percent INT NOT NULL COMMENT '失败率阈值百分比，取值1~100；FAILED×100>=样本数×阈值时自动暂停',
  monitor_round INT NOT NULL DEFAULT 1 COMMENT '当前监控轮次，从1开始，人工恢复后加一并清零统计',
  round_success INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入SUCCESS的任务数，重复回执不重复计数',
  round_failed INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入FAILED的任务数，重复回执不重复计数',
  label_dict_version BIGINT NOT NULL DEFAULT 0 COMMENT '快照解析时刻的标签字典版本；快照固化后标签修订不改变本值，0表示字典尚无标签',
  target_count INT NOT NULL DEFAULT 0 COMMENT '目标快照实际设备数（实际值）；创建时要求至少1台，为0则整体422且不落库',
  resolved_at_utc VARCHAR(40) NULL COMMENT '目标快照解析时刻，UTC，ISO-8601格式（如2026-09-28T10:15:30Z）；未解析为NULL',
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
) COMMENT='设备投放任务，同设备同发布单最多一条；设备标签后续修改不改变本表已有行';

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

-- 标签字典：全局标签集合与单调递增版本；仅当一次标签修订实际引入新标签时版本加一。
CREATE TABLE IF NOT EXISTS label_dict_meta (
  id INT NOT NULL COMMENT '固定为1的单行版本计数器',
  version BIGINT NOT NULL COMMENT '标签字典当前版本，从0开始；引入新标签的修订提交后加一',
  PRIMARY KEY (id)
) COMMENT='标签字典版本单行表';

MERGE INTO label_dict_meta (id, version) KEY(id) VALUES (1, 0);

CREATE TABLE IF NOT EXISTS label_dict_tag (
  tag VARCHAR(64) NOT NULL COMMENT '规范化标签（trim后，非空，大小写敏感）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '标签首次进入字典时间',
  PRIMARY KEY (tag)
) COMMENT='标签字典：系统中出现过的全部规范化标签';

CREATE TABLE IF NOT EXISTS label_revision (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '标签修订事件ID',
  event_type VARCHAR(32) NOT NULL COMMENT '修订来源：DEVICE_REGISTER设备登记引入新标签；LABEL_BATCH_UPDATE批量标签修改',
  operator VARCHAR(64) NULL COMMENT '操作者标识；未提供为NULL',
  device_count INT NOT NULL COMMENT '本次修订涉及的去重设备数（要求值）',
  new_tag_count INT NOT NULL COMMENT '本次修订实际引入的新标签数（实际值，为0时字典版本不推进；批量修改允许为0）',
  dict_version BIGINT NOT NULL COMMENT '修订提交后的标签字典版本（无新标签时与修订前相同）',
  revised_at_utc VARCHAR(40) NOT NULL COMMENT '修订提交时刻，UTC，ISO-8601格式',
  PRIMARY KEY (id)
) COMMENT='标签字典修订事件，只增不改；诊断查询时间区间按左闭右开[from,to)过滤';

-- 发布单目标快照：创建发布单时解析一次并固化，标签/设备后续修订不追溯重算。
CREATE TABLE IF NOT EXISTS release_target_snapshot (
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  expression_json CLOB NOT NULL COMMENT '固化的规范化标签表达式JSON：mustInclude必须全部包含、mustExclude不得包含任一、includeAny至少包含一项（空数组表示不加该类约束）',
  label_dict_version BIGINT NOT NULL COMMENT '解析时刻标签字典版本',
  target_count INT NOT NULL COMMENT '解析时刻实际命中的目标设备数',
  resolved_at_utc VARCHAR(40) NOT NULL COMMENT '快照解析时刻，UTC，ISO-8601格式',
  PRIMARY KEY (release_id)
) COMMENT='发布单目标设备快照主表，每发布单一行，只生成一次';

CREATE TABLE IF NOT EXISTS release_target_device (
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '快照成员设备ID（解析时刻同型号、同来源版本且标签表达式匹配）',
  PRIMARY KEY (release_id, device_id)
) COMMENT='发布单目标快照成员表；后续拉取仅对本表成员下发，标签变更不追溯扩大或缩小';

CREATE TABLE IF NOT EXISTS idempotency_record (
  request_id VARCHAR(64) NOT NULL COMMENT '全局唯一请求ID',
  api VARCHAR(64) NOT NULL COMMENT '接口标识',
  fingerprint CLOB NOT NULL COMMENT '结构化JSON指纹（含操作者、操作类型、规范化对象集合与全部结果相关字段），同键异参返回409',
  response_body CLOB NOT NULL COMMENT '成功响应快照（JSON），用于同键同参重放',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (request_id)
) COMMENT='写操作幂等去重记录，失败不占键';
