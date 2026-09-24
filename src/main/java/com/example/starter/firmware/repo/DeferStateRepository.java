package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.TaskDeferState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 任务顺延累计状态数据访问。主键 (release_id, device_id) 与任务身份一致：
 * 并发顺延由主键保证至多一条，累加使用原子 UPDATE，调用方持有发布单（及设备）行锁。
 */
@Repository
public class DeferStateRepository {

    private static final RowMapper<TaskDeferState> MAPPER = (rs, rowNum) -> new TaskDeferState(
            rs.getLong("release_id"), rs.getString("device_id"), rs.getInt("defer_count"),
            rs.getString("last_deferred_at_utc"));

    private static final String COLUMNS = "release_id, device_id, defer_count, last_deferred_at_utc";

    private final JdbcTemplate jdbc;

    public DeferStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 原子累加一次顺延：不存在则插入计数1，存在则计数加一并刷新最近顺延时刻。
     * 调用方事务内已持有发布单行锁，同任务并发顺延串行提交，不会丢失或重复累加。
     */
    public void increment(long releaseId, String deviceId, String deferredAtUtc) {
        int updated = jdbc.update("UPDATE task_defer_state SET defer_count = defer_count + 1,"
                + " last_deferred_at_utc = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND device_id = ?", deferredAtUtc, releaseId, deviceId);
        if (updated == 0) {
            jdbc.update("INSERT INTO task_defer_state (release_id, device_id, defer_count, last_deferred_at_utc)"
                    + " VALUES (?, ?, 1, ?)", releaseId, deviceId, deferredAtUtc);
        }
    }

    public Optional<TaskDeferState> find(long releaseId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_defer_state"
                        + " WHERE release_id = ? AND device_id = ?",
                MAPPER, releaseId, deviceId).stream().findFirst();
    }

    public List<TaskDeferState> findByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_defer_state WHERE release_id = ?"
                + " ORDER BY device_id", MAPPER, releaseId);
    }
}
