-- 固件灰度投放 schema（H2，MODE=MySQL）。业务时刻字段（暂停/恢复/快照解析/标签修订）均为 UTC ISO-8601；
-- created_at/updated_at 为服务器本地时间。时间区间查询一律采用左闭右开 [from, to)。

CREATE TABLE IF NOT EXISTS device (
  device_id VARCHAR(64) NOT NULL COMMENT '设备唯一标识',
  model VARCHAR(64) NOT NULL COMMENT '设备型号，登记后不可修改',
  current_version VARCHAR(64) NOT NULL COMMENT '设备当前固件版本',
  bucket_no INT NOT NULL COMMENT '灰度分桶号，取值0~99，登记后不可修改',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
  PRIMARY KEY (device_id)
) COMMENT='设备登记表';

CREATE TABLE IF NOT EXISTS device_tag (
  device_id VARCHAR(64) NOT NULL COMMENT '设备ID，外键关联设备登记，设备删除时级联清除',
  tag VARCHAR(64) NOT NULL COMMENT '规范化标签：trim后1~64字符，仅允许字母数字._:-，大小写敏感、原样保留',
  PRIMARY KEY (device_id, tag),
  CONSTRAINT fk_device_tag_device FOREIGN KEY (device_id)
      REFERENCES device (device_id) ON DELETE CASCADE
) COMMENT='设备规范化标签集合，每台设备每个标签一行，业务读取时按标签字典序排序';

CREATE TABLE IF NOT EXISTS tag_dict_meta (
  id INT NOT NULL COMMENT '单行表固定为1',
  version BIGINT NOT NULL COMMENT '标签字典当前版本号，初始0；每次批量标签修订整批提交成功后加一，仅新版本发布单读取新值',
  PRIMARY KEY (id)
) COMMENT='标签字典版本单行元数据，串行化标签修订与目标快照解析';

-- H2(MODE=MySQL)：内存库初始化时写入版本0的单行；行已存在（同一内存库内上下文重启）时不重置版本。
INSERT INTO tag_dict_meta (id, version)
SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM tag_dict_meta WHERE id = 1);

CREATE TABLE IF NOT EXISTS tag_revision (
  revision BIGINT NOT NULL COMMENT '修订版本号，等于本次提交后字典版本，从1开始，只增不改',
  operator VARCHAR(64) NOT NULL COMMENT '提交本批标签修改的操作者',
  requested_count INT NOT NULL COMMENT '本批要求修改的设备项数（要求值）',
  changed_count INT NOT NULL COMMENT '本批标签实际发生变化的设备项数（实际值）；requested_count-changed_count为无变化差额',
  changed_at_utc VARCHAR(40) NOT NULL COMMENT '修订提交时刻，UTC，ISO-8601格式（如2026-09-28T07:00:00Z）',
  PRIMARY KEY (revision)
) COMMENT='标签字典修订历史，只增不改；诊断区间查询按该时刻做左闭右开过滤';

CREATE TABLE IF NOT EXISTS release_order (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '发布单ID',
  version INT NOT NULL COMMENT '发布单版本号，从1开始，每次扩量或人工恢复成功加一',
  model VARCHAR(64) NOT NULL COMMENT '目标设备型号',
  from_version VARCHAR(64) NOT NULL COMMENT '来源固件版本',
  to_version VARCHAR(64) NOT NULL COMMENT '目标固件版本，必须与来源版本不同',
  ratio INT NOT NULL COMMENT '投放比例，取值0~100，只增不减；实际下发还需分桶号小于比例（左闭右开：0<=bucket<ratio）',
  status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE投放中，PAUSED失败率自动暂停，CANCELLED已取消（终态）',
  sample_floor INT NOT NULL COMMENT '失败率统计样本下限，取值2~100；本轮样本数达到下限才评估暂停',
  failure_threshold_percent INT NOT NULL COMMENT '失败率阈值百分比，取值1~100；FAILED×100>=样本数×阈值时自动暂停',
  monitor_round INT NOT NULL DEFAULT 1 COMMENT '当前监控轮次，从1开始，人工恢复后加一并清零统计',
  round_success INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入SUCCESS的任务数，重复回执不重复计数',
  round_failed INT NOT NULL DEFAULT 0 COMMENT '当前监控轮次内首次进入FAILED的任务数，重复回执不重复计数',
  active_model VARCHAR(64) NULL COMMENT 'ACTIVE或PAUSED时等于model，取消后置NULL；用于同型号至多一张未终结发布单的唯一约束',
  operator VARCHAR(64) NULL COMMENT '创建发布单的操作者；旧客户端未传为NULL；纳入幂等指纹',
  tag_expression CLOB NULL COMMENT '目标标签表达式JSON快照（规范化排序后的include/exclude/anyOf三组标签）；NULL表示未启用目标策略，按型号动态匹配',
  tag_dict_version BIGINT NULL COMMENT '目标快照解析时刻的标签字典版本；NULL表示未启用目标策略；后续标签修订不追溯改写该值',
  resolved_at_utc VARCHAR(40) NULL COMMENT '目标设备快照解析时刻，UTC，ISO-8601；NULL表示未启用目标策略；快照此后不再重新解析',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近变更时间',
  PRIMARY KEY (id)
) COMMENT='固件灰度发布单';

CREATE UNIQUE INDEX IF NOT EXISTS uk_release_active_model ON release_order (active_model);

CREATE TABLE IF NOT EXISTS release_target (
  release_id BIGINT NOT NULL COMMENT '所属发布单ID',
  device_id VARCHAR(64) NOT NULL COMMENT '快照成员设备ID，仅这些设备可被该发布单下发',
  ordinal INT NOT NULL COMMENT '规范排序序号，从0开始按device_id字典序升序，供明细查询稳定排序',
  PRIMARY KEY (release_id, device_id),
  CONSTRAINT fk_release_target_release FOREIGN KEY (release_id)
      REFERENCES release_order (id) ON DELETE CASCADE
) COMMENT='发布单目标设备快照：创建时按标签表达式一次性解析生成，标签修订与设备标签变更不追溯扩大或缩小';

CREATE INDEX IF NOT EXISTS idx_release_target_device ON release_target (device_id);

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
  fingerprint VARCHAR(128) NOT NULL COMMENT '请求参数指纹：操作者/操作类型/规范化对象集合及结果相关字段组成键排序JSON后取SHA-256十六进制，同键异参返回409',
  response_body CLOB NOT NULL COMMENT '成功响应快照（JSON），用于同键同参重放',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (request_id)
) COMMENT='写操作幂等去重记录，失败不占键';
