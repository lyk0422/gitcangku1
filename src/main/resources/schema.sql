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

CREATE TABLE IF NOT EXISTS team (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    team_id VARCHAR(64) NOT NULL COMMENT '队伍ID，同一赛事内唯一',
    captain_bib VARCHAR(64) NOT NULL COMMENT '队长参赛号，须为本赛事已报名选手',
    status VARCHAR(16) NOT NULL COMMENT '名单状态：OPEN-可增删成员，LOCKED-已锁定禁止普通增删',
    roster_version INT NOT NULL COMMENT '当前名单版本，每次锁定加一；从未锁定为0',
    created_at BIGINT NOT NULL COMMENT '创建时间，Unix毫秒时间戳',
    updated_at BIGINT NOT NULL COMMENT '最近一次名单变更时间，Unix毫秒时间戳',
    CONSTRAINT pk_team PRIMARY KEY (race_id, team_id),
    CONSTRAINT fk_team_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS team_member (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    team_id VARCHAR(64) NOT NULL COMMENT '所属队伍ID',
    bib VARCHAR(64) NOT NULL COMMENT '成员参赛号；同一赛事内一名参赛者最多属于一支队伍',
    added_at BIGINT NOT NULL COMMENT '加入时间，Unix毫秒时间戳',
    CONSTRAINT pk_team_member PRIMARY KEY (race_id, team_id, bib),
    CONSTRAINT uk_team_member_race_bib UNIQUE (race_id, bib),
    CONSTRAINT fk_team_member_team FOREIGN KEY (race_id, team_id) REFERENCES team (race_id, team_id),
    CONSTRAINT fk_team_member_runner FOREIGN KEY (race_id, bib) REFERENCES runner (race_id, bib)
);

CREATE TABLE IF NOT EXISTS roster_lock (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    team_id VARCHAR(64) NOT NULL COMMENT '队伍ID',
    roster_version INT NOT NULL COMMENT '名单版本，从1开始，重锁生成新版本',
    race_version INT NOT NULL COMMENT '锁定时的赛事版本（个人成绩版本）',
    locked_by VARCHAR(64) NOT NULL COMMENT '提交锁定的队长参赛号',
    locked_at BIGINT NOT NULL COMMENT '锁定时间，Unix毫秒时间戳',
    unlocked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已被裁判解锁：FALSE-生效中，TRUE-已解锁（快照保留不删除）',
    unlock_reason VARCHAR(512) COMMENT '裁判解锁原因；未解锁为NULL',
    unlocked_at BIGINT COMMENT '解锁时间，Unix毫秒时间戳；未解锁为NULL',
    CONSTRAINT pk_roster_lock PRIMARY KEY (race_id, team_id, roster_version),
    CONSTRAINT fk_roster_lock_team FOREIGN KEY (race_id, team_id) REFERENCES team (race_id, team_id)
);

CREATE TABLE IF NOT EXISTS roster_lock_member (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    team_id VARCHAR(64) NOT NULL COMMENT '队伍ID',
    roster_version INT NOT NULL COMMENT '名单版本',
    bib VARCHAR(64) NOT NULL COMMENT '锁定名单成员参赛号',
    CONSTRAINT pk_roster_lock_member PRIMARY KEY (race_id, team_id, roster_version, bib),
    CONSTRAINT fk_roster_lock_member_lock FOREIGN KEY (race_id, team_id, roster_version)
        REFERENCES roster_lock (race_id, team_id, roster_version)
);

CREATE TABLE IF NOT EXISTS team_standing (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    team_id VARCHAR(64) NOT NULL COMMENT '队伍ID；仅当前处于锁定状态的队伍有行',
    roster_version INT NOT NULL COMMENT '得分对应的锁定名单版本',
    race_version INT NOT NULL COMMENT '得分重算时的赛事版本（与个人成绩版本一致）',
    member_count INT NOT NULL COMMENT '锁定名单人数（2~8）',
    ranked_count INT NOT NULL COMMENT '锁定名单中状态为RANKED的成员人数',
    total_time_ms BIGINT COMMENT '团队得分=全部RANKED成员总耗时（原始完赛耗时+生效加时）之和（毫秒）；存在未排名成员时为NULL表示得分不完整',
    computed_at BIGINT NOT NULL COMMENT '重算时间，Unix毫秒时间戳',
    CONSTRAINT pk_team_standing PRIMARY KEY (race_id, team_id),
    CONSTRAINT fk_team_standing_team FOREIGN KEY (race_id, team_id) REFERENCES team (race_id, team_id)
);

CREATE TABLE IF NOT EXISTS result_snapshot_team (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    team_id VARCHAR(64) NOT NULL COMMENT '队伍ID',
    roster_version INT NOT NULL COMMENT '封榜时固化的名单版本',
    race_version INT NOT NULL COMMENT '封榜时固化的个人成绩版本（封榜后的赛事版本）',
    member_count INT NOT NULL COMMENT '封榜时锁定名单人数',
    ranked_count INT NOT NULL COMMENT '封榜时RANKED成员人数',
    total_time_ms BIGINT COMMENT '封榜时固化的团队得分（毫秒）；存在未排名成员为NULL',
    CONSTRAINT pk_snapshot_team PRIMARY KEY (race_id, team_id),
    CONSTRAINT fk_snapshot_team_snapshot FOREIGN KEY (race_id) REFERENCES result_snapshot (race_id)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求ID（写操作幂等键）；名单锁定使用rosterKey指纹',
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：CREATE_RACE/REGISTER_RUNNER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/CONFIGURE_CHECKPOINTS/SUBMIT_TIMING/SEAL_RACE/CREATE_TEAM/ADD_TEAM_MEMBER/REMOVE_TEAM_MEMBER/LOCK_ROSTER/BATCH_LOCK_ROSTER/UNLOCK_ROSTER',
    request_digest CHAR(64) NOT NULL COMMENT '请求参数（requestId除外，含expectedVersion）规范化JSON的SHA-256摘要',
    response_status INT NOT NULL COMMENT '原成功请求的HTTP状态码，重放时原样返回',
    response_body TEXT COMMENT '原成功响应体JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次成功提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);
