package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RollbackPlan;
import com.example.starter.firmware.domain.RollbackPlanStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Optional;

/**
 * 回退计划数据访问。派发、回执、恢复、取消共用行锁（SELECT ... FOR UPDATE）形成一致提交顺序。
 * 当前轮次统计（round_success/round_failed）只在持有计划行锁的事务内增减。
 */
@Repository
public class RollbackPlanRepository {

    private static final RowMapper<RollbackPlan> MAPPER = (rs, rowNum) -> {
        int pausedHop = rs.getInt("paused_hop_index");
        Integer pausedHopIndex = rs.wasNull() ? null : pausedHop;
        return new RollbackPlan(rs.getLong("id"), rs.getString("plan_key"),
                rs.getLong("source_release_id"), rs.getString("model"), rs.getString("target_version"),
                RollbackPlanStatus.valueOf(rs.getString("status")),
                rs.getInt("sample_floor"), rs.getInt("failure_threshold_percent"),
                rs.getInt("monitor_round"), pausedHopIndex,
                rs.getInt("round_success"), rs.getInt("round_failed"));
    };

    private static final String COLUMNS = "id, plan_key, source_release_id, model, target_version, status,"
            + " sample_floor, failure_threshold_percent, monitor_round, paused_hop_index,"
            + " round_success, round_failed";

    private final JdbcTemplate jdbc;

    public RollbackPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String planKey, long sourceReleaseId, String model, String targetVersion,
                       int sampleFloor, int failureThresholdPercent) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollback_plan (plan_key, source_release_id, model, target_version, status,"
                            + " sample_floor, failure_threshold_percent, monitor_round)"
                            + " VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 1)",
                    new String[]{"id"});
            ps.setString(1, planKey);
            ps.setLong(2, sourceReleaseId);
            ps.setString(3, model);
            ps.setString(4, targetVersion);
            ps.setInt(5, sampleFloor);
            ps.setInt(6, failureThresholdPercent);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<RollbackPlan> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_plan WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<RollbackPlan> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_plan WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<RollbackPlan> findByPlanKey(String planKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_plan WHERE plan_key = ?", MAPPER, planKey)
                .stream().findFirst();
    }

    /**
     * 当前监控轮次统计加一（成功或失败），调用方必须已持有计划行锁。
     */
    public void incrementRoundStats(long id, ReceiptResult result) {
        if (result == ReceiptResult.SUCCESS) {
            jdbc.update("UPDATE rollback_plan SET round_success = round_success + 1,"
                    + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        } else {
            jdbc.update("UPDATE rollback_plan SET round_failed = round_failed + 1,"
                    + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        }
    }

    /**
     * 仅当仍为 ACTIVE 时转为 PAUSED 并记录暂停跳次，返回影响行数；调用方已持有行锁。
     */
    public int pauseIfActive(long id, int hopIndex) {
        return jdbc.update("UPDATE rollback_plan SET status = 'PAUSED', paused_hop_index = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'ACTIVE'", hopIndex, id);
    }

    /**
     * 人工恢复：仅当仍为 PAUSED 时生效；开启新监控轮次、清零本轮统计、清除暂停跳次。
     */
    public int resume(long id) {
        return jdbc.update("UPDATE rollback_plan SET status = 'ACTIVE',"
                + " monitor_round = monitor_round + 1, round_success = 0, round_failed = 0,"
                + " paused_hop_index = NULL, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'PAUSED'", id);
    }

    /**
     * 全部设备到达目标后完结；仅当仍为 ACTIVE 或 PAUSED 时生效，返回影响行数。
     */
    public int completeIfOpen(long id) {
        return jdbc.update("UPDATE rollback_plan SET status = 'COMPLETED', paused_hop_index = NULL,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status IN ('ACTIVE','PAUSED')", id);
    }

    /**
     * 取消：仅当仍为 ACTIVE 或 PAUSED 时生效，返回影响行数。
     */
    public int cancelIfOpen(long id) {
        return jdbc.update("UPDATE rollback_plan SET status = 'CANCELLED', paused_hop_index = NULL,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status IN ('ACTIVE','PAUSED')", id);
    }
}
