-- 多语种段落修订与发布快照 schema（H2 MySQL 兼容模式）。
-- 所有时间字段为数据库默认时区时间戳；版本号均从 1（文档发布版本从 0）开始单调递增。

CREATE TABLE IF NOT EXISTS document (
    document_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_languages VARCHAR(255) NOT NULL,
    draft_version INT NOT NULL,
    published_version INT NOT NULL,
    term_version INT NOT NULL,
    train_release_version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE document IS '文档：全局唯一 documentId，含 1~5 种目标语言及草稿/发布/术语版本';
COMMENT ON COLUMN document.document_id IS '全局唯一文档 ID，自增';
COMMENT ON COLUMN document.target_languages IS '目标语言列表，逗号分隔的小写语言码，1~5 种';
COMMENT ON COLUMN document.draft_version IS '文档草稿版本，从 1 开始；增段落或修改源文/译文/术语时加一';
COMMENT ON COLUMN document.published_version IS '已发布版本号，从 0 开始，每次成功发布加一';
COMMENT ON COLUMN document.term_version IS '当前术语版本，从 0 开始（0 表示尚未建立术语版本），每次新增术语版本加一';
COMMENT ON COLUMN document.train_release_version IS '发布列车版本计数，从 0 开始，每次列车激活加一；全部语言发布指针切到该版本';
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

CREATE TABLE IF NOT EXISTS release_train (
    train_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    train_key VARCHAR(128) NOT NULL,
    document_id BIGINT NOT NULL,
    source_document_version INT NOT NULL,
    planned_at TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    term_version INT,
    source_digest VARCHAR(64),
    precheck_json LONGTEXT,
    release_train_version INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at TIMESTAMP,
    CONSTRAINT uk_release_train_key UNIQUE (train_key)
);
COMMENT ON TABLE release_train IS '发布列车：针对一个源文档版本的多语言原子发布计划，trainKey 全局唯一；状态 DRAFT/READY/CANCELLED/ACTIVATED';
COMMENT ON COLUMN release_train.train_id IS '全局唯一列车 ID，自增';
COMMENT ON COLUMN release_train.train_key IS '发布经理声明的列车键，全局唯一，重复创建返回 409';
COMMENT ON COLUMN release_train.document_id IS '所属文档 ID';
COMMENT ON COLUMN release_train.source_document_version IS '创建列车时声明的源文档草稿版本，必须等于创建时当前草稿版本';
COMMENT ON COLUMN release_train.planned_at IS '计划发布时间，数据库默认时区；到达该时刻后才允许激活';
COMMENT ON COLUMN release_train.status IS '列车状态：DRAFT 已创建、READY 已冻结、CANCELLED 已整列取消、ACTIVATED 已激活';
COMMENT ON COLUMN release_train.term_version IS '进入 READY 时冻结的术语版本；为空表示尚未进入 READY';
COMMENT ON COLUMN release_train.source_digest IS '进入 READY 时冻结的源文摘要（全部段落 ID+版本+正文的 SHA-256）；为空表示尚未进入 READY';
COMMENT ON COLUMN release_train.precheck_json IS '进入 READY 时冻结的预检结果 JSON；为空表示尚未进入 READY';
COMMENT ON COLUMN release_train.release_train_version IS '激活后本列车推进到的发布列车版本；为空表示尚未激活';
COMMENT ON COLUMN release_train.created_at IS '创建时间，数据库默认时区';
COMMENT ON COLUMN release_train.activated_at IS '激活时间，数据库默认时区；为空表示尚未激活';

CREATE TABLE IF NOT EXISTS release_train_locale (
    train_id BIGINT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    candidate_translation_version INT NOT NULL,
    expected_version INT NOT NULL,
    PRIMARY KEY (train_id, locale)
);
COMMENT ON TABLE release_train_locale IS '发布列车语言候选：每列车 2~20 个唯一语言，进入 READY 后不可替换';
COMMENT ON COLUMN release_train_locale.train_id IS '所属列车 ID';
COMMENT ON COLUMN release_train_locale.locale IS '目标语言码，小写；列车内唯一且集合须与文档目标语言完全一致';
COMMENT ON COLUMN release_train_locale.candidate_translation_version IS '该语言候选译文版本：全部段落译文须处于该版本且已批准';
COMMENT ON COLUMN release_train_locale.expected_version IS '该语言期望的当前发布指针；激活时若指针已被其他发布推进则整列 409';

CREATE TABLE IF NOT EXISTS release_pointer (
    document_id BIGINT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    released_version INT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, locale)
);
COMMENT ON TABLE release_pointer IS '发布指针：每个文档每种语言当前已推进到的发布列车版本，无行表示 0（尚未发布）';
COMMENT ON COLUMN release_pointer.document_id IS '所属文档 ID';
COMMENT ON COLUMN release_pointer.locale IS '目标语言码，小写';
COMMENT ON COLUMN release_pointer.released_version IS '当前发布指针，等于最近一次覆盖该语言的成功列车版本；0 表示尚未发布';
COMMENT ON COLUMN release_pointer.updated_at IS '最近推进时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS train_snapshot (
    document_id BIGINT NOT NULL,
    release_train_version INT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, release_train_version, locale)
);
COMMENT ON TABLE train_snapshot IS '列车发布快照：激活时按语言原子生成的不可变只读快照，记录源文、候选、术语与切换前后指针';
COMMENT ON COLUMN train_snapshot.document_id IS '所属文档 ID';
COMMENT ON COLUMN train_snapshot.release_train_version IS '发布列车版本，同一列车全部语言共用同一版本';
COMMENT ON COLUMN train_snapshot.locale IS '该快照对应的语言码，小写';
COMMENT ON COLUMN train_snapshot.snapshot_json IS '快照内容 JSON：源文摘要、候选译文、术语版本及规则、切换前后发布指针';
COMMENT ON COLUMN train_snapshot.created_at IS '快照生成时间，数据库默认时区';
