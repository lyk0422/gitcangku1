package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 人工裁决持久化：submission_adjudication 记录成功裁决的完整上下文，不可变、永不更新。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class AdjudicationRepository {

    private static final RowMapper<AdjudicationRecord> ADJUDICATION_MAPPER = (rs, rowNum) -> new AdjudicationRecord(
            rs.getString("adjudication_id"),
            rs.getString("submission_id"),
            rs.getString("request_id"),
            rs.getString("decision"),
            rs.getString("reason"),
            rs.getString("operator"),
            rs.getInt("merged_version"),
            rs.getTimestamp("adjudicated_at_utc").toInstant());

    private static final String ADJUDICATION_COLUMNS = "adjudication_id, submission_id, request_id, decision, "
            + "reason, operator, merged_version, adjudicated_at_utc";

    private final JdbcTemplate jdbcTemplate;

    public AdjudicationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条不可变裁决记录；adjudication_id 主键或 submission_id 唯一键冲突时抛出重复键异常。
     */
    public void insert(AdjudicationRecord record) {
        jdbcTemplate.update(
                "INSERT INTO submission_adjudication (adjudication_id, submission_id, request_id, decision, "
                        + "reason, operator, merged_version, adjudicated_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.adjudicationId(), record.submissionId(), record.requestId(), record.decision(),
                record.reason(), record.operator(), record.mergedVersion(),
                Timestamp.from(record.adjudicatedAtUtc()));
    }

    /**
     * 按全局唯一 adjudicationId 查询裁决记录；不存在时返回空。
     */
    public Optional<AdjudicationRecord> findByAdjudicationId(String adjudicationId) {
        return jdbcTemplate.query(
                        "SELECT " + ADJUDICATION_COLUMNS + " FROM submission_adjudication "
                                + "WHERE adjudication_id = ?",
                        ADJUDICATION_MAPPER, adjudicationId)
                .stream().findFirst();
    }

    /**
     * 按提交标识查询裁决历史，按裁决时刻与记录标识稳定排序；查询不修改状态。
     */
    public List<AdjudicationRecord> findBySubmissionId(String submissionId) {
        return jdbcTemplate.query(
                "SELECT " + ADJUDICATION_COLUMNS + " FROM submission_adjudication WHERE submission_id = ? "
                        + "ORDER BY adjudicated_at_utc ASC, adjudication_id ASC",
                ADJUDICATION_MAPPER, submissionId);
    }
}
