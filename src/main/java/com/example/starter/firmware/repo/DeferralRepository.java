package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.DeferralRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 设备拉取顺延统计数据访问。唯一约束 uk_deferral_release_device 保证同设备同发布单最多一条；
 * 计数递增在持有发布单行锁的事务内执行，不丢失、不重复累加。
 */
@Repository
public class DeferralRepository {

    private static final RowMapper<DeferralRecord> MAPPER = (rs, rowNum) -> new DeferralRecord(
            rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
            rs.getInt("defer_count"), rs.getString("last_deferred_at_utc"));

    private static final String COLUMNS = "id, release_id, device_id, defer_count, last_deferred_at_utc";

    private final JdbcTemplate jdbc;

    public DeferralRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 顺延计数加一并刷新最近顺延时刻；无记录时插入。调用方必须已持有发布单行锁。
     */
    public void increment(long releaseId, String deviceId, String deferredAtUtc) {
        int updated = jdbc.update("UPDATE task_deferral SET defer_count = defer_count + 1,"
                        + " last_deferred_at_utc = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE release_id = ? AND device_id = ?",
                deferredAtUtc, releaseId, deviceId);
        if (updated > 0) {
            return;
        }
        try {
            jdbc.update("INSERT INTO task_deferral (release_id, device_id, defer_count, last_deferred_at_utc)"
                    + " VALUES (?, ?, 1, ?)", releaseId, deviceId, deferredAtUtc);
        } catch (DuplicateKeyException e) {
            // 并发下其他事务已插入：退化为递增，保证不丢失
            jdbc.update("UPDATE task_deferral SET defer_count = defer_count + 1,"
                            + " last_deferred_at_utc = ?, updated_at = CURRENT_TIMESTAMP"
                            + " WHERE release_id = ? AND device_id = ?",
                    deferredAtUtc, releaseId, deviceId);
        }
    }

    public Optional<DeferralRecord> findByReleaseAndDevice(long releaseId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_deferral WHERE release_id = ? AND device_id = ?",
                MAPPER, releaseId, deviceId).stream().findFirst();
    }

    /**
     * 发布单下全部顺延记录，按设备ID稳定排序（只读）。
     */
    public List<DeferralRecord> findByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_deferral WHERE release_id = ?"
                + " ORDER BY device_id", MAPPER, releaseId);
    }
}
