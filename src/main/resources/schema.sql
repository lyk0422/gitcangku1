-- 多语种段落修订、术语版本、评审利益冲突与发布快照 schema（H2 MySQL 兼容模式）。
-- 业务版本号均从 1（文档发布版本从 0）开始单调递增。
-- created_at 等数据库默认时间列使用数据库默认时区；冲突与评审的业务时间一律由应用按 UTC 写入，精度毫秒。

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
    review_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    decision_reason VARCHAR(1024) NULL,
    review_status VARCHAR(16) NOT NULL,
    conflict_id BIGINT NULL,
    source_version INT NOT NULL,
    translation_version INT NOT NULL,
    reviewed_at TIMESTAMP NOT NULL,
    approved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_approval_document ON approval (document_id);
COMMENT ON TABLE approval IS '评审（只追加证据）：同一评审人每次提交产生新 review_id 行；decision 为 APPROVED/REJECTED，review_status 为 ACTIVE/CONFLICTED；法定人数按评审人最新行且版本匹配计算';
COMMENT ON COLUMN approval.review_id IS '评审记录 ID，自增，同一段落语种评审人越大越新；行只追加，后续操作不得改写旧行证据';
COMMENT ON COLUMN approval.document_id IS '所属文档 ID';
COMMENT ON COLUMN approval.segment_id IS '所属段落 ID';
COMMENT ON COLUMN approval.language IS '目标语言码，小写';
COMMENT ON COLUMN approval.reviewer IS '评审人，取提交时 X-Actor-Id，不得是译文作者';
COMMENT ON COLUMN approval.decision IS '评审决定：APPROVED 批准、REJECTED 否决；以最新一次提交为准';
COMMENT ON COLUMN approval.decision_reason IS '否决原因（仅 REJECTED 非空），评审提交时固化，不随后续操作改写；批准为 NULL';
COMMENT ON COLUMN approval.review_status IS '评审状态：ACTIVE 计入法定人数；CONFLICTED 因评审人有效利益冲突被排除，登记冲突时固化，解除冲突不自动恢复';
COMMENT ON COLUMN approval.conflict_id IS '置为 CONFLICTED 时固化的冲突声明 ID；ACTIVE 评审为 NULL';
COMMENT ON COLUMN approval.source_version IS '评审时的源文版本，发布校验须等于当前源文版本';
COMMENT ON COLUMN approval.translation_version IS '评审时的译文版本，发布校验须等于当前译文版本';
COMMENT ON COLUMN approval.reviewed_at IS '评审提交时间，UTC，应用写入，毫秒精度';
COMMENT ON COLUMN approval.approved_at IS '最近评审时间，数据库默认时区（兼容列）';

CREATE TABLE IF NOT EXISTS conflict_declaration (
    conflict_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    language VARCHAR(16) NOT NULL,
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    conflict_status VARCHAR(16) NOT NULL,
    declared_by VARCHAR(128) NOT NULL,
    declared_at TIMESTAMP NOT NULL,
    resolved_by VARCHAR(128) NULL,
    resolved_at TIMESTAMP NULL,
    resolve_reason VARCHAR(1024) NULL
);
COMMENT ON TABLE conflict_declaration IS '评审利益冲突声明：评审人对文档与语种声明左闭右开 UTC 有效区间及原因；ACTIVE 期间该评审人不得提交该语种评审，解除后只影响后续操作';
COMMENT ON COLUMN conflict_declaration.conflict_id IS '冲突声明 ID，自增，全局唯一';
COMMENT ON COLUMN conflict_declaration.document_id IS '所属文档 ID';
COMMENT ON COLUMN conflict_declaration.reviewer IS '存在利益冲突的评审人';
COMMENT ON COLUMN conflict_declaration.language IS '受限语种，小写语言码，须为文档目标语言之一；约束该文档该语种全部段落';
COMMENT ON COLUMN conflict_declaration.valid_from IS '有效区间起点（含），UTC，毫秒精度，必须严格早于 valid_to';
COMMENT ON COLUMN conflict_declaration.valid_to IS '有效区间终点（不含），UTC，毫秒精度，左闭右开';
COMMENT ON COLUMN conflict_declaration.reason IS '冲突原因，非空，最长 1024 字符，登记后不可修改';
COMMENT ON COLUMN conflict_declaration.conflict_status IS '声明状态：ACTIVE 有效中（含区间判定）、RESOLVED 已由合规角色解除';
COMMENT ON COLUMN conflict_declaration.declared_by IS '声明登记操作人，取登记时 X-Actor-Id';
COMMENT ON COLUMN conflict_declaration.declared_at IS '声明登记时间，UTC，应用写入，毫秒精度';
COMMENT ON COLUMN conflict_declaration.resolved_by IS '解除确认人（合规角色且不同于声明人），未解除为 NULL';
COMMENT ON COLUMN conflict_declaration.resolved_at IS '解除时间 UTC，未解除为 NULL';
COMMENT ON COLUMN conflict_declaration.resolve_reason IS '解除原因，解除时追加，未解除为 NULL';

CREATE INDEX IF NOT EXISTS idx_conflict_document ON conflict_declaration (document_id);

CREATE TABLE IF NOT EXISTS conflict_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conflict_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE conflict_event IS '冲突历史事件：只追加不可修改，按 event_id 排序；DECLARED 为登记，RESOLVED 为合规解除';
COMMENT ON COLUMN conflict_event.event_id IS '历史事件 ID，自增，按其排序保证稳定输出';
COMMENT ON COLUMN conflict_event.conflict_id IS '所属冲突声明 ID';
COMMENT ON COLUMN conflict_event.document_id IS '所属文档 ID';
COMMENT ON COLUMN conflict_event.event_type IS '事件类型：DECLARED 登记、RESOLVED 解除';
COMMENT ON COLUMN conflict_event.actor IS '事件操作人，取 X-Actor-Id';
COMMENT ON COLUMN conflict_event.actor_role IS '事件操作人角色：REVIEWER 声明人、COMPLIANCE 合规解除人';
COMMENT ON COLUMN conflict_event.valid_from IS '事件发生时固化的冲突区间起点 UTC（含）';
COMMENT ON COLUMN conflict_event.valid_to IS '事件发生时固化的冲突区间终点 UTC（不含）';
COMMENT ON COLUMN conflict_event.reason IS '事件原因：登记原因为冲突原因，解除原因为解除说明';
COMMENT ON COLUMN conflict_event.created_at IS '事件追加时间，UTC，应用写入，毫秒精度';

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
COMMENT ON COLUMN release_snapshot.snapshot_json IS '快照内容 JSON：全部段落源文及各语言译文、作者、评审（含决定与冲突状态）、冲突声明（区间与原因）与版本号';
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
