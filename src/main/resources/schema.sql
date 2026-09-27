-- 赛事成绩封榜领域表结构。
-- 本地默认以 H2 内存库运行：jdbc:h2:mem:race_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
-- 标识符统一小写；时间字段均为 Unix 毫秒时间戳（UTC），不涉及时区换算。

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
    withdrawn BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已退赛：FALSE-在赛，TRUE-已退赛（状态WITHDRAWN，不参与排名）',
    withdrawn_at BIGINT COMMENT '退赛时刻，Unix毫秒时间戳（UTC）；未退赛为NULL',
    created_at BIGINT NOT NULL COMMENT '登记时间，Unix毫秒时间戳（UTC）',
    updated_at BIGINT NOT NULL COMMENT '最近一次计时修订时间，Unix毫秒时间戳（UTC）',
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
    created_at BIGINT NOT NULL COMMENT '处罚新增时间，Unix毫秒时间戳（UTC）',
    revoked_at BIGINT COMMENT '处罚撤销时间，Unix毫秒时间戳（UTC）；未撤销为NULL',
    CONSTRAINT pk_penalty PRIMARY KEY (penalty_id),
    CONSTRAINT fk_penalty_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    INDEX idx_penalty_race_bib (race_id, bib)
);

-- 医疗暂停“开始”事件：仅插入，永不更新；恢复事实见 medical_hold_resumption。
CREATE TABLE IF NOT EXISTS medical_hold (
    hold_id VARCHAR(128) NOT NULL COMMENT '医疗暂停ID，全局唯一',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '接受医疗暂停的选手参赛号',
    start_at BIGINT NOT NULL COMMENT '暂停开始时刻，Unix毫秒时间戳（UTC），暂停区间左闭端点',
    reason VARCHAR(512) NOT NULL COMMENT '医疗暂停原因，登记时固化',
    started_by VARCHAR(128) NOT NULL COMMENT '登记暂停开始的医疗角色ID',
    created_at BIGINT NOT NULL COMMENT '暂停开始登记的提交时刻，Unix毫秒时间戳（UTC）',
    CONSTRAINT pk_medical_hold PRIMARY KEY (hold_id),
    CONSTRAINT fk_medical_hold_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    INDEX idx_medical_hold_race_bib (race_id, bib)
);

-- 医疗暂停“恢复”事件：仅插入，永不更新；一条暂停至多一条恢复记录。
CREATE TABLE IF NOT EXISTS medical_hold_resumption (
    hold_id VARCHAR(128) NOT NULL COMMENT '对应的医疗暂停ID',
    end_at BIGINT NOT NULL COMMENT '暂停结束时刻，Unix毫秒时间戳（UTC），必须晚于start_at，暂停区间右端点（右开）',
    resumed_by VARCHAR(128) NOT NULL COMMENT '确认适赛并恢复的医疗角色ID，必须与started_by不同',
    fit_conclusion VARCHAR(512) NOT NULL COMMENT '适赛结论，恢复时固化',
    resumed_at BIGINT NOT NULL COMMENT '恢复登记的提交时刻，Unix毫秒时间戳（UTC）',
    CONSTRAINT pk_medical_hold_resumption PRIMARY KEY (hold_id),
    CONSTRAINT fk_resumption_hold FOREIGN KEY (hold_id) REFERENCES medical_hold (hold_id)
);

-- 生效（未恢复）暂停的并发兜底表：同一选手同时仅一条生效暂停，重复开始撞主键即409。
-- 非证据表：证据以 medical_hold / medical_hold_resumption 两张仅插入表为准。
CREATE TABLE IF NOT EXISTS medical_hold_active (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '选手参赛号',
    hold_id VARCHAR(128) NOT NULL COMMENT '生效中的医疗暂停ID',
    start_at BIGINT NOT NULL COMMENT '暂停开始时刻，Unix毫秒时间戳（UTC）',
    CONSTRAINT pk_medical_hold_active PRIMARY KEY (race_id, bib),
    CONSTRAINT fk_active_hold FOREIGN KEY (hold_id) REFERENCES medical_hold (hold_id)
);

-- 退赛事件：仅插入永不更新；runner.withdrawn 为当前资格标记，本表固化退赛原因与时刻。
CREATE TABLE IF NOT EXISTS runner_withdrawal (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '退赛选手参赛号',
    reason VARCHAR(512) COMMENT '退赛原因；未填写为NULL',
    created_at BIGINT NOT NULL COMMENT '退赛提交时刻，Unix毫秒时间戳（UTC）',
    CONSTRAINT pk_runner_withdrawal PRIMARY KEY (race_id, bib),
    CONSTRAINT fk_withdrawal_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib)
);

