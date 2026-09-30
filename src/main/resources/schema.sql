-- 测量校准与结果放行 数据库结构
-- 所有时间字段均为 UTC 墙钟时间（DATETIME(6)，微秒精度），由应用层以 UTC 写入与读取。
-- 区间统一为左闭右开 [from, to)。

-- 仪器级互斥锁表：同一仪器的证书创建通过锁定本表对应行串行化，
-- 保证并发创建重叠区间证书时最多一张成功。
CREATE TABLE IF NOT EXISTS instrument_lock (
    instrument_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '仪器 ID，作为该仪器证书创建的互斥锁',
    created_at DATETIME(6) NOT NULL COMMENT '锁记录创建时间（UTC）'
) COMMENT='仪器级互斥锁表';

-- 校准证书：创建后不可修改，仅可撤销；同一仪器未撤销证书区间不得重叠（相邻合法）。
-- 证书本身即“证书版本”，复核通过证书 ID 引用其提交时的版本。
CREATE TABLE IF NOT EXISTS calibration_certificate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '证书 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '有效期终点（UTC，右开，不含该时刻）',
    coeff_a DECIMAL(38,6) NOT NULL COMMENT '校准系数 a，十进制，最多 6 位小数',
    offset_b DECIMAL(38,6) NOT NULL COMMENT '校准偏移 b，十进制，最多 6 位小数',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销；TRUE 表示该证书版本已失效',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（UTC）；未撤销时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    KEY idx_cert_instrument (instrument_id, revoked, valid_from, valid_to)
) COMMENT='校准证书';

-- 测量批次：目标值与允许偏差按修订版本管理；批次可被放行，放行后只允许新增后续修订。
CREATE TABLE IF NOT EXISTS measurement_batch (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量批次 ID，自增',
    batch_key VARCHAR(64) NOT NULL COMMENT '业务批次键，全局唯一，作为幂等与对外标识',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    current_revision_no INT NOT NULL COMMENT '当前测量修订版本号，从 1 开始，只增不改',
    status VARCHAR(16) NOT NULL COMMENT '批次状态：OPEN=未放行（含放行后新增修订），RELEASED=当前修订已放行',
    created_by VARCHAR(64) NOT NULL COMMENT '批次创建人',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_batch_key UNIQUE (batch_key),
    KEY idx_batch_instrument (instrument_id)
) COMMENT='测量批次';

-- 测量修订：每次修订封存一份目标值/允许偏差配置；已放行修订的放行快照不可被后续修订改写。
CREATE TABLE IF NOT EXISTS measurement_revision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '修订记录 ID，自增',
    batch_id BIGINT NOT NULL COMMENT '所属测量批次 ID',
    revision_no INT NOT NULL COMMENT '修订版本号，批次内从 1 开始递增',
    target_value DECIMAL(38,6) NOT NULL COMMENT '目标值，十进制，最多 6 位小数；允许区间以其为中心',
    tolerance DECIMAL(38,6) NOT NULL COMMENT '允许偏差（非负），最多 6 位小数；允许区间 [目标-偏差, 目标+偏差]',
    reason VARCHAR(500) NULL COMMENT '修订原因；首版为 NULL，后续修订必填，说明新增离群事实等',
    created_by VARCHAR(64) NOT NULL COMMENT '修订创建人',
    created_at DATETIME(6) NOT NULL COMMENT '修订创建时间（UTC）',
    released_at DATETIME(6) NULL COMMENT '该修订放行时间（UTC）；未放行为 NULL，放行后不可再写入读数',
    CONSTRAINT uk_revision_no UNIQUE (batch_id, revision_no)
) COMMENT='测量修订版本';

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果。
-- 批次读数额外携带批次与修订号；离群读数被 REJECT 裁决后置为 EXCLUDED，不参与统计与放行。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量记录 ID，自增',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键，全局唯一，作为幂等键',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    measured_at DATETIME(6) NOT NULL COMMENT '测量时刻（UTC）',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点）：批次读数为目标值-允许偏差，最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点）：批次读数为目标值+允许偏差，最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '读数提交人，不得担任该读数离群复核人',
    certificate_id BIGINT NOT NULL COMMENT '提交时匹配到的校准证书 ID（引用的证书版本）',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入计算值 a×读数+b，最多 12 位小数',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入值判定且包含上下限端点，FALSE 即离群',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行，EXCLUDED=被 REJECT 裁决排除',
    batch_id BIGINT NULL COMMENT '所属测量批次 ID；不走批次的独立测量为 NULL',
    revision_no INT NULL COMMENT '提交时的测量修订版本号；独立测量为 NULL',
    excluded BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已被离群 REJECT 裁决排除统计与放行；FALSE=保留',
    excluded_at DATETIME(6) NULL COMMENT '排除时间（UTC）；保留或未裁决时为 NULL',
    excluded_by_outlier_id BIGINT NULL COMMENT '导致排除的离群记录 ID；未排除时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id),
    KEY idx_measurement_batch (batch_id, revision_no)
) COMMENT='测量记录';

