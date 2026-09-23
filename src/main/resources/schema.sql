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
    timing_version INT NOT NULL DEFAULT 1 COMMENT '原始计时版本号，从1开始，每次计时修订加一；申诉裁决时据此检测计时修订并发',
    segment_version INT NOT NULL DEFAULT 1 COMMENT '分段判定版本号，从1开始，该选手每新增一条分段记录加一；申诉裁决时据此检测漏点判定并发',
    created_at BIGINT NOT NULL COMMENT '登记时间，Unix毫秒时间戳',
    updated_at BIGINT NOT NULL COMMENT '最近一次计时修订（即finishAt）时间，Unix毫秒时间戳',
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
    version INT NOT NULL DEFAULT 1 COMMENT '处罚版本号，从1开始；REPLACE裁决生成新版本，旧版本superseded=TRUE并由supersedes_penalty_id关联',
    superseded BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已被REPLACE裁决的新版本取代：FALSE-当前生效版本，TRUE-历史版本（不删除）',
    supersedes_penalty_id VARCHAR(64) COMMENT 'REPLACE裁决新处罚所取代的旧处罚ID；非替换产生的处罚为NULL',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销：FALSE-生效中，TRUE-已撤销（REMOVE裁决或人工撤销）',
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
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：CREATE_RACE/REGISTER_RUNNER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/CONFIGURE_CHECKPOINTS/SUBMIT_TIMING/SEAL_RACE/SUBMIT_APPEAL/APPEAL_OPINION',
    request_digest CHAR(64) NOT NULL COMMENT '请求参数（requestId除外，含expectedVersion）规范化JSON的SHA-256摘要',
    response_status INT NOT NULL COMMENT '原成功请求的HTTP状态码，重放时原样返回',
    response_body TEXT COMMENT '原成功响应体JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次成功提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);

CREATE TABLE IF NOT EXISTS penalty_appeal (
    appeal_key VARCHAR(128) NOT NULL COMMENT '申诉键，全局唯一',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '申诉选手参赛号（必须为被申诉处罚的当事人）',
    penalty_id VARCHAR(64) NOT NULL COMMENT '被申诉处罚ID（受理时冻结，裁决期间不删除）',
    status VARCHAR(16) NOT NULL COMMENT '申诉状态：PENDING-待决（冻结中），UPHELD-维持，REMOVED-撤销，REPLACED-改判替代罚时，REJECTED-第二人驳回',
    reason VARCHAR(1000) NOT NULL COMMENT '申诉理由（受理时提交）',
    penalty_version INT NOT NULL COMMENT '受理时冻结的处罚版本号；裁决时重读，变化则整次冲突且仍PENDING',
    timing_version INT NOT NULL COMMENT '受理时冻结的选手原始计时版本；裁决时重读计时版本，变化则冲突',
    segment_version INT NOT NULL COMMENT '受理时冻结的选手分段判定版本；裁决时重读分段版本，变化则冲突',
    frozen_leaderboard_version INT NOT NULL COMMENT '受理时冻结的榜单（赛事）版本号',
    frozen_finish_time_ms BIGINT COMMENT '受理时冻结的原始完赛耗时（毫秒）；计时缺失为NULL',
    frozen_penalty_ms BIGINT NOT NULL COMMENT '受理时冻结的生效加时合计毫秒数（净成绩罚时）',
    frozen_total_time_ms BIGINT COMMENT '受理时冻结的净成绩总耗时（原始+加时，毫秒）；未排名为NULL',
    frozen_rank INT COMMENT '受理时冻结的名次；未排名为NULL',
    frozen_entry_status VARCHAR(24) NOT NULL COMMENT '受理时冻结的成绩状态：RANKED/UNTIMED/MISSING_CHECKPOINT/DISQUALIFIED',
    finish_at_ms BIGINT NOT NULL COMMENT '选手最近一次计时修订（finishAt）时间，Unix毫秒时间戳；申诉须在其后30分钟内提交',
    before_leaderboard CLOB NOT NULL COMMENT '受理时重算前完整榜单快照JSON（含全体选手条目，冻结保留）',
    after_leaderboard CLOB COMMENT '裁决重算后完整榜单快照JSON；仍PENDING时为NULL，裁决后保留',
    new_penalty_id VARCHAR(64) COMMENT 'REPLACE裁决生成的新处罚版本ID；其余情况为NULL',
    first_steward_id VARCHAR(64) COMMENT '第一裁决干事ID；尚未提交建议时为NULL',
    first_recommendation VARCHAR(16) COMMENT '第一人建议：UPHOLD-维持，REMOVE-撤销，REPLACE-改判；未提交为NULL',
    first_replacement_ms BIGINT COMMENT '第一人REPLACE建议的非负替代罚时（毫秒，允许0）；非REPLACE为NULL',
    first_recorded_at BIGINT COMMENT '第一人建议提交时间，Unix毫秒时间戳；未提交为NULL',
    second_steward_id VARCHAR(64) COMMENT '第二裁决干事ID；尚未裁决时为NULL',
    second_action VARCHAR(16) COMMENT '第二人动作：CONFIRM-确认完全相同建议，REJECT-驳回；未裁决为NULL',
    second_recorded_at BIGINT COMMENT '第二人裁决时间，Unix毫秒时间戳；未裁决为NULL',
    created_at BIGINT NOT NULL COMMENT '受理时间，Unix毫秒时间戳',
    decided_at BIGINT COMMENT '裁决完成时间，Unix毫秒时间戳；仍PENDING为NULL',
    CONSTRAINT pk_penalty_appeal PRIMARY KEY (appeal_key),
    CONSTRAINT fk_appeal_race FOREIGN KEY (race_id) REFERENCES race (race_id),
    CONSTRAINT fk_appeal_penalty FOREIGN KEY (penalty_id) REFERENCES penalty (penalty_id),
    INDEX idx_appeal_race (race_id),
    INDEX idx_appeal_penalty (penalty_id)
);

CREATE TABLE IF NOT EXISTS penalty_appeal_segment (
    appeal_key VARCHAR(128) NOT NULL COMMENT '所属申诉键',
    bib VARCHAR(64) NOT NULL COMMENT '选手参赛号',
    checkpoint_code VARCHAR(64) NOT NULL COMMENT '检查点代码',
    position INT NOT NULL COMMENT '检查点顺序，从1递增',
    elapsed_millis BIGINT COMMENT '受理时冻结的该检查点累计耗时（毫秒）；缺失检查点为NULL',
    timing_id VARCHAR(128) COMMENT '受理时冻结的分段记录ID；缺失检查点为NULL',
    CONSTRAINT pk_appeal_segment PRIMARY KEY (appeal_key, checkpoint_code),
    CONSTRAINT fk_appeal_segment_appeal FOREIGN KEY (appeal_key) REFERENCES penalty_appeal (appeal_key)
);
