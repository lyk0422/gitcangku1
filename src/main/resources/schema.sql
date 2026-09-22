-- 测量校准与结果放行 数据库结构
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

-- 测量记录：同一 measurement_key 可多次修订，revision 从 1 起递增；
-- 每行是一个不可变版本，提交时按测量时刻匹配唯一有效证书并固化计算结果。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量记录 ID，自增',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键，同键多版本共享',
    revision INT NOT NULL DEFAULT 1 COMMENT '修订版本号；首次提交为 1，同键修订递增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID；同键各版本保持不变',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）；同键各版本保持不变',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '原提交人；同键各版本保持不变，仅其本人可修订',
    certificate_id BIGINT NOT NULL COMMENT '本版本提交时匹配到的校准证书 ID',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入计算值 a×读数+b，最多 12 位小数',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入值判定且包含上下限端点',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行',
    revision_reason VARCHAR(255) NULL COMMENT '修订原因（非空）；第 1 版为 NULL',
    request_id VARCHAR(64) NULL COMMENT '修订请求幂等 ID（X-Request-Id 语义，同键唯一）；第 1 版为 NULL',
    revised_by VARCHAR(64) NULL COMMENT '修订提交人（即原提交人，X-Actor-Id）；第 1 版为 NULL',
    revised_at DATETIME(6) NULL COMMENT '修订提交时间（UTC）；第 1 版为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '本版本创建时间（UTC）',
    CONSTRAINT uk_measurement_key_revision UNIQUE (measurement_key, revision),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id)
) COMMENT='测量记录（版本化，每行一个不可变修订版本）';

-- 最新版本指针：每键至多一行，与新版本在同一事务内更新，修订与放行通过本行锁串行化。
CREATE TABLE IF NOT EXISTS measurement_latest (
    measurement_key VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '业务测量键',
    measurement_id BIGINT NOT NULL COMMENT '最新版本测量记录 ID',
    revision INT NOT NULL COMMENT '最新修订号；首次提交为 1',
    updated_at DATETIME(6) NOT NULL COMMENT '指针最近更新时间（UTC）'
) COMMENT='测量最新版本指针（每键唯一）';

-- 修订幂等请求：仅记录成功的修订；同键同 requestId 同参重放首次结果，改参冲突 409。
CREATE TABLE IF NOT EXISTS revision_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '幂等记录 ID，自增',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键',
    request_id VARCHAR(64) NOT NULL COMMENT '修订请求幂等 ID；同键内唯一',
    expected_revision INT NOT NULL COMMENT '请求携带的期望最新版本号',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '请求的修订后原始读数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '请求的修订后合格下限',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '请求的修订后合格上限',
    reason VARCHAR(255) NOT NULL COMMENT '修订原因（非空）',
    measurement_id BIGINT NOT NULL COMMENT '首次成功请求所创建版本的测量记录 ID',
    created_at DATETIME(6) NOT NULL COMMENT '首次成功提交时间（UTC）',
    CONSTRAINT uk_revision_request_key_request UNIQUE (measurement_key, request_id)
) COMMENT='测量修订幂等请求记录';

-- 放行历史：证书撤销或旧版本被修订替代后保留，不回写为从未放行。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量记录（具体版本）ID',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_release_measurement (measurement_id)
) COMMENT='放行历史';
