-- 测量校准与结果放行 数据库结构
-- 所有时间字段均为 UTC 墙钟时间（DATETIME(6)，微秒精度），由应用层以 UTC 写入与读取。

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

-- 仪器不确定度阈值：版本化、只增不改；发布修订即新增版本，旧预算与旧放行快照继续引用旧版本。
CREATE TABLE IF NOT EXISTS uncertainty_threshold (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '阈值版本行 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    version INT NOT NULL COMMENT '仪器内阈值版本号，从 1 起逐版递增，不回用',
    max_uncertainty DECIMAL(38,6) NOT NULL COMMENT '合成不确定度上限（含端点），非负，最多 6 位小数',
    created_by VARCHAR(64) NOT NULL COMMENT '发布人（X-Actor-Id）',
    created_at DATETIME(6) NOT NULL COMMENT '发布时间（UTC）',
    CONSTRAINT uk_threshold_instrument_version UNIQUE (instrument_id, version)
) COMMENT='仪器不确定度阈值（版本化、不可变）';

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果。
-- version 为测量修订号（分量预算每次完整替换后加 1），用于乐观裁决；过期版本返回 410。
-- current_budget_id 指向当前生效的不确定度预算版本；从未登记预算时为 NULL（NULL 语义即空预算，合成不确定度视为 0）。
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
    version BIGINT NOT NULL DEFAULT 1 COMMENT '测量修订版本号，从 1 起；每次预算修订加 1',
    current_budget_id BIGINT NULL COMMENT '当前不确定度预算版本 ID；未登记预算为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id),
    KEY idx_measurement_budget (current_budget_id)
) COMMENT='测量记录';

-- 不确定度预算版本：只增不改。分量批量修改以“完整替换”方式产生新版本，旧版本永久保留。
-- 登记时固化当时的仪器阈值版本（id/version/值）与计算口径；阈值后续修订不改写本行。
CREATE TABLE IF NOT EXISTS uncertainty_budget (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '预算版本 ID，自增',
    measurement_id BIGINT NOT NULL COMMENT '所属测量记录 ID',
    budget_version INT NOT NULL COMMENT '测量内预算版本号，从 1 起逐版递增',
    calc_method VARCHAR(32) NOT NULL COMMENT '合成口径标识：RSS_V1=无相关分量平方和开根 sqrt(Σu_i²)',
    combined_uncertainty DECIMAL(38,12) NOT NULL COMMENT '未舍入合成不确定度（RSS），最多 12 位小数；展示与比较基准',
    threshold_id BIGINT NOT NULL COMMENT '固化的阈值版本行 ID',
    threshold_version INT NOT NULL COMMENT '固化的仪器阈值版本号',
    threshold_value DECIMAL(38,6) NOT NULL COMMENT '固化的阈值上限值快照，非负，最多 6 位小数',
    created_by VARCHAR(64) NOT NULL COMMENT '预算登记/修订人（X-Actor-Id）',
    created_at DATETIME(6) NOT NULL COMMENT '预算版本创建时间（UTC）',
    CONSTRAINT uk_budget_measurement_version UNIQUE (measurement_id, budget_version),
    KEY idx_budget_threshold (threshold_id)
) COMMENT='不确定度预算版本（不可变）';

-- 不确定度分量：隶属于某一不可变预算版本；分量名称在同一预算内唯一，值非负。
CREATE TABLE IF NOT EXISTS uncertainty_component (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '分量 ID，自增',
    budget_id BIGINT NOT NULL COMMENT '所属预算版本 ID',
    component_name VARCHAR(128) NOT NULL COMMENT '分量名称，预算内唯一，区分大小写，保留原名',
    component_value DECIMAL(38,6) NOT NULL COMMENT '分量标准不确定度，非负，最多 6 位小数',
    ordinal INT NOT NULL COMMENT '提交序号，从 0 起；明细与历史按 (ordinal, id) 稳定排序',
    created_at DATETIME(6) NOT NULL COMMENT '分量固化时间（UTC，与预算版本一致）',
    CONSTRAINT uk_component_budget_name UNIQUE (budget_id, component_name),
    KEY idx_component_budget (budget_id, ordinal)
) COMMENT='不确定度分量（不可变）';

-- 写接口幂等键：仅记录成功提交；失败随事务回滚不占键。
-- 指纹为规范化 JSON（操作者、操作类型、规范化集合及全部影响结果字段）的 SHA-256，不做裸分隔符拼接。
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '客户端请求幂等键（X-Request-Id）',
    operator VARCHAR(64) NOT NULL COMMENT '操作者（X-Actor-Id），参与指纹',
    operation_type VARCHAR(32) NOT NULL COMMENT '操作类型：THRESHOLD_PUBLISH / BUDGET_REVISE',
    fingerprint CHAR(64) NOT NULL COMMENT '请求指纹：规范化 JSON 的 SHA-256 十六进制',
    http_status INT NOT NULL COMMENT '首次成功响应的 HTTP 状态码，重放原样返回',
    response_snapshot CLOB NOT NULL COMMENT '首次成功完整响应体 JSON 快照，重放原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '首次成功提交时间（UTC）',
    KEY idx_idempotent_fingerprint (fingerprint)
) COMMENT='写接口幂等记录（仅成功）';

-- 放行历史：证书撤销后保留，不回写为从未放行。
-- 同时固化当次放行读取的预算版本、合成不确定度、计算口径与阈值版本快照；阈值后续修订不改写本行。
-- 无预算测量放行时预算快照列为 NULL（NULL 语义：空预算，合成不确定度视为 0）。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    budget_id BIGINT NULL COMMENT '放行时读取的预算版本 ID；测量无预算时为 NULL',
    calc_method VARCHAR(32) NULL COMMENT '放行时合成口径标识；无预算时为 NULL',
    combined_uncertainty DECIMAL(38,12) NULL COMMENT '放行时未舍入合成不确定度快照；无预算时为 NULL',
    threshold_id BIGINT NULL COMMENT '放行时阈值版本行 ID 快照；无预算时为 NULL',
    threshold_version INT NULL COMMENT '放行时阈值版本号快照；无预算时为 NULL',
    threshold_value DECIMAL(38,6) NULL COMMENT '放行时阈值上限值快照；无预算时为 NULL',
    KEY idx_release_measurement (measurement_id)
) COMMENT='放行历史（含不确定度预算快照）';
