package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 观测附件元数据持久化。归属与原始内容摘要业务不可修改；
 * 仅物理清除（purge）会清空 content_summary 并置位 content_purged。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class AttachmentRepository {

    private static final RowMapper<AttachmentRecord> MAPPER = (rs, rowNum) -> new AttachmentRecord(
            rs.getString("attachment_id"),
            rs.getString("observation_id"),
            rs.getString("filename"),
            rs.getString("media_type"),
            rs.getString("content_summary"),
            rs.getString("content_digest"),
            rs.getBoolean("content_purged"),
            toInstant(rs.getTimestamp("purged_at_utc")),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public AttachmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 按附件标识读取（不加锁）。
     */
    public Optional<AttachmentRecord> find(String attachmentId) {
        return jdbcTemplate.query(
                        "SELECT attachment_id, observation_id, filename, media_type, content_summary, "
                                + "content_digest, content_purged, purged_at_utc, created_at_utc "
                                + "FROM observation_attachment WHERE attachment_id = ?",
                        MAPPER, attachmentId)
                .stream().findFirst();
    }

    /**
     * 按附件标识读取并加行锁（SELECT ... FOR UPDATE），用于脱敏/清除事务内串行化。
     */
    public Optional<AttachmentRecord> findForUpdate(String attachmentId) {
        return jdbcTemplate.query(
                        "SELECT attachment_id, observation_id, filename, media_type, content_summary, "
                                + "content_digest, content_purged, purged_at_utc, created_at_utc "
                                + "FROM observation_attachment WHERE attachment_id = ? FOR UPDATE",
                        MAPPER, attachmentId)
                .stream().findFirst();
    }

    /**
     * 读取某观测的全部附件（按附件标识稳定排序），用于诊断与公开列表。
     */
    public List<AttachmentRecord> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT attachment_id, observation_id, filename, media_type, content_summary, "
                        + "content_digest, content_purged, purged_at_utc, created_at_utc "
                        + "FROM observation_attachment WHERE observation_id = ? ORDER BY attachment_id ASC",
                MAPPER, observationId);
    }

    /**
     * 登记新附件元数据。
     */
    public void insert(AttachmentRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_attachment (attachment_id, observation_id, filename, media_type, "
                        + "content_summary, content_digest, content_purged, purged_at_utc, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.attachmentId(), record.observationId(), record.filename(), record.mediaType(),
                record.contentSummary(), record.contentDigest(), record.contentPurged(),
                record.purgedAtUtc() == null ? null : Timestamp.from(record.purgedAtUtc()),
                Timestamp.from(record.createdAtUtc()));
    }

    /**
     * 物理清除附件内容：清空原始摘要并置位清除标记与时刻。内容不可恢复。
     */
    public void purgeContent(String attachmentId, java.time.Instant purgedAtUtc) {
        jdbcTemplate.update(
                "UPDATE observation_attachment SET content_summary = NULL, content_purged = TRUE, "
                        + "purged_at_utc = ? WHERE attachment_id = ?",
                Timestamp.from(purgedAtUtc), attachmentId);
    }
}
