-- 测量校准与结果放行 数据库结构
-- 所有时间字段均为 UTC 墙钟时间（DATETIME(6)），由应用层以 UTC 写入与读取。

-- 仪器级互斥锁表：同一仪器的证书创建、分辨率配置变更通过锁定本表对应行串行化，
-- 保证并发创建重叠区间证书时最多一张成功、并发配置变更按版本顺序生效。
CREATE TABLE IF NOT EXISTS instrument_lock (
    instrument_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '仪器 ID，作为该仪器证书创建与配置变更的互斥锁',
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

-- 仪器分辨率格点配置：每台仪器一行当前配置；版本从 1 起，每次成功更新 +1。
-- 更新必须携带 expectedVersion 乐观版本；不回算已冻结的旧测量。
CREATE TABLE IF NOT EXISTS resolution_config (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '配置记录 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    step DECIMAL(38,6) NOT NULL COMMENT '分辨率步长 s，必须为正，十进制，最多 6 位小数',
    origin DECIMAL(38,6) NOT NULL COMMENT '格点原点 o，可为负，十进制，最多 6 位小数',
    version INT NOT NULL COMMENT '配置版本，从 1 起，每次成功更新 +1',
    updated_by VARCHAR(64) NOT NULL COMMENT '最近配置人（X-Actor-Id）',
    created_at DATETIME(6) NOT NULL COMMENT '首次配置时间（UTC）',
    updated_at DATETIME(6) NOT NULL COMMENT '最近更新时间（UTC）',
    CONSTRAINT uk_resolution_instrument UNIQUE (instrument_id)
) COMMENT='仪器分辨率格点配置';

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果；
-- 已配置分辨率的仪器同时冻结配置版本、格点整数 k 与量化值。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量记录 ID，自增',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键，全局唯一，作为幂等键',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人',
    certificate_id BIGINT NOT NULL COMMENT '提交时匹配到的校准证书 ID',
    computed_value DECIMAL(38,12) NULL COMMENT '未舍入精确计算值 a×读数+b，最多 12 位小数；越界（超精度）时为 NULL，测量进入待审',
    resolution_version INT NULL COMMENT '提交时冻结的分辨率配置版本；未配置仪器或越界时为 NULL',
    grid_k DECIMAL(38,0) NULL COMMENT '冻结的格点整数 k=(x-o)/s 最近整数（半格取偶）；未量化时为 NULL',
    quantized_value DECIMAL(38,6) NULL COMMENT '冻结的量化值 o+k×s，最多 6 位小数；未量化或越界时为 NULL',
    passed BOOLEAN NOT NULL COMMENT '是否合格；已配置仪器需精确值与量化值均落在闭区间，未配置仪器仅看精确值；越界时为 FALSE',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行（含越界待审），RELEASED=已放行',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id)
) COMMENT='测量记录';

-- 放行历史：证书撤销后保留，不回写为从未放行。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_release_measurement (measurement_id)
) COMMENT='放行历史';

-- 幂等去重记录：新增写入按 requestId 去重，与业务写入同一事务原子提交；
-- 失败回滚不占键；身份指纹为结构化 JSON（操作/操作者/资源/全部参数/期望版本），避免分隔符碰撞。
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '幂等请求 ID（requestId），全局唯一',
    fingerprint VARCHAR(1024) NOT NULL COMMENT '请求身份指纹：操作/操作者/资源/全部参数/期望版本的结构化 JSON',
    response_status INT NOT NULL COMMENT '首次成功响应的 HTTP 状态码',
    response_body CLOB NOT NULL COMMENT '首次成功响应的完整 JSON 报文，用于同键同参重放',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间（UTC）'
) COMMENT='幂等去重记录';
