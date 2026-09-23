package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 墓碑恢复历史持久化：restore_history 仅追加成功恢复记录，与当前行更新、新版本快照同事务原子提交；
 * 恢复失败随事务回滚，不留历史。所有 SQL 使用参数化查询。
 */
@Repository
public class RestoreHistoryRepository {

    private static final RowMapper<RestoreHistoryRecord> RECORD_MAPPER = (rs, rowNum) -> new RestoreHistoryRecord(
            rs.getString("observation_id"),
            rs.getString("request_id"),
            rs.getInt("previous_version"),
            rs.getInt("new_version"),
            rs.getInt("source_version"),
            rs.getInt("previous_generation"),
            rs.getInt("new_generation"),
            rs.getString("reason"),
            rs.getTimestamp("restored_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public RestoreHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一条不可变恢复历史；恢复业务失败时随外层事务回滚。
     */
    public void insert(RestoreHistoryRecord record) {
        jdbcTemplate.update(
                "INSERT INTO restore_history (observation_id, request_id, previous_version, new_version, "
                        + "source_version, previous_generation, new_generation, reason, restored_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.observationId(),
                record.requestId(),
                record.previousVersion(),
                record.newVersion(),
                record.sourceVersion(),
                record.previousGeneration(),
                record.newGeneration(),
                record.reason(),
                java.sql.Timestamp.from(record.restoredAtUtc()));
    }

    /**
     * 按 observationId 按恢复时刻先后查询全部恢复历史；只读查询，不写入任何数据。
     */
    public List<RestoreHistoryRecord> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT observation_id, request_id, previous_version, new_version, source_version, "
                        + "previous_generation, new_generation, reason, restored_at_utc "
                        + "FROM restore_history WHERE observation_id = ? "
                        + "ORDER BY restored_at_utc ASC, request_id ASC",
                RECORD_MAPPER, observationId);
    }
}
