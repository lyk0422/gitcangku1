package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.RegionWaitRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 区域限流等待记录数据访问。同设备同发布单最多一条，限流时刷新为最近一次限流时刻。
 */
@Repository
public class RegionWaitRepository {

    private static final RowMapper<RegionWaitRecord> MAPPER = (rs, rowNum) -> new RegionWaitRecord(
            rs.getLong("release_id"), rs.getString("device_id"), rs.getString("region"),
            rs.getTimestamp("waited_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public RegionWaitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入或刷新等待记录：保留最近一次限流时刻。
     */
    public void upsert(long releaseId, String deviceId, String region, LocalDateTime waitedAt) {
        int updated = jdbc.update("UPDATE region_wait_record SET region = ?, waited_at = ?"
                        + " WHERE release_id = ? AND device_id = ?",
                region, Timestamp.valueOf(waitedAt), releaseId, deviceId);
        if (updated == 0) {
            jdbc.update("INSERT INTO region_wait_record (release_id, device_id, region, waited_at)"
                            + " VALUES (?, ?, ?, ?)",
                    releaseId, deviceId, region, Timestamp.valueOf(waitedAt));
        }
    }

    public Optional<RegionWaitRecord> find(long releaseId, String deviceId) {
        return jdbc.query("SELECT release_id, device_id, region, waited_at FROM region_wait_record"
                        + " WHERE release_id = ? AND device_id = ?",
                MAPPER, releaseId, deviceId).stream().findFirst();
    }

    /**
     * 统计同区域内排队早于指定位置的设备数（等待时刻更早，或同刻设备ID字典序更小）。
     */
    public long countAhead(long releaseId, String region, LocalDateTime waitedAt, String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM region_wait_record WHERE release_id = ? AND region = ?"
                        + " AND (waited_at < ? OR (waited_at = ? AND device_id < ?))",
                Long.class, releaseId, region, Timestamp.valueOf(waitedAt), Timestamp.valueOf(waitedAt),
                deviceId);
        return count == null ? 0 : count;
    }

    public void delete(long releaseId, String deviceId) {
        jdbc.update("DELETE FROM region_wait_record WHERE release_id = ? AND device_id = ?",
                releaseId, deviceId);
    }

    /**
     * 区域等待清单：按等待时刻升序、同刻按设备ID字典序。
     */
    public List<RegionWaitRecord> findByRegion(long releaseId, String region) {
        return jdbc.query("SELECT release_id, device_id, region, waited_at FROM region_wait_record"
                        + " WHERE release_id = ? AND region = ? ORDER BY waited_at, device_id",
                MAPPER, releaseId, region);
    }
}
