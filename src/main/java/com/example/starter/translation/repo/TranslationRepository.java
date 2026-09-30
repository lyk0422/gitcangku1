package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.FreezeAffectedSegmentRow;
import com.example.starter.translation.domain.Rows.FreezeRow;
import com.example.starter.translation.domain.Rows.FreezeUpdateRow;
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

    private static final RowMapper<FreezeRow> FREEZE_MAPPER = (rs, n) -> new FreezeRow(
            rs.getInt("freeze_id"), rs.getInt("term_version"),
            rs.getLong("start_at"), rs.getLong("end_at"));

    private static final RowMapper<FreezeUpdateRow> FREEZE_UPDATE_MAPPER = (rs, n) -> new FreezeUpdateRow(
            rs.getInt("freeze_id"), rs.getInt("term_version"), rs.getString("request_id"));

    private static final RowMapper<FreezeAffectedSegmentRow> FREEZE_AFFECTED_MAPPER = (rs, n) ->
            new FreezeAffectedSegmentRow(rs.getInt("freeze_id"),
                    rs.getString("segment_id"), rs.getString("language"));

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

    /** 插入冻结窗口；窗口序号由调用方按文档当前最大值加一给出，主键冲突时抛异常。 */
    public void insertFreeze(long documentId, FreezeRow freeze) {
        jdbc.update("INSERT INTO term_freeze (document_id, freeze_id, term_version, start_at, end_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                documentId, freeze.freezeId(), freeze.termVersion(), freeze.startAt(), freeze.endAt());
    }

    /** 查询指定冻结窗口（不加锁），不存在返回空。 */
    public Optional<FreezeRow> findFreeze(long documentId, int freezeId) {
        List<FreezeRow> rows = jdbc.query(
                "SELECT freeze_id, term_version, start_at, end_at FROM term_freeze "
                        + "WHERE document_id = ? AND freeze_id = ?",
                FREEZE_MAPPER, documentId, freezeId);
        return rows.stream().findFirst();
    }

    /** 查询指定冻结窗口并加行级写锁（FOR UPDATE），用于解冻更新串行化。 */
    public Optional<FreezeRow> findFreezeForUpdate(long documentId, int freezeId) {
        List<FreezeRow> rows = jdbc.query(
                "SELECT freeze_id, term_version, start_at, end_at FROM term_freeze "
                        + "WHERE document_id = ? AND freeze_id = ? FOR UPDATE",
                FREEZE_MAPPER, documentId, freezeId);
        return rows.stream().findFirst();
    }

    /** 查询文档全部冻结窗口，按序号升序。 */
    public List<FreezeRow> listFreezes(long documentId) {
        return jdbc.query(
                "SELECT freeze_id, term_version, start_at, end_at FROM term_freeze "
                        + "WHERE document_id = ? ORDER BY freeze_id",
                FREEZE_MAPPER, documentId);
    }

    /** 查询文档尚未完成解冻更新（freeze_term_update 无记录）的冻结窗口序号，按序号升序。 */
    public List<Integer> listUnresolvedFreezeIds(long documentId) {
        return jdbc.queryForList(
                "SELECT f.freeze_id FROM term_freeze f "
                        + "LEFT JOIN freeze_term_update u ON f.document_id = u.document_id AND f.freeze_id = u.freeze_id "
                        + "WHERE f.document_id = ? AND u.freeze_id IS NULL ORDER BY f.freeze_id",
                Integer.class, documentId);
    }

    /**
     * 解冻更新整单校验通过后，将文档全部译文原子绑定到新术语版本（正文与译文版本不变，
     * 受影响段落已按新规则逐段校验；非受影响段落不命中任何新规则，天然合规）。
     */
    public void rebindAllTranslationTermVersions(long documentId, int termVersion) {
        jdbc.update("UPDATE translation SET term_version = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE document_id = ?", termVersion, documentId);
    }

    /** 查询文档当前生效（含给定时刻）的冻结窗口，按序号倒序取至多一条。 */
    public Optional<FreezeRow> findActiveFreeze(long documentId, long nowEpochMilli) {
        List<FreezeRow> rows = jdbc.query(
                "SELECT freeze_id, term_version, start_at, end_at FROM term_freeze "
                        + "WHERE document_id = ? AND start_at <= ? AND end_at > ? ORDER BY freeze_id DESC",
                FREEZE_MAPPER, documentId, nowEpochMilli, nowEpochMilli);
        return rows.stream().findFirst();
    }

    /** 写入解冻更新记录（每个冻结窗口至多一条，主键冲突时抛异常）。 */
    public void insertFreezeUpdate(long documentId, FreezeUpdateRow update) {
        jdbc.update("INSERT INTO freeze_term_update (document_id, freeze_id, term_version, request_id) "
                        + "VALUES (?, ?, ?, ?)",
                documentId, update.freezeId(), update.termVersion(), update.requestId());
    }

    /** 查询冻结窗口的解冻更新记录，不存在返回空。 */
    public Optional<FreezeUpdateRow> findFreezeUpdate(long documentId, int freezeId) {
        List<FreezeUpdateRow> rows = jdbc.query(
                "SELECT freeze_id, term_version, request_id FROM freeze_term_update "
                        + "WHERE document_id = ? AND freeze_id = ?",
                FREEZE_UPDATE_MAPPER, documentId, freezeId);
        return rows.stream().findFirst();
    }

    /** 批量写入解冻更新受影响段落，与解冻更新同一事务提交。 */
    public void insertFreezeAffectedSegments(long documentId, List<FreezeAffectedSegmentRow> segments) {
        jdbc.batchUpdate("INSERT INTO freeze_affected_segment (document_id, freeze_id, segment_id, language) "
                        + "VALUES (?, ?, ?, ?)",
                segments, segments.size(), (ps, row) -> {
                    ps.setLong(1, documentId);
                    ps.setInt(2, row.freezeId());
                    ps.setString(3, row.segmentId());
                    ps.setString(4, row.language());
                });
    }

    /** 查询冻结窗口的全部受影响段落，按段落与语言排序保证稳定输出。 */
    public List<FreezeAffectedSegmentRow> listFreezeAffectedSegments(long documentId, int freezeId) {
        return jdbc.query(
                "SELECT freeze_id, segment_id, language FROM freeze_affected_segment "
                        + "WHERE document_id = ? AND freeze_id = ? ORDER BY segment_id, language",
                FREEZE_AFFECTED_MAPPER, documentId, freezeId);
    }
}
