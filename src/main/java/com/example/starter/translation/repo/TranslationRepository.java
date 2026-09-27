package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.OperationLogRow;
import com.example.starter.translation.domain.Rows.RequestLogRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 目标语言在 document 表中以逗号分隔存储，行记录中还原为列表；
 * 占位符集合在 segment 表中以规范排序的 JSON 数组字符串存储。
 */
@Repository
public class TranslationRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private static final RowMapper<DocumentRow> DOCUMENT_MAPPER = (rs, n) -> new DocumentRow(
            rs.getLong("document_id"),
            Arrays.stream(rs.getString("target_languages").split(",")).toList(),
            rs.getInt("draft_version"),
            rs.getInt("published_version"),
            rs.getInt("term_version"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TranslationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 占位符集合序列化为规范 JSON 数组字符串（调用方已去重排序）。 */
    private String writePlaceholders(List<String> placeholders) {
        try {
            return objectMapper.writeValueAsString(placeholders);
        } catch (Exception e) {
            throw new IllegalStateException("占位符集合序列化失败", e);
        }
    }

    /** 占位符 JSON 数组字符串还原为列表。 */
    private List<String> readPlaceholders(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("占位符集合反序列化失败: " + json, e);
        }
    }

    private RowMapper<SegmentRow> segmentMapper() {
        return (rs, n) -> new SegmentRow(rs.getString("segment_id"), rs.getString("source_text"),
                rs.getInt("source_version"), readPlaceholders(rs.getString("placeholders")));
    }

    private RowMapper<TranslationRow> translationMapper() {
        return (rs, n) -> new TranslationRow(
                rs.getString("segment_id"), rs.getString("language"), rs.getString("content"),
                rs.getString("author"), rs.getInt("source_version"), rs.getInt("translation_version"),
                rs.getInt("term_version"), rs.getString("status"));
    }

    private static final RowMapper<ApprovalRow> APPROVAL_MAPPER = (rs, n) -> new ApprovalRow(
            rs.getString("segment_id"), rs.getString("language"), rs.getString("reviewer"),
            rs.getInt("source_version"), rs.getInt("translation_version"));

    private static final RowMapper<TermRuleRow> TERM_RULE_MAPPER = (rs, n) -> new TermRuleRow(
            rs.getString("source_term"), rs.getString("language"), rs.getString("required_translation"));

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

    public void insertSegment(long documentId, String segmentId, String sourceText, List<String> placeholders) {
        jdbc.update("INSERT INTO segment (document_id, segment_id, source_text, source_version, placeholders) "
                        + "VALUES (?, ?, ?, 1, ?)",
                documentId, segmentId, sourceText, writePlaceholders(placeholders));
    }

    public Optional<SegmentRow> findSegment(long documentId, String segmentId) {
        List<SegmentRow> rows = jdbc.query(
                "SELECT segment_id, source_text, source_version, placeholders FROM segment "
                        + "WHERE document_id = ? AND segment_id = ?",
                segmentMapper(), documentId, segmentId);
        return rows.stream().findFirst();
    }

    public List<SegmentRow> listSegments(long documentId) {
        return jdbc.query(
                "SELECT segment_id, source_text, source_version, placeholders FROM segment "
                        + "WHERE document_id = ? ORDER BY segment_id",
                segmentMapper(), documentId);
    }

    public void updateSegmentSource(long documentId, String segmentId, String sourceText,
                                    List<String> placeholders, int sourceVersion) {
        jdbc.update("UPDATE segment SET source_text = ?, placeholders = ?, source_version = ? "
                        + "WHERE document_id = ? AND segment_id = ?",
                sourceText, writePlaceholders(placeholders), sourceVersion, documentId, segmentId);
    }

    public Optional<TranslationRow> findTranslation(long documentId, String segmentId, String language) {
        List<TranslationRow> rows = jdbc.query(
                "SELECT segment_id, language, content, author, source_version, translation_version, "
                        + "term_version, status FROM translation WHERE document_id = ? AND segment_id = ? AND language = ?",
                translationMapper(), documentId, segmentId, language);
        return rows.stream().findFirst();
    }

    public List<TranslationRow> listTranslations(long documentId) {
        return jdbc.query(
                "SELECT segment_id, language, content, author, source_version, translation_version, "
                        + "term_version, status FROM translation WHERE document_id = ? ORDER BY segment_id, language",
                translationMapper(), documentId);
    }

    /** 插入或覆盖译文（按主键段落+语言唯一），覆盖时状态恢复为 ACTIVE。 */
    public void upsertTranslation(long documentId, TranslationRow row) {
        int updated = jdbc.update(
                "UPDATE translation SET content = ?, author = ?, source_version = ?, translation_version = ?, "
                        + "term_version = ?, status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ?",
                row.content(), row.author(), row.sourceVersion(), row.translationVersion(), row.termVersion(),
                row.status(), documentId, row.segmentId(), row.language());
        if (updated == 0) {
            jdbc.update("INSERT INTO translation (document_id, segment_id, language, content, author, "
                            + "source_version, translation_version, term_version, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    documentId, row.segmentId(), row.language(), row.content(), row.author(),
                    row.sourceVersion(), row.translationVersion(), row.termVersion(), row.status());
        }
    }

    /** 源段修订时把该段全部目标译文置为待修订，旧译文不再允许发布。 */
    public int markTranslationsNeedsRevision(long documentId, String segmentId) {
        return jdbc.update(
                "UPDATE translation SET status = ? WHERE document_id = ? AND segment_id = ? AND status <> ?",
                TranslationRow.NEEDS_REVISION, documentId, segmentId, TranslationRow.NEEDS_REVISION);
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

    /** 删除批准（撤回），返回受影响行数；0 表示原本无批准。 */
    public int deleteApproval(long documentId, String segmentId, String language) {
        return jdbc.update("DELETE FROM approval WHERE document_id = ? AND segment_id = ? AND language = ?",
                documentId, segmentId, language);
    }

    /** 追加一条操作历史证据；只允许 INSERT，任何代码路径都不得 UPDATE/DELETE。 */
    public long insertOperationLog(OperationLogRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO operation_log (document_id, operation_type, actor, segment_id, language, "
                            + "request_id, occurred_at, fingerprint, detail_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"seq"});
            ps.setLong(1, row.documentId());
            ps.setString(2, row.operationType());
            ps.setString(3, row.actor());
            ps.setString(4, row.segmentId());
            ps.setString(5, row.language());
            ps.setString(6, row.requestId());
            ps.setLong(7, row.occurredAt());
            ps.setString(8, row.fingerprint());
            ps.setString(9, row.detailJson());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("操作历史写入后未返回自增序号");
        }
        return key.longValue();
    }

    /**
     * 按 UTC 毫秒区间查询操作历史，区间左闭右开（occurred_at &gt;= from 且 occurred_at &lt; to）；
     * 同一文档内按发生时间、序号稳定升序。
     */
    public List<OperationLogRow> listOperationLogs(long documentId, long fromInclusive, long toExclusive) {
        return jdbc.query(
                "SELECT seq, document_id, operation_type, actor, segment_id, language, request_id, "
                        + "occurred_at, fingerprint, detail_json FROM operation_log "
                        + "WHERE document_id = ? AND occurred_at >= ? AND occurred_at < ? "
                        + "ORDER BY occurred_at, seq",
                (rs, n) -> new OperationLogRow(rs.getLong("seq"), rs.getLong("document_id"),
                        rs.getString("operation_type"), rs.getString("actor"), rs.getString("segment_id"),
                        rs.getString("language"), rs.getString("request_id"), rs.getLong("occurred_at"),
                        rs.getString("fingerprint"), rs.getString("detail_json")),
                documentId, fromInclusive, toExclusive);
    }

    /** 查询文档全部操作历史，按发生时间、序号稳定升序。 */
    public List<OperationLogRow> listOperationLogs(long documentId) {
        return jdbc.query(
                "SELECT seq, document_id, operation_type, actor, segment_id, language, request_id, "
                        + "occurred_at, fingerprint, detail_json FROM operation_log "
                        + "WHERE document_id = ? ORDER BY occurred_at, seq",
                (rs, n) -> new OperationLogRow(rs.getLong("seq"), rs.getLong("document_id"),
                        rs.getString("operation_type"), rs.getString("actor"), rs.getString("segment_id"),
                        rs.getString("language"), rs.getString("request_id"), rs.getLong("occurred_at"),
                        rs.getString("fingerprint"), rs.getString("detail_json")),
                documentId);
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
