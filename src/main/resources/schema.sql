-- 泊位靠泊潮高校核：H2 内存库（MODE=MySQL）自动建表脚本。
-- 深度、吃水、潮高、余量单位均为米；所有时间为 Asia/Shanghai 本地时间（无时区）。

CREATE TABLE IF NOT EXISTS berth (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    code         VARCHAR(64)  NOT NULL,
    static_depth DECIMAL(6,2) NOT NULL,
    created_at   DATETIME     NOT NULL,
    CONSTRAINT uk_berth_code UNIQUE (code)
);
COMMENT ON TABLE berth IS '泊位表';
COMMENT ON COLUMN berth.code IS '泊位编码，业务唯一键';
COMMENT ON COLUMN berth.static_depth IS '泊位静态水深（米，相对海图基准面）';
COMMENT ON COLUMN berth.created_at IS '创建时间（Asia/Shanghai）';

CREATE TABLE IF NOT EXISTS tide_version (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_no  BIGINT       NOT NULL,
    range_start DATETIME     NOT NULL,
    range_end   DATETIME     NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    published_at DATETIME   NOT NULL,
    CONSTRAINT uk_tide_version_no UNIQUE (version_no)
);
COMMENT ON TABLE tide_version IS '潮高版本表，每次发布生成一个新版本';
COMMENT ON COLUMN tide_version.version_no IS '单调递增版本号';
COMMENT ON COLUMN tide_version.range_start IS '本版本覆盖时间轴起点（含，Asia/Shanghai）';
COMMENT ON COLUMN tide_version.range_end IS '本版本覆盖时间轴终点（不含，Asia/Shanghai）';
COMMENT ON COLUMN tide_version.status IS 'CURRENT=最新已提交版本，SUPERSEDED=已被新版本取代';
COMMENT ON COLUMN tide_version.published_at IS '版本发布时间（Asia/Shanghai）';

CREATE TABLE IF NOT EXISTS tide_segment (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id BIGINT       NOT NULL,
    start_time DATETIME     NOT NULL,
    end_time   DATETIME     NOT NULL,
    height     DECIMAL(5,2) NOT NULL,
    CONSTRAINT uk_tide_segment_version_start UNIQUE (version_id, start_time)
);
COMMENT ON TABLE tide_segment IS '潮高区间表，同一版本内区间首尾相接、不得有缺口或重叠';
COMMENT ON COLUMN tide_segment.version_id IS '所属潮高版本';
COMMENT ON COLUMN tide_segment.start_time IS '区间起点（含，Asia/Shanghai）';
COMMENT ON COLUMN tide_segment.end_time IS '区间终点（不含，Asia/Shanghai）';
COMMENT ON COLUMN tide_segment.height IS '该区间潮高（米，相对基准面，可为负）';

