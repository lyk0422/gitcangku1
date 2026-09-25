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

-- 赛事器材检录配置：仅强制检录赛事存在一行，建赛时写入后不可修改。
CREATE TABLE IF NOT EXISTS race_inspection_config (
    race_id VARCHAR(64) NOT NULL COMMENT '赛事ID，一个赛事最多一行检录配置',
    inspection_required BOOLEAN NOT NULL COMMENT '是否强制检录：TRUE-起跑/首个分段计时前必须存在未过期PASS，FALSE-不受检录门禁影响',
    valid_minutes INT NOT NULL COMMENT 'PASS检录有效分钟数（取值1~1440），有效至检录时刻加该分钟数',
    created_at BIGINT NOT NULL COMMENT '配置写入时间，Unix毫秒时间戳',
    CONSTRAINT pk_race_inspection_config PRIMARY KEY (race_id),
    CONSTRAINT fk_inspection_config_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

-- 器材检录历史：只追加、不可变；同一选手同一赛事的“当前有效检录”取 seq 最大的一行。
CREATE TABLE IF NOT EXISTS equipment_inspection (
    seq BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增提交序号，唯一确定同一时刻多条检录的先后顺序',
    inspection_id VARCHAR(128) NOT NULL COMMENT '检录记录业务键（选手提交的inspectionKey），全局唯一；同键同参重放、异参409',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '被检录选手参赛号',
    equipment_serial VARCHAR(128) NOT NULL COMMENT '器材序列号，赛事内同一时刻最多绑定一个未完赛选手',
    result VARCHAR(8) NOT NULL COMMENT '检录结果：PASS-通过（有效至valid_until），FAIL-不通过（立即阻断起跑）',
    valid_minutes INT NOT NULL COMMENT '本次检录快照的有效分钟数（1~1440）',
    inspected_at BIGINT NOT NULL COMMENT '检录提交时刻，Unix毫秒时间戳',
    valid_until BIGINT COMMENT 'PASS有效截止时刻=inspected_at+valid_minutes分钟，Unix毫秒时间戳；FAIL为NULL',
    created_at BIGINT NOT NULL COMMENT '记录落库时间，Unix毫秒时间戳',
    CONSTRAINT pk_equipment_inspection PRIMARY KEY (inspection_id),
    CONSTRAINT uk_equipment_inspection_seq UNIQUE (seq),
    CONSTRAINT fk_inspection_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    INDEX idx_inspection_race_bib (race_id, bib, inspected_at)
);

-- 器材当前绑定：同一(赛事,器材序列号)最多一行，持有者未完赛时其他选手PASS同一序列号返回409。
CREATE TABLE IF NOT EXISTS equipment_binding (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    equipment_serial VARCHAR(128) NOT NULL COMMENT '器材序列号，赛事内唯一',
    bib VARCHAR(64) NOT NULL COMMENT '当前绑定的选手参赛号；持有者退赛/取消资格/完赛后该行可被新PASS接管',
    inspection_id VARCHAR(128) NOT NULL COMMENT '产生该绑定的PASS检录记录键',
    bound_at BIGINT NOT NULL COMMENT '最近绑定时间，Unix毫秒时间戳',
    CONSTRAINT pk_equipment_binding PRIMARY KEY (race_id, equipment_serial),
    CONSTRAINT fk_binding_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib)
);

-- 选手赛程生命周期：行按需在起跑/退赛时创建；缺失行视为 REGISTERED。
CREATE TABLE IF NOT EXISTS runner_lifecycle (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '选手参赛号',
    status VARCHAR(16) NOT NULL COMMENT '生命周期状态：REGISTERED-已登记未起跑，STARTED-已起跑，WITHDRAWN-已退赛（终态），DISQUALIFIED由取消资格处罚派生',
    started_at BIGINT COMMENT '起跑时刻，Unix毫秒时间戳；未起跑为NULL',
    created_at BIGINT NOT NULL COMMENT '生命周期行创建时间，Unix毫秒时间戳',
    updated_at BIGINT NOT NULL COMMENT '最近状态变更时间，Unix毫秒时间戳',
    CONSTRAINT pk_runner_lifecycle PRIMARY KEY (race_id, bib),
    CONSTRAINT fk_lifecycle_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib)
);

-- 起跑记录：同一选手同一赛事最多一条成功起跑。
CREATE TABLE IF NOT EXISTS race_start (
    start_id VARCHAR(128) NOT NULL COMMENT '起跑记录业务键，全局唯一；同键同参重放、异参409',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '起跑选手参赛号',
    started_at BIGINT NOT NULL COMMENT '起跑提交时刻，Unix毫秒时间戳',
    CONSTRAINT pk_race_start PRIMARY KEY (start_id),
    CONSTRAINT uk_start_runner UNIQUE (race_id, bib),
    CONSTRAINT fk_start_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求ID（写操作幂等键）',
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：CREATE_RACE/REGISTER_RUNNER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/CONFIGURE_CHECKPOINTS/SUBMIT_TIMING/SEAL_RACE/SUBMIT_INSPECTION/START_RUNNER/WITHDRAW_RUNNER',
    request_digest CHAR(64) NOT NULL COMMENT '请求参数（requestId除外，含expectedVersion）规范化JSON的SHA-256摘要',
    response_status INT NOT NULL COMMENT '原成功请求的HTTP状态码，重放时原样返回',
    response_body TEXT COMMENT '原成功响应体JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次成功提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);
