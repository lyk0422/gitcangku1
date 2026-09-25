package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 区域限流数据访问：进行中任务计数、等待记录与限流历史事件。
 * 进行中计数由 rollout_task 与 device 实时关联统计（PENDING 即已下发未完成），
 * 与拉取、回执、配置修改共用发布单行锁，保证计数与实际下发严格一致。
 */
@Repository
public class RegionThrottleRepository {

    /**
     * 区域等待记录。
     *
     * @param deviceId        设备ID
     * @param lastThrottledAt 最近一次被限流时刻（服务器本地时区）
     */
    public record WaitEntry(String deviceId, LocalDateTime lastThrottledAt) {
    }

    /**
     * 限流历史事件。
     *
     * @param deviceId    设备ID
     * @param throttledAt 限流发生时刻（服务器本地时区）
     */
    public record ThrottleEvent(String deviceId, LocalDateTime throttledAt) {
    }

    private final JdbcTemplate jdbc;

    public RegionThrottleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 统计发布单在指定区域当前进行中（PENDING，已下发未完成）的任务数。
     */
    public int countInFlight(long releaseId, String region) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task t JOIN device d ON d.device_id = t.device_id"
                        + " WHERE t.release_id = ? AND d.region = ? AND t.status = 'PENDING'",
                Integer.class, releaseId, region);
        return count == null ? 0 : count;
    }

    /**
     * 查询发布单在指定区域的等待记录，按限流时刻升序、同时刻按设备标识字典序。
     */
    public List<WaitEntry> findWaiting(long releaseId, String region) {
        return jdbc.query("SELECT device_id, last_throttled_at FROM region_wait_record"
                        + " WHERE release_id = ? AND region = ? ORDER BY last_throttled_at, device_id",
                (rs, rowNum) -> new WaitEntry(rs.getString("device_id"),
                        rs.getTimestamp("last_throttled_at").toLocalDateTime()),
                releaseId, region);
    }

    /**
     * 写入或刷新设备的等待记录：保留最近一次限流时刻。调用方持有发布单行锁，先查后写无竞态。
     */
    public void upsertWaiting(long releaseId, String region, String deviceId) {
        int updated = jdbc.update("UPDATE region_wait_record SET region = ?,"
                        + " last_throttled_at = CURRENT_TIMESTAMP WHERE release_id = ? AND device_id = ?",
                region, releaseId, deviceId);
        if (updated == 0) {
            jdbc.update("INSERT INTO region_wait_record (release_id, region, device_id, last_throttled_at)"
                    + " VALUES (?, ?, ?, CURRENT_TIMESTAMP)", releaseId, region, deviceId);
        }
    }

    /**
     * 设备获得名额后移除等待记录。
     */
    public void deleteWaiting(long releaseId, String deviceId) {
        jdbc.update("DELETE FROM region_wait_record WHERE release_id = ? AND device_id = ?",
                releaseId, deviceId);
    }

    /**
     * 追加一条限流历史事件。
     */
    public void insertThrottleEvent(long releaseId, String region, String deviceId) {
        jdbc.update("INSERT INTO region_throttle_event (release_id, region, device_id, throttled_at)"
                + " VALUES (?, ?, ?, CURRENT_TIMESTAMP)", releaseId, region, deviceId);
    }

    /**
     * 发布单出现过的区域清单（任务、等待记录、限流事件的并集），按区域标识字典序。
     */
    public List<String> listRegions(long releaseId) {
        return jdbc.queryForList(
                "SELECT region FROM ("
                        + " SELECT d.region AS region FROM rollout_task t JOIN device d"
                        + " ON d.device_id = t.device_id WHERE t.release_id = ?"
                        + " UNION SELECT region FROM region_wait_record WHERE release_id = ?"
                        + " UNION SELECT region FROM region_throttle_event WHERE release_id = ?"
                        + " ) regions ORDER BY region",
                String.class, releaseId, releaseId, releaseId);
    }

    /**
     * 查询发布单在指定区域的限流历史，按发生顺序（事件ID升序）。
     */
    public List<ThrottleEvent> listThrottleEvents(long releaseId, String region) {
        return jdbc.query("SELECT device_id, throttled_at FROM region_throttle_event"
                        + " WHERE release_id = ? AND region = ? ORDER BY id",
                (rs, rowNum) -> new ThrottleEvent(rs.getString("device_id"),
                        rs.getTimestamp("throttled_at").toLocalDateTime()),
                releaseId, region);
    }
}
