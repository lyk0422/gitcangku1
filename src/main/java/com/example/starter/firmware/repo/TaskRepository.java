package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 投放任务数据访问。同设备同发布单由唯一约束 uk_task_release_device 保证最多一条。
 * 领取时冻结配置版本与迁移前后完整快照；首次 FAILED 回执冻结失败原因。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<RolloutTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        int configVersionBefore = rs.getInt("config_version_before");
        boolean configVersionNull = rs.wasNull();
        return new RolloutTask(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult),
                configVersionNull ? null : configVersionBefore,
                rs.getString("config_before"), rs.getString("config_after"),
                rs.getString("failure_reason"));
    };

    private static final String COLUMNS = "id, release_id, device_id, status, first_result,"
            + " config_version_before, config_before, config_after, failure_reason";

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建任务并冻结配置版本与迁移前后完整快照。
     */
    public long insert(long releaseId, String deviceId, int configVersionBefore,
                       String configBeforeJson, String configAfterJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollout_task (release_id, device_id, status, config_version_before,"
                            + " config_before, config_after) VALUES (?, ?, 'PENDING', ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            ps.setInt(3, configVersionBefore);
            ps.setString(4, configBeforeJson);
            ps.setString(5, configAfterJson);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<RolloutTask> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<RolloutTask> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<RolloutTask> findByReleaseAndDevice(long releaseId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? AND device_id = ?",
                MAPPER, releaseId, deviceId).stream().findFirst();
    }

    /**
     * 首次回执终结任务；FAILED 时同时冻结失败原因（未提供为 null）。
     */
    public void complete(long id, ReceiptResult result, String failureReason) {
        jdbc.update("UPDATE rollout_task SET status = ?, first_result = ?, failure_reason = ?,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                result.name(), result.name(), failureReason, id);
    }

    public int cancelPendingByRelease(long releaseId) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND status = 'PENDING'", releaseId);
    }

    public List<RolloutTask> findByRelease(long releaseId, TaskStatus statusFilter) {
        if (statusFilter == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? ORDER BY id",
                    MAPPER, releaseId);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? AND status = ? ORDER BY id",
                MAPPER, releaseId, statusFilter.name());
    }

    public long countByReleaseAndDevice(long releaseId, String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ?",
                Long.class, releaseId, deviceId);
        return count == null ? 0 : count;
    }

    /**
     * 设备当前进行中（PENDING）任务数；本地配置修改仅在该数为 0 时允许。
     */
    public long countPendingByDevice(String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE device_id = ? AND status = 'PENDING'",
                Long.class, deviceId);
        return count == null ? 0 : count;
    }
}
