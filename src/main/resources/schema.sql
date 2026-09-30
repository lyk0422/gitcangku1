-- 赛事成绩封榜领域表结构。
-- 本地默认以 H2 内存库运行：jdbc:h2:mem:race_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
-- 标识符统一小写；时间字段均为 Unix 毫秒时间戳（BIGINT），不涉及时区换算。
-- 金额字段均为人民币元、非负整数（INT），不使用小数；余额=选手可用余额，冻结保证金时等额扣减。

CREATE TABLE IF NOT EXISTS race (
    race_id VARCHAR(64) NOT NULL COMMENT '赛事ID，全局唯一',
    version INT NOT NULL COMMENT '版本号，从1开始，每次写操作加一',
    status VARCHAR(16) NOT NULL COMMENT '赛事状态：OPEN-开放可写，SEALED-已封榜只读',
    deposit_amount INT COMMENT '申诉保证金当前配置金额（人民币元，非负整数，取值1~1000000）；NULL表示尚未配置保证金，此时不能创建申诉；配置变更只影响之后新建的申诉',
    created_at BIGINT NOT NULL COMMENT '创建时间，Unix毫秒时间戳',
    CONSTRAINT pk_race PRIMARY KEY (race_id)
);

CREATE TABLE IF NOT EXISTS runner (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '参赛号，同一赛事内唯一',
    finish_time_ms BIGINT COMMENT '原始完赛耗时（毫秒，取值1~86400000）；NULL表示计时缺失，状态UNTIMED',
    balance INT NOT NULL DEFAULT 0 COMMENT '选手可用余额（人民币元，非负整数）；创建申诉冻结保证金时等额扣减，裁决ACCEPT退回时恢复，REJECT不恢复（冻结时已扣减）',
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

CREATE TABLE IF NOT EXISTS protest (
    seq BIGINT NOT NULL AUTO_INCREMENT UNIQUE COMMENT '申诉自增序号（仅用于同毫秒创建时的稳定先后排序，不作为业务ID）',
    protest_id VARCHAR(64) NOT NULL COMMENT '申诉ID，全局唯一',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '申诉选手参赛号',
    status VARCHAR(16) NOT NULL COMMENT '申诉状态：PENDING-待裁决（存在PENDING申诉时赛事暂缓封榜），ACCEPTED-申诉成立保证金已全额退回，REJECTED-申诉驳回保证金已全部没收；ACCEPTED/REJECTED均为终态不可改判',
    deposit_amount INT NOT NULL COMMENT '保证金快照金额（人民币元，正整数）：创建申诉时取赛事当前配置冻结，此后保证金配置变化不影响本申诉，退回/没收均恒按此金额',
    reason VARCHAR(500) NOT NULL COMMENT '申诉理由文本，创建时提交',
    decision_reason VARCHAR(500) COMMENT '裁决理由文本；终态裁决时写入，PENDING状态为NULL',
    created_at BIGINT NOT NULL COMMENT '申诉创建时间（即保证金冻结时间），Unix毫秒时间戳',
    decided_at BIGINT COMMENT '裁决时间（退款/没收时间），Unix毫秒时间戳；PENDING状态为NULL',
    CONSTRAINT pk_protest PRIMARY KEY (protest_id),
    CONSTRAINT fk_protest_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    INDEX idx_protest_race_bib (race_id, bib),
    INDEX idx_protest_race_status (race_id, status)
);

CREATE TABLE IF NOT EXISTS wallet_transaction (
    seq BIGINT NOT NULL AUTO_INCREMENT UNIQUE COMMENT '流水自增序号（记账先后顺序，同毫秒下也严格递增；不作为业务ID）',
    transaction_id VARCHAR(128) NOT NULL COMMENT '资金流水ID，全局唯一（充值由客户端提供，冻结/退回/没收由服务端生成）',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '流水所属选手参赛号',
    protest_id VARCHAR(64) COMMENT '关联申诉ID；TOPUP充值流水为NULL，FREEZE/REFUND/FORFEIT流水均关联具体申诉',
    type VARCHAR(16) NOT NULL COMMENT '流水类型：TOPUP-充值增加可用余额，FREEZE-创建申诉冻结并扣减保证金，REFUND-裁决ACCEPT全额退回冻结保证金，FORFEIT-裁决REJECT全部没收（不改变可用余额，冻结时已扣减）',
    amount INT NOT NULL COMMENT '本笔流水固定金额（人民币元，正整数）；同一申诉的FREEZE/REFUND/FORFEIT金额恒等于其保证金快照',
    balance_after INT NOT NULL COMMENT '本笔流水出账后选手可用余额（人民币元，非负整数）：TOPUP/FREEZE/REFUND反映扣减或退回后的余额，FORFEIT时等于当前可用余额（没收不改变可用余额）',
    created_at BIGINT NOT NULL COMMENT '流水记账时间，Unix毫秒时间戳',
    CONSTRAINT pk_wallet_transaction PRIMARY KEY (transaction_id),
    CONSTRAINT fk_wallet_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    CONSTRAINT fk_wallet_protest FOREIGN KEY (protest_id) REFERENCES protest (protest_id),
    INDEX idx_wallet_race_bib (race_id, bib)
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

-- 封榜时固化的申诉与裁决依据：仅当赛事不存在PENDING申诉时才允许封榜，
-- 因此封榜快照中的申诉全部为ACCEPTED/REJECTED终态，连同保证金快照金额、理由一并只读保留。
CREATE TABLE IF NOT EXISTS result_snapshot_protest (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    protest_id VARCHAR(64) NOT NULL COMMENT '申诉ID',
    bib VARCHAR(64) NOT NULL COMMENT '申诉选手参赛号',
    status VARCHAR(16) NOT NULL COMMENT '封榜时刻申诉状态（终态裁决依据）：ACCEPTED-已全额退回，REJECTED-已全部没收',
    deposit_amount INT NOT NULL COMMENT '该申诉保证金快照金额（人民币元，正整数），封榜时原样固化',
    reason VARCHAR(500) NOT NULL COMMENT '申诉理由，封榜时原样固化',
    decision_reason VARCHAR(500) COMMENT '裁决理由，封榜时原样固化；裁决未填写理由时为NULL',
    created_at BIGINT NOT NULL COMMENT '申诉创建时间，Unix毫秒时间戳',
    decided_at BIGINT NOT NULL COMMENT '裁决时间，Unix毫秒时间戳',
    display_order INT NOT NULL COMMENT '展示顺序，从0开始，按申诉创建时间再按申诉ID升序排列',
    CONSTRAINT pk_snapshot_protest PRIMARY KEY (race_id, protest_id),
    CONSTRAINT fk_snapshot_protest_snapshot FOREIGN KEY (race_id) REFERENCES result_snapshot (race_id)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求ID（写操作幂等键）',
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：CREATE_RACE/REGISTER_RUNNER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/CONFIGURE_CHECKPOINTS/SUBMIT_TIMING/SEAL_RACE/CONFIGURE_DEPOSIT/TOP_UP_WALLET/CREATE_PROTEST/DECIDE_PROTEST',
    request_digest CHAR(64) NOT NULL COMMENT '请求参数（requestId除外，含expectedVersion）规范化JSON的SHA-256摘要',
    response_status INT NOT NULL COMMENT '原成功请求的HTTP状态码，重放时原样返回',
    response_body TEXT COMMENT '原成功响应体JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次成功提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);
