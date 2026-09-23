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
COMMENT ON COLUMN document.draft_version IS '文档草稿版本，从 1 开始；增段落或修改源文/译文/术语或结构修订时加一';
COMMENT ON COLUMN document.published_version IS '已发布版本号，从 0 开始，每次成功发布加一';
COMMENT ON COLUMN document.term_version IS '当前术语版本，从 0 开始（0 表示尚未建立术语版本），每次新增术语版本加一';
COMMENT ON COLUMN document.created_at IS '创建时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS segment (
    document_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    source_text LONGTEXT NOT NULL,
    source_version INT NOT NULL,
    position INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'CURRENT',
    superseded_at TIMESTAMP NULL,
    PRIMARY KEY (document_id, segment_id)
);
COMMENT ON TABLE segment IS '段落：文档内唯一 segmentId，含源文、源文版本、当前结构位置与状态；结构修订后旧段保留为 SUPERSEDED';
COMMENT ON COLUMN segment.document_id IS '所属文档 ID';
COMMENT ON COLUMN segment.segment_id IS '文档内唯一段落 ID（含已废止段，结构修订新段键不得与任何历史段重复）';
COMMENT ON COLUMN segment.source_text IS '源文正文，UTF-8';
COMMENT ON COLUMN segment.source_version IS '源文版本，从 1 开始，每次源文修订加一；结构修订新段从 1 开始';
COMMENT ON COLUMN segment.position IS '当前结构中的从 0 开始的连续位置序号；SUPERSEDED 段保留被替换时的位置';
COMMENT ON COLUMN segment.status IS '段落状态：CURRENT=当前结构中的段，SUPERSEDED=被结构修订废止的段';
COMMENT ON COLUMN segment.superseded_at IS '废止时间，数据库默认时区；NULL 表示当前段';

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
COMMENT ON TABLE translation IS '译文：按段落与语言唯一，保存正文、作者、所依据源文版本、绑定术语版本及递增译文版本；结构修订后旧段译文保留为 REFERENCE 来源';
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
COMMENT ON TABLE approval IS '批准：按段落与语言唯一；源文或译文版本改变、或段落被结构修订废止后先前批准不再有效';
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
COMMENT ON TABLE release_snapshot IS '发布快照：发布时原子生成的完整只读快照，JSON 序列化，不可修改；结构修订不影响历史快照';
COMMENT ON COLUMN release_snapshot.document_id IS '所属文档 ID';
COMMENT ON COLUMN release_snapshot.published_version IS '发布版本号，从 1 开始';
COMMENT ON COLUMN release_snapshot.snapshot_json IS '快照内容 JSON：全部当前段落源文及各语言译文、作者、审核人与版本号';
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

CREATE TABLE IF NOT EXISTS structure_change (
    document_id BIGINT NOT NULL,
    change_key VARCHAR(128) NOT NULL,
    operation VARCHAR(8) NOT NULL,
    document_version INT NOT NULL,
    expected_document_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, change_key),
    UNIQUE (change_key)
);
COMMENT ON TABLE structure_change IS '结构修订事务：一次 changeKey 完成一次拆分（SPLIT，1 段拆 2~5 段）或合并（MERGE，2~5 个连续段合 1 段）';
COMMENT ON COLUMN structure_change.document_id IS '所属文档 ID';
COMMENT ON COLUMN structure_change.change_key IS '结构修订键，全局唯一；重复提交返回 409';
COMMENT ON COLUMN structure_change.operation IS '操作类型：SPLIT=拆分，MERGE=合并；一次事务只能是其中一种';
COMMENT ON COLUMN structure_change.document_version IS '修订成功后生成的文档草稿版本';
COMMENT ON COLUMN structure_change.expected_document_version IS '提交时携带的期望文档草稿版本，与当前版本不符返回 409';
COMMENT ON COLUMN structure_change.created_at IS '结构修订时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS segment_lineage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    change_key VARCHAR(128) NOT NULL,
    old_segment_id VARCHAR(64) NOT NULL,
    old_source_version INT NOT NULL,
    new_segment_id VARCHAR(64) NOT NULL,
    ordinal INT NOT NULL,
    UNIQUE (document_id, change_key, old_segment_id, new_segment_id)
);
COMMENT ON TABLE segment_lineage IS '源段结构血缘：旧源段与新源段的有序对应；拆分一旧对多新，合并多旧对一新';
COMMENT ON COLUMN segment_lineage.document_id IS '所属文档 ID';
COMMENT ON COLUMN segment_lineage.change_key IS '所属结构修订键';
COMMENT ON COLUMN segment_lineage.old_segment_id IS '旧源段 ID（修订后为 SUPERSEDED）';
COMMENT ON COLUMN segment_lineage.old_source_version IS '修订时旧源段的源文版本，与提交期望不符返回 409';
COMMENT ON COLUMN segment_lineage.new_segment_id IS '新源段 ID（修订后为 CURRENT）';
COMMENT ON COLUMN segment_lineage.ordinal IS '从 0 开始的有序序号：拆分时为新段顺序，合并时为旧段连续顺序';