CREATE TABLE IF NOT EXISTS berth_application (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id        VARCHAR(64)  NOT NULL,
    vessel_name       VARCHAR(128) NOT NULL,
    draft             DECIMAL(6,2) NOT NULL,
    safety_margin     DECIMAL(4,2) NOT NULL,
    berth_code        VARCHAR(64)  NOT NULL,
    start_time        DATETIME     NOT NULL,
    end_time          DATETIME     NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    rejection_code    VARCHAR(48),
    rejection_reason  VARCHAR(255),
    static_depth      DECIMAL(6,2) NOT NULL,
    tide_version_id   BIGINT,
    tide_version_no   BIGINT,
    min_tide_height   DECIMAL(5,2),
    available_depth   DECIMAL(6,2),
    clearance         DECIMAL(6,2),
    snap_tide_version_no BIGINT,
    snap_min_tide_height DECIMAL(5,2),
    snap_available_depth DECIMAL(6,2),
    request_hash      CHAR(64)     NOT NULL,
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    confirmed_at      DATETIME,
    reviewed_at       DATETIME,
    CONSTRAINT uk_application_request_id UNIQUE (request_id)
);
COMMENT ON TABLE berth_application IS '靠泊申请及分配/复核决策记录';
COMMENT ON COLUMN berth_application.request_id IS '客户端幂等请求ID，唯一';
COMMENT ON COLUMN berth_application.vessel_name IS '船舶名称（合成场景使用合成船名）';
COMMENT ON COLUMN berth_application.draft IS '船舶吃水（米，必须为正）';
COMMENT ON COLUMN berth_application.safety_margin IS '安全余量（米，非负）';
COMMENT ON COLUMN berth_application.berth_code IS '申请泊位编码';
COMMENT ON COLUMN berth_application.start_time IS '靠泊开始时间（含，Asia/Shanghai）';
COMMENT ON COLUMN berth_application.end_time IS '靠泊结束时间（不含，必须晚于开始时间）';
COMMENT ON COLUMN berth_application.status IS 'CONFIRMED=已确认占泊；PENDING_REVIEW=潮高修订待复核（不占泊）；REJECTED=已拒绝（不占泊）';
COMMENT ON COLUMN berth_application.rejection_code IS '机器可区分拒绝原因：DEPTH_INSUFFICIENT=深度不足；BERTH_OCCUPIED=泊位时段重叠占用；TIDE_REVISION_DEPTH_INSUFFICIENT=潮高修订后深度不足；MANUAL_REJECT=人工复核拒绝';
COMMENT ON COLUMN berth_application.rejection_reason IS '拒绝原因人类可读说明；未拒绝时为NULL';
COMMENT ON COLUMN berth_application.static_depth IS '校核时泊位静态水深快照（米）';
COMMENT ON COLUMN berth_application.tide_version_id IS '本次校核所用已提交潮高版本';
COMMENT ON COLUMN berth_application.tide_version_no IS '本次校核所用潮高版本号';
COMMENT ON COLUMN berth_application.min_tide_height IS '整个申请区间内最低有效潮高（米）';
COMMENT ON COLUMN berth_application.available_depth IS '可用深度=静态水深+最低潮高（米）';
COMMENT ON COLUMN berth_application.clearance IS '余量=可用深度-(吃水+安全余量)（米，负值表示不足）';
COMMENT ON COLUMN berth_application.snap_tide_version_no IS '最近一次确认决策时的潮高版本号快照，修订后不自动改写';
COMMENT ON COLUMN berth_application.snap_min_tide_height IS '最近一次确认决策时的最低潮高快照（米）';
COMMENT ON COLUMN berth_application.snap_available_depth IS '最近一次确认决策时的可用深度快照（米）';
COMMENT ON COLUMN berth_application.request_hash IS '同参幂等校验用的请求参数哈希';
COMMENT ON COLUMN berth_application.confirmed_at IS '最近一次确认占泊时间（Asia/Shanghai），未确认或待复核时保留上一次值';
COMMENT ON COLUMN berth_application.reviewed_at IS '最近一次人工复核时间（Asia/Shanghai），未复核为NULL';

CREATE TABLE IF NOT EXISTS review_record (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id  VARCHAR(64) NOT NULL,
    decision    VARCHAR(16) NOT NULL,
    request_hash CHAR(64)   NOT NULL,
    reviewed_at DATETIME    NOT NULL,
    CONSTRAINT uk_review_request_id UNIQUE (request_id)
);
COMMENT ON TABLE review_record IS '复核幂等记录，每个靠泊申请至多一条首次复核结论';
COMMENT ON COLUMN review_record.request_id IS '关联靠泊申请的requestId';
COMMENT ON COLUMN review_record.decision IS 'APPROVE=复核通过，REJECT=复核拒绝';
COMMENT ON COLUMN review_record.request_hash IS '复核请求参数哈希，异参重放返回409';
COMMENT ON COLUMN review_record.reviewed_at IS '复核时间（Asia/Shanghai）';

CREATE TABLE IF NOT EXISTS alloc_lock (
    id   BIGINT PRIMARY KEY,
    name VARCHAR(32) NOT NULL
);
COMMENT ON TABLE alloc_lock IS '全局分配锁表，靠泊确认与潮高发布事务串行化，确保只能按已提交潮高版本校核';
