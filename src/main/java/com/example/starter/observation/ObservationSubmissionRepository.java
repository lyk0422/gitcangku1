package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 观测提交原始记录持久化：批量提交一次事务插入多条；状态流转（PENDING/REVIEW/CONSUMED）只在合并/裁决事务内更新。
 * 维护标记列写入后不再更新（按提交时版本固化）。所有 SQL 使用参数化查询。
 */
@Repository
public class ObservationSubmissionRepository {

    private static final RowMapper<ObservationSubmission> SUBMISSION_MAPPER = (rs, rowNum) ->
            new ObservationSubmission(
                    rs.getString("submission_id"),
                    rs.getString("station_id"),
                    rs.getString("observation_id"),
                    rs.getTimestamp("submitted_at_utc").toInstant(),
                    rs.getString("location"),
                    rs.getString("reading"),
                    rs.getString("note"),
                    rs.getBoolean("maintenance_affected"),
                    rs.getString("window_id"),
                    (Integer) rs.getObject("window_version"),
                    SubmissionStatus.valueOf(rs.getString("status")),
                    rs.getString("batch_request_id"));

    private final JdbcTemplate jdbcTemplate;

    public ObservationSubmissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条原始提交记录（维护标记已在服务层按提交时窗口版本确定并固化）。
     */
    public void insert(ObservationSubmission submission) {
        jdbcTemplate.update(
                "INSERT INTO observation_submission (submission_id, station_id, observation_id, submitted_at_utc, "
                        + "location, reading, note, maintenance_affected, window_id, window_version, status, "
                        + "batch_request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                submission.submissionId(), submission.stationId(), submission.observationId(),
                Timestamp.from(submission.submittedAtUtc()), submission.location(), submission.reading(),
                submission.note(), submission.maintenanceAffected(), submission.windowId(),
                submission.windowVersion(), submission.status().name(), submission.batchRequestId());
    }

    /**
     * 按提交标识查询（不加锁）。
     */
    public Optional<ObservationSubmission> findById(String submissionId) {
        return jdbcTemplate.query(
                        "SELECT submission_id, station_id, observation_id, submitted_at_utc, location, reading, note, "
                                + "maintenance_affected, window_id, window_version, status, batch_request_id "
                                + "FROM observation_submission WHERE submission_id = ?",
                        SUBMISSION_MAPPER, submissionId)
                .stream().findFirst();
    }

    /**
     * 按批次 requestId 查询该批全部提交，按提交时刻、提交标识排序，供批次明细/证据查询。
     */
    public List<ObservationSubmission> findByBatchRequestId(String batchRequestId) {
        return jdbcTemplate.query(
                "SELECT submission_id, station_id, observation_id, submitted_at_utc, location, reading, note, "
                        + "maintenance_affected, window_id, window_version, status, batch_request_id "
                        + "FROM observation_submission WHERE batch_request_id = ? "
                        + "ORDER BY submitted_at_utc ASC, submission_id ASC",
                SUBMISSION_MAPPER, batchRequestId);
    }

    /**
     * 读取一个观测记录冲突簇中未消费的提交并加行锁，按提交时刻、提交标识排序；供合并事务串行裁决。
     */
    public List<ObservationSubmission> findPendingClusterForUpdate(String observationId) {
        return jdbcTemplate.query(
                "SELECT submission_id, station_id, observation_id, submitted_at_utc, location, reading, note, "
                        + "maintenance_affected, window_id, window_version, status, batch_request_id "
                        + "FROM observation_submission WHERE observation_id = ? AND status IN ('PENDING','REVIEW') "
                        + "ORDER BY submitted_at_utc ASC, submission_id ASC FOR UPDATE",
                SUBMISSION_MAPPER, observationId);
    }

    /**
     * 按标识列表加行锁读取簇内提交（人工裁决重算用，顺序由服务层保证）。
     */
    public List<ObservationSubmission> findByIdsForUpdate(List<String> submissionIds) {
        if (submissionIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", submissionIds.stream().map(s -> "?").toList());
        return jdbcTemplate.query(
                "SELECT submission_id, station_id, observation_id, submitted_at_utc, location, reading, note, "
                        + "maintenance_affected, window_id, window_version, status, batch_request_id "
                        + "FROM observation_submission WHERE submission_id IN (" + placeholders + ") FOR UPDATE",
                SUBMISSION_MAPPER, submissionIds.toArray());
    }

    /**
     * 更新处理状态（PENDING/REVIEW/CONSUMED）；维护标记列永不更新。
     */
    public void updateStatus(String submissionId, SubmissionStatus status) {
        jdbcTemplate.update(
                "UPDATE observation_submission SET status = ? WHERE submission_id = ?",
                status.name(), submissionId);
    }

    /**
     * 按观测记录统计当前未消费提交数（合并幂等/状态前置校验用）。
     */
    public int countPendingByObservation(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_submission "
                        + "WHERE observation_id = ? AND status IN ('PENDING','REVIEW')",
                Integer.class, observationId);
        return count == null ? 0 : count;
    }
}
