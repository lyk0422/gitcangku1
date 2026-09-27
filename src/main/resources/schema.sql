-- 授权代次表：按“主体＋用途”维护从 1 开始递增的代次，只允许从有效变为已撤回
CREATE TABLE IF NOT EXISTS consent_grant (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '授权代次，从 1 开始递增',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 有效 / REVOKED 已撤回',
    region_code VARCHAR(32) NULL DEFAULT NULL COMMENT '本代驻留区域代码，须预先登记；NULL 表示尚未绑定区域，绑定后不可直接修改',
    consent_version INT NOT NULL DEFAULT 1 COMMENT '授权版本：直接授权为 1，迁移产生的新代为源代版本+1，快照固化后不改写',
    request_id VARCHAR(128) NOT NULL COMMENT '创建本代授权的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    revoked_at TIMESTAMP NULL DEFAULT NULL COMMENT '撤回时间（服务器时区 Asia/Shanghai），未撤回为 NULL',
    PRIMARY KEY (subject_key, purpose, epoch)
) COMMENT = '授权代次表';

-- 授权记录表：仅当前有效代次可写入和查询，撤回后保留数据但不可见；清除后内容置 NULL
CREATE TABLE IF NOT EXISTS consent_record (
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '记录所属授权代次',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键，同一代内唯一',
    payload TEXT NULL COMMENT '记录内容（合成字符串）；数据清除后置 NULL',
    region_code VARCHAR(32) NULL DEFAULT NULL COMMENT '写入时的驻留区域路由，须与所属代次区域一致；代次未绑定区域时为 NULL',
    request_id VARCHAR(128) NOT NULL COMMENT '写入本记录的幂等请求标识',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    erased_at TIMESTAMP NULL DEFAULT NULL COMMENT '数据清除时间（UTC），未清除为 NULL；清除后记录不可读、不可迁移',
    PRIMARY KEY (subject_key, purpose, epoch, record_key)
) COMMENT = '授权记录表';

-- 幂等请求表：成功结果与业务变更同事务保存，失败请求不占用 requestId
CREATE TABLE IF NOT EXISTS idempotency_request (
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求标识',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：GRANT 授权 / WRITE 写入 / REVOKE 撤回 / REGION_REGISTER 区域登记 / REGION_BIND 区域绑定 / MIGRATE 迁移 / ERASE 清除 / QUERY 跨对象查询',
    params_fingerprint VARCHAR(512) NOT NULL COMMENT '规范化参数指纹，用于检测同 requestId 参数变更',
    response_body TEXT NOT NULL COMMENT '成功响应快照（JSON）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（服务器时区 Asia/Shanghai）',
    PRIMARY KEY (request_id)
) COMMENT = '幂等请求表';

-- 驻留区域登记表：区域代码必须预先登记，登记后不可修改
CREATE TABLE IF NOT EXISTS residency_region (
    region_code VARCHAR(32) NOT NULL COMMENT '区域代码（合成字符串），全局唯一',
    request_id VARCHAR(128) NOT NULL COMMENT '登记本区域的幂等请求标识',
    created_at TIMESTAMP NOT NULL COMMENT '登记时间（UTC）',
    PRIMARY KEY (region_code)
) COMMENT = '驻留区域登记表';

-- 查询快照表：跨对象查询成功时生成，固化当时代次，后续迁移/撤回/清除不改写
CREATE TABLE IF NOT EXISTS consent_snapshot (
    snapshot_id VARCHAR(64) NOT NULL COMMENT '快照标识（UUID）',
    request_id VARCHAR(128) NOT NULL COMMENT '生成本快照的幂等请求标识',
    subject_key VARCHAR(128) NOT NULL COMMENT '主体标识（合成字符串）',
    purpose VARCHAR(32) NOT NULL COMMENT '用途：RESEARCH 研究 / PERSONALIZATION 个性化',
    epoch INT NOT NULL COMMENT '快照时的授权代次',
    created_at TIMESTAMP NOT NULL COMMENT '快照生成时间（UTC）',
    PRIMARY KEY (snapshot_id)
) COMMENT = '查询快照表';

-- 查询快照明细表：固化每个对象的代次、驻留区域与授权版本，按 item_index 保持稳定排序
CREATE TABLE IF NOT EXISTS consent_snapshot_item (
    snapshot_id VARCHAR(64) NOT NULL COMMENT '所属快照标识',
    record_key VARCHAR(128) NOT NULL COMMENT '记录键',
    epoch INT NOT NULL COMMENT '对象所属授权代次（快照时固化）',
    region_code VARCHAR(32) NULL DEFAULT NULL COMMENT '对象驻留区域（快照时固化）；未绑定区域为 NULL',
    consent_version INT NOT NULL COMMENT '授权版本（快照时固化）',
    payload TEXT NULL COMMENT '对象内容（快照时固化）；已清除对象为 NULL',
    item_index INT NOT NULL COMMENT '快照内排序序号，从 0 开始，保证明细顺序稳定',
    PRIMARY KEY (snapshot_id, record_key)
) COMMENT = '查询快照明细表';
