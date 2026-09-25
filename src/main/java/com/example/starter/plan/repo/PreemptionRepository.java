package com.example.starter.plan.repo;

import com.example.starter.plan.model.Preemption;
import com.example.starter.plan.model.PreemptionSlot;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 抢占记录与涉及时隙持久化。记录不可变，仅在发布锁保护的事务内写入。
 */
@Repository
public class PreemptionRepository {

    private static final RowMapper<Preemption> PREEMPTION_MAPPER = (rs, n) -> new Preemption(
            rs.getLong("id"),
            rs.getLong("preempting_plan_id"),
            rs.getString("preempting_schedule_key"),
            rs.getLong("preempted_plan_id"),
            rs.getString("preempted_schedule_key"),
            rs.getInt("preempting_level"),
            rs.getInt("preempted_level"),
            Instant.ofEpochMilli(rs.getLong("created_at")));

    private static final RowMapper<PreemptionSlot> SLOT_MAPPER = (rs, n) -> new PreemptionSlot(
            rs.getLong("id"),
            rs.getLong("preemption_id"),
            rs.getString("section_id"),
            rs.getInt("section_level"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")));

    private final JdbcTemplate jdbc;

    public PreemptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入抢占记录头，返回自增主键。
     */
    public long insert(long preemptingPlanId, String preemptingScheduleKey,
                       long preemptedPlanId, String preemptedScheduleKey,
                       int preemptingLevel, int preemptedLevel, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_preemption (preempting_plan_id, preempting_schedule_key,"
                            + " preempted_plan_id, preempted_schedule_key,"
                            + " preempting_level, preempted_level, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, preemptingPlanId);
            ps.setString(2, preemptingScheduleKey);
            ps.setLong(3, preemptedPlanId);
            ps.setString(4, preemptedScheduleKey);
            ps.setInt(5, preemptingLevel);
            ps.setInt(6, preemptedLevel);
            ps.setLong(7, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 批量写入抢占涉及时隙。
     */
    public void insertSlots(long preemptionId, List<PreemptionSlot> slots) {
        jdbc.batchUpdate(
                "INSERT INTO rail_preemption_slot (preemption_id, section_id, section_level,"
                        + " start_utc, end_utc) VALUES (?, ?, ?, ?, ?)",
                slots, slots.size(),
                (ps, s) -> {
                    ps.setLong(1, preemptionId);
                    ps.setString(2, s.sectionId());
                    ps.setInt(3, s.sectionLevel());
                    ps.setLong(4, s.startUtc().toEpochMilli());
                    ps.setLong(5, s.endUtc().toEpochMilli());
                });
    }

    /**
     * 判断指定区段上与 [startUtc, endUtc) 重叠的时隙是否已被抢占过（左闭右开）。
     */
    public boolean existsOverlappingSlot(String sectionId, Instant startUtc, Instant endUtc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_preemption_slot"
                        + " WHERE section_id = ? AND start_utc < ? AND ? < end_utc",
                Integer.class, sectionId, endUtc.toEpochMilli(), startUtc.toEpochMilli());
        return count != null && count > 0;
    }

    /**
     * 查询全部抢占记录头，按 id 升序。
     */
    public List<Preemption> findAll() {
        return jdbc.query("SELECT id, preempting_plan_id, preempting_schedule_key,"
                        + " preempted_plan_id, preempted_schedule_key,"
                        + " preempting_level, preempted_level, created_at"
                        + " FROM rail_preemption ORDER BY id",
                PREEMPTION_MAPPER);
    }

    /**
     * 按抢占记录 id 集合查询涉及时隙，按 id 升序。
     */
    public List<PreemptionSlot> findSlots(Collection<Long> preemptionIds) {
        if (preemptionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        preemptionIds.forEach(id -> placeholders.add("?"));
        return jdbc.query("SELECT id, preemption_id, section_id, section_level, start_utc, end_utc"
                        + " FROM rail_preemption_slot WHERE preemption_id IN (" + placeholders + ")"
                        + " ORDER BY id",
                SLOT_MAPPER, preemptionIds.toArray());
    }
}
