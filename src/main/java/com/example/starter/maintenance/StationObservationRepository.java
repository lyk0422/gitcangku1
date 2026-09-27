package com.example.starter.maintenance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 站点观测持久化：原始记录始终保存，维护标记与命中窗口版本在提交时固化。
 */
@Repository
public class StationObservationRepository {

    private static final RowMapper<StationObservation> OBSERVATION_MAPPER = (rs, rowNum) -> new StationObservation(
            rs.getString("observation_id"),
            rs.getString("station_id"),
            rs.getString("cluster_id"),
            rs.getTimestamp("submitted_at_utc").toInstant(),
            rs.getString("payload"),
            rs.getBoolean("maintenance_affected"),
            rs.getString("matched_window_id"),
            (Integer) rs.getObject("matched_maintenance_version"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public StationObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条原始观测记录（含固化的维护标记）。
     */
    public void insert(StationObservation observation) {
        jdbcTemplate.update(
                "INSERT INTO station_observation (observation_id, station_id, cluster_id, submitted_at_utc, "
                        + "payload, maintenance_affected, matched_window_id, matched_maintenance_version, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                observation.observationId(),
                observation.stationId(),
                observation.clusterId(),
                Timestamp.from(observation.submittedAtUtc()),
                observation.payload(),
                observation.maintenanceAffected(),
                observation.matchedWindowId(),
                observation.matchedMaintenanceVersion(),
                Timestamp.from(observation.createdAtUtc()));
    }

    /**
     * 按标识查询原始观测。
     */
    public Optional<StationObservation> findById(String observationId) {
        return jdbcTemplate.query(selectAll() + " WHERE observation_id = ?", OBSERVATION_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 按冲突簇查询观测并对这些行加锁，用于合并/裁决事务内串行化同簇操作；
     * 按提交时刻升序、平局按观测标识升序，保证胜出规则与证据顺序稳定。
     */
    public List<StationObservation> findByClusterForUpdate(String clusterId) {
        return jdbcTemplate.query(
                selectAll() + " WHERE cluster_id = ? "
                        + "ORDER BY submitted_at_utc ASC, observation_id ASC FOR UPDATE",
                OBSERVATION_MAPPER, clusterId);
    }

    /**
     * 按冲突簇查询观测（不加锁），用于只读诊断。
     */
    public List<StationObservation> findByCluster(String clusterId) {
        return jdbcTemplate.query(
                selectAll() + " WHERE cluster_id = ? ORDER BY submitted_at_utc ASC, observation_id ASC",
                OBSERVATION_MAPPER, clusterId);
    }

    /**
     * 查询冲突簇已落库观测涉及的全部站点标识（跨批次同簇校验用）。
     */
    public List<String> findStationIdsByCluster(String clusterId) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT station_id FROM station_observation WHERE cluster_id = ?",
                String.class, clusterId);
    }

    private String selectAll() {
        return "SELECT observation_id, station_id, cluster_id, submitted_at_utc, payload, "
                + "maintenance_affected, matched_window_id, matched_maintenance_version, created_at_utc "
                + "FROM station_observation";
    }
}
