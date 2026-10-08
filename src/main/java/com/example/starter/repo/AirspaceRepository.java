package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.Polygon;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局空域版本与禁飞区数据访问。
 * 支持轴对齐矩形（RECTANGLE）与带孔洞多边形（POLYGON）两类区域。
 */
@Repository
public class AirspaceRepository {

    private final JdbcTemplate jdbc;

    public AirspaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String ZONE_COLUMNS =
            "zone_id, zone_type, x_min, y_min, x_max, y_max, "
                    + "status, created_version, revoked_version";

    private final RowMapper<ZonePo> zoneMapper = (rs, n) -> {
        String type = rs.getString("zone_type");
        String zoneId = rs.getString("zone_id");
        String status = rs.getString("status");
        long createdVersion = rs.getLong("created_version");
        Long revokedVersion = (Long) rs.getObject("revoked_version");
        if ("POLYGON".equals(type)) {
            return ZonePo.polygon(zoneId, loadPolygon(zoneId),
                    status, createdVersion, revokedVersion);
        }
        return ZonePo.rectangle(zoneId, rs.getInt("x_min"), rs.getInt("y_min"),
                rs.getInt("x_max"), rs.getInt("y_max"),
                status, createdVersion, revokedVersion);
    };

    /** 读取某多边形区域冻结的外环与孔洞环顶点。 */
    private Polygon loadPolygon(String zoneId) {
        // key: ringKind|ringIndex
        Map<String, List<Point>> rings = new LinkedHashMap<>();
        jdbc.query("SELECT ring_kind, ring_index, seq, x, y FROM zone_ring "
                + "WHERE zone_id = ? ORDER BY ring_kind, ring_index, seq",
                (rs) -> {
                    String key = rs.getString("ring_kind") + "|" + rs.getInt("ring_index");
                    rings.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new Point(rs.getInt("x"), rs.getInt("y")));
                }, zoneId);
        List<Point> outer = List.of();
        List<List<Point>> holes = new ArrayList<>();
        for (Map.Entry<String, List<Point>> entry : rings.entrySet()) {
            if (entry.getKey().startsWith("OUTER")) {
                outer = entry.getValue();
            } else {
                holes.add(entry.getValue());
            }
        }
        return new Polygon(outer, holes);
    }

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
                    zoneMapper, zoneId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 查询全部有效（ACTIVE）禁飞区（含矩形与多边形）。 */
    public List<ZonePo> findActiveZones() {
        return jdbc.query(
                "SELECT " + ZONE_COLUMNS + " FROM no_fly_zone "
                        + "WHERE status = 'ACTIVE' ORDER BY zone_id",
                zoneMapper);
    }

    /** 创建矩形禁飞区（调用方负责事务与版本递增）。 */
    public void insertZone(ZonePo zone) {
        jdbc.update("INSERT INTO no_fly_zone "
                        + "(zone_id, zone_type, x_min, y_min, x_max, y_max, "
                        + "status, created_version, revoked_version) "
                        + "VALUES (?, 'RECTANGLE', ?, ?, ?, ?, ?, ?, ?)",
                zone.zoneId(), zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax(),
                zone.status(), zone.createdVersion(), zone.revokedVersion());
    }

    /** 创建带孔洞多边形禁飞区并冻结全部环顶点（调用方负责事务与版本递增）。 */
    public void insertPolygonZone(ZonePo zone) {
        jdbc.update("INSERT INTO no_fly_zone "
                        + "(zone_id, zone_type, x_min, y_min, x_max, y_max, "
                        + "status, created_version, revoked_version) "
                        + "VALUES (?, 'POLYGON', NULL, NULL, NULL, NULL, ?, ?, ?)",
                zone.zoneId(), zone.status(), zone.createdVersion(), zone.revokedVersion());
        insertRing(zone.zoneId(), "OUTER", 0, zone.polygon().outerRing());
        for (int i = 0; i < zone.polygon().holes().size(); i++) {
            insertRing(zone.zoneId(), "HOLE", i, zone.polygon().holes().get(i));
        }
    }

    private void insertRing(String zoneId, String ringKind, int ringIndex, List<Point> ring) {
        for (int seq = 0; seq < ring.size(); seq++) {
            Point p = ring.get(seq);
            jdbc.update("INSERT INTO zone_ring "
                            + "(zone_id, ring_kind, ring_index, seq, x, y) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    zoneId, ringKind, ringIndex, seq, p.x(), p.y());
        }
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
