package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.CitationAnchorEventRow;
import com.example.starter.translation.domain.Rows.CitationAnchorRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.RequestLogRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 多语种段落发布的数据访问层，基于 JdbcTemplate 与参数化 SQL。
 * 目标语言在 document 表中以逗号分隔存储，行记录中还原为列表。
 */
@Repository
public class TranslationRepository {

    private static final RowMapper<DocumentRow> DOCUMENT_MAPPER = (rs, n) -> new DocumentRow(
            rs.getLong("document_id"),
            Arrays.stream(rs.getString("target_languages").split(",")).toList(),
            rs.getInt("draft_version"),
            rs.getInt("published_version"),
            rs.getInt("term_version"));

    private static final RowMapper<SegmentRow> SEGMENT_MAPPER = (rs, n) -> new SegmentRow(
            rs.getString("segment_id"), rs.getString("source_text"), rs.getInt("source_version"));

    private static final RowMapper<TranslationRow> TRANSLATION_MAPPER = (rs, n) -> new TranslationRow(
            rs.getString("segment_id"), rs.getString("language"), rs.getString("content"),
            rs.getString("author"), rs.getInt("source_version"), rs.getInt("translation_version"),
            rs.getInt("term_version"));

    private static final RowMapper<ApprovalRow> APPROVAL_MAPPER = (rs, n) -> new ApprovalRow(
            rs.getString("segment_id"), rs.getString("language"), rs.getString("reviewer"),
            rs.getInt("source_version"), rs.getInt("translation_version"));

    private static final RowMapper<TermRuleRow> TERM_RULE_MAPPER = (rs, n) -> new TermRuleRow(
            rs.getString("source_term"), rs.getString("language"), rs.getString("required_translation"));

