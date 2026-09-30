-- 赛事成绩封榜领域表结构。
-- 本地默认以 H2 内存库运行：jdbc:h2:mem:race_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
-- 标识符统一小写；时间字段均为 Unix 毫秒时间戳（BIGINT），不涉及时区换算。

CREATE TABLE IF NOT EXISTS race (
    race_id VARCHAR(64) NOT NULL COMMENT '赛事ID，全局唯一',
    version INT NOT NULL COMMENT '版本号，从1开始，每次写操作加一',
    status VARCHAR(16) NOT NULL COMMENT '赛事状态：OPEN-开放可写，SEALED-已封榜只读',
    created_at BIGINT NOT NULL COMMENT '创建时间，Unix毫秒时间戳',
    CONSTRAINT pk_race PRIMARY KEY (race_id)
);

CREATE TABLE IF NOT EXISTS race_category (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    category_code VARCHAR(64) NOT NULL COMMENT '组别代码，赛事内唯一；未指定时默认 OPEN',
    capacity INT COMMENT '组别在册容量（人数）；NULL 表示不限容量',
    created_at BIGINT NOT NULL COMMENT '创建时间，Unix毫秒时间戳',
    CONSTRAINT pk_race_category PRIMARY KEY (race_id, category_code),
    CONSTRAINT fk_category_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS runner (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '参赛号，同一赛事内唯一',
    finish_time_ms BIGINT COMMENT '原始完赛耗时（毫秒，取值1~86400000）；NULL表示计时缺失，状态UNTIMED',
    category_code VARCHAR(64) NOT NULL DEFAULT 'OPEN' COMMENT '当前所属组别；组别重分批准后整体迁移，历史不复制',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '在赛状态：ACTIVE-在赛，WITHDRAWN-已退赛；退赛后不再参与排名与封榜',
    created_at BIGINT NOT NULL COMMENT '登记时间，Unix毫秒时间戳',
    updated_at BIGINT NOT NULL COMMENT '最近一次计时修订时间，Unix毫秒时间戳',
    CONSTRAINT pk_runner PRIMARY KEY (id),
    CONSTRAINT uk_runner_race_bib UNIQUE (race_id, bib),
    CONSTRAINT fk_runner_race FOREIGN KEY (race_id) REFERENCES race (race_id),
    CONSTRAINT fk_runner_category FOREIGN KEY (race_id, category_code) REFERENCES race_category (race_id, category_code)
);

CREATE TABLE IF NOT EXISTS penalty (
    penalty_id VARCHAR(64) NOT NULL COMMENT '处罚ID，全局唯一',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '被罚选手参赛号',
    type VARCHAR(16) NOT NULL COMMENT '处罚类型：ADD_TIME-加时，DISQUALIFY-取消资格',
    amount_ms BIGINT COMMENT '加时时长（毫秒，取值1~3600000）；取消资格时为NULL',
    category_code VARCHAR(64) NOT NULL DEFAULT 'OPEN' COMMENT '处罚归属组别（登记时选手所在组别，随组别重分整体迁移）',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销：FALSE-生效中，TRUE-已撤销',
    created_at BIGINT NOT NULL COMMENT '处罚新增时间，Unix毫秒时间戳',
    revoked_at BIGINT COMMENT '处罚撤销时间，Unix毫秒时间戳；未撤销为NULL',
    CONSTRAINT pk_penalty PRIMARY KEY (penalty_id),
    CONSTRAINT fk_penalty_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    CONSTRAINT fk_penalty_category FOREIGN KEY (race_id, category_code) REFERENCES race_category (race_id, category_code),
    INDEX idx_penalty_race_bib (race_id, bib)
);

CREATE TABLE IF NOT EXISTS result_snapshot (
    race_id VARCHAR(64) NOT NULL COMMENT '赛事ID，每个赛事封榜后仅一份只读快照',
    version INT NOT NULL COMMENT '封榜后的赛事版本号',
    sealed_at BIGINT NOT NULL COMMENT '封榜时间，Unix毫秒时间戳',
    CONSTRAINT pk_result_snapshot PRIMARY KEY (race_id),
    CONSTRAINT fk_snapshot_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS result_snapshot_entry (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '参赛号',
    rank_no INT COMMENT '名次（从1开始，并列同名次并跳号，如1、1、3）；未计时/漏点/取消资格为NULL',
    status VARCHAR(24) NOT NULL COMMENT '成绩状态：RANKED-参与排名，UNTIMED-计时缺失，MISSING_CHECKPOINT-有完赛但未覆盖全部检查点，DISQUALIFIED-取消资格',
    finish_time_ms BIGINT COMMENT '原始完赛耗时（毫秒）；计时缺失为NULL',
    penalty_ms BIGINT NOT NULL COMMENT '生效（未撤销）加时处罚合计毫秒数，无加时为0',
    total_time_ms BIGINT COMMENT '总耗时=原始完赛耗时+生效加时（毫秒）；未排名时为NULL',
    checkpoint_count INT NOT NULL DEFAULT 0 COMMENT '赛事检查点总数；未配置检查点为0',
    covered_checkpoint_count INT NOT NULL DEFAULT 0 COMMENT '封榜时该选手已覆盖检查点数量；缺失检查点由result_snapshot_checkpoint中elapsed_millis为NULL的行固化',
    display_order INT NOT NULL COMMENT '展示顺序，从0开始：先名次顺序，并列者按参赛号字典序，其余按参赛号字典序',
    CONSTRAINT pk_snapshot_entry PRIMARY KEY (race_id, bib),
    CONSTRAINT fk_snapshot_entry_snapshot FOREIGN KEY (race_id) REFERENCES result_snapshot (race_id)
);

CREATE TABLE IF NOT EXISTS checkpoint (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID；未配置检查点的赛事无任何行',
    checkpoint_code VARCHAR(64) NOT NULL COMMENT '检查点代码，同一赛事内唯一',
    position INT NOT NULL COMMENT '检查点顺序，从1连续递增，配置后不可修改',
    created_at BIGINT NOT NULL COMMENT '配置时间，Unix毫秒时间戳',
    CONSTRAINT pk_checkpoint PRIMARY KEY (race_id, checkpoint_code),
    CONSTRAINT uk_checkpoint_position UNIQUE (race_id, position),
    CONSTRAINT fk_checkpoint_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS checkpoint_timing (
    timing_id VARCHAR(128) NOT NULL COMMENT '分段通过记录ID，全局唯一（第二层幂等键）',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '选手参赛号',
    checkpoint_code VARCHAR(64) NOT NULL COMMENT '通过的检查点代码',
    position INT NOT NULL COMMENT '检查点顺序（配置时固化），用于严格递增校验',
    elapsed_millis BIGINT NOT NULL COMMENT '通过该检查点的累计耗时（毫秒，1~86400000），必须小于该选手原始完赛耗时',
    category_code VARCHAR(64) NOT NULL DEFAULT 'OPEN' COMMENT '分段归属组别（提交时选手所在组别，随组别重分整体迁移）',
    created_at BIGINT NOT NULL COMMENT '记录提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_checkpoint_timing PRIMARY KEY (timing_id),
    CONSTRAINT uk_timing_runner_checkpoint UNIQUE (race_id, bib, checkpoint_code),
    CONSTRAINT fk_timing_checkpoint FOREIGN KEY (race_id, checkpoint_code) REFERENCES checkpoint (race_id, checkpoint_code),
    CONSTRAINT fk_timing_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    CONSTRAINT fk_timing_category FOREIGN KEY (race_id, category_code) REFERENCES race_category (race_id, category_code),
    INDEX idx_timing_race_bib (race_id, bib)
);

CREATE TABLE IF NOT EXISTS result_snapshot_checkpoint (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '参赛号',
    checkpoint_code VARCHAR(64) NOT NULL COMMENT '检查点代码',
    position INT NOT NULL COMMENT '检查点顺序，从1递增',
    elapsed_millis BIGINT COMMENT '封榜时该选手通过该检查点的累计耗时（毫秒）；缺失检查点为NULL',
    timing_id VARCHAR(128) COMMENT '分段记录ID；缺失检查点为NULL',
    CONSTRAINT pk_snapshot_checkpoint PRIMARY KEY (race_id, bib, checkpoint_code),
    CONSTRAINT fk_snapshot_checkpoint_snapshot FOREIGN KEY (race_id) REFERENCES result_snapshot (race_id)
);

CREATE TABLE IF NOT EXISTS reclass_request (
    request_id VARCHAR(128) NOT NULL COMMENT '申请ID，全局唯一，同时作为提交申请的幂等键',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '申请人参赛号',
    from_category VARCHAR(64) NOT NULL COMMENT '申请时选手所属组别',
    to_category VARCHAR(64) NOT NULL COMMENT '目标组别',
    reason TEXT NOT NULL COMMENT '申请理由',
    status VARCHAR(16) NOT NULL COMMENT '申请状态：PENDING-待审批，APPROVED-已批准，REJECTED-已拒绝',
    expected_version INT NOT NULL COMMENT '提交时客户端所见赛事版本',
    pending_key VARCHAR(160) COMMENT '待审批唯一闭合键=race_id|bib；仅 PENDING 行非空，审批后清空，保证每人至多一条待审批申请',
    created_at BIGINT NOT NULL COMMENT '申请提交时间，Unix毫秒时间戳',
    resolved_at BIGINT COMMENT '审批时间，Unix毫秒时间戳；未审批为NULL',
    CONSTRAINT pk_reclass_request PRIMARY KEY (request_id),
    CONSTRAINT uk_reclass_pending UNIQUE (pending_key),
    CONSTRAINT fk_reclass_req_race FOREIGN KEY (race_id) REFERENCES race (race_id),
    CONSTRAINT fk_reclass_req_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    INDEX idx_reclass_req_race (race_id, status)
);

CREATE TABLE IF NOT EXISTS reclass_record (
    record_id VARCHAR(160) NOT NULL COMMENT '重分记录ID，全局唯一',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '被重分的选手参赛号',
    from_category VARCHAR(64) NOT NULL COMMENT '迁移前所属组别',
    to_category VARCHAR(64) NOT NULL COMMENT '迁移后所属组别',
    reason TEXT NOT NULL COMMENT '申请理由（随申请固化）',
    application_id VARCHAR(128) NOT NULL COMMENT '来源申请ID',
    operator VARCHAR(128) NOT NULL COMMENT '批准操作者',
    race_version_before INT NOT NULL COMMENT '批准前赛事版本号',
    race_version_after INT NOT NULL COMMENT '批准后赛事版本号',
    previous_rank INT COMMENT '迁移前在来源组别的名次；未参与排名为NULL',
    timing_snapshot TEXT NOT NULL COMMENT '迁移前计时快照JSON：原始完赛耗时与逐检查点通过情况',
    created_at BIGINT NOT NULL COMMENT '批准时间，Unix毫秒时间戳',
    CONSTRAINT pk_reclass_record PRIMARY KEY (record_id),
    CONSTRAINT uk_reclass_record_runner UNIQUE (race_id, bib),
    CONSTRAINT fk_reclass_rec_race FOREIGN KEY (race_id) REFERENCES race (race_id),
    CONSTRAINT fk_reclass_rec_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib)
);

CREATE TABLE IF NOT EXISTS category_snapshot (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    category_code VARCHAR(64) NOT NULL COMMENT '组别代码；封榜时每个组别固化一份只读快照，历史快照不追溯重写',
    version INT NOT NULL COMMENT '封榜后的赛事版本号',
    sealed_at BIGINT NOT NULL COMMENT '封榜时间，Unix毫秒时间戳',
    CONSTRAINT pk_category_snapshot PRIMARY KEY (race_id, category_code),
    CONSTRAINT fk_category_snapshot FOREIGN KEY (race_id, category_code) REFERENCES race_category (race_id, category_code)
);

CREATE TABLE IF NOT EXISTS category_snapshot_entry (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    category_code VARCHAR(64) NOT NULL COMMENT '所属快照的组别代码',
    bib VARCHAR(64) NOT NULL COMMENT '参赛号',
    rank_no INT COMMENT '名次（从1开始，并列同名次并跳号）；未计时/漏点/取消资格为NULL',
    status VARCHAR(24) NOT NULL COMMENT '成绩状态：RANKED/UNTIMED/MISSING_CHECKPOINT/DISQUALIFIED',
    finish_time_ms BIGINT COMMENT '原始完赛耗时（毫秒）；计时缺失为NULL',
    penalty_ms BIGINT NOT NULL COMMENT '生效加时合计毫秒数，无加时为0',
    total_time_ms BIGINT COMMENT '总耗时=原始完赛耗时+生效加时（毫秒）；未排名为NULL',
    checkpoint_count INT NOT NULL DEFAULT 0 COMMENT '赛事检查点总数；未配置检查点为0',
    covered_checkpoint_count INT NOT NULL DEFAULT 0 COMMENT '封榜时该选手已覆盖检查点数量',
    display_order INT NOT NULL COMMENT '展示顺序，从0开始：先名次顺序，并列者按参赛号字典序，其余按参赛号字典序',
    CONSTRAINT pk_category_snapshot_entry PRIMARY KEY (race_id, category_code, bib),
    CONSTRAINT fk_category_snapshot_entry FOREIGN KEY (race_id, category_code) REFERENCES category_snapshot (race_id, category_code)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求ID（写操作幂等键）',
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：CREATE_RACE/REGISTER_RUNNER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/CONFIGURE_CHECKPOINTS/SUBMIT_TIMING/SEAL_RACE/SUBMIT_RECLASS/APPROVE_RECLASS/REJECT_RECLASS/WITHDRAW_RUNNER',
    request_digest CHAR(64) NOT NULL COMMENT '请求参数（requestId除外，含expectedVersion）规范化JSON的SHA-256摘要',
    response_status INT NOT NULL COMMENT '原成功请求的HTTP状态码，重放时原样返回',
    response_body TEXT COMMENT '原成功响应体JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次成功提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);
