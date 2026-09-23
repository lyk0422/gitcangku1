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

CREATE TABLE IF NOT EXISTS review_policy (
    document_id BIGINT NOT NULL,
    language VARCHAR(16) NOT NULL,
    policy_version INT NOT NULL,
    language_reviewers LONGTEXT NOT NULL,
    language_quorum INT NOT NULL,
    compliance_reviewers LONGTEXT NOT NULL,
    compliance_quorum INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, language, policy_version)
);
COMMENT ON TABLE review_policy IS '双阶段评审策略版本：按文档与语言版本化，不可变；记录 LANGUAGE 与 COMPLIANCE 两阶段候选审核人集合与法定人数';
COMMENT ON COLUMN review_policy.document_id IS '所属文档 ID';
COMMENT ON COLUMN review_policy.language IS '目标语言码，小写';
COMMENT ON COLUMN review_policy.policy_version IS '该语言的策略版本，从 1 开始单调递增，切换策略即新增版本';
COMMENT ON COLUMN review_policy.language_reviewers IS '语言阶段候选审核人集合，逗号分隔；与合规阶段集合可重叠';
COMMENT ON COLUMN review_policy.language_quorum IS '语言阶段通过所需 APPROVE 人数，不小于 1 且不超过候选人数';
COMMENT ON COLUMN review_policy.compliance_reviewers IS '合规阶段候选审核人集合，逗号分隔；与语言阶段集合可重叠';
COMMENT ON COLUMN review_policy.compliance_quorum IS '合规阶段通过所需 APPROVE 人数，不小于 1 且不超过候选人数';
COMMENT ON COLUMN review_policy.created_at IS '策略版本创建时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS review_policy_active (
    document_id BIGINT NOT NULL,
    language VARCHAR(16) NOT NULL,
    policy_version INT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, language)
);
COMMENT ON TABLE review_policy_active IS '各语言当前激活的评审策略版本指针；与策略版本在同一事务切换，仅作用于新投票';
COMMENT ON COLUMN review_policy_active.document_id IS '所属文档 ID';
COMMENT ON COLUMN review_policy_active.language IS '目标语言码，小写';
COMMENT ON COLUMN review_policy_active.policy_version IS '当前激活的策略版本；旧策略下投出的票保留审计但不计入当前法定人数';
COMMENT ON COLUMN review_policy_active.updated_at IS '最近切换时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS review_vote (
    vote_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vote_key VARCHAR(128) NOT NULL,
    document_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    stage VARCHAR(16) NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    source_version INT NOT NULL,
    translation_version INT NOT NULL,
    term_version INT NOT NULL,
    policy_version INT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    vote_version INT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_review_vote_key UNIQUE (vote_key)
);
COMMENT ON TABLE review_vote IS '评审票：审核人对精确四版本投 APPROVE/REJECT；历史票全部保留审计，仅匹配当前版本与激活策略的票计入法定人数';
COMMENT ON COLUMN review_vote.vote_id IS '票记录自增主键';
COMMENT ON COLUMN review_vote.vote_key IS '调用方提供的票唯一键，全局唯一；换新请求复用已存在 vote_key 返回 409';
COMMENT ON COLUMN review_vote.document_id IS '所属文档 ID';
COMMENT ON COLUMN review_vote.segment_id IS '所属段落 ID';
COMMENT ON COLUMN review_vote.language IS '目标语言码，小写';
COMMENT ON COLUMN review_vote.stage IS '评审阶段：LANGUAGE（语言）或 COMPLIANCE（合规）';
COMMENT ON COLUMN review_vote.reviewer IS '投票审核人，取 X-Actor-Id，须在该阶段候选集合中';
COMMENT ON COLUMN review_vote.source_version IS '投票针对的精确源文版本';
COMMENT ON COLUMN review_vote.translation_version IS '投票针对的精确译文版本';
COMMENT ON COLUMN review_vote.term_version IS '投票针对的精确术语版本';
COMMENT ON COLUMN review_vote.policy_version IS '投票时激活的策略版本；策略切换后旧票不计入当前法定人数';
COMMENT ON COLUMN review_vote.decision IS '票决定：APPROVE 或 REJECT';
COMMENT ON COLUMN review_vote.vote_version IS '同一段落/语言/阶段/审核人票序列内单调递增的票版本；改票生成新版本';
COMMENT ON COLUMN review_vote.request_id IS '产生该票的写操作 requestId';
COMMENT ON COLUMN review_vote.created_at IS '投票时间，数据库默认时区';

CREATE INDEX IF NOT EXISTS idx_review_vote_target
    ON review_vote (document_id, segment_id, language);
CREATE INDEX IF NOT EXISTS idx_review_vote_series
    ON review_vote (document_id, segment_id, language, stage, reviewer, vote_version);

CREATE TABLE IF NOT EXISTS release_vote_freeze (
    document_id BIGINT NOT NULL,
    published_version INT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    stage VARCHAR(16) NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    vote_id BIGINT NOT NULL,
    vote_version INT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    source_version INT NOT NULL,
    translation_version INT NOT NULL,
    term_version INT NOT NULL,
    policy_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, published_version, segment_id, language, stage, reviewer)
);
COMMENT ON TABLE release_vote_freeze IS '发布冻结的票版本集合：发布成功时与快照同事务固化两阶段采用的票，事后投票或改票不影响已发布快照';
COMMENT ON COLUMN release_vote_freeze.document_id IS '所属文档 ID';
COMMENT ON COLUMN release_vote_freeze.published_version IS '对应的发布版本号，从 1 开始';
COMMENT ON COLUMN release_vote_freeze.segment_id IS '所属段落 ID';
COMMENT ON COLUMN release_vote_freeze.language IS '目标语言码，小写';
COMMENT ON COLUMN release_vote_freeze.stage IS '评审阶段：LANGUAGE 或 COMPLIANCE';
COMMENT ON COLUMN release_vote_freeze.reviewer IS '投票审核人';
COMMENT ON COLUMN release_vote_freeze.vote_id IS '被采用的 review_vote 主键';
COMMENT ON COLUMN release_vote_freeze.vote_version IS '被采用的票版本';
COMMENT ON COLUMN release_vote_freeze.decision IS '被采用的票决定，冻结时必为 APPROVE';
COMMENT ON COLUMN release_vote_freeze.source_version IS '票采用的源文版本，发布时与当前源文版本一致';
COMMENT ON COLUMN release_vote_freeze.translation_version IS '票采用的译文版本，发布时与当前译文版本一致';
COMMENT ON COLUMN release_vote_freeze.term_version IS '票采用的术语版本，发布时与当前术语版本一致';
COMMENT ON COLUMN release_vote_freeze.policy_version IS '票采用的策略版本，发布时为该语言激活策略版本';
COMMENT ON COLUMN release_vote_freeze.created_at IS '冻结时间，数据库默认时区';

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
