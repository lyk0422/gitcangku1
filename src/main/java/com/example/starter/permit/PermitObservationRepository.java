package com.example.starter.permit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 许可观测持久化：permit_observation 当前态与 permit_observation_version 快照，均参数化 SQL。
 */
@Repository
public class PermitObservationRepository {

    private static final String COLUMNS = "observation_key, device_id, grid_lon, grid_lat, sampled_at_utc, "
            + "permit_id, permit_version, content, review_status, merged_cluster_id, reject_reason_code, "
            + "received_at_utc";

    private static final RowMapper<PermitObservation> OBSERVATION_MAPPER = (rs, rowNum) -> new PermitObservation(
            rs.getString("observation_key"),
            rs.getString("device_id"),
            rs.getBigDecimal("grid_lon"),
            rs.getBigDecimal("grid_lat"),
            rs.getTimestamp("sampled_at_utc").toInstant(),
            rs.getString("permit_id"),
            rs.getInt("permit_version"),
            rs.getString("content"),
            rs.getString("review_status"),
            rs.getString("merged_cluster_id"),
            rs.getString("reject_reason_code"),
            rs.getTimestamp("received_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public PermitObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按业务键加锁读取当前观测。
     */
    public Optional<PermitObservation> findByKeyForUpdate(String observationKey) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM permit_observation WHERE observation_key = ? FOR UPDATE",
                        OBSERVATION_MAPPER, observationKey)
                .stream().findFirst();
    }

    /**
     * 按业务键读取当前观测（不加锁）。
     */
    public Optional<PermitObservation> findByKey(String observationKey) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM permit_observation WHERE observation_key = ?",
                        OBSERVATION_MAPPER, observationKey)
                .stream().findFirst();
    }

    /**
     * 插入接收的观测当前行（初始 RECEIVED）。
     */
    public void insertCurrent(PermitObservation observation) {
        jdbcTemplate.update(
                "INSERT INTO permit_observation (observation_key, device_id, grid_lon, grid_lat, sampled_at_utc, "
                        + "permit_id, permit_version, content, review_status, merged_cluster_id, "
                        + "reject_reason_code, received_at_utc, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                observation.observationKey(), observation.deviceId(),
                observation.gridLon(), observation.gridLat(), Timestamp.from(observation.sampledAt()),
                observation.permitId(), observation.permitVersion(), observation.content(),
                observation.reviewStatus(), observation.mergedClusterId(), observation.rejectReasonCode(),
                Timestamp.from(observation.receivedAt()));
    }

    /**
     * 追加一版状态快照。
     */
    public void insertVersion(PermitObservation observation, int version) {
        jdbcTemplate.update(
                "INSERT INTO permit_observation_version (observation_key, version, device_id, grid_lon, grid_lat, "
                        + "sampled_at_utc, permit_id, permit_version, content, review_status, merged_cluster_id, "
                        + "reject_reason_code, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                observation.observationKey(), version, observation.deviceId(),
                observation.gridLon(), observation.gridLat(), Timestamp.from(observation.sampledAt()),
                observation.permitId(), observation.permitVersion(), observation.content(),
                observation.reviewStatus(), observation.mergedClusterId(), observation.rejectReasonCode());
    }

    /**
     * 返回该观测当前最大快照版本号；无快照返回 0。
     */
    public int currentVersion(String observationKey) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT MAX(version) FROM permit_observation_version WHERE observation_key = ?",
                Integer.class, observationKey);
        return version == null ? 0 : version;
    }

    /**
     * 更新合并状态与冲突簇归属；无冲突观测的 clusterId 为 null。
     */
    public void updateMerged(String observationKey, String clusterId) {
        jdbcTemplate.update(
                "UPDATE permit_observation SET review_status = ?, merged_cluster_id = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE observation_key = ?",
                PermitReviewStatuses.MERGED, clusterId, observationKey);
    }

    /**
     * 将观测置为许可撤销复核。
     */
    public void updateReview(String observationKey) {
        jdbcTemplate.update(
                "UPDATE permit_observation SET review_status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE observation_key = ?",
                PermitReviewStatuses.PERMIT_REVIEW, observationKey);
    }

    /**
     * 读取某许可下全部未合并（RECEIVED）观测并加锁，供撤销快照使用。
     */
    public List<PermitObservation> findReceivedByPermitForUpdate(String permitId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM permit_observation WHERE permit_id = ? "
                        + "AND review_status = ? FOR UPDATE",
                OBSERVATION_MAPPER, permitId, PermitReviewStatuses.RECEIVED);
    }

    /**
     * 读取某冲突簇的全部成员观测并加锁，供人工裁决使用。
     */
    public List<PermitObservation> findByClusterForUpdate(String clusterId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM permit_observation WHERE merged_cluster_id = ? FOR UPDATE",
                OBSERVATION_MAPPER, clusterId);
    }

    /**
     * 判断给定冲突簇标识是否已被任一已合并观测引用（供批次簇重复预校验）。
     */
    public boolean clusterReferenced(String clusterId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM permit_observation WHERE merged_cluster_id = ?",
                Integer.class, clusterId);
        return count != null && count > 0;
    }
}
