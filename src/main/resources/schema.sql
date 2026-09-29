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
-- 同一 measurement_key 可经多次修订形成版本链：(measurement_key, version) 唯一；
-- 旧版本置为 SUPERSEDED，不可回写，放行证据固化在快照表。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量记录 ID，自增',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键；同一业务键修订后多行，以 version 区分',
    version INT NOT NULL COMMENT '测量版本号，同一 measurement_key 内从 1 递增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人',
    certificate_id BIGINT NOT NULL COMMENT '提交时匹配到的校准证书 ID',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入计算值 a×读数+b，最多 12 位小数',
    passed BOOLEAN NOT NULL COMMENT '是否合格（偏差判定）；基于未舍入值判定且包含上下限端点',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行，SUPERSEDED=已被新版本过期取代',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key_version UNIQUE (measurement_key, version),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id)
) COMMENT='测量记录（版本化）';

-- 放行历史：证书撤销后保留，不回写为从未放行。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量记录（具体版本行）ID',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_release_measurement (measurement_id)
) COMMENT='放行历史';

-- 不确定度预算版本头：每次“整体替换”追加一个新版本（即使分量为空也保留版本痕迹），
-- 预算版本只增不改；分量明细见 uncertainty_component。
CREATE TABLE IF NOT EXISTS uncertainty_budget (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '预算版本头 ID，自增',
    measurement_id BIGINT NOT NULL COMMENT '所属测量记录（版本行）ID',
    budget_version INT NOT NULL COMMENT '预算版本号，从 1 开始；每次整体替换递增',
    updated_by VARCHAR(64) NOT NULL COMMENT '本次预算修改操作者',
    created_at DATETIME(6) NOT NULL COMMENT '该预算版本写入时间（UTC）',
    CONSTRAINT uk_budget_measurement_version UNIQUE (measurement_id, budget_version)
) COMMENT='不确定度预算版本头（只追加）';

-- 不确定度分量：一条测量（具体版本行）可登记多个非负分量；预算整体替换时随预算版本头新增整组明细，
-- 历史版本只增不改，名称在同一测量同一预算版本内唯一。
CREATE TABLE IF NOT EXISTS uncertainty_component (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '分量 ID，自增',
    measurement_id BIGINT NOT NULL COMMENT '所属测量记录（版本行）ID',
    budget_version INT NOT NULL COMMENT '预算版本号，从 1 开始；每次整体替换递增',
    name VARCHAR(128) NOT NULL COMMENT '分量名称；同一测量同一预算版本内唯一，空值不允许',
    magnitude DECIMAL(38,6) NOT NULL COMMENT '分量大小（非负，含 0），最多 6 位小数',
    created_at DATETIME(6) NOT NULL COMMENT '该预算版本写入时间（UTC）',
    CONSTRAINT uk_component_measurement_version_name UNIQUE (measurement_id, budget_version, name),
    KEY idx_component_measurement_version (measurement_id, budget_version)
) COMMENT='不确定度分量（版本化预算明细，只增不改）';

-- 仪器不确定度阈值版本：同一仪器多版本按 valid_from 排序，
-- 某时刻生效版本为 valid_from <= 时刻的最新未停用版本，相邻版本区间左闭右开。
-- 版本一经创建不改写；阈值修订只追加新版本。
CREATE TABLE IF NOT EXISTS uncertainty_threshold (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '阈值版本 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    version INT NOT NULL COMMENT '阈值版本号，同一仪器内从 1 递增',
    threshold_value DECIMAL(38,6) NOT NULL COMMENT '合成不确定度允许上限（非负，含端点比较），最多 6 位小数',
    valid_from DATETIME(6) NOT NULL COMMENT '生效起点（UTC，左闭）；与下一版本构成 [valid_from, next_valid_from)',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '该阈值版本是否已停用；停用后时刻匹配回退到更早版本',
    revoked_at DATETIME(6) NULL COMMENT '停用时间（UTC）；未停用为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '版本创建时间（UTC）',
    CONSTRAINT uk_threshold_instrument_version UNIQUE (instrument_id, version),
    KEY idx_threshold_instrument_time (instrument_id, valid_from)
) COMMENT='仪器不确定度阈值版本（只追加）';

