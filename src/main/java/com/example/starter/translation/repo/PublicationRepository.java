package com.example.starter.translation.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 发布快照的数据访问；快照只写一次、只读查询，不提供更新。
 */
@Repository
public class PublicationRepository {

    private final JdbcTemplate jdbc;

    public PublicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 快照段落。
     *
     * @param segmentId     段落 ID
     * @param sourceText    发布时刻源文正文
     * @param sourceVersion 发布时刻源文版本
     */
    public record SnapshotSegmentRow(String segmentId, String sourceText, int sourceVersion) {
    }

    /**
     * 快照译文。
     *
     * @param segmentId          段落 ID
     * @param language           译文语言
     * @param body               发布时刻译文正文
     * @param author             译文作者
     * @param sourceVersion      译文所依据的源文版本
     * @param translationVersion 发布时刻译文版本
     */
    public record SnapshotTranslationRow(String segmentId, String language, String body, String author,
                                         int sourceVersion, int translationVersion) {
    }

    public void insertPublication(String documentId, int publishedVersion, Instant now) {
        jdbc.update("INSERT INTO publication (document_id, published_version, created_at) VALUES (?, ?, ?)",
                documentId, publishedVersion, Timestamp.from(now));
    }

    public void insertSegment(String documentId, int publishedVersion, String segmentId,
                              String sourceText, int sourceVersion) {
        jdbc.update("INSERT INTO publication_segment (document_id, published_version, segment_id, source_text, source_version) "
                        + "VALUES (?, ?, ?, ?, ?)",
                documentId, publishedVersion, segmentId, sourceText, sourceVersion);
    }

    public void insertTranslation(String documentId, int publishedVersion, String segmentId, String language,
                                  String body, String author, int sourceVersion, int translationVersion) {
        jdbc.update("INSERT INTO publication_translation (document_id, published_version, segment_id, language, body, author, source_version, translation_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                documentId, publishedVersion, segmentId, language, body, author, sourceVersion, translationVersion);
    }

    public Optional<Instant> findPublication(String documentId, int publishedVersion) {
        return jdbc.query("SELECT created_at FROM publication WHERE document_id = ? AND published_version = ?",
                (rs, i) -> rs.getTimestamp(1).toInstant(), documentId, publishedVersion).stream().findFirst();
    }

    public List<SnapshotSegmentRow> findSegments(String documentId, int publishedVersion) {
        return jdbc.query("SELECT segment_id, source_text, source_version FROM publication_segment "
                        + "WHERE document_id = ? AND published_version = ? ORDER BY segment_id",
                (rs, i) -> new SnapshotSegmentRow(rs.getString(1), rs.getString(2), rs.getInt(3)),
                documentId, publishedVersion);
    }

    public List<SnapshotTranslationRow> findTranslations(String documentId, int publishedVersion) {
        return jdbc.query("SELECT segment_id, language, body, author, source_version, translation_version FROM publication_translation "
                        + "WHERE document_id = ? AND published_version = ? ORDER BY segment_id, language",
                (rs, i) -> new SnapshotTranslationRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getInt(6)),
                documentId, publishedVersion);
    }
}
