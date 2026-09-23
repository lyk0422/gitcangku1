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

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果。
-- 同一 measurement_key 构成修订链：原始提交 version=0，每次驳回后的后继修订 version+1。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量记录 ID，自增',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键，同一修订链共享，与 version 共同唯一',
    version INT NOT NULL COMMENT '版本号：原始提交为 0，每次后继修订 +1',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人',
    certificate_id BIGINT NOT NULL COMMENT '提交时匹配到的校准证书 ID',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入计算值 a×读数+b，最多 12 位小数',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入值判定且包含上下限端点',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行，REJECTED=复核驳回',
    note VARCHAR(512) NULL COMMENT '测量说明；原始提交为 NULL，仅修订时可填写或修改',
    predecessor_id BIGINT NULL COMMENT '前驱测量记录 ID（修订链）；原始提交为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key_version UNIQUE (measurement_key, version),
    CONSTRAINT uk_measurement_predecessor UNIQUE (predecessor_id),
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
    KEY idx_release_measurement (measurement_id),
    KEY idx_release_batch (batch_id)
) COMMENT='放行历史';

-- 放行批次：首次批量放行与复核后的重新放行各产生一个批次。
CREATE TABLE IF NOT EXISTS release_batch (
    batch_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '放行批次 ID（UUID）',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    status VARCHAR(16) NOT NULL COMMENT '状态：RELEASED=生效中，REVIEW_REQUIRED=复核驳回待处理，SUPERSEDED=已被重新放行取代（终态不可变）',
    request_id VARCHAR(64) NULL COMMENT '重新放行幂等键，全局唯一；首次批量放行为 NULL',
    request_fingerprint VARCHAR(64) NULL COMMENT '重新放行请求参数规范化指纹（SHA-256），用于同键异参检测；首次批量放行为 NULL',
    source_batch_id VARCHAR(64) NULL COMMENT '重新放行来源批次 ID；首次批量放行为 NULL',
    CONSTRAINT uk_release_batch_request UNIQUE (request_id),
    KEY idx_release_batch_source (source_batch_id)
) COMMENT='放行批次';

-- 复核记录：复核员对一个放行批次的驳回操作，review_key 全局唯一作为幂等键。
CREATE TABLE IF NOT EXISTS review_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '复核记录 ID，自增',
    review_key VARCHAR(64) NOT NULL COMMENT '复核幂等键，全局唯一',
    batch_id VARCHAR(64) NOT NULL COMMENT '被复核的放行批次 ID',
    reviewer VARCHAR(64) NOT NULL COMMENT '复核人（X-Actor-Id），不得为原放行人',
    request_fingerprint VARCHAR(64) NOT NULL COMMENT '复核请求参数规范化指纹（SHA-256），用于同键异参检测',
    reviewed_at DATETIME(6) NOT NULL COMMENT '复核时间（UTC）',
    CONSTRAINT uk_review_key UNIQUE (review_key),
    KEY idx_review_batch (batch_id)
) COMMENT='放行批次复核记录';

-- 批次版本快照：复核成功时冻结整批每个位置的测量与版本，作为重新放行精确映射的基准。
CREATE TABLE IF NOT EXISTS batch_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '快照记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID',
    review_id BIGINT NOT NULL COMMENT '产生该快照的复核记录 ID',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    measurement_version INT NOT NULL COMMENT '冻结的测量版本号',
    rejected BOOLEAN NOT NULL COMMENT '该位置是否被驳回；TRUE 表示被驳回',
    reason VARCHAR(512) NULL COMMENT '驳回原因；未驳回为 NULL',
    KEY idx_snapshot_batch (batch_id)
) COMMENT='复核时冻结的批次版本快照';

-- 重新放行逐项血缘：新批次每个位置对应来源批次的哪个测量记录。
CREATE TABLE IF NOT EXISTS release_lineage (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '血缘记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '重新放行生成的新批次 ID',
    measurement_id BIGINT NOT NULL COMMENT '新批次中使用的测量记录 ID',
    source_batch_id VARCHAR(64) NOT NULL COMMENT '来源批次 ID',
    source_measurement_id BIGINT NOT NULL COMMENT '来源批次中对应位置的测量记录 ID',
    KEY idx_lineage_batch (batch_id)
) COMMENT='重新放行逐项血缘';
