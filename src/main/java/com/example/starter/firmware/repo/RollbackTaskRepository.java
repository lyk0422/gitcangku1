package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.RollbackTask;
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
 * 回退波次任务数据访问。同（计划,跳,轮,设备）由唯一约束保证至多一条；receipt_key 全局唯一。
 */
@Repository
public class RollbackTaskRepository {

    private static final RowMapper<RollbackTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        return new RollbackTask(rs.getLong("id"), rs.getLong("plan_id"), rs.getInt("hop_index"),
                rs.getInt("round"), rs.getString("device_id"), rs.getString("expected_version"),
                rs.getString("to_version"), rs.getLong("source_release_id"),
                TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null
                        : com.example.starter.firmware.domain.ReceiptResult.valueOf(firstResult),
                rs.getString("receipt_key"));
    };

    private static final String COLUMNS = "id, plan_id, hop_index, round, device_id, expected_version,"
            + " to_version, source_release_id, status, first_result, receipt_key";

    private final JdbcTemplate jdbc;

    public RollbackTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long planId, int hopIndex, int round, String deviceId, String expectedVersion,
                       String toVersion, long sourceReleaseId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollback_task (plan_id, hop_index, round, device_id, expected_version,"
                            + " to_version, source_release_id, status)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')",
                    new String[]{"id"});
            ps.setLong(1, planId);
            ps.setInt(2, hopIndex);
            ps.setInt(3, round);
            ps.setString(4, deviceId);
            ps.setString(5, expectedVersion);
            ps.setString(6, toVersion);
            ps.setLong(7, sourceReleaseId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<RollbackTask> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_task WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<RollbackTask> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_task WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 终结任务并写入回执键，仅当仍为 PENDING 时生效，返回影响行数。
     */
    public int complete(long id, com.example.starter.firmware.domain.ReceiptResult result, String receiptKey) {
        return jdbc.update("UPDATE rollback_task SET status = ?, first_result = ?, receipt_key = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'PENDING'",
                result.name(), result.name(), receiptKey, id);
    }

    /**
     * 计划取消时将所有未终结任务转 CANCELLED。
     */
    public int cancelPendingByPlan(long planId) {
        return jdbc.update("UPDATE rollback_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE plan_id = ? AND status = 'PENDING'", planId);
    }

    /**
     * 人工恢复时作废旧轮次仍 PENDING 的任务（已 FAILED 的保留作为历史）。
     */
    public int cancelPendingByPlanHopRound(long planId, int hopIndex, int round) {
        return jdbc.update("UPDATE rollback_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE plan_id = ? AND hop_index = ? AND round = ? AND status = 'PENDING'",
                planId, hopIndex, round);
    }

    /**
     * 统计某计划某跳内所有轮次中是否已存在 SUCCESS 任务，用于判断设备是否已通过该跳。
     */
    public boolean existsSuccess(long planId, int hopIndex, String deviceId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM rollback_task WHERE plan_id = ?"
                + " AND hop_index = ? AND device_id = ? AND status = 'SUCCESS'",
                Long.class, planId, hopIndex, deviceId);
        return count != null && count > 0;
    }

    /**
     * 某跳当前轮次处于各状态的任务数。
     */
    public long countByPlanHopRoundStatus(long planId, int hopIndex, int round, TaskStatus status) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM rollback_task WHERE plan_id = ?"
                + " AND hop_index = ? AND round = ? AND status = ?",
                Long.class, planId, hopIndex, round, status.name());
        return count == null ? 0 : count;
    }

    public List<RollbackTask> findByPlan(long planId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_task WHERE plan_id = ?"
                + " ORDER BY hop_index, round, id", MAPPER, planId);
    }

    public List<RollbackTask> findByDevice(long planId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_task WHERE plan_id = ? AND device_id = ?"
                + " ORDER BY hop_index, round, id", MAPPER, planId, deviceId);
    }
}
