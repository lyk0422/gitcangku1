package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 冲突簇持久化：geo_cluster 记录簇的胜出观测与人工裁决状态。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class GeoClusterRepository {

    private static final String COLUMNS = "cluster_id, manually_resolved, winner_observation_id, "
            + "resolved_by, resolved_at_utc";

    private static final RowMapper<GeoCluster> CLUSTER_MAPPER = (rs, rowNum) -> new GeoCluster(
            rs.getString("cluster_id"),
            rs.getBoolean("manually_resolved"),
            rs.getString("winner_observation_id"),
            rs.getString("resolved_by"),
            rs.getTimestamp("resolved_at_utc") == null ? null : rs.getTimestamp("resolved_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public GeoClusterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按标识查询冲突簇；不存在时返回空。
     */
    public Optional<GeoCluster> find(String clusterId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM geo_cluster WHERE cluster_id = ?",
                        CLUSTER_MAPPER, clusterId)
                .stream().findFirst();
    }

    /**
     * 按标识查询冲突簇并加行锁（SELECT ... FOR UPDATE），用于人工裁决事务内串行化。
     */
    public Optional<GeoCluster> findForUpdate(String clusterId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM geo_cluster WHERE cluster_id = ? FOR UPDATE",
                        CLUSTER_MAPPER, clusterId)
                .stream().findFirst();
    }

    /**
     * 查询全部冲突簇（按标识排序），用于簇归属全量重算与快照。
     */
    public List<GeoCluster> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM geo_cluster ORDER BY cluster_id ASC",
                CLUSTER_MAPPER);
    }

    /**
     * 创建冲突簇（初始未人工裁决）。
     */
    public void insert(GeoCluster cluster) {
        jdbcTemplate.update(
                "INSERT INTO geo_cluster (cluster_id, manually_resolved, winner_observation_id, "
                        + "resolved_by, resolved_at_utc, created_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                cluster.clusterId(), cluster.manuallyResolved(), cluster.winnerObservationId(),
                cluster.resolvedBy(),
                cluster.resolvedAtUtc() == null ? null : Timestamp.from(cluster.resolvedAtUtc()));
    }

    /**
     * 更新自动簇的胜出观测（仅未人工裁决的簇由重算调用）。
     */
    public void updateWinner(String clusterId, String winnerObservationId) {
        jdbcTemplate.update(
                "UPDATE geo_cluster SET winner_observation_id = ? WHERE cluster_id = ?",
                winnerObservationId, clusterId);
    }

    /**
     * 记录人工裁决结果：置人工裁决标记、胜出观测、操作者与裁决时刻。
     */
    public void markResolved(String clusterId, String winnerObservationId, String operator,
                             java.time.Instant resolvedAtUtc) {
        jdbcTemplate.update(
                "UPDATE geo_cluster SET manually_resolved = TRUE, winner_observation_id = ?, "
                        + "resolved_by = ?, resolved_at_utc = ? WHERE cluster_id = ?",
                winnerObservationId, operator, Timestamp.from(resolvedAtUtc), clusterId);
    }

    /**
     * 删除簇（重算后成员不足两员的自动簇被解散；人工裁决簇不删除）。
     */
    public void delete(String clusterId) {
        jdbcTemplate.update("DELETE FROM geo_cluster WHERE cluster_id = ?", clusterId);
    }
}
