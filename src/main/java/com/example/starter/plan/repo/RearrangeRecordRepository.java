package com.example.starter.plan.repo;

import com.example.starter.plan.model.RearrangeRecord;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 限速重排记录的 JDBC 持久化。记录只追加、不修改、不删除，保证历史证据稳定。
 */
@Repository
public class RearrangeRecordRepository {

    private static final RowMapper<RearrangeRecord> MAPPER = (rs, n) -> new RearrangeRecord(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("schedule_key"),
            rs.getString("restriction_key"),
            rs.getInt("restriction_version"),
            rs.getString("section_id"),
            rs.getInt("max_speed_kmh"),
            Instant.ofEpochMilli(rs.getLong("restriction_start_utc")),
            Instant.ofEpochMilli(rs.getLong("restriction_end_utc")),
            rs.getInt("seq"),
            rs.getString("train_no"),
            Instant.ofEpochMilli(rs.getLong("original_start_utc")),
            Instant.ofEpochMilli(rs.getLong("original_end_utc")),
            Instant.ofEpochMilli(rs.getLong("shifted_start_utc")),
            Instant.ofEpochMilli(rs.getLong("shifted_end_utc")),
            rs.getInt("shift_minutes"),
            rs.getInt("affected_minutes"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public RearrangeRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 批量追加重排记录（仅在发布/改签事务全部校验通过后调用）。
     */
    public void insertAll(List<RearrangeRecord> records) {
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_rearrange_record (plan_id, schedule_key, restriction_key,"
                        + " restriction_version, section_id, max_speed_kmh,"
                        + " restriction_start_utc, restriction_end_utc, seq, train_no,"
                        + " original_start_utc, original_end_utc,"
                        + " shifted_start_utc, shifted_end_utc,"
                        + " shift_minutes, affected_minutes, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                records, records.size(),
                (ps, r) -> {
                    ps.setLong(1, r.planId());
                    ps.setString(2, r.scheduleKey());
                    ps.setString(3, r.restrictionKey());
                    ps.setInt(4, r.restrictionVersion());
                    ps.setString(5, r.sectionId());
                    ps.setInt(6, r.maxSpeedKmh());
                    ps.setLong(7, r.restrictionStartUtc().toEpochMilli());
                    ps.setLong(8, r.restrictionEndUtc().toEpochMilli());
                    ps.setInt(9, r.seq());
                    ps.setString(10, r.trainNo());
                    ps.setLong(11, r.originalStartUtc().toEpochMilli());
                    ps.setLong(12, r.originalEndUtc().toEpochMilli());
                    ps.setLong(13, r.shiftedStartUtc().toEpochMilli());
                    ps.setLong(14, r.shiftedEndUtc().toEpochMilli());
                    ps.setInt(15, r.shiftMinutes());
                    ps.setInt(16, r.affectedMinutes());
                    ps.setLong(17, r.createdAt());
                });
    }

    /**
     * 查询指定计划的重排记录，按主键升序（即追加顺序），保证历史排序稳定。
     */
    public List<RearrangeRecord> findByPlanId(long planId) {
        return jdbc.query("SELECT id, plan_id, schedule_key, restriction_key, restriction_version,"
                        + " section_id, max_speed_kmh, restriction_start_utc, restriction_end_utc,"
                        + " seq, train_no, original_start_utc, original_end_utc,"
                        + " shifted_start_utc, shifted_end_utc, shift_minutes, affected_minutes,"
                        + " created_at FROM rail_plan_rearrange_record"
                        + " WHERE plan_id = ? ORDER BY id",
                MAPPER, planId);
    }
}