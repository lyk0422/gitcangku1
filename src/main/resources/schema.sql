-- 测量校准与结果放行 数据库结构
-- 所有时间字段均为 UTC 墙钟时间（DATETIME(6)），由应用层以 UTC 写入与读取。

-- 仪器级互斥锁表：同一仪器的证书创建通过锁定本表对应行串行化，
-- 保证并发创建重叠区间证书时最多一张成功。
CREATE TABLE IF NOT EXISTS instrument_lock (
    instrument_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '仪器 ID，作为该仪器证书创建的互斥锁',
    created_at DATETIME(6) NOT NULL COMMENT '锁记录创建时间（UTC）'
) COMMENT='仪器级互斥锁表';

-- 校准证书：创建后不可修改，仅可撤销；同一仪器未撤销证书区间不得重叠（相邻合法）。
-- cert_type=LINEAR 使用 coeff_a/offset_b；cert_type=HYSTERESIS 使用 slope/up_offset/down_offset。
CREATE TABLE IF NOT EXISTS calibration_certificate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '证书 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    valid_from DATETIME(6) NOT NULL COMMENT '有效期起点（UTC，左闭，含该时刻）',
    valid_to DATETIME(6) NOT NULL COMMENT '有效期终点（UTC，右开，不含该时刻）',
    cert_type VARCHAR(16) NOT NULL DEFAULT 'LINEAR' COMMENT '证书类型：LINEAR=线性 a×读数+b；HYSTERESIS=迟滞 斜率×读数+方向偏移',
    coeff_a DECIMAL(38,6) NULL COMMENT '线性校准系数 a（仅 LINEAR），最多 6 位小数；HYSTERESIS 为 NULL',
    offset_b DECIMAL(38,6) NULL COMMENT '线性校准偏移 b（仅 LINEAR），最多 6 位小数；HYSTERESIS 为 NULL',
    slope DECIMAL(38,6) NULL COMMENT '迟滞斜率（仅 HYSTERESIS），最多 6 位小数；LINEAR 为 NULL',
    up_offset DECIMAL(38,6) NULL COMMENT '上升方向偏移（仅 HYSTERESIS），最多 6 位小数；LINEAR 为 NULL',
    down_offset DECIMAL(38,6) NULL COMMENT '下降方向偏移（仅 HYSTERESIS），最多 6 位小数；LINEAR 为 NULL',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销；TRUE 表示已撤销',
    revoked_at DATETIME(6) NULL COMMENT '撤销时间（UTC）；未撤销时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    KEY idx_cert_instrument (instrument_id, revoked, valid_from, valid_to)
) COMMENT='校准证书';

-- 测量记录：提交时按测量时刻匹配唯一有效证书并固化计算结果。
-- 会话成员由会话追加产生（session_id 非空），禁止通过单测量放行接口放行。
CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '测量记录 ID，自增',
    measurement_key VARCHAR(64) NOT NULL COMMENT '业务测量键，全局唯一，作为幂等键',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID',
    measured_at DATETIME(6) NULL COMMENT '测量时刻（UTC）；会话成员不按时刻匹配证书，固定为 NULL',
    raw_reading DECIMAL(38,6) NOT NULL COMMENT '原始读数，十进制，最多 6 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数',
    submitted_by VARCHAR(64) NOT NULL COMMENT '提交人',
    certificate_id BIGINT NOT NULL COMMENT '提交时匹配到的校准证书 ID；会话成员为会话固定证书',
    computed_value DECIMAL(38,12) NOT NULL COMMENT '未舍入计算值（线性 a×读数+b；迟滞 斜率×读数+方向偏移），最多 12 位小数',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入值判定且包含上下限端点',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING=待放行，RELEASED=已放行',
    session_id BIGINT NULL COMMENT '所属校准会话 ID；普通测量为 NULL，会话成员非空',
    direction VARCHAR(8) NULL COMMENT '读数变化方向 UP/DOWN；仅会话成员有值，普通测量为 NULL',
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

