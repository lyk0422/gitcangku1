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
    standard_version_id BIGINT NULL COMMENT '提交时绑定的有效标准器版本 ID；未指定标准器时为 NULL',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入计算值 a×读数+b，最多 12 位小数',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入值判定且包含上下限端点',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行，BLOCKED=失效冻结阻断，REVIEW_REQUIRED=失效后需复审',
    impact_version VARCHAR(64) NULL COMMENT '命中失效冻结的影响版本号；未受影响为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id),
    KEY idx_measurement_standard_version (standard_version_id)
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

-- 标准器：一个标准器可有多个标准器版本，版本之间构成血缘树。
CREATE TABLE IF NOT EXISTS measurement_standard (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '标准器 ID，自增',
    standard_id VARCHAR(64) NOT NULL COMMENT '标准器业务 ID，全局唯一；血缘路径等长时按其字典序择路',
    name VARCHAR(128) NOT NULL COMMENT '标准器名称',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_standard_id UNIQUE (standard_id)
) COMMENT='标准器';

-- 标准器版本：每个版本可由一个上级标准器版本校准（parent_version_id 为 NULL 表示根）；
-- 血缘必须无环，子级有效窗口不得超出父级窗口。
CREATE TABLE IF NOT EXISTS standard_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '标准器版本 ID，自增',
    version_key VARCHAR(64) NOT NULL COMMENT '版本业务键，全局唯一',
    standard_id VARCHAR(64) NOT NULL COMMENT '所属标准器业务 ID',
    parent_version_id BIGINT NULL COMMENT '上级标准器版本 ID；NULL 表示血缘根版本',
    valid_from DATETIME(6) NOT NULL COMMENT '版本有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '版本有效期终点（UTC，右开，不含该时刻）',
    certificate_no VARCHAR(128) NOT NULL COMMENT '校准证书号',
    status VARCHAR(16) NOT NULL COMMENT '状态：VALID=有效，INVALID=已被失效单冻结',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_version_key UNIQUE (version_key),
    KEY idx_version_parent (parent_version_id),
    KEY idx_version_standard (standard_id),
    KEY idx_version_window (valid_from, valid_to)
) COMMENT='标准器版本（血缘节点）';

-- 领域状态单行表：版本号随血缘、窗口、标准器版本或结果状态变化而递增，
-- 失效单创建与激活时通过 expectedVersion 乐观校验；激活在本行行锁内串行化，按提交顺序生效。
CREATE TABLE IF NOT EXISTS domain_state (
    id BIGINT NOT NULL PRIMARY KEY COMMENT '固定为 1 的单行',
    version BIGINT NOT NULL COMMENT '领域版本号，每次领域状态变化递增'
) COMMENT='领域状态版本';

-- 失效单：质量负责人指定失效根版本、失效起始 UTC 时刻、期望领域版本与原因。
CREATE TABLE IF NOT EXISTS invalidation_order (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '失效单 ID，自增',
    invalidation_key VARCHAR(64) NOT NULL COMMENT '失效单业务键，全局唯一',
    request_id VARCHAR(64) NOT NULL COMMENT '创建请求的幂等键（X-Request-Id）',
    root_version_id BIGINT NOT NULL COMMENT '失效根标准器版本 ID',
    effective_from DATETIME(6) NOT NULL COMMENT '失效起始时刻（UTC，含该时刻）',
    expected_version BIGINT NOT NULL COMMENT '创建时客户端期望的领域版本',
    reason VARCHAR(512) NOT NULL COMMENT '失效原因',
    status VARCHAR(16) NOT NULL COMMENT '状态：CREATED=待双人确认，ACTIVATED=已激活冻结',
    impact_version VARCHAR(64) NULL COMMENT '激活成功后生成的唯一影响版本号；未激活为 NULL',
    snapshot_version BIGINT NOT NULL COMMENT '创建闭包快照时的领域版本',
    created_by VARCHAR(64) NOT NULL COMMENT '创建人（X-Actor-Id）',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    activated_at DATETIME(6) NULL COMMENT '激活时间（UTC）；未激活为 NULL',
    CONSTRAINT uk_invalidation_key UNIQUE (invalidation_key),
    CONSTRAINT uk_invalidation_request UNIQUE (request_id),
    KEY idx_invalidation_root (root_version_id)
) COMMENT='标准器失效单';

-- 失效单确认：必须由两名不同质量人员确认，创建人可作为其中之一但两人不得相同。
CREATE TABLE IF NOT EXISTS invalidation_confirmation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '确认记录 ID，自增',
    invalidation_id BIGINT NOT NULL COMMENT '失效单 ID',
    confirmed_by VARCHAR(64) NOT NULL COMMENT '确认人（X-Actor-Id）',
    confirmed_at DATETIME(6) NOT NULL COMMENT '确认时间（UTC）',
    CONSTRAINT uk_confirmation UNIQUE (invalidation_id, confirmed_by),
    KEY idx_confirmation_order (invalidation_id)
) COMMENT='失效单双人确认';

-- 失效单创建时的闭包快照：预览式完整闭包，稳定排序，激活时在同一事务内重算并与之比对；
-- 任一血缘、窗口、标准器版本或结果状态变化即整单 409，不允许只冻结部分结果。
CREATE TABLE IF NOT EXISTS invalidation_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '快照行 ID，自增',
    invalidation_id BIGINT NOT NULL COMMENT '失效单 ID',
    item_type VARCHAR(16) NOT NULL COMMENT '快照项类型：STANDARD=标准器版本，MEASUREMENT=测量记录',
    ref_id BIGINT NOT NULL COMMENT '引用 ID（标准器版本 ID 或测量记录 ID）',
    ref_status VARCHAR(32) NOT NULL COMMENT '快照时状态：VALID/INVALID 或 PENDING/RELEASED/BLOCKED/REVIEW_REQUIRED',
    ordinal INT NOT NULL COMMENT '闭包内稳定排序序号，从 0 开始',
    CONSTRAINT uk_snapshot_item UNIQUE (invalidation_id, item_type, ref_id),
    KEY idx_snapshot_order (invalidation_id, ordinal)
) COMMENT='失效影响闭包快照';

-- 冻结血缘路径：激活后为每条受影响测量结果记录其到失效根的最短血缘路径，
-- 路径等长时按 standardId 字典序选择；路径内容为版本 ID 序列（含测量绑定版本，不含根重复）。
CREATE TABLE IF NOT EXISTS impact_path (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '路径行 ID，自增',
    impact_version VARCHAR(64) NOT NULL COMMENT '所属影响版本号（与失效单一一对应）',
    measurement_id BIGINT NOT NULL COMMENT '受影响测量结果 ID',
    version_id BIGINT NOT NULL COMMENT '路径上的标准器版本 ID',
    depth INT NOT NULL COMMENT '该版本在路径上的深度：0=测量直接绑定版本，依次向失效根递增',
    CONSTRAINT uk_impact_measurement UNIQUE (measurement_id, depth),
    KEY idx_impact_version (impact_version),
    KEY idx_impact_path_version (version_id)
) COMMENT='失效冻结血缘路径';
