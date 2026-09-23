package com.example.starter.repo;

import com.example.starter.domain.TimeWindow;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 全局空域版本与禁飞区数据访问。
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
            new TimeWindow((Long) rs.getObject("window_start"),
                    (Long) rs.getObject("window_end")));

    private static final String ZONE_COLUMNS =
            "zone_id, x_min, y_min, x_max, y_max, status, created_version, revoked_version, "
                    + "window_start, window_end";

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
                "SELECT " + ZONE_COLUMNS + " FROM no_fly_zone WHERE status = 'ACTIVE' "
                        + "ORDER BY zone_id",
                ZONE_MAPPER);
    }

    /** 创建禁飞区（调用方负责事务与版本递增）。 */
    public void insertZone(ZonePo zone) {
        jdbc.update("INSERT INTO no_fly_zone "
                        + "(zone_id, x_min, y_min, x_max, y_max, status, created_version, "
                        + "revoked_version, window_start, window_end) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                zone.zoneId(), zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax(),
                zone.status(), zone.createdVersion(), zone.revokedVersion(),
                zone.window().startUtcMillis(), zone.window().endUtcMillis());
    }

    /** 撤销禁飞区并记录撤销生效版本（调用方负责事务与版本递增）。 */
    public void markRevoked(String zoneId, long revokedVersion) {
        jdbc.update("UPDATE no_fly_zone SET status = 'REVOKED', revoked_version = ? "
                + "WHERE zone_id = ? AND status = 'ACTIVE'", revokedVersion, zoneId);
    }

    /**
     * 在当前事务内对协调锁行做真实更新（touched 加一）取得行级排他锁，并读取空域版本。
     *
     * <p>必须更新为不同的值，数据库才不会跳过加锁；该锁在 H2（MVStore）与
     * MySQL InnoDB 下都确定持有至事务提交。审核事务持锁期间，任何禁飞区
     * 创建/撤销事务（同样先更新该行）都无法提交，保证审核读到的版本号与
     * 全部禁飞区（含其有效窗口）来自同一个已提交状态。</p>
     */
    public long getGlobalVersionForUpdate() {
        jdbc.update("UPDATE coord_lock SET touched = touched + 1 WHERE id = 1");
        return getGlobalVersion();
    }

    /**
     * 原子地将全局空域版本加一并返回新版本。
     * 先更新协调锁行（与审核事务互斥），再推进版本；必须在业务事务内调用，
     * 保证区域变更、版本推进与去重记录原子提交，且不会产生
     * “携带新版本、使用旧区域/旧窗口”的结论。
     */
    public long incrementGlobalVersion() {
        jdbc.update("UPDATE coord_lock SET touched = touched + 1 WHERE id = 1");
        jdbc.update("UPDATE airspace_meta SET global_version = global_version + 1 WHERE id = 1");
        return getGlobalVersion();
    }
}
