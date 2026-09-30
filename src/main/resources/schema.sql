-- 泊位：静态水深不随潮汐变化，单位米。
CREATE TABLE IF NOT EXISTS berth (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    static_depth_m  DECIMAL(6,2) NOT NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_berth_code UNIQUE (code)
);

-- 潮高版本：每次潮汐表修订发布一个新版本，published_at 为业务时钟时间（Asia/Shanghai）。
CREATE TABLE IF NOT EXISTS tide_version (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_no    INT          NOT NULL,
    published_at  DATETIME(6)  NOT NULL,
    remark        VARCHAR(255),
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_tide_version_no UNIQUE (version_no)
);

-- 潮高区间：左闭右开 [start_time, end_time)，tide_height_m 为该区间潮高，单位米，允许负值。
CREATE TABLE IF NOT EXISTS tide_window (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id     BIGINT       NOT NULL,
    start_time     DATETIME(6)  NOT NULL,
    end_time       DATETIME(6)  NOT NULL,
    tide_height_m  DECIMAL(5,2) NOT NULL,
    CONSTRAINT fk_tide_window_version FOREIGN KEY (version_id)
        REFERENCES tide_version (id) ON DELETE CASCADE,
    CONSTRAINT uk_tide_window_version_start UNIQUE (version_id, start_time)
);

-- 靠泊申请：保存申请参数、决策时校核所用潮高版本与深度快照；CONFIRMED 才占用泊位区间。
CREATE TABLE IF NOT EXISTS berth_allocation (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id                VARCHAR(64)  NOT NULL,
    vessel_name               VARCHAR(128) NOT NULL,
    draft_m                   DECIMAL(6,2) NOT NULL,
    safety_margin_m           DECIMAL(5,2) NOT NULL,
    berth_id                  BIGINT       NOT NULL,
    start_time                DATETIME(6)  NOT NULL,
    end_time                  DATETIME(6)  NOT NULL,
    -- 状态：CONFIRMED 已确认并占用；PENDING_REVIEW 待复核（已释放占用）；REJECTED 已拒绝。
    status                    VARCHAR(24)  NOT NULL,
    -- 原决策快照所用潮高版本；申请被拒或待复核时仍记录校核时所用版本。
    tide_version_id           BIGINT,
    min_tide_height_m         DECIMAL(5,2),
    static_depth_m            DECIMAL(6,2),
    available_depth_m         DECIMAL(6,2),
    required_depth_m          DECIMAL(6,2),
    -- 富余余量 = 可用深度 -（吃水 + 安全余量），单位米，负值表示深度不足。
    clearance_m               DECIMAL(6,2),
    -- 最近一次修订/复核所依据的版本与校核结果，未发生修订时为空。
    review_tide_version_id    BIGINT,
    review_min_tide_height_m  DECIMAL(5,2),
    review_available_depth_m  DECIMAL(6,2),
    review_clearance_m        DECIMAL(6,2),
    -- 拒绝/挂起原因：TIDE_GAP 潮高缺口、DEPTH_INSUFFICIENT 深度不足、BERTH_CONFLICT 窗口冲突等。
    reject_reason             VARCHAR(48),
    review_count              INT          NOT NULL DEFAULT 0,
    decided_at                DATETIME(6),
    created_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_allocation_request_id UNIQUE (request_id),
    CONSTRAINT fk_allocation_berth FOREIGN KEY (berth_id) REFERENCES berth (id),
    CONSTRAINT fk_allocation_tide_version FOREIGN KEY (tide_version_id) REFERENCES tide_version (id)
);

-- 同泊位已确认区间的重叠检测走该组合索引（应用层以 status='CONFIRMED' 过滤）。
CREATE INDEX IF NOT EXISTS idx_allocation_berth_interval
    ON berth_allocation (berth_id, status, start_time, end_time);

-- 全局事务串行锁：申请/发布/复核在该行上排他排队，保证只按已提交版本校核。
CREATE TABLE IF NOT EXISTS app_lock (
    id   BIGINT PRIMARY KEY,
    name VARCHAR(32) NOT NULL
);

MERGE INTO app_lock (id, name) KEY (id) VALUES (1, 'GLOBAL');

COMMENT ON COLUMN berth.static_depth_m IS '泊位静态水深，单位米';
COMMENT ON COLUMN tide_window.tide_height_m IS '区间潮高，单位米，相对基准面，允许负值';
COMMENT ON COLUMN berth_allocation.draft_m IS '船舶吃水，单位米';
COMMENT ON COLUMN berth_allocation.safety_margin_m IS '要求的安全余量，单位米';
COMMENT ON COLUMN berth_allocation.status IS 'CONFIRMED 已确认占用；PENDING_REVIEW 待复核并已释放；REJECTED 已拒绝';
COMMENT ON COLUMN berth_allocation.clearance_m IS '富余余量=可用深度-吃水-安全余量，单位米，负值表示不足';
COMMENT ON COLUMN berth_allocation.reject_reason IS '拒绝或挂起原因代码';
