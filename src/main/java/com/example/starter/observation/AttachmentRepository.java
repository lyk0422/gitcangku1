package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 观测附件元数据持久化：附件登记后不可修改，仅提供插入与查询。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class AttachmentRepository {

    private static final RowMapper<Attachment> ATTACHMENT_MAPPER = (rs, rowNum) -> new Attachment(
            rs.getString("attachment_id"),
            rs.getString("observation_id"),
            rs.getString("content_summary"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public AttachmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 登记附件元数据；attachment_id 主键冲突时抛出重复键异常。
     */
    public void insert(Attachment attachment) {
        jdbcTemplate.update(
                "INSERT INTO observation_attachment (attachment_id, observation_id, content_summary, created_at_utc) "
                        + "VALUES (?, ?, ?, ?)",
                attachment.attachmentId(), attachment.observationId(), attachment.contentSummary(),
                Timestamp.from(attachment.createdAtUtc()));
    }

    /**
     * 按附件标识查询；不存在时返回空。
     */
    public Optional<Attachment> findById(String attachmentId) {
        return jdbcTemplate.query(
                        "SELECT attachment_id, observation_id, content_summary, created_at_utc "
                                + "FROM observation_attachment WHERE attachment_id = ?",
                        ATTACHMENT_MAPPER, attachmentId)
                .stream().findFirst();
    }

    /**
     * 按观测记录查询全部附件，按附件标识排序，保证列表顺序稳定。
     */
    public List<Attachment> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT attachment_id, observation_id, content_summary, created_at_utc "
                        + "FROM observation_attachment WHERE observation_id = ? ORDER BY attachment_id ASC",
                ATTACHMENT_MAPPER, observationId);
    }
}
