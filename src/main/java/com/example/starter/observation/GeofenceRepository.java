package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 地理围栏持久化：geofence 表按 (fence_id, fence_version) 追加不可变版本行；
 * geofence_site_lock 提供同站点围栏写操作的数据库行锁。所有 SQL 使用参数化查询。
 */
@Repository
public class GeofenceRepository {

    private static final RowMapper<GeofenceRecord> FENCE_MAPPER = (rs, rowNum) -> new GeofenceRecord(
            rs.getString("fence_id"),
            rs.getInt("fence_version"),
            rs.getString("site_id"),
            rs.getBigDecimal("min_latitude"),
            rs.getBigDecimal("max_latitude"),
            rs.getBigDecimal("min_longitude"),
            rs.getBigDecimal("max_longitude"),
            rs.getTimestamp("effective_from_utc").toInstant(),
            rs.getTimestamp("effective_to_utc").toInstant(),
            rs.getString("operator"),
            rs.getString("request_id"));

    private static final String FENCE_COLUMNS = "fence_id, fence_version, site_id, min_latitude, max_latitude, "
            + "min_longitude, max_longitude, effective_from_utc, effective_to_utc, operator, request_id";

    private final JdbcTemplate jdbcTemplate;

    public GeofenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一个不可变围栏版本行；主键冲突时抛出重复键异常。
     */
    public void insert(GeofenceRecord record) {
        jdbcTemplate.update(
                "INSERT INTO geofence (fence_id, fence_version, site_id, min_latitude, max_latitude, "
                        + "min_longitude, max_longitude, effective_from_utc, effective_to_utc, operator, "
                        + "request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.fenceId(), record.fenceVersion(), record.siteId(),
                record.minLatitude(), record.maxLatitude(), record.minLongitude(), record.maxLongitude(),
                Timestamp.from(record.effectiveFromUtc()), Timestamp.from(record.effectiveToUtc()),
                record.operator(), record.requestId());
    }

    /**
     * 读取围栏的最新版本；围栏不存在时返回空。
     */
    public Optional<GeofenceRecord> findLatest(String fenceId) {
        return jdbcTemplate.query(
                        "SELECT " + FENCE_COLUMNS + " FROM geofence WHERE fence_id = ? "
                                + "ORDER BY fence_version DESC LIMIT 1",
                        FENCE_MAPPER, fenceId)
                .stream().findFirst();
    }

    /**
     * 读取围栏的指定历史版本；不存在时返回空。
     */
    public Optional<GeofenceRecord> findVersion(String fenceId, int fenceVersion) {
        return jdbcTemplate.query(
                        "SELECT " + FENCE_COLUMNS + " FROM geofence WHERE fence_id = ? AND fence_version = ?",
                        FENCE_MAPPER, fenceId, fenceVersion)
                .stream().findFirst();
    }

    /**
     * 读取站点全部围栏的全部版本（按围栏标识、版本号稳定排序），由服务层归并出当前版本集合。
     */
    public List<GeofenceRecord> findBySite(String siteId) {
        return jdbcTemplate.query(
                "SELECT " + FENCE_COLUMNS + " FROM geofence WHERE site_id = ? "
                        + "ORDER BY fence_id ASC, fence_version ASC",
                FENCE_MAPPER, siteId);
    }

    /**
     * 写入站点锁行（已存在时由主键冲突抛出重复键异常，调用方捕获后继续加锁）。
     */
    public void insertSiteLock(String siteId) {
        jdbcTemplate.update("INSERT INTO geofence_site_lock (site_id) VALUES (?)", siteId);
    }

    /**
     * 对站点锁行加行锁（SELECT ... FOR UPDATE），串行化同站点围栏的并发创建/变更。
     */
    public void lockSiteForUpdate(String siteId) {
        jdbcTemplate.query("SELECT site_id FROM geofence_site_lock WHERE site_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString(1), siteId);
    }
}
