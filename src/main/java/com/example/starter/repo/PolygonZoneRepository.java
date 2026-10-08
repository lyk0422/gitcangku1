package com.example.starter.repo;

import com.example.starter.domain.Point;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 带孔洞多边形禁飞区与共享区域标识的数据访问。
 * 环顶点按 (ring_no, seq) 冻结写入，读取时还原为外环与孔洞列表。
 */
@Repository
public class PolygonZoneRepository {

    /** 标识注册表中的区域形状常量。 */
    public static final String KIND_RECTANGLE = "RECTANGLE";
    public static final String KIND_POLYGON = "POLYGON";

    private final JdbcTemplate jdbc;

    public PolygonZoneRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 在共享标识注册表登记 zoneId（矩形与多边形创建事务都必须调用）。
     * 并发同号时由主键唯一约束抛出 DuplicateKeyException，调用方事务回滚。
     */
    public void registerIdentity(String zoneId, String kind) {
        jdbc.update("INSERT INTO zone_identity (zone_id, kind) VALUES (?, ?)", zoneId, kind);
    }

    /** 查询 zoneId 已登记的形状（RECTANGLE/POLYGON），未登记返回 null。 */
    public String findIdentityKind(String zoneId) {
        try {
            return jdbc.queryForObject(
                    "SELECT kind FROM zone_identity WHERE zone_id = ?", String.class, zoneId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 创建多边形禁飞区主记录（调用方负责事务、版本递增与标识登记）。 */
    public void insertPolygonZone(PolygonZonePo po) {
        jdbc.update("INSERT INTO polygon_zone "
                        + "(zone_id, status, created_version, revoked_version) "
                        + "VALUES (?, ?, ?, ?)",
                po.zoneId(), po.status(), po.createdVersion(), po.revokedVersion());
        int ringNo = 0;
        insertRing(po.zoneId(), ringNo, po.outer());
        for (List<Point> hole : po.holes()) {
            ringNo++;
            insertRing(po.zoneId(), ringNo, hole);
        }
    }

    private void insertRing(String zoneId, int ringNo, List<Point> ring) {
        int seq = 0;
        for (Point p : ring) {
            jdbc.update("INSERT INTO polygon_zone_ring (zone_id, ring_no, seq, x, y) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    zoneId, ringNo, seq++, p.x(), p.y());
        }
    }

    /** 查询全部有效（ACTIVE）多边形禁飞区（含冻结环），按 zoneId 排序。 */
    public List<PolygonZonePo> findActivePolygonZones() {
        List<ZoneHeader> headers = jdbc.query(
                "SELECT zone_id, status, created_version, revoked_version "
                        + "FROM polygon_zone WHERE status = 'ACTIVE' ORDER BY zone_id",
                (rs, n) -> new ZoneHeader(rs.getString("zone_id"),
                        rs.getString("status"), rs.getLong("created_version"),
                        (Long) rs.getObject("revoked_version")));
        List<PolygonZonePo> result = new ArrayList<>();
        for (ZoneHeader header : headers) {
            PolygonZonePo po = loadPolygonZone(
                    "SELECT pz.zone_id, pz.status, pz.created_version, pz.revoked_version, "
                            + "r.ring_no, r.seq, r.x, r.y "
                            + "FROM polygon_zone pz JOIN polygon_zone_ring r "
                            + "ON r.zone_id = pz.zone_id WHERE pz.zone_id = ? "
                            + "ORDER BY r.ring_no, r.seq",
                    header.zoneId());
            result.add(po);
        }
        return result;
    }

    private PolygonZonePo loadPolygonZone(String sql, String zoneId) {
        // ring_no -> (seq -> point)，用 LinkedHashMap 保持读取顺序
        Map<Integer, List<Point>> rings = new LinkedHashMap<>();
        return jdbc.query(sql, rs -> {
            String zid = null;
            String status = null;
            long createdVersion = 0L;
            Long revokedVersion = null;
            while (rs.next()) {
                zid = rs.getString("zone_id");
                status = rs.getString("status");
                createdVersion = rs.getLong("created_version");
                revokedVersion = (Long) rs.getObject("revoked_version");
                rings.computeIfAbsent(rs.getInt("ring_no"), k -> new ArrayList<>())
                        .add(new Point(rs.getInt("x"), rs.getInt("y")));
            }
            if (zid == null) {
                return null;
            }
            List<Point> outer = rings.getOrDefault(0, List.of());
            List<List<Point>> holes = new ArrayList<>();
            for (Map.Entry<Integer, List<Point>> entry : rings.entrySet()) {
                if (entry.getKey() > 0) {
                    holes.add(entry.getValue());
                }
            }
            return new PolygonZonePo(zid, status, createdVersion, revokedVersion,
                    List.copyOf(outer), List.copyOf(holes));
        }, zoneId);
    }

    /** 撤销多边形禁飞区并记录撤销生效版本（调用方负责事务与版本递增）。 */
    public int markRevoked(String zoneId, long revokedVersion) {
        return jdbc.update("UPDATE polygon_zone SET status = 'REVOKED', revoked_version = ? "
                + "WHERE zone_id = ? AND status = 'ACTIVE'", revokedVersion, zoneId);
    }

    private record ZoneHeader(String zoneId, String status, long createdVersion,
                              Long revokedVersion) {
    }
}
