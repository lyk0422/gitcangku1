package com.example.starter.observation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 冲突簇持久化：conflict_cluster 记录簇成员归属之外的簇级状态（胜出记录、人工裁决）。
 * 同时提供簇操作全局锁：提交、基准变更与人工裁决均先取锁，按提交顺序串行化。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class ClusterRepository {

    private static final RowMapper<ConflictCluster> CLUSTER_MAPPER = (rs, rowNum) -> new ConflictCluster(
            rs.getString("cluster_id"),
            rs.getString("winner_observation_id"),
            rs.getBoolean("manually_resolved"),
            rs.getString("resolved_observation_id"));

    private final JdbcTemplate jdbcTemplate;

    public ClusterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 获取簇操作全局锁：确保锁行存在后以 SELECT ... FOR UPDATE 持有至事务结束，
     * 使提交、基准变更与人工裁决在提交顺序上串行化。
     */
    public void acquireLock() {
        try {
            jdbcTemplate.update("INSERT INTO cluster_lock (lock_id) VALUES (1)");
        } catch (DuplicateKeyException ignored) {
            // 锁行已存在
        }
        jdbcTemplate.queryForObject("SELECT lock_id FROM cluster_lock WHERE lock_id = 1 FOR UPDATE",
                Integer.class);
    }

    /**
     * 插入新簇（初始无胜出记录、未人工裁决）。
     */
    public void insert(String clusterId) {
        jdbcTemplate.update(
                "INSERT INTO conflict_cluster (cluster_id, winner_observation_id, manually_resolved, "
                        + "resolved_observation_id, created_at, updated_at) "
                        + "VALUES (?, NULL, FALSE, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                clusterId);
    }

    /**
     * 按簇标识查询（不加锁）；不存在时返回空。
     */
    public Optional<ConflictCluster> findById(String clusterId) {
        return jdbcTemplate.query(
                        "SELECT cluster_id, winner_observation_id, manually_resolved, resolved_observation_id "
                                + "FROM conflict_cluster WHERE cluster_id = ?",
                        CLUSTER_MAPPER, clusterId)
                .stream().findFirst();
    }

    /**
     * 按簇标识查询并加行锁（SELECT ... FOR UPDATE），用于人工裁决事务内串行化。
     */
    public Optional<ConflictCluster> findForUpdate(String clusterId) {
        return jdbcTemplate.query(
                        "SELECT cluster_id, winner_observation_id, manually_resolved, resolved_observation_id "
                                + "FROM conflict_cluster WHERE cluster_id = ? FOR UPDATE",
                        CLUSTER_MAPPER, clusterId)
                .stream().findFirst();
    }

    /**
     * 查询全部簇。
     */
    public List<ConflictCluster> findAll() {
        return jdbcTemplate.query(
                "SELECT cluster_id, winner_observation_id, manually_resolved, resolved_observation_id "
                        + "FROM conflict_cluster ORDER BY cluster_id ASC",
                CLUSTER_MAPPER);
    }

    /**
     * 查询全部已人工裁决的簇标识。
     */
    public List<String> findManuallyResolvedIds() {
        return jdbcTemplate.queryForList(
                "SELECT cluster_id FROM conflict_cluster WHERE manually_resolved = TRUE", String.class);
    }

    /**
     * 更新当前胜出记录。
     */
    public void updateWinner(String clusterId, String winnerObservationId) {
        jdbcTemplate.update(
                "UPDATE conflict_cluster SET winner_observation_id = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE cluster_id = ?",
                winnerObservationId, clusterId);
    }

    /**
     * 标记人工裁决：固化选定记录为胜出记录，之后不被自动覆盖。
     */
    public void markResolved(String clusterId, String observationId) {
        jdbcTemplate.update(
                "UPDATE conflict_cluster SET manually_resolved = TRUE, resolved_observation_id = ?, "
                        + "winner_observation_id = ?, updated_at = CURRENT_TIMESTAMP WHERE cluster_id = ?",
                observationId, observationId, clusterId);
    }

    /**
     * 删除簇（成员被吸收或簇解散时）。
     */
    public void delete(String clusterId) {
        jdbcTemplate.update("DELETE FROM conflict_cluster WHERE cluster_id = ?", clusterId);
    }
}
