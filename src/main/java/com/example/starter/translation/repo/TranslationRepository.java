package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.DraftMigrationRow;
import com.example.starter.translation.domain.Rows.RequestLogRow;
import com.example.starter.translation.domain.Rows.RetirementImpactRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRetirementRow;
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

    public void insertSnapshot(long documentId, int publishedVersion, int termVersion, String snapshotJson) {
        jdbc.update("INSERT INTO release_snapshot (document_id, published_version, term_version, snapshot_json) "
                        + "VALUES (?, ?, ?, ?)",
                documentId, publishedVersion, termVersion, snapshotJson);
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

    private static final RowMapper<TermRetirementRow> RETIREMENT_MAPPER = (rs, n) -> new TermRetirementRow(
            rs.getLong("retirement_id"), rs.getLong("document_id"), rs.getString("retirement_key"),
            rs.getInt("term_version"), rs.getString("language"), rs.getInt("replacement_version"),
            rs.getLong("effective_from_utc"), rs.getLong("effective_to_utc"),
            rs.getString("status"),
            rs.getObject("activated_at_utc") == null ? null : rs.getLong("activated_at_utc"),
            rs.getString("preview_json"), rs.getString("impact_snapshot_json"));

    private static final RowMapper<RetirementImpactRow> IMPACT_MAPPER = (rs, n) -> new RetirementImpactRow(
            rs.getLong("document_id"), rs.getLong("retirement_id"),
            rs.getObject("published_version") == null ? null : rs.getInt("published_version"),
            rs.getString("segment_id"), rs.getString("language"), rs.getString("kind"),
            rs.getString("hit_terms_json"));

    /** 插入退役单（初始状态 DRAFT），返回自增退役单 ID。 */
    public long insertRetirement(long documentId, String retirementKey, int termVersion, String language,
                                 int replacementVersion, long effectiveFromUtc, long effectiveToUtc,
                                 String previewJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO term_retirement (document_id, retirement_key, term_version, language, "
                            + "replacement_version, effective_from_utc, effective_to_utc, status, preview_json) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, '"
                            + TermRetirementRow.STATUS_DRAFT + "', ?)",
                    new String[]{"retirement_id"});
            ps.setLong(1, documentId);
            ps.setString(2, retirementKey);
            ps.setInt(3, termVersion);
            ps.setString(4, language);
            ps.setInt(5, replacementVersion);
            ps.setLong(6, effectiveFromUtc);
            ps.setLong(7, effectiveToUtc);
            ps.setString(8, previewJson);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("退役单创建后未返回自增主键");
        }
        return key.longValue();
    }

    /** 按业务键查询退役单。 */
    public Optional<TermRetirementRow> findRetirementByKey(long documentId, String retirementKey) {
        List<TermRetirementRow> rows = jdbc.query(
                "SELECT retirement_id, document_id, retirement_key, term_version, language, replacement_version, "
                        + "effective_from_utc, effective_to_utc, status, activated_at_utc, preview_json, "
                        + "impact_snapshot_json FROM term_retirement "
                        + "WHERE document_id = ? AND retirement_key = ?",
                RETIREMENT_MAPPER, documentId, retirementKey);
        return rows.stream().findFirst();
    }

    /** 查询指定术语版本在指定语言下的全部退役单（用于窗口重叠与环检测）。 */
    public List<TermRetirementRow> listRetirements(long documentId, int termVersion, String language) {
        return jdbc.query(
                "SELECT retirement_id, document_id, retirement_key, term_version, language, replacement_version, "
                        + "effective_from_utc, effective_to_utc, status, activated_at_utc, preview_json, "
                        + "impact_snapshot_json FROM term_retirement "
                        + "WHERE document_id = ? AND term_version = ? AND language = ? "
                        + "ORDER BY effective_from_utc, retirement_id",
                RETIREMENT_MAPPER, documentId, termVersion, language);
    }

    /** 查询文档内全部退役单（用于替代环检测），按退役单 ID 稳定排序。 */
    public List<TermRetirementRow> listAllRetirements(long documentId) {
        return jdbc.query(
                "SELECT retirement_id, document_id, retirement_key, term_version, language, replacement_version, "
                        + "effective_from_utc, effective_to_utc, status, activated_at_utc, preview_json, "
                        + "impact_snapshot_json FROM term_retirement "
                        + "WHERE document_id = ? ORDER BY retirement_id",
                RETIREMENT_MAPPER, documentId);
    }

    /** 激活退役单：置状态 ACTIVE、记录激活时刻并写入冻结的影响快照。 */
    public void activateRetirement(long retirementId, long activatedAtUtc, String impactSnapshotJson) {
        jdbc.update("UPDATE term_retirement SET status = '" + TermRetirementRow.STATUS_ACTIVE
                        + "', activated_at_utc = ?, impact_snapshot_json = ? WHERE retirement_id = ?",
                activatedAtUtc, impactSnapshotJson, retirementId);
    }

    /** 查询术语版本状态（ACTIVE/RETIRED）；版本不存在返回空。 */
    public Optional<String> findTermVersionStatus(long documentId, int termVersion) {
        List<String> rows = jdbc.query(
                "SELECT status FROM term_version WHERE document_id = ? AND term_version = ?",
                (rs, n) -> rs.getString(1), documentId, termVersion);
        return rows.stream().findFirst();
    }

    /** 更新术语版本状态（退役激活后置为 RETIRED，不自动恢复）。 */
    public void updateTermVersionStatus(long documentId, int termVersion, String status) {
        jdbc.update("UPDATE term_version SET status = ? WHERE document_id = ? AND term_version = ?",
                status, documentId, termVersion);
    }

    /**
     * 判断指定术语版本在指定语言、指定 UTC 毫秒时刻是否已退役生效：
     * 存在已激活退役单且窗口起（含）不晚于该时刻；窗口结束不恢复。
     */
    public boolean isTermVersionRetired(long documentId, int termVersion, String language, long nowUtcMillis) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_retirement WHERE document_id = ? AND term_version = ? AND language = ? "
                        + "AND status = '" + TermRetirementRow.STATUS_ACTIVE + "' AND effective_from_utc <= ?",
                Integer.class, documentId, termVersion, language, nowUtcMillis);
        return count != null && count > 0;
    }

    /** 写入一条退役影响清单条目（预览或激活冻结）。 */
    public void insertImpact(RetirementImpactRow row) {
        jdbc.update("INSERT INTO retirement_impact (document_id, retirement_id, published_version, segment_id, "
                        + "language, kind, hit_terms_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.documentId(), row.retirementId(), row.publishedVersion(), row.segmentId(),
                row.language(), row.kind(), row.hitTermsJson());
    }

    /** 查询退役单的影响清单，按类型、发布版本、段落、语言稳定排序。 */
    public List<RetirementImpactRow> listImpacts(long documentId, long retirementId) {
        return jdbc.query(
                "SELECT document_id, retirement_id, published_version, segment_id, language, kind, hit_terms_json "
                        + "FROM retirement_impact WHERE document_id = ? AND retirement_id = ? "
                        + "ORDER BY kind, published_version, segment_id, language",
                IMPACT_MAPPER, documentId, retirementId);
    }

    /** 删除退役单的影响清单（激活前清除预览条目，重写冻结条目）。 */
    public void deleteImpacts(long documentId, long retirementId) {
        jdbc.update("DELETE FROM retirement_impact WHERE document_id = ? AND retirement_id = ?",
                documentId, retirementId);
    }

    /** 查询绑定指定术语版本的发布版本号列表（历史受影响快照），按发布版本升序。 */
    public List<Integer> listPublishedVersionsByTermVersion(long documentId, int termVersion) {
        return jdbc.query(
                "SELECT published_version FROM release_snapshot WHERE document_id = ? AND term_version = ? "
                        + "ORDER BY published_version",
                (rs, n) -> rs.getInt(1), documentId, termVersion);
    }

    /** 删除指定译文的批准（退役激活撤批）。 */
    public void deleteApproval(long documentId, String segmentId, String language) {
        jdbc.update("DELETE FROM approval WHERE document_id = ? AND segment_id = ? AND language = ?",
                documentId, segmentId, language);
    }

    /** 写入一条草稿迁移记录。 */
    public void insertMigration(DraftMigrationRow row) {
        jdbc.update("INSERT INTO draft_migration (document_id, retirement_id, expected_version, rule_version, "
                        + "old_summary, old_text_hash, new_summary, new_text_hash, segment_id, language, "
                        + "created_at_utc, request_log_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.documentId(), row.retirementId(), row.expectedVersion(), row.ruleVersion(),
                row.oldSummary(), row.oldTextHash(), row.newSummary(), row.newTextHash(),
                row.segmentId(), row.language(), row.createdAtUtc(), row.requestLogId());
    }

    /** 查询退役单的全部草稿迁移记录，按段落、语言稳定排序。 */
    public List<DraftMigrationRow> listMigrations(long documentId, long retirementId) {
        return jdbc.query(
                "SELECT document_id, retirement_id, expected_version, rule_version, old_summary, old_text_hash, "
                        + "new_summary, new_text_hash, segment_id, language, created_at_utc, request_log_id "
                        + "FROM draft_migration WHERE document_id = ? AND retirement_id = ? "
                        + "ORDER BY segment_id, language",
                (rs, n) -> new DraftMigrationRow(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getInt(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getLong(11), rs.getString(12)),
                documentId, retirementId);
    }
}