CREATE TABLE IF NOT EXISTS translation_reference (
    document_id BIGINT NOT NULL,
    change_key VARCHAR(128) NOT NULL,
    new_segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    content LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, new_segment_id, language)
);
COMMENT ON TABLE translation_reference IS '结构修订生成的旧译文 REFERENCE 候选：按有序映射直接拼接，仅供参考，不构成译文、批准或发布资格';
COMMENT ON COLUMN translation_reference.document_id IS '所属文档 ID';
COMMENT ON COLUMN translation_reference.change_key IS '生成该候选的结构修订键';
COMMENT ON COLUMN translation_reference.new_segment_id IS '候选所属的新源段 ID';
COMMENT ON COLUMN translation_reference.language IS '目标语言码，小写；每个目标语言每个新段一条';
COMMENT ON COLUMN translation_reference.content IS '按映射顺序直接拼接的旧译文正文（无分隔符），片段边界见 lineage_fragment';
COMMENT ON COLUMN translation_reference.created_at IS '生成时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS lineage_fragment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    change_key VARCHAR(128) NOT NULL,
    new_segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    old_segment_id VARCHAR(64) NOT NULL,
    old_translation_version INT NOT NULL,
    ordinal INT NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    UNIQUE (document_id, change_key, new_segment_id, language, ordinal)
);
CREATE INDEX IF NOT EXISTS idx_lineage_fragment_old
    ON lineage_fragment (document_id, old_segment_id, language);
COMMENT ON TABLE lineage_fragment IS '跨语言译文血缘片段：新段到旧译文片段的有序来源映射及在拼接候选中的字符边界，支持新→旧与旧→新双向查询';
COMMENT ON COLUMN lineage_fragment.document_id IS '所属文档 ID';
COMMENT ON COLUMN lineage_fragment.change_key IS '所属结构修订键';
COMMENT ON COLUMN lineage_fragment.new_segment_id IS '新源段 ID（新→旧方向）';
COMMENT ON COLUMN lineage_fragment.language IS '目标语言码，小写';
COMMENT ON COLUMN lineage_fragment.old_segment_id IS '旧译文所属旧源段 ID（旧→新方向）';
COMMENT ON COLUMN lineage_fragment.old_translation_version IS '修订时该旧译文的译文版本，与提交期望不符返回 409';
COMMENT ON COLUMN lineage_fragment.ordinal IS '同一新段同一语言内从 0 开始的有序片段序号';
COMMENT ON COLUMN lineage_fragment.start_offset IS '片段在拼接候选中的起始字符偏移（含，按 Java 字符长度计）';
COMMENT ON COLUMN lineage_fragment.end_offset IS '片段在拼接候选中的结束字符偏移（不含，按 Java 字符长度计）';

CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(128) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    response_status INT NOT NULL,
    response_body LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE request_log IS '写操作幂等去重：全局唯一 requestId，仅记录成功结果，与业务变更原子提交';
COMMENT ON COLUMN request_log.request_id IS '全局唯一请求 ID';
COMMENT ON COLUMN request_log.request_hash IS '请求参数规范化后的 SHA-256 摘要（含全部有序映射）；同键异参返回 409';
COMMENT ON COLUMN request_log.response_status IS '原成功响应的 HTTP 状态码，用于重放';
COMMENT ON COLUMN request_log.response_body IS '原成功响应体 JSON，用于重放';
COMMENT ON COLUMN request_log.created_at IS '记录时间，数据库默认时区';
