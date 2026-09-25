package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 全局空域版本、禁飞区与高度带数据访问。
 */
@Repository
public class AirspaceRepository {

    private final JdbcTemplate jdbc;

    public AirspaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ZonePo> ZONE_MAPPER = (rs, n) -> new ZonePo(
            rs.getString("zone_id"),
            rs.getInt("x_min"),
            rs.getInt("y_min"),
            rs.getInt("x_max"),
            rs.getInt("y_max"),
            rs.getString("status"),
            rs.getLong("created_version"),
            (Long) rs.getObject("revoked_version"),
            rs.getInt("zone_version"));

    private static final RowMapper<BandPo> BAND_MAPPER = (rs, n) -> new BandPo(
            rs.getString("band_id"),
            rs.getString("zone_id"),
            rs.getInt("lower_altitude"),
            rs.getInt("upper_altitude"),
            rs.getInt("capacity"),
            rs.getLong("created_version"));

    private static final String ZONE_COLUMNS =
            "zone_id, x_min, y_min, x_max, y_max, status, created_version, revoked_version, zone_version";

    private static final String BAND_COLUMNS =
            "band_id, zone_id, lower_altitude, upper_altitude, capacity, created_version";

    /** 读取当前全局空域版本（单行）。 */
    public long getGlobalVersion() {
        Long version = jdbc.queryForObject(
                "SELECT global_version FROM airspace_meta WHERE id = 1", Long.class);
        return version == null ? 0L : version;
    }

    /** 按 zoneId 查询禁飞区，不存在返回 null。 */
    public ZonePo findZone(String zoneId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + ZONE_COLUMNS + " FROM no_fly_zone WHERE zone_id = ?",
                    ZONE_MAPPER, zoneId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 在当前事务内对区域行做真实更新（touch 加一）取得区域级排他锁，并读取区域。
     *
     * <p>高度带配置与占用创建都先加该锁，保证同一区域的高度带集合与占用
     * 判定基于同一已提交状态；更新为不同的值确保数据库不跳过加锁。</p>
     *
     * @return 区域当前状态；区域不存在返回 null（更新 0 行）
     */
    public ZonePo findZoneForUpdate(String zoneId) {
        int locked = jdbc.update(
                "UPDATE no_fly_zone SET touch = touch + 1 WHERE zone_id = ?", zoneId);
        if (locked == 0) {
            return null;
        }
        return findZone(zoneId);
    }

    /** 查询全部有效（ACTIVE）禁飞区。 */
    public List<ZonePo> findActiveZones() {
        return jdbc.query(
                "SELECT " + ZONE_COLUMNS + " FROM no_fly_zone WHERE status = 'ACTIVE' ORDER BY zone_id",
                ZONE_MAPPER);
    }

    /** 创建禁飞区（区域配置版本初始为 1；调用方负责事务与版本递增）。 */
    public void insertZone(ZonePo zone) {
        jdbc.update("INSERT INTO no_fly_zone "
                        + "(zone_id, x_min, y_min, x_max, y_max, status, created_version, "
                        + "revoked_version, zone_version, touch) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                zone.zoneId(), zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax(),
                zone.status(), zone.createdVersion(), zone.revokedVersion(), zone.zoneVersion());
    }

    /** 撤销禁飞区并记录撤销生效版本（调用方负责事务与版本递增）。 */
    public void markRevoked(String zoneId, long revokedVersion) {
        jdbc.update("UPDATE no_fly_zone SET status = 'REVOKED', revoked_version = ? "
                + "WHERE zone_id = ? AND status = 'ACTIVE'", revokedVersion, zoneId);
    }

    /**
     * 条件推进区域配置版本：仅当当前版本等于 expectedVersion 时加一。
     *
     * @return 更新行数；0 表示版本已被其他事务推进（或区域不存在）
     */
    public int compareAndIncrementZoneVersion(String zoneId, int expectedVersion) {
        return jdbc.update(
                "UPDATE no_fly_zone SET zone_version = zone_version + 1 "
                        + "WHERE zone_id = ? AND zone_version = ?",
                zoneId, expectedVersion);
    }

    /** 查询某区域全部高度带（按下限、上限升序）。 */
    public List<BandPo> findBandsByZone(String zoneId) {
        return jdbc.query(
                "SELECT " + BAND_COLUMNS + " FROM altitude_band WHERE zone_id = ? "
                        + "ORDER BY lower_altitude, upper_altitude",
                BAND_MAPPER, zoneId);
    }

    /** 按 bandId 查询高度带，不存在返回 null。 */
    public BandPo findBand(String bandId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + BAND_COLUMNS + " FROM altitude_band WHERE band_id = ?",
                    BAND_MAPPER, bandId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 在当前事务内对高度带行做真实更新（touch 加一）取得带级排他锁，并读取该带。
     *
     * <p>占用创建/取消按 bandId 加锁，使同一高度带的容量计数与占用写入串行，
     * 容量不超卖；更新为不同的值确保数据库不跳过加锁。</p>
     *
     * @return 高度带当前状态；不存在返回 null（更新 0 行）
     */
    public BandPo findBandForUpdate(String bandId) {
        int locked = jdbc.update(
                "UPDATE altitude_band SET touch = touch + 1 WHERE band_id = ?", bandId);
        if (locked == 0) {
            return null;
        }
        return findBand(bandId);
    }

    /** 登记新高度带（调用方负责事务、重叠校验与版本递增）。 */
    public void insertBand(BandPo band) {
        jdbc.update("INSERT INTO altitude_band "
                        + "(band_id, zone_id, lower_altitude, upper_altitude, capacity, "
                        + "created_version, touch) VALUES (?, ?, ?, ?, ?, ?, 0)",
                band.bandId(), band.zoneId(), band.lowerAltitude(), band.upperAltitude(),
                band.capacity(), band.createdVersion());
    }

    /**
     * 上调高度带容量（只允许上调；调用方负责事务与校验）。
     *
     * @return 更新行数；0 表示带不存在
     */
    public int raiseBandCapacity(String bandId, int newCapacity) {
        return jdbc.update(
                "UPDATE altitude_band SET capacity = ? WHERE band_id = ?",
                newCapacity, bandId);
    }

    /**
     * 在当前事务内对协调锁行做真实更新（touched 加一）取得行级排他锁，并读取空域版本。
     *
     * <p>必须更新为不同的值，数据库才不会跳过加锁；该锁在 H2（MVStore）与
     * MySQL InnoDB 下都确定持有至事务提交。审核/占用事务持锁期间，任何禁飞区
     * 创建/撤销/高度带配置事务（同样先更新该行）都无法提交，保证审核读到的版本号与
     * 全部禁飞区、高度带来自同一个已提交状态。</p>
     */
    public long getGlobalVersionForUpdate() {
        jdbc.update("UPDATE coord_lock SET touched = touched + 1 WHERE id = 1");
        return getGlobalVersion();
    }

    /**
     * 原子地将全局空域版本加一并返回新版本。
     * 先更新协调锁行（与审核/占用事务互斥），再推进版本；必须在业务事务内调用，
     * 保证区域变更、版本推进与去重记录原子提交，且不会产生
     * “携带新版本、使用旧区域”的结论。
     */
    public long incrementGlobalVersion() {
        jdbc.update("UPDATE coord_lock SET touched = touched + 1 WHERE id = 1");
        jdbc.update("UPDATE airspace_meta SET global_version = global_version + 1 WHERE id = 1");
        return getGlobalVersion();
    }
}
