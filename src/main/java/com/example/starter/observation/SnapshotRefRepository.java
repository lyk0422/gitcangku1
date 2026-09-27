package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 快照附件证据引用持久化：snapshot_attachment_ref 只追加、永不更新或删除。
 * 后续脱敏、撤销与物理清除均不回写已固化的摘要原文。所有 SQL 使用参数化查询。
 */
@Repository
public class SnapshotRefRepository {

    private static final RowMapper<SnapshotAttachmentRef> REF_MAPPER = (rs, rowNum) -> new SnapshotAttachmentRef(
            rs.getLong("id"),
            rs.getString("source_type"),
            rs.getString("source_request_id"),
            rs.getString("resolution_id"),
            rs.getString("observation_id"),
            rs.getInt("observation_version"),
            rs.getString("attachment_id"),
            "ORIGINAL".equals(rs.getString("ref_kind")),
            rs.getString("redaction_id"),
            (Integer) rs.getObject("redaction_version"),
            rs.getString("summary_text"),
            rs.getBoolean("content_cleared_at_fix"),
            rs.getTimestamp("fixed_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public SnapshotRefRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一条不可变证据引用。
     */
    public void insert(SnapshotAttachmentRef ref) {
        jdbcTemplate.update(
                "INSERT INTO snapshot_attachment_ref (source_type, source_request_id, resolution_id, "
                        + "observation_id, observation_version, attachment_id, ref_kind, redaction_id, "
                        + "redaction_version, summary_text, content_cleared_at_fix, fixed_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ref.sourceType(), ref.sourceRequestId(), ref.resolutionId(), ref.observationId(),
                ref.observationVersion(), ref.attachmentId(), ref.original() ? "ORIGINAL" : "REDACTED",
                ref.redactionId(), ref.redactionVersion(), ref.summaryText(),
                ref.contentClearedAtFix(), Timestamp.from(ref.fixedAtUtc()));
    }

    /**
     * 按观测记录查询全部证据引用，按固化时刻与自增主键升序，保证历史排序稳定。
     */
    public List<SnapshotAttachmentRef> findByObservation(String observationId) {
        return jdbcTemplate.query(
                "SELECT id, source_type, source_request_id, resolution_id, observation_id, observation_version, "
                        + "attachment_id, ref_kind, redaction_id, redaction_version, summary_text, "
                        + "content_cleared_at_fix, fixed_at_utc FROM snapshot_attachment_ref "
                        + "WHERE observation_id = ? ORDER BY fixed_at_utc ASC, id ASC",
                REF_MAPPER, observationId);
    }

    /**
     * 按观测记录与来源请求标识查询证据引用。
     */
    public List<SnapshotAttachmentRef> findByObservationAndRequest(String observationId, String requestId) {
        return jdbcTemplate.query(
                "SELECT id, source_type, source_request_id, resolution_id, observation_id, observation_version, "
                        + "attachment_id, ref_kind, redaction_id, redaction_version, summary_text, "
                        + "content_cleared_at_fix, fixed_at_utc FROM snapshot_attachment_ref "
                        + "WHERE observation_id = ? AND source_request_id = ? ORDER BY fixed_at_utc ASC, id ASC",
                REF_MAPPER, observationId, requestId);
    }

    /**
     * 按请求标识查询证据引用（跨观测诊断用）。
     */
    public Optional<SnapshotAttachmentRef> findAnyByRequest(String requestId) {
        return jdbcTemplate.query(
                        "SELECT id, source_type, source_request_id, resolution_id, observation_id, "
                                + "observation_version, attachment_id, ref_kind, redaction_id, redaction_version, "
                                + "summary_text, content_cleared_at_fix, fixed_at_utc FROM snapshot_attachment_ref "
                                + "WHERE source_request_id = ? ORDER BY id ASC LIMIT 1",
                        REF_MAPPER, requestId)
                .stream().findFirst();
    }
}
