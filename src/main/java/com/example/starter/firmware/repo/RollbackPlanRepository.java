package com.example.starter.firmware.repo;

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
 * 回退计划数据访问。计划状态与（跳,轮）统计只在持有计划行锁（SELECT ... FOR UPDATE）的事务内读写。
 */
@Repository
public class RollbackPlanRepository {

    private static final RowMapper<RollbackPlan> MAPPER = (rs, rowNum) -> new RollbackPlan(
            rs.getLong("id"), rs.getString("plan_key"), rs.getLong("source_release_id"),
            rs.getString("target_version"), RollbackPlanStatus.valueOf(rs.getString("status")),
            rs.getInt("current_hop"), rs.getInt("max_hop"),
            rs.getInt("sample_floor"), rs.getInt("failure_threshold_percent"),
            rs.getInt("current_round"), rs.getInt("round_success"), rs.getInt("round_failed"));

    private static final String COLUMNS = "id, plan_key, source_release_id, target_version, status,"
            + " current_hop, max_hop, sample_floor, failure_threshold_percent,"
            + " current_round, round_success, round_failed";

    private final JdbcTemplate jdbc;

    public RollbackPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String planKey, long sourceReleaseId, String targetVersion, int maxHop,
                       int sampleFloor, int failureThresholdPercent) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollback_plan (plan_key, source_release_id, target_version, status,"
                            + " current_hop, max_hop, sample_floor, failure_threshold_percent, current_round)"
                            + " VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?, ?, 1)",
                    new String[]{"id"});
            ps.setString(1, planKey);
            ps.setLong(2, sourceReleaseId);
            ps.setString(3, targetVersion);
            ps.setInt(4, maxHop);
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

    /**
     * 插入并直接返回行锁内最新计划，供创建事务内继续派发首跳使用。
     */
    public Optional<RollbackPlan> findByPlanKeyForUpdate(String planKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_plan WHERE plan_key = ? FOR UPDATE",
                MAPPER, planKey).stream().findFirst();
    }

    /**
     * 当前（跳,轮）统计加一，调用方必须持有计划行锁。
     */
    public void incrementRoundStats(long id, com.example.starter.firmware.domain.ReceiptResult result) {
        if (result == com.example.starter.firmware.domain.ReceiptResult.SUCCESS) {
            jdbc.update("UPDATE rollback_plan SET round_success = round_success + 1,"
                    + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        } else {
            jdbc.update("UPDATE rollback_plan SET round_failed = round_failed + 1,"
                    + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        }
    }

    /**
     * 仅当仍为 ACTIVE 时转为 PAUSED，返回影响行数；调用方已持有计划行锁。
     */
    public int pauseIfActive(long id) {
        return jdbc.update("UPDATE rollback_plan SET status = 'PAUSED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'ACTIVE'", id);
    }

    /**
     * 人工恢复：仅当状态仍为 PAUSED 时生效；当前跳不变、轮次加一、本轮统计清零。
     */
    public int resume(long id) {
        return jdbc.update("UPDATE rollback_plan SET status = 'ACTIVE', current_round = current_round + 1,"
                + " round_success = 0, round_failed = 0, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'PAUSED'", id);
    }

    /**
     * 进入下一跳：跳号加一、轮次重置为1、本轮统计清零，仅当仍为 ACTIVE 时生效。
     */
    public int advanceHop(long id, int expectedHop) {
        return jdbc.update("UPDATE rollback_plan SET current_hop = current_hop + 1, current_round = 1,"
                + " round_success = 0, round_failed = 0, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND current_hop = ? AND status = 'ACTIVE'", id, expectedHop);
    }

    public int complete(long id) {
        return jdbc.update("UPDATE rollback_plan SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'ACTIVE'", id);
    }

    public int cancel(long id) {
        return jdbc.update("UPDATE rollback_plan SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status IN ('ACTIVE','PAUSED')", id);
    }
}
