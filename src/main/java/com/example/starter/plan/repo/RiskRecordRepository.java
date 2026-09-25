package com.example.starter.plan.repo;

import com.example.starter.plan.model.CrewRole;
import com.example.starter.plan.model.RiskRecord;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 乘务资质风险记录持久化。记录追加后不可变；同一计划同一乘务员同一角色至多一条。
 */
@Repository
public class RiskRecordRepository {

    private static final RowMapper<RiskRecord> MAPPER = (rs, n) -> new RiskRecord(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("crew_id"),
            CrewRole.valueOf(rs.getString("role")),
            rs.getString("qualification_code"),
            rs.getString("reason"),
            Instant.ofEpochMilli(rs.getLong("created_at")));

    private final JdbcTemplate jdbc;

    public RiskRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加不可变风险记录；唯一约束冲突时抛出 DuplicateKeyException 由上层裁决（整单回滚）。
     */
    public void insert(long planId, String crewId, CrewRole role, String qualificationCode,
                       String reason, long nowMillis) {
        jdbc.update("INSERT INTO rail_plan_risk_record"
                        + " (plan_id, crew_id, role, qualification_code, reason, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                planId, crewId, role.name(), qualificationCode, reason, nowMillis);
    }

    /**
     * 查询计划的风险记录，按创建顺序（主键）升序。
     */
    public List<RiskRecord> findByPlanId(long planId) {
        return jdbc.query("SELECT id, plan_id, crew_id, role, qualification_code, reason, created_at"
                        + " FROM rail_plan_risk_record WHERE plan_id = ? ORDER BY id",
                MAPPER, planId);
    }
}
