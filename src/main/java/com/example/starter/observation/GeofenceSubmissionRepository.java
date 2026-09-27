package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 离线观测地理围栏提交持久化：原始证据（坐标、采集时刻、匹配结果、提交时刻）只插不改；
 * 仅人工接受流程可推进 status 与 decided_* 字段一次。所有 SQL 使用参数化查询。
 */
@Repository
public class GeofenceSubmissionRepository {

    private static final String COLUMNS = "submission_id, request_id, batch_seq, site_id, observation_id, "
            + "base_version, candidate_location, candidate_reading, candidate_note, latitude, longitude, "
            + "observed_at_utc, match_result, matched_fence_id, status, merge_version, operator, "
            + "submitted_at_utc, decided_by, decided_at_utc, decision_reason";

    private static final RowMapper<GeofenceSubmission> SUBMISSION_MAPPER = (rs, rowNum) -> {
        Timestamp decidedAt = rs.getTimestamp("decided_at_utc");
        return new GeofenceSubmission(
                rs.getString("submission_id"),
                rs.getString("request_id"),
                rs.getInt("batch_seq"),
                rs.getString("site_id"),
                rs.getString("observation_id"),
                rs.getInt("base_version"),
                rs.getString("candidate_location"),
                rs.getString("candidate_reading"),
                rs.getString("candidate_note"),
                rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude"),
                rs.getTimestamp("observed_at_utc").toInstant(),
                rs.getString("match_result"),
                rs.getString("matched_fence_id"),
                rs.getString("status"),
                (Integer) rs.getObject("merge_version"),
                rs.getString("operator"),
                rs.getTimestamp("submitted_at_utc").toInstant(),
                rs.getString("decided_by"),
                decidedAt == null ? null : decidedAt.toInstant(),
                rs.getString("decision_reason"));
    };

    private final JdbcTemplate jdbcTemplate;

    public GeofenceSubmissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条提交证据记录；submissionId 主键冲突时抛出重复键异常。
     */
    public void insert(GeofenceSubmission submission) {
        jdbcTemplate.update(
                "INSERT INTO geofence_submission (submission_id, request_id, batch_seq, site_id, observation_id, "
                        + "base_version, candidate_location, candidate_reading, candidate_note, latitude, longitude, "
                        + "observed_at_utc, match_result, matched_fence_id, status, merge_version, operator, "
                        + "submitted_at_utc, decided_by, decided_at_utc, decision_reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)",
                submission.submissionId(), submission.requestId(), submission.batchSeq(), submission.siteId(),
                submission.observationId(), submission.baseVersion(), submission.candidateLocation(),
                submission.candidateReading(), submission.candidateNote(), submission.latitude(),
                submission.longitude(), Timestamp.from(submission.observedAtUtc()), submission.matchResult(),
                submission.matchedFenceId(), submission.status(), submission.mergeVersion(),
                submission.operator(), Timestamp.from(submission.submittedAtUtc()));
    }

    /**
     * 按全局唯一 submissionId 查询提交；不存在时返回空。
     */
    public Optional<GeofenceSubmission> findBySubmissionId(String submissionId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM geofence_submission WHERE submission_id = ?",
                        SUBMISSION_MAPPER, submissionId)
                .stream().findFirst();
    }

    /**
     * 按 submissionId 查询并加行锁（SELECT ... FOR UPDATE），用于人工接受事务内串行化。
     */
    public Optional<GeofenceSubmission> findBySubmissionIdForUpdate(String submissionId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM geofence_submission WHERE submission_id = ? FOR UPDATE",
                        SUBMISSION_MAPPER, submissionId)
                .stream().findFirst();
    }

    /**
     * 按站点查询提交历史，按受理时刻与提交标识稳定排序。
     */
    public List<GeofenceSubmission> findBySiteId(String siteId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM geofence_submission WHERE site_id = ? "
                        + "ORDER BY submitted_at_utc ASC, submission_id ASC",
                SUBMISSION_MAPPER, siteId);
    }

    /**
     * 按站点统计指定状态的提交数量（实际值）。
     */
    public long countBySiteIdAndStatus(String siteId, String status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geofence_submission WHERE site_id = ? AND status = ?",
                Long.class, siteId, status);
        return count == null ? 0 : count;
    }

    /**
     * 人工接受：推进状态并写入裁决审计字段；原始证据列不被修改。
     */
    public void markManuallyAccepted(String submissionId, String decidedBy, Instant decidedAtUtc,
                                     String decisionReason, int mergeVersion) {
        jdbcTemplate.update(
                "UPDATE geofence_submission SET status = ?, decided_by = ?, decided_at_utc = ?, "
                        + "decision_reason = ?, merge_version = ? WHERE submission_id = ?",
                GeofenceSubmission.MANUALLY_ACCEPTED, decidedBy, Timestamp.from(decidedAtUtc),
                decisionReason, mergeVersion, submissionId);
    }
}
