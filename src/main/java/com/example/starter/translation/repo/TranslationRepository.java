package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.ConflictDeclarationRow;
import com.example.starter.translation.domain.Rows.ConflictEventRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.RequestLogRow;
import com.example.starter.translation.domain.Rows.ReviewHistoryRow;
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
            rs.getInt("source_version"), rs.getInt("translation_version"),
            rs.getString("decision"), rs.getString("review_status"), rs.getInt("term_version"),
            (Long) rs.getObject("conflict_declaration_id"),
            rs.getTimestamp("conflicted_at") == null ? null : rs.getTimestamp("conflicted_at").toInstant(),
            rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant());

    private static final RowMapper<TermRuleRow> TERM_RULE_MAPPER = (rs, n) -> new TermRuleRow(
            rs.getString("source_term"), rs.getString("language"), rs.getString("required_translation"));

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

    private static final String APPROVAL_COLUMNS =
            "segment_id, language, reviewer, source_version, translation_version, decision, review_status, "
                    + "term_version, conflict_declaration_id, conflicted_at, reviewed_at";

    public Optional<ApprovalRow> findApproval(long documentId, String segmentId, String language) {
        List<ApprovalRow> rows = jdbc.query(
                "SELECT " + APPROVAL_COLUMNS + " FROM approval WHERE document_id = ? AND segment_id = ? AND language = ?",
                APPROVAL_MAPPER, documentId, segmentId, language);
        return rows.stream().findFirst();
    }

    public List<ApprovalRow> listApprovals(long documentId) {
        return jdbc.query(
                "SELECT " + APPROVAL_COLUMNS + " FROM approval WHERE document_id = ? ORDER BY segment_id, language",
                APPROVAL_MAPPER, documentId);
    }

    /** 查询某评审人在某文档某语种的全部评审（登记冲突时用于批量标记 CONFLICTED）。 */
    public List<ApprovalRow> listApprovalsByReviewer(long documentId, String reviewer, String language) {
        return jdbc.query(
                "SELECT " + APPROVAL_COLUMNS + " FROM approval "
                        + "WHERE document_id = ? AND reviewer = ? AND language = ? ORDER BY segment_id",
                APPROVAL_MAPPER, documentId, reviewer, language);
    }

    /** 插入或覆盖评审（按主键段落+语言唯一），固化决定、版本与提交时间。 */
    public void upsertApproval(long documentId, ApprovalRow row) {
        int updated = jdbc.update(
                "UPDATE approval SET reviewer = ?, source_version = ?, translation_version = ?, decision = ?, "
                        + "review_status = ?, term_version = ?, conflict_declaration_id = ?, conflicted_at = ?, "
                        + "reviewed_at = ?, approved_at = CURRENT_TIMESTAMP "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ?",
                row.reviewer(), row.sourceVersion(), row.translationVersion(), row.decision(),
                row.reviewStatus(), row.termVersion(),
                row.conflictDeclarationId(), timestamp(row.conflictedAt()), Timestamp.from(row.reviewedAt()),
                documentId, row.segmentId(), row.language());
        if (updated == 0) {
            jdbc.update("INSERT INTO approval (document_id, segment_id, language, reviewer, "
                            + "source_version, translation_version, decision, review_status, term_version, "
                            + "conflict_declaration_id, conflicted_at, reviewed_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    documentId, row.segmentId(), row.language(), row.reviewer(),
                    row.sourceVersion(), row.translationVersion(), row.decision(), row.reviewStatus(),
                    row.termVersion(), row.conflictDeclarationId(),
                    timestamp(row.conflictedAt()), Timestamp.from(row.reviewedAt()));
        }
    }

    /** 将一条评审标记为 CONFLICTED 并记录冲突声明与时间；仅当当前为 VALID 时更新。 */
    public int markApprovalConflicted(long documentId, String segmentId, String language,
                                      long conflictDeclarationId, Instant conflictedAt) {
        return jdbc.update(
                "UPDATE approval SET review_status = 'CONFLICTED', conflict_declaration_id = ?, conflicted_at = ? "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ? AND review_status = 'VALID'",
                conflictDeclarationId, Timestamp.from(conflictedAt), documentId, segmentId, language);
    }

    /** 解除冲突后，将被该声明排除且仍指向同一评审版本的评审恢复为 VALID；追加历史记录由服务层完成。 */
    public int restoreApprovalsForDeclaration(long documentId, long conflictDeclarationId) {
        return jdbc.update(
                "UPDATE approval SET review_status = 'VALID', conflict_declaration_id = NULL, conflicted_at = NULL "
                        + "WHERE document_id = ? AND conflict_declaration_id = ? AND review_status = 'CONFLICTED'",
                documentId, conflictDeclarationId);
    }

    /** 列出被某冲突声明标记为 CONFLICTED 的评审（解除时用于追加历史）。 */
    public List<ApprovalRow> listConflictedApprovals(long documentId, long conflictDeclarationId) {
        return jdbc.query(
                "SELECT " + APPROVAL_COLUMNS + " FROM approval "
                        + "WHERE document_id = ? AND conflict_declaration_id = ? AND review_status = 'CONFLICTED' "
                        + "ORDER BY segment_id, language",
                APPROVAL_MAPPER, documentId, conflictDeclarationId);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
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

    // ---------------- 利益冲突声明与历史 ----------------

    private static final RowMapper<ConflictDeclarationRow> CONFLICT_MAPPER = (rs, n) -> new ConflictDeclarationRow(
            rs.getLong("declaration_id"), rs.getLong("document_id"), rs.getString("reviewer"),
            rs.getString("language"), rs.getTimestamp("valid_from").toInstant(),
            rs.getTimestamp("valid_to").toInstant(), rs.getString("reason"), rs.getString("status"),
            rs.getString("declared_by"), rs.getTimestamp("declared_at").toInstant(),
            rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toInstant(),
            rs.getString("released_by"));

    private static final RowMapper<ConflictEventRow> CONFLICT_EVENT_MAPPER = (rs, n) -> new ConflictEventRow(
            rs.getLong("event_id"), rs.getLong("declaration_id"), rs.getLong("document_id"),
            rs.getString("event_type"), rs.getString("reviewer"), rs.getString("language"),
            rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_to").toInstant(),
            rs.getString("reason"), rs.getString("actor"), rs.getString("actor_role"),
            rs.getTimestamp("occurred_at").toInstant());

    private static final RowMapper<ReviewHistoryRow> REVIEW_HISTORY_MAPPER = (rs, n) -> new ReviewHistoryRow(
            rs.getLong("history_id"), rs.getLong("document_id"), rs.getString("segment_id"),
            rs.getString("language"), rs.getString("reviewer"), rs.getString("decision"),
            rs.getString("review_status"), rs.getInt("source_version"), rs.getInt("translation_version"),
            rs.getInt("term_version"), (Long) rs.getObject("conflict_declaration_id"),
            rs.getString("actor"), rs.getTimestamp("occurred_at").toInstant());

    /** 插入冲突声明，返回自增声明 ID。 */
    public long insertConflictDeclaration(long documentId, String reviewer, String language,
                                          Instant validFrom, Instant validTo, String reason,
                                          String declaredBy, Instant declaredAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO conflict_declaration (document_id, reviewer, language, valid_from, valid_to, "
                            + "reason, status, declared_by, declared_at) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                    new String[]{"declaration_id"});
            ps.setLong(1, documentId);
            ps.setString(2, reviewer);
            ps.setString(3, language);
            ps.setTimestamp(4, Timestamp.from(validFrom));
            ps.setTimestamp(5, Timestamp.from(validTo));
            ps.setString(6, reason);
            ps.setString(7, declaredBy);
            ps.setTimestamp(8, Timestamp.from(declaredAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("冲突声明插入后未返回自增主键");
        }
        return key.longValue();
    }

    /** 追加冲突生命周期事件。 */
    public void insertConflictEvent(long declarationId, long documentId, String eventType, String reviewer,
                                    String language, Instant validFrom, Instant validTo, String reason,
                                    String actor, String actorRole, Instant occurredAt) {
        jdbc.update("INSERT INTO conflict_event (declaration_id, document_id, event_type, reviewer, language, "
                        + "valid_from, valid_to, reason, actor, actor_role, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                declarationId, documentId, eventType, reviewer, language,
                Timestamp.from(validFrom), Timestamp.from(validTo), reason, actor, actorRole,
                Timestamp.from(occurredAt));
    }

    /** 追加评审历史记录。 */
    public void insertReviewHistory(long documentId, String segmentId, String language, String reviewer,
                                    String decision, String reviewStatus, int sourceVersion,
                                    int translationVersion, int termVersion, Long conflictDeclarationId,
                                    String actor, Instant occurredAt) {
        jdbc.update("INSERT INTO review_history (document_id, segment_id, language, reviewer, decision, "
                        + "review_status, source_version, translation_version, term_version, "
                        + "conflict_declaration_id, actor, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                documentId, segmentId, language, reviewer, decision, reviewStatus, sourceVersion,
                translationVersion, termVersion, conflictDeclarationId, actor, Timestamp.from(occurredAt));
    }

    public Optional<ConflictDeclarationRow> findConflictDeclaration(long documentId, long declarationId) {
        List<ConflictDeclarationRow> rows = jdbc.query(
                "SELECT * FROM conflict_declaration WHERE document_id = ? AND declaration_id = ?",
                CONFLICT_MAPPER, documentId, declarationId);
        return rows.stream().findFirst();
    }

    /** 列出文档全部冲突声明，按声明 ID 升序稳定排序。 */
    public List<ConflictDeclarationRow> listConflictDeclarations(long documentId) {
        return jdbc.query(
                "SELECT * FROM conflict_declaration WHERE document_id = ? ORDER BY declaration_id",
                CONFLICT_MAPPER, documentId);
    }

    /** 列出某文档某评审人某语种全部声明（登记时重叠校验），按声明 ID 升序。 */
    public List<ConflictDeclarationRow> listReviewerConflicts(long documentId, String reviewer, String language) {
        return jdbc.query(
                "SELECT * FROM conflict_declaration WHERE document_id = ? AND reviewer = ? AND language = ? "
                        + "ORDER BY declaration_id",
                CONFLICT_MAPPER, documentId, reviewer, language);
    }

    /** 解除：写入解除时间与确认人并置 RELEASED，仅当前 ACTIVE 时生效。 */
    public int markConflictReleased(long documentId, long declarationId, String releasedBy, Instant releasedAt) {
        return jdbc.update(
                "UPDATE conflict_declaration SET status = 'RELEASED', released_by = ?, released_at = ? "
                        + "WHERE document_id = ? AND declaration_id = ? AND status = 'ACTIVE'",
                releasedBy, Timestamp.from(releasedAt), documentId, declarationId);
    }

    /** 列出文档全部冲突生命周期事件，按事件 ID 升序稳定排序。 */
    public List<ConflictEventRow> listConflictEvents(long documentId) {
        return jdbc.query(
                "SELECT * FROM conflict_event WHERE document_id = ? ORDER BY event_id",
                CONFLICT_EVENT_MAPPER, documentId);
    }

    /** 列出某声明的生命周期事件，按事件 ID 升序。 */
    public List<ConflictEventRow> listConflictEventsForDeclaration(long documentId, long declarationId) {
        return jdbc.query(
                "SELECT * FROM conflict_event WHERE document_id = ? AND declaration_id = ? ORDER BY event_id",
                CONFLICT_EVENT_MAPPER, documentId, declarationId);
    }

    /** 列出文档全部评审历史，按历史 ID 升序稳定排序。 */
    public List<ReviewHistoryRow> listReviewHistory(long documentId) {
        return jdbc.query(
                "SELECT * FROM review_history WHERE document_id = ? ORDER BY history_id",
                REVIEW_HISTORY_MAPPER, documentId);
    }

    /** 列出某段落某语种的评审历史，按历史 ID 升序。 */
    public List<ReviewHistoryRow> listReviewHistoryForTarget(long documentId, String segmentId, String language) {
        return jdbc.query(
                "SELECT * FROM review_history WHERE document_id = ? AND segment_id = ? AND language = ? "
                        + "ORDER BY history_id",
                REVIEW_HISTORY_MAPPER, documentId, segmentId, language);
    }
}
