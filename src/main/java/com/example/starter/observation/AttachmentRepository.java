package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 观测附件元数据持久化：附件登记后不可变，唯一例外是物理清除把原始摘要置空。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class AttachmentRepository {

    private static final RowMapper<AttachmentMeta> META_MAPPER = (rs, rowNum) -> new AttachmentMeta(
            rs.getString("attachment_id"),
            rs.getString("observation_id"),
            rs.getString("file_name"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getString("original_summary"),
            rs.getBoolean("content_cleared"),
            rs.getTimestamp("cleared_at_utc") == null ? null : rs.getTimestamp("cleared_at_utc").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public AttachmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 登记附件元数据；attachmentId 已存在时抛出重复键异常。
     */
    public void insert(AttachmentMeta attachment) {
        jdbcTemplate.update(
                "INSERT INTO observation_attachment (attachment_id, observation_id, file_name, content_type, "
                        + "size_bytes, original_summary, content_cleared, cleared_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, FALSE, NULL, CURRENT_TIMESTAMP)",
                attachment.attachmentId(), attachment.observationId(), attachment.fileName(),
                attachment.contentType(), attachment.sizeBytes(), attachment.originalSummary());
    }

    /**
     * 按附件标识查询元数据；不存在时返回空。
     */
    public Optional<AttachmentMeta> find(String attachmentId) {
        return jdbcTemplate.query(
                        "SELECT attachment_id, observation_id, file_name, content_type, size_bytes, "
                                + "original_summary, content_cleared, cleared_at_utc, created_at "
                                + "FROM observation_attachment WHERE attachment_id = ?",
                        META_MAPPER, attachmentId)
                .stream().findFirst();
    }

    /**
     * 查询某条观测记录下的全部附件，按附件标识排序保证结果顺序稳定。
     */
    public List<AttachmentMeta> findByObservation(String observationId) {
        return jdbcTemplate.query(
                "SELECT attachment_id, observation_id, file_name, content_type, size_bytes, "
                        + "original_summary, content_cleared, cleared_at_utc, created_at "
                        + "FROM observation_attachment WHERE observation_id = ? ORDER BY attachment_id ASC",
                META_MAPPER, observationId);
    }

    /**
     * 物理清除原始内容：仅当尚未清除时更新成功，返回是否实际更新。
     */
    public boolean clearContent(String attachmentId, Instant clearedAtUtc) {
        int updated = jdbcTemplate.update(
                "UPDATE observation_attachment SET original_summary = NULL, content_cleared = TRUE, "
                        + "cleared_at_utc = ? WHERE attachment_id = ? AND content_cleared = FALSE",
                Timestamp.from(clearedAtUtc), attachmentId);
        return updated > 0;
    }
}
