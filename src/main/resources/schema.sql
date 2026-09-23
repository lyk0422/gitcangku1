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
-- 原始测量 version=1、revision_of 为 NULL；驳回后继修订另起版本链（从 1 开始）。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量记录 ID，自增',
    measurement_key VARCHAR(96) NOT NULL COMMENT '业务测量键，全局唯一，作为幂等键；修订行为原键#r版本号',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人',
    certificate_id BIGINT NOT NULL COMMENT '提交时匹配到的校准证书 ID；修订保留原证书',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入计算值 a×读数+b，最多 12 位小数',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入值判定且包含上下限端点',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行，REJECTED=复核驳回',
    version INT NOT NULL DEFAULT 1 COMMENT '版本号：原始测量为 1；修订链自 1 重新编号递增',
    revision_of BIGINT NULL COMMENT '前驱测量记录 ID；NULL 表示原始提交；唯一约束保证每个测量只有单一后继修订',
    root_id BIGINT NULL COMMENT '修订链根测量 ID；原始测量指向自身，修订指向最初原始测量',
    note VARCHAR(500) NULL COMMENT '测量说明或修订原因；NULL 表示无说明',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    CONSTRAINT uk_measurement_revision_of UNIQUE (revision_of),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id),
    KEY idx_measurement_root (root_id)
) COMMENT='测量记录';

-- 放行批次：一次批量放行/重新放行生成一个批次；复核驳回后原子置 REVIEW_REQUIRED 并冻结。
CREATE TABLE IF NOT EXISTS release_batch (
    batch_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '放行批次 ID（UUID）',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）；复核人不得与放行人相同',
    status VARCHAR(20) NOT NULL COMMENT '批次状态：RELEASED=已放行对外可用，REVIEW_REQUIRED=复核驳回待重新放行',
    created_at DATETIME(6) NOT NULL COMMENT '批次创建时间（UTC）',
    reviewed_at DATETIME(6) NULL COMMENT '复核驳回时间（UTC）；未复核为 NULL',
    KEY idx_batch_status (status)
) COMMENT='放行批次';

-- 放行历史：证书撤销后保留，不回写为从未放行；重新放行时未驳回位置复用原测量记录。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    position INT NOT NULL COMMENT '批次内位置（从 1 开始，按提交顺序）；重新放行须逐位置精确映射',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID（未驳回位置在新旧批次间复用同一行）',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    CONSTRAINT uk_release_batch_position UNIQUE (batch_id, position),
    KEY idx_release_measurement (measurement_id),
    KEY idx_release_batch (batch_id)
) COMMENT='放行历史';

-- 放行后复核：每个批次至多一条成功复核；review_key 全局唯一，仅成功时占用幂等键。
CREATE TABLE IF NOT EXISTS batch_review (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '复核记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '被复核的放行批次 ID',
    review_key VARCHAR(64) NOT NULL COMMENT '复核幂等键，全局唯一；同参换序重放返回原结果，异参 409',
    reviewer VARCHAR(64) NOT NULL COMMENT '复核人（X-Actor-Id），不得为该批原放行人',
    snapshot CLOB NOT NULL COMMENT '冻结的整批版本快照（JSON，含全部位置的测量键、版本与驳回标记）',
    reviewed_at DATETIME(6) NOT NULL COMMENT '复核时间（UTC）',
    CONSTRAINT uk_review_batch UNIQUE (batch_id),
    CONSTRAINT uk_review_key UNIQUE (review_key)
) COMMENT='放行后复核记录';

-- 复核驳回明细：仅记录被驳回的位置，逐项固化版本与原因。
CREATE TABLE IF NOT EXISTS review_item (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '复核驳回明细 ID，自增',
    review_id BIGINT NOT NULL COMMENT '所属复核记录 ID',
    position INT NOT NULL COMMENT '批次内位置（从 1 开始）',
    measurement_id BIGINT NOT NULL COMMENT '被驳回测量记录 ID',
    version INT NOT NULL COMMENT '驳回时测量版本；版本已变化则复核失败',
    reason VARCHAR(500) NOT NULL COMMENT '驳回原因（非空）',
    CONSTRAINT uk_review_item_position UNIQUE (review_id, position),
    KEY idx_review_item_measurement (measurement_id)
) COMMENT='复核驳回明细';

-- 重新放行逐项血缘：新批次每个位置记录来源批次、来源测量与实际采用测量（驳回项为最新修订）。
CREATE TABLE IF NOT EXISTS batch_lineage (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '血缘记录 ID，自增',
    new_batch_id VARCHAR(64) NOT NULL COMMENT '重新放行生成的新批次 ID',
    source_batch_id VARCHAR(64) NOT NULL COMMENT '来源（被复核驳回的）旧批次 ID',
    position INT NOT NULL COMMENT '批次内位置（从 1 开始），新旧批次一一对应',
    source_measurement_id BIGINT NOT NULL COMMENT '旧批次该位置的原始测量记录 ID',
    used_measurement_id BIGINT NOT NULL COMMENT '新批次实际采用测量记录 ID（驳回项为修订行，其余为原行）',
    version INT NOT NULL COMMENT '重新放行提交并校验通过的版本快照',
    revised BOOLEAN NOT NULL COMMENT '该位置是否使用了修订；TRUE=驳回项最新修订，FALSE=未驳回原测量',
    CONSTRAINT uk_lineage_new_batch_position UNIQUE (new_batch_id, position),
    KEY idx_lineage_source (source_batch_id, position)
) COMMENT='重新放行逐项血缘';

-- 请求幂等键：仅业务成功时随业务数据同一事务提交；业务失败回滚不占键。
CREATE TABLE IF NOT EXISTS request_idempotency (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '请求幂等键（复核 reviewKey / 重新放行 requestId）',
    kind VARCHAR(24) NOT NULL COMMENT '请求类型：REVIEW=复核，RERELEASE=重新放行',
    fingerprint VARCHAR(200) NOT NULL COMMENT '归一化参数指纹；同键异参返回 409',
    response_json CLOB NOT NULL COMMENT '成功响应快照（JSON），重放原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次成功提交时间（UTC）'
) COMMENT='请求幂等记录';
