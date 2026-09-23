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
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行，BLOCKED=失效冻结未审核，REVIEW_REQUIRED=失效冻结待复审',
    standard_version_id BIGINT NULL COMMENT '提交时绑定的当时有效标准器版本记录 ID；未绑定为 NULL',
    impact_version VARCHAR(64) NULL COMMENT '失效激活生成的影响版本号；未受影响为 NULL',
    impact_path VARCHAR(1024) NULL COMMENT '冻结的到失效根最短血缘路径（standardId 以 > 连接）；未受影响为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id),
    KEY idx_measurement_standard (standard_version_id)
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

-- 标准器版本血缘：每个版本至多一个上级版本；血缘必须无环，子级窗口不得超出父级窗口。
CREATE TABLE IF NOT EXISTS standard_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '标准器版本记录 ID，自增',
    standard_id VARCHAR(64) NOT NULL COMMENT '标准器版本业务键，全局唯一',
    parent_standard_id VARCHAR(64) NULL COMMENT '上级（校准方）标准器版本业务键；无上级为 NULL',
    valid_from DATETIME(6) NOT NULL COMMENT '有效窗口起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '有效窗口终点（UTC，右开，不含该时刻）',
    certificate_no VARCHAR(128) NOT NULL COMMENT '上级出具的校准证书号',
    status VARCHAR(16) NOT NULL COMMENT '状态：VALID=有效，INVALID=已失效',
    version INT NOT NULL COMMENT '版本号，失效等状态变化时递增，用于失效单 expectedVersion 校验',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_standard_id UNIQUE (standard_id),
    KEY idx_standard_parent (parent_standard_id)
) COMMENT='标准器版本血缘';

-- 血缘全局互斥锁表：血缘创建、绑定标准器的测量提交与失效激活通过锁定
-- 本表 GLOBAL 行串行化，保证并发时按事务提交顺序生效。
CREATE TABLE IF NOT EXISTS lineage_lock (
    lock_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '锁标识，固定为 GLOBAL',
    created_at DATETIME(6) NOT NULL COMMENT '锁记录创建时间（UTC）'
) COMMENT='标准器血缘全局互斥锁表';

-- 标准器失效单：创建时固化首次闭包快照；两名不同质量人员确认后激活，激活时重算闭包，漂移整单 409。
CREATE TABLE IF NOT EXISTS invalidation_order (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '失效单 ID，自增',
    invalidation_key VARCHAR(64) NOT NULL COMMENT '失效单业务键，全局唯一',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键，全局唯一；同参重放返回首次闭包快照，异参 409，失败不占键',
    root_standard_id VARCHAR(64) NOT NULL COMMENT '失效根标准器版本业务键',
    invalid_from DATETIME(6) NOT NULL COMMENT '失效起始时刻（UTC），该时刻及以后受影响',
    expected_version INT NOT NULL COMMENT '创建时根标准器版本号；激活时不一致整单 409',
    reason VARCHAR(512) NOT NULL COMMENT '失效原因',
    created_by VARCHAR(64) NOT NULL COMMENT '创建人（质量负责人，X-Actor-Id）',
    status VARCHAR(24) NOT NULL COMMENT '状态：PENDING_CONFIRMATION=待双人确认，ACTIVATED=已激活',
    impact_version VARCHAR(64) NULL COMMENT '激活后生成的单一影响版本号；未激活为 NULL',
    closure_snapshot VARCHAR(8192) NOT NULL COMMENT '首次闭包快照（规范串），用于同参重放与激活漂移检测',
    request_params VARCHAR(1024) NOT NULL COMMENT '首次请求参数规范串，用于 requestId 同参判断',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    activated_at DATETIME(6) NULL COMMENT '激活时间（UTC）；未激活为 NULL',
    CONSTRAINT uk_invalidation_key UNIQUE (invalidation_key),
    CONSTRAINT uk_invalidation_request UNIQUE (request_id)
) COMMENT='标准器失效单';

-- 失效单双人确认记录：同一失效单同一确认人仅可确认一次。
CREATE TABLE IF NOT EXISTS invalidation_confirmation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '确认记录 ID，自增',
    invalidation_key VARCHAR(64) NOT NULL COMMENT '失效单业务键',
    confirmed_by VARCHAR(64) NOT NULL COMMENT '确认人（质量人员，须两人不同且不同于创建人）',
    confirmed_at DATETIME(6) NOT NULL COMMENT '确认时间（UTC）',
    CONSTRAINT uk_confirmation UNIQUE (invalidation_key, confirmed_by)
) COMMENT='失效单双人确认记录';

-- 失效影响明细：激活时对每条已放行结果冻结到失效根的最短血缘路径，只增不改，可按 impactVersion 重现。
CREATE TABLE IF NOT EXISTS impact_item (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '影响明细 ID，自增',
    impact_version VARCHAR(64) NOT NULL COMMENT '影响版本号，同一失效单激活共享',
    measurement_id BIGINT NOT NULL COMMENT '受影响测量记录 ID',
    measurement_key VARCHAR(64) NOT NULL COMMENT '受影响测量键',
    standard_id VARCHAR(64) NOT NULL COMMENT '结果绑定的标准器版本业务键',
    path VARCHAR(1024) NOT NULL COMMENT '冻结的到失效根最短血缘路径（standardId 以 > 连接，等长取字典序）',
    previous_status VARCHAR(16) NOT NULL COMMENT '冻结前状态：RELEASED',
    created_at DATETIME(6) NOT NULL COMMENT '冻结时间（UTC）',
    KEY idx_impact_version (impact_version),
    KEY idx_impact_measurement (measurement_id)
) COMMENT='失效影响明细（已放行结果冻结）';
