package com.example.starter.plan.repo;

import com.example.starter.plan.model.EnvelopeSnapshot;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 应急制动包络判定快照的 JDBC 持久化。快照追加后不可变，仅随发布/改签成功写入。
 */
@Repository
public class EnvelopeSnapshotRepository {

    private static final RowMapper<EnvelopeSnapshot> MAPPER = (rs, n) -> new EnvelopeSnapshot(
            rs.getLong("plan_id"),
            rs.getInt("occupancy_seq"),
            rs.getString("train_no"),
            rs.getString("section_id"),
            rs.getBigDecimal("speed_mps"),
            rs.getBigDecimal("reaction_time_s"),
            rs.getBigDecimal("base_deceleration_mps2"),
            rs.getBigDecimal("protection_distance_m"),
            rs.getInt("train_profile_version"),
            rs.getInt("section_profile_version"),
            rs.getBigDecimal("lhs_scaled"),
            rs.getBigDecimal("rhs_scaled"));

    private final JdbcTemplate jdbc;

    public EnvelopeSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 批量追加冻结快照（仅发布/改签成功时调用，同事务提交）。
     */
    public void insertAll(List<EnvelopeSnapshot> snapshots, long nowMillis) {
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_envelope_snapshot (plan_id, occupancy_seq, train_no,"
                        + " section_id, speed_mps, reaction_time_s, base_deceleration_mps2,"
                        + " protection_distance_m, train_profile_version, section_profile_version,"
                        + " lhs_scaled, rhs_scaled, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshots, snapshots.size(),
                (ps, s) -> {
                    ps.setLong(1, s.planId());
                    ps.setInt(2, s.occupancySeq());
                    ps.setString(3, s.trainNo());
                    ps.setString(4, s.sectionId());
                    ps.setBigDecimal(5, s.speedMps());
                    ps.setBigDecimal(6, s.reactionTimeS());
                    ps.setBigDecimal(7, s.baseDeceleration());
                    ps.setBigDecimal(8, s.protectionDistanceM());
                    ps.setInt(9, s.trainProfileVersion());
                    ps.setInt(10, s.sectionProfileVersion());
                    ps.setBigDecimal(11, s.lhsScaled());
                    ps.setBigDecimal(12, s.rhsScaled());
                    ps.setLong(13, nowMillis);
                });
    }

    /**
     * 按计划 id 查询冻结快照，按占用序号升序。
     */
    public List<EnvelopeSnapshot> findByPlan(long planId) {
        return jdbc.query("SELECT plan_id, occupancy_seq, train_no, section_id, speed_mps,"
                        + " reaction_time_s, base_deceleration_mps2, protection_distance_m,"
                        + " train_profile_version, section_profile_version, lhs_scaled, rhs_scaled"
                        + " FROM rail_plan_envelope_snapshot WHERE plan_id = ?"
                        + " ORDER BY occupancy_seq",
                MAPPER, planId);
    }
}
