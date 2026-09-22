-- 多语种段落修订与发布快照 schema（H2 MySQL 兼容模式；仅使用合成数据）。
-- 所有时间列均为 UTC 瞬时（应用侧以 Instant 写入）。

CREATE TABLE IF NOT EXISTS document (
    document_id       VARCHAR(64)  NOT NULL,
    draft_version     INT          NOT NULL,
    published_version INT          NOT NULL,
    created_at        TIMESTAMP    NOT NULL,
    PRIMARY KEY (document_id)
);
COMMENT ON TABLE document IS '文档：全局唯一 documentId，含草稿版本与发布版本';
COMMENT ON COLUMN document.document_id IS '全局唯一文档 ID';
COMMENT ON COLUMN document.draft_version IS '草稿版本，从 1 开始；增段落或修改任意源文/译文时加一';
COMMENT ON COLUMN document.published_version IS '发布版本，从 0 开始；每次成功发布加一';
COMMENT ON COLUMN document.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS document_language (
    document_id VARCHAR(64) NOT NULL,
    language    VARCHAR(32) NOT NULL,
    PRIMARY KEY (document_id, language)
);
COMMENT ON TABLE document_language IS '文档目标语言：每文档 1~5 种';
COMMENT ON COLUMN document_language.document_id IS '所属文档 ID';
COMMENT ON COLUMN document_language.language IS '目标语言代码（如 en、ja）';

CREATE TABLE IF NOT EXISTS segment (
    document_id    VARCHAR(64)  NOT NULL,
    segment_id     VARCHAR(64)  NOT NULL,
    source_text    CLOB         NOT NULL,
    source_version INT          NOT NULL,
    PRIMARY KEY (document_id, segment_id)
);
COMMENT ON TABLE segment IS '段落：文档内唯一 segmentId，含源文与源文版本';
COMMENT ON COLUMN segment.document_id IS '所属文档 ID';
COMMENT ON COLUMN segment.segment_id IS '文档内唯一段落 ID';
COMMENT ON COLUMN segment.source_text IS '源文正文';
COMMENT ON COLUMN segment.source_version IS '源文版本，从 1 开始；每次源文修订加一';

CREATE TABLE IF NOT EXISTS translation (
    document_id         VARCHAR(64)  NOT NULL,
    segment_id          VARCHAR(64)  NOT NULL,
    language            VARCHAR(32)  NOT NULL,
    body                CLOB         NOT NULL,
    author              VARCHAR(64)  NOT NULL,
    source_version      INT          NOT NULL,
    translation_version INT          NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    PRIMARY KEY (document_id, segment_id, language)
);
COMMENT ON TABLE translation IS '译文：按段落与语言唯一';
COMMENT ON COLUMN translation.document_id IS '所属文档 ID';
COMMENT ON COLUMN translation.segment_id IS '所属段落 ID';
COMMENT ON COLUMN translation.language IS '译文语言代码';
COMMENT ON COLUMN translation.body IS '译文正文';
COMMENT ON COLUMN translation.author IS '译文作者（X-Actor-Id）';
COMMENT ON COLUMN translation.source_version IS '译文所依据的源文版本';
COMMENT ON COLUMN translation.translation_version IS '译文版本，从 1 开始，每次提交递增';
COMMENT ON COLUMN translation.updated_at IS '最近提交时间（UTC）';

CREATE TABLE IF NOT EXISTS translation_approval (
    document_id         VARCHAR(64)  NOT NULL,
    segment_id          VARCHAR(64)  NOT NULL,
    language            VARCHAR(32)  NOT NULL,
    reviewer            VARCHAR(64)  NOT NULL,
    source_version      INT          NOT NULL,
    translation_version INT          NOT NULL,
    approved_at         TIMESTAMP    NOT NULL,
    PRIMARY KEY (document_id, segment_id, language, reviewer)
);
COMMENT ON TABLE translation_approval IS '译文批准：仅当源文版本与译文版本均未变化时有效';
COMMENT ON COLUMN translation_approval.document_id IS '所属文档 ID';
COMMENT ON COLUMN translation_approval.segment_id IS '所属段落 ID';
COMMENT ON COLUMN translation_approval.language IS '译文语言代码';
COMMENT ON COLUMN translation_approval.reviewer IS '审核人（X-Actor-Id），不得为译文作者';
COMMENT ON COLUMN translation_approval.source_version IS '批准时的段落源文版本';
COMMENT ON COLUMN translation_approval.translation_version IS '批准时的译文版本';
COMMENT ON COLUMN translation_approval.approved_at IS '批准时间（UTC）';

