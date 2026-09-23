package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.RollbackPauseRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 回退计划自动暂停记录数据访问。唯一约束 uk_plan_pause_round 保证每轮至多一条，历史只增不改。
 */
@Repository
public class RollbackPauseRecordRepository {

    private static final RowMapper<RollbackPauseRecord> MAPPER = (rs, rowNum) -> new RollbackPauseRecord(
            rs.getLong("id"), rs.getLong("plan_id"), rs.getInt("monitor_round"),
            rs.getInt("hop_index"), rs.getLong("trigger_hop_task_id"),
            rs.getInt("success_count"), rs.getInt("failed_count"), rs.getString("paused_at_utc"));

    private static final String COLUMNS = "id, plan_id, monitor_round, hop_index, trigger_hop_task_id,"
            + " success_count, failed_count, paused_at_utc";

    private final JdbcTemplate jdbc;

    public RollbackPauseRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long planId, int monitorRound, int hopIndex, long triggerHopTaskId,
                       int successCount, int failedCount, String pausedAtUtc) {
        jdbc.update("INSERT INTO rollback_plan_pause_record"
                        + " (plan_id, monitor_round, hop_index, trigger_hop_task_id,"
                        + " success_count, failed_count, paused_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                planId, monitorRound, hopIndex, triggerHopTaskId,
                successCount, failedCount, pausedAtUtc);
    }

    public List<RollbackPauseRecord> findByPlan(long planId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollback_plan_pause_record"
                + " WHERE plan_id = ? ORDER BY id", MAPPER, planId);
    }
}
