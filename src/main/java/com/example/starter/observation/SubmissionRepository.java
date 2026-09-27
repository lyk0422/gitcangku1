package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 离线观测提交持久化：observation_submission 记录逐项落库的不可变提交证据，
 * 原始坐标、采集/提交时刻与匹配结果写入后不再更新。所有 SQL 使用参数化查询。
 */
@Repository
public class SubmissionRepository {

    private static final String COLUMNS =
            "submission_id, request_id, item_index, operator, observation_id, site_id, base_version, "
                    + "location, reading, note, captured_lat, captured_lon, captured_at_utc, submitted_at_utc, "
                    + "match_status, matched_fence_id, matched_fence_version, observation_version";

    private static final RowMapper<SubmissionRecord> SUBMISSION_MAPPER = (rs, rowNum) -> new SubmissionRecord(
            rs.getString("submission_id"),
            rs.getString("request_id"),
            rs.getInt("item_index"),
            rs.getString("operator"),
            rs.getString("observation_id"),
            rs.getString("site_id"),
            rs.getInt("base_version"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            Decimals.normalize(rs.getBigDecimal("captured_lat")),
            Decimals.normalize(rs.getBigDecimal("captured_lon")),
            rs.getTimestamp("captured_at_utc").toInstant(),
            rs.getTimestamp("submitted_at_utc").toInstant(),
            rs.getString("match_status"),
            rs.getString("matched_fence_id"),
            (Integer) rs.getObject("matched_fence_version"),
            (Integer) rs.getObject("observation_version"));

    private final JdbcTemplate jdbcTemplate;

    public SubmissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一条不可变提交记录；submissionId 已存在时抛出重复键异常。
     */
    public void insert(SubmissionRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_submission (submission_id, request_id, item_index, operator, "
                        + "observation_id, site_id, base_version, location, reading, note, "
                        + "captured_lat, captured_lon, captured_at_utc, submitted_at_utc, "
                        + "match_status, matched_fence_id, matched_fence_version, observation_version, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.submissionId(), record.requestId(), record.itemIndex(), record.operator(),
                record.observationId(), record.siteId(), record.baseVersion(),
                record.location(), record.reading(), record.note(),
                record.latitude(), record.longitude(),
                Timestamp.from(record.capturedAtUtc()), Timestamp.from(record.submittedAtUtc()),
                record.matchStatus(), record.matchedFenceId(), record.matchedFenceVersion(),
                record.observationVersion());
    }

    /**
     * 按提交标识查询（不加锁）；不存在时返回空。
     */
    public Optional<SubmissionRecord> find(String submissionId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM observation_submission WHERE submission_id = ?",
                        SUBMISSION_MAPPER, submissionId)
                .stream().findFirst();
    }

    /**
     * 按提交标识查询并加行锁，用于人工裁决事务内串行化同一提交的并发裁决。
     */
    public Optional<SubmissionRecord> findForUpdate(String submissionId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM observation_submission WHERE submission_id = ? FOR UPDATE",
                        SUBMISSION_MAPPER, submissionId)
                .stream().findFirst();
    }

    /**
     * 按观测记录查询提交历史，按提交时刻与提交标识升序（稳定排序）。
     */
    public List<SubmissionRecord> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM observation_submission WHERE observation_id = ? "
                        + "ORDER BY submitted_at_utc ASC, submission_id ASC",
                SUBMISSION_MAPPER, observationId);
    }

    /**
     * 按站点查询提交历史，按提交时刻与提交标识升序（稳定排序）。
     */
    public List<SubmissionRecord> findBySiteId(String siteId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM observation_submission WHERE site_id = ? "
                        + "ORDER BY submitted_at_utc ASC, submission_id ASC",
                SUBMISSION_MAPPER, siteId);
    }

    /**
     * 诊断统计：按匹配结果统计站点提交数量。
     */
    public int countBySiteAndStatus(String siteId, String matchStatus) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_submission WHERE site_id = ? AND match_status = ?",
                Integer.class, siteId, matchStatus);
        return count == null ? 0 : count;
    }
}
