package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 人工裁决持久化：submission_adjudication 记录不可变裁决证据，一次提交至多一条。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class AdjudicationRepository {

    private static final RowMapper<AdjudicationRecord> ADJUDICATION_MAPPER = (rs, rowNum) ->
            new AdjudicationRecord(
                    rs.getString("submission_id"),
                    rs.getString("request_id"),
                    rs.getString("operator"),
                    rs.getString("decision"),
                    rs.getString("reason"),
                    rs.getInt("observation_version"),
                    rs.getTimestamp("decided_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public AdjudicationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一条不可变裁决记录；该提交已有裁决时抛出重复键异常。
     */
    public void insert(AdjudicationRecord record) {
        jdbcTemplate.update(
                "INSERT INTO submission_adjudication (submission_id, request_id, operator, decision, reason, "
                        + "observation_version, decided_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.submissionId(), record.requestId(), record.operator(), record.decision(),
                record.reason(), record.observationVersion(), Timestamp.from(record.decidedAtUtc()));
    }

    /**
     * 按提交标识查询裁决记录；未裁决时返回空。
     */
    public Optional<AdjudicationRecord> findBySubmissionId(String submissionId) {
        return jdbcTemplate.query(
                        "SELECT submission_id, request_id, operator, decision, reason, "
                                + "observation_version, decided_at_utc "
                                + "FROM submission_adjudication WHERE submission_id = ?",
                        ADJUDICATION_MAPPER, submissionId)
                .stream().findFirst();
    }

    /**
     * 诊断统计：站点下已被人工接受的提交数量（按提交关联）。
     */
    public int countAcceptedBySite(String siteId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_adjudication a "
                        + "JOIN observation_submission s ON s.submission_id = a.submission_id "
                        + "WHERE s.site_id = ?",
                Integer.class, siteId);
        return count == null ? 0 : count;
    }
}
