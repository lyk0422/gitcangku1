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
    rank_no INT COMMENT '名次（从1开始，并列同名次并跳号，如1、1、3）；未计时/取消资格为NULL',
    status VARCHAR(16) NOT NULL COMMENT '成绩状态：RANKED-参与排名，UNTIMED-计时缺失，DISQUALIFIED-取消资格',
    finish_time_ms BIGINT COMMENT '原始完赛耗时（毫秒）；计时缺失为NULL',
    penalty_ms BIGINT NOT NULL COMMENT '生效（未撤销）加时处罚合计毫秒数，无加时为0',
    total_time_ms BIGINT COMMENT '总耗时=原始完赛耗时+生效加时（毫秒）；未排名时为NULL',
    display_order INT NOT NULL COMMENT '展示顺序，从0开始：先名次顺序，并列者按参赛号字典序，其余按参赛号字典序',
    CONSTRAINT pk_snapshot_entry PRIMARY KEY (race_id, bib),
    CONSTRAINT fk_snapshot_entry_snapshot FOREIGN KEY (race_id) REFERENCES result_snapshot (race_id)
);

CREATE TABLE IF NOT EXISTS relay_config (
    race_id VARCHAR(64) NOT NULL COMMENT '赛事ID，每个赛事至多一份接力配置；存在即接力模式',
    leg_count INT NOT NULL COMMENT '棒次数，取值2~8，配置后不可修改',
    exchange_limit_ms BIGINT NOT NULL COMMENT '交接区用时上限（毫秒，取值1~10000），超过即判犯规',
    created_at BIGINT NOT NULL COMMENT '配置时间，Unix毫秒时间戳',
    CONSTRAINT pk_relay_config PRIMARY KEY (race_id),
    CONSTRAINT fk_relay_config_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS relay_team_leg (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    team_key VARCHAR(64) NOT NULL COMMENT '队伍标识，同一赛事内唯一',
    leg_no INT NOT NULL COMMENT '棒次序号，从1开始',
    runner VARCHAR(64) NOT NULL COMMENT '该棒次登记选手标识，顺序固定，同队同棒次仅一人',
    CONSTRAINT pk_relay_team_leg PRIMARY KEY (race_id, team_key, leg_no),
    CONSTRAINT fk_relay_team_leg_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS relay_handoff (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    team_key VARCHAR(64) NOT NULL COMMENT '队伍标识',
    leg_no INT NOT NULL COMMENT '交接棒次（接棒选手棒次，取值2~leg_count），每队每棒次仅一条',
    receiver VARCHAR(64) NOT NULL COMMENT '接棒选手标识，须与登记棒次选手一致',
    elapsed_ms BIGINT NOT NULL COMMENT '接棒选手累计用时（毫秒），须大于上一棒次记录值',
    zone_ms BIGINT NOT NULL COMMENT '交接区实际用时（毫秒，非负），超过上限判犯规',
    foul BOOLEAN NOT NULL COMMENT '本次交接是否犯规：TRUE-交接区用时超上限',
    server_completed_at BIGINT NOT NULL COMMENT '本次交接完成的服务端时刻，Unix毫秒时间戳',
    CONSTRAINT pk_relay_handoff PRIMARY KEY (race_id, team_key, leg_no),
    CONSTRAINT fk_relay_handoff_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS relay_foul (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    team_key VARCHAR(64) NOT NULL COMMENT '犯规队伍标识',
    leg_no INT NOT NULL COMMENT '犯规发生的交接棒次，每队每交接至多一条犯规（不可逆）',
    zone_ms BIGINT NOT NULL COMMENT '交接区实际用时（毫秒）',
    limit_ms BIGINT NOT NULL COMMENT '判犯规时生效的交接区上限（毫秒）',
    created_at BIGINT NOT NULL COMMENT '犯规记录时间，Unix毫秒时间戳',
    CONSTRAINT pk_relay_foul PRIMARY KEY (race_id, team_key, leg_no),
    CONSTRAINT fk_relay_foul_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS relay_finish (
    race_id VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    team_key VARCHAR(64) NOT NULL COMMENT '队伍标识，末棒交接完成后自动生成完赛记录',
    total_ms BIGINT NOT NULL COMMENT '接力总用时（毫秒），取末棒 elapsedMillis',
    foul_count INT NOT NULL COMMENT '该队犯规次数；达到2次时状态为DISQUALIFIED',
    status VARCHAR(16) NOT NULL COMMENT '队伍成绩状态：RANKED-参与排名，DISQUALIFIED-取消资格',
    created_at BIGINT NOT NULL COMMENT '完赛记录生成时间，Unix毫秒时间戳',
    CONSTRAINT pk_relay_finish PRIMARY KEY (race_id, team_key),
    CONSTRAINT fk_relay_finish_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS relay_snapshot (
    race_id VARCHAR(64) NOT NULL COMMENT '赛事ID，接力赛事封榜后仅一份只读快照',
    version INT NOT NULL COMMENT '封榜后的赛事版本号',
    sealed_at BIGINT NOT NULL COMMENT '封榜时间，Unix毫秒时间戳',
    CONSTRAINT pk_relay_snapshot PRIMARY KEY (race_id),
    CONSTRAINT fk_relay_snapshot_race FOREIGN KEY (race_id) REFERENCES race (race_id)
);

CREATE TABLE IF NOT EXISTS relay_snapshot_team (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    team_key VARCHAR(64) NOT NULL COMMENT '队伍标识',
    rank_no INT COMMENT '名次（从1开始，并列同名次并跳号）；未完赛/取消资格为NULL',
    status VARCHAR(16) NOT NULL COMMENT '队伍成绩状态：RANKED-参与排名，UNTIMED-未完赛，DISQUALIFIED-取消资格',
    total_ms BIGINT COMMENT '接力总用时（毫秒）；未完赛/取消资格为NULL',
    foul_count INT NOT NULL COMMENT '犯规次数，存在犯规的队伍以此标注',
    display_order INT NOT NULL COMMENT '展示顺序，从0开始：先名次顺序，其余按队伍标识字典序',
    CONSTRAINT pk_relay_snapshot_team PRIMARY KEY (race_id, team_key),
    CONSTRAINT fk_relay_snapshot_team FOREIGN KEY (race_id) REFERENCES relay_snapshot (race_id)
);

CREATE TABLE IF NOT EXISTS relay_snapshot_leg (
    race_id VARCHAR(64) NOT NULL COMMENT '所属快照的赛事ID',
    team_key VARCHAR(64) NOT NULL COMMENT '队伍标识',
    leg_no INT NOT NULL COMMENT '棒次序号，从1开始',
    runner VARCHAR(64) NOT NULL COMMENT '该棒次登记选手标识',
    elapsed_ms BIGINT COMMENT '该棒次结束时的累计用时（毫秒）；未交接为NULL',
    split_ms BIGINT COMMENT '该棒次分段用时（毫秒）；无法计算为NULL',
    zone_ms BIGINT COMMENT '进入该棒次的交接区用时（毫秒，棒次>=2）；未交接/首棒为NULL',
    foul BOOLEAN NOT NULL COMMENT '进入该棒次的交接是否犯规（不可逆）',
    CONSTRAINT pk_relay_snapshot_leg PRIMARY KEY (race_id, team_key, leg_no),
    CONSTRAINT fk_relay_snapshot_leg FOREIGN KEY (race_id) REFERENCES relay_snapshot (race_id)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) NOT NULL COMMENT '全局唯一请求ID（写操作幂等键）',
    operation VARCHAR(48) NOT NULL COMMENT '操作类型：CREATE_RACE/REGISTER_RUNNER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/SEAL_RACE/CONFIGURE_RELAY/SUBMIT_HANDOFF',
    request_digest CHAR(64) NOT NULL COMMENT '请求参数（requestId除外，含expectedVersion）规范化JSON的SHA-256摘要',
    response_status INT NOT NULL COMMENT '原成功请求的HTTP状态码，重放时原样返回',
    response_body TEXT COMMENT '原成功响应体JSON，重放时原样返回',
    created_at BIGINT NOT NULL COMMENT '首次成功提交时间，Unix毫秒时间戳',
    CONSTRAINT pk_idempotency_record PRIMARY KEY (request_id)
);
