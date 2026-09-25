package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 设备观测提交持久化：device_observation 记录不可改写的原始本地时刻与可重建的矫正后时刻。
 * 合并顺序统一为（矫正后时刻，设备标识，提交标识）升序；胜出提交为该顺序下的最后一条。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class DeviceObservationRepository {

    private static final String COLUMNS = "submission_id, observation_id, device_id, device_local_at, "
            + "corrected_at_utc, offset_seconds, location, reading, note, request_id";

    private static final RowMapper<DeviceSubmission> SUBMISSION_MAPPER = (rs, rowNum) -> new DeviceSubmission(
            rs.getString("submission_id"),
            rs.getString("observation_id"),
            rs.getString("device_id"),
            rs.getTimestamp("device_local_at").toLocalDateTime(),
            rs.getTimestamp("corrected_at_utc").toInstant(),
            rs.getInt("offset_seconds"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getString("request_id"));

    private final JdbcTemplate jdbcTemplate;

    public DeviceObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条观测提交记录；submissionId 主键冲突时抛出重复键异常。
     */
    public void insert(DeviceSubmission submission) {
        jdbcTemplate.update(
                "INSERT INTO device_observation (submission_id, observation_id, device_id, device_local_at, "
                        + "corrected_at_utc, offset_seconds, location, reading, note, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                submission.submissionId(), submission.observationId(), submission.deviceId(),
                Timestamp.valueOf(submission.deviceLocalAt()), Timestamp.from(submission.correctedAtUtc()),
                submission.offsetSeconds(), submission.location(), submission.reading(), submission.note(),
                submission.requestId());
    }

    /**
     * 按提交标识查询；不存在时返回空。
     */
    public Optional<DeviceSubmission> findBySubmissionId(String submissionId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM device_observation WHERE submission_id = ?",
                        SUBMISSION_MAPPER, submissionId)
                .stream().findFirst();
    }

    /**
     * 按观测记录查询全部提交，按合并顺序（矫正后时刻，设备标识，提交标识）升序。
     */
    public List<DeviceSubmission> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_observation WHERE observation_id = ? "
                        + "ORDER BY corrected_at_utc ASC, device_id ASC, submission_id ASC",
                SUBMISSION_MAPPER, observationId);
    }

    /**
     * 查询当前胜出提交：合并顺序下的最后一条（矫正后时刻最大，并列时设备标识再提交标识字典序最大）。
     */
    public Optional<DeviceSubmission> findWinner(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM device_observation WHERE observation_id = ? "
                                + "ORDER BY corrected_at_utc DESC, device_id DESC, submission_id DESC LIMIT 1",
                        SUBMISSION_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 查询指定设备本地时刻不早于 fromLocal（含）的全部提交，即偏移重建的受影响范围。
     */
    public List<DeviceSubmission> findAffected(String deviceId, LocalDateTime fromLocal) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM device_observation "
                        + "WHERE device_id = ? AND device_local_at >= ? "
                        + "ORDER BY device_local_at ASC, submission_id ASC",
                SUBMISSION_MAPPER, deviceId, Timestamp.valueOf(fromLocal));
    }

    /**
     * 查询偏移重建受影响提交所涉及的观测记录标识（去重，按标识升序保证处理顺序稳定）。
     */
    public List<String> findAffectedObservationIds(String deviceId, LocalDateTime fromLocal) {
        return jdbcTemplate.query(
                "SELECT DISTINCT observation_id FROM device_observation "
                        + "WHERE device_id = ? AND device_local_at >= ? ORDER BY observation_id ASC",
                (rs, rowNum) -> rs.getString("observation_id"), deviceId, Timestamp.valueOf(fromLocal));
    }

    /**
     * 重建时回写矫正后时刻与命中偏移；原始本地时刻不被改写。
     */
    public void updateCorrection(String submissionId, Instant correctedAtUtc, int offsetSeconds) {
        jdbcTemplate.update(
                "UPDATE device_observation SET corrected_at_utc = ?, offset_seconds = ? WHERE submission_id = ?",
                Timestamp.from(correctedAtUtc), offsetSeconds, submissionId);
    }
}
