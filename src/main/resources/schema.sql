-- 赛事成绩封榜领域表结构。
-- 本地默认以 H2 内存库运行：jdbc:h2:mem:race_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
-- 标识符统一小写；时间字段均为 Unix 毫秒时间戳（BIGINT），不涉及时区换算。

CREATE TABLE IF NOT EXISTS race (
    race_id VARCHAR(64) NOT NULL COMMENT '赛事ID，全局唯一',
    version INT NOT NULL COMMENT '版本号，从1开始，每次写操作加一',
    status VARCHAR(16) NOT NULL COMMENT '赛事状态：OPEN-开放可写，SEALED-已封榜只读',
    mode VARCHAR(16) NOT NULL DEFAULT 'STANDARD' COMMENT '赛事模式：STANDARD-普通计时，PURSUIT-追逐赛（计时前启用，启用后不可回退）',
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
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：CREATE_RACE/REGISTER_RUNNER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/CONFIGURE_CHECKPOINTS/SUBMIT_TIMING/SEAL_RACE/ENABLE_PURSUIT/SUBMIT_SAMPLES/SUBMIT_RULING',
    request_digest CHAR(64) NOT NULL COMMENT '请求参数（requestId除外，含expectedVersion）规范化JSON的SHA-256摘要',
    response_status INT NOT NULL COMMENT '原成功请求的HTTP状态码，重放时原样返回',
    response_body TEXT COMMENT '原成功响应体JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次成功提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);

-- 追逐赛（PURSUIT）模式表结构：合成直线进度，不模拟赛道。
-- 位置（毫米）= 起始距离 + 累计前进量；采样间线性插值，禁止外推。

CREATE TABLE IF NOT EXISTS pursuit_pair (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    pair_id VARCHAR(64) NOT NULL COMMENT '追逐对ID，赛事内唯一',
    pursuer_bib VARCHAR(64) NOT NULL COMMENT '追赶者参赛号，起始距离必须小于被追者',
    pursued_bib VARCHAR(64) NOT NULL COMMENT '被追者参赛号',
    pursuer_start_mm BIGINT NOT NULL COMMENT '追赶者起始距离（毫米，非负整数）',
    pursued_start_mm BIGINT NOT NULL COMMENT '被追者起始距离（毫米，非负整数）',
    created_at BIGINT NOT NULL COMMENT '配置时间，Unix毫秒时间戳',
    CONSTRAINT pk_pursuit_pair PRIMARY KEY (race_id, pair_id),
    CONSTRAINT fk_pursuit_pair_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS trajectory (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '选手参赛号',
    version INT NOT NULL COMMENT '轨迹版本，从1开始，每次采样提交/更正加一',
    sample_count INT NOT NULL COMMENT '当前版本采样点数（1~10，首点固定(0,0)）',
    operator VARCHAR(64) NOT NULL COMMENT '最近一次提交操作者',
    updated_at BIGINT NOT NULL COMMENT '最近一次提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_trajectory PRIMARY KEY (race_id, bib),
    CONSTRAINT fk_trajectory_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS trajectory_sample (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '选手参赛号',
    seq INT NOT NULL COMMENT '采样序号，从0开始；seq=0固定为(0,0)',
    elapsed_ms BIGINT NOT NULL COMMENT '从起跑起的累计毫秒（非负整数，同一人严格递增）',
    progress_mm BIGINT NOT NULL COMMENT '累计前进毫米（非负整数，同一人单调不减）',
    CONSTRAINT pk_trajectory_sample PRIMARY KEY (race_id, bib, seq),
    CONSTRAINT fk_sample_trajectory FOREIGN KEY (race_id, bib) REFERENCES trajectory (race_id, bib)
);

CREATE TABLE IF NOT EXISTS pursuit_ruling (
    ruling_id VARCHAR(64) NOT NULL COMMENT '裁定ID，全局唯一',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    pair_id VARCHAR(64) NOT NULL COMMENT '追逐对ID',
    pursuer_trajectory_version INT NOT NULL COMMENT '裁定时追赶者轨迹版本',
    pursued_trajectory_version INT NOT NULL COMMENT '裁定时被追者轨迹版本',
    catch_time_num VARCHAR(64) NOT NULL COMMENT '最早交会时刻（从起跑起毫秒）精确有理数分子，已约分',
    catch_time_den VARCHAR(64) NOT NULL COMMENT '最早交会时刻精确有理数分母（正整数），已约分',
    status VARCHAR(16) NOT NULL COMMENT '裁定状态：CURRENT-当前有效，SUPERSEDED-因轨迹更正失效',
    operator VARCHAR(64) NOT NULL COMMENT '裁定操作者',
    created_at BIGINT NOT NULL COMMENT '裁定时间，Unix毫秒时间戳',
    CONSTRAINT pk_pursuit_ruling PRIMARY KEY (ruling_id),
    CONSTRAINT fk_ruling_race FOREIGN KEY (race_id) REFERENCES race (race_id),
    INDEX idx_ruling_pair (race_id, pair_id, status)
);

CREATE TABLE IF NOT EXISTS result_snapshot_trajectory (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '选手参赛号',
    trajectory_version INT NOT NULL COMMENT '封榜时轨迹版本',
    seq INT NOT NULL COMMENT '采样序号，从0开始',
    elapsed_ms BIGINT NOT NULL COMMENT '从起跑起的累计毫秒（非负整数）',
    progress_mm BIGINT NOT NULL COMMENT '累计前进毫米（非负整数）',
    CONSTRAINT pk_snapshot_trajectory PRIMARY KEY (race_id, bib, seq),
    CONSTRAINT fk_snapshot_trajectory_snapshot FOREIGN KEY (race_id) REFERENCES result_snapshot (race_id)
);

CREATE TABLE IF NOT EXISTS result_snapshot_ruling (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    pair_id VARCHAR(64) NOT NULL COMMENT '追逐对ID',
    ruling_id VARCHAR(64) NOT NULL COMMENT '封榜时当前裁定ID',
    pursuer_trajectory_version INT NOT NULL COMMENT '裁定时追赶者轨迹版本',
    pursued_trajectory_version INT NOT NULL COMMENT '裁定时被追者轨迹版本',
    catch_time_num VARCHAR(64) NOT NULL COMMENT '最早交会时刻精确有理数分子，已约分',
    catch_time_den VARCHAR(64) NOT NULL COMMENT '最早交会时刻精确有理数分母（正整数），已约分',
    CONSTRAINT pk_snapshot_ruling PRIMARY KEY (race_id, pair_id),
    CONSTRAINT fk_snapshot_ruling_snapshot FOREIGN KEY (race_id) REFERENCES result_snapshot (race_id)
);
