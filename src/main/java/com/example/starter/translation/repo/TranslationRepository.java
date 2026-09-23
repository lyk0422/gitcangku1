package com.example.starter.translation.repo;

import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.ReleasePointerRow;
import com.example.starter.translation.domain.Rows.ReleaseTrainRow;
import com.example.starter.translation.domain.Rows.RequestLogRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TrainLocaleRow;
import com.example.starter.translation.domain.Rows.TrainStatus;
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
 */
@Repository
public class TranslationRepository {

    private static final RowMapper<DocumentRow> DOCUMENT_MAPPER = (rs, n) -> new DocumentRow(
            rs.getLong("document_id"),
            Arrays.stream(rs.getString("target_languages").split(",")).toList(),
            rs.getInt("draft_version"),
            rs.getInt("published_version"),
            rs.getInt("term_version"),
            rs.getInt("train_release_version"));

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

    private static final RowMapper<ReleaseTrainRow> TRAIN_MAPPER = (rs, n) -> new ReleaseTrainRow(
            rs.getLong("train_id"),
            rs.getString("train_key"),
            rs.getLong("document_id"),
            rs.getInt("source_document_version"),
            rs.getTimestamp("planned_at").toInstant(),
            TrainStatus.valueOf(rs.getString("status")),
            (Integer) rs.getObject("term_version"),
            rs.getString("source_digest"),
            rs.getString("precheck_json"),
            (Integer) rs.getObject("release_train_version"));

    private static final RowMapper<TrainLocaleRow> TRAIN_LOCALE_MAPPER = (rs, n) -> new TrainLocaleRow(
            rs.getLong("train_id"),
            rs.getString("locale"),
            rs.getInt("candidate_translation_version"),
            rs.getInt("expected_version"));

    private static final RowMapper<ReleasePointerRow> POINTER_MAPPER = (rs, n) -> new ReleasePointerRow(
            rs.getLong("document_id"),
            rs.getString("locale"),
            rs.getInt("released_version"));

