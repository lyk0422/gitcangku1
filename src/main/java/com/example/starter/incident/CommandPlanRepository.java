package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 指令计划、步骤与补偿尝试的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行（incidents FOR UPDATE）的写事务内，
 * 同事件的计划执行、取消与补偿按事务提交顺序串行化，保证取消后不会新增效果。
 */
@Repository
public class CommandPlanRepository {

    private final JdbcTemplate jdbc;

    public CommandPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CommandPlan> PLAN_MAPPER = (rs, n) -> new CommandPlan(
            rs.getLong("id"), rs.getLong("incident_id"),
            PlanStatus.valueOf(rs.getString("status")), rs.getLong("version"),
            rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<CommandPlanStep> STEP_MAPPER = (rs, n) -> mapStep(rs);

    private static final RowMapper<PlanCompensation> COMPENSATION_MAPPER = (rs, n) -> new PlanCompensation(
            rs.getLong("id"), rs.getLong("plan_id"), rs.getLong("step_id"),
            rs.getInt("step_index"), rs.getInt("attempt_no"),
            CompensationStatus.valueOf(rs.getString("status")),
            rs.getString("evidence"), rs.getString("failure_diagnostic"),
            rs.getString("actor"), rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private static CommandPlanStep mapStep(ResultSet rs) throws SQLException {
        Timestamp appliedAt = rs.getTimestamp("applied_at");
        Timestamp compensatedAt = rs.getTimestamp("compensated_at");
        return new CommandPlanStep(
                rs.getLong("id"), rs.getLong("plan_id"), rs.getLong("incident_id"),
                rs.getInt("step_index"), rs.getString("step_key"),
                rs.getString("effect_desc"), rs.getString("compensation_desc"),
                PlanStepStatus.valueOf(rs.getString("status")),
                rs.getString("applied_by"),
                appliedAt == null ? null : appliedAt.toInstant(),
                rs.getString("compensated_by"),
                compensatedAt == null ? null : compensatedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /** 插入计划，初始 RUNNING、version=0，返回生成主键。 */
    public long insertPlan(CommandPlan plan) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO command_plans (incident_id, status, version, created_by,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, plan.incidentId());
            ps.setString(2, plan.status().name());
            ps.setLong(3, plan.version());
            ps.setString(4, plan.createdBy());
            ps.setTimestamp(5, Timestamp.from(plan.createdAt()));
            ps.setTimestamp(6, Timestamp.from(plan.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 按主键查询计划。 */
    public Optional<CommandPlan> findPlanById(long planId) {
        List<CommandPlan> rows = jdbc.query("SELECT * FROM command_plans WHERE id = ?",
                PLAN_MAPPER, planId);
        return rows.stream().findFirst();
    }

    /** 查询事件全部计划，按创建顺序返回。 */
    public List<CommandPlan> listPlansByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM command_plans WHERE incident_id = ? ORDER BY id",
                PLAN_MAPPER, incidentId);
    }

    /** 无条件推进计划状态与版本，返回受影响行数（1 表示成功）。 */
    public int updatePlanStatus(long planId, PlanStatus status, long newVersion, Instant updatedAt) {
        return jdbc.update("UPDATE command_plans SET status = ?, version = ?, updated_at = ?"
                        + " WHERE id = ?",
                status.name(), newVersion, Timestamp.from(updatedAt), planId);
    }

    /** 插入 PENDING 步骤，返回生成主键。(plan_id, step_key)/(plan_id, step_index) 唯一兜底。 */
    public long insertStep(CommandPlanStep step) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO command_plan_steps (plan_id, incident_id, step_index, step_key,"
                            + " effect_desc, compensation_desc, status, applied_by, applied_at,"
                            + " compensated_by, compensated_at, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, step.planId());
            ps.setLong(2, step.incidentId());
            ps.setInt(3, step.stepIndex());
            ps.setString(4, step.stepKey());
            ps.setString(5, step.effectDesc());
            ps.setString(6, step.compensationDesc());
            ps.setString(7, step.status().name());
            ps.setString(8, step.appliedBy());
            ps.setTimestamp(9, step.appliedAt() == null ? null : Timestamp.from(step.appliedAt()));
            ps.setString(10, step.compensatedBy());
            ps.setTimestamp(11, step.compensatedAt() == null
                    ? null : Timestamp.from(step.compensatedAt()));
            ps.setTimestamp(12, Timestamp.from(step.createdAt()));
            ps.setTimestamp(13, Timestamp.from(step.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 查询计划全部步骤，按序号升序返回。 */
    public List<CommandPlanStep> listSteps(long planId) {
        return jdbc.query("SELECT * FROM command_plan_steps WHERE plan_id = ? ORDER BY step_index",
                STEP_MAPPER, planId);
    }

    /** 按步骤键查询计划内步骤。 */
    public Optional<CommandPlanStep> findStepByKey(long planId, String stepKey) {
        List<CommandPlanStep> rows = jdbc.query(
                "SELECT * FROM command_plan_steps WHERE plan_id = ? AND step_key = ?",
                STEP_MAPPER, planId, stepKey);
        return rows.stream().findFirst();
    }

    /**
     * 条件置为 APPLIED：仅当步骤仍 PENDING 时生效，返回受影响行数。
     * 唯一入口保证并发/重复执行只记一次效果。
     */
    public int markApplied(long stepId, String actor, Instant at) {
        return jdbc.update("UPDATE command_plan_steps SET status = 'APPLIED', applied_by = ?,"
                        + " applied_at = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'",
                actor, Timestamp.from(at), Timestamp.from(at), stepId);
    }

    /**
     * 条件置为 COMPENSATED：仅当步骤仍 APPLIED 时生效，返回受影响行数。
     */
    public int markCompensated(long stepId, String actor, Instant at) {
        return jdbc.update("UPDATE command_plan_steps SET status = 'COMPENSATED', compensated_by = ?,"
                        + " compensated_at = ?, updated_at = ? WHERE id = ? AND status = 'APPLIED'",
                actor, Timestamp.from(at), Timestamp.from(at), stepId);
    }

    /** 追加一条补偿尝试（成功或失败均留痕），返回生成主键。 */
    public long insertCompensation(PlanCompensation compensation) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO command_plan_compensations (plan_id, step_id, step_index,"
                            + " attempt_no, status, evidence, failure_diagnostic, actor,"
                            + " request_id, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, compensation.planId());
            ps.setLong(2, compensation.stepId());
            ps.setInt(3, compensation.stepIndex());
            ps.setInt(4, compensation.attemptNo());
            ps.setString(5, compensation.status().name());
            ps.setString(6, compensation.evidence());
            ps.setString(7, compensation.failureDiagnostic());
            ps.setString(8, compensation.actor());
            ps.setString(9, compensation.requestId());
            ps.setTimestamp(10, Timestamp.from(compensation.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 查询某步骤的全部补偿尝试，按尝试顺序返回。 */
    public List<PlanCompensation> listCompensationsByStep(long stepId) {
        return jdbc.query(
                "SELECT * FROM command_plan_compensations WHERE step_id = ? ORDER BY id",
                COMPENSATION_MAPPER, stepId);
    }

    /** 查询某计划的全部补偿尝试，按落库顺序返回。 */
    public List<PlanCompensation> listCompensationsByPlan(long planId) {
        return jdbc.query(
                "SELECT * FROM command_plan_compensations WHERE plan_id = ? ORDER BY id",
                COMPENSATION_MAPPER, planId);
    }

    /** 统计某步骤已有补偿尝试次数，用于生成 attemptNo。 */
    public int countCompensationsByStep(long stepId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_plan_compensations WHERE step_id = ?",
                Integer.class, stepId);
        return count == null ? 0 : count;
    }
}
