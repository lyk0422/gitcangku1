-- 多语种段落修订、评审利益冲突与发布快照 schema（H2 MySQL 兼容模式）。
-- 业务时间（冲突区间、声明/解除时刻）统一为 UTC，以毫秒精度 BIGINT 存储，区间左闭右开。
-- 其余 created_at/updated_at/reviewed_at 为数据库默认时区时间戳；版本号均从 1（文档发布版本从 0）开始单调递增。

CREATE TABLE IF NOT EXISTS document (
    document_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_languages VARCHAR(255) NOT NULL,
    required_review_count INT NOT NULL,
    draft_version INT NOT NULL,
    published_version INT NOT NULL,
    term_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE document IS '文档：全局唯一 documentId，含 1~5 种目标语言、法定评审人数及草稿/发布/术语版本';
COMMENT ON COLUMN document.document_id IS '全局唯一文档 ID，自增';
COMMENT ON COLUMN document.target_languages IS '目标语言列表，逗号分隔的小写语言码，1~5 种';
COMMENT ON COLUMN document.required_review_count IS '每个译文当前版本所需的非冲突有效批准数（法定人数），1~5，建文档时给定，缺省 1';
COMMENT ON COLUMN document.draft_version IS '文档草稿版本，从 1 开始；增段落或修改源文/译文/术语时加一（冲突声明/解除与评审提交不改变草稿版本）';
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

CREATE TABLE IF NOT EXISTS review (
    document_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reason VARCHAR(1024),
    source_version INT NOT NULL,
    translation_version INT NOT NULL,
    conflict_id BIGINT,
    conflict_reason VARCHAR(1024),
    conflict_valid_from BIGINT,
    conflict_valid_to BIGINT,
    reviewed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, segment_id, language, reviewer)
);
COMMENT ON TABLE review IS '评审：同一译文可被多名评审人各提交一行（批准或否决）；命中有效利益冲突时状态为 CONFLICTED 并固化排除证据';
COMMENT ON COLUMN review.document_id IS '所属文档 ID';
COMMENT ON COLUMN review.segment_id IS '所属段落 ID';
COMMENT ON COLUMN review.language IS '目标语言码，小写';
COMMENT ON COLUMN review.reviewer IS '评审人，取提交时 X-Actor-Id，不得是译文作者';
COMMENT ON COLUMN review.decision IS '评审决定：APPROVED 批准 / REJECTED 否决';
COMMENT ON COLUMN review.status IS '评审状态：ACTIVE 有效 / CONFLICTED 因评审人命中当前有效冲突而被排除；解除或过期后经发布/解除重算为 ACTIVE，证据列保留';
COMMENT ON COLUMN review.reason IS '否决说明原文，批准为 null；随评审行与发布快照固化，重新提交评审时以最新说明覆盖';
COMMENT ON COLUMN review.source_version IS '评审时的源文版本；发布校验须等于当前源文版本，否则该评审不计入法定人数';
COMMENT ON COLUMN review.translation_version IS '评审时的译文版本；发布校验须等于当前译文版本，否则该评审不计入法定人数';
COMMENT ON COLUMN review.conflict_id IS '最近一次排除该行的冲突声明 ID，null 表示从未因冲突被排除；解除冲突也不改写，作为历史证据';
COMMENT ON COLUMN review.conflict_reason IS '排除时固化的冲突原因（UTC 登记原文），null 表示从未被排除';
COMMENT ON COLUMN review.conflict_valid_from IS '排除时固化的冲突区间起点，UTC 毫秒时间戳，左闭；null 表示从未被排除';
COMMENT ON COLUMN review.conflict_valid_to IS '排除时固化的冲突区间终点，UTC 毫秒时间戳，右开；null 表示从未被排除';
COMMENT ON COLUMN review.reviewed_at IS '评审提交时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS reviewer_conflict (
    conflict_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    language VARCHAR(16) NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    valid_from BIGINT NOT NULL,
    valid_to BIGINT NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    status VARCHAR(16) NOT NULL,
    declared_by VARCHAR(128) NOT NULL,
    declared_at BIGINT NOT NULL,
    lifted_by VARCHAR(128),
    lifted_at BIGINT,
    lift_reason VARCHAR(1024)
);
COMMENT ON TABLE reviewer_conflict IS '评审利益冲突声明：评审人对文档内某语种的 UTC 左闭右开有效区间及原因；解除后状态 LIFTED 且声明字段不可改写';
COMMENT ON COLUMN reviewer_conflict.conflict_id IS '冲突声明 ID，自增';
COMMENT ON COLUMN reviewer_conflict.document_id IS '所属文档 ID';
COMMENT ON COLUMN reviewer_conflict.language IS '受限目标语言码，小写；命中期间评审人不得对该语种译文提交批准或否决';
COMMENT ON COLUMN reviewer_conflict.reviewer IS '受限评审人，取声明时 X-Actor-Id';
COMMENT ON COLUMN reviewer_conflict.valid_from IS '冲突区间起点，UTC 毫秒时间戳，左闭';
COMMENT ON COLUMN reviewer_conflict.valid_to IS '冲突区间终点，UTC 毫秒时间戳，右开，必须晚于 valid_from';
COMMENT ON COLUMN reviewer_conflict.reason IS '冲突原因，非空，登记原文，解除不改写';
COMMENT ON COLUMN reviewer_conflict.status IS '声明状态：DECLARED 有效声明 / LIFTED 已由合规角色解除';
COMMENT ON COLUMN reviewer_conflict.declared_by IS '声明人 X-Actor-Id';
COMMENT ON COLUMN reviewer_conflict.declared_at IS '声明提交时刻，UTC 毫秒时间戳';
COMMENT ON COLUMN reviewer_conflict.lifted_by IS '解除确认人 X-Actor-Id，须为不同于声明人的合规角色；null 表示尚未解除';
COMMENT ON COLUMN reviewer_conflict.lifted_at IS '解除时刻，UTC 毫秒时间戳；null 表示尚未解除';
COMMENT ON COLUMN reviewer_conflict.lift_reason IS '解除说明，可空；null 表示尚未解除或解除未附说明';

