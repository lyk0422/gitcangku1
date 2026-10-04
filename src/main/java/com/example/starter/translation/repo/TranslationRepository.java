package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows.AnnotationRow;
import com.example.starter.translation.domain.Rows.ApprovalRow;
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

    private static final RowMapper<AnnotationRow> ANNOTATION_MAPPER = (rs, n) -> new AnnotationRow(
            rs.getLong("annotation_id"), rs.getString("segment_id"), rs.getString("language"),
            rs.getInt("translation_version"),
            (Integer) rs.getObject("start_offset"), (Integer) rs.getObject("end_offset"),
            rs.getString("quote_text"), rs.getString("body"), rs.getString("creator"),
            rs.getString("status"));

    /** 插入批注并返回自增 ID，初始状态 OPEN。 */
    public long insertAnnotation(long documentId, AnnotationRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO annotation (document_id, segment_id, language, translation_version, "
                            + "start_offset, end_offset, quote_text, body, creator, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"annotation_id"});
            ps.setLong(1, documentId);
            ps.setString(2, row.segmentId());
            ps.setString(3, row.language());
            ps.setInt(4, row.translationVersion());
            ps.setInt(5, row.startOffset());
            ps.setInt(6, row.endOffset());
            ps.setString(7, row.quote());
            ps.setString(8, row.body());
            ps.setString(9, row.creator());
            ps.setString(10, row.status());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("批注创建后未返回自增主键");
        }
        return key.longValue();
    }

    /** 按 ID 与所属路径查询批注。 */
    public Optional<AnnotationRow> findAnnotation(long documentId, String segmentId, String language,
                                                  long annotationId) {
        List<AnnotationRow> rows = jdbc.query(
                "SELECT annotation_id, segment_id, language, translation_version, start_offset, end_offset, "
                        + "quote_text, body, creator, status FROM annotation "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ? AND annotation_id = ?",
                ANNOTATION_MAPPER, documentId, segmentId, language, annotationId);
        return rows.stream().findFirst();
    }

    /** 查询段落某语言下全部批注（含已解决），按 ID 升序保证稳定输出。 */
    public List<AnnotationRow> listAnnotations(long documentId, String segmentId, String language) {
        return jdbc.query(
                "SELECT annotation_id, segment_id, language, translation_version, start_offset, end_offset, "
                        + "quote_text, body, creator, status FROM annotation "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ? ORDER BY annotation_id",
                ANNOTATION_MAPPER, documentId, segmentId, language);
    }

    /** 查询段落某语言下状态为 OPEN 的批注（仅这些持有有效锚点，参与定点编辑平移）。 */
    public List<AnnotationRow> listOpenAnnotations(long documentId, String segmentId, String language) {
        return jdbc.query(
                "SELECT annotation_id, segment_id, language, translation_version, start_offset, end_offset, "
                        + "quote_text, body, creator, status FROM annotation "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ? AND status = 'OPEN' "
                        + "ORDER BY annotation_id",
                ANNOTATION_MAPPER, documentId, segmentId, language);
    }

    /** 统计段落某语言下未解决（OPEN 或 ORPHAN）批注数。 */
    public int countUnresolvedAnnotations(long documentId, String segmentId, String language) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM annotation WHERE document_id = ? AND segment_id = ? AND language = ? "
                        + "AND status IN ('OPEN', 'ORPHAN')",
                Integer.class, documentId, segmentId, language);
        return count == null ? 0 : count;
    }

    /** 统计文档全部未解决（OPEN 或 ORPHAN）批注数，用于发布校验。 */
    public int countUnresolvedAnnotations(long documentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM annotation WHERE document_id = ? AND status IN ('OPEN', 'ORPHAN')",
                Integer.class, documentId);
        return count == null ? 0 : count;
    }

    /** 定点编辑后整体平移批注锚点。 */
    public void shiftAnnotation(long annotationId, int startOffset, int endOffset) {
        jdbc.update("UPDATE annotation SET start_offset = ?, end_offset = ? WHERE annotation_id = ?",
                startOffset, endOffset, annotationId);
    }

    /** 批注孤立：状态置 ORPHAN 并清空锚点，仅保留原引用。 */
    public void markAnnotationOrphan(long annotationId) {
        jdbc.update("UPDATE annotation SET status = 'ORPHAN', start_offset = NULL, end_offset = NULL "
                + "WHERE annotation_id = ?", annotationId);
    }

    /** 确认解决：状态置 RESOLVED 并记录解决时间。 */
    public void resolveAnnotation(long annotationId) {
        jdbc.update("UPDATE annotation SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP "
                + "WHERE annotation_id = ?", annotationId);
    }

    /** 删除段落某语言下的批准（新增批注使当前批准失效）。 */
    public void deleteApproval(long documentId, String segmentId, String language) {
        jdbc.update("DELETE FROM approval WHERE document_id = ? AND segment_id = ? AND language = ?",
                documentId, segmentId, language);
    }
}
