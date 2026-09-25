-- 测量校准、同行复核与结果放行 数据库结构
-- 所有时间字段均为 UTC 墙钟时间（DATETIME(6)），由应用层以 UTC 写入与读取。

-- 仪器级互斥锁表：同一仪器的证书创建通过锁定本表对应行串行化，
-- 保证并发创建重叠区间证书时最多一张成功。
CREATE TABLE IF NOT EXISTS instrument_lock (
    instrument_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '仪器 ID，作为该仪器证书创建的互斥锁',
    created_at DATETIME(6) NOT NULL COMMENT '锁记录创建时间（UTC）'
) COMMENT='仪器级互斥锁表';

-- 校准证书：创建后不可修改，仅可撤销；同一仪器未撤销证书区间不得重叠（相邻合法）。
CREATE TABLE IF NOT EXISTS calibration_certificate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '证书 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '有效期终点（UTC，右开，不含该时刻）',
    coeff_a DECIMAL(38,6) NOT NULL COMMENT '校准系数 a，十进制，最多 6 位小数',
    offset_b DECIMAL(38,6) NOT NULL COMMENT '校准偏移 b，十进制，最多 6 位小数',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销；TRUE 表示已撤销',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（UTC）；未撤销时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    KEY idx_cert_instrument (instrument_id, revoked, valid_from, valid_to)
) COMMENT='校准证书';

-- 测量当前版本指针：每个 measurement_key 一行，修订在该行行锁内串行化。
CREATE TABLE IF NOT EXISTS measurement_head (
    measurement_key VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '业务测量键，指向该测量当前版本',
    current_version INT NOT NULL COMMENT '当前版本号，从 1 开始，修订后递增',
    current_id BIGINT NOT NULL COMMENT '当前版本对应的 measurement 行 ID',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次版本变更时间（UTC）'
) COMMENT='测量当前版本指针';

-- 测量记录：每个版本一行不可变快照；提交时按测量时刻匹配唯一有效证书并固化计算结果。
-- 修订插入新版本并将旧版本置为 SUPERSEDED；旧版本复核仅保留历史，不迁移。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量版本记录 ID，自增',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键，同一测量的各版本共享',
    version INT NOT NULL COMMENT '版本号，从 1 开始，同一 measurement_key 下递增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人',
    certificate_id BIGINT NOT NULL COMMENT '提交/修订时匹配到的校准证书 ID（该版本固化）',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入计算值 a×读数+b，最多 12 位小数',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入值判定且包含上下限端点',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RETURNED=待修订（收到退回），RELEASED=已放行，SUPERSEDED=已被新版本取代',
    created_at DATETIME(6) NOT NULL COMMENT '该版本提交时间（UTC）',
    CONSTRAINT uk_measurement_key_version UNIQUE (measurement_key, version),
    KEY idx_measurement_key (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id)
) COMMENT='测量记录（按版本不可变）';

-- 同行复核记录：写入后不可变，固化被复核版本、证书版本、结论、说明与时刻。
-- valid_slot 仅对 VALID 记录赋值（measurement_key#version#conclusion），
-- 配合唯一约束保证“同一版本最多一条有效 PASS 和一条有效 RETURN”；STALE 记录该列为 NULL，不占名额。
CREATE TABLE IF NOT EXISTS peer_review (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '复核记录 ID，自增',
    review_key VARCHAR(64) NOT NULL COMMENT '复核业务键，全局唯一（复核幂等键）',
    measurement_key VARCHAR(64) NOT NULL COMMENT '被复核测量的业务键',
    version INT NOT NULL COMMENT '被复核的测量版本号（固化，修订后不迁移）',
    measurement_id BIGINT NOT NULL COMMENT '被复核的测量版本记录 ID',
    certificate_id BIGINT NOT NULL COMMENT '复核时测量关联的证书 ID（证书版本固化）',
    conclusion VARCHAR(16) NOT NULL COMMENT '复核结论：PASS=通过，RETURN=退回修订',
    state VARCHAR(16) NOT NULL COMMENT '记录状态：VALID=有效，STALE=提交时版本已变化而失效，不得用于放行',
    reviewer VARCHAR(64) NOT NULL COMMENT '复核人，必须不同于该版本提交人',
    comment VARCHAR(1000) NOT NULL COMMENT '复核说明',
    valid_slot VARCHAR(160) NULL COMMENT '有效名额键：measurement_key#version#conclusion，VALID 唯一，STALE 为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '复核提交时间（UTC）',
    CONSTRAINT uk_review_key UNIQUE (review_key),
    CONSTRAINT uk_review_valid_slot UNIQUE (valid_slot),
    KEY idx_review_measurement (measurement_key, version),
    KEY idx_review_state (state)
) COMMENT='同行复核记录（不可变）';

-- 复核幂等请求记录：requestId 同键同参重放首次结果，异参 409；业务失败随事务回滚不占键。
CREATE TABLE IF NOT EXISTS review_request (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '复核请求幂等键 requestId',
    request_hash VARCHAR(64) NOT NULL COMMENT '首次请求规范化参数的 SHA-256，异参重放据此判定 409',
    http_status INT NULL COMMENT '首次请求的 HTTP 状态码（201 有效 / 410 失效）；占用未决时为 NULL',
    review_id BIGINT NULL COMMENT '首次请求产生的复核记录 ID',
    created_at DATETIME(6) NOT NULL COMMENT '首次请求占用时间（UTC）'
) COMMENT='复核幂等请求记录';

-- 放行历史：证书撤销后保留，不回写为从未放行。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量版本记录 ID',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_release_measurement (measurement_id)
) COMMENT='放行历史';
