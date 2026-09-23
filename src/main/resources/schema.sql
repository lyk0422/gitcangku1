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
    finish_time_ms BIGINT COMMENT '原始完赛耗时（毫秒，取值1~86400000）；NULL表示计时缺失，状态UNTIMED',
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
    version INT NOT NULL DEFAULT 1 COMMENT '处罚版本号，从1开始；撤销或裁决替换（REPLACE）时加一',
    created_at BIGINT NOT NULL COMMENT '处罚新增时间，Unix毫秒时间戳',
    revoked_at BIGINT COMMENT '处罚撤销时间，Unix毫秒时间戳；未撤销为NULL',
    CONSTRAINT pk_penalty PRIMARY KEY (penalty_id),
    CONSTRAINT fk_penalty_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    INDEX idx_penalty_race_bib (race_id, bib)
);

CREATE TABLE IF NOT EXISTS penalty_revision (
    penalty_id VARCHAR(64) NOT NULL COMMENT '所属处罚ID',
    version INT NOT NULL COMMENT '被替换的旧版本号，从1开始',
    type VARCHAR(16) NOT NULL COMMENT '旧版本处罚类型：ADD_TIME-加时，DISQUALIFY-取消资格',
    amount_ms BIGINT COMMENT '旧版本加时时长（毫秒）；取消资格为NULL',
    superseded_by_version INT NOT NULL COMMENT '替换生成的新版本号（=旧版本号+1），关联新旧版本',
    appeal_key VARCHAR(128) NOT NULL COMMENT '触发本次替换的申诉键',
    superseded_at BIGINT NOT NULL COMMENT '旧版本被替换时间，Unix毫秒时间戳',
    CONSTRAINT pk_penalty_revision PRIMARY KEY (penalty_id, version),
    CONSTRAINT fk_revision_penalty FOREIGN KEY (penalty_id) REFERENCES penalty (penalty_id)
);

CREATE TABLE IF NOT EXISTS appeal (
    appeal_key VARCHAR(128) NOT NULL COMMENT '申诉键，全局唯一（业务幂等键）',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '申诉选手参赛号，必须等于被罚选手',
    penalty_id VARCHAR(64) NOT NULL COMMENT '被申诉处罚ID；一条处罚最多一次申诉',
    reason VARCHAR(1024) NOT NULL COMMENT '申诉理由',
    status VARCHAR(16) NOT NULL COMMENT '申诉状态：PENDING-待裁决，UPHELD-维持，REMOVED-撤销处罚，REPLACED-替换罚时',
    penalty_version INT NOT NULL COMMENT '受理时冻结的处罚版本号；裁决确认时重新读取比对，不一致整次失败',
    frozen_penalty_type VARCHAR(16) NOT NULL COMMENT '受理时冻结的处罚类型',
    frozen_penalty_amount_ms BIGINT COMMENT '受理时冻结的加时毫秒数；取消资格为NULL',
    frozen_finish_time_ms BIGINT COMMENT '受理时冻结的原始完赛耗时（毫秒）；计时缺失为NULL',
    frozen_penalty_ms BIGINT NOT NULL COMMENT '受理时冻结的生效加时合计（毫秒）',
    frozen_total_time_ms BIGINT COMMENT '受理时冻结的净成绩总耗时（毫秒）；未排名为NULL',
    frozen_rank INT COMMENT '受理时冻结的名次；未排名为NULL',
    frozen_status VARCHAR(24) NOT NULL COMMENT '受理时冻结的成绩状态：RANKED/UNTIMED/MISSING_CHECKPOINT/DISQUALIFIED',
    frozen_segments TEXT NOT NULL COMMENT '受理时冻结的分段判定JSON：按检查点顺序的通过耗时与缺失标记',
    leaderboard_version INT NOT NULL COMMENT '受理时冻结的榜单版本（赛事版本）；确认裁决时必须仍相等',
    first_official_id VARCHAR(64) COMMENT '提交建议的第一名赛事干事ID；尚无建议或被驳回后为NULL',
    first_decision VARCHAR(16) COMMENT '第一人建议：UPHOLD-维持，REMOVE-撤销，REPLACE-替换罚时',
    first_replacement_ms BIGINT COMMENT '第一人建议的替代罚时（毫秒，非负）；仅REPLACE时有值',
    first_at BIGINT COMMENT '第一人提交建议时间，Unix毫秒时间戳',
    second_official_id VARCHAR(64) COMMENT '第二名赛事干事ID（必须与第一人不同）',
    second_action VARCHAR(16) COMMENT '第二人动作：CONFIRM-确认相同建议，REJECT-驳回建议（申诉回到PENDING）',
    second_decision VARCHAR(16) COMMENT '第二人确认的建议，必须与第一人完全相同',
    second_replacement_ms BIGINT COMMENT '第二人确认的替代罚时（毫秒）；仅REPLACE时有值',
    second_at BIGINT COMMENT '第二人操作时间，Unix毫秒时间戳',
    leaderboard_before TEXT COMMENT '裁决重算前完整榜单快照JSON；未裁决为NULL',
    leaderboard_after TEXT COMMENT '裁决重算后完整榜单快照JSON；未裁决为NULL',
    new_leaderboard_version INT COMMENT '裁决确认生成的新榜单版本；未裁决为NULL',
    created_at BIGINT NOT NULL COMMENT '申诉受理时间，Unix毫秒时间戳',
    decided_at BIGINT COMMENT '裁决完成时间，Unix毫秒时间戳；未裁决为NULL',
    CONSTRAINT pk_appeal PRIMARY KEY (appeal_key),
    CONSTRAINT uk_appeal_penalty UNIQUE (penalty_id),
    CONSTRAINT fk_appeal_race FOREIGN KEY (race_id) REFERENCES race (race_id),
    CONSTRAINT fk_appeal_penalty FOREIGN KEY (penalty_id) REFERENCES penalty (penalty_id),
    INDEX idx_appeal_race_status (race_id, status)
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

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求ID（写操作幂等键）',
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：CREATE_RACE/REGISTER_RUNNER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/CONFIGURE_CHECKPOINTS/SUBMIT_TIMING/SEAL_RACE/SUBMIT_APPEAL/RECOMMEND_APPEAL/CONFIRM_APPEAL',
    request_digest CHAR(64) NOT NULL COMMENT '请求参数（requestId除外，含expectedVersion）规范化JSON的SHA-256摘要',
    response_status INT NOT NULL COMMENT '原成功请求的HTTP状态码，重放时原样返回',
    response_body TEXT COMMENT '原成功响应体JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次成功提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);
