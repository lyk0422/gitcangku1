-- 赛事成绩封榜领域表结构。
-- 本地默认以 H2 内存库运行：jdbc:h2:mem:race_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
-- 标识符统一小写；时间字段均为 Unix 毫秒时间戳（BIGINT），不涉及时区换算。

-- 赛事状态：OPEN-开放可写，SUSPENDED-中止暂停中（仅允许同eventKey恢复），SEALED-已封榜只读
CREATE TABLE IF NOT EXISTS race (
    race_id VARCHAR(64) NOT NULL COMMENT '赛事ID，全局唯一',
    version INT NOT NULL COMMENT '版本号，从1开始，每次写操作加一',
    status VARCHAR(16) NOT NULL COMMENT '赛事状态：OPEN-开放可写，SUSPENDED-中止暂停中，SEALED-已封榜只读',
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
    created_at BIGINT NOT NULL COMMENT '处罚新增时间，Unix毫秒时间戳',
    revoked_at BIGINT COMMENT '处罚撤销时间，Unix毫秒时间戳；未撤销为NULL',
    CONSTRAINT pk_penalty PRIMARY KEY (penalty_id),
    CONSTRAINT fk_penalty_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    INDEX idx_penalty_race_bib (race_id, bib)
);

CREATE TABLE IF NOT EXISTS result_snapshot (
    race_id VARCHAR(64) NOT NULL COMMENT '赛事ID，每个赛事封榜后仅一份只读快照',
    version INT NOT NULL COMMENT '封榜后的赛事版本号',
    event_version INT NOT NULL DEFAULT 0 COMMENT '封榜时已登记的中止恢复事件总数（完整事件版本）',
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
    net_finish_time_ms BIGINT COMMENT '净完赛耗时（毫秒）=原始完赛-完赛口径累计补偿；计时缺失为NULL，无中止事件时等于原始值',
    penalty_ms BIGINT NOT NULL COMMENT '生效（未撤销）加时处罚合计毫秒数，无加时为0',
    finish_compensation_ms BIGINT NOT NULL DEFAULT 0 COMMENT '完赛口径累计补偿毫秒数，未受影响为0',
    total_time_ms BIGINT COMMENT '总耗时=净完赛耗时+生效加时（毫秒）；未排名时为NULL',
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
    elapsed_millis BIGINT NOT NULL COMMENT '通过该检查点的原始累计耗时（毫秒，1~86400000），永不被中止恢复改写',
    created_at BIGINT NOT NULL COMMENT '记录提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_checkpoint_timing PRIMARY KEY (timing_id),
    CONSTRAINT uk_timing_runner_checkpoint UNIQUE (race_id, bib, checkpoint_code),
    CONSTRAINT fk_timing_checkpoint FOREIGN KEY (race_id, checkpoint_code) REFERENCES checkpoint (race_id, checkpoint_code),
    CONSTRAINT fk_timing_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    INDEX idx_timing_race_bib (race_id, bib)
);

-- 中止恢复事件：登记后赛事SUSPENDED，恢复时重算净计时；多个事件须互不重叠，eventKey赛事内唯一。
CREATE TABLE IF NOT EXISTS suspension_event (
    event_key VARCHAR(64) NOT NULL COMMENT '中止事件键，赛事内唯一',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    checkpoint_code VARCHAR(64) NOT NULL COMMENT '受影响起始检查点代码：已通过该检查点的选手补偿0',
    checkpoint_position INT NOT NULL COMMENT '受影响起始检查点顺序（登记时固化）',
    start_elapsed_ms BIGINT NOT NULL COMMENT '中止开始的比赛相对耗时（毫秒）',
    resume_elapsed_ms BIGINT COMMENT '恢复时刻（毫秒），严格大于start_elapsed_ms；未恢复为NULL',
    duration_ms BIGINT COMMENT '中止时长=resume_elapsed_ms-start_elapsed_ms（毫秒）；未恢复为NULL',
    status VARCHAR(16) NOT NULL COMMENT '事件状态：SUSPENDED-中止中，RESUMED-已恢复并重算净值',
    version_after_suspend INT NOT NULL COMMENT '中止登记后的赛事版本',
    version_after_resume INT COMMENT '恢复重算后的赛事版本；未恢复为NULL',
    created_at BIGINT NOT NULL COMMENT '中止登记时间，Unix毫秒时间戳',
    resumed_at BIGINT COMMENT '恢复提交时间，Unix毫秒时间戳；未恢复为NULL',
    CONSTRAINT pk_suspension_event PRIMARY KEY (event_key),
    CONSTRAINT uk_suspension_race_event UNIQUE (race_id, event_key),
    CONSTRAINT fk_suspension_race FOREIGN KEY (race_id) REFERENCES race (race_id),
    INDEX idx_suspension_race (race_id)
);

