package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 未解决冲突登记持久化：合并检出冲突时登记 OPEN，显式解决成功后同事务置为 RESOLVED。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class ConflictRepository {

    private final JdbcTemplate jdbcTemplate;

    public ConflictRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 登记一条 OPEN 冲突；conflictId 取触发合并请求的 requestId，重复时抛出重复键异常由调用方忽略。
     */
    public void insertOpen(String conflictId, String observationId, String conflictFieldsJson,
                           Instant detectedAtUtc) {
        jdbcTemplate.update(
                "INSERT INTO observation_conflict (conflict_id, observation_id, conflict_fields, status, "
                        + "detected_at_utc, resolved_at_utc, resolution_id, created_at) "
                        + "VALUES (?, ?, ?, 'OPEN', ?, NULL, NULL, CURRENT_TIMESTAMP)",
                conflictId, observationId, conflictFieldsJson, Timestamp.from(detectedAtUtc));
    }

    /**
     * 统计指定观测记录的未解决冲突数量（实际值）。
     */
    public int countOpen(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_conflict WHERE observation_id = ? AND status = 'OPEN'",
                Integer.class, observationId);
        return count == null ? 0 : count;
    }

    /**
     * 将指定观测记录的全部 OPEN 冲突置为 RESOLVED，记录解决标识与时刻；在解决事务内调用。
     */
    public void closeOpen(String observationId, String resolutionId, Instant resolvedAtUtc) {
        jdbcTemplate.update(
                "UPDATE observation_conflict SET status = 'RESOLVED', resolved_at_utc = ?, resolution_id = ? "
                        + "WHERE observation_id = ? AND status = 'OPEN'",
                Timestamp.from(resolvedAtUtc), resolutionId, observationId);
    }
}