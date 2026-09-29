-- 赛事成绩封榜领域表结构。
-- 本地默认以 H2 内存库运行：jdbc:h2:mem:race_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
-- 标识符统一小写；时间字段均为 Unix 毫秒时间戳（BIGINT，UTC 瞬时），不涉及时区换算。

CREATE TABLE IF NOT EXISTS race (
    race_id VARCHAR(64) NOT NULL COMMENT '赛事ID，全局唯一',
    version INT NOT NULL COMMENT '版本号，从1开始，每次写操作加一',
    status VARCHAR(16) NOT NULL COMMENT '赛事状态：OPEN-开放可写，SEALED-已封榜只读',
    created_at BIGINT NOT NULL COMMENT '创建时间，Unix毫秒时间戳（UTC）',
    CONSTRAINT pk_race PRIMARY KEY (race_id)
);

CREATE TABLE IF NOT EXISTS runner (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '参赛号，同一赛事内唯一',
    finish_time_ms BIGINT COMMENT '原始完赛耗时（毫秒，取值1~86400000）；NULL表示计时缺失，状态UNTIMED',
    runner_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '参赛状态：ACTIVE-在赛，WITHDRAWN-已退赛；退赛后不得再提交读数或更换芯片',
    chip_code VARCHAR(128) COMMENT '当前生效计时芯片代码；NULL表示尚未发放芯片。芯片区间左闭右开，更换历史见chip_replacement',
    created_at BIGINT NOT NULL COMMENT '登记时间，Unix毫秒时间戳（UTC）',
    updated_at BIGINT NOT NULL COMMENT '最近一次计时修订时间，Unix毫秒时间戳（UTC）',
    CONSTRAINT pk_runner PRIMARY KEY (id),
    CONSTRAINT uk_runner_race_bib UNIQUE (race_id, bib),
    CONSTRAINT fk_runner_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS chip_binding (
    chip_code VARCHAR(128) NOT NULL COMMENT '计时芯片代码，全局唯一；一行表示该芯片当前绑定的唯一未完赛参赛者',
    race_id VARCHAR(64) NOT NULL COMMENT '芯片当前绑定选手所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '芯片当前绑定选手参赛号',
    bound_at BIGINT NOT NULL COMMENT '本次绑定建立时间，Unix毫秒时间戳（UTC）：随登记发放或芯片更换生效登记',
    CONSTRAINT pk_chip_binding PRIMARY KEY (chip_code),
    CONSTRAINT uk_chip_binding_runner UNIQUE (race_id, bib),
    CONSTRAINT fk_chip_binding_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib)
);

CREATE TABLE IF NOT EXISTS chip_replacement (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键，同时作为更换历史的稳定排序键（按提交顺序）',
    replacement_id VARCHAR(128) NOT NULL COMMENT '更换记录ID，全局唯一，由请求提供',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '更换芯片的选手参赛号',
    old_chip_code VARCHAR(128) NOT NULL COMMENT '更换前芯片代码，固化不再随后续操作改写',
    new_chip_code VARCHAR(128) NOT NULL COMMENT '更换后芯片代码，固化不再随后续操作改写',
    effective_at BIGINT NOT NULL COMMENT 'UTC生效时刻（Unix毫秒），区间左闭右开：[前一生效时刻, 本时刻)归旧芯片，[本时刻, 下一时刻)归新芯片',
    referee VARCHAR(128) NOT NULL COMMENT '裁判确认人（操作者账号），原样固化',
    before_attribution TEXT NOT NULL COMMENT '重算前各分段归属的规范化JSON（按检查点顺序：timingId/checkpointCode/position/elapsedMillis/readAtMs/chipCode）',
    after_attribution TEXT NOT NULL COMMENT '重算后各分段归属的规范化JSON，结构同before_attribution；更换不改写读数时与重算前一致',
    created_at BIGINT NOT NULL COMMENT '更换提交时间，Unix毫秒时间戳（UTC）',
    CONSTRAINT pk_chip_replacement PRIMARY KEY (id),
    CONSTRAINT uk_chip_replacement_id UNIQUE (replacement_id),
    CONSTRAINT fk_chip_replacement_race FOREIGN KEY (race_id) REFERENCES race (race_id),
    CONSTRAINT fk_chip_replacement_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    INDEX idx_chip_replacement_race_bib (race_id, bib)
);