    private static final String TRAIN_COLUMNS = "train_id, train_key, document_id, source_document_version, "
            + "planned_at, status, term_version, source_digest, precheck_json, release_train_version";

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
                "SELECT document_id, target_languages, draft_version, published_version, term_version, "
                        + "train_release_version FROM document WHERE document_id = ? FOR UPDATE",
                DOCUMENT_MAPPER, documentId);
        return rows.stream().findFirst();
    }

    /** 只读查询文档（不加锁），用于快照查询。 */
    public Optional<DocumentRow> findDocument(long documentId) {
        List<DocumentRow> rows = jdbc.query(
                "SELECT document_id, target_languages, draft_version, published_version, term_version, "
                        + "train_release_version FROM document WHERE document_id = ?",
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

    /** 推进文档的发布列车版本计数（列车激活时调用，与快照、指针同事务）。 */
    public void updateTrainReleaseVersion(long documentId, int trainReleaseVersion) {
        jdbc.update("UPDATE document SET train_release_version = ? WHERE document_id = ?",
                trainReleaseVersion, documentId);
    }

    /** 插入发布列车（DRAFT 状态）并返回自增列车 ID；trainKey 重复时由唯一约束抛冲突。 */
    public long insertTrain(String trainKey, long documentId, int sourceDocumentVersion, Instant plannedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_train (train_key, document_id, source_document_version, planned_at, "
                            + "status) VALUES (?, ?, ?, ?, 'DRAFT')",
                    new String[]{"train_id"});
            ps.setString(1, trainKey);
            ps.setLong(2, documentId);
            ps.setInt(3, sourceDocumentVersion);
            ps.setTimestamp(4, Timestamp.from(plannedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("发布列车创建后未返回自增主键");
        }
        return key.longValue();
    }

    /** 按文档与 trainKey 查询列车。 */
    public Optional<ReleaseTrainRow> findTrain(long documentId, String trainKey) {
        List<ReleaseTrainRow> rows = jdbc.query(
                "SELECT " + TRAIN_COLUMNS + " FROM release_train WHERE document_id = ? AND train_key = ?",
                TRAIN_MAPPER, documentId, trainKey);
        return rows.stream().findFirst();
    }

    /** 按 trainKey 全局查询列车 ID（用于创建前唯一性预检）。 */
    public boolean trainKeyExists(String trainKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_train WHERE train_key = ?", Integer.class, trainKey);
        return count != null && count > 0;
    }

    /** 查询文档全部列车，按 trainKey 稳定排序。 */
    public List<ReleaseTrainRow> listTrains(long documentId) {
        return jdbc.query(
                "SELECT " + TRAIN_COLUMNS + " FROM release_train WHERE document_id = ? ORDER BY train_key",
                TRAIN_MAPPER, documentId);
    }

    /** 插入列车语言候选。 */
    public void insertTrainLocale(long trainId, TrainLocaleRow row) {
        jdbc.update("INSERT INTO release_train_locale (train_id, locale, candidate_translation_version, "
                        + "expected_version) VALUES (?, ?, ?, ?)",
                trainId, row.locale(), row.candidateTranslationVersion(), row.expectedVersion());
    }

    /** 查询列车全部语言候选，按语言码稳定排序。 */
    public List<TrainLocaleRow> listTrainLocales(long trainId) {
        return jdbc.query(
                "SELECT train_id, locale, candidate_translation_version, expected_version "
                        + "FROM release_train_locale WHERE train_id = ? ORDER BY locale",
                TRAIN_LOCALE_MAPPER, trainId);
    }

    /** 进入 READY：冻结术语版本、源文摘要与预检结果。 */
    public void markTrainReady(long trainId, int termVersion, String sourceDigest, String precheckJson) {
        jdbc.update("UPDATE release_train SET status = 'READY', term_version = ?, source_digest = ?, "
                        + "precheck_json = ? WHERE train_id = ?",
                termVersion, sourceDigest, precheckJson, trainId);
    }

    /** 整列取消（仅 DRAFT/READY 状态允许，由服务层校验）。 */
    public void markTrainCancelled(long trainId) {
        jdbc.update("UPDATE release_train SET status = 'CANCELLED' WHERE train_id = ?", trainId);
    }

    /** 激活完成：记录推进到的发布列车版本与激活时间。 */
    public void markTrainActivated(long trainId, int releaseTrainVersion) {
        jdbc.update("UPDATE release_train SET status = 'ACTIVATED', release_train_version = ?, "
                        + "activated_at = CURRENT_TIMESTAMP WHERE train_id = ?",
                releaseTrainVersion, trainId);
    }

    /** 查询指定语言的当前发布指针；无行表示 0（尚未发布）。 */
    public Optional<ReleasePointerRow> findPointer(long documentId, String locale) {
        List<ReleasePointerRow> rows = jdbc.query(
                "SELECT document_id, locale, released_version FROM release_pointer "
                        + "WHERE document_id = ? AND locale = ?",
                POINTER_MAPPER, documentId, locale);
        return rows.stream().findFirst();
    }

    /** 查询文档全部发布指针，按语言码稳定排序。 */
    public List<ReleasePointerRow> listPointers(long documentId) {
        return jdbc.query(
                "SELECT document_id, locale, released_version FROM release_pointer "
                        + "WHERE document_id = ? ORDER BY locale",
                POINTER_MAPPER, documentId);
    }

    /** 推进指定语言的发布指针（不存在则插入）。 */
    public void upsertPointer(long documentId, String locale, int releasedVersion) {
        int updated = jdbc.update(
                "UPDATE release_pointer SET released_version = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE document_id = ? AND locale = ?",
                releasedVersion, documentId, locale);
        if (updated == 0) {
            jdbc.update("INSERT INTO release_pointer (document_id, locale, released_version) VALUES (?, ?, ?)",
                    documentId, locale, releasedVersion);
        }
    }

    /** 插入某语言的列车发布快照（不可变，主键已存在时抛冲突）。 */
    public void insertTrainSnapshot(long documentId, int releaseTrainVersion, String locale, String snapshotJson) {
        jdbc.update("INSERT INTO train_snapshot (document_id, release_train_version, locale, snapshot_json) "
                + "VALUES (?, ?, ?, ?)", documentId, releaseTrainVersion, locale, snapshotJson);
    }

    /** 查询指定发布列车版本已有快照的语言列表，按语言码稳定排序。 */
    public List<String> listTrainSnapshotLocales(long documentId, int releaseTrainVersion) {
        return jdbc.query(
                "SELECT locale FROM train_snapshot "
                        + "WHERE document_id = ? AND release_train_version = ? ORDER BY locale",
                (rs, n) -> rs.getString(1), documentId, releaseTrainVersion);
    }

    /** 查询指定发布列车版本某语言的快照 JSON。 */
    public Optional<String> findTrainSnapshot(long documentId, int releaseTrainVersion, String locale) {
        List<String> rows = jdbc.query(
                "SELECT snapshot_json FROM train_snapshot "
                        + "WHERE document_id = ? AND release_train_version = ? AND locale = ?",
                (rs, n) -> rs.getString(1), documentId, releaseTrainVersion, locale);
        return rows.stream().findFirst();
    }
}
