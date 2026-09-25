-- 多语种段落修订与发布快照 schema（H2 MySQL 兼容模式）。
-- 所有时间字段为数据库默认时区时间戳；版本号均从 1（文档发布版本从 0）开始单调递增。

CREATE TABLE IF NOT EXISTS document (
    document_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_languages VARCHAR(255) NOT NULL,
    draft_version INT NOT NULL,
    published_version INT NOT NULL,
    term_version INT NOT NULL,
    document_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE document IS '文档：全局唯一 documentId，含 1~5 种目标语言及草稿/发布/术语/文档版本';
COMMENT ON COLUMN document.document_id IS '全局唯一文档 ID，自增';
COMMENT ON COLUMN document.target_languages IS '目标语言列表，逗号分隔的小写语言码，1~5 种';
COMMENT ON COLUMN document.draft_version IS '文档草稿版本，从 1 开始；增段落或修改源文/译文/术语时加一';
COMMENT ON COLUMN document.published_version IS '已发布版本号，从 0 开始，每次成功发布加一';
COMMENT ON COLUMN document.term_version IS '当前术语版本，从 0 开始（0 表示尚未建立术语版本），每次新增术语版本加一';
COMMENT ON COLUMN document.document_version IS '文档版本，从 1 开始；增段落或源文修订等源文景观变化时加一，术语冻结绑定该版本，变化后旧冻结不能复用';
COMMENT ON COLUMN document.created_at IS '创建时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS segment (
    document_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    source_text LONGTEXT NOT NULL,
    source_version INT NOT NULL,
    PRIMARY KEY (document_id, segment_id)
);
COMMENT ON TABLE segment IS '段落：文档内唯一 segmentId，含源文及源文版本';
COMMENT ON COLUMN segment.document_id IS '所属文档 ID';
COMMENT ON COLUMN segment.segment_id IS '文档内唯一段落 ID';
COMMENT ON COLUMN segment.source_text IS '源文正文，UTF-8';
COMMENT ON COLUMN segment.source_version IS '源文版本，从 1 开始，每次源文修订加一';

CREATE TABLE IF NOT EXISTS translation (
    document_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    content LONGTEXT NOT NULL,
    author VARCHAR(128) NOT NULL,
    source_version INT NOT NULL,
    translation_version INT NOT NULL,
    term_version INT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, segment_id, language)
);
COMMENT ON TABLE translation IS '译文：按段落与语言唯一，保存正文、作者、所依据源文版本、绑定术语版本及递增译文版本';
COMMENT ON COLUMN translation.document_id IS '所属文档 ID';
COMMENT ON COLUMN translation.segment_id IS '所属段落 ID';
COMMENT ON COLUMN translation.language IS '目标语言码，小写';
COMMENT ON COLUMN translation.content IS '译文正文，UTF-8';
COMMENT ON COLUMN translation.author IS '译文作者，取提交时 X-Actor-Id';
COMMENT ON COLUMN translation.source_version IS '译文所依据的源文版本；提交时必须等于当前源文版本';
COMMENT ON COLUMN translation.translation_version IS '译文版本，从 1 开始，每次重新提交加一';
COMMENT ON COLUMN translation.term_version IS '译文提交时绑定的术语版本；不等于当前术语版本时视为术语过期';
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

CREATE TABLE IF NOT EXISTS term_freeze (
    document_id BIGINT NOT NULL,
    freeze_version INT NOT NULL,
    document_version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    freeze_key VARCHAR(64) NOT NULL,
    operator VARCHAR(128) NOT NULL,
    active_version INT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL,
    PRIMARY KEY (document_id, freeze_version),
    CONSTRAINT uk_term_freeze_key UNIQUE (freeze_key),
    CONSTRAINT uk_term_freeze_active UNIQUE (document_id, active_version)
);
COMMENT ON TABLE term_freeze IS '术语冻结：文档某草稿版本上的不可变术语冻结，同一文档版本至多一份有效（ACTIVE）冻结';
COMMENT ON COLUMN term_freeze.document_id IS '所属文档 ID';
COMMENT ON COLUMN term_freeze.freeze_version IS '冻结版本号，文档内从 1 开始单调递增，撤销后再冻结取下一版本';
COMMENT ON COLUMN term_freeze.document_version IS '冻结时绑定的文档版本（document.document_version）；文档版本变化后该冻结失效，不能复用';
COMMENT ON COLUMN term_freeze.status IS '冻结状态：ACTIVE 有效，REVOKED 已撤销；撤销只影响后续修订与发布';
COMMENT ON COLUMN term_freeze.freeze_key IS 'freezeKey 指纹：文档 ID、文档版本、规范化术语条目、操作者与状态的 SHA-256，全局唯一，同键重放';
COMMENT ON COLUMN term_freeze.operator IS '冻结操作者，取创建时 X-Actor-Id';
COMMENT ON COLUMN term_freeze.active_version IS '有效冻结绑定的文档版本，撤销时置 NULL；与 document_id 组成唯一约束保证同版本至多一份有效冻结';
COMMENT ON COLUMN term_freeze.created_at IS '冻结创建时间，数据库默认时区';
COMMENT ON COLUMN term_freeze.revoked_at IS '撤销时间，数据库默认时区；未撤销为 NULL';

CREATE TABLE IF NOT EXISTS term_freeze_entry (
    entry_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    freeze_version INT NOT NULL,
    normalized_term VARCHAR(512) NOT NULL,
    language VARCHAR(16) NOT NULL,
    allowed_translation VARCHAR(2048) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE term_freeze_entry IS '术语冻结条目：属于某冻结版本的不可变条目，创建后不可原地修改，按规范化术语与语言给出允许译法';
COMMENT ON COLUMN term_freeze_entry.entry_id IS '条目自增主键';
COMMENT ON COLUMN term_freeze_entry.document_id IS '所属文档 ID';
COMMENT ON COLUMN term_freeze_entry.freeze_version IS '所属冻结版本号';
COMMENT ON COLUMN term_freeze_entry.normalized_term IS '规范化术语：去首尾空白、压缩连续空白并转小写；命中按规范化源文连续子串判定';
COMMENT ON COLUMN term_freeze_entry.language IS '目标语言码，小写';
COMMENT ON COLUMN term_freeze_entry.allowed_translation IS '该术语在目标语言中的一种允许译法（同样规范化存储），同术语同语言可有多条';
COMMENT ON COLUMN term_freeze_entry.created_at IS '条目创建时间，数据库默认时区';

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
