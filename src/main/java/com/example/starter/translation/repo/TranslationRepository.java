package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.ReferenceCandidateRow;
import com.example.starter.translation.domain.Rows.RequestLogRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.SourceLineageRow;
import com.example.starter.translation.domain.Rows.StructureChangeRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationLineageRow;
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
            rs.getString("status"), rs.getInt("position"));

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

    /**
     * 原子自增草稿版本并返回新版本：UPDATE 在行写锁上按最新已提交值求值，
     * 避免长事务在 FOR UPDATE 排队后基于过期快照自增造成丢失更新。
     */
    public int incrementDraftVersion(long documentId) {
        int updated = jdbc.update(
                "UPDATE document SET draft_version = draft_version + 1 WHERE document_id = ?", documentId);
        if (updated == 0) {
            throw new IllegalStateException("文档不存在: " + documentId);
        }
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, documentId);
        if (draftVersion == null) {
            throw new IllegalStateException("文档不存在: " + documentId);
        }
        return draftVersion;
    }

    /**
     * 结构修订提交闸门：仅当草稿版本与术语版本仍与提交期望一致时才原子自增草稿版本。
     * 并发的源文/译文/术语更新或发布会使条件失配，返回 0 由调用方转 409 整体回滚。
     */
    public int incrementDraftVersionIfMatches(long documentId, int expectedDraftVersion, int expectedTermVersion) {
        return jdbc.update(
                "UPDATE document SET draft_version = draft_version + 1 "
                        + "WHERE document_id = ? AND draft_version = ? AND term_version = ?",
                documentId, expectedDraftVersion, expectedTermVersion);
    }

    /**
     * 术语版本提交闸门：仅当当前术语版本仍等于期望版本时，原子地把术语版本与草稿版本各加一，
     * 返回新术语版本；并发术语更新会使条件失配，返回 -1 由调用方转 409。
     */
    public int advanceTermVersionIfMatches(long documentId, int expectedTermVersion) {
        int updated = jdbc.update(
                "UPDATE document SET term_version = term_version + 1, draft_version = draft_version + 1 "
                        + "WHERE document_id = ? AND term_version = ?",
                documentId, expectedTermVersion);
        if (updated == 0) {
            return -1;
        }
        Integer termVersion = jdbc.queryForObject(
                "SELECT term_version FROM document WHERE document_id = ?", Integer.class, documentId);
        if (termVersion == null) {
            throw new IllegalStateException("文档不存在: " + documentId);
        }
        return termVersion;
    }

    /**
     * 发布提交闸门：仅当草稿版本与发布版本仍等于期望版本时才原子自增发布版本，
     * 返回新发布版本；并发的结构修订或源文/译文更新会改变草稿版本，返回 -1 由调用方转 409。
     */
    public int advancePublishedVersionIfMatches(long documentId, int expectedDraftVersion,
                                                int expectedPublishedVersion) {
        int updated = jdbc.update(
                "UPDATE document SET published_version = published_version + 1 "
                        + "WHERE document_id = ? AND draft_version = ? AND published_version = ?",
                documentId, expectedDraftVersion, expectedPublishedVersion);
        if (updated == 0) {
            return -1;
        }
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, documentId);
        if (publishedVersion == null) {
            throw new IllegalStateException("文档不存在: " + documentId);
        }
        return publishedVersion;
    }

    /** 插入当前段落：源文版本 1，status 由调用方指定位置（追加在当前结构末尾）。 */
    public void insertSegment(long documentId, String segmentId, String sourceText, int position) {
        jdbc.update("INSERT INTO segment (document_id, segment_id, source_text, source_version, status, position) "
                + "VALUES (?, ?, ?, 1, 'CURRENT', ?)",
                documentId, segmentId, sourceText, position);
    }

    /** 插入结构修订产生的新段：源文版本 1，状态 CURRENT。 */
    public void insertNewSegment(long documentId, String segmentId, String sourceText, int position) {
        jdbc.update("INSERT INTO segment (document_id, segment_id, source_text, source_version, status, position) "
                + "VALUES (?, ?, ?, 1, 'CURRENT', ?)",
                documentId, segmentId, sourceText, position);
    }

    /** 判断段落键是否在任意文档中已存在（含已淘汰段），用于结构修订新段键的全局唯一校验。 */
    public boolean segmentKeyExistsGlobally(String segmentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment WHERE segment_id = ?", Integer.class, segmentId);
        return count != null && count > 0;
    }

    /** 查询任意状态段落（含 SUPERSEDED），结构修订血缘校验需要读取旧段。 */
    public Optional<SegmentRow> findAnySegment(long documentId, String segmentId) {
        List<SegmentRow> rows = jdbc.query(
                "SELECT segment_id, source_text, source_version, status, position FROM segment "
                        + "WHERE document_id = ? AND segment_id = ?",
                SEGMENT_MAPPER, documentId, segmentId);
        return rows.stream().findFirst();
    }

    public Optional<SegmentRow> findSegment(long documentId, String segmentId) {
        return findAnySegment(documentId, segmentId).filter(SegmentRow::current);
    }

    /** 列出当前有效段落，按结构顺序排列。 */
    public List<SegmentRow> listSegments(long documentId) {
        return jdbc.query(
                "SELECT segment_id, source_text, source_version, status, position FROM segment "
                        + "WHERE document_id = ? AND status = 'CURRENT' ORDER BY position",
                SEGMENT_MAPPER, documentId);
    }

    /** 列出全部段落（含 SUPERSEDED），按位置与键排序，用于结构查询。 */
    public List<SegmentRow> listAllSegments(long documentId) {
        return jdbc.query(
                "SELECT segment_id, source_text, source_version, status, position FROM segment "
                        + "WHERE document_id = ? ORDER BY position, segment_id",
                SEGMENT_MAPPER, documentId);
    }

    /** 旧段标记为 SUPERSEDED，保留淘汰时位置序号，不再参与当前结构。 */
    public void markSegmentSuperseded(long documentId, String segmentId) {
        jdbc.update("UPDATE segment SET status = 'SUPERSEDED' WHERE document_id = ? AND segment_id = ?",
                documentId, segmentId);
    }

    /** 更新当前段位置，结构修订后重排当前结构。 */
    public void updateSegmentPosition(long documentId, String segmentId, int position) {
        jdbc.update("UPDATE segment SET position = ? WHERE document_id = ? AND segment_id = ?",
                position, documentId, segmentId);
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

    /** 插入结构修订事务记录（changeKey 文档内唯一，重复由主键约束兜底抛 409）。 */
    public void insertStructureChange(long documentId, StructureChangeRow row) {
        jdbc.update("INSERT INTO structure_change (document_id, change_key, change_type, draft_version) "
                        + "VALUES (?, ?, ?, ?)",
                documentId, row.changeKey(), row.changeType(), row.draftVersion());
    }

    public Optional<StructureChangeRow> findStructureChange(long documentId, String changeKey) {
        List<StructureChangeRow> rows = jdbc.query(
                "SELECT change_key, change_type, draft_version FROM structure_change "
                        + "WHERE document_id = ? AND change_key = ?",
                (rs, n) -> new StructureChangeRow(rs.getString(1), rs.getString(2), rs.getInt(3)),
                documentId, changeKey);
        return rows.stream().findFirst();
    }

    /** 插入一条源段血缘。 */
    public void insertSourceLineage(long documentId, SourceLineageRow row) {
        jdbc.update("INSERT INTO source_lineage (document_id, new_segment_id, old_segment_id, ordinal) "
                        + "VALUES (?, ?, ?, ?)",
                documentId, row.newSegmentId(), row.oldSegmentId(), row.ordinal());
    }

    /** 查询新段的源段血缘，按顺序排列；跨语言血缘查询用于沿链向上追溯。 */
    public List<SourceLineageRow> listSourceLineageByNew(long documentId, String newSegmentId) {
        return jdbc.query(
                "SELECT new_segment_id, old_segment_id, ordinal FROM source_lineage "
                        + "WHERE document_id = ? AND new_segment_id = ? ORDER BY ordinal",
                SOURCE_LINEAGE_MAPPER, documentId, newSegmentId);
    }

    /** 查询旧段被哪些新段继承（双向血缘的反向边），按新段与顺序排列。 */
    public List<SourceLineageRow> listSourceLineageByOld(long documentId, String oldSegmentId) {
        return jdbc.query(
                "SELECT new_segment_id, old_segment_id, ordinal FROM source_lineage "
                        + "WHERE document_id = ? AND old_segment_id = ? ORDER BY new_segment_id, ordinal",
                SOURCE_LINEAGE_MAPPER, documentId, oldSegmentId);
    }

    /** 插入一条跨语言译文血缘。 */
    public void insertTranslationLineage(long documentId, TranslationLineageRow row) {
        jdbc.update("INSERT INTO translation_lineage (document_id, new_segment_id, language, old_segment_id, ordinal) "
                        + "VALUES (?, ?, ?, ?, ?)",
                documentId, row.newSegmentId(), row.language(), row.oldSegmentId(), row.ordinal());
    }

    /** 查询某语言下新段的有序旧译文片段来源。 */
    public List<TranslationLineageRow> listTranslationLineage(long documentId, String newSegmentId,
                                                              String language) {
        return jdbc.query(
                "SELECT new_segment_id, language, old_segment_id, ordinal FROM translation_lineage "
                        + "WHERE document_id = ? AND new_segment_id = ? AND language = ? ORDER BY ordinal",
                TRANSLATION_LINEAGE_MAPPER, documentId, newSegmentId, language);
    }

    /** 查询某语言下引用了指定旧段译文的全部新段（反向边）。 */
    public List<TranslationLineageRow> listTranslationLineageByOld(long documentId, String oldSegmentId,
                                                                   String language) {
        return jdbc.query(
                "SELECT new_segment_id, language, old_segment_id, ordinal FROM translation_lineage "
                        + "WHERE document_id = ? AND old_segment_id = ? AND language = ? "
                        + "ORDER BY new_segment_id, ordinal",
                TRANSLATION_LINEAGE_MAPPER, documentId, oldSegmentId, language);
    }

    /** 插入 REFERENCE 候选（按新段+语言唯一）。 */
    public void insertReferenceCandidate(long documentId, ReferenceCandidateRow row) {
        jdbc.update("INSERT INTO reference_candidate (document_id, segment_id, language, content, fragment_boundaries) "
                        + "VALUES (?, ?, ?, ?, ?)",
                documentId, row.segmentId(), row.language(), row.content(), row.fragmentBoundaries());
    }

    /** 查询新段某语言的 REFERENCE 候选。 */
    public Optional<ReferenceCandidateRow> findReferenceCandidate(long documentId, String segmentId,
                                                                  String language) {
        List<ReferenceCandidateRow> rows = jdbc.query(
                "SELECT segment_id, language, content, fragment_boundaries FROM reference_candidate "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ?",
                REFERENCE_CANDIDATE_MAPPER, documentId, segmentId, language);
        return rows.stream().findFirst();
    }

    /** 列出文档全部 REFERENCE 候选，按段与语言排序。 */
    public List<ReferenceCandidateRow> listReferenceCandidates(long documentId) {
        return jdbc.query(
                "SELECT segment_id, language, content, fragment_boundaries FROM reference_candidate "
                        + "WHERE document_id = ? ORDER BY segment_id, language",
                REFERENCE_CANDIDATE_MAPPER, documentId);
    }

    private static final RowMapper<SourceLineageRow> SOURCE_LINEAGE_MAPPER = (rs, n) -> new SourceLineageRow(
            rs.getString("new_segment_id"), rs.getString("old_segment_id"), rs.getInt("ordinal"));

    private static final RowMapper<TranslationLineageRow> TRANSLATION_LINEAGE_MAPPER = (rs, n) ->
            new TranslationLineageRow(rs.getString("new_segment_id"), rs.getString("language"),
                    rs.getString("old_segment_id"), rs.getInt("ordinal"));

    private static final RowMapper<ReferenceCandidateRow> REFERENCE_CANDIDATE_MAPPER = (rs, n) ->
            new ReferenceCandidateRow(rs.getString("segment_id"), rs.getString("language"),
                    rs.getString("content"), rs.getString("fragment_boundaries"));
}
