package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 观测坐标持久化：observation_geo 保存原始坐标与原基准版本（不可改写）、
 * 统一基准坐标与簇归属（基准重算时更新）。所有 SQL 使用参数化查询。
 */
@Repository
public class ObservationGeoRepository {

    private static final String COLUMNS = "observation_id, submission_seq, device_id, "
            + "original_frame_version, current_frame_version, raw_latitude, raw_longitude, "
            + "unified_latitude, unified_longitude, captured_at, cluster_id";

    private static final RowMapper<ObservationGeo> GEO_MAPPER = (rs, rowNum) -> new ObservationGeo(
            rs.getString("observation_id"),
            rs.getLong("submission_seq"),
            rs.getString("device_id"),
            rs.getString("original_frame_version"),
            rs.getString("current_frame_version"),
            rs.getDouble("raw_latitude"),
            rs.getDouble("raw_longitude"),
            rs.getDouble("unified_latitude"),
            rs.getDouble("unified_longitude"),
            rs.getTimestamp("captured_at").toInstant(),
            rs.getString("cluster_id"));

    private final JdbcTemplate jdbcTemplate;

    public ObservationGeoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条观测坐标记录（初始不入簇）。
     */
    public void insert(ObservationGeo geo) {
        jdbcTemplate.update(
                "INSERT INTO observation_geo (observation_id, submission_seq, device_id, "
                        + "original_frame_version, current_frame_version, raw_latitude, raw_longitude, "
                        + "unified_latitude, unified_longitude, captured_at, cluster_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                geo.observationId(), geo.submissionSeq(), geo.deviceId(),
                geo.originalFrameVersion(), geo.currentFrameVersion(),
                geo.rawLatitude(), geo.rawLongitude(),
                geo.unifiedLatitude(), geo.unifiedLongitude(),
                Timestamp.from(geo.capturedAt()), geo.clusterId());
    }

    /**
     * 下一个提交顺序号；调用方必须已持有簇操作全局锁，保证并发下唯一递增。
     */
    public long nextSeq() {
        Long next = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(submission_seq), 0) + 1 FROM observation_geo", Long.class);
        return next == null ? 1 : next;
    }

    /**
     * 按观测记录标识查询坐标信息；不存在时返回空。
     */
    public Optional<ObservationGeo> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM observation_geo WHERE observation_id = ?",
                        GEO_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 按设备标识查询全部观测坐标，按提交顺序排序。
     */
    public List<ObservationGeo> findByDeviceId(String deviceId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM observation_geo WHERE device_id = ? ORDER BY submission_seq ASC",
                GEO_MAPPER, deviceId);
    }

    /**
     * 查询全部观测坐标，按提交顺序排序；用于簇连通分量计算。
     */
    public List<ObservationGeo> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM observation_geo ORDER BY submission_seq ASC",
                GEO_MAPPER);
    }

    /**
     * 查询指定簇的全部成员，按提交顺序排序。
     */
    public List<ObservationGeo> findByClusterId(String clusterId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM observation_geo WHERE cluster_id = ? ORDER BY submission_seq ASC",
                GEO_MAPPER, clusterId);
    }

    /**
     * 更新簇归属；clusterId 为 null 表示移出所有簇。
     */
    public void updateClusterId(String observationId, String clusterId) {
        jdbcTemplate.update(
                "UPDATE observation_geo SET cluster_id = ? WHERE observation_id = ?",
                clusterId, observationId);
    }

    /**
     * 基准重算：更新统一基准坐标与当前基准版本；原始坐标与原基准版本不可改写。
     */
    public void updateUnified(String observationId, String currentFrameVersion,
                              double unifiedLatitude, double unifiedLongitude) {
        jdbcTemplate.update(
                "UPDATE observation_geo SET current_frame_version = ?, unified_latitude = ?, "
                        + "unified_longitude = ? WHERE observation_id = ?",
                currentFrameVersion, unifiedLatitude, unifiedLongitude, observationId);
    }
}
