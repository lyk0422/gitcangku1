-- 赛事成绩封榜 schema（H2 MySQL 兼容模式，仅嵌入式 H2 自动执行）
-- 时间单位：毫秒；时间戳为数据库默认时区时间，仅作审计用途。

CREATE TABLE IF NOT EXISTS race (
    race_id     VARCHAR(64)  NOT NULL COMMENT '赛事ID，全局唯一',
    version     BIGINT       NOT NULL COMMENT '乐观锁版本，从1开始，每次写操作加一',
    status      VARCHAR(16)  NOT NULL COMMENT '赛事状态：OPEN=可写入，SEALED=已封榜只读',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (race_id)
);

CREATE TABLE IF NOT EXISTS participant (
    race_id      VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib          VARCHAR(64) NOT NULL COMMENT '参赛号，赛事内唯一',
    raw_time_ms  BIGINT      NULL COMMENT '原始完赛耗时（毫秒，1~86400000）；NULL表示计时缺失（UNTIMED）',
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
    PRIMARY KEY (race_id, bib)
);

CREATE TABLE IF NOT EXISTS penalty (
    penalty_id  VARCHAR(64) NOT NULL COMMENT '处罚ID，全局唯一',
    race_id     VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib         VARCHAR(64) NOT NULL COMMENT '被处罚选手参赛号',
    type        VARCHAR(16) NOT NULL COMMENT '处罚类型：TIME_ADD=加时，DISQUALIFY=取消资格',
    amount_ms   BIGINT      NULL COMMENT '加时毫秒数（1~3600000）；DISQUALIFY类型恒为NULL',
    revoked     BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '是否已撤销；撤销后不再参与成绩计算',
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (penalty_id)
);

CREATE TABLE IF NOT EXISTS race_snapshot (
    race_id       VARCHAR(64) NOT NULL COMMENT '所属赛事ID',
    bib           VARCHAR(64) NOT NULL COMMENT '参赛号',
    status        VARCHAR(16) NOT NULL COMMENT '成绩状态：RANKED=参与排名，UNTIMED=计时缺失，DISQUALIFIED=取消资格',
    rank_no       INT         NULL COMMENT '名次（并列同名次，下一名次跳过并列人数）；非RANKED为NULL',
    total_time_ms BIGINT      NULL COMMENT '封榜时总耗时（毫秒）=原始耗时+未撤销加时；非RANKED为NULL',
    PRIMARY KEY (race_id, bib)
);

CREATE TABLE IF NOT EXISTS request_log (
    request_id   VARCHAR(64)  NOT NULL COMMENT '全局唯一请求ID，用于幂等去重；失败请求不占键',
    action       VARCHAR(40)  NOT NULL COMMENT '操作类型，如 CREATE_RACE/REGISTER/REVISE_TIME/ADD_PENALTY/REVOKE_PENALTY/SEAL',
    fingerprint  VARCHAR(512) NOT NULL COMMENT '请求参数指纹；同键异参返回409',
    response_body CLOB        NOT NULL COMMENT '成功响应JSON快照，用于同键同参重放',
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (request_id)
);
