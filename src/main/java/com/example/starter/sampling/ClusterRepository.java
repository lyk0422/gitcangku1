package com.example.starter.sampling;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 采样冲突簇持久化：批量合并产生待裁决簇，人工裁决后不再重算。所有 SQL 使用参数化查询。
 */
@Repository
public class ClusterRepository {

    private static final String COLUMNS = "cluster_id, batch_id, device_id, grid_x, grid_y, status, "
            + "winning_content, adjudicated_by, adjudicated_at_utc";

    private static final RowMapper<ConflictCluster> CLUSTER_MAPPER = (rs, rowNum) -> new ConflictCluster(
            rs.getString("cluster_id"),
            rs.getString("batch_id"),
            rs.getString("device_id"),
            rs.getInt("grid_x"),
            rs.getInt("grid_y"),
            ClusterStatus.valueOf(rs.getString("status")),
            rs.getString("winning_content"),
            rs.getString("adjudicated_by"),
            rs.getTimestamp("adjudicated_at_utc") == null ? null : rs.getTimestamp("adjudicated_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public ClusterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一个待裁决冲突簇。
     */
    public void insert(ConflictCluster cluster) {
        jdbcTemplate.update(
                "INSERT INTO sampling_conflict_cluster (cluster_id, batch_id, device_id, grid_x, grid_y, status, "
                        + "winning_content, adjudicated_by, adjudicated_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, CURRENT_TIMESTAMP)",
                cluster.clusterId(), cluster.batchId(), cluster.deviceId(),
                cluster.gridX(), cluster.gridY(), cluster.status().name());
    }

    /**
     * 按标识查询冲突簇（不加锁）。
     */
    public Optional<ConflictCluster> findById(String clusterId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM sampling_conflict_cluster WHERE cluster_id = ?",
                        CLUSTER_MAPPER, clusterId)
                .stream().findFirst();
    }

    /**
     * 按标识查询冲突簇并加行锁，用于裁决串行化。
     */
    public Optional<ConflictCluster> findByIdForUpdate(String clusterId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM sampling_conflict_cluster WHERE cluster_id = ? FOR UPDATE",
                        CLUSTER_MAPPER, clusterId)
                .stream().findFirst();
    }

    /**
     * 将簇标记为已人工裁决，记录采用内容、操作者与裁决时刻（UTC）。
     */
    public void markAdjudicated(String clusterId, String winningContent, String operator, Instant adjudicatedAtUtc) {
        jdbcTemplate.update(
                "UPDATE sampling_conflict_cluster SET status = ?, winning_content = ?, adjudicated_by = ?, "
                        + "adjudicated_at_utc = ? WHERE cluster_id = ?",
                ClusterStatus.ADJUDICATED.name(), winningContent, operator,
                Timestamp.from(adjudicatedAtUtc), clusterId);
    }
}
