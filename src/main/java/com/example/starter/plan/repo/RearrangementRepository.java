package com.example.starter.plan.repo;

import com.example.starter.plan.model.Rearrangement;
import com.example.starter.plan.model.RearrangementItem;
import com.example.starter.plan.model.RearrangementRestriction;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 限速重排记录的 JDBC 持久化。记录为历史证据：只追加、不更新、不删除。
 */
@Repository
public class RearrangementRepository {

    private static final RowMapper<Rearrangement> REARRANGEMENT_MAPPER = (rs, n) -> new Rearrangement(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("schedule_key"),
            rs.getString("op_type"),
            rs.getLong("shift_minutes"),
            rs.getLong("created_at"));

    private static final RowMapper<RearrangementItem> ITEM_MAPPER = (rs, n) -> new RearrangementItem(
            rs.getLong("id"),
            rs.getLong("rearrangement_id"),
            rs.getInt("seq"),
            rs.getString("train_no"),
            rs.getString("section_id"),
            Instant.ofEpochMilli(rs.getLong("orig_start_utc")),
            Instant.ofEpochMilli(rs.getLong("orig_end_utc")),
            Instant.ofEpochMilli(rs.getLong("new_start_utc")),
            Instant.ofEpochMilli(rs.getLong("new_end_utc")));

    private static final RowMapper<RearrangementRestriction> RESTRICTION_MAPPER =
            (rs, n) -> new RearrangementRestriction(
                    rs.getLong("id"),
                    rs.getLong("rearrangement_id"),
                    rs.getString("restriction_key"),
                    rs.getInt("restriction_version"),
                    rs.getString("section_id"),
                    rs.getInt("max_speed_kmh"),
                    rs.getLong("affected_minutes"));

    private final JdbcTemplate jdbc;

    public RearrangementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加重排记录主档，返回自增主键。
     */
    public long insertRearrangement(long planId, String scheduleKey, String opType,
                                    long shiftMinutes, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_plan_rearrangement (plan_id, schedule_key, op_type,"
                            + " shift_minutes, created_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, planId);
            ps.setString(2, scheduleKey);
            ps.setString(3, opType);
            ps.setLong(4, shiftMinutes);
            ps.setLong(5, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 批量追加重排明细（逐段原计划时刻与新计划时刻）。
     */
    public void insertItems(long rearrangementId, List<RearrangementItem> items) {
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_rearrangement_item (rearrangement_id, seq, train_no, section_id,"
                        + " orig_start_utc, orig_end_utc, new_start_utc, new_end_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                items, items.size(),
                (ps, item) -> {
                    ps.setLong(1, rearrangementId);
                    ps.setInt(2, item.seq());
                    ps.setString(3, item.trainNo());
                    ps.setString(4, item.sectionId());
                    ps.setLong(5, item.origStartUtc().toEpochMilli());
                    ps.setLong(6, item.origEndUtc().toEpochMilli());
                    ps.setLong(7, item.newStartUtc().toEpochMilli());
                    ps.setLong(8, item.newEndUtc().toEpochMilli());
                });
    }

    /**
     * 批量追加重排引用的限速令快照。
     */
    public void insertRestrictions(long rearrangementId, List<RearrangementRestriction> restrictions) {
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_rearrangement_restriction (rearrangement_id, restriction_key,"
                        + " restriction_version, section_id, max_speed_kmh, affected_minutes)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                restrictions, restrictions.size(),
                (ps, r) -> {
                    ps.setLong(1, rearrangementId);
                    ps.setString(2, r.restrictionKey());
                    ps.setInt(3, r.restrictionVersion());
                    ps.setString(4, r.sectionId());
                    ps.setInt(5, r.maxSpeedKmh());
                    ps.setLong(6, r.affectedMinutes());
                });
    }

    /**
     * 查询指定计划的全部重排记录，按 id 升序（即生成顺序）。
     */
    public List<Rearrangement> findByPlanId(long planId) {
        return jdbc.query("SELECT id, plan_id, schedule_key, op_type, shift_minutes, created_at"
                        + " FROM rail_plan_rearrangement WHERE plan_id = ? ORDER BY id",
                REARRANGEMENT_MAPPER, planId);
    }

    /**
     * 查询重排记录的逐段明细，按计划内序号升序。
     */
    public List<RearrangementItem> findItems(long rearrangementId) {
        return jdbc.query("SELECT id, rearrangement_id, seq, train_no, section_id,"
                        + " orig_start_utc, orig_end_utc, new_start_utc, new_end_utc"
                        + " FROM rail_plan_rearrangement_item WHERE rearrangement_id = ? ORDER BY seq",
                ITEM_MAPPER, rearrangementId);
    }

    /**
     * 查询重排记录引用的限速令快照，按 id 升序（即登记顺序）。
     */
    public List<RearrangementRestriction> findRestrictions(long rearrangementId) {
        return jdbc.query("SELECT id, rearrangement_id, restriction_key, restriction_version,"
                        + " section_id, max_speed_kmh, affected_minutes"
                        + " FROM rail_plan_rearrangement_restriction WHERE rearrangement_id = ? ORDER BY id",
                RESTRICTION_MAPPER, rearrangementId);
    }
}
