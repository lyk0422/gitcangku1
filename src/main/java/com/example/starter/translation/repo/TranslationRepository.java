package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows.ConflictHistoryRow;
import com.example.starter.translation.domain.Rows.ConflictRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.RequestLogRow;
import com.example.starter.translation.domain.Rows.ReviewRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
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
            rs.getInt("required_review_count"),
            rs.getInt("draft_version"),
            rs.getInt("published_version"),
            rs.getInt("term_version"));

    private static final RowMapper<SegmentRow> SEGMENT_MAPPER = (rs, n) -> new SegmentRow(
            rs.getString("segment_id"), rs.getString("source_text"), rs.getInt("source_version"));

    private static final RowMapper<TranslationRow> TRANSLATION_MAPPER = (rs, n) -> new TranslationRow(
            rs.getString("segment_id"), rs.getString("language"), rs.getString("content"),
            rs.getString("author"), rs.getInt("source_version"), rs.getInt("translation_version"),
            rs.getInt("term_version"));

    private static final RowMapper<ReviewRow> REVIEW_MAPPER = (rs, n) -> new ReviewRow(
            rs.getString("segment_id"), rs.getString("language"), rs.getString("reviewer"),
            rs.getString("decision"), rs.getString("status"), rs.getString("reason"),
            rs.getInt("source_version"), rs.getInt("translation_version"),
            (Long) rs.getObject("conflict_id"), rs.getString("conflict_reason"),
            (Long) rs.getObject("conflict_valid_from"), (Long) rs.getObject("conflict_valid_to"));

    private static final RowMapper<ConflictRow> CONFLICT_MAPPER = (rs, n) -> new ConflictRow(
            rs.getLong("conflict_id"), rs.getLong("document_id"), rs.getString("language"),
            rs.getString("reviewer"), rs.getLong("valid_from"), rs.getLong("valid_to"),
            rs.getString("reason"), rs.getString("status"), rs.getString("declared_by"),
            rs.getLong("declared_at"), rs.getString("lifted_by"), (Long) rs.getObject("lifted_at"),
            rs.getString("lift_reason"));

    private static final RowMapper<ConflictHistoryRow> CONFLICT_HISTORY_MAPPER = (rs, n) ->
            new ConflictHistoryRow(rs.getLong("history_id"), rs.getLong("conflict_id"),
                    rs.getLong("document_id"), rs.getString("action"), rs.getString("actor"),
                    rs.getString("actor_role"), rs.getString("reason"),
                    rs.getLong("valid_from"), rs.getLong("valid_to"), rs.getLong("created_at"));

    private static final RowMapper<TermRuleRow> TERM_RULE_MAPPER = (rs, n) -> new TermRuleRow(
            rs.getString("source_term"), rs.getString("language"), rs.getString("required_translation"));

    private final JdbcTemplate jdbc;

    public TranslationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入文档并返回自增 ID，初始草稿版本 1、发布版本 0、术语版本 0。 */
    public long insertDocument(List<String> targetLanguages, int requiredReviewCount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO document (target_languages, required_review_count, draft_version, "
                            + "published_version, term_version) VALUES (?, ?, 1, 0, 0)",
                    new String[]{"document_id"});
            ps.setString(1, String.join(",", targetLanguages));
            ps.setInt(2, requiredReviewCount);
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
                "SELECT document_id, target_languages, required_review_count, draft_version, published_version, "
                        + "term_version FROM document WHERE document_id = ? FOR UPDATE",
                DOCUMENT_MAPPER, documentId);
        return rows.stream().findFirst();
    }

    /** 只读查询文档（不加锁），用于快照查询。 */
    public Optional<DocumentRow> findDocument(long documentId) {
        List<DocumentRow> rows = jdbc.query(
                "SELECT document_id, target_languages, required_review_count, draft_version, published_version, "
                        + "term_version FROM document WHERE document_id = ?",
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

    /** 查询某文档某评审人在某语种上的全部评审行（用于冲突登记时批量标记 CONFLICTED）。 */
    public List<ReviewRow> listReviewsByReviewerAndLanguage(long documentId, String reviewer, String language) {
        return jdbc.query(
                "SELECT segment_id, language, reviewer, decision, status, reason, source_version, "
                        + "translation_version, conflict_id, conflict_reason, conflict_valid_from, "
                        + "conflict_valid_to FROM review WHERE document_id = ? AND reviewer = ? AND language = ?",
                REVIEW_MAPPER, documentId, reviewer, language);
    }

    public List<ReviewRow> listReviews(long documentId) {
        return jdbc.query(
                "SELECT segment_id, language, reviewer, decision, status, reason, source_version, "
                        + "translation_version, conflict_id, conflict_reason, conflict_valid_from, "
                        + "conflict_valid_to FROM review WHERE document_id = ? "
                        + "ORDER BY segment_id, language, reviewer",
                REVIEW_MAPPER, documentId);
    }

    /** 插入或覆盖同一评审人对同一译文的评审（按主键段落+语言+评审人唯一）；历史排除证据列保留不清除。 */
    public void upsertReview(long documentId, ReviewRow row) {
        int updated = jdbc.update(
                "UPDATE review SET decision = ?, status = ?, reason = ?, source_version = ?, "
                        + "translation_version = ?, conflict_id = COALESCE(conflict_id, ?), "
                        + "conflict_reason = COALESCE(conflict_reason, ?), "
                        + "conflict_valid_from = COALESCE(conflict_valid_from, ?), "
                        + "conflict_valid_to = COALESCE(conflict_valid_to, ?), "
                        + "reviewed_at = CURRENT_TIMESTAMP "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ? AND reviewer = ?",
                row.decision(), row.status(), row.reason(), row.sourceVersion(), row.translationVersion(),
                row.conflictId(), row.conflictReason(), row.conflictValidFrom(), row.conflictValidTo(),
                documentId, row.segmentId(), row.language(), row.reviewer());
        if (updated == 0) {
            jdbc.update("INSERT INTO review (document_id, segment_id, language, reviewer, decision, status, "
                            + "reason, source_version, translation_version, conflict_id, conflict_reason, "
                            + "conflict_valid_from, conflict_valid_to) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    documentId, row.segmentId(), row.language(), row.reviewer(), row.decision(), row.status(),
                    row.reason(), row.sourceVersion(), row.translationVersion(), row.conflictId(),
                    row.conflictReason(), row.conflictValidFrom(), row.conflictValidTo());
        }
    }

    /**
     * 将指定评审人在文档+语种范围内的全部评审行标记为 CONFLICTED 并固化冲突证据；
     * 已经是 CONFLICTED 的行同样用最新命中的声明刷新证据列（历史证据不删除，只保留最近一次）。
     * 返回受影响行数。
     */
    public int markReviewsConflicted(long documentId, String reviewer, String language, long conflictId,
                                     String reason, long validFrom, long validTo) {
        return jdbc.update(
                "UPDATE review SET status = 'CONFLICTED', conflict_id = ?, conflict_reason = ?, "
                        + "conflict_valid_from = ?, conflict_valid_to = ? "
                        + "WHERE document_id = ? AND reviewer = ? AND language = ?",
                conflictId, reason, validFrom, validTo, documentId, reviewer, language);
    }

    /**
     * 解除冲突后重算：把因指定冲突声明被排除的评审行恢复为 ACTIVE（解除只影响后续，评审版本仍存于行内，
     * 是否计入法定人数由发布按当前译文版本判定）。返回受影响行数。
     */
    public int reactivateReviewsByConflict(long conflictId) {
        return jdbc.update("UPDATE review SET status = 'ACTIVE' WHERE conflict_id = ? AND status = 'CONFLICTED'",
                conflictId);
    }

    /** 查询因指定冲突声明被排除、当前仍为 CONFLICTED 的评审行（解除时按其他仍有效冲突重算用）。 */
    public List<ReviewRow> listConflictedReviewsByConflict(long conflictId) {
        return jdbc.query(
                "SELECT segment_id, language, reviewer, decision, status, reason, source_version, "
                        + "translation_version, conflict_id, conflict_reason, conflict_valid_from, "
                        + "conflict_valid_to FROM review WHERE conflict_id = ? AND status = 'CONFLICTED'",
                REVIEW_MAPPER, conflictId);
    }

    /** 插入冲突声明并返回自增 ID。 */
    public long insertConflict(ConflictRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO reviewer_conflict (document_id, language, reviewer, valid_from, valid_to, reason, "
                            + "status, declared_by, declared_at) VALUES (?, ?, ?, ?, ?, ?, 'DECLARED', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, row.documentId());
            ps.setString(2, row.language());
            ps.setString(3, row.reviewer());
            ps.setLong(4, row.validFrom());
            ps.setLong(5, row.validTo());
            ps.setString(6, row.reason());
            ps.setString(7, row.declaredBy());
            ps.setLong(8, row.declaredAt());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("冲突声明创建后未返回自增主键");
        }
        return key.longValue();
    }

    /** 按 ID 查询冲突声明并加行级写锁，用于解除串行化。 */
    public Optional<ConflictRow> findConflictForUpdate(long conflictId) {
        List<ConflictRow> rows = jdbc.query(
                "SELECT conflict_id, document_id, language, reviewer, valid_from, valid_to, reason, status, "
                        + "declared_by, declared_at, lifted_by, lifted_at, lift_reason "
                        + "FROM reviewer_conflict WHERE conflict_id = ? FOR UPDATE",
                CONFLICT_MAPPER, conflictId);
        return rows.stream().findFirst();
    }

    public Optional<ConflictRow> findConflict(long conflictId) {
        List<ConflictRow> rows = jdbc.query(
                "SELECT conflict_id, document_id, language, reviewer, valid_from, valid_to, reason, status, "
                        + "declared_by, declared_at, lifted_by, lifted_at, lift_reason "
                        + "FROM reviewer_conflict WHERE conflict_id = ?",
                CONFLICT_MAPPER, conflictId);
        return rows.stream().findFirst();
    }

    /** 列出文档内全部冲突声明，按冲突 ID 升序。 */
    public List<ConflictRow> listConflicts(long documentId) {
        return jdbc.query(
                "SELECT conflict_id, document_id, language, reviewer, valid_from, valid_to, reason, status, "
                        + "declared_by, declared_at, lifted_by, lifted_at, lift_reason "
                        + "FROM reviewer_conflict WHERE document_id = ? ORDER BY conflict_id",
                CONFLICT_MAPPER, documentId);
    }

    /** 列出文档内某评审人某语种当前状态为 DECLARED 的全部声明（登记重叠校验用）。 */
    public List<ConflictRow> listDeclaredConflicts(long documentId, String reviewer, String language) {
        return jdbc.query(
                "SELECT conflict_id, document_id, language, reviewer, valid_from, valid_to, reason, status, "
                        + "declared_by, declared_at, lifted_by, lifted_at, lift_reason "
                        + "FROM reviewer_conflict WHERE document_id = ? AND reviewer = ? AND language = ? "
                        + "AND status = 'DECLARED' ORDER BY valid_from, conflict_id",
                CONFLICT_MAPPER, documentId, reviewer, language);
    }

    /**
     * 查询某评审人在文档+语种范围、指定 UTC 时刻命中的有效声明（DECLARED 且左闭右开覆盖该时刻），
     * 按区间起点升序、冲突 ID 升序，保证命中裁决稳定。
     */
    public List<ConflictRow> findActiveConflicts(long documentId, String reviewer, String language, long now) {
        return jdbc.query(
                "SELECT conflict_id, document_id, language, reviewer, valid_from, valid_to, reason, status, "
                        + "declared_by, declared_at, lifted_by, lifted_at, lift_reason "
                        + "FROM reviewer_conflict WHERE document_id = ? AND reviewer = ? AND language = ? "
                        + "AND status = 'DECLARED' AND valid_from <= ? AND valid_to > ? "
                        + "ORDER BY valid_from, conflict_id",
                CONFLICT_MAPPER, documentId, reviewer, language, now, now);
    }

    /** 解除冲突：更新状态与解除信息；声明字段（区间、原因、声明人）不改写。 */
    public void liftConflict(long conflictId, String liftedBy, long liftedAt, String liftReason) {
        jdbc.update("UPDATE reviewer_conflict SET status = 'LIFTED', lifted_by = ?, lifted_at = ?, "
                        + "lift_reason = ? WHERE conflict_id = ?",
                liftedBy, liftedAt, liftReason, conflictId);
    }

    /** 追加一条冲突历史（只追加，不更新、不删除）。 */
    public void insertConflictHistory(ConflictHistoryRow row) {
        jdbc.update("INSERT INTO conflict_history (conflict_id, document_id, action, actor, actor_role, reason, "
                        + "valid_from, valid_to, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.conflictId(), row.documentId(), row.action(), row.actor(), row.actorRole(), row.reason(),
                row.validFrom(), row.validTo(), row.createdAt());
    }

    /** 查询某文档全部冲突历史，按历史 ID 升序（即追加顺序），输出稳定。 */
    public List<ConflictHistoryRow> listConflictHistory(long documentId) {
        return jdbc.query(
                "SELECT history_id, conflict_id, document_id, action, actor, actor_role, reason, valid_from, "
                        + "valid_to, created_at FROM conflict_history WHERE document_id = ? ORDER BY history_id",
                CONFLICT_HISTORY_MAPPER, documentId);
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
}