-- 迟滞校准会话：固定仪器与 HYSTERESIS 证书，顺序追加 1～20 条原始值；
-- version 等于已提交条目数，追加/关闭须携带期望版本并匹配后加一（关闭不加）。
CREATE TABLE IF NOT EXISTS calibration_session (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '会话 ID，自增',
    instrument_id VARCHAR(64) NOT NULL COMMENT '仪器 ID，须与证书仪器一致',
    certificate_id BIGINT NOT NULL COMMENT '固定的 HYSTERESIS 证书 ID',
    status VARCHAR(16) NOT NULL COMMENT '状态：OPEN=追加中，CLOSED=已关闭（冻结方向与结果），RELEASED=已放行',
    version INT NOT NULL COMMENT '当前版本=已提交条目数；追加须 expectedSessionVersion 相等，成功后加一',
    last_direction VARCHAR(8) NULL COMMENT '最近一条方向 UP/DOWN；无条目时为 NULL，相同读数继承该方向',
    last_raw DECIMAL(38,6) NULL COMMENT '最近一条原始值，用于推导下一条方向；无条目时为 NULL',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    closed_at DATETIME(6) NULL COMMENT '关闭时间（UTC）；未关闭为 NULL',
    KEY idx_session_cert (certificate_id)
) COMMENT='迟滞校准会话';

-- 会话条目：只允许顺序追加（seq 从 1 递增），不允许插入历史或修改前条；
-- 方向与校正值在追加时固化，关闭后整体冻结。
CREATE TABLE IF NOT EXISTS session_entry (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '条目 ID，自增',
    session_id BIGINT NOT NULL COMMENT '所属会话 ID',
    seq INT NOT NULL COMMENT '会话内序号，从 1 开始连续递增',
    raw_value DECIMAL(38,6) NOT NULL COMMENT '原始值，十进制，最多 6 位小数',
    direction VARCHAR(8) NOT NULL COMMENT '方向 UP/DOWN；首条显式给定，后续按读数增减推导、相同继承',
    corrected_value DECIMAL(38,12) NOT NULL COMMENT '未舍入校正值 斜率×原值+方向偏移，最多 12 位小数',
    lower_limit DECIMAL(38,6) NOT NULL COMMENT '合格下限（含端点），最多 6 位小数',
    upper_limit DECIMAL(38,6) NOT NULL COMMENT '合格上限（含端点），最多 6 位小数',
    passed BOOLEAN NOT NULL COMMENT '是否合格；基于未舍入校正值判定且包含端点',
    submitted_by VARCHAR(64) NOT NULL COMMENT '条目提交人；会话放行人不得是任何条目提交人',
    measurement_id BIGINT NOT NULL COMMENT '关联测量记录 ID（会话成员，禁止单条放行）',
    created_at DATETIME(6) NOT NULL COMMENT '追加时间（UTC）',
    CONSTRAINT uk_session_entry_seq UNIQUE (session_id, seq)
) COMMENT='迟滞校准会话条目';

-- 幂等记录：新增写入按 requestId 去重；身份为操作+操作者+资源+全部参数及期望版本的结构化 JSON。
-- 仅成功提交后记录可见（失败随事务回滚不占键）；同键同参重放首次完整响应，异参 409。
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '幂等请求 ID，全局唯一',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型，如 SESSION_CREATE / SESSION_APPEND / SESSION_CLOSE / SESSION_RELEASE / CERT_CREATE_HYSTERESIS',
    identity_json VARCHAR(2048) NOT NULL COMMENT '结构化身份 JSON（操作、操作者、资源、全部参数、期望版本），避免分隔符碰撞',
    response_json CLOB NULL COMMENT '首次成功的完整响应 JSON；与业务同事务提交，重放时原样返回',
    created_at DATETIME(6) NOT NULL COMMENT '记录创建时间（UTC）'
) COMMENT='幂等请求记录';