-- 终点（原始完赛）计时事件：登记带成绩与每次计时修订都追加一行，仅插入永不更新。
-- 恢复暂停时据此判定暂停区间内是否存在终点计时，存在则422要求先裁定完赛状态。
CREATE TABLE IF NOT EXISTS runner_finish_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '选手参赛号',
    finish_time_ms BIGINT NOT NULL COMMENT '该次登记/修订提交的原始完赛耗时（毫秒，1~86400000）',
    request_id VARCHAR(128) NOT NULL COMMENT '产生该事件的写操作requestId',
    created_at BIGINT NOT NULL COMMENT '终点计时提交时刻，Unix毫秒时间戳（UTC）',
    CONSTRAINT pk_runner_finish_event PRIMARY KEY (id),
    CONSTRAINT fk_finish_event_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    INDEX idx_finish_event_race_bib (race_id, bib)
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
    covered_checkpoint_count INT NOT NULL DEFAULT 0 COMMENT '封榜时该选手有效（未被医疗暂停排除）分段覆盖的检查点数量；被排除分段不计覆盖',
    medical_hold_active BOOLEAN NOT NULL DEFAULT FALSE COMMENT '封榜时刻该选手是否处于生效医疗暂停区间：TRUE-暂停中，FALSE-无生效暂停',
    excluded_timing_count INT NOT NULL DEFAULT 0 COMMENT '因医疗暂停被排除（MEDICAL_HOLD）的分段计时数量，排除原因固化在result_snapshot_checkpoint',
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
    excluded_hold_id VARCHAR(128) COMMENT '排除该计时的医疗暂停ID；非NULL时该计时标记为MEDICAL_HOLD，保存但不参与排名与检查点覆盖；NULL为正常计时。插入时按提交顺序裁决，之后永不改变',
    created_at BIGINT NOT NULL COMMENT '记录提交时间，Unix毫秒时间戳（UTC）',
    CONSTRAINT pk_checkpoint_timing PRIMARY KEY (timing_id),
    CONSTRAINT uk_timing_runner_checkpoint UNIQUE (race_id, bib, checkpoint_code),
    CONSTRAINT fk_timing_checkpoint FOREIGN KEY (race_id, checkpoint_code) REFERENCES checkpoint (race_id, checkpoint_code),
    CONSTRAINT fk_timing_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib),
    CONSTRAINT fk_timing_hold FOREIGN KEY (excluded_hold_id) REFERENCES medical_hold (hold_id),
    INDEX idx_timing_race_bib (race_id, bib)
);

CREATE TABLE IF NOT EXISTS result_snapshot_checkpoint (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '参赛号',
    checkpoint_code VARCHAR(64) NOT NULL COMMENT '检查点代码',
    position INT NOT NULL COMMENT '检查点顺序，从1递增',
    elapsed_millis BIGINT COMMENT '封榜时该选手通过该检查点的累计耗时（毫秒）；缺失检查点为NULL',
    timing_id VARCHAR(128) COMMENT '分段记录ID；缺失检查点为NULL',
    excluded_hold_id VARCHAR(128) COMMENT '排除该分段的医疗暂停ID；非NULL表示该分段因医疗暂停被排除（MEDICAL_HOLD），不计覆盖不参与排名；NULL为正常/缺失',
    excluded_reason VARCHAR(512) COMMENT '分段被排除的原因（固化的医疗暂停原因）；未排除为NULL',
    CONSTRAINT pk_snapshot_checkpoint PRIMARY KEY (race_id, bib, checkpoint_code),
    CONSTRAINT fk_snapshot_checkpoint_snapshot FOREIGN KEY (race_id) REFERENCES result_snapshot (race_id)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求ID（写操作幂等键）',
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：CREATE_RACE/REGISTER_RUNNER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/CONFIGURE_CHECKPOINTS/SUBMIT_TIMING/WITHDRAW_RUNNER/START_MEDICAL_HOLD/RESUME_MEDICAL_HOLD/SEAL_RACE',
    request_digest CHAR(64) NOT NULL COMMENT '请求参数（requestId除外，含expectedVersion）规范化JSON的SHA-256摘要',
    response_status INT NOT NULL COMMENT '原成功请求的HTTP状态码，重放时原样返回',
    response_body TEXT COMMENT '原成功响应体JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次成功提交时间，Unix毫秒时间戳（UTC）',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);
