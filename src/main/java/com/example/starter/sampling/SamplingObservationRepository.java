package com.example.starter.sampling;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 采样观测持久化：permit_key 为幂等键，仅成功接收的观测落库。所有 SQL 使用参数化查询。
 */
@Repository
public class SamplingObservationRepository {

    private static final String COLUMNS = "permit_key, device_id, grid_x, grid_y, collected_at_utc, content, "
            + "permit_id, permit_version, status, batch_id, cluster_id, response_body";

    private static final RowMapper<SamplingObservation> OBSERVATION_MAPPER = (rs, rowNum) -> new SamplingObservation(
            rs.getString("permit_key"),
            rs.getString("device_id"),
            rs.getInt("grid_x"),
            rs.getInt("grid_y"),
            rs.getTimestamp("collected_at_utc").toInstant(),
            rs.getString("content"),
            rs.getString("permit_id"),
            rs.getInt("permit_version"),
            ObservationStatus.valueOf(rs.getString("status")),
            rs.getString("batch_id"),
            rs.getString("cluster_id"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbcTemplate;

    public SamplingObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条接收的观测；permit_key 主键冲突时抛出重复键异常（并发同键由数据库串行化）。
     */
    public void insert(SamplingObservation observation) {
        jdbcTemplate.update(
                "INSERT INTO sampling_observation (permit_key, device_id, grid_x, grid_y, collected_at_utc, content, "
                        + "permit_id, permit_version, status, batch_id, cluster_id, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                observation.permitKey(), observation.deviceId(), observation.gridX(), observation.gridY(),
                Timestamp.from(observation.collectedAtUtc()), observation.content(),
                observation.permitId(), observation.permitVersion(), observation.status().name(),
                observation.batchId(), observation.clusterId(), observation.responseBody());
    }

    /**
     * 按幂等键查询观测；不存在时返回空。
     */
    public Optional<SamplingObservation> findByKey(String permitKey) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM sampling_observation WHERE permit_key = ?",
                        OBSERVATION_MAPPER, permitKey)
                .stream().findFirst();
    }

    /**
     * 批量查询已存在的幂等键，用于批次内去重与重放判定。
     */
    public Set<String> findExistingKeys(Collection<String> permitKeys) {
        if (permitKeys.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(permitKeys.size(), "?"));
        return new HashSet<>(jdbcTemplate.query(
                "SELECT permit_key FROM sampling_observation WHERE permit_key IN (" + placeholders + ")",
                (rs, rowNum) -> rs.getString(1), permitKeys.toArray()));
    }

    /**
     * 按许可标识查询全部观测，按接收先后排序。
     */
    public List<SamplingObservation> findByPermitId(String permitId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sampling_observation WHERE permit_id = ? "
                        + "ORDER BY created_at ASC, permit_key ASC",
                OBSERVATION_MAPPER, permitId);
    }

    /**
     * 查询许可下未合并（RECEIVED）观测的幂等键并加行锁，用于撤销时逐条标记复核。
     */
    public List<String> findReceivedKeysByPermitForUpdate(String permitId) {
        return jdbcTemplate.query(
                "SELECT permit_key FROM sampling_observation WHERE permit_id = ? AND status = 'RECEIVED' "
                        + "ORDER BY permit_key ASC FOR UPDATE",
                (rs, rowNum) -> rs.getString(1), permitId);
    }

    /**
     * 更新单条观测状态。
     */
    public void updateStatus(String permitKey, ObservationStatus status) {
        jdbcTemplate.update(
                "UPDATE sampling_observation SET status = ? WHERE permit_key = ?",
                status.name(), permitKey);
    }

    /**
     * 将冲突簇全部成员观测置为指定状态（人工裁决后统一置 MERGED）。
     */
    public void updateStatusForCluster(String clusterId, ObservationStatus status) {
        jdbcTemplate.update(
                "UPDATE sampling_observation SET status = ? WHERE cluster_id = ?",
                status.name(), clusterId);
    }
}
