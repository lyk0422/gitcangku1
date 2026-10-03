-- 剧目提示点编排与排练执行的库表结构（H2 MODE=MySQL，兼容 MySQL 语法）。
-- 时间字段均按应用时区 Asia/Shanghai 写入；NULL 含义见各字段 COMMENT。

CREATE TABLE IF NOT EXISTS cue_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '版本主键',
    production VARCHAR(128) NOT NULL COMMENT '剧目名称',
    version_no INT NOT NULL COMMENT '剧目内递增版本号，从 1 开始',
    status VARCHAR(16) NOT NULL COMMENT '版本状态：DRAFT 草稿 / FROZEN 已冻结，仅冻结版本可绑定场次',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（Asia/Shanghai）',
    CONSTRAINT uk_version_prod_no UNIQUE (production, version_no)
) COMMENT='提示点编排版本，每次修改产生新版本';

CREATE TABLE IF NOT EXISTS cue_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '提示点主键',
    version_id BIGINT NOT NULL COMMENT '所属版本 ID，关联 cue_version.id',
    seq INT NOT NULL COMMENT '场次内递增序号，从 1 开始，版本内唯一',
    cue_type VARCHAR(32) NOT NULL COMMENT '提示点类型：LIGHT 灯光 / SOUND 音效',
    trigger_phrase VARCHAR(256) NOT NULL COMMENT '触发口令',
    duration_seconds INT NOT NULL COMMENT '持续秒数，冻结时校验必须为 1 至 600',
    CONSTRAINT fk_item_version FOREIGN KEY (version_id) REFERENCES cue_version (id)
) COMMENT='版本内的提示点明细';

CREATE TABLE IF NOT EXISTS rehearsal_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '场次主键',
    production VARCHAR(128) NOT NULL COMMENT '剧目名称',
    version_id BIGINT NOT NULL COMMENT '绑定的提示点版本 ID，关联 cue_version.id',
    status VARCHAR(16) NOT NULL COMMENT '场次状态：PENDING 未开始 / RUNNING 排练中 / ENDED 已结束',
    completed BIT NULL COMMENT '结束时是否已按序执行完全部提示点；未结束为 NULL',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（Asia/Shanghai）',
    started_at TIMESTAMP NULL COMMENT '开始时间；未开始为 NULL',
    ended_at TIMESTAMP NULL COMMENT '结束时间；未结束为 NULL',
    CONSTRAINT fk_session_version FOREIGN KEY (version_id) REFERENCES cue_version (id)
) COMMENT='排练场次，开始时冻结提示点快照';

CREATE TABLE IF NOT EXISTS session_cue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '快照主键',
    session_id BIGINT NOT NULL COMMENT '所属场次 ID，关联 rehearsal_session.id',
    seq INT NOT NULL COMMENT '冻结时的提示点序号，场次内唯一',
    cue_type VARCHAR(32) NOT NULL COMMENT '提示点类型：LIGHT 灯光 / SOUND 音效',
    trigger_phrase VARCHAR(256) NOT NULL COMMENT '触发口令',
    duration_seconds INT NOT NULL COMMENT '持续秒数，1 至 600',
    CONSTRAINT uk_session_seq UNIQUE (session_id, seq),
    CONSTRAINT fk_scue_session FOREIGN KEY (session_id) REFERENCES rehearsal_session (id)
) COMMENT='排练开始时冻结的提示点顺序快照，之后不随版本修改变化';

CREATE TABLE IF NOT EXISTS cue_execution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '回报主键',
    session_id BIGINT NOT NULL COMMENT '所属场次 ID，关联 rehearsal_session.id',
    seq INT NOT NULL COMMENT '回报的提示点序号',
    request_id VARCHAR(64) NOT NULL COMMENT '操作员幂等请求 ID',
    operator VARCHAR(64) NOT NULL COMMENT '回报操作员',
    result VARCHAR(8) NOT NULL COMMENT '回报结果：SUCCESS 成功 / FAILED 失败',
    fail_reason VARCHAR(512) NULL COMMENT '失败原因，仅 FAILED 有值，SUCCESS 为 NULL',
    success_request_id VARCHAR(64) NULL COMMENT '成功幂等键：SUCCESS 时等于 request_id，FAILED 为 NULL（失败不消耗幂等键）',
    success_seq INT NULL COMMENT '成功序号占位：SUCCESS 时等于 seq，FAILED 为 NULL（保证同一序号仅一条成功记录）',
    reported_at TIMESTAMP NOT NULL COMMENT '回报时间（Asia/Shanghai）',
    CONSTRAINT uk_exec_req UNIQUE (session_id, success_request_id),
    CONSTRAINT uk_exec_seq UNIQUE (session_id, success_seq),
    CONSTRAINT fk_exec_session FOREIGN KEY (session_id) REFERENCES rehearsal_session (id)
) COMMENT='提示点执行回报记录，成功记录唯一，失败可重复回报';