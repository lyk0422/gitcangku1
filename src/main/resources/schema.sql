-- 剧目排练提示点：编排版本、冻结快照、执行回报。
-- 运行于 H2 内存库（MODE=MySQL），时间列均为 Asia/Shanghai 本地时间。

CREATE TABLE IF NOT EXISTS cue_version (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    play_code   VARCHAR(64)  NOT NULL,
    version_no  INT          NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT uq_cue_version_play_no UNIQUE (play_code, version_no)
);
COMMENT ON TABLE cue_version IS '剧目提示点编排版本，编排修改只追加新版本，历史版本不可变';
COMMENT ON COLUMN cue_version.play_code IS '剧目编码';
COMMENT ON COLUMN cue_version.version_no IS '剧目内递增版本号，从 1 开始';
COMMENT ON COLUMN cue_version.created_at IS '版本创建时间（Asia/Shanghai）';

CREATE TABLE IF NOT EXISTS cue_item (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id       BIGINT       NOT NULL,
    seq_no           INT          NOT NULL,
    cue_type         VARCHAR(32)  NOT NULL,
    trigger_word     VARCHAR(128) NOT NULL,
    duration_seconds INT          NOT NULL,
    CONSTRAINT fk_cue_item_version FOREIGN KEY (version_id) REFERENCES cue_version (id),
    CONSTRAINT uq_cue_item_version_seq UNIQUE (version_id, seq_no),
    CONSTRAINT ck_cue_item_duration CHECK (duration_seconds BETWEEN 1 AND 600),
    CONSTRAINT ck_cue_item_seq CHECK (seq_no >= 1)
);
COMMENT ON TABLE cue_item IS '版本内的提示点，按场次内序号 seq_no 递增排列';
COMMENT ON COLUMN cue_item.seq_no IS '场次内提示点序号，从 1 开始且连续，同版本内不可重复';
COMMENT ON COLUMN cue_item.cue_type IS '提示点类型，如 LIGHT（灯光）、SOUND（音效），非空';
COMMENT ON COLUMN cue_item.trigger_word IS '触发口令，非空';
COMMENT ON COLUMN cue_item.duration_seconds IS '持续秒数，合法范围 1 至 600 秒，单位：秒';

CREATE TABLE IF NOT EXISTS rehearsal_session (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    play_code           VARCHAR(64)  NOT NULL,
    session_code        VARCHAR(64)  NOT NULL,
    version_id          BIGINT       NULL,
    status              VARCHAR(16)  NOT NULL,
    total_cues          INT          NOT NULL DEFAULT 0,
    executed_seq        INT          NOT NULL DEFAULT 0,
    frozen_version_id   BIGINT       NULL,
    frozen_at           TIMESTAMP    NULL,
    completed_at        TIMESTAMP    NULL,
    created_at          TIMESTAMP    NOT NULL,
    CONSTRAINT uq_rehearsal_session_code UNIQUE (play_code, session_code),
    CONSTRAINT fk_rehearsal_version FOREIGN KEY (version_id) REFERENCES cue_version (id),
    CONSTRAINT fk_rehearsal_frozen_version FOREIGN KEY (frozen_version_id) REFERENCES cue_version (id)
);
COMMENT ON TABLE rehearsal_session IS '排练场次；未开始可改绑版本，开始时冻结快照，运行中快照不可变';
COMMENT ON COLUMN rehearsal_session.session_code IS '场次业务编码，同剧目内唯一';
COMMENT ON COLUMN rehearsal_session.version_id IS '当前绑定的编排版本 ID；未开始场次可重新绑定，开始后不再变化';
COMMENT ON COLUMN rehearsal_session.status IS '场次状态：NOT_STARTED 未开始 / RUNNING 运行中 / FINISHED 已结束';
COMMENT ON COLUMN rehearsal_session.total_cues IS '冻结快照中的提示点总数，为 0 表示尚未冻结';
COMMENT ON COLUMN rehearsal_session.executed_seq IS '已连续成功执行到的序号，从 0 开始，失败回报不推进';
COMMENT ON COLUMN rehearsal_session.frozen_version_id IS '排练开始时冻结的版本 ID，与运行中绑定版本解耦，未冻结时为空';
COMMENT ON COLUMN rehearsal_session.frozen_at IS '冻结（排练开始）时间，未冻结为空（Asia/Shanghai）';
COMMENT ON COLUMN rehearsal_session.completed_at IS '本场结束时间，未结束为空（Asia/Shanghai）';

CREATE TABLE IF NOT EXISTS cue_snapshot (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id       BIGINT       NOT NULL,
    seq_no           INT          NOT NULL,
    cue_type         VARCHAR(32)  NOT NULL,
    trigger_word     VARCHAR(128) NOT NULL,
    duration_seconds INT          NOT NULL,
    CONSTRAINT fk_cue_snapshot_session FOREIGN KEY (session_id) REFERENCES rehearsal_session (id),
    CONSTRAINT uq_cue_snapshot_session_seq UNIQUE (session_id, seq_no)
);
COMMENT ON TABLE cue_snapshot IS '排练开始时冻结的提示点快照，之后编排新版本不影响本场';
COMMENT ON COLUMN cue_snapshot.session_id IS '所属排练场次 ID';
COMMENT ON COLUMN cue_snapshot.seq_no IS '冻结时的提示点序号';
COMMENT ON COLUMN cue_snapshot.duration_seconds IS '冻结时的持续秒数（1 至 600），单位：秒';

CREATE TABLE IF NOT EXISTS cue_report (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id       BIGINT       NOT NULL,
    seq_no           INT          NOT NULL,
    success          BOOLEAN      NOT NULL,
    fail_reason      VARCHAR(255) NULL,
    request_id       VARCHAR(64)  NOT NULL,
    operator_code    VARCHAR(64)  NULL,
    reported_at      TIMESTAMP    NOT NULL,
    success_seq      INT          NULL,
    CONSTRAINT fk_cue_report_session FOREIGN KEY (session_id) REFERENCES rehearsal_session (id),
    CONSTRAINT uq_cue_report_request UNIQUE (session_id, request_id),
    CONSTRAINT uq_cue_report_success UNIQUE (session_id, success_seq)
);
COMMENT ON TABLE cue_report IS '操作员执行回报；成功与有因失败都落库，被 409 拒绝的跳号/重复请求不落库、不消耗幂等键';
COMMENT ON COLUMN cue_report.seq_no IS '回报的提示点序号';
COMMENT ON COLUMN cue_report.success IS 'TRUE 表示执行成功并推进序号；FALSE 表示执行失败，记录原因且不推进';
COMMENT ON COLUMN cue_report.fail_reason IS '失败原因，仅 success=FALSE 时非空；成功时为空';
COMMENT ON COLUMN cue_report.request_id IS '客户端幂等键，同场次唯一；被拒绝的请求不写入，因此不占用该键';
COMMENT ON COLUMN cue_report.operator_code IS '操作员标识，可空';
COMMENT ON COLUMN cue_report.reported_at IS '回报受理时间（Asia/Shanghai）';
COMMENT ON COLUMN cue_report.success_seq IS '成功回报占用的序号（生成列）；NULL 表示失败回报，借唯一约束保证同一提示点仅一条成功记录';
