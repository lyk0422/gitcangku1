package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.RegionThrottleEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 区域限流历史事件数据访问。只追加，不修改。
 */
@Repository
public class RegionThrottleEventRepository {

    private static final RowMapper<RegionThrottleEvent> MAPPER = (rs, rowNum) -> new RegionThrottleEvent(
            rs.getLong("id"), rs.getLong("release_id"), rs.getString("region"), rs.getString("device_id"),
            rs.getTimestamp("throttled_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public RegionThrottleEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(long releaseId, String region, String deviceId, LocalDateTime throttledAt) {
        jdbc.update("INSERT INTO region_throttle_event (release_id, region, device_id, throttled_at)"
                        + " VALUES (?, ?, ?, ?)",
                releaseId, region, deviceId, Timestamp.valueOf(throttledAt));
    }

    /**
     * 区域限流历史：按事件ID（即发生顺序）升序。
     */
    public List<RegionThrottleEvent> findByRegion(long releaseId, String region) {
        return jdbc.query("SELECT id, release_id, region, device_id, throttled_at FROM region_throttle_event"
                        + " WHERE release_id = ? AND region = ? ORDER BY id",
                MAPPER, releaseId, region);
    }
}
