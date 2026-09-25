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
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入计算值 a×读数+b，最多 12 位小数',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入值判定且包含上下限端点',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行',
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

-- 联合放行批次：跨仪器 2～20 条测量的一致放行；记录创建后不可变。
CREATE TABLE IF NOT EXISTS joint_release_batch (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '联合放行批次自增主键',
    joint_batch_key VARCHAR(64) NOT NULL COMMENT '业务联合批次键，全局唯一，作为幂等键',
    request_id VARCHAR(96) NOT NULL COMMENT '请求幂等键 requestId；同键同参重放返回首次响应快照',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行提交时刻（UTC）',
    -- 成功响应快照：固化首次请求返回顺序；重放时以集合比对，换序视为同参
    snapshot_keys TEXT NOT NULL COMMENT '成功时固化的测量键列表（首次请求顺序的 JSON 数组文本）',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间（UTC），与放行时刻一致',
    CONSTRAINT uk_joint_batch_key UNIQUE (joint_batch_key),
    CONSTRAINT uk_joint_batch_request UNIQUE (request_id)
) COMMENT='跨仪器联合放行批次（不可变）';

-- 联合放行明细：每条测量一行，固化证书与计算值快照；记录创建后不可变。
CREATE TABLE IF NOT EXISTS joint_release_item (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '联合放行明细自增主键',
    joint_batch_id BIGINT NOT NULL COMMENT '关联 joint_release_batch.id',
    joint_batch_key VARCHAR(64) NOT NULL COMMENT '冗余联合批次键，便于按批次查询',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    measurement_key VARCHAR(64) NOT NULL COMMENT '测量业务键快照',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID 快照（允许跨仪器混合）',
    certificate_id BIGINT NOT NULL COMMENT '本次放行使用的校准证书 ID 快照',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '放行时固化的未舍入计算值 a×读数+b 快照',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时刻（UTC），整批一致',
    KEY idx_joint_item_batch (joint_batch_id),
    KEY idx_joint_item_key (joint_batch_key),
    KEY idx_joint_item_measurement (measurement_id)
) COMMENT='联合放行批次测量明细（不可变快照）';
