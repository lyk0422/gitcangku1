-- 赛事成绩封榜领域表结构。
-- 本地默认以 H2 内存库运行：jdbc:h2:mem:race_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
-- 标识符统一小写；时间字段均为 Unix 毫秒时间戳（BIGINT），不涉及时区换算。

CREATE TABLE IF NOT EXISTS race (
    race_id VARCHAR(64) NOT NULL COMMENT '赛事ID，全局唯一',
    version INT NOT NULL COMMENT '版本号，从1开始，每次写操作加一',
    status VARCHAR(16) NOT NULL COMMENT '赛事状态：OPEN-开放可写，SEALED-已封榜只读',
    deposit_fen BIGINT NOT NULL DEFAULT 0 COMMENT '申诉保证金当前配置金额，单位：分（1元=100分）；0表示未配置，此时不允许创建申诉；配置变化不影响已创建申诉deposit_fen中的快照金额',
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

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求ID（写操作幂等键）',
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：CREATE_RACE/REGISTER_RUNNER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/CONFIGURE_CHECKPOINTS/SUBMIT_TIMING/SEAL_RACE/RECHARGE/CONFIGURE_DEPOSIT/CREATE_PROTEST/ADJUDICATE_PROTEST',
    request_digest CHAR(64) NOT NULL COMMENT '请求参数（requestId除外，含expectedVersion）规范化JSON的SHA-256摘要',
    response_status INT NOT NULL COMMENT '原成功请求的HTTP状态码，重放时原样返回',
    response_body TEXT COMMENT '原成功响应体JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次成功提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);

CREATE TABLE IF NOT EXISTS runner_account (
    account_id VARCHAR(128) NOT NULL COMMENT '账户ID，全局唯一（测试合成场景直接指定）',
    holder_name VARCHAR(128) COMMENT '账户持有人名称（展示用）；NULL表示未命名',
    balance_fen BIGINT NOT NULL COMMENT '可用余额，单位：分（1元=100分）；不含已被申诉冻结的金额，永远非负',
    created_at BIGINT NOT NULL COMMENT '账户创建时间，Unix毫秒时间戳',
    updated_at BIGINT NOT NULL COMMENT '最近一次账务变动时间，Unix毫秒时间戳',
    CONSTRAINT pk_runner_account PRIMARY KEY (account_id)
);

CREATE TABLE IF NOT EXISTS protest (
    protest_id VARCHAR(64) NOT NULL COMMENT '申诉ID，全局唯一',
    race_id VARCHAR(64) NOT NULL COMMENT '所申诉赛事ID',
    account_id VARCHAR(128) NOT NULL COMMENT '提交申诉（并被冻结保证金）的账户ID',
    reason VARCHAR(512) NOT NULL COMMENT '申诉理由原文，创建后不可修改',
    deposit_fen BIGINT NOT NULL COMMENT '保证金快照金额，单位：分；取创建申诉时赛事当前deposit_fen并冻结，此后赛事配置变化不影响该金额',
    status VARCHAR(16) NOT NULL COMMENT '申诉状态：PENDING-待裁决（相关成绩暂缓封榜），ACCEPTED-申诉成立（已全额退回），REJECTED-申诉驳回（已全部没收）；ACCEPTED/REJECTED均为终态',
    decision VARCHAR(16) COMMENT '裁决结果：ACCEPT-全额退回，REJECT-全部没收；PENDING时为NULL',
    decided_at BIGINT COMMENT '裁决时间，Unix毫秒时间戳；未裁决为NULL',
    created_at BIGINT NOT NULL COMMENT '申诉创建（保证金冻结）时间，Unix毫秒时间戳',
    CONSTRAINT pk_protest PRIMARY KEY (protest_id),
    CONSTRAINT fk_protest_race FOREIGN KEY (race_id) REFERENCES race (race_id),
    CONSTRAINT fk_protest_account FOREIGN KEY (account_id) REFERENCES runner_account (account_id),
    INDEX idx_protest_race_status (race_id, status)
);

CREATE TABLE IF NOT EXISTS protest_deposit_ledger (
    ledger_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    account_id VARCHAR(128) NOT NULL COMMENT '账务账户ID',
    protest_id VARCHAR(64) COMMENT '关联申诉ID；RECHARGE（充值）流水为NULL，FREEZE/REFUND/FORFEIT均非NULL',
    type VARCHAR(16) NOT NULL COMMENT '流水类型：RECHARGE-充值入账，FREEZE-创建申诉冻结，REFUND-裁决ACCEPT全额退回，FORFEIT-裁决REJECT全部没收',
    amount_fen BIGINT NOT NULL COMMENT '本笔流水固定金额，单位：分，恒为正数；退款/没收金额恒等于对应申诉deposit_fen快照',
    balance_after_fen BIGINT NOT NULL COMMENT '本笔流水落账后的账户可用余额（不含冻结），单位：分，永远非负',
    created_at BIGINT NOT NULL COMMENT '流水生成时间，Unix毫秒时间戳',
    CONSTRAINT pk_protest_deposit_ledger PRIMARY KEY (ledger_id),
    CONSTRAINT fk_ledger_account FOREIGN KEY (account_id) REFERENCES runner_account (account_id),
    CONSTRAINT fk_ledger_protest FOREIGN KEY (protest_id) REFERENCES protest (protest_id),
    INDEX idx_ledger_account (account_id, ledger_id)
);

CREATE TABLE IF NOT EXISTS result_snapshot_protest (
    race_id VARCHAR(64) NOT NULL COMMENT '所属封榜快照的赛事ID',
    protest_id VARCHAR(64) NOT NULL COMMENT '申诉ID（封榜时已全部终态，保留当时的申诉与裁决依据）',
    account_id VARCHAR(128) NOT NULL COMMENT '提交申诉的账户ID',
    reason VARCHAR(512) NOT NULL COMMENT '申诉理由原文（历史依据，不随后续任何变化改变）',
    deposit_fen BIGINT NOT NULL COMMENT '该申诉保证金快照金额，单位：分',
    decision VARCHAR(16) NOT NULL COMMENT '封榜时的终态裁决：ACCEPT / REJECT',
    created_at BIGINT NOT NULL COMMENT '申诉创建时间，Unix毫秒时间戳',
    decided_at BIGINT NOT NULL COMMENT '裁决时间，Unix毫秒时间戳',
    CONSTRAINT pk_snapshot_protest PRIMARY KEY (race_id, protest_id),
    CONSTRAINT fk_snapshot_protest_snapshot FOREIGN KEY (race_id) REFERENCES result_snapshot (race_id)
);
