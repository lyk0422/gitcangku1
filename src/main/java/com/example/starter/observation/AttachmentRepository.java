package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 观测附件持久化：attachment 表登记附件元数据与原始内容摘要；原始摘要只增不改，仅物理清除可置空。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class AttachmentRepository {

    private static final RowMapper<AttachmentRecord> ATTACHMENT_MAPPER = (rs, rowNum) -> new AttachmentRecord(
            rs.getString("attachment_id"),
            rs.getString("observation_id"),
            rs.getString("content_summary"),
            rs.getBoolean("content_purged"),
            rs.getTimestamp("created_at_utc").toInstant(),
            rs.getTimestamp("purged_at_utc") == null ? null : rs.getTimestamp("purged_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public AttachmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 登记附件元数据；attachment_id 主键冲突时抛出重复键异常。
     */
    public void insert(AttachmentRecord record) {
        jdbcTemplate.update(
                "INSERT INTO attachment (attachment_id, observation_id, content_summary, content_purged, "
                        + "created_at_utc, purged_at_utc, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.attachmentId(), record.observationId(), record.contentSummary(), record.contentPurged(),
                Timestamp.from(record.createdAtUtc()),
                record.purgedAtUtc() == null ? null : Timestamp.from(record.purgedAtUtc()));
    }

    /**
     * 按附件标识查询；不存在时返回空。
     */
    public Optional<AttachmentRecord> findById(String attachmentId) {
        return jdbcTemplate.query(
                        "SELECT attachment_id, observation_id, content_summary, content_purged, "
                                + "created_at_utc, purged_at_utc FROM attachment WHERE attachment_id = ?",
                        ATTACHMENT_MAPPER, attachmentId)
                .stream().findFirst();
    }

    /**
     * 按附件标识查询并加行锁（SELECT ... FOR UPDATE），用于写事务内串行化同一附件的并发脱敏。
     */
    public Optional<AttachmentRecord> findByIdForUpdate(String attachmentId) {
        return jdbcTemplate.query(
                        "SELECT attachment_id, observation_id, content_summary, content_purged, "
                                + "created_at_utc, purged_at_utc FROM attachment WHERE attachment_id = ? FOR UPDATE",
                        ATTACHMENT_MAPPER, attachmentId)
                .stream().findFirst();
    }

    /**
     * 按观测记录查询全部附件，按附件标识排序，保证公开列表顺序稳定。
     */
    public List<AttachmentRecord> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT attachment_id, observation_id, content_summary, content_purged, "
                        + "created_at_utc, purged_at_utc FROM attachment WHERE observation_id = ? "
                        + "ORDER BY attachment_id ASC",
                ATTACHMENT_MAPPER, observationId);
    }

    /**
     * 物理清除附件原始内容：摘要置空、标记已清除并记录清除时刻；已清除的附件重复调用为无操作。
     */
    public void purgeContent(String attachmentId, java.time.Instant purgedAtUtc) {
        jdbcTemplate.update(
                "UPDATE attachment SET content_summary = NULL, content_purged = TRUE, purged_at_utc = ? "
                        + "WHERE attachment_id = ?",
                Timestamp.from(purgedAtUtc), attachmentId);
    }
}
