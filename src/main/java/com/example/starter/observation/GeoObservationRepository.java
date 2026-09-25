package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 坐标观测持久化：geo_observation 保存原始坐标（不可改写）与统一坐标（重算可更新）。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class GeoObservationRepository {

    private static final String COLUMNS = "observation_id, device_id, request_id, raw_latitude, raw_longitude, "
            + "raw_frame_version, unified_latitude, unified_longitude, applied_frame_version, captured_at_utc, "
            + "location, reading, note, cluster_id";

    private static final RowMapper<GeoObservation> OBSERVATION_MAPPER = (rs, rowNum) -> new GeoObservation(
            rs.getString("observation_id"),
            rs.getString("device_id"),
            rs.getString("request_id"),
            rs.getDouble("raw_latitude"),
            rs.getDouble("raw_longitude"),
            rs.getString("raw_frame_version"),
            rs.getDouble("unified_latitude"),
            rs.getDouble("unified_longitude"),
            rs.getString("applied_frame_version"),
            rs.getTimestamp("captured_at_utc").toInstant(),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getString("cluster_id"));

    private final JdbcTemplate jdbcTemplate;

    public GeoObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按标识查询坐标观测；不存在时返回空。
     */
    public Optional<GeoObservation> find(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM geo_observation WHERE observation_id = ?",
                        OBSERVATION_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 查询指定设备的全部坐标观测（按落库先后排序），用于基准变更重算。
     */
    public List<GeoObservation> findByDeviceId(String deviceId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM geo_observation WHERE device_id = ? "
                        + "ORDER BY created_at ASC, observation_id ASC",
                OBSERVATION_MAPPER, deviceId);
    }

    /**
     * 查询全部坐标观测（按落库先后排序），用于簇归属全量重算。
     */
    public List<GeoObservation> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM geo_observation ORDER BY created_at ASC, observation_id ASC",
                OBSERVATION_MAPPER);
    }

    /**
     * 查询指定簇的全部成员标识（按标识排序，保证快照稳定）。
     */
    public List<String> findMemberIds(String clusterId) {
        return jdbcTemplate.query(
                "SELECT observation_id FROM geo_observation WHERE cluster_id = ? ORDER BY observation_id ASC",
                (rs, rowNum) -> rs.getString("observation_id"), clusterId);
    }

    /**
     * 插入新坐标观测；原始坐标与原基准版本落库后不再更新。
     */
    public void insert(GeoObservation observation) {
        jdbcTemplate.update(
                "INSERT INTO geo_observation (observation_id, device_id, request_id, raw_latitude, raw_longitude, "
                        + "raw_frame_version, unified_latitude, unified_longitude, applied_frame_version, "
                        + "captured_at_utc, location, reading, note, cluster_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                observation.observationId(), observation.deviceId(), observation.requestId(),
                observation.rawLatitude(), observation.rawLongitude(), observation.rawFrameVersion(),
                observation.unifiedLatitude(), observation.unifiedLongitude(), observation.appliedFrameVersion(),
                Timestamp.from(observation.capturedAtUtc()),
                observation.location(), observation.reading(), observation.note(), observation.clusterId());
    }

    /**
     * 重算后更新统一坐标与所采用的基准参数版本；原始坐标与原基准版本保持不变。
     */
    public void updateUnified(String observationId, double unifiedLatitude, double unifiedLongitude,
                              String appliedFrameVersion) {
        jdbcTemplate.update(
                "UPDATE geo_observation SET unified_latitude = ?, unified_longitude = ?, "
                        + "applied_frame_version = ? WHERE observation_id = ?",
                unifiedLatitude, unifiedLongitude, appliedFrameVersion, observationId);
    }

    /**
     * 更新簇归属（加入簇或置空脱离簇）。
     */
    public void updateCluster(String observationId, String clusterId) {
        jdbcTemplate.update(
                "UPDATE geo_observation SET cluster_id = ? WHERE observation_id = ?",
                clusterId, observationId);
    }
}
