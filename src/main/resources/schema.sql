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

-- 发布撤回：状态固化在发布快照行上，撤回不改写 snapshot_json 证据；时间列为 UTC、毫秒精度。
ALTER TABLE release_snapshot ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED';
ALTER TABLE release_snapshot ADD COLUMN IF NOT EXISTS withdrawn_at TIMESTAMP(3);
ALTER TABLE release_snapshot ADD COLUMN IF NOT EXISTS withdrawn_by VARCHAR(128);
ALTER TABLE release_snapshot ADD COLUMN IF NOT EXISTS withdraw_reason VARCHAR(512);
COMMENT ON COLUMN release_snapshot.status IS '发布状态：PUBLISHED 有效；WITHDRAWN 已撤回，撤回后不能新建归档包但证据保留';
COMMENT ON COLUMN release_snapshot.withdrawn_at IS '撤回时刻，UTC、毫秒精度；null 表示未撤回';
COMMENT ON COLUMN release_snapshot.withdrawn_by IS '撤回操作者，取 X-Actor-Id；null 表示未撤回';
COMMENT ON COLUMN release_snapshot.withdraw_reason IS '撤回原因，UTF-8，非空；null 表示未撤回';

CREATE TABLE IF NOT EXISTS archive_package (
    archive_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    archive_version INT NOT NULL,
    published_version INT NOT NULL,
    target_languages VARCHAR(255) NOT NULL,
    term_version INT NOT NULL,
    segment_count INT NOT NULL,
    language_count INT NOT NULL,
    approval_count INT NOT NULL,
    evidence_json LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    revoked_at TIMESTAMP(3),
    revoked_by VARCHAR(128),
    revoke_reason VARCHAR(512),
    created_at TIMESTAMP(3) NOT NULL,
    UNIQUE (document_id, archive_version)
);
COMMENT ON TABLE archive_package IS '译文归档包：生成后不可变，固化规范排序语种、逐段译文版本、术语版本、审批记录与发布快照摘要';
COMMENT ON COLUMN archive_package.archive_id IS '全局唯一归档包 ID，自增';
COMMENT ON COLUMN archive_package.document_id IS '所属文档 ID';
COMMENT ON COLUMN archive_package.archive_version IS '文档内归档包序号，从 1 开始单调递增';
COMMENT ON COLUMN archive_package.published_version IS '归档包引用的发布版本号；一个包只能引用同一发布版本';
COMMENT ON COLUMN archive_package.target_languages IS '规范排序（小写、字典序）的目标语言码，逗号分隔';
COMMENT ON COLUMN archive_package.term_version IS '归档固化的术语版本，取自发布快照；术语退役不改写本值';
COMMENT ON COLUMN archive_package.segment_count IS '每语种固化的段落数（实际值）';
COMMENT ON COLUMN archive_package.language_count IS '固化的目标语种数（要求值）';
COMMENT ON COLUMN archive_package.approval_count IS '固化的审批记录条数 = 段数 × 语种数';
COMMENT ON COLUMN archive_package.evidence_json IS '归档证据 JSON，生成后任何操作不得改写';
COMMENT ON COLUMN archive_package.status IS '访问状态：ACTIVE 可查明细；REVOKED 已撤销，明细 410 但证据保留';
COMMENT ON COLUMN archive_package.revoked_at IS '撤销时刻，UTC、毫秒精度；null 表示未撤销';
COMMENT ON COLUMN archive_package.revoked_by IS '撤销操作者，取 X-Actor-Id；null 表示未撤销';
COMMENT ON COLUMN archive_package.revoke_reason IS '撤销原因，UTF-8，非空；null 表示未撤销';
COMMENT ON COLUMN archive_package.created_at IS '归档包生成时刻，UTC、毫秒精度，生成后不可变';

CREATE TABLE IF NOT EXISTS archive_segment (
    archive_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    translation_version INT NOT NULL,
    source_version INT NOT NULL,
    term_version INT NOT NULL,
    PRIMARY KEY (archive_id, segment_id, language)
);
COMMENT ON TABLE archive_segment IS '归档逐段译文版本证据：按包、段落、语种唯一，与归档包同一事务写入，失败不留半成品';
COMMENT ON COLUMN archive_segment.archive_id IS '所属归档包 ID';
COMMENT ON COLUMN archive_segment.document_id IS '所属文档 ID';
COMMENT ON COLUMN archive_segment.segment_id IS '段落 ID';
COMMENT ON COLUMN archive_segment.language IS '目标语言码，小写';
COMMENT ON COLUMN archive_segment.translation_version IS '归档时该段该语种的译文版本（固化证据）';
COMMENT ON COLUMN archive_segment.source_version IS '归档时该段源文版本，须与译文依据源文版本一致';
COMMENT ON COLUMN archive_segment.term_version IS '归档时译文绑定的术语版本，须与发布快照术语版本一致';

CREATE TABLE IF NOT EXISTS archive_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    archive_id BIGINT,
    event_type VARCHAR(32) NOT NULL,
    published_version INT,
    actor VARCHAR(128),
    reason VARCHAR(512),
    event_time TIMESTAMP(3) NOT NULL
);
COMMENT ON TABLE archive_event IS '归档领域事件历史：按提交顺序裁决，自增序列保证稳定排序，后续操作不得改写既有事件';
COMMENT ON COLUMN archive_event.event_id IS '事件自增序列，按提交顺序单调递增';
COMMENT ON COLUMN archive_event.document_id IS '所属文档 ID';
COMMENT ON COLUMN archive_event.archive_id IS '关联归档包 ID；发布撤回事件不关联具体包时为 null';
COMMENT ON COLUMN archive_event.event_type IS '事件类型：ARCHIVE_CREATED 归档生成 / ARCHIVE_REVOKED 归档撤销 / RELEASE_WITHDRAWN 发布撤回';
COMMENT ON COLUMN archive_event.published_version IS '关联发布版本；不涉及发布版本时为 null';
COMMENT ON COLUMN archive_event.actor IS '操作者，取 X-Actor-Id；系统事件为 null';
COMMENT ON COLUMN archive_event.reason IS '撤回或撤销原因；归档生成事件为 null';
COMMENT ON COLUMN archive_event.event_time IS '事件时刻，UTC、毫秒精度，区间按左闭右开解释';
