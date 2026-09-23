package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.LineageFragmentRow;
import com.example.starter.translation.domain.Rows.RequestLogRow;
import com.example.starter.translation.domain.Rows.SegmentLineageRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.StructureChangeRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
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
            rs.getInt("position"), "CURRENT".equals(rs.getString("status")));

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
            new StructureChangeRow(rs.getString("change_key"), rs.getString("operation"),
                    rs.getInt("document_version"), rs.getInt("expected_document_version"));

    private static final RowMapper<SegmentLineageRow> SEGMENT_LINEAGE_MAPPER = (rs, n) ->
            new SegmentLineageRow(rs.getString("change_key"), rs.getString("old_segment_id"),
                    rs.getInt("old_source_version"), rs.getString("new_segment_id"), rs.getInt("ordinal"));

    private static final RowMapper<TranslationReferenceRow> TRANSLATION_REFERENCE_MAPPER = (rs, n) ->
            new TranslationReferenceRow(rs.getString("change_key"), rs.getString("new_segment_id"),
                    rs.getString("language"), rs.getString("content"));

    private static final RowMapper<LineageFragmentRow> LINEAGE_FRAGMENT_MAPPER = (rs, n) ->
            new LineageFragmentRow(rs.getString("change_key"), rs.getString("new_segment_id"),
                    rs.getString("language"), rs.getString("old_segment_id"),
                    rs.getInt("old_translation_version"), rs.getInt("ordinal"),
                    rs.getInt("start_offset"), rs.getInt("end_offset"));

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
        Integer maxPosition = jdbc.queryForObject(
                "SELECT COALESCE(MAX(position), -1) FROM segment WHERE document_id = ? AND status = 'CURRENT'",
                Integer.class, documentId);
        jdbc.update("INSERT INTO segment (document_id, segment_id, source_text, source_version, position, status) "
                        + "VALUES (?, ?, ?, 1, ?, 'CURRENT')",
                documentId, segmentId, sourceText, maxPosition == null ? 0 : maxPosition + 1);
    }

    public Optional<SegmentRow> findSegment(long documentId, String segmentId) {
        List<SegmentRow> rows = jdbc.query(
                "SELECT segment_id, source_text, source_version, position, status FROM segment "
                        + "WHERE document_id = ? AND segment_id = ?",
                SEGMENT_MAPPER, documentId, segmentId);
        return rows.stream().findFirst();
    }

    /** 查询当前结构中的全部段落，按位置升序。 */
    public List<SegmentRow> listSegments(long documentId) {
        return jdbc.query(
                "SELECT segment_id, source_text, source_version, position, status FROM segment "
                        + "WHERE document_id = ? AND status = 'CURRENT' ORDER BY position",
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

    /** 结构修订插入新当前段：显式指定位置，源文版本从 1 开始。 */
    public void insertStructureSegment(long documentId, String segmentId, String sourceText, int position) {
        jdbc.update("INSERT INTO segment (document_id, segment_id, source_text, source_version, position, status) "
                        + "VALUES (?, ?, ?, 1, ?, 'CURRENT')",
                documentId, segmentId, sourceText, position);
    }

    /** 将旧段废止为 SUPERSEDED，源文与版本保留不变。 */
    public void markSegmentSuperseded(long documentId, String segmentId) {
        jdbc.update("UPDATE segment SET status = 'SUPERSEDED', superseded_at = CURRENT_TIMESTAMP "
                + "WHERE document_id = ? AND segment_id = ?", documentId, segmentId);
    }

    /** 按给定的当前段 ID 顺序重写连续位置（从 0 开始）。 */
    public void resequencePositions(long documentId, List<String> orderedSegmentIds) {
        for (int i = 0; i < orderedSegmentIds.size(); i++) {
            jdbc.update("UPDATE segment SET position = ? WHERE document_id = ? AND segment_id = ? AND status = 'CURRENT'",
                    i, documentId, orderedSegmentIds.get(i));
        }
    }

    /** 结构修订键是否已存在（文档内唯一）。 */
    public boolean structureChangeExists(long documentId, String changeKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id = ? AND change_key = ?",
                Integer.class, documentId, changeKey);
        return count != null && count > 0;
    }

    /** 结构修订键是否已被任何文档使用（changeKey 全局唯一）。 */
    public boolean structureChangeKeyUsed(String changeKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE change_key = ?",
                Integer.class, changeKey);
        return count != null && count > 0;
    }

    public Optional<StructureChangeRow> findStructureChange(long documentId, String changeKey) {
        List<StructureChangeRow> rows = jdbc.query(
                "SELECT change_key, operation, document_version, expected_document_version "
                        + "FROM structure_change WHERE document_id = ? AND change_key = ?",
                STRUCTURE_CHANGE_MAPPER, documentId, changeKey);
        return rows.stream().findFirst();
    }

    public void insertStructureChange(long documentId, StructureChangeRow row) {
        jdbc.update("INSERT INTO structure_change (document_id, change_key, operation, document_version, "
                        + "expected_document_version) VALUES (?, ?, ?, ?, ?)",
                documentId, row.changeKey(), row.operation(), row.documentVersion(), row.expectedDocumentVersion());
    }

    public void insertSegmentLineage(long documentId, SegmentLineageRow row) {
        jdbc.update("INSERT INTO segment_lineage (document_id, change_key, old_segment_id, old_source_version, "
                        + "new_segment_id, ordinal) VALUES (?, ?, ?, ?, ?, ?)",
                documentId, row.changeKey(), row.oldSegmentId(), row.oldSourceVersion(),
                row.newSegmentId(), row.ordinal());
    }

    /** 查询某次结构修订的全部源段血缘，按新旧段与序号排序保证稳定输出。 */
    public List<SegmentLineageRow> listSegmentLineage(long documentId, String changeKey) {
        return jdbc.query(
                "SELECT change_key, old_segment_id, old_source_version, new_segment_id, ordinal "
                        + "FROM segment_lineage WHERE document_id = ? AND change_key = ? "
                        + "ORDER BY old_segment_id, new_segment_id, ordinal",
                SEGMENT_LINEAGE_MAPPER, documentId, changeKey);
    }

    public void insertTranslationReference(long documentId, TranslationReferenceRow row) {
        jdbc.update("INSERT INTO translation_reference (document_id, change_key, new_segment_id, language, content) "
                        + "VALUES (?, ?, ?, ?, ?)",
                documentId, row.changeKey(), row.newSegmentId(), row.language(), row.content());
    }

    public void insertLineageFragment(long documentId, LineageFragmentRow row) {
        jdbc.update("INSERT INTO lineage_fragment (document_id, change_key, new_segment_id, language, "
                        + "old_segment_id, old_translation_version, ordinal, start_offset, end_offset) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                documentId, row.changeKey(), row.newSegmentId(), row.language(), row.oldSegmentId(),
                row.oldTranslationVersion(), row.ordinal(), row.startOffset(), row.endOffset());
    }

    /** 新→旧方向：查询某新段全部语言的有序译文血缘片段。 */
    public List<LineageFragmentRow> listFragmentsByNewSegment(long documentId, String newSegmentId) {
        return jdbc.query(
                "SELECT change_key, new_segment_id, language, old_segment_id, old_translation_version, "
                        + "ordinal, start_offset, end_offset FROM lineage_fragment "
                        + "WHERE document_id = ? AND new_segment_id = ? ORDER BY language, ordinal",
                LINEAGE_FRAGMENT_MAPPER, documentId, newSegmentId);
    }

    /** 旧→新方向：查询某旧段全部语言的有序译文血缘片段。 */
    public List<LineageFragmentRow> listFragmentsByOldSegment(long documentId, String oldSegmentId) {
        return jdbc.query(
                "SELECT change_key, new_segment_id, language, old_segment_id, old_translation_version, "
                        + "ordinal, start_offset, end_offset FROM lineage_fragment "
                        + "WHERE document_id = ? AND old_segment_id = ? ORDER BY language, new_segment_id, ordinal",
                LINEAGE_FRAGMENT_MAPPER, documentId, oldSegmentId);
    }

    /** 查询若干新段的 REFERENCE 候选，按新段与语言排序。 */
    public List<TranslationReferenceRow> listReferences(long documentId, List<String> newSegmentIds) {
        if (newSegmentIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(newSegmentIds.size(), "?"));
        Object[] args = new Object[newSegmentIds.size() + 1];
        args[0] = documentId;
        for (int i = 0; i < newSegmentIds.size(); i++) {
            args[i + 1] = newSegmentIds.get(i);
        }
        return jdbc.query(
                "SELECT change_key, new_segment_id, language, content FROM translation_reference "
                        + "WHERE document_id = ? AND new_segment_id IN (" + placeholders
                        + ") ORDER BY new_segment_id, language",
                TRANSLATION_REFERENCE_MAPPER, args);
    }

    /** 查询某文档全部源段血缘（当前结构查询用），按修订与序号排序。 */
    public List<SegmentLineageRow> listAllSegmentLineage(long documentId) {
        return jdbc.query(
                "SELECT change_key, old_segment_id, old_source_version, new_segment_id, ordinal "
                        + "FROM segment_lineage WHERE document_id = ? "
                        + "ORDER BY change_key, old_segment_id, ordinal, new_segment_id",
                SEGMENT_LINEAGE_MAPPER, documentId);
    }

    /** 新→旧方向：查询某新段的源段血缘。 */
    public List<SegmentLineageRow> listLineageByNewSegment(long documentId, String newSegmentId) {
        return jdbc.query(
                "SELECT change_key, old_segment_id, old_source_version, new_segment_id, ordinal "
                        + "FROM segment_lineage WHERE document_id = ? AND new_segment_id = ? "
                        + "ORDER BY ordinal, old_segment_id",
                SEGMENT_LINEAGE_MAPPER, documentId, newSegmentId);
    }

    /** 旧→新方向：查询某旧段派生新段的源段血缘。 */
    public List<SegmentLineageRow> listLineageByOldSegment(long documentId, String oldSegmentId) {
        return jdbc.query(
                "SELECT change_key, old_segment_id, old_source_version, new_segment_id, ordinal "
                        + "FROM segment_lineage WHERE document_id = ? AND old_segment_id = ? "
                        + "ORDER BY ordinal, new_segment_id",
                SEGMENT_LINEAGE_MAPPER, documentId, oldSegmentId);
    }
}
