-- 多语种段落修订与发布快照 schema（H2 MySQL 兼容模式）。
-- 所有时间字段为数据库默认时区时间戳；版本号均从 1（文档发布版本从 0）开始单调递增。
-- 例外：citation_anchor 与 citation_anchor_event 的时间一律使用 UTC（TIMESTAMP(6) 微秒精度），
-- 由应用层 Clock（systemUTC）写入，接口输出固定为 yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'。

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

-- 法定引文锚点：锚定已批准译文段落内的引用文本，字符区间左闭右开，时间一律 UTC。
CREATE TABLE IF NOT EXISTS citation_anchor (
    anchor_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    citation_key VARCHAR(512) NOT NULL,
    range_start INT NOT NULL,
    range_end INT NOT NULL,
    anchor_text LONGTEXT NOT NULL,
    lock_reason VARCHAR(2048) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    translation_version INT NOT NULL,
    source_version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    released_by VARCHAR(128),
    release_reason VARCHAR(2048),
    created_at TIMESTAMP(6) NOT NULL,
    released_at TIMESTAMP(6),
    UNIQUE (document_id, segment_id, language, citation_key)
);
COMMENT ON TABLE citation_anchor IS '法定引文锚点：同一段落同一引用标识仅可登记一次；解除后行保留为 RELEASED 证据，不物理删除';
COMMENT ON COLUMN citation_anchor.anchor_id IS '锚点全局 ID，自增，迁移区间时保持不变';
COMMENT ON COLUMN citation_anchor.document_id IS '所属文档 ID';
COMMENT ON COLUMN citation_anchor.segment_id IS '所属段落 ID';
COMMENT ON COLUMN citation_anchor.language IS '目标语言码，小写';
COMMENT ON COLUMN citation_anchor.citation_key IS '规范化引用标识（去首尾空白），同一段落+语言内唯一，解除后也不得再次登记';
COMMENT ON COLUMN citation_anchor.range_start IS '字符区间起点（含），按 Java 字符偏移，从 0 开始';
COMMENT ON COLUMN citation_anchor.range_end IS '字符区间终点（不含），不得超过译文字符长度';
COMMENT ON COLUMN citation_anchor.anchor_text IS '登记时固化的引用文本；修订后必须逐字符保留，是业务证据';
COMMENT ON COLUMN citation_anchor.lock_reason IS '锁定原因，登记时写入，不可变';
COMMENT ON COLUMN citation_anchor.created_by IS '登记人（X-Actor-Id），解除人不得与登记人相同';
COMMENT ON COLUMN citation_anchor.translation_version IS '最近一次迁移/登记时锚定的译文版本';
COMMENT ON COLUMN citation_anchor.source_version IS '登记时批准所对应的源文版本';
COMMENT ON COLUMN citation_anchor.status IS '状态：LOCKED 生效中 / RELEASED 已解除；仅可 LOCKED→RELEASED 一次';
COMMENT ON COLUMN citation_anchor.released_by IS '解除人法务（X-Actor-Id），LOCKED 时为 NULL';
COMMENT ON COLUMN citation_anchor.release_reason IS '解除理由，解除时写入不可变；LOCKED 时为 NULL';
COMMENT ON COLUMN citation_anchor.created_at IS '登记时间，UTC，微秒精度';
COMMENT ON COLUMN citation_anchor.released_at IS '解除时间，UTC，微秒精度；LOCKED 时为 NULL';

-- 引文锚点事件历史：只追加，不更新不删除，按 event_id 稳定排序。
CREATE TABLE IF NOT EXISTS citation_anchor_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    anchor_id BIGINT NOT NULL,
    segment_id VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    citation_key VARCHAR(512) NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    range_start INT NOT NULL,
    range_end INT NOT NULL,
    previous_start INT,
    previous_end INT,
    anchor_text LONGTEXT NOT NULL,
    reason VARCHAR(2048) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    translation_version INT NOT NULL,
    source_version INT NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE citation_anchor_event IS '引文锚点事件历史：REGISTERED/MIGRATED/RELEASED 只追加，字段、排序与 null 语义稳定';
COMMENT ON COLUMN citation_anchor_event.event_id IS '事件 ID，自增，历史按此排序';
COMMENT ON COLUMN citation_anchor_event.document_id IS '所属文档 ID';
COMMENT ON COLUMN citation_anchor_event.anchor_id IS '关联锚点 ID';
COMMENT ON COLUMN citation_anchor_event.segment_id IS '所属段落 ID';
COMMENT ON COLUMN citation_anchor_event.language IS '目标语言码，小写';
COMMENT ON COLUMN citation_anchor_event.citation_key IS '事件发生时的引用标识';
COMMENT ON COLUMN citation_anchor_event.event_type IS '事件类型：REGISTERED 登记 / MIGRATED 区间迁移 / RELEASED 解除';
COMMENT ON COLUMN citation_anchor_event.range_start IS '事件后区间起点（含）';
COMMENT ON COLUMN citation_anchor_event.range_end IS '事件后区间终点（不含）';
COMMENT ON COLUMN citation_anchor_event.previous_start IS '迁移前区间起点；仅 MIGRATED 非空，其余事件为 NULL';
COMMENT ON COLUMN citation_anchor_event.previous_end IS '迁移前区间终点；仅 MIGRATED 非空，其余事件为 NULL';
COMMENT ON COLUMN citation_anchor_event.anchor_text IS '事件时的引用文本，逐字符保留证据';
COMMENT ON COLUMN citation_anchor_event.reason IS '事件理由：REGISTERED/MIGRATED 为锁定原因，RELEASED 为不可变解除理由';
COMMENT ON COLUMN citation_anchor_event.actor_id IS '触发事件的操作者（X-Actor-Id）';
COMMENT ON COLUMN citation_anchor_event.translation_version IS '事件时译文版本';
COMMENT ON COLUMN citation_anchor_event.source_version IS '事件时源文版本';
COMMENT ON COLUMN citation_anchor_event.occurred_at IS '事件发生时间，UTC，微秒精度';
