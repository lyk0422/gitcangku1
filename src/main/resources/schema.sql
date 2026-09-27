-- 多语种段落修订与发布快照 schema（H2 MySQL 兼容模式）。
-- 所有时间字段为数据库默认时区时间戳；版本号均从 1（文档发布版本从 0）开始单调递增。

CREATE TABLE IF NOT EXISTS document (
    document_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_languages VARCHAR(255) NOT NULL,
    draft_version INT NOT NULL,
    published_version INT NOT NULL,
    term_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE document IS '文档：全局唯一 documentId，含 1~5 种目标语言及草稿/发布/术语版本';
COMMENT ON COLUMN document.document_id IS '全局唯一文档 ID，自增';
COMMENT ON COLUMN document.target_languages IS '目标语言列表，逗号分隔的小写语言码，1~5 种';
COMMENT ON COLUMN document.draft_version IS '文档草稿版本，从 1 开始；增段落或修改源文/译文/术语时加一';
COMMENT ON COLUMN document.published_version IS '已发布版本号，从 0 开始，每次成功发布加一';
COMMENT ON COLUMN document.term_version IS '当前术语版本，从 0 开始（0 表示尚未建立术语版本），每次新增术语版本加一';
COMMENT ON COLUMN document.created_at IS '创建时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS segment (
    document_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    source_text LONGTEXT NOT NULL,
    source_version INT NOT NULL,
    placeholders VARCHAR(4000) NOT NULL DEFAULT '[]',
    PRIMARY KEY (document_id, segment_id)
);
COMMENT ON TABLE segment IS '段落：文档内唯一 segmentId，含源文、源文版本与版式占位符集合';
COMMENT ON COLUMN segment.document_id IS '所属文档 ID';
COMMENT ON COLUMN segment.segment_id IS '文档内唯一段落 ID';
COMMENT ON COLUMN segment.source_text IS '源文正文，UTF-8';
COMMENT ON COLUMN segment.source_version IS '源文版本，从 1 开始；修订正文或占位符集合均加一';
COMMENT ON COLUMN segment.placeholders IS '源段声明的规范排序占位符集合，JSON 数组字符串（无占位符为 []）；译文须保留完全相同的占位符及出现次数';

CREATE TABLE IF NOT EXISTS translation (
    document_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    content LONGTEXT NOT NULL,
    author VARCHAR(128) NOT NULL,
    source_version INT NOT NULL,
    translation_version INT NOT NULL,
    term_version INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, segment_id, language)
);
COMMENT ON TABLE translation IS '译文：按段落与语言唯一，保存正文、作者、所依据源文版本、绑定术语版本、递增译文版本及修订状态';
COMMENT ON COLUMN translation.document_id IS '所属文档 ID';
COMMENT ON COLUMN translation.segment_id IS '所属段落 ID';
COMMENT ON COLUMN translation.language IS '目标语言码，小写';
COMMENT ON COLUMN translation.content IS '译文正文，UTF-8';
COMMENT ON COLUMN translation.author IS '译文作者，取提交时 X-Actor-Id';
COMMENT ON COLUMN translation.source_version IS '译文所依据的源文版本；提交时必须等于当前源文版本';
COMMENT ON COLUMN translation.translation_version IS '译文版本，从 1 开始，每次重新提交加一';
COMMENT ON COLUMN translation.term_version IS '译文提交时绑定的术语版本；不等于当前术语版本时视为术语过期';
COMMENT ON COLUMN translation.status IS '修订状态：ACTIVE=可进入批准/发布流程，NEEDS_REVISION=源段已修订（正文或占位符集合变化）须重新提交；源段每次修订时该段全部译文置为 NEEDS_REVISION，重新提交后恢复 ACTIVE';
COMMENT ON COLUMN translation.updated_at IS '最近提交时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS approval (
    document_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    source_version INT NOT NULL,
    translation_version INT NOT NULL,
    approved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, segment_id, language)
);
COMMENT ON TABLE approval IS '批准：按段落与语言唯一；源文或译文版本改变后先前批准不再有效';
COMMENT ON COLUMN approval.document_id IS '所属文档 ID';
COMMENT ON COLUMN approval.segment_id IS '所属段落 ID';
COMMENT ON COLUMN approval.language IS '目标语言码，小写';
COMMENT ON COLUMN approval.reviewer IS '审核人，取批准时 X-Actor-Id，不得是译文作者';
COMMENT ON COLUMN approval.source_version IS '批准时的源文版本，发布校验须等于当前源文版本';
COMMENT ON COLUMN approval.translation_version IS '批准时的译文版本，发布校验须等于当前译文版本';
COMMENT ON COLUMN approval.approved_at IS '批准时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS release_snapshot (
    document_id BIGINT NOT NULL,
    published_version INT NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, published_version)
);
COMMENT ON TABLE release_snapshot IS '发布快照：发布时原子生成的完整只读快照，JSON 序列化，不可修改';
COMMENT ON COLUMN release_snapshot.document_id IS '所属文档 ID';
COMMENT ON COLUMN release_snapshot.published_version IS '发布版本号，从 1 开始';
COMMENT ON COLUMN release_snapshot.snapshot_json IS '快照内容 JSON：全部段落源文及各语言译文、作者、审核人与版本号';
COMMENT ON COLUMN release_snapshot.created_at IS '发布时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS term_version (
    document_id BIGINT NOT NULL,
    term_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, term_version)
);
COMMENT ON TABLE term_version IS '术语版本：每个文档从 1 开始的不可变术语快照版本，已有版本不可覆盖';
COMMENT ON COLUMN term_version.document_id IS '所属文档 ID';
COMMENT ON COLUMN term_version.term_version IS '术语版本号，从 1 开始单调递增';
COMMENT ON COLUMN term_version.created_at IS '版本创建时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS term_rule (
    document_id BIGINT NOT NULL,
    term_version INT NOT NULL,
    source_term VARCHAR(512) NOT NULL,
    language VARCHAR(16) NOT NULL,
    required_translation VARCHAR(2048) NOT NULL,
    PRIMARY KEY (document_id, term_version, source_term, language)
);
COMMENT ON TABLE term_rule IS '术语规则：属于某术语版本的不可变规则，按 sourceTerm 与目标语言唯一，每版本 0~100 条';
COMMENT ON COLUMN term_rule.document_id IS '所属文档 ID';
COMMENT ON COLUMN term_rule.term_version IS '所属术语版本号';
COMMENT ON COLUMN term_rule.source_term IS '源文术语，Unicode 原文、区分大小写，按连续子串匹配';
COMMENT ON COLUMN term_rule.language IS '目标语言码，小写';
COMMENT ON COLUMN term_rule.required_translation IS '该术语在目标语言中的必译文本，非空';

CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(128) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    response_status INT NOT NULL,
    response_body LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE request_log IS '写操作幂等去重：全局唯一 requestId，仅记录成功结果，与业务变更原子提交';
COMMENT ON COLUMN request_log.request_id IS '全局唯一请求 ID';
COMMENT ON COLUMN request_log.request_hash IS '请求参数规范化后的 SHA-256 摘要；同键异参返回 409';
COMMENT ON COLUMN request_log.response_status IS '原成功响应的 HTTP 状态码，用于重放';
COMMENT ON COLUMN request_log.response_body IS '原成功响应体 JSON，用于重放';
COMMENT ON COLUMN request_log.created_at IS '记录时间，数据库默认时区';

-- 只追加的操作历史证据：任何后续操作都不得 UPDATE/DELETE 本表记录。
CREATE TABLE IF NOT EXISTS operation_log (
    seq BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    operation_type VARCHAR(40) NOT NULL,
    actor VARCHAR(128),
    segment_id VARCHAR(64),
    language VARCHAR(16),
    request_id VARCHAR(128) NOT NULL,
    occurred_at BIGINT NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    detail_json LONGTEXT NOT NULL
);
COMMENT ON TABLE operation_log IS '操作历史：只追加证据表，记录操作者、操作类型、UTC 毫秒时间与结果指纹，任何后续操作不得覆盖或删除';
COMMENT ON COLUMN operation_log.seq IS '全局单调递增序号，与 occurred_at 共同保证稳定排序';
COMMENT ON COLUMN operation_log.document_id IS '所属文档 ID';
COMMENT ON COLUMN operation_log.operation_type IS '操作类型：DOCUMENT_CREATED/SEGMENT_ADDED/SOURCE_REVISED/TRANSLATION_SUBMITTED/TRANSLATION_APPROVED/TRANSLATION_APPROVAL_WITHDRAWN/TRANSLATIONS_BATCH_APPROVED/PUBLISHED/TERMS_UPDATED';
COMMENT ON COLUMN operation_log.actor IS '操作者 X-Actor-Id；文档级无操作者操作（建文档、发布、术语更新）为 NULL';
COMMENT ON COLUMN operation_log.segment_id IS '关联段落 ID；文档级操作为 NULL';
COMMENT ON COLUMN operation_log.language IS '关联目标语言码；文档级或跨语言操作为 NULL';
COMMENT ON COLUMN operation_log.request_id IS '写操作携带的幂等 requestId；重放不产生新记录';
COMMENT ON COLUMN operation_log.occurred_at IS '操作发生时间，UTC 纪元毫秒；历史查询区间左闭右开（>=from 且 <to）';
COMMENT ON COLUMN operation_log.fingerprint IS 'SHA-256 指纹十六进制，覆盖操作者、操作类型、规范化 JSON 集合与全部结果相关字段';
COMMENT ON COLUMN operation_log.detail_json IS '结果相关字段的规范化 JSON 明细；集合以 JSON 数组编码，null 字段显式保留表示无关联值';
CREATE INDEX IF NOT EXISTS idx_operation_log_doc_time ON operation_log (document_id, occurred_at, seq);
