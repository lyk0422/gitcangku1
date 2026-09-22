package com.example.starter.translation.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 译文与批准的数据访问。
 */
@Repository
public class TranslationRepository {

    private final JdbcTemplate jdbc;

    public TranslationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 译文行快照。
     *
     * @param segmentId          段落 ID
     * @param language           译文语言
     * @param body               译文正文
     * @param author             译文作者
     * @param sourceVersion      所依据的源文版本
     * @param translationVersion 译文版本（从 1 开始递增）
     */
    public record TranslationRow(String segmentId, String language, String body, String author,
                                 int sourceVersion, int translationVersion) {
    }

    /**
     * 批准行快照。
     *
     * @param segmentId          段落 ID
     * @param language           译文语言
     * @param reviewer           审核人
     * @param sourceVersion      批准时的源文版本
     * @param translationVersion 批准时的译文版本
     */
    public record ApprovalRow(String segmentId, String language, String reviewer,
                              int sourceVersion, int translationVersion) {
    }

    public Optional<TranslationRow> find(String documentId, String segmentId, String language) {
        return jdbc.query("SELECT segment_id, language, body, author, source_version, translation_version "
                        + "FROM translation WHERE document_id = ? AND segment_id = ? AND language = ?",
                (rs, i) -> new TranslationRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getInt(6)),
                documentId, segmentId, language).stream().findFirst();
    }

    public List<TranslationRow> findAll(String documentId) {
        return jdbc.query("SELECT segment_id, language, body, author, source_version, translation_version "
                        + "FROM translation WHERE document_id = ? ORDER BY segment_id, language",
                (rs, i) -> new TranslationRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getInt(6)), documentId);
    }

    public void insert(String documentId, String segmentId, String language, String body, String author,
                       int sourceVersion, int translationVersion, Instant now) {
        jdbc.update("INSERT INTO translation (document_id, segment_id, language, body, author, source_version, translation_version, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                documentId, segmentId, language, body, author, sourceVersion, translationVersion, Timestamp.from(now));
    }

    public void update(String documentId, String segmentId, String language, String body, String author,
                       int sourceVersion, int translationVersion, Instant now) {
        jdbc.update("UPDATE translation SET body = ?, author = ?, source_version = ?, translation_version = ?, updated_at = ? "
                        + "WHERE document_id = ? AND segment_id = ? AND language = ?",
                body, author, sourceVersion, translationVersion, Timestamp.from(now), documentId, segmentId, language);
    }

    /**
     * 覆盖式写入批准：同一审核人对同一译文重新批准时替换旧记录。
     */
    public void upsertApproval(String documentId, String segmentId, String language, String reviewer,
                               int sourceVersion, int translationVersion, Instant now) {
        jdbc.update("DELETE FROM translation_approval WHERE document_id = ? AND segment_id = ? AND language = ? AND reviewer = ?",
                documentId, segmentId, language, reviewer);
        jdbc.update("INSERT INTO translation_approval (document_id, segment_id, language, reviewer, source_version, translation_version, approved_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                documentId, segmentId, language, reviewer, sourceVersion, translationVersion, Timestamp.from(now));
    }

    public List<ApprovalRow> findApprovals(String documentId) {
        return jdbc.query("SELECT segment_id, language, reviewer, source_version, translation_version "
                        + "FROM translation_approval WHERE document_id = ?",
                (rs, i) -> new ApprovalRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5)),
                documentId);
    }
}