    private static final RowMapper<CitationAnchorRow> CITATION_ANCHOR_MAPPER = (rs, n) -> {
        Timestamp releasedAt = rs.getTimestamp("released_at");
        return new CitationAnchorRow(
                rs.getLong("anchor_id"), rs.getLong("document_id"), rs.getString("segment_id"),
                rs.getString("language"), rs.getString("citation_key"),
                rs.getInt("start_offset"), rs.getInt("end_offset"), rs.getString("anchor_text"),
                rs.getString("lock_reason"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getInt("translation_version"),
                Rows.AnchorStatus.valueOf(rs.getString("status")),
                rs.getString("released_by"), rs.getString("release_reason"),
                releasedAt == null ? null : releasedAt.toInstant());
    };

    private static final RowMapper<CitationAnchorEventRow> CITATION_EVENT_MAPPER = (rs, n) -> {
        Integer fromStart = rs.getObject("from_start", Integer.class);
        Integer fromEnd = rs.getObject("from_end", Integer.class);
        return new CitationAnchorEventRow(
                rs.getLong("event_id"), rs.getLong("anchor_id"), rs.getLong("document_id"),
                rs.getString("segment_id"), rs.getString("language"), rs.getString("citation_key"),
                rs.getString("event_type"), fromStart, fromEnd,
                rs.getInt("to_start"), rs.getInt("to_end"), rs.getString("anchor_text"),
                rs.getString("actor_id"), rs.getString("actor_role"), rs.getString("reason"),
                rs.getTimestamp("event_time").toInstant());
    };

    private final JdbcTemplate jdbc;

    public TranslationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入文档并返回自增 ID，初始草稿版本 1、发布版本 0、术语版本 0。 */
    public long insertDocument(List<String> targetLanguages) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO document (target_languages, draft_version, published_version, term_version) "
                            + "VALUES (?, 1, 0, 0)",
                    new String[]{"document_id"});
            ps.setString(1, String.join(",", targetLanguages));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("文档创建后未返回自增主键");
        }
        return key.longValue();
    }

    /** 按 ID 查询文档并加行级写锁（FOR UPDATE），用于串行化同一文档的写操作。 */
    public Optional<DocumentRow> findDocumentForUpdate(long documentId) {
        List<DocumentRow> rows = jdbc.query(
                "SELECT document_id, target_languages, draft_version, published_version, term_version "
                        + "FROM document WHERE document_id = ? FOR UPDATE",
                DOCUMENT_MAPPER, documentId);
        return rows.stream().findFirst();
    }

    /** 只读查询文档（不加锁），用于快照查询。 */
    public Optional<DocumentRow> findDocument(long documentId) {
        List<DocumentRow> rows = jdbc.query(
                "SELECT document_id, target_languages, draft_version, published_version, term_version "
                        + "FROM document WHERE document_id = ?",
                DOCUMENT_MAPPER, documentId);
        return rows.stream().findFirst();
    }

    public void updateDraftVersion(long documentId, int draftVersion) {
        jdbc.update("UPDATE document SET draft_version = ? WHERE document_id = ?", draftVersion, documentId);
    }

    public void updatePublishedVersion(long documentId, int publishedVersion) {
        jdbc.update("UPDATE document SET published_version = ? WHERE document_id = ?", publishedVersion, documentId);
    }

    public void updateTermVersion(long documentId, int termVersion) {
        jdbc.update("UPDATE document SET term_version = ? WHERE document_id = ?", termVersion, documentId);
    }

    public void insertSegment(long documentId, String segmentId, String sourceText) {
        jdbc.update("INSERT INTO segment (document_id, segment_id, source_text, source_version) VALUES (?, ?, ?, 1)",
                documentId, segmentId, sourceText);
    }

    public Optional<SegmentRow> findSegment(long documentId, String segmentId) {
        List<SegmentRow> rows = jdbc.query(
                "SELECT segment_id, source_text, source_version FROM segment "
                        + "WHERE document_id = ? AND segment_id = ?",
                SEGMENT_MAPPER, documentId, segmentId);
        return rows.stream().findFirst();
    }

    public List<SegmentRow> listSegments(long documentId) {
        return jdbc.query(
                "SELECT segment_id, source_text, source_version FROM segment "
                        + "WHERE document_id = ? ORDER BY segment_id",
                SEGMENT_MAPPER, documentId);
    }

    public void updateSegmentSource(long documentId, String segmentId, String sourceText, int sourceVersion) {
        jdbc.update("UPDATE segment SET source_text = ?, source_version = ? "
                        + "WHERE document_id = ? AND segment_id = ?",
                sourceText, sourceVersion, documentId, segmentId);
    }

    public Optional<TranslationRow> findTranslation(long documentId, String segmentId, String language) {
        List<TranslationRow> rows = jdbc.query(
                "SELECT segment_id, language, content, author, source_version, translation_version, term_version "
                        + "FROM translation WHERE document_id = ? AND segment_id = ? AND language = ?",
                TRANSLATION_MAPPER, documentId, segmentId, language);
        return rows.stream().findFirst();
    }

    public List<TranslationRow> listTranslations(long documentId) {
        return jdbc.query(
                "SELECT segment_id, language, content, author, source_version, translation_version, term_version "
                        + "FROM translation WHERE document_id = ? ORDER BY segment_id, language",
                TRANSLATION_MAPPER, documentId);
    }

    /** 插入或覆盖译文（按主键段落+语言唯一）。 */
    public void upsertTranslation(long documentId, TranslationRow row) {
        int updated = jdbc.update(
                "UPDATE translation SET content = ?, author = ?, source_version = ?, translation_version = ?, "
                        + "term_version = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ?",
                row.content(), row.author(), row.sourceVersion(), row.translationVersion(), row.termVersion(),
                documentId, row.segmentId(), row.language());
        if (updated == 0) {
            jdbc.update("INSERT INTO translation (document_id, segment_id, language, content, author, "
                            + "source_version, translation_version, term_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    documentId, row.segmentId(), row.language(), row.content(), row.author(),
                    row.sourceVersion(), row.translationVersion(), row.termVersion());
        }
    }

    public Optional<ApprovalRow> findApproval(long documentId, String segmentId, String language) {
        List<ApprovalRow> rows = jdbc.query(
                "SELECT segment_id, language, reviewer, source_version, translation_version "
                        + "FROM approval WHERE document_id = ? AND segment_id = ? AND language = ?",
                APPROVAL_MAPPER, documentId, segmentId, language);
        return rows.stream().findFirst();
    }

    public List<ApprovalRow> listApprovals(long documentId) {
        return jdbc.query(
                "SELECT segment_id, language, reviewer, source_version, translation_version "
                        + "FROM approval WHERE document_id = ? ORDER BY segment_id, language",
                APPROVAL_MAPPER, documentId);
    }

    /** 插入或覆盖批准（按主键段落+语言唯一）。 */
    public void upsertApproval(long documentId, ApprovalRow row) {
        int updated = jdbc.update(
                "UPDATE approval SET reviewer = ?, source_version = ?, translation_version = ?, "
                        + "approved_at = CURRENT_TIMESTAMP "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ?",
                row.reviewer(), row.sourceVersion(), row.translationVersion(),
                documentId, row.segmentId(), row.language());
        if (updated == 0) {
            jdbc.update("INSERT INTO approval (document_id, segment_id, language, reviewer, "
                            + "source_version, translation_version) VALUES (?, ?, ?, ?, ?, ?)",
                    documentId, row.segmentId(), row.language(), row.reviewer(),
                    row.sourceVersion(), row.translationVersion());
        }
    }

    public void insertSnapshot(long documentId, int publishedVersion, String snapshotJson) {
        jdbc.update("INSERT INTO release_snapshot (document_id, published_version, snapshot_json) VALUES (?, ?, ?)",
                documentId, publishedVersion, snapshotJson);
    }

    /** 插入术语版本主记录（不可变，主键已存在时抛冲突）。 */
    public void insertTermVersion(long documentId, int termVersion) {
        jdbc.update("INSERT INTO term_version (document_id, term_version) VALUES (?, ?)",
                documentId, termVersion);
    }

    /** 插入一条术语规则，归属指定术语版本。 */
    public void insertTermRule(long documentId, int termVersion, TermRuleRow rule) {
        jdbc.update("INSERT INTO term_rule (document_id, term_version, source_term, language, "
                        + "required_translation) VALUES (?, ?, ?, ?, ?)",
                documentId, termVersion, rule.sourceTerm(), rule.language(), rule.requiredTranslation());
    }

    /** 判断指定术语版本是否存在。 */
    public boolean termVersionExists(long documentId, int termVersion) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_version WHERE document_id = ? AND term_version = ?",
                Integer.class, documentId, termVersion);
        return count != null && count > 0;
    }

    /** 查询指定术语版本的全部规则，按 sourceTerm、语言排序保证稳定输出。 */
    public List<TermRuleRow> listTermRules(long documentId, int termVersion) {
        return jdbc.query(
                "SELECT source_term, language, required_translation FROM term_rule "
                        + "WHERE document_id = ? AND term_version = ? ORDER BY source_term, language",
                TERM_RULE_MAPPER, documentId, termVersion);
    }

    public Optional<String> findSnapshot(long documentId, int publishedVersion) {
        List<String> rows = jdbc.query(
                "SELECT snapshot_json FROM release_snapshot WHERE document_id = ? AND published_version = ?",
                (rs, n) -> rs.getString(1), documentId, publishedVersion);
        return rows.stream().findFirst();
    }

    public Optional<RequestLogRow> findRequestLog(String requestId) {
        List<RequestLogRow> rows = jdbc.query(
                "SELECT request_id, request_hash, response_status, response_body FROM request_log WHERE request_id = ?",
                (rs, n) -> new RequestLogRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)),
                requestId);
        return rows.stream().findFirst();
    }

    public void insertRequestLog(String requestId, String requestHash, int responseStatus, String responseBody) {
        jdbc.update("INSERT INTO request_log (request_id, request_hash, response_status, response_body) "
                + "VALUES (?, ?, ?, ?)", requestId, requestHash, responseStatus, responseBody);
    }

    /** 登记新锚点并返回自增 anchorId；createdAt 为 UTC 微秒精度。 */
    public long insertAnchor(CitationAnchorRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO citation_anchor (document_id, segment_id, language, citation_key, "
                            + "start_offset, end_offset, anchor_text, lock_reason, created_by, created_at, "
                            + "translation_version, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')",
                    new String[]{"anchor_id"});
            ps.setLong(1, row.documentId());
            ps.setString(2, row.segmentId());
            ps.setString(3, row.language());
            ps.setString(4, row.citationKey());
            ps.setInt(5, row.startOffset());
            ps.setInt(6, row.endOffset());
            ps.setString(7, row.anchorText());
            ps.setString(8, row.lockReason());
            ps.setString(9, row.createdBy());
            ps.setTimestamp(10, Timestamp.from(row.createdAt()));
            ps.setInt(11, row.translationVersion());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("锚点登记后未返回自增主键");
        }
        return key.longValue();
    }

    public Optional<CitationAnchorRow> findAnchor(long documentId, long anchorId) {
        List<CitationAnchorRow> rows = jdbc.query(ANCHOR_COLUMNS + " FROM citation_anchor "
                + "WHERE document_id = ? AND anchor_id = ?", CITATION_ANCHOR_MAPPER, documentId, anchorId);
        return rows.stream().findFirst();
    }

    /** 同段落引用标识查询（含已解除行，用于“只能锁定一次”判定），不存在返回空。 */
    public Optional<CitationAnchorRow> findAnchorByKey(long documentId, String segmentId, String citationKey) {
        List<CitationAnchorRow> rows = jdbc.query(ANCHOR_COLUMNS + " FROM citation_anchor "
                        + "WHERE document_id = ? AND segment_id = ? AND citation_key = ?",
                CITATION_ANCHOR_MAPPER, documentId, segmentId, citationKey);
        return rows.stream().findFirst();
    }

    /** 列出段落全部锚点（含已解除），按 anchor_id 排序。 */
    public List<CitationAnchorRow> listAnchorsBySegment(long documentId, String segmentId) {
        return jdbc.query(ANCHOR_COLUMNS + " FROM citation_anchor "
                        + "WHERE document_id = ? AND segment_id = ? ORDER BY anchor_id",
                CITATION_ANCHOR_MAPPER, documentId, segmentId);
    }

    /** 列出文档全部锚点（含已解除），按段落、anchor_id 排序。 */
    public List<CitationAnchorRow> listAnchorsForDocument(long documentId) {
        return jdbc.query(ANCHOR_COLUMNS + " FROM citation_anchor "
                        + "WHERE document_id = ? ORDER BY segment_id, anchor_id",
                CITATION_ANCHOR_MAPPER, documentId);
    }

    /** 列出某译文当前全部生效锚点，按 anchor_id 排序。 */
    public List<CitationAnchorRow> listActiveAnchorsForTranslation(long documentId, String segmentId,
                                                                   String language) {
        return jdbc.query(ANCHOR_COLUMNS + " FROM citation_anchor "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ? AND status = 'ACTIVE' "
                        + "ORDER BY anchor_id",
                CITATION_ANCHOR_MAPPER, documentId, segmentId, language);
    }

    /** 译文修订迁移：原地更新锚点区间与绑定译文版本，ID 与固化引用文本不变。 */
    public void updateAnchorMigration(long anchorId, int startOffset, int endOffset, int translationVersion) {
        jdbc.update("UPDATE citation_anchor SET start_offset = ?, end_offset = ?, translation_version = ? "
                + "WHERE anchor_id = ? AND status = 'ACTIVE'", startOffset, endOffset, translationVersion, anchorId);
    }

    /** 解除锚点：写入法务确认人、不可变理由与 UTC 解除时间，状态置 RELEASED；返回受影响行数。 */
    public int releaseAnchor(long anchorId, String releasedBy, String releaseReason, Instant releasedAt) {
        return jdbc.update("UPDATE citation_anchor SET status = 'RELEASED', released_by = ?, release_reason = ?, "
                + "released_at = ? WHERE anchor_id = ? AND status = 'ACTIVE'",
                releasedBy, releaseReason, Timestamp.from(releasedAt), anchorId);
    }

    /** 追加不可变锚点事件。 */
    public void insertAnchorEvent(CitationAnchorEventRow row) {
        jdbc.update("INSERT INTO citation_anchor_event (anchor_id, document_id, segment_id, language, "
                        + "citation_key, event_type, from_start, from_end, to_start, to_end, anchor_text, "
                        + "actor_id, actor_role, reason, event_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.anchorId(), row.documentId(), row.segmentId(), row.language(), row.citationKey(),
                row.eventType(), row.fromStart(), row.fromEnd(), row.toStart(), row.toEnd(), row.anchorText(),
                row.actorId(), row.actorRole(), row.reason(), Timestamp.from(row.eventTime()));
    }

    /** 查询段落锚点全部事件，按提交顺序（event_id）排列。 */
    public List<CitationAnchorEventRow> listAnchorEvents(long documentId, String segmentId) {
        return jdbc.query(EVENT_COLUMNS + " FROM citation_anchor_event "
                        + "WHERE document_id = ? AND segment_id = ? ORDER BY event_id",
                CITATION_EVENT_MAPPER, documentId, segmentId);
    }

    /** 查询文档全部锚点事件，按提交顺序排列，用于发布快照固化。 */
    public List<CitationAnchorEventRow> listAnchorEventsForDocument(long documentId) {
        return jdbc.query(EVENT_COLUMNS + " FROM citation_anchor_event "
                        + "WHERE document_id = ? ORDER BY event_id",
                CITATION_EVENT_MAPPER, documentId);
    }

    private static final String ANCHOR_COLUMNS = "SELECT anchor_id, document_id, segment_id, language, "
            + "citation_key, start_offset, end_offset, anchor_text, lock_reason, created_by, created_at, "
            + "translation_version, status, released_by, release_reason, released_at";

    private static final String EVENT_COLUMNS = "SELECT event_id, anchor_id, document_id, segment_id, language, "
            + "citation_key, event_type, from_start, from_end, to_start, to_end, anchor_text, actor_id, "
            + "actor_role, reason, event_time";
}
