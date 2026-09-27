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
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    conflict_id BIGINT NULL,
    conflicted_at TIMESTAMP NULL,
    approved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, segment_id, language)
);
COMMENT ON TABLE approval IS '批准：按段落与语言唯一；源文或译文版本改变后先前批准不再有效；命中有效利益冲突的批准标记 CONFLICTED 并排除出法定人数';
COMMENT ON COLUMN approval.document_id IS '所属文档 ID';
COMMENT ON COLUMN approval.segment_id IS '所属段落 ID';
COMMENT ON COLUMN approval.language IS '目标语言码，小写';
COMMENT ON COLUMN approval.reviewer IS '审核人，取批准时 X-Actor-Id，不得是译文作者';
COMMENT ON COLUMN approval.source_version IS '批准时的源文版本，发布校验须等于当前源文版本';
COMMENT ON COLUMN approval.translation_version IS '批准时的译文版本，发布校验须等于当前译文版本';
COMMENT ON COLUMN approval.status IS '评审状态：ACTIVE 有效；CONFLICTED 因命中有效利益冲突被排除出法定人数';
COMMENT ON COLUMN approval.conflict_id IS '导致排除的利益冲突声明 ID；status 为 ACTIVE 时为 NULL';
COMMENT ON COLUMN approval.conflicted_at IS '被标记 CONFLICTED 的时间（UTC，秒精度）；status 为 ACTIVE 时为 NULL';
COMMENT ON COLUMN approval.approved_at IS '批准时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS reviewer_conflict (
    conflict_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    language VARCHAR(16) NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    declared_by VARCHAR(128) NOT NULL,
    declared_at TIMESTAMP NOT NULL,
    lifted_by VARCHAR(128) NULL,
    lifted_at TIMESTAMP NULL,
    lift_reason VARCHAR(1024) NULL
);
COMMENT ON TABLE reviewer_conflict IS '评审利益冲突声明：评审人对文档与语种登记的有效 UTC 区间（左闭右开）冲突原因，解除后仅影响后续评审与发布';
COMMENT ON COLUMN reviewer_conflict.conflict_id IS '全局唯一冲突声明 ID，自增';
COMMENT ON COLUMN reviewer_conflict.document_id IS '所属文档 ID';
COMMENT ON COLUMN reviewer_conflict.reviewer IS '被声明存在利益冲突的评审人';
COMMENT ON COLUMN reviewer_conflict.language IS '冲突范围目标语言码，小写，须在文档目标语言中';
COMMENT ON COLUMN reviewer_conflict.reason IS '冲突原因，非空，登记后不可改写';
COMMENT ON COLUMN reviewer_conflict.valid_from IS '有效区间起点（UTC，秒精度，左闭）';
COMMENT ON COLUMN reviewer_conflict.valid_to IS '有效区间终点（UTC，秒精度，右开），必须大于 valid_from';
COMMENT ON COLUMN reviewer_conflict.status IS '声明状态：ACTIVE 有效；LIFTED 已解除，解除只影响后续评审与发布';
COMMENT ON COLUMN reviewer_conflict.declared_by IS '声明人，取登记时 X-Actor-Id';
COMMENT ON COLUMN reviewer_conflict.declared_at IS '登记时间（UTC，秒精度）';
COMMENT ON COLUMN reviewer_conflict.lifted_at IS '解除确认时间（UTC，秒精度）；未解除为 NULL';
COMMENT ON COLUMN reviewer_conflict.lifted_by IS '解除确认人（合规角色，须不同于声明人）；未解除为 NULL';
COMMENT ON COLUMN reviewer_conflict.lift_reason IS '解除原因；未解除为 NULL';

CREATE TABLE IF NOT EXISTS reviewer_conflict_history (
    history_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conflict_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP NOT NULL,
    recorded_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE reviewer_conflict_history IS '冲突声明历史：登记与解除的追加式记录，字段、排序与时间精度固定，后续操作不得改写';
COMMENT ON COLUMN reviewer_conflict_history.history_id IS '全局唯一历史记录 ID，自增，即历史排序';
COMMENT ON COLUMN reviewer_conflict_history.conflict_id IS '所属冲突声明 ID';
COMMENT ON COLUMN reviewer_conflict_history.document_id IS '所属文档 ID';
COMMENT ON COLUMN reviewer_conflict_history.action IS '动作：DECLARED 登记；LIFTED 解除';
COMMENT ON COLUMN reviewer_conflict_history.actor IS '操作人：登记为声明人，解除为合规确认人';
COMMENT ON COLUMN reviewer_conflict_history.reason IS '当时的原因：登记为冲突原因，解除为解除原因';
COMMENT ON COLUMN reviewer_conflict_history.valid_from IS '登记时固化的有效区间起点（UTC，秒精度，左闭）';
COMMENT ON COLUMN reviewer_conflict_history.valid_to IS '登记时固化的有效区间终点（UTC，秒精度，右开）';
COMMENT ON COLUMN reviewer_conflict_history.recorded_at IS '记录时间（UTC，秒精度）';

CREATE TABLE IF NOT EXISTS review_exclusion (
    exclusion_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    source_version INT NOT NULL,
    translation_version INT NOT NULL,
    conflict_id BIGINT NOT NULL,
    excluded_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE review_exclusion IS '评审资格排除记录：批准被标记 CONFLICTED 时追加的只增证据，固化当时版本与导致排除的冲突声明';
COMMENT ON COLUMN review_exclusion.exclusion_id IS '全局唯一排除记录 ID，自增';
COMMENT ON COLUMN review_exclusion.document_id IS '所属文档 ID';
COMMENT ON COLUMN review_exclusion.segment_id IS '被排除评审所属段落 ID';
COMMENT ON COLUMN review_exclusion.language IS '被排除评审的目标语言码，小写';
COMMENT ON COLUMN review_exclusion.reviewer IS '被排除的评审人';
COMMENT ON COLUMN review_exclusion.source_version IS '被排除评审当时的源文版本';
COMMENT ON COLUMN review_exclusion.translation_version IS '被排除评审当时的译文版本';
COMMENT ON COLUMN review_exclusion.conflict_id IS '导致排除的冲突声明 ID，区间与原因以该声明为准';
COMMENT ON COLUMN review_exclusion.excluded_at IS '排除时间（UTC，秒精度）';

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
