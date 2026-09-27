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

-- 译文引文锚点：锚点依附已批准译文（段落+语言），区间为 Unicode 码点偏移、左闭右开，时间一律 UTC。
-- 区间迁移采用原地更新并由 citation_anchor_event 保存完整历史；解除后行保留且解除字段不可变。
CREATE TABLE IF NOT EXISTS citation_anchor (
    anchor_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    citation_key VARCHAR(512) NOT NULL,
    citation_key_raw VARCHAR(512) NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    quote_text LONGTEXT NOT NULL,
    translation_version INT NOT NULL,
    lock_reason VARCHAR(2048) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    migrated_by VARCHAR(128),
    migrated_at TIMESTAMP(6),
    released_by VARCHAR(128),
    released_reason LONGTEXT,
    released_at TIMESTAMP(6)
);
COMMENT ON TABLE citation_anchor IS '引文锚点：段落+语言内锁定引用文本；同一规范化引用标识在同一段落+语言只能锁定一次（含已解除行）';
COMMENT ON COLUMN citation_anchor.anchor_id IS '锚点 ID，自增，迁移中保持不变';
COMMENT ON COLUMN citation_anchor.document_id IS '所属文档 ID';
COMMENT ON COLUMN citation_anchor.segment_id IS '所属段落 ID';
COMMENT ON COLUMN citation_anchor.language IS '目标语言码，小写';
COMMENT ON COLUMN citation_anchor.citation_key IS '规范化引用标识：NFKC、去空白、小写；同段落+语言全局唯一';
COMMENT ON COLUMN citation_anchor.citation_key_raw IS '登记时提交的原始引用标识，历史原值不改写';
COMMENT ON COLUMN citation_anchor.start_offset IS '引用文本起始码点偏移，从 0 开始，闭区间端';
COMMENT ON COLUMN citation_anchor.end_offset IS '引用文本结束码点偏移，开区间端，等于内容码点长度时表示取到末尾';
COMMENT ON COLUMN citation_anchor.quote_text IS '锁定的引用文本，登记时由服务端按区间截取，迁移区间时文本必须保持一致';
COMMENT ON COLUMN citation_anchor.translation_version IS '区间当前所在的译文版本；译文修订后随迁移或原样保留更新到新译文版本';
COMMENT ON COLUMN citation_anchor.lock_reason IS '登记时的锁定原因，不可变';
COMMENT ON COLUMN citation_anchor.created_by IS '创建人（登记锚点的 X-Actor-Id），迁移保留原创建人';
COMMENT ON COLUMN citation_anchor.created_at IS '登记时间，UTC，微秒精度';
COMMENT ON COLUMN citation_anchor.status IS '状态：ACTIVE 生效中；RELEASED 已由法务解除（终态，不可变）';
COMMENT ON COLUMN citation_anchor.migrated_by IS '最近一次区间迁移操作人（修订提交者）；未迁移为 NULL';
COMMENT ON COLUMN citation_anchor.migrated_at IS '最近一次区间迁移时间，UTC；未迁移为 NULL';
COMMENT ON COLUMN citation_anchor.released_by IS '解除确认人，法务角色且不同于创建人；未解除为 NULL';
COMMENT ON COLUMN citation_anchor.released_reason IS '解除时写入的不可变理由；未解除为 NULL';
COMMENT ON COLUMN citation_anchor.released_at IS '解除时间，UTC；未解除为 NULL';

CREATE UNIQUE INDEX IF NOT EXISTS ux_citation_anchor_key
    ON citation_anchor (document_id, segment_id, language, citation_key);
CREATE INDEX IF NOT EXISTS ix_citation_anchor_doc
    ON citation_anchor (document_id, segment_id, language, start_offset);

CREATE TABLE IF NOT EXISTS citation_anchor_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    anchor_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    translation_version INT NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    actor VARCHAR(128) NOT NULL,
    reason LONGTEXT,
    event_at TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE citation_anchor_event IS '引文锚点事件历史：登记、区间迁移、解除均追加不可变事件，保证旧快照外仍可追溯';
COMMENT ON COLUMN citation_anchor_event.event_id IS '事件 ID，自增，即历史排序';
COMMENT ON COLUMN citation_anchor_event.anchor_id IS '关联锚点 ID';
COMMENT ON COLUMN citation_anchor_event.document_id IS '所属文档 ID';
COMMENT ON COLUMN citation_anchor_event.segment_id IS '所属段落 ID';
COMMENT ON COLUMN citation_anchor_event.language IS '目标语言码，小写';
COMMENT ON COLUMN citation_anchor_event.event_type IS '事件类型：REGISTERED 登记、MIGRATED 区间迁移、RELEASED 解除';
COMMENT ON COLUMN citation_anchor_event.translation_version IS '事件发生时锚点区间所在的译文版本';
COMMENT ON COLUMN citation_anchor_event.start_offset IS '该事件下区间起始码点偏移，闭区间端';
COMMENT ON COLUMN citation_anchor_event.end_offset IS '该事件下区间结束码点偏移，开区间端';
COMMENT ON COLUMN citation_anchor_event.actor IS '事件操作人 X-Actor-Id';
COMMENT ON COLUMN citation_anchor_event.reason IS '登记/迁移时为锁定原因，解除时为不可变解除理由，迁移事件可为 NULL';
COMMENT ON COLUMN citation_anchor_event.event_at IS '事件时间，UTC，微秒精度';

CREATE TABLE IF NOT EXISTS release_anchor_ref (
    document_id BIGINT NOT NULL,
    published_version INT NOT NULL,
    anchor_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    citation_key VARCHAR(512) NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    translation_version INT NOT NULL,
    PRIMARY KEY (document_id, published_version, anchor_id)
);
COMMENT ON TABLE release_anchor_ref IS '发布快照锚点固化：发布时写入，之后锚点迁移/解除均不改写，支撑已发布锚点追溯';
COMMENT ON COLUMN release_anchor_ref.document_id IS '所属文档 ID';
COMMENT ON COLUMN release_anchor_ref.published_version IS '发布版本号，从 1 开始';
COMMENT ON COLUMN release_anchor_ref.anchor_id IS '被固化的锚点 ID';
COMMENT ON COLUMN release_anchor_ref.segment_id IS '所属段落 ID';
COMMENT ON COLUMN release_anchor_ref.language IS '目标语言码，小写';
COMMENT ON COLUMN release_anchor_ref.citation_key IS '发布时的规范化引用标识';
COMMENT ON COLUMN release_anchor_ref.start_offset IS '发布时区间起始码点偏移，闭区间端';
COMMENT ON COLUMN release_anchor_ref.end_offset IS '发布时区间结束码点偏移，开区间端';
COMMENT ON COLUMN release_anchor_ref.translation_version IS '发布时锚点所在译文版本';
