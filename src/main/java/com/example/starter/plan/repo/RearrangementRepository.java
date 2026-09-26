package com.example.starter.plan.repo;

import com.example.starter.plan.model.Rearrangement;
import com.example.starter.plan.model.RearrangementSegment;
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
 * 计划重排记录及逐段明细的 JDBC 持久化。记录追加后不可变：
 * 限速令撤销/修订、计划后续变更均不改写已生成的重排记录。
 */
@Repository
public class RearrangementRepository {

    private static final RowMapper<Rearrangement> HEADER_MAPPER = (rs, n) -> new Rearrangement(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("schedule_key"),
            rs.getString("op_type"),
            rs.getLong("shift_minutes"),
            rs.getString("operator"),
            rs.getLong("created_at"));

    private static final RowMapper<RearrangementSegment> SEGMENT_MAPPER = (rs, n) -> new RearrangementSegment(
            rs.getLong("id"),
            rs.getLong("rearrangement_id"),
            rs.getInt("seq"),
            rs.getString("train_no"),
            rs.getString("section_id"),
            Instant.ofEpochMilli(rs.getLong("old_start_utc")),
            Instant.ofEpochMilli(rs.getLong("old_end_utc")),
            Instant.ofEpochMilli(rs.getLong("new_start_utc")),
            Instant.ofEpochMilli(rs.getLong("new_end_utc")),
            rs.getLong("affected_minutes"),
            rs.getLong("restriction_id"),
            rs.getString("restriction_key"),
            rs.getInt("restriction_version"),
            rs.getInt("max_speed_kmh"));

    private final JdbcTemplate jdbc;

    public RearrangementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入重排记录头部，返回自增主键。
     */
    public long insertHeader(long planId, String scheduleKey, String opType, long shiftMinutes,
                             String operator, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_plan_rearrangement"
                            + " (plan_id, schedule_key, op_type, shift_minutes, operator, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, planId);
            ps.setString(2, scheduleKey);
            ps.setString(3, opType);
            ps.setLong(4, shiftMinutes);
            ps.setString(5, operator);
            ps.setLong(6, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 批量插入重排逐段明细。
     */
    public void insertSegments(List<RearrangementSegment> segments) {
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_rearrangement_segment (rearrangement_id, seq, train_no, section_id,"
                        + " old_start_utc, old_end_utc, new_start_utc, new_end_utc, affected_minutes,"
                        + " restriction_id, restriction_key, restriction_version, max_speed_kmh)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                segments, segments.size(),
                (ps, s) -> {
                    ps.setLong(1, s.rearrangementId());
                    ps.setInt(2, s.seq());
                    ps.setString(3, s.trainNo());
                    ps.setString(4, s.sectionId());
                    ps.setLong(5, s.oldStartUtc().toEpochMilli());
                    ps.setLong(6, s.oldEndUtc().toEpochMilli());
                    ps.setLong(7, s.newStartUtc().toEpochMilli());
                    ps.setLong(8, s.newEndUtc().toEpochMilli());
                    ps.setLong(9, s.affectedMinutes());
                    ps.setLong(10, s.restrictionId());
                    ps.setString(11, s.restrictionKey());
                    ps.setInt(12, s.restrictionVersion());
                    ps.setInt(13, s.maxSpeedKmh());
                });
    }

    /**
     * 查询指定计划的全部重排记录，按创建顺序（主键）升序；历史查询，读取不改变状态。
     */
    public List<Rearrangement> findByPlanId(long planId) {
        return jdbc.query("SELECT id, plan_id, schedule_key, op_type, shift_minutes, operator, created_at"
                        + " FROM rail_plan_rearrangement WHERE plan_id = ? ORDER BY id",
                HEADER_MAPPER, planId);
    }

    /**
     * 查询指定重排记录的逐段明细，按计划内序号升序。
     */
    public List<RearrangementSegment> findSegments(long rearrangementId) {
        return jdbc.query("SELECT id, rearrangement_id, seq, train_no, section_id,"
                        + " old_start_utc, old_end_utc, new_start_utc, new_end_utc, affected_minutes,"
                        + " restriction_id, restriction_key, restriction_version, max_speed_kmh"
                        + " FROM rail_plan_rearrangement_segment WHERE rearrangement_id = ? ORDER BY seq",
                SEGMENT_MAPPER, rearrangementId);
    }
}