CREATE INDEX idx_reviewer_conflict_scope ON reviewer_conflict (document_id, reviewer, language);

CREATE TABLE IF NOT EXISTS conflict_history (
    history_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conflict_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    valid_from BIGINT NOT NULL,
    valid_to BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);
COMMENT ON TABLE conflict_history IS '冲突历史：声明与解除均只追加，固化操作人、角色、原因与区间，任何后续操作不得改写';
COMMENT ON COLUMN conflict_history.history_id IS '历史记录 ID，自增，排序辅助';
COMMENT ON COLUMN conflict_history.conflict_id IS '关联的冲突声明 ID';
COMMENT ON COLUMN conflict_history.document_id IS '所属文档 ID';
COMMENT ON COLUMN conflict_history.action IS '历史动作：DECLARED 声明 / LIFTED 解除';
COMMENT ON COLUMN conflict_history.actor IS '操作人 X-Actor-Id；解除人与声明人不同';
COMMENT ON COLUMN conflict_history.actor_role IS '操作人角色，取 X-Actor-Role；解除须为 COMPLIANCE';
COMMENT ON COLUMN conflict_history.reason IS '该动作的原因/说明原文';
COMMENT ON COLUMN conflict_history.valid_from IS '关联冲突区间起点，UTC 毫秒时间戳，左闭';
COMMENT ON COLUMN conflict_history.valid_to IS '关联冲突区间终点，UTC 毫秒时间戳，右开';
COMMENT ON COLUMN conflict_history.created_at IS '历史记录追加时刻，UTC 毫秒时间戳';

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
COMMENT ON COLUMN release_snapshot.snapshot_json IS '快照内容 JSON：全部段落源文及各语言译文、作者、评审明细（含决定/冲突状态/排除证据）、法定人数、版本号与固化术语';
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
