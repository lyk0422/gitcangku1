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

CREATE TABLE IF NOT EXISTS runner (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '参赛号，同一赛事内唯一',
    category_code VARCHAR(64) COMMENT '所属组别代码；未分配组别为NULL',
    finish_time_ms BIGINT COMMENT '原始完赛耗时（毫秒，取值1~86400000）；NULL表示计时缺失，状态UNTIMED',
    withdrawn BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已退赛：FALSE-在赛参与排名，TRUE-已退赛不参与排名且不占组别容量',
    withdrawn_at BIGINT COMMENT '退赛时间，Unix毫秒时间戳；未退赛为NULL',
    created_at BIGINT NOT NULL COMMENT '登记时间，Unix毫秒时间戳',
    updated_at BIGINT NOT NULL COMMENT '最近一次计时修订时间，Unix毫秒时间戳',
    CONSTRAINT pk_runner PRIMARY KEY (id),
    CONSTRAINT uk_runner_race_bib UNIQUE (race_id, bib),
    CONSTRAINT fk_runner_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS penalty (
    penalty_id VARCHAR(64) NOT NULL COMMENT '处罚ID，全局唯一',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '被罚选手参赛号',
    type VARCHAR(16) NOT NULL COMMENT '处罚类型：ADD_TIME-加时，DISQUALIFY-取消资格',
    amount_ms BIGINT COMMENT '加时时长（毫秒，取值1~3600000）；取消资格时为NULL',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销：FALSE-生效中，TRUE-已撤销',
    created_at BIGINT NOT NULL COMMENT '处罚新增时间，Unix毫秒时间戳',
    revoked_at BIGINT COMMENT '处罚撤销时间，Unix毫秒时间戳；未撤销为NULL',
    CONSTRAINT pk_penalty PRIMARY KEY (penalty_id),
    CONSTRAINT fk_penalty_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
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
    category_code VARCHAR(64) COMMENT '封榜时刻选手所属组别代码；未分配组别为NULL；历史快照不追溯重写',
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
    created_at BIGINT NOT NULL COMMENT '记录提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_checkpoint_timing PRIMARY KEY (timing_id),
    CONSTRAINT uk_timing_runner_checkpoint UNIQUE (race_id, bib, checkpoint_code),
    CONSTRAINT fk_timing_checkpoint FOREIGN KEY (race_id, checkpoint_code) REFERENCES checkpoint (race_id, checkpoint_code),
    CONSTRAINT fk_timing_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
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

CREATE TABLE IF NOT EXISTS race_category (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    category_code VARCHAR(64) NOT NULL COMMENT '组别代码，同一赛事内唯一',
    capacity INT NOT NULL COMMENT '组别容量：在赛（未退赛）人数上限，取值>=1；退赛者不占容量',
    sealed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '组别是否已封榜：FALSE-开放可重分，TRUE-已封榜只读',
    created_at BIGINT NOT NULL COMMENT '配置时间，Unix毫秒时间戳',
    sealed_at BIGINT COMMENT '组别封榜时间，Unix毫秒时间戳；未封榜为NULL',
    CONSTRAINT pk_race_category PRIMARY KEY (race_id, category_code),
    CONSTRAINT fk_category_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS reclass_application (
    application_id VARCHAR(64) NOT NULL COMMENT '重分申请ID，全局唯一',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '申请人参赛号',
    target_category VARCHAR(64) NOT NULL COMMENT '目标组别代码',
    reason VARCHAR(512) NOT NULL COMMENT '申请理由',
    status VARCHAR(16) NOT NULL COMMENT '申请状态：PENDING-待审批，APPROVED-已批准，REJECTED-已拒绝',
    expected_version INT NOT NULL COMMENT '提交时客户端所见赛事版本',
    created_at BIGINT NOT NULL COMMENT '申请提交时间，Unix毫秒时间戳',
    decided_at BIGINT COMMENT '审批时间，Unix毫秒时间戳；待审批为NULL',
    CONSTRAINT pk_reclass_application PRIMARY KEY (application_id),
    CONSTRAINT fk_application_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    INDEX idx_application_race (race_id)
);

CREATE TABLE IF NOT EXISTS reclass_record (
    record_id VARCHAR(128) NOT NULL COMMENT '重分记录ID，全局唯一（reclassKey:applicationId）',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '被迁移选手参赛号',
    application_id VARCHAR(64) NOT NULL COMMENT '来源重分申请ID',
    operator VARCHAR(64) NOT NULL COMMENT '执行批准的操作者',
    from_category VARCHAR(64) COMMENT '迁移前所属组别代码；未分配组别为NULL',
    to_category VARCHAR(64) NOT NULL COMMENT '迁移后所属组别代码',
    pre_rank INT COMMENT '迁移前在来源组别内的名次；未排名为NULL',
    pre_status VARCHAR(24) NOT NULL COMMENT '迁移前成绩状态（同result_snapshot_entry.status取值）',
    pre_finish_time_ms BIGINT COMMENT '迁移前原始完赛耗时（毫秒）；计时缺失为NULL',
    pre_penalty_ms BIGINT NOT NULL COMMENT '迁移前生效加时合计毫秒数，无加时为0',
    pre_total_time_ms BIGINT COMMENT '迁移前总耗时（毫秒）；未排名为NULL',
    created_at BIGINT NOT NULL COMMENT '迁移提交时间，Unix毫秒时间戳；记录不可变',
    CONSTRAINT pk_reclass_record PRIMARY KEY (record_id),
    CONSTRAINT fk_record_application FOREIGN KEY (application_id) REFERENCES reclass_application (application_id),
    INDEX idx_record_race (race_id)
);

CREATE TABLE IF NOT EXISTS category_snapshot (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    category_code VARCHAR(64) NOT NULL COMMENT '组别代码；每个组别封榜后仅一份只读快照',
    version INT NOT NULL COMMENT '组别封榜后的赛事版本号',
    sealed_at BIGINT NOT NULL COMMENT '组别封榜时间，Unix毫秒时间戳',
    CONSTRAINT pk_category_snapshot PRIMARY KEY (race_id, category_code),
    CONSTRAINT fk_category_snapshot_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS category_snapshot_entry (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    category_code VARCHAR(64) NOT NULL COMMENT '所属快照的组别代码',
    bib VARCHAR(64) NOT NULL COMMENT '参赛号',
    rank_no INT COMMENT '组内名次（并列同名次并跳号）；未计时/漏点/取消资格为NULL',
    status VARCHAR(24) NOT NULL COMMENT '成绩状态：取值同result_snapshot_entry.status',
    finish_time_ms BIGINT COMMENT '原始完赛耗时（毫秒）；计时缺失为NULL',
    penalty_ms BIGINT NOT NULL COMMENT '生效加时合计毫秒数，无加时为0',
    total_time_ms BIGINT COMMENT '总耗时（毫秒）；未排名时为NULL',
    display_order INT NOT NULL COMMENT '组内展示顺序，从0开始',
    CONSTRAINT pk_category_snapshot_entry PRIMARY KEY (race_id, category_code, bib),
    CONSTRAINT fk_category_snapshot_entry FOREIGN KEY (race_id, category_code) REFERENCES category_snapshot (race_id, category_code)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求ID（写操作幂等键；批准重分批次时为reclassKey）',
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：CREATE_RACE/REGISTER_RUNNER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/CONFIGURE_CHECKPOINTS/SUBMIT_TIMING/SEAL_RACE/CONFIGURE_CATEGORIES/WITHDRAW_RUNNER/SUBMIT_RECLASS/REJECT_RECLASS/APPROVE_RECLASS/SEAL_CATEGORY',
    request_digest CHAR(64) NOT NULL COMMENT '请求参数（requestId除外，含expectedVersion）规范化JSON的SHA-256摘要；APPROVE_RECLASS时指纹含操作者、赛事版本、规范化申请集合与目标组别',
    response_status INT NOT NULL COMMENT '原成功请求的HTTP状态码，重放时原样返回',
    response_body TEXT COMMENT '原成功响应体JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次成功提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);