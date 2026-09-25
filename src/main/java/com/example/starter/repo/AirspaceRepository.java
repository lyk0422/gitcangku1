package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 全局空域版本、禁飞区与区域高度带数据访问。
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
            rs.getInt("config_version"));

    private static final RowMapper<BandPo> BAND_MAPPER = (rs, n) -> new BandPo(
            rs.getString("zone_id"),
            rs.getString("band_id"),
            rs.getInt("lower_m"),
            rs.getInt("upper_m"),
            rs.getInt("capacity"));

    private static final String ZONE_COLUMNS =
            "zone_id, x_min, y_min, x_max, y_max, status, created_version, revoked_version, config_version";

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

    /** 查询全部有效（ACTIVE）禁飞区。 */
    public List<ZonePo> findActiveZones() {
        return jdbc.query(
                "SELECT " + ZONE_COLUMNS + " FROM no_fly_zone WHERE status = 'ACTIVE' ORDER BY zone_id",
                ZONE_MAPPER);
    }

    /** 创建禁飞区（高度带配置版本初始为 1；调用方负责事务与版本递增）。 */
    public void insertZone(ZonePo zone) {
        jdbc.update("INSERT INTO no_fly_zone "
                        + "(zone_id, x_min, y_min, x_max, y_max, status, created_version, revoked_version, config_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
                zone.zoneId(), zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax(),
                zone.status(), zone.createdVersion(), zone.revokedVersion());
    }

    /** 撤销禁飞区并记录撤销生效版本（调用方负责事务与版本递增）。 */
    public void markRevoked(String zoneId, long revokedVersion) {
        jdbc.update("UPDATE no_fly_zone SET status = 'REVOKED', revoked_version = ? "
                + "WHERE zone_id = ? AND status = 'ACTIVE'", revokedVersion, zoneId);
    }

    /**
     * 条件推进高度带配置版本：仅当当前配置版本等于 expectedVersion 时加一。
     * 该更新同时取得区域行的写锁，与并发的高度带修改互斥；
     * 不推进全局空域版本（高度带修改不影响已有审核的 STALE 判定）。
     *
     * @return 更新行数；0 表示区域不存在或配置版本不匹配
     */
    public int compareAndIncrementConfigVersion(String zoneId, int expectedVersion) {
        return jdbc.update(
                "UPDATE no_fly_zone SET config_version = config_version + 1 "
                        + "WHERE zone_id = ? AND config_version = ?",
                zoneId, expectedVersion);
    }

    /** 查询某区域全部高度带（按 bandId 排序）。 */
    public List<BandPo> findBands(String zoneId) {
        return jdbc.query(
                "SELECT zone_id, band_id, lower_m, upper_m, capacity "
                        + "FROM zone_altitude_band WHERE zone_id = ? ORDER BY band_id",
                BAND_MAPPER, zoneId);
    }

    /** 查询全部高度带（审核时按区域分组使用）。 */
    public List<BandPo> findAllBands() {
        return jdbc.query(
                "SELECT zone_id, band_id, lower_m, upper_m, capacity "
                        + "FROM zone_altitude_band ORDER BY zone_id, band_id",
                BAND_MAPPER);
    }

    /** 按区域与高度带标识查询，不存在返回 null。 */
    public BandPo findBand(String zoneId, String bandId) {
        try {
            return jdbc.queryForObject(
                    "SELECT zone_id, band_id, lower_m, upper_m, capacity "
                            + "FROM zone_altitude_band WHERE zone_id = ? AND band_id = ?",
                    BAND_MAPPER, zoneId, bandId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 新增高度带（调用方负责事务与重叠校验）。 */
    public void insertBand(BandPo band, long createdAt) {
        jdbc.update("INSERT INTO zone_altitude_band "
                        + "(zone_id, band_id, lower_m, upper_m, capacity, touch, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 0, ?)",
                band.zoneId(), band.bandId(), band.lowerM(), band.upperM(), band.capacity(),
                createdAt);
    }

    /**
     * 条件上调高度带容量：仅当新容量大于当前容量时生效。
     *
     * @return 更新行数；0 表示高度带不存在或新容量未上调
     */
    public int increaseCapacity(String zoneId, String bandId, int newCapacity) {
        return jdbc.update(
                "UPDATE zone_altitude_band SET capacity = ? "
                        + "WHERE zone_id = ? AND band_id = ? AND capacity < ?",
                newCapacity, zoneId, bandId, newCapacity);
    }

    /**
     * 在当前事务内对高度带行做真实更新（touch 加一）取得行级排他锁，
     * 串行化同一高度带上的占用创建事务，保证容量计数与插入之间不被并发占用插入，
     * 容量不得超卖。
     *
     * @return 更新行数；0 表示高度带不存在
     */
    public int lockBandForUpdate(String zoneId, String bandId) {
        return jdbc.update(
                "UPDATE zone_altitude_band SET touch = touch + 1 "
                        + "WHERE zone_id = ? AND band_id = ?",
                zoneId, bandId);
    }

    /**
     * 在当前事务内对协调锁行做真实更新（touched 加一）取得行级排他锁，并读取空域版本。
     *
     * <p>必须更新为不同的值，数据库才不会跳过加锁；该锁在 H2（MVStore）与
     * MySQL InnoDB 下都确定持有至事务提交。审核事务持锁期间，任何禁飞区
     * 创建/撤销事务（同样先更新该行）都无法提交，保证审核读到的版本号与
     * 全部禁飞区来自同一个已提交状态。</p>
     */
    public long getGlobalVersionForUpdate() {
        jdbc.update("UPDATE coord_lock SET touched = touched + 1 WHERE id = 1");
        return getGlobalVersion();
    }

    /**
     * 原子地将全局空域版本加一并返回新版本。
     * 先更新协调锁行（与审核事务互斥），再推进版本；必须在业务事务内调用，
     * 保证区域变更、版本推进与去重记录原子提交，且不会产生
     * “携带新版本、使用旧区域”的结论。
     */
    public long incrementGlobalVersion() {
        jdbc.update("UPDATE coord_lock SET touched = touched + 1 WHERE id = 1");
        jdbc.update("UPDATE airspace_meta SET global_version = global_version + 1 WHERE id = 1");
        return getGlobalVersion();
    }
}
