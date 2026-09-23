package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.RequestLogRow;
import static com.example.starter.translation.domain.Rows.SEGMENT_CURRENT;
import static com.example.starter.translation.domain.Rows.SEGMENT_SUPERSEDED;
import com.example.starter.translation.domain.Rows.SegmentLineageRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.StructureChangeRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationLineageRow;
import com.example.starter.translation.domain.Rows.TranslationReferenceRow;
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
            rs.getString("segment_id"), rs.getString("source_text"), rs.getInt("source_version"),
            rs.getString("status"), rs.getInt("position"),
            rs.getString("created_change_key"), rs.getString("superseded_change_key"));

    private static final RowMapper<TranslationRow> TRANSLATION_MAPPER = (rs, n) -> new TranslationRow(
            rs.getString("segment_id"), rs.getString("language"), rs.getString("content"),
            rs.getString("author"), rs.getInt("source_version"), rs.getInt("translation_version"),
            rs.getInt("term_version"));

    private static final RowMapper<ApprovalRow> APPROVAL_MAPPER = (rs, n) -> new ApprovalRow(
            rs.getString("segment_id"), rs.getString("language"), rs.getString("reviewer"),
            rs.getInt("source_version"), rs.getInt("translation_version"));

    private static final RowMapper<TermRuleRow> TERM_RULE_MAPPER = (rs, n) -> new TermRuleRow(
            rs.getString("source_term"), rs.getString("language"), rs.getString("required_translation"));

    private static final RowMapper<StructureChangeRow> STRUCTURE_CHANGE_MAPPER = (rs, n) ->
            new StructureChangeRow(rs.getString("change_key"), rs.getLong("document_id"),
                    rs.getString("change_type"), rs.getInt("expected_document_version"),
                    rs.getInt("document_version"), rs.getInt("expected_term_version"));

    private static final RowMapper<SegmentLineageRow> SEGMENT_LINEAGE_MAPPER = (rs, n) ->
            new SegmentLineageRow(rs.getString("change_key"), rs.getString("old_segment_id"),
                    rs.getString("new_segment_id"), rs.getInt("ordinal"));

    private static final RowMapper<TranslationLineageRow> TRANSLATION_LINEAGE_MAPPER = (rs, n) ->
            new TranslationLineageRow(rs.getString("change_key"), rs.getString("language"),
                    rs.getString("old_segment_id"), rs.getString("new_segment_id"),
                    rs.getInt("ordinal"), rs.getInt("start_offset"), rs.getInt("end_offset"));

    private static final RowMapper<TranslationReferenceRow> REFERENCE_MAPPER = (rs, n) ->
            new TranslationReferenceRow(rs.getString("new_segment_id"), rs.getString("language"),
                    rs.getString("change_key"), rs.getString("content"), rs.getString("boundary_json"));

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

    /** 插入段（建文档或直接增段），初始源文版本 1、状态 CURRENT，createdChangeKey 为 null。 */
    public void insertSegment(long documentId, String segmentId, String sourceText, int position) {
        jdbc.update("INSERT INTO segment (document_id, segment_id, source_text, source_version, status, position) "
                        + "VALUES (?, ?, ?, 1, ?, ?)",
                documentId, segmentId, sourceText, SEGMENT_CURRENT, position);
    }

    /** 插入结构修订产生的新段，初始源文版本 1、状态 CURRENT，记录创建它的 changeKey。 */
    public void insertStructuredSegment(long documentId, String segmentId, String sourceText, int position,
                                        String changeKey) {
        jdbc.update("INSERT INTO segment (document_id, segment_id, source_text, source_version, status, position, "
                        + "created_change_key) VALUES (?, ?, ?, 1, ?, ?, ?)",
                documentId, segmentId, sourceText, SEGMENT_CURRENT, position, changeKey);
    }

    /** 查询当前段（CURRENT）；已被结构修订取代的段不可再编辑，按不存在处理。 */
    public Optional<SegmentRow> findSegment(long documentId, String segmentId) {
        List<SegmentRow> rows = jdbc.query(
                "SELECT segment_id, source_text, source_version, status, position, created_change_key, "
                        + "superseded_change_key FROM segment "
                        + "WHERE document_id = ? AND segment_id = ? AND status = ?",
                SEGMENT_MAPPER, documentId, segmentId, SEGMENT_CURRENT);
        return rows.stream().findFirst();
    }

    /** 查询任意状态段（含 SUPERSEDED），用于新段键全局唯一性校验。 */
    public Optional<SegmentRow> findAnySegment(long documentId, String segmentId) {
        List<SegmentRow> rows = jdbc.query(
                "SELECT segment_id, source_text, source_version, status, position, created_change_key, "
                        + "superseded_change_key FROM segment WHERE document_id = ? AND segment_id = ?",
                SEGMENT_MAPPER, documentId, segmentId);
        return rows.stream().findFirst();
    }

    /** 全部当前段，按当前结构序号排序。 */
    public List<SegmentRow> listCurrentSegments(long documentId) {
        return jdbc.query(
                "SELECT segment_id, source_text, source_version, status, position, created_change_key, "
                        + "superseded_change_key FROM segment WHERE document_id = ? AND status = ? "
                        + "ORDER BY position, segment_id",
                SEGMENT_MAPPER, documentId, SEGMENT_CURRENT);
    }

    /** 全部段（含 SUPERSEDED），按序号与状态排序，供结构溯源查询。 */
    public List<SegmentRow> listAllSegments(long documentId) {
        return jdbc.query(
                "SELECT segment_id, source_text, source_version, status, position, created_change_key, "
                        + "superseded_change_key FROM segment WHERE document_id = ? "
                        + "ORDER BY position, status, segment_id",
                SEGMENT_MAPPER, documentId);
    }

    /** 当前结构最大序号；无当前段时返回 0。 */
    public int maxPosition(long documentId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(position), 0) FROM segment WHERE document_id = ? AND status = ?",
                Integer.class, documentId, SEGMENT_CURRENT);
        return max == null ? 0 : max;
    }

    /** 重排当前段序号。 */
    public void updatePosition(long documentId, String segmentId, int position) {
        jdbc.update("UPDATE segment SET position = ? WHERE document_id = ? AND segment_id = ?",
                position, documentId, segmentId);
    }

    /** 将旧段标记为 SUPERSEDED 并记录取代它的 changeKey，源文与版本保留供溯源。 */
    public void supersedeSegment(long documentId, String segmentId, String changeKey) {
        jdbc.update("UPDATE segment SET status = ?, superseded_change_key = ? "
                        + "WHERE document_id = ? AND segment_id = ?",
                SEGMENT_SUPERSEDED, changeKey, documentId, segmentId);
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

    /** 插入结构修订事务记录；changeKey 全局唯一，冲突时由调用方转 409。 */
    public void insertStructureChange(StructureChangeRow row) {
        jdbc.update("INSERT INTO structure_change (change_key, document_id, change_type, expected_document_version, "
                        + "document_version, expected_term_version) VALUES (?, ?, ?, ?, ?, ?)",
                row.changeKey(), row.documentId(), row.changeType(), row.expectedDocumentVersion(),
                row.documentVersion(), row.expectedTermVersion());
    }

    /** 按全局 changeKey 查询结构修订记录。 */
    public Optional<StructureChangeRow> findStructureChange(String changeKey) {
        List<StructureChangeRow> rows = jdbc.query(
                "SELECT change_key, document_id, change_type, expected_document_version, document_version, "
                        + "expected_term_version FROM structure_change WHERE change_key = ?",
                STRUCTURE_CHANGE_MAPPER, changeKey);
        return rows.stream().findFirst();
    }

    /** 查询文档的全部结构修订，按生成的文档版本排序。 */
    public List<StructureChangeRow> listStructureChanges(long documentId) {
        return jdbc.query(
                "SELECT change_key, document_id, change_type, expected_document_version, document_version, "
                        + "expected_term_version FROM structure_change WHERE document_id = ? "
                        + "ORDER BY document_version",
                STRUCTURE_CHANGE_MAPPER, documentId);
    }

    public void insertSegmentLineage(long documentId, SegmentLineageRow row) {
        jdbc.update("INSERT INTO segment_lineage (document_id, change_key, old_segment_id, new_segment_id, ordinal) "
                        + "VALUES (?, ?, ?, ?, ?)",
                documentId, row.changeKey(), row.oldSegmentId(), row.newSegmentId(), row.ordinal());
    }

    /** 查询某次结构修订的全部源段血缘，按新段与旧段次序排序。 */
    public List<SegmentLineageRow> listSegmentLineage(long documentId, String changeKey) {
        return jdbc.query(
                "SELECT change_key, old_segment_id, new_segment_id, ordinal FROM segment_lineage "
                        + "WHERE document_id = ? AND change_key = ? ORDER BY new_segment_id, ordinal, old_segment_id",
                SEGMENT_LINEAGE_MAPPER, documentId, changeKey);
    }

    public void insertTranslationLineage(long documentId, TranslationLineageRow row) {
        jdbc.update("INSERT INTO translation_lineage (document_id, change_key, language, old_segment_id, "
                        + "new_segment_id, ordinal, start_offset, end_offset) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                documentId, row.changeKey(), row.language(), row.oldSegmentId(), row.newSegmentId(),
                row.ordinal(), row.startOffset(), row.endOffset());
    }

    /** 查询某次结构修订全部语言的译文片段血缘，按语言、新段、片段次序排序。 */
    public List<TranslationLineageRow> listTranslationLineage(long documentId, String changeKey) {
        return jdbc.query(
                "SELECT change_key, language, old_segment_id, new_segment_id, ordinal, start_offset, end_offset "
                        + "FROM translation_lineage WHERE document_id = ? AND change_key = ? "
                        + "ORDER BY language, new_segment_id, ordinal",
                TRANSLATION_LINEAGE_MAPPER, documentId, changeKey);
    }

    /** 插入结构修订参考候选（新段+语言唯一，首次结构修订产生）。 */
    public void insertTranslationReference(long documentId, TranslationReferenceRow row) {
        jdbc.update("INSERT INTO translation_reference (document_id, new_segment_id, language, change_key, content, "
                        + "boundary_json) VALUES (?, ?, ?, ?, ?, ?)",
                documentId, row.newSegmentId(), row.language(), row.changeKey(), row.content(), row.boundaryJson());
    }

    /** 查询某次结构修订产生的全部参考候选，按新段、语言排序。 */
    public List<TranslationReferenceRow> listReferencesByChange(long documentId, String changeKey) {
        return jdbc.query(
                "SELECT new_segment_id, language, change_key, content, boundary_json FROM translation_reference "
                        + "WHERE document_id = ? AND change_key = ? ORDER BY new_segment_id, language",
                REFERENCE_MAPPER, documentId, changeKey);
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
