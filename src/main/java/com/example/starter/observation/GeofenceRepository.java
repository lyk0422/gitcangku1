package com.example.starter.observation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 地理围栏持久化：维护 geofence_fence 当前指针与 geofence_version 不可变配置历史。
 * 围栏写操作先经 geofence_site 锁行按站点串行化，再校验重叠并追加版本。
 * 所有 SQL 使用参数化查询；定点数读出后统一去尾零，保证数值表示稳定。
 */
@Repository
public class GeofenceRepository {

    private static final String VERSION_COLUMNS =
            "v.fence_id, f.site_id, v.version, v.min_lat, v.max_lat, v.min_lon, v.max_lon, "
                    + "v.effective_from_utc, v.effective_to_utc, v.operator, v.created_at_utc";

    private static final String VERSION_JOIN =
            "FROM geofence_version v JOIN geofence_fence f ON f.fence_id = v.fence_id";

    private static final RowMapper<GeofenceFence> FENCE_MAPPER = (rs, rowNum) -> new GeofenceFence(
            rs.getString("fence_id"),
            rs.getString("site_id"),
            rs.getInt("current_version"));

    private static final RowMapper<GeofenceVersionRecord> VERSION_MAPPER = (rs, rowNum) ->
            new GeofenceVersionRecord(
                    rs.getString("fence_id"),
                    rs.getString("site_id"),
                    rs.getInt("version"),
                    Decimals.normalize(rs.getBigDecimal("min_lat")),
                    Decimals.normalize(rs.getBigDecimal("max_lat")),
                    Decimals.normalize(rs.getBigDecimal("min_lon")),
                    Decimals.normalize(rs.getBigDecimal("max_lon")),
                    rs.getTimestamp("effective_from_utc").toInstant(),
                    rs.getTimestamp("effective_to_utc").toInstant(),
                    rs.getString("operator"),
                    rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public GeofenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按站点获取围栏写锁：确保锁行存在后 SELECT ... FOR UPDATE，
     * 串行化同站点的围栏创建/改版，使重叠校验与写入不会被并发双双通过。
     */
    public void lockSiteForUpdate(String siteId) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO geofence_site (site_id, created_at) VALUES (?, CURRENT_TIMESTAMP)",
                    siteId);
        } catch (DuplicateKeyException e) {
            // 锁行已存在：继续加锁即可
        }
        jdbcTemplate.query(
                "SELECT site_id FROM geofence_site WHERE site_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString("site_id"), siteId);
    }

    /**
     * 读取围栏当前指针（不加锁）。
     */
    public Optional<GeofenceFence> findFence(String fenceId) {
        return jdbcTemplate.query(
                        "SELECT fence_id, site_id, current_version FROM geofence_fence WHERE fence_id = ?",
                        FENCE_MAPPER, fenceId)
                .stream().findFirst();
    }

    /**
     * 读取围栏当前指针并加行锁，用于改版事务内串行化同一围栏的并发改版。
     */
    public Optional<GeofenceFence> findFenceForUpdate(String fenceId) {
        return jdbcTemplate.query(
                        "SELECT fence_id, site_id, current_version FROM geofence_fence "
                                + "WHERE fence_id = ? FOR UPDATE",
                        FENCE_MAPPER, fenceId)
                .stream().findFirst();
    }

    /**
     * 插入围栏当前指针（初始版本 1）；fenceId 已存在时抛出重复键异常。
     */
    public void insertFence(String fenceId, String siteId) {
        jdbcTemplate.update(
                "INSERT INTO geofence_fence (fence_id, site_id, current_version, created_at) "
                        + "VALUES (?, ?, 1, CURRENT_TIMESTAMP)",
                fenceId, siteId);
    }

    /**
     * 将围栏当前指针推进到新版本。
     */
    public void updateCurrentVersion(String fenceId, int newVersion) {
        jdbcTemplate.update(
                "UPDATE geofence_fence SET current_version = ? WHERE fence_id = ?",
                newVersion, fenceId);
    }

    /**
     * 追加一条不可变配置版本，历史永不更新或删除。
     */
    public void insertVersion(GeofenceVersionRecord record) {
        jdbcTemplate.update(
                "INSERT INTO geofence_version (fence_id, version, min_lat, max_lat, min_lon, max_lon, "
                        + "effective_from_utc, effective_to_utc, operator, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.fenceId(), record.version(),
                record.minLat(), record.maxLat(), record.minLon(), record.maxLon(),
                Timestamp.from(record.effectiveFromUtc()), Timestamp.from(record.effectiveToUtc()),
                record.operator(), Timestamp.from(record.createdAtUtc()));
    }

    /**
     * 读取围栏当前生效配置版本；围栏不存在时返回空。
     */
    public Optional<GeofenceVersionRecord> findCurrentVersion(String fenceId) {
        return jdbcTemplate.query(
                        "SELECT " + VERSION_COLUMNS + " " + VERSION_JOIN
                                + " WHERE v.fence_id = ? AND v.version = f.current_version",
                        VERSION_MAPPER, fenceId)
                .stream().findFirst();
    }

    /**
     * 读取围栏全部配置版本，按版本号升序（稳定排序）。
     */
    public List<GeofenceVersionRecord> findVersions(String fenceId) {
        return jdbcTemplate.query(
                "SELECT " + VERSION_COLUMNS + " " + VERSION_JOIN
                        + " WHERE v.fence_id = ? ORDER BY v.version ASC",
                VERSION_MAPPER, fenceId);
    }

    /**
     * 读取站点全部围栏的当前生效配置版本，按 fenceId 升序（稳定排序）。
     */
    public List<GeofenceVersionRecord> findCurrentVersionsBySite(String siteId) {
        return jdbcTemplate.query(
                "SELECT " + VERSION_COLUMNS + " " + VERSION_JOIN
                        + " WHERE f.site_id = ? AND v.version = f.current_version"
                        + " ORDER BY v.fence_id ASC",
                VERSION_MAPPER, siteId);
    }

    /**
     * 查找同站点与新配置重叠的当前生效围栏版本：矩形有正面积交集且生效区间（左闭右开）相交。
     * 仅共边/共点或区间端点相接不算重叠。excludeFenceId 用于改版时排除自身。
     */
    public List<GeofenceVersionRecord> findOverlapping(String siteId, String excludeFenceId,
                                                       BigDecimal minLat, BigDecimal maxLat,
                                                       BigDecimal minLon, BigDecimal maxLon,
                                                       Instant effectiveFromUtc, Instant effectiveToUtc) {
        return jdbcTemplate.query(
                "SELECT " + VERSION_COLUMNS + " " + VERSION_JOIN
                        + " WHERE f.site_id = ? AND f.fence_id <> ? AND v.version = f.current_version"
                        + " AND v.min_lat < ? AND v.max_lat > ?"
                        + " AND v.min_lon < ? AND v.max_lon > ?"
                        + " AND v.effective_from_utc < ? AND v.effective_to_utc > ?"
                        + " ORDER BY v.fence_id ASC",
                VERSION_MAPPER,
                siteId, excludeFenceId,
                maxLat, minLat, maxLon, minLon,
                Timestamp.from(effectiveToUtc), Timestamp.from(effectiveFromUtc));
    }

    /**
     * 按采集时刻与坐标匹配站点当前生效围栏：生效区间左闭右开，矩形边界含端点。
     * 多个匹配时按 fenceId 升序取第一个，保证结果确定。
     */
    public Optional<GeofenceVersionRecord> findMatching(String siteId, Instant capturedAtUtc,
                                                        BigDecimal latitude, BigDecimal longitude) {
        return jdbcTemplate.query(
                        "SELECT " + VERSION_COLUMNS + " " + VERSION_JOIN
                                + " WHERE f.site_id = ? AND v.version = f.current_version"
                                + " AND v.effective_from_utc <= ? AND v.effective_to_utc > ?"
                                + " AND v.min_lat <= ? AND v.max_lat >= ?"
                                + " AND v.min_lon <= ? AND v.max_lon >= ?"
                                + " ORDER BY v.fence_id ASC",
                        VERSION_MAPPER,
                        siteId,
                        Timestamp.from(capturedAtUtc), Timestamp.from(capturedAtUtc),
                        latitude, latitude, longitude, longitude)
                .stream().findFirst();
    }
}