-- 放行结论快照：放行时刻固化测量结果、不确定度预算、计算口径与阈值版本；
-- 后续阈值修订、预算修改或测量修订均不改写旧快照（旧证据不可回写）。
CREATE TABLE IF NOT EXISTS release_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '快照 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），与 release_record 一致',
    measurement_id BIGINT NOT NULL COMMENT '放行的测量记录（具体版本行）ID',
    measurement_key VARCHAR(64) NOT NULL COMMENT '放行时测量业务键（固化）',
    measurement_version INT NOT NULL COMMENT '放行时测量版本号（固化）',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '放行时未舍入计算值 a×读数+b（固化）',
    passed_bias BOOLEAN NOT NULL COMMENT '放行时偏差判定结果（固化）',
    budget_version INT NOT NULL COMMENT '放行时读取的预算版本号（固化）',
    combined_uncertainty DECIMAL(38,4) NOT NULL COMMENT '放行时合成不确定度，RSS 口径 HALF_UP 保留 4 位小数（固化）',
    formula VARCHAR(48) NOT NULL COMMENT '计算口径标识：RSS_ROOT_SUM_OF_SQUARES',
    threshold_id BIGINT NOT NULL COMMENT '放行时采用的阈值版本行 ID（固化）',
    threshold_version INT NOT NULL COMMENT '放行时阈值版本号（固化）',
    threshold_value DECIMAL(38,6) NOT NULL COMMENT '放行时阈值要求值（固化）',
    uncertainty_ok BOOLEAN NOT NULL COMMENT '放行时合成不确定度是否不超过阈值（固化）',
    decision VARCHAR(16) NOT NULL COMMENT '放行结论：APPROVED',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（固化）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC，微秒精度）',
    KEY idx_snapshot_batch (batch_id),
    KEY idx_snapshot_measurement (measurement_id),
    KEY idx_snapshot_released_at (released_at, id)
) COMMENT='放行结论快照（结果/预算/口径/阈值版本固化）';

-- 放行快照分量明细：按分量名称升序固化放行时读取的一致预算；旧证据不可回写。
CREATE TABLE IF NOT EXISTS release_snapshot_component (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '快照分量 ID，自增',
    snapshot_id BIGINT NOT NULL COMMENT '所属放行快照 ID',
    name VARCHAR(128) NOT NULL COMMENT '分量名称（固化，空值不允许）',
    magnitude DECIMAL(38,6) NOT NULL COMMENT '分量大小（非负，固化）',
    KEY idx_snapshot_component_snapshot (snapshot_id)
) COMMENT='放行快照分量明细（只增不改）';

-- 幂等请求记录：事务开始即插入占坑行（response_json 暂为 NULL），动作失败随事务回滚而删除（失败不占键）；
-- 成功后回填首次完整响应 JSON。同 requestId 同参重放原样返回首次响应，异参返回 409。
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id VARCHAR(128) NOT NULL PRIMARY KEY COMMENT '客户端幂等键（X-Request-Id）',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：UPDATE_BUDGET/REVISE_MEASUREMENT/RELEASE_BUDGET',
    fingerprint CHAR(64) NOT NULL COMMENT '规范化请求指纹（SHA-256 十六进制，不用未转义分隔符拼接）',
    response_json TEXT NULL COMMENT '首次成功的完整响应 JSON 快照；占坑期间为 NULL，成功后回填，重放原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次成功时间（UTC）',
    KEY idx_idempotent_operation (operation)
) COMMENT='幂等请求记录（先占坑后回填，失败不占键）';
