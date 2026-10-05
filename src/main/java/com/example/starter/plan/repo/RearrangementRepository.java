package com.example.starter.plan.repo;

import com.example.starter.plan.model.Rearrangement;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 限速重排记录的 JDBC 持久化。记录追加后不可变，仅提供插入与查询，不提供更新与删除。
 */
@Repository
public class RearrangementRepository {

    private static final RowMapper<Rearrangement> MAPPER = (rs, n) -> new Rearrangement(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("schedule_key"),
            rs.getString("op_type"),
            rs.getLong("shift_minutes"),
            rs.getString("restriction_refs"),
            rs.getString("original_occupancies"),
            rs.getString("shifted_occupancies"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public RearrangementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加重排记录（不可变，仅在发布/改签事务内调用）。
     */
    public void insert(Rearrangement r) {
        jdbc.update("INSERT INTO rail_plan_rearrangement (plan_id, schedule_key, op_type,"
                        + " shift_minutes, restriction_refs, original_occupancies,"
                        + " shifted_occupancies, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                r.planId(), r.scheduleKey(), r.opType(), r.shiftMinutes(),
                r.restrictionRefsJson(), r.originalJson(), r.shiftedJson(), r.createdAt());
    }

    /**
     * 按计划业务键查询重排历史，按主键升序（生成顺序稳定）。
     */
    public List<Rearrangement> findByScheduleKey(String scheduleKey) {
        return jdbc.query("SELECT id, plan_id, schedule_key, op_type, shift_minutes,"
                        + " restriction_refs, original_occupancies, shifted_occupancies, created_at"
                        + " FROM rail_plan_rearrangement WHERE schedule_key = ? ORDER BY id",
                MAPPER, scheduleKey);
    }
}
