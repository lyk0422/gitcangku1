package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 站点地理围栏持久化：geofence 表只插不改，围栏配置创建后不可变。
 * 所有 SQL 使用参数化查询；UTC 时刻以 TIMESTAMP 存储（同一 JVM 生命周期内一致）。
 */
@Repository
public class GeofenceRepository {

    private static final String COLUMNS = "fence_id, site_id, min_lat, max_lat, min_lon, max_lon, "
            + "effective_from_utc, effective_to_utc, operator, created_at_utc";

    private static final RowMapper<Geofence> GEOFENCE_MAPPER = (rs, rowNum) -> new Geofence(
            rs.getString("fence_id"),
            rs.getString("site_id"),
            rs.getBigDecimal("min_lat"),
            rs.getBigDecimal("max_lat"),
            rs.getBigDecimal("min_lon"),
            rs.getBigDecimal("max_lon"),
            rs.getTimestamp("effective_from_utc").toInstant(),
            rs.getTimestamp("effective_to_utc").toInstant(),
            rs.getString("operator"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public GeofenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条不可变围栏配置；fenceId 主键冲突时抛出重复键异常。
     */
    public void insert(Geofence geofence) {
        jdbcTemplate.update(
                "INSERT INTO geofence (fence_id, site_id, min_lat, max_lat, min_lon, max_lon, "
                        + "effective_from_utc, effective_to_utc, operator, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                geofence.fenceId(), geofence.siteId(),
                geofence.minLat(), geofence.maxLat(), geofence.minLon(), geofence.maxLon(),
                Timestamp.from(geofence.effectiveFromUtc()), Timestamp.from(geofence.effectiveToUtc()),
                geofence.operator(), Timestamp.from(geofence.createdAtUtc()));
    }

    /**
     * 按全局唯一 fenceId 查询围栏；不存在时返回空。
     */
    public Optional<Geofence> findByFenceId(String fenceId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM geofence WHERE fence_id = ?",
                        GEOFENCE_MAPPER, fenceId)
                .stream().findFirst();
    }

    /**
     * 按站点查询全部围栏，按生效起点与围栏标识稳定排序。
     */
    public List<Geofence> findBySiteId(String siteId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM geofence WHERE site_id = ? "
                        + "ORDER BY effective_from_utc ASC, fence_id ASC",
                GEOFENCE_MAPPER, siteId);
    }

    /**
     * 查询同站点与新区间 [from, to) 重叠（左闭右开语义）的既有围栏。
     */
    public List<Geofence> findOverlapping(String siteId, Instant fromUtc, Instant toUtc) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM geofence WHERE site_id = ? "
                        + "AND effective_from_utc < ? AND effective_to_utc > ? "
                        + "ORDER BY effective_from_utc ASC, fence_id ASC",
                GEOFENCE_MAPPER, siteId, Timestamp.from(toUtc), Timestamp.from(fromUtc));
    }

    /**
     * 查询站点在指定 UTC 时刻生效的围栏（左闭右开）；同站点生效区间不重叠，至多一条。
     */
    public Optional<Geofence> findEffectiveAt(String siteId, Instant atUtc) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM geofence WHERE site_id = ? "
                                + "AND effective_from_utc <= ? AND effective_to_utc > ? "
                                + "ORDER BY effective_from_utc ASC, fence_id ASC",
                        GEOFENCE_MAPPER, siteId, Timestamp.from(atUtc), Timestamp.from(atUtc))
                .stream().findFirst();
    }
}
