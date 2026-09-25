package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.RequestLogRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermFreezeEntryRow;
import com.example.starter.translation.domain.Rows.TermFreezeRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
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
            rs.getInt("term_version"),
            rs.getInt("document_version"));

    private static final String DOCUMENT_COLUMNS =
            "document_id, target_languages, draft_version, published_version, term_version, document_version";

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

    private final JdbcTemplate jdbc;

    public TranslationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入文档并返回自增 ID，初始草稿版本 1、发布版本 0、术语版本 0、文档版本 1。 */
    public long insertDocument(List<String> targetLanguages) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO document (target_languages, draft_version, published_version, term_version, "
                            + "document_version) VALUES (?, 1, 0, 0, 1)",
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
                "SELECT " + DOCUMENT_COLUMNS + " FROM document WHERE document_id = ? FOR UPDATE",
                DOCUMENT_MAPPER, documentId);
        return rows.stream().findFirst();
    }

    /** 只读查询文档（不加锁），用于快照查询。 */
    public Optional<DocumentRow> findDocument(long documentId) {
        List<DocumentRow> rows = jdbc.query(
                "SELECT " + DOCUMENT_COLUMNS + " FROM document WHERE document_id = ?",
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

    public void updateDocumentVersion(long documentId, int documentVersion) {
        jdbc.update("UPDATE document SET document_version = ? WHERE document_id = ?",
                documentVersion, documentId);
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

    private static final RowMapper<TermFreezeRow> TERM_FREEZE_MAPPER = (rs, n) -> new TermFreezeRow(
            rs.getInt("freeze_version"), rs.getInt("document_version"), rs.getString("status"),
            rs.getString("freeze_key"), rs.getString("operator"));

    private static final RowMapper<TermFreezeEntryRow> TERM_FREEZE_ENTRY_MAPPER =
            (rs, n) -> new TermFreezeEntryRow(rs.getString("normalized_term"), rs.getString("language"),
                    rs.getString("allowed_translation"));

    /** 插入术语冻结主记录；active_version 与 document_id 组成唯一约束，保证同版本至多一份有效冻结。 */
    public void insertTermFreeze(long documentId, TermFreezeRow row) {
        jdbc.update("INSERT INTO term_freeze (document_id, freeze_version, document_version, status, "
                        + "freeze_key, operator, active_version) VALUES (?, ?, ?, ?, ?, ?, ?)",
                documentId, row.freezeVersion(), row.documentVersion(), row.status(), row.freezeKey(),
                row.operator(), row.documentVersion());
    }

    /** 插入一条冻结条目；条目创建后不可原地修改。 */
    public void insertTermFreezeEntry(long documentId, int freezeVersion, TermFreezeEntryRow entry) {
        jdbc.update("INSERT INTO term_freeze_entry (document_id, freeze_version, normalized_term, language, "
                        + "allowed_translation) VALUES (?, ?, ?, ?, ?)",
                documentId, freezeVersion, entry.normalizedTerm(), entry.language(), entry.allowedTranslation());
    }

    /** 查询文档当前状态为 ACTIVE 的冻结（可能绑定已失效的旧文档版本）。 */
    public Optional<TermFreezeRow> findActiveFreeze(long documentId) {
        List<TermFreezeRow> rows = jdbc.query(
                "SELECT freeze_version, document_version, status, freeze_key, operator FROM term_freeze "
                        + "WHERE document_id = ? AND status = 'ACTIVE'",
                TERM_FREEZE_MAPPER, documentId);
        return rows.stream().findFirst();
    }

    /** 按冻结版本查询冻结记录。 */
    public Optional<TermFreezeRow> findFreeze(long documentId, int freezeVersion) {
        List<TermFreezeRow> rows = jdbc.query(
                "SELECT freeze_version, document_version, status, freeze_key, operator FROM term_freeze "
                        + "WHERE document_id = ? AND freeze_version = ?",
                TERM_FREEZE_MAPPER, documentId, freezeVersion);
        return rows.stream().findFirst();
    }

    /** 按 freezeKey 指纹查询冻结记录，用于同键重放。 */
    public Optional<TermFreezeRow> findFreezeByKey(String freezeKey) {
        List<TermFreezeRow> rows = jdbc.query(
                "SELECT freeze_version, document_version, status, freeze_key, operator FROM term_freeze "
                        + "WHERE freeze_key = ?",
                TERM_FREEZE_MAPPER, freezeKey);
        return rows.stream().findFirst();
    }

    /** 查询文档已用的最大冻结版本号，无冻结时返回 0。 */
    public int maxFreezeVersion(long documentId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(freeze_version), 0) FROM term_freeze WHERE document_id = ?",
                Integer.class, documentId);
        return max == null ? 0 : max;
    }

    /** 查询指定冻结版本的全部条目，按术语、语言、译法排序保证稳定输出。 */
    public List<TermFreezeEntryRow> listFreezeEntries(long documentId, int freezeVersion) {
        return jdbc.query(
                "SELECT normalized_term, language, allowed_translation FROM term_freeze_entry "
                        + "WHERE document_id = ? AND freeze_version = ? "
                        + "ORDER BY normalized_term, language, allowed_translation",
                TERM_FREEZE_ENTRY_MAPPER, documentId, freezeVersion);
    }

    /** 撤销指定冻结：状态置 REVOKED、active_version 置 NULL 并记录撤销时间。 */
    public void revokeFreeze(long documentId, int freezeVersion) {
        jdbc.update("UPDATE term_freeze SET status = 'REVOKED', active_version = NULL, "
                        + "revoked_at = CURRENT_TIMESTAMP "
                        + "WHERE document_id = ? AND freeze_version = ?",
                documentId, freezeVersion);
    }

    /** 撤销文档上绑定其他文档版本的有效冻结（旧版本冻结不能复用）。 */
    public void revokeStaleActiveFreezes(long documentId, int currentDocumentVersion) {
        jdbc.update("UPDATE term_freeze SET status = 'REVOKED', active_version = NULL, "
                        + "revoked_at = CURRENT_TIMESTAMP "
                        + "WHERE document_id = ? AND status = 'ACTIVE' AND document_version <> ?",
                documentId, currentDocumentVersion);
    }
}
