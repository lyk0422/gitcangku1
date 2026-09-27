package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 观测附件元数据持久化：只保存内容摘要。所有 SQL 使用参数化查询。
 */
@Repository
public class AttachmentRepository {

    private static final RowMapper<AttachmentRecord> MAPPER = (rs, rowNum) -> new AttachmentRecord(
            rs.getString("attachment_id"),
            rs.getString("observation_id"),
            rs.getString("content_summary"),
            rs.getString("media_type"),
            rs.getBoolean("purged"),
            rs.getTimestamp("created_at_utc").toInstant(),
            Optional.ofNullable(rs.getTimestamp("purged_at_utc")).map(Timestamp::toInstant).orElse(null));

    private final JdbcTemplate jdbcTemplate;

    public AttachmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入附件元数据。
     */
    public void insert(AttachmentRecord record) {
        jdbcTemplate.update(
                "INSERT INTO attachment (attachment_id, observation_id, content_summary, media_type, "
                        + "purged, created_at_utc, purged_at_utc) VALUES (?, ?, ?, ?, ?, ?, NULL)",
                record.attachmentId(), record.observationId(), record.contentSummary(), record.mediaType(),
                record.purged(), Timestamp.from(record.createdAtUtc()));
    }

    /**
     * 按附件标识查询（跨观测），用于全局路径。
     */
    public Optional<AttachmentRecord> findById(String attachmentId) {
        return jdbcTemplate.query(
                        "SELECT attachment_id, observation_id, content_summary, media_type, purged, "
                                + "created_at_utc, purged_at_utc FROM attachment WHERE attachment_id = ?",
                        MAPPER, attachmentId)
                .stream().findFirst();
    }

    /**
     * 按（观测, 附件）查询并对行加锁，用于批量脱敏事务内串行化同一附件的并发提交。
     */
    public Optional<AttachmentRecord> findForUpdate(String observationId, String attachmentId) {
        return jdbcTemplate.query(
                        "SELECT attachment_id, observation_id, content_summary, media_type, purged, "
                                + "created_at_utc, purged_at_utc FROM attachment "
                                + "WHERE observation_id = ? AND attachment_id = ? FOR UPDATE",
                        MAPPER, observationId, attachmentId)
                .stream().findFirst();
    }

    /**
     * 列出观测下全部附件，按附件标识排序，保证批量响应顺序稳定。
     */
    public List<AttachmentRecord> findByObservation(String observationId) {
        return jdbcTemplate.query(
                "SELECT attachment_id, observation_id, content_summary, media_type, purged, "
                        + "created_at_utc, purged_at_utc FROM attachment WHERE observation_id = ? "
                        + "ORDER BY attachment_id ASC",
                MAPPER, observationId);
    }

    /**
     * 物理清除：清空原始摘要并置清除标记与清除时刻；元数据行保留。
     */
    public void markPurged(String attachmentId, Instant purgedAtUtc) {
        jdbcTemplate.update(
                "UPDATE attachment SET content_summary = NULL, purged = TRUE, purged_at_utc = ? "
                        + "WHERE attachment_id = ?",
                Timestamp.from(purgedAtUtc), attachmentId);
    }
}
