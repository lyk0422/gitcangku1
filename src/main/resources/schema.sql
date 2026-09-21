-- 授权与数据隔离题数据库结构。
-- 所有时间列均为 Asia/Shanghai 本地时间（与应用时区一致），精度到秒。

-- 授权表：按“主体 + 用途”管理，每次成功授权生成一代（epoch 从 1 递增）。
-- status: ACTIVE=当前有效，REVOKE 后变为 REVOKED（已撤回，只允许 ACTIVE -> REVOKED 单向迁移）。
CREATE TABLE IF NOT EXISTS consent_grant (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose     VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH=研究，PERSONALIZATION=个性化',
    epoch       INT         NOT NULL COMMENT '授权代次，同一主体+用途内从 1 开始递增',
    status      VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE=有效，REVOKED=已撤回',
    request_id  VARCHAR(128) NOT NULL COMMENT '产生本代授权的幂等请求标识',
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间（本地时间）',
    revoked_at  TIMESTAMP   NULL COMMENT '撤回时间（本地时间），未撤回为 NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uk_grant_epoch (subject_key, purpose, epoch)
) COMMENT = '授权代次表';

-- 数据记录表：记录仅归属某一代授权；撤回后不物理删除，仅通过代次状态隔离。
CREATE TABLE IF NOT EXISTS consent_record (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose     VARCHAR(32)  NOT NULL COMMENT '用途：RESEARCH=研究，PERSONALIZATION=个性化',
    epoch       INT          NOT NULL COMMENT '写入时的授权代次',
    record_key  VARCHAR(128) NOT NULL COMMENT '记录键，同一代内唯一',
    payload     VARCHAR(2048) NOT NULL COMMENT '记录内容（合成字符串）',
    request_id  VARCHAR(128) NOT NULL COMMENT '写入本记录的幂等请求标识',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间（本地时间）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_record_key (subject_key, purpose, epoch, record_key)
) COMMENT = '授权数据记录表';

-- 幂等请求表：仅保存成功请求；失败请求随事务回滚不占用 requestId。
-- 同一 requestId 相同参数重试返回原结果，参数不同返回 409。
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id   VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    request_type VARCHAR(16)  NOT NULL COMMENT '请求类型：GRANT=授权，REVOKE=撤回，WRITE=写入记录',
    fingerprint  VARCHAR(512) NOT NULL COMMENT '请求参数指纹，用于识别同 requestId 参数变化',
    http_status  INT          NOT NULL COMMENT '原成功响应的 HTTP 状态码',
    response_body VARCHAR(2048) NOT NULL COMMENT '原成功响应体（JSON）',
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次成功时间（本地时间）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求记录表';
