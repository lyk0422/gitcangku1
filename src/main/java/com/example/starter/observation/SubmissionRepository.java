package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 离线观测提交持久化：observation_submission 记录逐条提交证据。
 * 原始坐标、采集时刻、提交时刻与匹配结果写入后不可变；仅状态与合并版本可经人工裁决更新。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class SubmissionRepository {

    private static final RowMapper<SubmissionRecord> SUBMISSION_MAPPER = (rs, rowNum) -> new SubmissionRecord(
            rs.getString("submission_id"),
            rs.getString("batch_request_id"),
            rs.getInt("batch_seq"),
            rs.getString("site_id"),
            rs.getString("observation_id"),
            rs.getInt("base_version"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getBigDecimal("latitude"),
            rs.getBigDecimal("longitude"),
            rs.getTimestamp("collected_at_utc").toInstant(),
            rs.getTimestamp("submitted_at_utc").toInstant(),
            rs.getString("status"),
            rs.getString("match_result"),
            rs.getString("matched_fence_id"),
            (Integer) rs.getObject("matched_fence_version"),
            (Integer) rs.getObject("merged_version"));

    private static final String SUBMISSION_COLUMNS = "submission_id, batch_request_id, batch_seq, site_id, "
            + "observation_id, base_version, location, reading, note, latitude, longitude, "
            + "collected_at_utc, submitted_at_utc, status, match_result, matched_fence_id, "
            + "matched_fence_version, merged_version";

    private final JdbcTemplate jdbcTemplate;

    public SubmissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条提交记录；submission_id 主键冲突时抛出重复键异常。
     */
    public void insert(SubmissionRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_submission (submission_id, batch_request_id, batch_seq, site_id, "
                        + "observation_id, base_version, location, reading, note, latitude, longitude, "
                        + "collected_at_utc, submitted_at_utc, status, match_result, matched_fence_id, "
                        + "matched_fence_version, merged_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.submissionId(), record.batchRequestId(), record.batchSeq(), record.siteId(),
                record.observationId(), record.baseVersion(), record.location(), record.reading(),
                record.note(), record.latitude(), record.longitude(),
                Timestamp.from(record.collectedAtUtc()), Timestamp.from(record.submittedAtUtc()),
                record.status(), record.matchResult(), record.matchedFenceId(),
                record.matchedFenceVersion(), record.mergedVersion());
    }

    /**
     * 按提交标识查询（不加锁）；不存在时返回空。
     */
    public Optional<SubmissionRecord> find(String submissionId) {
        return jdbcTemplate.query(
                        "SELECT " + SUBMISSION_COLUMNS + " FROM observation_submission WHERE submission_id = ?",
                        SUBMISSION_MAPPER, submissionId)
                .stream().findFirst();
    }

    /**
     * 按提交标识查询并加行锁（SELECT ... FOR UPDATE），用于人工裁决串行化。
     */
    public Optional<SubmissionRecord> findForUpdate(String submissionId) {
        return jdbcTemplate.query(
                        "SELECT " + SUBMISSION_COLUMNS + " FROM observation_submission "
                                + "WHERE submission_id = ? FOR UPDATE",
                        SUBMISSION_MAPPER, submissionId)
                .stream().findFirst();
    }

    /**
     * 按站点查询提交历史，按提交时刻与批内顺序稳定排序；查询不修改状态。
     */
    public List<SubmissionRecord> findBySite(String siteId) {
        return jdbcTemplate.query(
                "SELECT " + SUBMISSION_COLUMNS + " FROM observation_submission WHERE site_id = ? "
                        + "ORDER BY submitted_at_utc ASC, batch_request_id ASC, batch_seq ASC",
                SUBMISSION_MAPPER, siteId);
    }

    /**
     * 人工裁决接受后推进状态与合并版本；其余字段（原始坐标、采集时刻、提交时刻、匹配结果）不可变。
     */
    public void markManuallyAccepted(String submissionId, int mergedVersion) {
        jdbcTemplate.update(
                "UPDATE observation_submission SET status = ?, merged_version = ? WHERE submission_id = ?",
                SubmissionRecord.STATUS_MANUAL_ACCEPTED, mergedVersion, submissionId);
    }
}
