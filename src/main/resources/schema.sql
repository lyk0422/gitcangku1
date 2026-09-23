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
    term_version INT NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, published_version)
);
COMMENT ON TABLE release_snapshot IS '发布快照：发布时原子生成的完整只读快照，JSON 序列化，不可修改';
COMMENT ON COLUMN release_snapshot.document_id IS '所属文档 ID';
COMMENT ON COLUMN release_snapshot.published_version IS '发布版本号，从 1 开始';
COMMENT ON COLUMN release_snapshot.term_version IS '发布时固化的术语版本，冗余自快照 JSON 便于退役影响查询';
COMMENT ON COLUMN release_snapshot.snapshot_json IS '快照内容 JSON：全部段落源文及各语言译文、作者、审核人与版本号';
COMMENT ON COLUMN release_snapshot.created_at IS '发布时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS term_version (
    document_id BIGINT NOT NULL,
    term_version INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, term_version)
);
COMMENT ON TABLE term_version IS '术语版本：每个文档从 1 开始的不可变术语快照版本，已有版本不可覆盖；退役激活后状态置为 RETIRED 且不自动恢复';
COMMENT ON COLUMN term_version.document_id IS '所属文档 ID';
COMMENT ON COLUMN term_version.term_version IS '术语版本号，从 1 开始单调递增';
COMMENT ON COLUMN term_version.status IS '版本状态：ACTIVE 可用（新建默认），RETIRED 已被退役单激活退役，窗口结束也不恢复';
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

CREATE TABLE IF NOT EXISTS term_retirement (
    retirement_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    retirement_key VARCHAR(128) NOT NULL,
    term_version INT NOT NULL,
    language VARCHAR(16) NOT NULL,
    replacement_version INT NOT NULL,
    effective_from_utc BIGINT NOT NULL,
    effective_to_utc BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    activated_at_utc BIGINT NULL,
    preview_json LONGTEXT NOT NULL,
    impact_snapshot_json LONGTEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (retirement_key),
    UNIQUE (document_id, term_version, language, effective_from_utc)
);
COMMENT ON TABLE term_retirement IS '术语版本退役单：指定退役术语版本、同语言替代版本与左闭右开 UTC 窗口；创建预览不改写内容，激活时原子重算影响集并撤批';
COMMENT ON COLUMN term_retirement.retirement_id IS '退役单 ID，自增';
COMMENT ON COLUMN term_retirement.document_id IS '所属文档 ID';
COMMENT ON COLUMN term_retirement.retirement_key IS '退役单业务键，全局唯一，用于查询与幂等';
COMMENT ON COLUMN term_retirement.term_version IS '被退役的术语版本（target 版本）';
COMMENT ON COLUMN term_retirement.language IS '退役适用的目标语言码，小写';
COMMENT ON COLUMN term_retirement.replacement_version IS '替代术语版本，创建与激活时均须为 ACTIVE 且不形成替代环';
COMMENT ON COLUMN term_retirement.effective_from_utc IS '窗口起（含），UTC epoch 毫秒；此刻起新批准/发布不得引用已退役版本';
COMMENT ON COLUMN term_retirement.effective_to_utc IS '窗口止（不含），左闭右开，UTC epoch 毫秒；窗口结束不自动恢复退役状态';
COMMENT ON COLUMN term_retirement.status IS '退役单状态：DRAFT 已创建未激活；ACTIVE 已激活（target RETIRED、影响已冻结）；窗口是否结束不改变状态';
COMMENT ON COLUMN term_retirement.activated_at_utc IS '激活时刻 UTC epoch 毫秒；未激活为 NULL';
COMMENT ON COLUMN term_retirement.preview_json IS '创建时生成的只读影响预览 JSON（DRAFT/APPROVED/PUBLISHED 受影响条目，稳定排序），激活不覆盖';
COMMENT ON COLUMN term_retirement.impact_snapshot_json IS '激活时在同一事务内重算并冻结的影响集合 JSON；NULL 表示尚未激活';
COMMENT ON COLUMN term_retirement.created_at IS '创建时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS retirement_impact (
    impact_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    retirement_id BIGINT NOT NULL,
    published_version INT NULL,
    segment_id VARCHAR(64) NULL,
    language VARCHAR(16) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    hit_terms_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE retirement_impact IS '退役影响清单：创建预览与激活冻结各写一份；已发布快照不可变仅登记历史影响及实际命中术语位置';
COMMENT ON COLUMN retirement_impact.document_id IS '所属文档 ID';
COMMENT ON COLUMN retirement_impact.retirement_id IS '所属退役单 ID';
COMMENT ON COLUMN retirement_impact.published_version IS 'PUBLISHED 条目的发布版本号；DRAFT/APPROVED 条目为 NULL';
COMMENT ON COLUMN retirement_impact.segment_id IS '条目段落 ID；仅当受影响发布版本全部段落均无术语命中时，以 segment_id 为 NULL 的标记行登记该版本';
COMMENT ON COLUMN retirement_impact.language IS '目标语言码，小写';
COMMENT ON COLUMN retirement_impact.kind IS '条目类型：DRAFT 普通草稿；APPROVED 激活时仍绑定退役术语被撤批；PUBLISHED 历史发布快照';
COMMENT ON COLUMN retirement_impact.hit_terms_json IS '实际命中的术语位置 JSON 数组：sourceTerm 及出现位置起止偏移（基于字符）';
COMMENT ON COLUMN retirement_impact.created_at IS '记录时间，数据库默认时区';

CREATE TABLE IF NOT EXISTS draft_migration (
    migration_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    retirement_id BIGINT NOT NULL,
    expected_version INT NOT NULL,
    rule_version INT NOT NULL,
    old_summary VARCHAR(2048) NOT NULL,
    old_text_hash VARCHAR(64) NOT NULL,
    new_summary VARCHAR(2048) NOT NULL,
    new_text_hash VARCHAR(64) NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    created_at_utc BIGINT NOT NULL,
    request_log_id VARCHAR(128) NOT NULL,
    UNIQUE (document_id, retirement_id, segment_id, language)
);
COMMENT ON TABLE draft_migration IS '草稿迁移记录：成功迁移逐稿增版，保存旧/新文本摘要、哈希与校验所用替代术语版本（规则版本）';
COMMENT ON COLUMN draft_migration.migration_id IS '迁移记录 ID，自增';
COMMENT ON COLUMN draft_migration.document_id IS '所属文档 ID';
COMMENT ON COLUMN draft_migration.retirement_id IS '所属退役单 ID';
COMMENT ON COLUMN draft_migration.expected_version IS '迁移命令声明的 expectedVersion（译文版本乐观校验）';
COMMENT ON COLUMN draft_migration.rule_version IS '替换校验使用的 replacementVersion（规则版本）';
COMMENT ON COLUMN draft_migration.old_summary IS '旧文本摘要（SHA-256 前 32 字符），非空';
COMMENT ON COLUMN draft_migration.old_text_hash IS '旧文本完整 SHA-256';
COMMENT ON COLUMN draft_migration.new_summary IS '新文本摘要（SHA-256 前 32 字符），非空';
COMMENT ON COLUMN draft_migration.new_text_hash IS '新文本完整 SHA-256';
COMMENT ON COLUMN draft_migration.segment_id IS '被迁移草稿的段落 ID';
COMMENT ON COLUMN draft_migration.language IS '被迁移草稿的语言码，小写';
COMMENT ON COLUMN draft_migration.created_at_utc IS '迁移成功时间，UTC epoch 毫秒';
COMMENT ON COLUMN draft_migration.request_log_id IS '关联的幂等请求 ID，保证逐稿记录与请求同生死';
