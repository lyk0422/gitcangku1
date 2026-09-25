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
 * 任务创建时固化发布快照，首次回执完成时固化完成快照；冻结/解冻只影响 RELEASE_FROZEN/PENDING 任务。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<RolloutTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        Long freezeOrderId = rs.getObject("freeze_order_id", Long.class);
        return new RolloutTask(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult),
                rs.getString("from_version"), rs.getString("to_version"),
                rs.getString("receipt_from_version"), rs.getString("receipt_to_version"),
                freezeOrderId, rs.getString("freeze_snapshot"));
    };

    private static final String COLUMNS = "id, release_id, device_id, status, first_result,"
            + " from_version, to_version, receipt_from_version, receipt_to_version,"
            + " freeze_order_id, freeze_snapshot";

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建任务并固化创建时发布快照（来源/目标版本）。
     */
    public long insert(long releaseId, String deviceId, String fromVersion, String toVersion) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollout_task (release_id, device_id, status, from_version, to_version)"
                            + " VALUES (?, ?, 'PENDING', ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            ps.setString(3, fromVersion);
            ps.setString(4, toVersion);
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
     * 首次回执终结任务，并固化完成时发布快照。
     */
    public void complete(long id, ReceiptResult result, String fromVersion, String toVersion) {
        jdbc.update("UPDATE rollout_task SET status = ?, first_result = ?,"
                        + " receipt_from_version = ?, receipt_to_version = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ?", result.name(), result.name(), fromVersion, toVersion, id);
    }

    /**
     * 取消未终结任务（PENDING 与 RELEASE_FROZEN 均为未开始），返回影响行数。
     */
    public int cancelPendingByRelease(long releaseId) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND status IN ('PENDING', 'RELEASE_FROZEN')", releaseId);
    }

    /**
     * 冻结命中任务：仅当仍为 PENDING 时转为 RELEASE_FROZEN 并固化冻结令快照，返回影响行数。
     * 条件更新保证已完成回执不被改写；与回执并发时按任务行锁提交顺序裁决。
     */
    public int freezeIfPending(long taskId, long freezeOrderId, String freezeSnapshot) {
        return jdbc.update("UPDATE rollout_task SET status = 'RELEASE_FROZEN', freeze_order_id = ?,"
                        + " freeze_snapshot = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND status = 'PENDING'",
                freezeOrderId, freezeSnapshot, taskId);
    }

    /**
     * 解冻指定冻结令冻结且仍未开始的任务：回到 PENDING 并清除冻结快照，返回解冻任务ID。
     */
    public List<Long> unfreezeByFreezeOrder(long freezeOrderId) {
        List<Long> taskIds = jdbc.query("SELECT id FROM rollout_task"
                        + " WHERE freeze_order_id = ? AND status = 'RELEASE_FROZEN' ORDER BY id",
                (rs, rowNum) -> rs.getLong("id"), freezeOrderId);
        if (!taskIds.isEmpty()) {
            jdbc.update("UPDATE rollout_task SET status = 'PENDING', freeze_order_id = NULL,"
                    + " freeze_snapshot = NULL, updated_at = CURRENT_TIMESTAMP"
                    + " WHERE freeze_order_id = ? AND status = 'RELEASE_FROZEN'", freezeOrderId);
        }
        return taskIds;
    }

    /**
     * 查询指定冻结令当前冻结中的任务ID（状态仍为 RELEASE_FROZEN）。
     */
    public List<Long> findFrozenTaskIds(long freezeOrderId) {
        return jdbc.query("SELECT id FROM rollout_task"
                        + " WHERE freeze_order_id = ? AND status = 'RELEASE_FROZEN' ORDER BY id",
                (rs, rowNum) -> rs.getLong("id"), freezeOrderId);
    }

    /**
     * 查询所有未开始（PENDING）任务及其发布单型号，供冻结令按范围命中过滤。
     */
    public List<PendingTaskWithModel> findPendingWithModel() {
        return jdbc.query("SELECT t.id, t.release_id, o.model FROM rollout_task t"
                        + " JOIN release_order o ON o.id = t.release_id WHERE t.status = 'PENDING' ORDER BY t.id",
                (rs, rowNum) -> new PendingTaskWithModel(rs.getLong("id"), rs.getLong("release_id"),
                        rs.getString("model")));
    }

    /**
     * 未开始任务及其发布单型号。
     */
    public record PendingTaskWithModel(long taskId, long releaseId, String model) {
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
}
