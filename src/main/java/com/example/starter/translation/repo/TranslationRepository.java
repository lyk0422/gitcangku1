package com.example.starter.translation.repo;

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

    private static final RowMapper<CitationAnchorRow> ANCHOR_MAPPER = (rs, n) -> {
        Timestamp releasedAt = rs.getTimestamp("released_at");
        return new CitationAnchorRow(
                rs.getLong("anchor_id"), rs.getLong("document_id"), rs.getString("segment_id"),
                rs.getString("language"), rs.getString("citation_key"), rs.getInt("range_start"),
                rs.getInt("range_end"), rs.getString("anchor_text"), rs.getString("lock_reason"),
                rs.getString("created_by"), rs.getInt("translation_version"), rs.getInt("source_version"),
                rs.getString("status"), rs.getString("released_by"), rs.getString("release_reason"),
                rs.getTimestamp("created_at").toInstant(),
                releasedAt == null ? null : releasedAt.toInstant());
    };

    private static final RowMapper<CitationAnchorEventRow> ANCHOR_EVENT_MAPPER = (rs, n) -> {
        Integer previousStart = (Integer) rs.getObject("previous_start");
        Integer previousEnd = (Integer) rs.getObject("previous_end");
        return new CitationAnchorEventRow(
                rs.getLong("event_id"), rs.getLong("document_id"), rs.getLong("anchor_id"),
                rs.getString("segment_id"), rs.getString("language"), rs.getString("citation_key"),
                rs.getString("event_type"), rs.getInt("range_start"), rs.getInt("range_end"),
                previousStart, previousEnd, rs.getString("anchor_text"), rs.getString("reason"),
                rs.getString("actor_id"), rs.getInt("translation_version"), rs.getInt("source_version"),
                rs.getTimestamp("occurred_at").toInstant());
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

    /** 登记锚点并回填自增 anchorId；时间由调用方以 UTC 传入，保证可测且精度稳定。 */
    public long insertAnchor(CitationAnchorRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO citation_anchor (document_id, segment_id, language, citation_key, "
                            + "range_start, range_end, anchor_text, lock_reason, created_by, "
                            + "translation_version, source_version, status, released_by, release_reason, "
                            + "created_at, released_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"anchor_id"});
            ps.setLong(1, row.documentId());
            ps.setString(2, row.segmentId());
            ps.setString(3, row.language());
            ps.setString(4, row.citationKey());
            ps.setInt(5, row.rangeStart());
            ps.setInt(6, row.rangeEnd());
            ps.setString(7, row.anchorText());
            ps.setString(8, row.lockReason());
            ps.setString(9, row.createdBy());
            ps.setInt(10, row.translationVersion());
            ps.setInt(11, row.sourceVersion());
            ps.setString(12, row.status());
            ps.setString(13, row.releasedBy());
            ps.setString(14, row.releaseReason());
            ps.setTimestamp(15, Timestamp.from(row.createdAt()));
            ps.setTimestamp(16, row.releasedAt() == null ? null : Timestamp.from(row.releasedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("锚点登记后未返回自增主键");
        }
        return key.longValue();
    }

    /** 按 ID 查询锚点。 */
    public Optional<CitationAnchorRow> findAnchor(long anchorId) {
        List<CitationAnchorRow> rows = jdbc.query(anchorSelect() + " WHERE anchor_id = ?",
                ANCHOR_MAPPER, anchorId);
        return rows.stream().findFirst();
    }

    /** 查询某段落某语言的全部锚点（含已解除），按登记先后排序。 */
    public List<CitationAnchorRow> listAnchors(long documentId, String segmentId, String language) {
        return jdbc.query(anchorSelect()
                        + " WHERE document_id = ? AND segment_id = ? AND language = ? ORDER BY anchor_id",
                ANCHOR_MAPPER, documentId, segmentId, language);
    }

    /** 查询某文档全部锚点（含已解除），按段落、语言、登记先后排序。 */
    public List<CitationAnchorRow> listAllAnchors(long documentId) {
        return jdbc.query(anchorSelect() + " WHERE document_id = ? ORDER BY segment_id, language, anchor_id",
                ANCHOR_MAPPER, documentId);
    }

    /** 查询某文档当前生效（LOCKED）锚点。 */
    public List<CitationAnchorRow> listLockedAnchors(long documentId) {
        return jdbc.query(anchorSelect()
                        + " WHERE document_id = ? AND status = 'LOCKED' ORDER BY segment_id, language, anchor_id",
                ANCHOR_MAPPER, documentId);
    }

    /** 迁移锚点区间与锚定译文版本（区间随译文修订移动）。 */
    public void updateAnchorRange(long anchorId, int rangeStart, int rangeEnd, int translationVersion) {
        jdbc.update("UPDATE citation_anchor SET range_start = ?, range_end = ?, translation_version = ? "
                + "WHERE anchor_id = ?", rangeStart, rangeEnd, translationVersion, anchorId);
    }

    /** 解除锚点：仅可 LOCKED→RELEASED 一次，写入解除人、不可变理由与 UTC 时间。 */
    public int releaseAnchor(long anchorId, String releasedBy, String reason, java.time.Instant releasedAt) {
        return jdbc.update("UPDATE citation_anchor SET status = 'RELEASED', released_by = ?, release_reason = ?, "
                        + "released_at = ? WHERE anchor_id = ? AND status = 'LOCKED'",
                releasedBy, reason, Timestamp.from(releasedAt), anchorId);
    }

    /** 追加锚点事件（只追加历史）。 */
    public long insertAnchorEvent(CitationAnchorEventRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO citation_anchor_event (document_id, anchor_id, segment_id, language, "
                            + "citation_key, event_type, range_start, range_end, previous_start, previous_end, "
                            + "anchor_text, reason, actor_id, translation_version, source_version, occurred_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"event_id"});
            ps.setLong(1, row.documentId());
            ps.setLong(2, row.anchorId());
            ps.setString(3, row.segmentId());
            ps.setString(4, row.language());
            ps.setString(5, row.citationKey());
            ps.setString(6, row.eventType());
            ps.setInt(7, row.rangeStart());
            ps.setInt(8, row.rangeEnd());
            if (row.previousStart() == null) {
                ps.setNull(9, java.sql.Types.INTEGER);
            } else {
                ps.setInt(9, row.previousStart());
            }
            if (row.previousEnd() == null) {
                ps.setNull(10, java.sql.Types.INTEGER);
            } else {
                ps.setInt(10, row.previousEnd());
            }
            ps.setString(11, row.anchorText());
            ps.setString(12, row.reason());
            ps.setString(13, row.actorId());
            ps.setInt(14, row.translationVersion());
            ps.setInt(15, row.sourceVersion());
            ps.setTimestamp(16, Timestamp.from(row.occurredAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("锚点事件落库后未返回自增主键");
        }
        return key.longValue();
    }

    /** 查询锚点事件历史：可按文档全量或单锚点，按 event_id 稳定排序。 */
    public List<CitationAnchorEventRow> listAnchorEvents(long documentId, Long anchorId) {
        if (anchorId == null) {
            return jdbc.query(eventSelect() + " WHERE document_id = ? ORDER BY event_id",
                    ANCHOR_EVENT_MAPPER, documentId);
        }
        return jdbc.query(eventSelect() + " WHERE document_id = ? AND anchor_id = ? ORDER BY event_id",
                ANCHOR_EVENT_MAPPER, documentId, anchorId);
    }

    private static String anchorSelect() {
        return "SELECT anchor_id, document_id, segment_id, language, citation_key, range_start, range_end, "
                + "anchor_text, lock_reason, created_by, translation_version, source_version, status, "
                + "released_by, release_reason, created_at, released_at FROM citation_anchor";
    }

    private static String eventSelect() {
        return "SELECT event_id, document_id, anchor_id, segment_id, language, citation_key, event_type, "
                + "range_start, range_end, previous_start, previous_end, anchor_text, reason, actor_id, "
                + "translation_version, source_version, occurred_at FROM citation_anchor_event";
    }
}
