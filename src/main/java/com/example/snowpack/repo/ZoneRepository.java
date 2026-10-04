package com.example.snowpack.repo;

import com.example.snowpack.repo.Rows.ZoneStateRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 控制区状态行存取；提供行锁以串行化上报与审核。 */
@Repository
public class ZoneRepository {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    private final JdbcTemplate jdbc;

    public ZoneRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 确保区域行存在（默认 OPEN），已存在则忽略。 */
    public void ensureZone(String zoneId, Instant now) {
        try {
            jdbc.update("INSERT INTO zone_state (zone_id, status, reading_version, updated_at) VALUES (?,?,0,?)",
                    zoneId, STATUS_OPEN, Timestamp.from(now));
        } catch (DuplicateKeyException ignored) {
            // 并发建行时保留已有行
        }
    }

    /** 读取并锁定区域行（SELECT ... FOR UPDATE），调用方须处于事务中。 */
    public Optional<ZoneStateRow> lockById(String zoneId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT zone_id, status, reading_version, updated_at FROM zone_state WHERE zone_id = ? FOR UPDATE",
                    (rs, rowNum) -> new ZoneStateRow(rs.getString("zone_id"), rs.getString("status"),
                            rs.getLong("reading_version"), rs.getTimestamp("updated_at").toInstant()),
                    zoneId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<ZoneStateRow> findById(String zoneId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT zone_id, status, reading_version, updated_at FROM zone_state WHERE zone_id = ?",
                    (rs, rowNum) -> new ZoneStateRow(rs.getString("zone_id"), rs.getString("status"),
                            rs.getLong("reading_version"), rs.getTimestamp("updated_at").toInstant()),
                    zoneId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 读数版本号加一并刷新时间，返回新版本号。 */
    public long bumpReadingVersion(String zoneId, Instant now) {
        jdbc.update("UPDATE zone_state SET reading_version = reading_version + 1, updated_at = ? WHERE zone_id = ?",
                Timestamp.from(now), zoneId);
        return findById(zoneId).orElseThrow().readingVersion();
    }

    public void updateStatus(String zoneId, String status, Instant now) {
        jdbc.update("UPDATE zone_state SET status = ?, updated_at = ? WHERE zone_id = ?",
                status, Timestamp.from(now), zoneId);
    }
}