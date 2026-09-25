package com.example.starter.plan.repo;

import com.example.starter.plan.model.Preemption;
import com.example.starter.plan.model.PreemptionSection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 不可变抢占记录持久化：只插不改。记录固化双方计划、涉及区段与各自等级，
 * 被抢占计划 id 与抢占幂等键均唯一，支撑"同一计划最多被抢占一次"与并发裁决。
 */
@Repository
public class PreemptionRepository {

    private static final RowMapper<Preemption> PREEMPTION_MAPPER = (rs, n) -> new Preemption(
            rs.getLong("id"),
            rs.getString("preempt_key"),
            rs.getObject("op_date", LocalDate.class),
            rs.getLong("winner_plan_id"),
            rs.getString("winner_schedule_key"),
            rs.getInt("winner_level"),
            rs.getLong("loser_plan_id"),
            rs.getString("loser_schedule_key"),
            rs.getInt("loser_level"),
            rs.getLong("created_at"));

    private static final RowMapper<PreemptionSection> SECTION_MAPPER = (rs, n) -> new PreemptionSection(
            rs.getLong("id"),
            rs.getLong("preemption_id"),
            rs.getString("section_id"),
            rs.getInt("section_level"));

    private final JdbcTemplate jdbc;

    public PreemptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入抢占记录，返回自增主键；preempt_key 或 loser_plan_id 冲突时抛出 DuplicateKeyException。
     */
    public long insert(Preemption preemption) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_preemption (preempt_key, op_date, winner_plan_id,"
                            + " winner_schedule_key, winner_level, loser_plan_id, loser_schedule_key,"
                            + " loser_level, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, preemption.preemptKey());
            ps.setDate(2, Date.valueOf(preemption.opDate()));
            ps.setLong(3, preemption.winnerPlanId());
            ps.setString(4, preemption.winnerScheduleKey());
            ps.setInt(5, preemption.winnerLevel());
            ps.setLong(6, preemption.loserPlanId());
            ps.setString(7, preemption.loserScheduleKey());
            ps.setInt(8, preemption.loserLevel());
            ps.setLong(9, preemption.createdAt());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 批量写入抢占涉及区段明细。
     */
    public void insertSections(long preemptionId, List<PreemptionSection> sections) {
        jdbc.batchUpdate(
                "INSERT INTO rail_preemption_section (preemption_id, section_id, section_level)"
                        + " VALUES (?, ?, ?)",
                sections, sections.size(),
                (ps, s) -> {
                    ps.setLong(1, preemptionId);
                    ps.setString(2, s.sectionId());
                    ps.setInt(3, s.sectionLevel());
                });
    }

    /**
     * 抢占幂等键是否已使用（不同抢占重复使用返回 409）。
     */
    public boolean existsByPreemptKey(String preemptKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_preemption WHERE preempt_key = ?",
                Integer.class, preemptKey);
        return count != null && count > 0;
    }

    /**
     * 指定计划是否曾作为抢占方赢得涉及某区段的抢占（用于"同一时隙只能被抢占一次"的 409 裁决）。
     */
    public boolean existsWinnerRecordOnSection(long winnerPlanId, String sectionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_preemption r JOIN rail_preemption_section s"
                        + " ON s.preemption_id = r.id"
                        + " WHERE r.winner_plan_id = ? AND s.section_id = ?",
                Integer.class, winnerPlanId, sectionId);
        return count != null && count > 0;
    }

    /**
     * 按计划业务键（匹配抢占方或被抢占方）与运营日查询抢占记录，按 id 升序（提交顺序）。
     * 两个过滤条件均可为空（null 表示不过滤）。
     */
    public List<Preemption> findRecords(String scheduleKey, LocalDate opDate) {
        StringBuilder sql = new StringBuilder("SELECT id, preempt_key, op_date, winner_plan_id,"
                + " winner_schedule_key, winner_level, loser_plan_id, loser_schedule_key, loser_level,"
                + " created_at FROM rail_preemption WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (scheduleKey != null) {
            sql.append(" AND (winner_schedule_key = ? OR loser_schedule_key = ?)");
            args.add(scheduleKey);
            args.add(scheduleKey);
        }
        if (opDate != null) {
            sql.append(" AND op_date = ?");
            args.add(Date.valueOf(opDate));
        }
        sql.append(" ORDER BY id");
        return jdbc.query(sql.toString(), PREEMPTION_MAPPER, args.toArray());
    }

    /**
     * 查询抢占记录的涉及区段明细。
     */
    public List<PreemptionSection> findSections(long preemptionId) {
        return jdbc.query("SELECT id, preemption_id, section_id, section_level"
                        + " FROM rail_preemption_section WHERE preemption_id = ? ORDER BY id",
                SECTION_MAPPER, preemptionId);
    }

    /**
     * 被抢占计划是否已有抢占记录（防御性查询，唯一约束为最终保证）。
     */
    public Optional<Preemption> findByLoserPlanId(long loserPlanId) {
        return jdbc.query("SELECT id, preempt_key, op_date, winner_plan_id, winner_schedule_key,"
                        + " winner_level, loser_plan_id, loser_schedule_key, loser_level, created_at"
                        + " FROM rail_preemption WHERE loser_plan_id = ?",
                PREEMPTION_MAPPER, loserPlanId).stream().findFirst();
    }
}
