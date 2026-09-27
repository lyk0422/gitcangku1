package com.example.starter.plan.repo;

import com.example.starter.plan.model.PlanRearrangement;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 计划重排记录的 JDBC 持久化。记录追加后不可变，不提供更新与删除。
 */
@Repository
public class RearrangementRepository {

    private static final RowMapper<PlanRearrangement> MAPPER = (rs, n) -> new PlanRearrangement(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("schedule_key"),
            rs.getString("op_type"),
            rs.getInt("shift_minutes"),
            rs.getString("restriction_snapshot_json"),
            rs.getString("original_json"),
            rs.getString("shifted_json"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public RearrangementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加重排记录（不可变业务证据）。
     */
    public void insert(long planId, String scheduleKey, String opType, int shiftMinutes,
                       String restrictionSnapshotJson, String originalJson, String shiftedJson,
                       long nowMillis) {
        jdbc.update("INSERT INTO rail_plan_rearrangement"
                        + " (plan_id, schedule_key, op_type, shift_minutes,"
                        + " restriction_snapshot_json, original_json, shifted_json, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                planId, scheduleKey, opType, shiftMinutes,
                restrictionSnapshotJson, originalJson, shiftedJson, nowMillis);
    }

    /**
     * 按计划业务键查询重排历史，按 id 升序（即追加顺序），排序稳定。
     */
    public List<PlanRearrangement> findByScheduleKey(String scheduleKey) {
        return jdbc.query("SELECT id, plan_id, schedule_key, op_type, shift_minutes,"
                        + " restriction_snapshot_json, original_json, shifted_json, created_at"
                        + " FROM rail_plan_rearrangement WHERE schedule_key = ? ORDER BY id",
                MAPPER, scheduleKey);
    }
}
