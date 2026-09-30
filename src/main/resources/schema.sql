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

-- 团队名单：名单按团队逐个版本锁定，锁定即在成员行固化当时个人成绩归属；历史版本永不改写。
CREATE TABLE IF NOT EXISTS team_roster (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    team_id VARCHAR(64) NOT NULL COMMENT '团队ID，同一赛事内唯一',
    version_no INT NOT NULL COMMENT '名单版本号，同一团队内从1连续递增；锁定后不可修改',
    locked_at BIGINT NOT NULL COMMENT '锁定时间，Unix毫秒时间戳',
    team_points INT NOT NULL COMMENT '锁定时刻按固化成员成绩汇总的团队积分（未剔退赛）；封榜团队积分以team_standing_snapshot为准',
    CONSTRAINT pk_team_roster PRIMARY KEY (race_id, team_id, version_no),
    CONSTRAINT fk_team_roster_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS team_roster_member (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    team_id VARCHAR(64) NOT NULL COMMENT '所属团队ID',
    version_no INT NOT NULL COMMENT '所属名单版本号；成员归属随版本固化，退赛不删除本行',
    bib VARCHAR(64) NOT NULL COMMENT '参赛号',
    rank_no INT COMMENT '锁定时固化的全场名次（从1开始，并列同名次并跳号）；非RANKED为NULL',
    entry_status VARCHAR(24) NOT NULL COMMENT '锁定时固化的个人成绩状态：RANKED/UNTIMED/MISSING_CHECKPOINT/DISQUALIFIED',
    finish_time_ms BIGINT COMMENT '锁定时固化的原始完赛耗时（毫秒）；计时缺失为NULL',
    penalty_ms BIGINT NOT NULL COMMENT '锁定时固化的生效加时合计毫秒数，无加时为0',
    total_time_ms BIGINT COMMENT '锁定时固化的总耗时（毫秒）；非RANKED为NULL',
    member_points INT NOT NULL COMMENT '该成员为本团队贡献的名次积分（按固化名次计算）；非RANKED为0',
    display_order INT NOT NULL COMMENT '名单内展示顺序，从0开始，按参赛号字典序',
    CONSTRAINT pk_team_roster_member PRIMARY KEY (race_id, team_id, version_no, bib),
    CONSTRAINT fk_roster_member_roster FOREIGN KEY (race_id, team_id, version_no)
        REFERENCES team_roster (race_id, team_id, version_no)
);

-- 成员在同一赛事内的唯一归属：唯一键(race_id,bib)保证不得跨队；退赛只回填退赛字段，不删行。
CREATE TABLE IF NOT EXISTS team_membership (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib VARCHAR(64) NOT NULL COMMENT '参赛号，同一赛事内只能归属一个团队',
    team_id VARCHAR(64) NOT NULL COMMENT '归属团队ID；锁定后不可变更',
    first_version_no INT NOT NULL COMMENT '首次被该团队锁定的名单版本号',
    locked_at BIGINT NOT NULL COMMENT '首次入队锁定时间，Unix毫秒时间戳',
    effective_session VARCHAR(64) COMMENT '退赛生效场次代码；未退赛为NULL',
    withdrawal_reason VARCHAR(255) COMMENT '退赛原因；未退赛为NULL',
    withdrawn_at BIGINT COMMENT '退赛记录时间，Unix毫秒时间戳；未退赛为NULL',
    -- 主键(race_id,bib)同时充当“同一赛事内成员唯一归属”唯一键，不得跨队。
    CONSTRAINT pk_team_membership PRIMARY KEY (race_id, bib),
    CONSTRAINT fk_team_membership_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

-- 封榜团队榜快照：每个团队一行，引用封榜时刻该团队一个完整锁定名单版本，封榜后不再重算。
CREATE TABLE IF NOT EXISTS team_standing_snapshot (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    team_id VARCHAR(64) NOT NULL COMMENT '团队ID',
    roster_version_no INT NOT NULL COMMENT '封榜引用的完整名单版本号（该团队最新已锁定版本）',
    team_points INT NOT NULL COMMENT '封榜时刻固化的团队积分（剔除当时已退赛成员）',
    member_count INT NOT NULL COMMENT '计入积分的成员人数（已剔除退赛成员）',
    rank_no INT NOT NULL COMMENT '团队名次，从1开始，积分并列同名次并跳号',
    display_order INT NOT NULL COMMENT '展示顺序，从0开始：先名次顺序，并列者按团队ID字典序',
    CONSTRAINT pk_team_standing_snapshot PRIMARY KEY (race_id, team_id),
    CONSTRAINT fk_team_standing_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求ID（写操作幂等键）',
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：CREATE_RACE/REGISTER_RUNNER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/CONFIGURE_CHECKPOINTS/SUBMIT_TIMING/SEAL_RACE/LOCK_ROSTER/WITHDRAW_MEMBER',
    request_digest CHAR(64) NOT NULL COMMENT '请求参数（requestId除外，含expectedVersion）规范化JSON的SHA-256摘要',
    response_status INT NOT NULL COMMENT '原成功请求的HTTP状态码，重放时原样返回',
    response_body TEXT COMMENT '原成功响应体JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次成功提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);