CREATE TABLE IF NOT EXISTS publication (
    document_id       VARCHAR(64) NOT NULL,
    published_version INT         NOT NULL,
    created_at        TIMESTAMP   NOT NULL,
    PRIMARY KEY (document_id, published_version)
);
COMMENT ON TABLE publication IS '发布快照头：每次发布生成一份完整只读快照';
COMMENT ON COLUMN publication.document_id IS '所属文档 ID';
COMMENT ON COLUMN publication.published_version IS '发布版本号（递增）';
COMMENT ON COLUMN publication.created_at IS '发布时间（UTC）';

CREATE TABLE IF NOT EXISTS publication_segment (
    document_id       VARCHAR(64) NOT NULL,
    published_version INT         NOT NULL,
    segment_id        VARCHAR(64) NOT NULL,
    source_text       CLOB        NOT NULL,
    source_version    INT         NOT NULL,
    PRIMARY KEY (document_id, published_version, segment_id)
);
COMMENT ON TABLE publication_segment IS '发布快照段落：发布时刻源文内容，只读不可改';
COMMENT ON COLUMN publication_segment.document_id IS '所属文档 ID';
COMMENT ON COLUMN publication_segment.published_version IS '所属发布版本号';
COMMENT ON COLUMN publication_segment.segment_id IS '段落 ID';
COMMENT ON COLUMN publication_segment.source_text IS '发布时刻源文正文';
COMMENT ON COLUMN publication_segment.source_version IS '发布时刻源文版本';

CREATE TABLE IF NOT EXISTS publication_translation (
    document_id         VARCHAR(64) NOT NULL,
    published_version   INT         NOT NULL,
    segment_id          VARCHAR(64) NOT NULL,
    language            VARCHAR(32) NOT NULL,
    body                CLOB        NOT NULL,
    author              VARCHAR(64) NOT NULL,
    source_version      INT         NOT NULL,
    translation_version INT         NOT NULL,
    PRIMARY KEY (document_id, published_version, segment_id, language)
);
COMMENT ON TABLE publication_translation IS '发布快照译文：发布时刻译文内容，只读不可改';
COMMENT ON COLUMN publication_translation.document_id IS '所属文档 ID';
COMMENT ON COLUMN publication_translation.published_version IS '所属发布版本号';
COMMENT ON COLUMN publication_translation.segment_id IS '段落 ID';
COMMENT ON COLUMN publication_translation.language IS '译文语言代码';
COMMENT ON COLUMN publication_translation.body IS '发布时刻译文正文';
COMMENT ON COLUMN publication_translation.author IS '译文作者';
COMMENT ON COLUMN publication_translation.source_version IS '译文所依据的源文版本';
COMMENT ON COLUMN publication_translation.translation_version IS '发布时刻译文版本';

CREATE TABLE IF NOT EXISTS request_log (
    request_id   VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64)  NOT NULL,
    status       INT          NOT NULL,
    response     CLOB         NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (request_id)
);
COMMENT ON TABLE request_log IS '写操作幂等去重：全局唯一 requestId，仅记录成功结果';
COMMENT ON COLUMN request_log.request_id IS '全局唯一请求 ID';
COMMENT ON COLUMN request_log.request_hash IS '请求参数（方法+路径+操作者+正文）的 SHA-256 摘要';
COMMENT ON COLUMN request_log.status IS '原成功响应的 HTTP 状态码';
COMMENT ON COLUMN request_log.response IS '原成功响应正文（JSON）';
COMMENT ON COLUMN request_log.created_at IS '记录时间（UTC）';
