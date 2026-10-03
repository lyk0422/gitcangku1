package com.example.starter.plan.repo;

import com.example.starter.plan.model.RearrangeRecord;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 计划重排记录的 JDBC 持久化。记录只追加、不更新、不删除，
 * 保证历史证据不被后续限速令撤销或修订覆盖。
 */
@Repository
public class RearrangeRepository {

    private static final RowMapper<RearrangeRecord> MAPPER = (rs, n) -> new RearrangeRecord(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("schedule_key"),
            rs.getObject("op_date", LocalDate.class),
            rs.getInt("shift_minutes"),
            rs.getString("restriction_snapshot"),
            rs.getString("original_occupancies"),
            rs.getString("new_occupancies"),
            rs.getString("operator"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public RearrangeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加重排记录（不可变）。
     */
    public void insert(long planId, String scheduleKey, LocalDate opDate, int shiftMinutes,
                       String restrictionSnapshot, String originalOccupancies,
                       String newOccupancies, String operator, long nowMillis) {
        jdbc.update("INSERT INTO rail_plan_rearrange (plan_id, schedule_key, op_date,"
                        + " shift_minutes, restriction_snapshot, original_occupancies,"
                        + " new_occupancies, operator, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                planId, scheduleKey, Date.valueOf(opDate), shiftMinutes, restrictionSnapshot,
                originalOccupancies, newOccupancies, operator, nowMillis);
    }

    /**
     * 按计划 id 查询重排历史，按主键升序（即追加顺序）。
     */
    public List<RearrangeRecord> findByPlanId(long planId) {
        return jdbc.query("SELECT id, plan_id, schedule_key, op_date, shift_minutes,"
                        + " restriction_snapshot, original_occupancies, new_occupancies,"
                        + " operator, created_at FROM rail_plan_rearrange"
                        + " WHERE plan_id = ? ORDER BY id",
                MAPPER, planId);
    }
}