CREATE TABLE IF NOT EXISTS penalty (
    penalty_id VARCHAR(64) NOT NULL COMMENT '处罚ID，全局唯一',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '被罚选手参赛号',
    type VARCHAR(16) NOT NULL COMMENT '处罚类型：ADD_TIME-加时，DISQUALIFY-取消资格',
    amount_ms BIGINT COMMENT '加时时长（毫秒，取值1~3600000）；取消资格时为NULL',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销：FALSE-生效中，TRUE-已撤销',
    created_at BIGINT NOT NULL COMMENT '处罚新增时间，Unix毫秒时间戳（UTC）',
    revoked_at BIGINT COMMENT '处罚撤销时间，Unix毫秒时间戳（UTC）；未撤销为NULL',
    CONSTRAINT pk_penalty PRIMARY KEY (penalty_id),
    CONSTRAINT fk_penalty_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    INDEX idx_penalty_race_bib (race_id, bib)
);

CREATE TABLE IF NOT EXISTS result_snapshot (
    race_id VARCHAR(64) NOT NULL COMMENT '赛事ID，每个赛事封榜后仅一份只读快照',
    version INT NOT NULL COMMENT '封榜后的赛事版本号',
    sealed_at BIGINT NOT NULL COMMENT '封榜时间，Unix毫秒时间戳（UTC）',
    CONSTRAINT pk_result_snapshot PRIMARY KEY (race_id),
    CONSTRAINT fk_snapshot_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS result_snapshot_entry (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '参赛号',
    rank_no INT COMMENT '名次（从1开始，并列同名次并跳号，如1、1、3）；未计时/漏点/退赛/取消资格为NULL',
    status VARCHAR(24) NOT NULL COMMENT '成绩状态：RANKED-参与排名，UNTIMED-计时缺失，MISSING_CHECKPOINT-有完赛但未覆盖全部检查点，WITHDRAWN-已退赛，DISQUALIFIED-取消资格',
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
    created_at BIGINT NOT NULL COMMENT '配置时间，Unix毫秒时间戳（UTC）',
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
    chip_code VARCHAR(128) COMMENT '产生该读数并据UTC左闭右开区间判定归属的芯片代码；选手尚无芯片时为NULL',
    read_at_ms BIGINT COMMENT '芯片实际被读取的UTC时刻（Unix毫秒）；NULL表示提交时未上报，归属按接收时刻created_at判定',
    created_at BIGINT NOT NULL COMMENT '记录接收（提交）时间，Unix毫秒时间戳（UTC）',
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
    chip_code VARCHAR(128) COMMENT '封榜时该分段读数实际使用（归属）的芯片代码，随快照固化；后续更换芯片不改写；缺失检查点为NULL',
    CONSTRAINT pk_snapshot_checkpoint PRIMARY KEY (race_id, bib, checkpoint_code),
    CONSTRAINT fk_snapshot_checkpoint_snapshot FOREIGN KEY (race_id) REFERENCES result_snapshot (race_id)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求ID（写操作幂等键）',
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：CREATE_RACE/REGISTER_RUNNER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/CONFIGURE_CHECKPOINTS/SUBMIT_TIMING/REPLACE_CHIP/WITHDRAW_RUNNER/SEAL_RACE',
    request_digest CHAR(64) NOT NULL COMMENT '请求参数（requestId除外，含expectedVersion、操作者与规范化对象集合）规范化JSON的SHA-256摘要',
    response_status INT NOT NULL COMMENT '原成功请求的HTTP状态码，重放时原样返回',
    response_body TEXT COMMENT '原成功响应体JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次成功提交时间，Unix毫秒时间戳（UTC）',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);