-- 选手净完赛口径：每次恢复一致重算后覆盖；原始完赛耗时保留在runner表不改写。
CREATE TABLE IF NOT EXISTS runner_net (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '选手参赛号',
    net_finish_time_ms BIGINT COMMENT '净完赛耗时（毫秒）=原始完赛-完赛口径累计补偿；计时缺失为NULL',
    finish_compensation_ms BIGINT NOT NULL DEFAULT 0 COMMENT '完赛口径累计补偿毫秒数，未受影响为0',
    updated_at BIGINT NOT NULL COMMENT '最近一次净值重算时间，Unix毫秒时间戳',
    CONSTRAINT pk_runner_net PRIMARY KEY (race_id, bib),
    CONSTRAINT fk_runner_net_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib)
);

-- 选手逐检查点净分段：每次恢复一致重算后覆盖；原始分段保留在checkpoint_timing不改写。
CREATE TABLE IF NOT EXISTS checkpoint_timing_net (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '选手参赛号',
    checkpoint_code VARCHAR(64) NOT NULL COMMENT '检查点代码',
    position INT NOT NULL COMMENT '检查点顺序，从1递增',
    net_elapsed_ms BIGINT NOT NULL COMMENT '净分段累计耗时（毫秒）=原始累计耗时-该点累计补偿',
    compensation_ms BIGINT NOT NULL DEFAULT 0 COMMENT '该检查点累计补偿毫秒数，未受影响为0',
    updated_at BIGINT NOT NULL COMMENT '最近一次净值重算时间，Unix毫秒时间戳',
    CONSTRAINT pk_checkpoint_timing_net PRIMARY KEY (race_id, bib, checkpoint_code),
    CONSTRAINT fk_net_timing_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib)
);

CREATE TABLE IF NOT EXISTS result_snapshot_checkpoint (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '参赛号',
    checkpoint_code VARCHAR(64) NOT NULL COMMENT '检查点代码',
    position INT NOT NULL COMMENT '检查点顺序，从1递增',
    elapsed_millis BIGINT COMMENT '封榜时该选手通过该检查点的原始累计耗时（毫秒）；缺失检查点为NULL',
    net_elapsed_ms BIGINT COMMENT '封榜时净分段累计耗时（毫秒）=原始-累计补偿；缺失检查点为NULL，无中止事件时等于原始值',
    compensation_ms BIGINT NOT NULL DEFAULT 0 COMMENT '该检查点累计补偿毫秒数，未受影响为0',
    timing_id VARCHAR(128) COMMENT '分段记录ID；缺失检查点为NULL',
    CONSTRAINT pk_snapshot_checkpoint PRIMARY KEY (race_id, bib, checkpoint_code),
    CONSTRAINT fk_snapshot_checkpoint_snapshot FOREIGN KEY (race_id) REFERENCES result_snapshot (race_id)
);

-- 封榜时冻结的完整中止恢复事件版本（只读），与快照同生共死。
CREATE TABLE IF NOT EXISTS result_snapshot_suspension (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    event_key VARCHAR(64) NOT NULL COMMENT '中止事件键',
    checkpoint_code VARCHAR(64) NOT NULL COMMENT '受影响起始检查点代码',
    checkpoint_position INT NOT NULL COMMENT '受影响起始检查点顺序',
    start_elapsed_ms BIGINT NOT NULL COMMENT '中止开始的比赛相对耗时（毫秒）',
    resume_elapsed_ms BIGINT COMMENT '恢复时刻（毫秒）；封榜时仍未恢复为NULL',
    duration_ms BIGINT COMMENT '中止时长（毫秒）；未恢复为NULL',
    status VARCHAR(16) NOT NULL COMMENT '封榜时事件状态：SUSPENDED/RESUMED',
    display_order INT NOT NULL COMMENT '展示顺序，从0开始，按登记时间与事件键',
    CONSTRAINT pk_snapshot_suspension PRIMARY KEY (race_id, event_key),
    CONSTRAINT fk_snapshot_suspension_snapshot FOREIGN KEY (race_id) REFERENCES result_snapshot (race_id)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求ID（写操作幂等键）',
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：CREATE_RACE/REGISTER_RUNNER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/CONFIGURE_CHECKPOINTS/SUBMIT_TIMING/SUSPEND_RACE/RESUME_RACE/SEAL_RACE',
    request_digest CHAR(64) NOT NULL COMMENT '请求参数（requestId除外，含expectedVersion）规范化JSON的SHA-256摘要',
    response_status INT NOT NULL COMMENT '原成功请求的HTTP状态码，重放时原样返回',
    response_body TEXT COMMENT '原成功响应体JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次成功提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);
