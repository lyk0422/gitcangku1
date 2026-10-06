-- 测量校准与结果放行 数据库结构
-- 所有时间字段均为 UTC 墙钟时间（DATETIME(6)），由应用层以 UTC 写入与读取。

-- 仪器级互斥锁表：同一仪器的证书创建通过锁定本表对应行串行化，
-- 保证并发创建重叠区间证书时最多一张成功。
CREATE TABLE IF NOT EXISTS instrument_lock (
    instrument_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '仪器 ID，作为该仪器证书创建的互斥锁',
    created_at DATETIME(6) NOT NULL COMMENT '锁记录创建时间（UTC）'
) COMMENT='仪器级互斥锁表';

-- 校准证书：创建后不可修改，仅可撤销；同一仪器未撤销证书区间不得重叠（相邻合法）。
-- LINEAR：线性证书，使用 coeff_a/offset_b；HYSTERESIS：迟滞证书，使用 slope/up_offset/down_offset。
CREATE TABLE IF NOT EXISTS calibration_certificate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '证书 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '有效期终点（UTC，右开，不含该时刻）',
    cert_type VARCHAR(16) NOT NULL DEFAULT 'LINEAR' COMMENT '证书类型：LINEAR=线性，HYSTERESIS=迟滞（按读数方向选偏移）',
    coeff_a DECIMAL(38,6) NULL COMMENT '线性校准系数 a，最多 6 位小数；仅 LINEAR 使用，HYSTERESIS 为 NULL',
    offset_b DECIMAL(38,6) NULL COMMENT '线性校准偏移 b，最多 6 位小数；仅 LINEAR 使用，HYSTERESIS 为 NULL',
    slope DECIMAL(38,6) NULL COMMENT '迟滞斜率，最多 6 位小数；仅 HYSTERESIS 使用，LINEAR 为 NULL',
    up_offset DECIMAL(38,6) NULL COMMENT '迟滞上升方向偏移，最多 6 位小数；仅 HYSTERESIS 使用，LINEAR 为 NULL',
    down_offset DECIMAL(38,6) NULL COMMENT '迟滞下降方向偏移，最多 6 位小数；仅 HYSTERESIS 使用，LINEAR 为 NULL',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销；TRUE 表示已撤销',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（UTC）；未撤销时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    KEY idx_cert_instrument (instrument_id, revoked, valid_from, valid_to)
) COMMENT='校准证书';

-- 迟滞校准会话：固定仪器与 HYSTERESIS 证书，顺序追加 1～20 条原始值后关闭。
-- version 等于已追加条目数，追加/关闭均按期望版本乐观校验并在行锁内原子生效。
CREATE TABLE IF NOT EXISTS calibration_session (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '会话 ID，自增',
    session_key VARCHAR(48) NOT NULL COMMENT '业务会话键，全局唯一',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID，创建时固定',
    certificate_id BIGINT NOT NULL COMMENT '创建时固定的 HYSTERESIS 证书 ID',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数，作用于每条校正值',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数，作用于每条校正值',
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN=可追加，CLOSED=已关闭（冻结全部方向与精确结果）',
    version INT NOT NULL COMMENT '当前版本，等于已追加条目数（0～20）',
    created_by VARCHAR(64) NOT NULL COMMENT '会话创建人',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    closed_at DATETIME(6) NULL COMMENT '关闭时间（UTC）；未关闭为 NULL',
    CONSTRAINT uk_session_key UNIQUE (session_key),
    KEY idx_session_cert (certificate_id)
) COMMENT='迟滞校准会话';

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
    session_id BIGINT NULL COMMENT '所属迟滞会话 ID；普通单测量为 NULL，会话成员只能随会话整批放行',
    session_seq INT NULL COMMENT '会话内序号（1 起）；普通单测量为 NULL',
    direction VARCHAR(8) NULL COMMENT '读数变化方向：UP=上升，DOWN=下降；仅会话成员有值，关闭后冻结',
    created_at DATETIME(6) NOT NULL COMMENT '提交时间（UTC）',
    CONSTRAINT uk_measurement_key UNIQUE (measurement_key),
    KEY idx_measurement_instrument (instrument_id),
    KEY idx_measurement_cert (certificate_id),
    KEY idx_measurement_session (session_id)
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

-- 幂等去重表：新增写入按 requestId 去重。身份为操作+操作者+资源+结构化参数 JSON
-- （含期望版本），分字段存储避免分隔符碰撞；仅成功提交才占键，失败回滚不占键。
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '幂等请求键（requestId），全局唯一',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型，如 SESSION_CREATE/SESSION_APPEND/SESSION_CLOSE/SESSION_RELEASE',
    operator VARCHAR(64) NOT NULL COMMENT '操作者（创建人/提交人/关闭人/放行人）',
    resource VARCHAR(128) NOT NULL COMMENT '操作资源标识（如会话键）',
    params_json TEXT NOT NULL COMMENT '全部请求参数（含期望版本）的 JSON，结构化比较',
    response_status INT NOT NULL COMMENT '首次成功响应的 HTTP 状态码',
    response_body TEXT NOT NULL COMMENT '首次成功响应的完整响应体（JSON），重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间（UTC）'
) COMMENT='幂等去重表';
