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
    withdrawn_at BIGINT COMMENT '退赛时间，Unix毫秒时间戳；NULL表示未退赛，退赛后禁止提交分段与更换芯片',
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
    chip_code VARCHAR(64) COMMENT '接收该读数时生效的计时芯片代码；选手未绑定芯片为NULL',
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
    chip_code VARCHAR(64) COMMENT '封榜时该分段归属的计时芯片代码；缺失检查点或未绑定芯片为NULL，封榜后不随后续更换改写',
    CONSTRAINT pk_snapshot_checkpoint PRIMARY KEY (race_id, bib, checkpoint_code),
    CONSTRAINT fk_snapshot_checkpoint_snapshot FOREIGN KEY (race_id) REFERENCES result_snapshot (race_id)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求ID（写操作幂等键）',
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：CREATE_RACE/REGISTER_RUNNER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/CONFIGURE_CHECKPOINTS/SUBMIT_TIMING/SEAL_RACE/WITHDRAW_RUNNER/REPLACE_CHIP',
    request_digest CHAR(64) NOT NULL COMMENT '请求参数（requestId除外，含expectedVersion）规范化JSON的SHA-256摘要',
    response_status INT NOT NULL COMMENT '原成功请求的HTTP状态码，重放时原样返回',
    response_body TEXT COMMENT '原成功响应体JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次成功提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);

CREATE TABLE IF NOT EXISTS runner_chip (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '选手参赛号',
    chip_code VARCHAR(64) NOT NULL COMMENT '计时芯片代码',
    effective_from BIGINT NOT NULL COMMENT '芯片生效时刻（Unix毫秒时间戳，UTC，左闭区间起点）',
    effective_to BIGINT COMMENT '芯片失效时刻（Unix毫秒时间戳，UTC，右开区间终点）；NULL表示当前生效中',
    created_at BIGINT NOT NULL COMMENT '绑定登记时间，Unix毫秒时间戳',
    CONSTRAINT pk_runner_chip PRIMARY KEY (id),
    CONSTRAINT fk_runner_chip_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    INDEX idx_runner_chip_runner (race_id, bib),
    INDEX idx_runner_chip_chip (chip_code)
);

CREATE TABLE IF NOT EXISTS chip_active (
    chip_code VARCHAR(64) NOT NULL COMMENT '计时芯片代码，全局最多一条生效绑定（并发兜底唯一键）',
    race_id VARCHAR(64) NOT NULL COMMENT '当前绑定选手所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '当前绑定选手参赛号',
    bound_at BIGINT NOT NULL COMMENT '本次绑定生效时刻，Unix毫秒时间戳',
    CONSTRAINT pk_chip_active PRIMARY KEY (chip_code)
);

CREATE TABLE IF NOT EXISTS chip_replacement (
    replacement_id VARCHAR(128) NOT NULL COMMENT '芯片更换记录ID，全局唯一（第二层幂等键）',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '更换芯片的选手参赛号',
    old_chip_code VARCHAR(64) NOT NULL COMMENT '旧芯片代码',
    new_chip_code VARCHAR(64) NOT NULL COMMENT '新芯片代码',
    effective_at BIGINT NOT NULL COMMENT '更换生效时刻（Unix毫秒时间戳，UTC）；生效前读数归旧芯片，生效时刻起归新芯片（左闭右开）',
    referee VARCHAR(64) NOT NULL COMMENT '确认更换的裁判',
    operator VARCHAR(64) NOT NULL COMMENT '提交更换的操作者',
    reassigned_count INT NOT NULL COMMENT '本次重算后归属发生变化的分段读数数量',
    race_version INT NOT NULL COMMENT '更换完成后的赛事版本号，用于同键重放原响应',
    created_at BIGINT NOT NULL COMMENT '更换登记时间，Unix毫秒时间戳',
    CONSTRAINT pk_chip_replacement PRIMARY KEY (replacement_id),
    CONSTRAINT fk_chip_replacement_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    INDEX idx_chip_replacement_runner (race_id, bib)
);

CREATE TABLE IF NOT EXISTS chip_replacement_detail (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    replacement_id VARCHAR(128) NOT NULL COMMENT '所属更换记录ID',
    timing_id VARCHAR(128) NOT NULL COMMENT '被重算归属的分段记录ID',
    checkpoint_code VARCHAR(64) NOT NULL COMMENT '分段对应检查点代码',
    position INT NOT NULL COMMENT '检查点顺序，用于稳定排序',
    chip_before VARCHAR(64) COMMENT '重算前归属芯片；此前未归属为NULL',
    chip_after VARCHAR(64) NOT NULL COMMENT '重算后归属芯片',
    CONSTRAINT pk_chip_replacement_detail PRIMARY KEY (id),
    CONSTRAINT fk_chip_replacement_detail FOREIGN KEY (replacement_id) REFERENCES chip_replacement (replacement_id),
    INDEX idx_chip_replacement_detail_repl (replacement_id, position)
);
