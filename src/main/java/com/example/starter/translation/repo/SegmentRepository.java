package com.example.starter.translation.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 段落（源文）的数据访问。
 */
@Repository
public class SegmentRepository {

    private final JdbcTemplate jdbc;

    public SegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 段落行快照。
     *
     * @param segmentId     文档内唯一段落 ID
     * @param sourceText    源文正文
     * @param sourceVersion 源文版本（从 1 开始）
     */
    public record SegmentRow(String segmentId, String sourceText, int sourceVersion) {
    }

    public void insert(String documentId, String segmentId, String sourceText) {
        jdbc.update("INSERT INTO segment (document_id, segment_id, source_text, source_version) VALUES (?, ?, ?, 1)",
                documentId, segmentId, sourceText);
    }

    public Optional<SegmentRow> find(String documentId, String segmentId) {
        return jdbc.query("SELECT segment_id, source_text, source_version FROM segment WHERE document_id = ? AND segment_id = ?",
                (rs, i) -> new SegmentRow(rs.getString(1), rs.getString(2), rs.getInt(3)),
                documentId, segmentId).stream().findFirst();
    }

    public List<SegmentRow> findAll(String documentId) {
        return jdbc.query("SELECT segment_id, source_text, source_version FROM segment WHERE document_id = ? ORDER BY segment_id",
                (rs, i) -> new SegmentRow(rs.getString(1), rs.getString(2), rs.getInt(3)), documentId);
    }

    public void updateSource(String documentId, String segmentId, String sourceText, int sourceVersion) {
        jdbc.update("UPDATE segment SET source_text = ?, source_version = ? WHERE document_id = ? AND segment_id = ?",
                sourceText, sourceVersion, documentId, segmentId);
    }
}
