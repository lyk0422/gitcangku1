package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 观测版本固化的附件证据引用持久化。观测版本生成时写入，历史永不更新或删除。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class VersionAttachmentRepository {

    private static final RowMapper<VersionAttachmentRef> MAPPER = (rs, rowNum) -> new VersionAttachmentRef(
            rs.getString("observation_id"),
            rs.getInt("version"),
            rs.getString("attachment_id"),
            (Integer) rs.getObject("redaction_version"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public VersionAttachmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 固化一条附件证据引用。
     */
    public void insert(VersionAttachmentRef ref) {
        jdbcTemplate.update(
                "INSERT INTO observation_version_attachment (observation_id, version, attachment_id, "
                        + "redaction_version, created_at_utc) VALUES (?, ?, ?, ?, ?)",
                ref.observationId(), ref.version(), ref.attachmentId(), ref.redactionVersion(),
                Timestamp.from(ref.createdAtUtc()));
    }

    /**
     * 读取某观测版本固化的全部附件引用（按附件标识稳定排序）。
     */
    public List<VersionAttachmentRef> findByVersion(String observationId, int version) {
        return jdbcTemplate.query(
                "SELECT observation_id, version, attachment_id, redaction_version, created_at_utc "
                        + "FROM observation_version_attachment WHERE observation_id = ? AND version = ? "
                        + "ORDER BY attachment_id ASC",
                MAPPER, observationId, version);
    }
}
