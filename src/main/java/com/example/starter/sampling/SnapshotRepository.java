package com.example.starter.sampling;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 观测状态快照持久化：许可撤销等状态变更逐条追加，历史永不删除。所有 SQL 使用参数化查询。
 */
@Repository
public class SnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    public SnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一条观测状态变更快照。
     */
    public void insert(String permitKey, String fromStatus, String toStatus, String reason) {
        jdbcTemplate.update(
                "INSERT INTO sampling_observation_snapshot (permit_key, from_status, to_status, reason, created_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                permitKey, fromStatus, toStatus, reason);
    }
}
