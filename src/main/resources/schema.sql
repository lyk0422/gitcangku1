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
    status VARCHAR(16) NOT NULL DEFAULT 'CURRENT',
    position INT NOT NULL,
    PRIMARY KEY (document_id, segment_id)
);
COMMENT ON TABLE segment IS '段落：文档内唯一 segmentId，含源文、源文版本、状态与当前顺序';
COMMENT ON COLUMN segment.document_id IS '所属文档 ID';
COMMENT ON COLUMN segment.segment_id IS '文档内唯一段落 ID；结构修订产生的新段全局不与任何已有段键重复';
COMMENT ON COLUMN segment.source_text IS '源文正文，UTF-8';
COMMENT ON COLUMN segment.source_version IS '源文版本，从 1 开始，每次源文修订加一';
COMMENT ON COLUMN segment.status IS '段落状态：CURRENT 为当前有效段，SUPERSEDED 为已被结构修订取代的旧段';
COMMENT ON COLUMN segment.position IS '当前段落顺序，从 1 开始按文档当前结构连续编号；SUPERSEDED 段保留淘汰时序号，不再参与排序';

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

CREATE TABLE IF NOT EXISTS structure_change (
    document_id BIGINT NOT NULL,
    change_key VARCHAR(128) NOT NULL,
    change_type VARCHAR(8) NOT NULL,
    draft_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, change_key)
);
COMMENT ON TABLE structure_change IS '结构修订事务：changeKey 文档内唯一，记录拆分/合并类型与原子生成的新文档草稿版本';
COMMENT ON COLUMN structure_change.document_id IS '所属文档 ID';
COMMENT ON COLUMN structure_change.change_key IS '结构修订事务键，文档内唯一，重复提交返回 409';
COMMENT ON COLUMN structure_change.change_type IS '操作类型：SPLIT 为一段拆多段，MERGE 为多连续段合为一段；不允许混合';
COMMENT ON COLUMN structure_change.draft_version IS '本次结构修订成功后原子生成的文档草稿版本';
COMMENT ON COLUMN structure_change.created_at IS '结构修订提交时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS source_lineage (
    document_id BIGINT NOT NULL,
    new_segment_id VARCHAR(64) NOT NULL,
    old_segment_id VARCHAR(64) NOT NULL,
    ordinal INT NOT NULL,
    PRIMARY KEY (document_id, new_segment_id, ordinal),
    UNIQUE (document_id, new_segment_id, old_segment_id)
);
COMMENT ON TABLE source_lineage IS '源段双向血缘：新段到旧段的有序来源；拆分时一个旧段按序对应多个新段，合并时一个新段按序来源多个旧段';
COMMENT ON COLUMN source_lineage.document_id IS '所属文档 ID';
COMMENT ON COLUMN source_lineage.new_segment_id IS '结构修订产生的新段 ID';
COMMENT ON COLUMN source_lineage.old_segment_id IS '被取代（SUPERSEDED）的旧段 ID';
COMMENT ON COLUMN source_lineage.ordinal IS '旧来源在新段映射中的顺序，从 1 开始，保证拼接与片段边界有序';

CREATE TABLE IF NOT EXISTS translation_lineage (
    document_id BIGINT NOT NULL,
    new_segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    old_segment_id VARCHAR(64) NOT NULL,
    ordinal INT NOT NULL,
    PRIMARY KEY (document_id, new_segment_id, language, ordinal),
    UNIQUE (document_id, new_segment_id, language, old_segment_id)
);
COMMENT ON TABLE translation_lineage IS '跨语言译文血缘：每个新段、每种目标语言到旧译文片段的有序来源映射';
COMMENT ON COLUMN translation_lineage.document_id IS '所属文档 ID';
COMMENT ON COLUMN translation_lineage.new_segment_id IS '结构修订产生的新段 ID';
COMMENT ON COLUMN translation_lineage.language IS '目标语言码，小写；结构修订要求覆盖文档全部目标语言';
COMMENT ON COLUMN translation_lineage.old_segment_id IS '旧译文所属旧段 ID，必须与源段血缘中的旧段集合一致';
COMMENT ON COLUMN translation_lineage.ordinal IS '旧译文片段在新段候选中的拼接顺序，从 1 开始';

CREATE TABLE IF NOT EXISTS reference_candidate (
    document_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    content LONGTEXT NOT NULL,
    fragment_boundaries VARCHAR(2048) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, segment_id, language)
);
COMMENT ON TABLE reference_candidate IS 'REFERENCE 候选：结构修订后按血缘有序拼接旧译文形成的只读参考内容及片段边界；非译文、不可批准、不可发布';
COMMENT ON COLUMN reference_candidate.document_id IS '所属文档 ID';
COMMENT ON COLUMN reference_candidate.segment_id IS '新段 ID';
COMMENT ON COLUMN reference_candidate.language IS '目标语言码，小写';
COMMENT ON COLUMN reference_candidate.content IS '按 ordinal 顺序拼接的旧译文内容，字符直接拼接';
COMMENT ON COLUMN reference_candidate.fragment_boundaries IS '各片段结束位置（不含）的 JSON 整数数组，按序对应翻译血缘片段';
COMMENT ON COLUMN reference_candidate.created_at IS '候选生成时间，数据库默认时区';

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
