package com.example.starter.dao;

import com.example.starter.domain.Zone;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 禁飞区表访问：仅支持创建与撤销，撤销保留行并置 active=FALSE。
 */
@Repository
public class ZoneDao {

    private static final RowMapper<Zone> MAPPER = (rs, rowNum) -> new Zone(
            rs.getString("zone_id"),
            rs.getInt("min_x"),
            rs.getInt("min_y"),
            rs.getInt("max_x"),
            rs.getInt("max_y"),
            rs.getBoolean("active"));

    private final JdbcTemplate jdbc;

    public ZoneDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按主键查询禁飞区（含已撤销）。
     */
    public Optional<Zone> findById(String zoneId) {
        List<Zone> zones = jdbc.query("SELECT * FROM zones WHERE zone_id = ?", MAPPER, zoneId);
        return zones.stream().findFirst();
    }

    /**
     * 查询全部有效禁飞区。
     */
    public List<Zone> findActive() {
        return jdbc.query("SELECT * FROM zones WHERE active = TRUE", MAPPER);
    }

    /**
     * 插入新禁飞区；zoneId 重复时由主键约束抛出异常。
     */
    public void insert(Zone zone, Instant createdAt) {
        jdbc.update(
                "INSERT INTO zones (zone_id, min_x, min_y, max_x, max_y, active, created_at, revoked_at)"
                        + " VALUES (?, ?, ?, ?, ?, TRUE, ?, NULL)",
                zone.zoneId(), zone.minX(), zone.minY(), zone.maxX(), zone.maxY(),
                Timestamp.from(createdAt));
    }

    /**
     * 撤销禁飞区：置为无效并记录撤销时间。
     */
    public void revoke(String zoneId, Instant revokedAt) {
        jdbc.update("UPDATE zones SET active = FALSE, revoked_at = ? WHERE zone_id = ?",
                Timestamp.from(revokedAt), zoneId);
    }
}
