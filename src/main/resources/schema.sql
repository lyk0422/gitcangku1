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
    decision VARCHAR(16) NOT NULL DEFAULT 'APPROVED',
    review_status VARCHAR(16) NOT NULL DEFAULT 'VALID',
    term_version INT NOT NULL DEFAULT 0,
    conflict_declaration_id BIGINT NULL,
    conflicted_at TIMESTAMP(6) NULL,
    approved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (document_id, segment_id, language)
);
COMMENT ON TABLE approval IS '当前评审：按段落与语言唯一；源文或译文版本改变后先前批准不再有效；评审人命中有效利益冲突时标记 CONFLICTED';
COMMENT ON COLUMN approval.document_id IS '所属文档 ID';
COMMENT ON COLUMN approval.segment_id IS '所属段落 ID';
COMMENT ON COLUMN approval.language IS '目标语言码，小写';
COMMENT ON COLUMN approval.reviewer IS '审核人，取批准时 X-Actor-Id，不得是译文作者';
COMMENT ON COLUMN approval.source_version IS '评审时的源文版本，发布校验须等于当前源文版本';
COMMENT ON COLUMN approval.translation_version IS '评审时的译文版本，发布校验须等于当前译文版本';
COMMENT ON COLUMN approval.decision IS '评审决定：APPROVED 批准 / REJECTED 否决；仅 APPROVED 且 VALID 计入法定人数';
COMMENT ON COLUMN approval.review_status IS '评审当前资格状态：VALID 有效 / CONFLICTED 因命中利益冲突被排除；解除冲突不改写本标记，发布时按有效冲突重新计算';
COMMENT ON COLUMN approval.term_version IS '评审提交时文档当前术语版本，作为评审证据固化';
COMMENT ON COLUMN approval.conflict_declaration_id IS '导致评审被标记 CONFLICTED 的冲突声明 ID；VALID 时为 NULL';
COMMENT ON COLUMN approval.conflicted_at IS '评审被标记 CONFLICTED 的 UTC 时间（微秒精度）；VALID 时为 NULL';
COMMENT ON COLUMN approval.approved_at IS '批准时间，数据库默认时区（历史列，保留兼容）';
COMMENT ON COLUMN approval.reviewed_at IS '评审提交时间，UTC 微秒精度';

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

-- 评审利益冲突：声明、生命周期历史、评审证据固化。
-- 所有区间端点为 UTC 时间（微秒精度），按左闭右开解释；声明只增不改，解除以事件方式追加。
CREATE TABLE IF NOT EXISTS conflict_declaration (
    declaration_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    language VARCHAR(16) NOT NULL,
    valid_from TIMESTAMP(6) NOT NULL,
    valid_to TIMESTAMP(6) NOT NULL,
    reason VARCHAR(2048) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    declared_by VARCHAR(128) NOT NULL,
    declared_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    released_at TIMESTAMP(6) NULL,
    released_by VARCHAR(128) NULL
);
COMMENT ON TABLE conflict_declaration IS '评审利益冲突声明：评审人对文档、语种与有效 UTC 区间登记冲突原因；同一评审人同范围重叠声明冲突（409）';
COMMENT ON COLUMN conflict_declaration.declaration_id IS '声明 ID，自增全局唯一';
COMMENT ON COLUMN conflict_declaration.document_id IS '所属文档 ID';
COMMENT ON COLUMN conflict_declaration.reviewer IS '评审人标识，取登记时 X-Actor-Id';
COMMENT ON COLUMN conflict_declaration.language IS '目标语言码，小写；声明仅在该语种范围内生效';
COMMENT ON COLUMN conflict_declaration.valid_from IS '冲突生效起点（含），UTC 微秒精度';
COMMENT ON COLUMN conflict_declaration.valid_to IS '冲突生效终点（不含），UTC 微秒精度，必须严格晚于 valid_from';
COMMENT ON COLUMN conflict_declaration.reason IS '冲突原因，非空';
COMMENT ON COLUMN conflict_declaration.status IS '声明状态：ACTIVE 生效中（按当前 UTC 是否在区间内判定有效）/ RELEASED 已解除';
COMMENT ON COLUMN conflict_declaration.declared_by IS '登记声明的操作者，取登记时 X-Actor-Id';
COMMENT ON COLUMN conflict_declaration.declared_at IS '声明登记时间，UTC 微秒精度';
COMMENT ON COLUMN conflict_declaration.released_at IS '解除生效时间，UTC 微秒精度；未解除为 NULL';
COMMENT ON COLUMN conflict_declaration.released_by IS '解除确认人（合规角色，须不同于声明人）；未解除为 NULL';

