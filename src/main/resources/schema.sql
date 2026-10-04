-- 剧目提示点编排与排练执行 schema（H2 MODE=MySQL，进程退出后数据不保留）

CREATE TABLE IF NOT EXISTS cue_version (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '版本ID',
  status VARCHAR(16) NOT NULL COMMENT '版本状态：DRAFT草稿/FROZEN已冻结',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，服务器本地时区(Asia/Shanghai)'
) COMMENT='提示点编排版本，每次修改产生新版本';

CREATE TABLE IF NOT EXISTS cue_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '提示点ID',
  version_id BIGINT NOT NULL COMMENT '所属版本ID，关联cue_version.id',
  seq INT NOT NULL COMMENT '场次内递增序号，从1开始',
  cue_type VARCHAR(16) NOT NULL COMMENT '提示点类型：LIGHT灯光/SOUND音效',
  trigger_phrase VARCHAR(255) NOT NULL COMMENT '触发口令',
  duration_seconds INT NOT NULL COMMENT '持续秒数，有效范围1至600',
  CONSTRAINT fk_cue_item_version FOREIGN KEY (version_id) REFERENCES cue_version(id)
) COMMENT='版本内的提示点明细，未冻结前可含无效数据';

CREATE TABLE IF NOT EXISTS rehearsal_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '排练场次ID',
  version_id BIGINT NOT NULL COMMENT '绑定的编排版本ID，关联cue_version.id',
  status VARCHAR(16) NOT NULL COMMENT '场次状态：SCHEDULED未开始/RUNNING进行中/FINISHED已结束',
  last_executed_seq INT NOT NULL DEFAULT 0 COMMENT '已成功执行的最后一个提示点序号，0表示未执行',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，服务器本地时区',
  started_at TIMESTAMP NULL COMMENT '开始时间，未开始为NULL',
  finished_at TIMESTAMP NULL COMMENT '结束时间，未结束为NULL',
  CONSTRAINT fk_session_version FOREIGN KEY (version_id) REFERENCES cue_version(id)
) COMMENT='排练场次，开始时冻结提示点快照';

CREATE TABLE IF NOT EXISTS session_cue (
  session_id BIGINT NOT NULL COMMENT '排练场次ID，关联rehearsal_session.id',
  seq INT NOT NULL COMMENT '冻结快照中的提示点序号',
  cue_type VARCHAR(16) NOT NULL COMMENT '提示点类型快照：LIGHT/SOUND',
  trigger_phrase VARCHAR(255) NOT NULL COMMENT '触发口令快照',
  duration_seconds INT NOT NULL COMMENT '持续秒数快照，1至600',
  PRIMARY KEY (session_id, seq)
) COMMENT='排练开始时的提示点冻结快照，之后版本修改不影响本场';

CREATE TABLE IF NOT EXISTS cue_execution (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '执行记录ID',
  session_id BIGINT NOT NULL COMMENT '排练场次ID',
  seq INT NOT NULL COMMENT '已执行提示点序号',
  request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID，仅成功回报占用',
  payload_hash VARCHAR(64) NOT NULL COMMENT '回报参数指纹，用于同requestId异参判重',
  executed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间，服务器本地时区',
  CONSTRAINT uq_execution_session_seq UNIQUE (session_id, seq),
  CONSTRAINT uq_execution_request UNIQUE (session_id, request_id)
) COMMENT='提示点成功执行记录，同一序号只留一条成功记录';

CREATE TABLE IF NOT EXISTS cue_failure (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '失败记录ID',
  session_id BIGINT NOT NULL COMMENT '排练场次ID',
  seq INT NOT NULL COMMENT '回报失败的提示点序号',
  request_id VARCHAR(64) NOT NULL COMMENT '回报请求ID，失败不占用幂等键',
  reason VARCHAR(500) NOT NULL COMMENT '失败原因',
  reported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '回报时间，服务器本地时区'
) COMMENT='提示点执行失败回报，记录原因但不推进已执行序号';