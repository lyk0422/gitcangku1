-- 赛事成绩封榜领域表结构。
-- 本地默认以 H2 内存库运行：jdbc:h2:mem:race_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
-- 标识符统一小写；时间字段均为 Unix 毫秒时间戳（BIGINT），不涉及时区换算。

CREATE TABLE IF NOT EXISTS race (
    race_id VARCHAR(64) NOT NULL COMMENT '赛事ID，全局唯一',
    version INT NOT NULL COMMENT '版本号，从1开始，每次写操作加一',
    status VARCHAR(16) NOT NULL COMMENT '赛事状态：OPEN-开放可写，SEALED-已封榜只读',
    mode VARCHAR(16) NOT NULL DEFAULT 'STANDARD' COMMENT '赛事模式：STANDARD-普通计时赛，PURSUIT-追逐赛（启用后禁止直接录入完赛耗时）',
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
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：CREATE_RACE/REGISTER_RUNNER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/CONFIGURE_CHECKPOINTS/SUBMIT_TIMING/SEAL_RACE/CONFIGURE_PURSUIT/SUBMIT_TRAJECTORY/ADJUDICATE_PURSUIT',
    request_digest CHAR(64) NOT NULL COMMENT '请求参数（requestId除外，含expectedVersion）规范化JSON的SHA-256摘要',
    response_status INT NOT NULL COMMENT '原成功请求的HTTP状态码，重放时原样返回',
    response_body TEXT COMMENT '原成功响应体JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次成功提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);

-- 追逐赛（PURSUIT）领域表：合成直线进度，不模拟赛道。
-- 距离单位均为毫米（非负整数），时刻为起跑起非负整数毫秒；位置=起始距离+累计前进量。

CREATE TABLE IF NOT EXISTS pursuit_pair (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    pair_no INT NOT NULL COMMENT '追逐对序号，从1连续递增，启用PURSUIT时一次性固定（1~5对）',
    chaser_bib VARCHAR(64) NOT NULL COMMENT '追赶者参赛号',
    chased_bib VARCHAR(64) NOT NULL COMMENT '被追者参赛号',
    chaser_start_mm BIGINT NOT NULL COMMENT '追赶者起始距离（毫米，非负），必须小于被追者起始距离',
    chased_start_mm BIGINT NOT NULL COMMENT '被追者起始距离（毫米，非负）',
    created_at BIGINT NOT NULL COMMENT '配置时间，Unix毫秒时间戳',
    created_by VARCHAR(64) NOT NULL COMMENT '配置操作者，非空',
    CONSTRAINT pk_pursuit_pair PRIMARY KEY (race_id, pair_no),
    CONSTRAINT fk_pursuit_pair_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS pursuit_trajectory (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '选手参赛号',
    version INT NOT NULL COMMENT '轨迹版本号，首次采样为1，每次更正加一',
    updated_at BIGINT NOT NULL COMMENT '最近一次采样/更正时间，Unix毫秒时间戳',
    updated_by VARCHAR(64) NOT NULL COMMENT '最近一次采样/更正操作者，非空',
    CONSTRAINT pk_pursuit_trajectory PRIMARY KEY (race_id, bib),
    CONSTRAINT fk_pursuit_trajectory_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib)
);

CREATE TABLE IF NOT EXISTS pursuit_sample (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '选手参赛号',
    seq INT NOT NULL COMMENT '采样序号，从1连续递增；隐含起点固定为(0,0)不占序号',
    time_ms BIGINT NOT NULL COMMENT '从起跑起的时刻（毫秒，正整数，同一人严格递增）',
    distance_mm BIGINT NOT NULL COMMENT '累计前进距离（毫米，非负，同一人不减）',
    CONSTRAINT pk_pursuit_sample PRIMARY KEY (race_id, bib, seq),
    CONSTRAINT fk_pursuit_sample_trajectory FOREIGN KEY (race_id, bib) REFERENCES pursuit_trajectory (race_id, bib)
);

CREATE TABLE IF NOT EXISTS pursuit_adjudication (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    pair_no INT NOT NULL COMMENT '追逐对序号；每个追逐对仅保留一条当前裁定，轨迹更正即删除旧裁定',
    chaser_bib VARCHAR(64) NOT NULL COMMENT '追赶者参赛号（裁定时的快照）',
    chased_bib VARCHAR(64) NOT NULL COMMENT '被追者参赛号（裁定时的快照）',
    chaser_trajectory_version INT NOT NULL COMMENT '裁定依据的追赶者轨迹版本',
    chased_trajectory_version INT NOT NULL COMMENT '裁定依据的被追者轨迹版本',
    catch_time_num BIGINT NOT NULL COMMENT '最早交会时刻（起跑起毫秒）精确有理数的分子，已约分',
    catch_time_den BIGINT NOT NULL COMMENT '最早交会时刻（起跑起毫秒）精确有理数的分母，已约分，正整数',
    created_at BIGINT NOT NULL COMMENT '裁定时间，Unix毫秒时间戳',
    created_by VARCHAR(64) NOT NULL COMMENT '裁定操作者，非空',
    CONSTRAINT pk_pursuit_adjudication PRIMARY KEY (race_id, pair_no),
    CONSTRAINT fk_pursuit_adjudication_pair FOREIGN KEY (race_id, pair_no) REFERENCES pursuit_pair (race_id, pair_no)
);

CREATE TABLE IF NOT EXISTS result_snapshot_pursuit_pair (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    pair_no INT NOT NULL COMMENT '追逐对序号',
    chaser_bib VARCHAR(64) NOT NULL COMMENT '追赶者参赛号',
    chased_bib VARCHAR(64) NOT NULL COMMENT '被追者参赛号',
    chaser_start_mm BIGINT NOT NULL COMMENT '追赶者起始距离（毫米）',
    chased_start_mm BIGINT NOT NULL COMMENT '被追者起始距离（毫米）',
    chaser_trajectory_version INT NOT NULL COMMENT '封榜时裁定依据的追赶者轨迹版本',
    chased_trajectory_version INT NOT NULL COMMENT '封榜时裁定依据的被追者轨迹版本',
    catch_time_num BIGINT NOT NULL COMMENT '封榜冻结的最早交会时刻（毫秒）有理数分子，已约分',
    catch_time_den BIGINT NOT NULL COMMENT '封榜冻结的最早交会时刻（毫秒）有理数分母，已约分',
    CONSTRAINT pk_snapshot_pursuit_pair PRIMARY KEY (race_id, pair_no),
    CONSTRAINT fk_snapshot_pursuit_pair_snapshot FOREIGN KEY (race_id) REFERENCES result_snapshot (race_id)
);

CREATE TABLE IF NOT EXISTS result_snapshot_pursuit_sample (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '选手参赛号',
    seq INT NOT NULL COMMENT '采样序号，从1连续递增；隐含起点固定为(0,0)不占序号',
    time_ms BIGINT NOT NULL COMMENT '从起跑起的时刻（毫秒）',
    distance_mm BIGINT NOT NULL COMMENT '累计前进距离（毫米）',
    CONSTRAINT pk_snapshot_pursuit_sample PRIMARY KEY (race_id, bib, seq),
    CONSTRAINT fk_snapshot_pursuit_sample_snapshot FOREIGN KEY (race_id) REFERENCES result_snapshot (race_id)
);