CREATE TABLE IF NOT EXISTS conflict_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    declaration_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    language VARCHAR(16) NOT NULL,
    valid_from TIMESTAMP(6) NOT NULL,
    valid_to TIMESTAMP(6) NOT NULL,
    reason VARCHAR(2048) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    actor_role VARCHAR(32) NULL,
    occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
COMMENT ON TABLE conflict_event IS '冲突生命周期历史：DECLARED 登记 / RELEASED 解除，只追加不改写，按事件 ID 升序稳定排序';
COMMENT ON COLUMN conflict_event.event_id IS '事件 ID，自增，同时决定历史顺序';
COMMENT ON COLUMN conflict_event.declaration_id IS '所属冲突声明 ID';
COMMENT ON COLUMN conflict_event.document_id IS '所属文档 ID';
COMMENT ON COLUMN conflict_event.event_type IS '事件类型：DECLARED / RELEASED';
COMMENT ON COLUMN conflict_event.reviewer IS '评审人标识（事件发生时证据）';
COMMENT ON COLUMN conflict_event.language IS '目标语言码（事件发生时证据）';
COMMENT ON COLUMN conflict_event.valid_from IS '声明区间起点（含），UTC 微秒精度（事件发生时证据）';
COMMENT ON COLUMN conflict_event.valid_to IS '声明区间终点（不含），UTC 微秒精度（事件发生时证据）';
COMMENT ON COLUMN conflict_event.reason IS '冲突原因（事件发生时证据）';
COMMENT ON COLUMN conflict_event.actor IS '触发事件的操作者';
COMMENT ON COLUMN conflict_event.actor_role IS '操作者角色；解除事件固定为 COMPLIANCE，登记事件为 NULL';
COMMENT ON COLUMN conflict_event.occurred_at IS '事件发生时间，UTC 微秒精度';

CREATE TABLE IF NOT EXISTS review_history (
    history_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    review_status VARCHAR(16) NOT NULL,
    source_version INT NOT NULL,
    translation_version INT NOT NULL,
    term_version INT NOT NULL,
    conflict_declaration_id BIGINT NULL,
    actor VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
COMMENT ON TABLE review_history IS '评审历史：每次评审提交及每次冲突标记/解除导致的资格变化追加一条，只增不改，按历史 ID 升序稳定排序';
COMMENT ON COLUMN review_history.history_id IS '历史记录 ID，自增，同时决定历史顺序';
COMMENT ON COLUMN review_history.document_id IS '所属文档 ID';
COMMENT ON COLUMN review_history.segment_id IS '所属段落 ID';
COMMENT ON COLUMN review_history.language IS '目标语言码，小写';
COMMENT ON COLUMN review_history.reviewer IS '评审人（事件发生时证据）';
COMMENT ON COLUMN review_history.decision IS '评审决定：APPROVED / REJECTED';
COMMENT ON COLUMN review_history.review_status IS '该事件后的资格状态：VALID / CONFLICTED';
COMMENT ON COLUMN review_history.source_version IS '评审时的源文版本（证据固化）';
COMMENT ON COLUMN review_history.translation_version IS '评审时的译文版本（证据固化）';
COMMENT ON COLUMN review_history.term_version IS '评审时的文档术语版本（证据固化）';
COMMENT ON COLUMN review_history.conflict_declaration_id IS '资格变为 CONFLICTED 时的冲突声明 ID；其余事件为 NULL';
COMMENT ON COLUMN review_history.actor IS '触发事件的操作者：评审提交为评审人，冲突标记为声明登记人，恢复为解除确认人';
COMMENT ON COLUMN review_history.occurred_at IS '事件发生时间，UTC 微秒精度';
