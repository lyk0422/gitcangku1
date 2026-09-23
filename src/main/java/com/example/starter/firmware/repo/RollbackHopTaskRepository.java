package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RollbackHopTask;
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
 * 回跳任务数据访问。同设备同跳同一轮次由唯一约束 uk_hop_attempt 保证最多一条；
 * receipt_key 全局唯一，回执必须原样携带。
 */
@Repository
public class RollbackHopTaskRepository {

    private static final RowMapper<RollbackHopTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        return new RollbackHopTask(rs.getLong("id"), rs.getString("receipt_key"), rs.getLong("plan_id"),
                rs.getString("device_id"), rs.getInt("hop_index"), rs.getInt("round_no"),
                rs.getString("expected_version"), rs.getString("target_version"),
                rs.getLong("source_release_id"), TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult));
    };

    private static final String COLUMNS = "id, receipt_key, plan_id, device_id, hop_index, round_no,"
            + " expected_version, target_version, source_release_id, status, first_result";

    private final JdbcTemplate jdbc;

    public RollbackHopTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String receiptKey, long planId, String deviceId, int hopIndex, int roundNo,
                       String expectedVersion, String targetVersion, long sourceReleaseId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollback_hop_task (receipt_key, plan_id, device_id, hop_index, round_no,"
                            + " expected_version, target_version, source_release_id, status)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')",
                    new String[]{"id"});
            ps.setString(1, receiptKey);
            ps.setLong(2, planId);
            ps.setString(3, deviceId);
            ps.setInt(4, hopIndex);
            ps.setInt(5, roundNo);
            ps.setString(6, expectedVersion);
            ps.setString(7, targetVersion);
            ps.setLong(8, sourceReleaseId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<RollbackHopTask> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_hop_task WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<RollbackHopTask> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_hop_task WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 设备某跳最新轮次的任务（无论状态），用于幂等派发与门控判断。
     */
    public Optional<RollbackHopTask> findLatest(long planId, String deviceId, int hopIndex) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_hop_task"
                        + " WHERE plan_id = ? AND device_id = ? AND hop_index = ?"
                        + " ORDER BY round_no DESC, id DESC LIMIT 1",
                MAPPER, planId, deviceId, hopIndex).stream().findFirst();
    }

    public List<RollbackHopTask> findByPlanAndDevice(long planId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_hop_task"
                        + " WHERE plan_id = ? AND device_id = ? ORDER BY hop_index, round_no, id",
                MAPPER, planId, deviceId);
    }

    public List<RollbackHopTask> findByPlan(long planId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_hop_task"
                        + " WHERE plan_id = ? ORDER BY device_id, hop_index, round_no, id",
                MAPPER, planId);
    }

    public void complete(long id, ReceiptResult result) {
        jdbc.update("UPDATE rollback_hop_task SET status = ?, first_result = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", result.name(), result.name(), id);
    }

    /**
     * 取消计划内某跳的 PENDING 任务（人工恢复时清理该跳未回执任务）。
     */
    public int cancelPendingByHop(long planId, int hopIndex) {
        return jdbc.update("UPDATE rollback_hop_task SET status = 'CANCELLED',"
                + " updated_at = CURRENT_TIMESTAMP"
                + " WHERE plan_id = ? AND hop_index = ? AND status = 'PENDING'", planId, hopIndex);
    }

    /**
     * 取消计划内全部 PENDING 任务（计划取消时）。
     */
    public int cancelPendingByPlan(long planId) {
        return jdbc.update("UPDATE rollback_hop_task SET status = 'CANCELLED',"
                + " updated_at = CURRENT_TIMESTAMP WHERE plan_id = ? AND status = 'PENDING'", planId);
    }

    /**
     * 设备是否还有未终结的回跳任务（跨计划），用于新投放冲突检查。
     */
    public boolean hasPendingByDevice(String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollback_hop_task WHERE device_id = ? AND status = 'PENDING'",
                Long.class, deviceId);
        return count != null && count > 0;
    }

    /**
     * 指定跳在指定轮次内首次终结为某结果的任务数，用于该跳失败率评估。
     */
    public int countByHopRoundAndResult(long planId, int hopIndex, int roundNo, ReceiptResult result) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollback_hop_task"
                        + " WHERE plan_id = ? AND hop_index = ? AND round_no = ? AND first_result = ?",
                Long.class, planId, hopIndex, roundNo, result.name());
        return count == null ? 0 : count.intValue();
    }

    /**
     * 计划内某设备是否已全部跳成功（用于计划完结判定）。
     */
    public boolean allHopsSucceeded(long planId, String deviceId, int hopCount) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT hop_index) FROM rollback_hop_task"
                        + " WHERE plan_id = ? AND device_id = ? AND status = 'SUCCESS'",
                Long.class, planId, deviceId);
        return count != null && count == hopCount;
    }

    /**
     * 按轮次聚合统计：round_no, success, failed。
     */
    public List<long[]> roundStats(long planId) {
        return jdbc.query("SELECT round_no,"
                        + " SUM(CASE WHEN first_result = 'SUCCESS' THEN 1 ELSE 0 END) AS s,"
                        + " SUM(CASE WHEN first_result = 'FAILED' THEN 1 ELSE 0 END) AS f"
                        + " FROM rollback_hop_task WHERE plan_id = ? GROUP BY round_no ORDER BY round_no",
                (rs, rowNum) -> new long[]{rs.getLong("round_no"), rs.getLong("s"), rs.getLong("f")},
                planId);
    }
}
