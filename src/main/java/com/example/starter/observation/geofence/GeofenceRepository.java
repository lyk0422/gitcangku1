package com.example.starter.observation.geofence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 站点地理围栏持久化：site_geofence 保存全部不可变版本；geofence_site 提供站点级行锁，
 * 串行化同站点并发围栏写入，保证“同一站点任一时刻至多一个生效版本”的不变量。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class GeofenceRepository {

    private static final String VERSION_COLUMNS =
            "fence_id, fence_version, site_id, min_latitude, max_latitude, min_longitude, max_longitude, "
                    + "effective_from_utc, effective_to_utc, operator, request_id";

    private static final RowMapper<FenceVersionRecord> VERSION_MAPPER = (rs, rowNum) -> new FenceVersionRecord(
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

    private final JdbcTemplate jdbcTemplate;

    public GeofenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 登记站点并加行锁（SELECT ... FOR UPDATE），串行化同站点围栏写事务。
     * 站点行不存在时先插入；并发插入由主键串行化，后到者读取已提交行。
     */
    public void lockSite(String siteId) {
        try {
            jdbcTemplate.update("INSERT INTO geofence_site (site_id, created_at) VALUES (?, CURRENT_TIMESTAMP)",
                    siteId);
        } catch (DuplicateKeyException e) {
            // 站点已登记（含并发登记）：忽略，统一走下面的行锁
        }
        jdbcTemplate.query("SELECT site_id FROM geofence_site WHERE site_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString("site_id"), siteId);
    }

    /**
     * 判断围栏标识是否已存在（任意版本）。
     */
    public boolean existsFence(String fenceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM site_geofence WHERE fence_id = ?", Integer.class, fenceId);
        return count != null && count > 0;
    }

    /**
     * 查询围栏的最新版本；不存在时返回空。
     */
    public Optional<FenceVersionRecord> findLatestVersion(String fenceId) {
        return jdbcTemplate.query(
                        "SELECT " + VERSION_COLUMNS + " FROM site_geofence WHERE fence_id = ? "
                                + "ORDER BY fence_version DESC LIMIT 1",
                        VERSION_MAPPER, fenceId)
                .stream().findFirst();
    }

    /**
     * 查询围栏的全部版本，按版本号升序。
     */
    public List<FenceVersionRecord> findVersions(String fenceId) {
        return jdbcTemplate.query(
                "SELECT " + VERSION_COLUMNS + " FROM site_geofence WHERE fence_id = ? ORDER BY fence_version ASC",
                VERSION_MAPPER, fenceId);
    }

    /**
     * 查询站点全部围栏版本，按生效起始时刻、围栏标识、版本号升序（排序稳定）。
     */
    public List<FenceVersionRecord> findBySite(String siteId) {
        return jdbcTemplate.query(
                "SELECT " + VERSION_COLUMNS + " FROM site_geofence WHERE site_id = ? "
                        + "ORDER BY effective_from_utc ASC, fence_id ASC, fence_version ASC",
                VERSION_MAPPER, siteId);
    }

    /**
     * 查询与新区间 [from, to) 重叠的同站点围栏版本（含其他围栏与历史版本）。
     */
    public List<FenceVersionRecord> findOverlapping(String siteId, Instant fromUtc, Instant toUtc) {
        return jdbcTemplate.query(
                "SELECT " + VERSION_COLUMNS + " FROM site_geofence "
                        + "WHERE site_id = ? AND effective_from_utc < ? AND effective_to_utc > ? "
                        + "ORDER BY fence_id ASC, fence_version ASC",
                VERSION_MAPPER, siteId, Timestamp.from(toUtc), Timestamp.from(fromUtc));
    }

    /**
     * 按采集时刻匹配站点生效围栏版本；同站点任一时刻至多一个生效版本（由重叠校验保证）。
     */
    public Optional<FenceVersionRecord> findEffective(String siteId, Instant observedAtUtc) {
        return jdbcTemplate.query(
                        "SELECT " + VERSION_COLUMNS + " FROM site_geofence "
                                + "WHERE site_id = ? AND effective_from_utc <= ? AND effective_to_utc > ? "
                                + "ORDER BY fence_id ASC, fence_version ASC LIMIT 1",
                        VERSION_MAPPER, siteId, Timestamp.from(observedAtUtc), Timestamp.from(observedAtUtc))
                .stream().findFirst();
    }

    /**
     * 追加一个不可变围栏版本。
     */
    public void insertVersion(FenceVersionRecord record) {
        jdbcTemplate.update(
                "INSERT INTO site_geofence (fence_id, fence_version, site_id, min_latitude, max_latitude, "
                        + "min_longitude, max_longitude, effective_from_utc, effective_to_utc, operator, request_id, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.fenceId(), record.fenceVersion(), record.siteId(),
                record.minLatitude(), record.maxLatitude(), record.minLongitude(), record.maxLongitude(),
                Timestamp.from(record.effectiveFromUtc()), Timestamp.from(record.effectiveToUtc()),
                record.operator(), record.requestId());
    }
}
