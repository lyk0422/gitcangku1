package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.ConflictDeclarationRow;
import com.example.starter.translation.domain.Rows.ConflictEventRow;
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
 * 业务时间（评审、冲突、历史）由应用按 UTC 以毫秒精度写入。
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

    private static final RowMapper<ApprovalRow> APPROVAL_MAPPER = (rs, n) -> {
        // conflict_id 可空：必须在 getLong 之后立即用 wasNull 判定，中间不能读取其他列。
        long conflictIdValue = rs.getLong("conflict_id");
        Long conflictId = rs.wasNull() ? null : conflictIdValue;
        return new ApprovalRow(
                rs.getLong("review_id"),
                rs.getString("segment_id"), rs.getString("language"), rs.getString("reviewer"),
                rs.getString("decision"), rs.getString("decision_reason"),
                rs.getString("review_status"), conflictId,
                rs.getInt("source_version"), rs.getInt("translation_version"),
                rs.getTimestamp("reviewed_at").toInstant());
    };

    private static final RowMapper<TermRuleRow> TERM_RULE_MAPPER = (rs, n) -> new TermRuleRow(
            rs.getString("source_term"), rs.getString("language"), rs.getString("required_translation"));

    private static final RowMapper<ConflictDeclarationRow> CONFLICT_MAPPER = (rs, n) -> new ConflictDeclarationRow(
            rs.getLong("conflict_id"), rs.getLong("document_id"), rs.getString("reviewer"),
            rs.getString("language"), rs.getTimestamp("valid_from").toInstant(),
            rs.getTimestamp("valid_to").toInstant(), rs.getString("reason"),
            rs.getString("conflict_status"), rs.getString("declared_by"),
            rs.getTimestamp("declared_at").toInstant(),
            rs.getString("resolved_by"),
            instantOrNull(rs.getTimestamp("resolved_at")),
            rs.getString("resolve_reason"));

    private static final RowMapper<ConflictEventRow> CONFLICT_EVENT_MAPPER = (rs, n) -> new ConflictEventRow(
            rs.getLong("event_id"), rs.getLong("conflict_id"), rs.getLong("document_id"),
            rs.getString("event_type"), rs.getString("actor"), rs.getString("actor_role"),
            rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_to").toInstant(),
            rs.getString("reason"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public TranslationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
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

    /** 查询文档全部评审（含历史 SUPERSEDED、CONFLICTED 与版本失效行），按记录顺序稳定排序。 */
    public List<ApprovalRow> listApprovals(long documentId) {
        return jdbc.query(approvalSelect() + " WHERE document_id = ? "
                        + "ORDER BY segment_id, language, reviewer, review_id",
                APPROVAL_MAPPER, documentId);
    }

    private static String approvalSelect() {
        return "SELECT review_id, segment_id, language, reviewer, decision, decision_reason, "
                + "review_status, conflict_id, source_version, translation_version, reviewed_at FROM approval";
    }

    /**
     * 追加一条评审（只增）：同一评审人重复提交产生新 review_id 行，旧行内容与状态永不改写；
     * “评审人最新评审”由最大 review_id 判定。
     */
    public void insertReview(long documentId, ApprovalRow row) {
        jdbc.update("INSERT INTO approval (document_id, segment_id, language, reviewer, decision, "
                        + "decision_reason, review_status, conflict_id, source_version, translation_version, "
                        + "reviewed_at) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', NULL, ?, ?, ?)",
                documentId, row.segmentId(), row.language(), row.reviewer(), row.decision(),
                row.decisionReason(), row.sourceVersion(), row.translationVersion(),
                Timestamp.from(row.reviewedAt()));
    }

    /**
     * 将某评审人在文档某语种的全部 ACTIVE 既有评审标记为 CONFLICTED 并固化冲突 ID；
     * 已是 CONFLICTED 的行保留首次固化的证据（冲突 ID、评审时间）不变。返回被更新行数。
     * 只改状态列，不改写评审时间等旧证据。
     */
    public int markReviewsConflicted(long documentId, String reviewer, String language, long conflictId) {
        return jdbc.update(
                "UPDATE approval SET review_status = 'CONFLICTED', conflict_id = ? "
                        + "WHERE document_id = ? AND reviewer = ? AND language = ? AND review_status = 'ACTIVE'",
                conflictId, documentId, reviewer, language);
    }

    /** 插入冲突声明并返回自增冲突 ID。区间为 UTC 左闭右开。 */
    public long insertConflictDeclaration(ConflictDeclarationRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO conflict_declaration (document_id, reviewer, language, valid_from, valid_to, "
                            + "reason, conflict_status, declared_by, declared_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"conflict_id"});
            ps.setLong(1, row.documentId());
            ps.setString(2, row.reviewer());
            ps.setString(3, row.language());
            ps.setTimestamp(4, Timestamp.from(row.validFrom()));
            ps.setTimestamp(5, Timestamp.from(row.validTo()));
            ps.setString(6, row.reason());
            ps.setString(7, row.conflictStatus());
            ps.setString(8, row.declaredBy());
            ps.setTimestamp(9, Timestamp.from(row.declaredAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("冲突声明创建后未返回自增主键");
        }
        return key.longValue();
    }

    /** 按 ID 加行锁查询冲突声明。 */
    public Optional<ConflictDeclarationRow> findConflictForUpdate(long conflictId) {
        List<ConflictDeclarationRow> rows = jdbc.query(conflictSelect() + " WHERE conflict_id = ? FOR UPDATE",
                CONFLICT_MAPPER, conflictId);
        return rows.stream().findFirst();
    }

    /** 按 ID 查询冲突声明。 */
    public Optional<ConflictDeclarationRow> findConflict(long conflictId) {
        List<ConflictDeclarationRow> rows = jdbc.query(conflictSelect() + " WHERE conflict_id = ?",
                CONFLICT_MAPPER, conflictId);
        return rows.stream().findFirst();
    }

    /** 查询文档全部冲突声明，按冲突 ID 升序稳定输出。 */
    public List<ConflictDeclarationRow> listConflicts(long documentId) {
        return jdbc.query(conflictSelect() + " WHERE document_id = ? ORDER BY conflict_id",
                CONFLICT_MAPPER, documentId);
    }

    private static String conflictSelect() {
        return "SELECT conflict_id, document_id, reviewer, language, valid_from, valid_to, reason, "
                + "conflict_status, declared_by, declared_at, resolved_by, resolved_at, resolve_reason "
                + "FROM conflict_declaration";
    }

    /** 解除冲突：写入解除人、解除时间与原因，状态置为 RESOLVED。 */
    public void resolveConflict(long conflictId, String resolvedBy, String reason, Instant resolvedAt) {
        jdbc.update("UPDATE conflict_declaration SET conflict_status = 'RESOLVED', resolved_by = ?, "
                        + "resolved_at = ?, resolve_reason = ? WHERE conflict_id = ?",
                resolvedBy, Timestamp.from(resolvedAt), reason, conflictId);
    }

    /** 追加一条只增冲突历史事件。 */
    public void insertConflictEvent(ConflictEventRow event) {
        jdbc.update("INSERT INTO conflict_event (conflict_id, document_id, event_type, actor, actor_role, "
                        + "valid_from, valid_to, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                event.conflictId(), event.documentId(), event.eventType(), event.actor(), event.actorRole(),
                Timestamp.from(event.validFrom()), Timestamp.from(event.validTo()), event.reason(),
                Timestamp.from(event.createdAt()));
    }

    /** 查询文档全部冲突历史，按事件 ID 升序。 */
    public List<ConflictEventRow> listConflictEvents(long documentId) {
        return jdbc.query("SELECT event_id, conflict_id, document_id, event_type, actor, actor_role, "
                        + "valid_from, valid_to, reason, created_at FROM conflict_event "
                        + "WHERE document_id = ? ORDER BY event_id",
                CONFLICT_EVENT_MAPPER, documentId);
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