-- 离群记录：读数超出 [目标-偏差, 目标+偏差] 时自动生成，封存目标值、阈值、读数与提交角色，不可变。
CREATE TABLE IF NOT EXISTS outlier_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '离群记录 ID，自增',
    outlier_key VARCHAR(64) NOT NULL COMMENT '离群业务键，全局唯一，作为对外标识',
    batch_id BIGINT NOT NULL COMMENT '所属测量批次 ID',
    revision_no INT NOT NULL COMMENT '检测时封存的测量修订版本号',
    measurement_id BIGINT NOT NULL COMMENT '离群读数对应的测量记录 ID',
    target_value DECIMAL(38,6) NOT NULL COMMENT '封存的目标值，最多 6 位小数',
    tolerance DECIMAL(38,6) NOT NULL COMMENT '封存的允许偏差（非负），最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '封存的允许下限=目标值-偏差（含端点）',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '封存的允许上限=目标值+偏差（含端点）',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '封存的原始读数，最多 6 位小数',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '封存的未舍入计算值，最多 12 位小数',
    deviation DECIMAL(38,12) NOT NULL COMMENT '有符号差额=计算值-目标值；绝对值大于偏差即离群',
    excess DECIMAL(38,12) NOT NULL COMMENT '超出量=|差额|-允许偏差；离群记录该值必为正',
    submitted_by VARCHAR(64) NOT NULL COMMENT '读数提交人（封存角色，不得复核本记录）',
    status VARCHAR(16) NOT NULL COMMENT '裁决状态：PENDING=待裁决（含待仲裁），ACCEPTED=两人同议保留，REJECTED=两人同议排除',
    detected_at DATETIME(6) NOT NULL COMMENT '离群检测时间（UTC）',
    decided_at DATETIME(6) NULL COMMENT '裁决形成时间（UTC）；未形成有效裁决时为 NULL',
    CONSTRAINT uk_outlier_key UNIQUE (outlier_key),
    CONSTRAINT uk_outlier_measurement_revision UNIQUE (measurement_id, revision_no),
    KEY idx_outlier_batch_revision (batch_id, revision_no, status)
) COMMENT='离群记录';

-- 离群复核：只增不改。两名不同于提交人的复核人同议才成裁；引用失效版本的复核标记 STALE 且不计入裁决。
CREATE TABLE IF NOT EXISTS outlier_review (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '复核记录 ID，自增',
    outlier_id BIGINT NOT NULL COMMENT '所属离群记录 ID',
    reviewer VARCHAR(64) NOT NULL COMMENT '复核人，必须不同于该读数提交人',
    decision VARCHAR(8) NOT NULL COMMENT '复核结论：ACCEPT=保留读数，REJECT=排除读数',
    reason VARCHAR(500) NOT NULL COMMENT '复核理由；ACCEPT/REJECT 均必填',
    measurement_revision INT NOT NULL COMMENT '复核引用的当前测量修订版本号',
    certificate_id BIGINT NOT NULL COMMENT '复核引用的证书版本（测量记录绑定的证书 ID）',
    stale BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'TRUE=引用版本已变更或证书已失效而标记 STALE，不计入裁决',
    created_at DATETIME(6) NOT NULL COMMENT '复核时间（UTC）',
    -- 不设 (outlier_id, reviewer) 唯一约束：STALE 复核不占名额，同一复核人可用当前版本重新表态；
    -- 有效复核的同人去重由服务层在批次行锁内保证。
    KEY idx_review_outlier (outlier_id, id),
    KEY idx_review_outlier_reviewer (outlier_id, reviewer)
) COMMENT='离群复核记录';

-- 批次事件流水：离群检测、每次复核、裁决、统计重算、修订与放行快照只追加，
-- 封存目标值、阈值、读数与角色；历史证据不被后续操作覆盖，按 id 升序即提交顺序。
CREATE TABLE IF NOT EXISTS batch_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '事件序号，自增，批次内严格递增即提交顺序',
    batch_id BIGINT NOT NULL COMMENT '所属测量批次 ID',
    revision_no INT NOT NULL COMMENT '事件关联的测量修订版本号',
    event_type VARCHAR(24) NOT NULL COMMENT '事件类型：REVISION_CREATED/OUTLIER_DETECTED/REVIEW/VERDICT/RECOMPUTE/RELEASE',
    actor VARCHAR(64) NOT NULL COMMENT '事件触发角色（提交人/复核人/放行人/修订人）',
    payload TEXT NOT NULL COMMENT '封存内容快照（JSON），字段、键顺序与 null 含义保持稳定',
    occurred_at DATETIME(6) NOT NULL COMMENT '事件发生时间（UTC，微秒精度）',
    KEY idx_event_batch (batch_id, id)
) COMMENT='批次不可变事件流水';

-- 请求幂等记录：仅记录已提交成功的请求；业务失败随事务回滚，不占用 requestId。
CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(96) NOT NULL PRIMARY KEY COMMENT '请求幂等键（X-Request-Id）',
    method VARCHAR(8) NOT NULL COMMENT 'HTTP 方法',
    path VARCHAR(255) NOT NULL COMMENT '请求路径',
    actor VARCHAR(64) NULL COMMENT '请求角色（X-Actor-Id，无该头时为 NULL）',
    request_hash CHAR(64) NOT NULL COMMENT '同参归一指纹 SHA-256 十六进制；异参重放判 409',
    status_code INT NULL COMMENT '首次成功响应的 HTTP 状态码；处理中为 NULL',
    response_body TEXT NULL COMMENT '首次成功响应体原文，用于同参重放；处理中为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '首次请求时间（UTC）'
) COMMENT='请求幂等记录';

-- 放行历史：证书撤销后保留，不回写为从未放行；批次放行按修订生成共享 UUID 与不可变放行快照。
CREATE TABLE IF NOT EXISTS release_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '放行记录 ID，自增',
    batch_id VARCHAR(64) NOT NULL COMMENT '放行批次 ID（UUID），同一批原子放行共享',
    measurement_id BIGINT NOT NULL COMMENT '测量记录 ID',
    released_by VARCHAR(64) NOT NULL COMMENT '放行人（X-Actor-Id）',
    released_at DATETIME(6) NOT NULL COMMENT '放行时间（UTC）',
    KEY idx_release_measurement (measurement_id)
) COMMENT='放行历史';
