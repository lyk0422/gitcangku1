package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 墓碑恢复历史持久化：observation_recovery 记录每次成功恢复的完整上下文，不可变、永不更新。
 * 所有 SQL 使用参数化查询；查询接口只读，不会产生任何写入。
 */
@Repository
public class RecoveryRepository {

    private static final RowMapper<RecoveryRecord> RECOVERY_MAPPER = (rs, rowNum) -> new RecoveryRecord(
            rs.getString("observation_id"),
            rs.getInt("recovered_version"),
            rs.getInt("previous_version"),
            rs.getInt("source_version"),
            rs.getInt("generation_before"),
            rs.getInt("generation_after"),
            rs.getString("reason"),
            rs.getString("request_id"),
            rs.getTimestamp("recovered_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public RecoveryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条不可变恢复记录，与观测状态变更在同一事务内提交。
     */
    public void insert(RecoveryRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_recovery (observation_id, recovered_version, previous_version, "
                        + "source_version, generation_before, generation_after, reason, request_id, "
                        + "recovered_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.observationId(),
                record.recoveredVersion(),
                record.previousVersion(),
                record.sourceVersion(),
                record.generationBefore(),
                record.generationAfter(),
                record.reason(),
                record.requestId(),
                Timestamp.from(record.recoveredAtUtc()));
    }

    /**
     * 按 observationId 按恢复先后查询全部恢复历史；无记录时返回空列表。
     */
    public List<RecoveryRecord> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT observation_id, recovered_version, previous_version, source_version, "
                        + "generation_before, generation_after, reason, request_id, recovered_at_utc "
                        + "FROM observation_recovery WHERE observation_id = ? "
                        + "ORDER BY recovered_at_utc ASC, recovered_version ASC",
                RECOVERY_MAPPER, observationId);
    }

    /**
     * 按 requestId 查询恢复记录（幂等诊断用）；不存在时返回空。
     */
    public Optional<RecoveryRecord> findByRequestId(String requestId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, recovered_version, previous_version, source_version, "
                                + "generation_before, generation_after, reason, request_id, recovered_at_utc "
                                + "FROM observation_recovery WHERE request_id = ?",
                        RECOVERY_MAPPER, requestId)
                .stream().findFirst();
    }
}
