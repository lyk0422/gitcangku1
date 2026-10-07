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
 * 指令计划、步骤及补偿证据的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内，同一事件的计划操作按事务提交顺序串行生效。
 */
@Repository
public class CommandPlanRepository {

    private final JdbcTemplate jdbc;

    public CommandPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CommandPlan> PLAN_MAPPER = (rs, n) -> mapPlan(rs);
    private static final RowMapper<CommandPlanStep> STEP_MAPPER = (rs, n) -> mapStep(rs);
    private static final RowMapper<CommandPlanCompensation> COMP_MAPPER = (rs, n) -> mapComp(rs);

    private static CommandPlan mapPlan(ResultSet rs) throws SQLException {
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        return new CommandPlan(rs.getLong("id"), rs.getLong("incident_id"),
                rs.getString("plan_key"), PlanStatus.valueOf(rs.getString("status")),
                rs.getInt("version"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getString("cancelled_by"),
                cancelledAt == null ? null : cancelledAt.toInstant());
    }

    private static CommandPlanStep mapStep(ResultSet rs) throws SQLException {
        Timestamp appliedAt = rs.getTimestamp("applied_at");
        Timestamp compensatedAt = rs.getTimestamp("compensated_at");
        return new CommandPlanStep(rs.getLong("id"), rs.getLong("plan_id"),
                rs.getString("step_key"), rs.getInt("seq"), rs.getString("effect_text"),
                rs.getString("compensation_text"), PlanStepStatus.valueOf(rs.getString("status")),
                rs.getString("applied_by"), appliedAt == null ? null : appliedAt.toInstant(),
                rs.getString("compensated_by"),
                compensatedAt == null ? null : compensatedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private static CommandPlanCompensation mapComp(ResultSet rs) throws SQLException {
        return new CommandPlanCompensation(rs.getLong("id"), rs.getLong("plan_id"),
                rs.getLong("step_id"), rs.getString("step_key"), rs.getString("evidence"),
                rs.getString("outcome"), rs.getString("diagnostics"), rs.getString("actor"),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 插入计划（初始 RUNNING、版本 1），返回生成主键。
     * (incident_id, plan_key) 唯一约束兜底并发重复插入。
     */
    public long insertPlan(CommandPlan plan) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO command_plans (incident_id, plan_key, status, version, created_by,"
                            + " created_at, updated_at, cancelled_by, cancelled_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, plan.incidentId());
            ps.setString(2, plan.planKey());
            ps.setString(3, plan.status().name());
            ps.setInt(4, plan.version());
            ps.setString(5, plan.createdBy());
            ps.setTimestamp(6, Timestamp.from(plan.createdAt()));
            ps.setTimestamp(7, Timestamp.from(plan.updatedAt()));
            ps.setString(8, plan.cancelledBy());
            ps.setTimestamp(9, plan.cancelledAt() == null ? null : Timestamp.from(plan.cancelledAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按事件与 planKey 查询计划，用于幂等比对与明细查询。
     */
    public Optional<CommandPlan> findPlanByKey(long incidentId, String planKey) {
        List<CommandPlan> rows = jdbc.query(
                "SELECT * FROM command_plans WHERE incident_id = ? AND plan_key = ?",
                PLAN_MAPPER, incidentId, planKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部计划，按创建顺序返回。
     */
    public List<CommandPlan> listPlansByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM command_plans WHERE incident_id = ? ORDER BY id",
                PLAN_MAPPER, incidentId);
    }

    /**
     * 查询阻止事件解决的计划（RUNNING/COMPENSATING），按创建顺序返回。
     */
    public List<CommandPlan> listBlockingResolve(long incidentId) {
        return jdbc.query("SELECT * FROM command_plans WHERE incident_id = ?"
                + " AND status IN ('RUNNING','COMPENSATING') ORDER BY id", PLAN_MAPPER, incidentId);
    }

    /**
     * 更新计划状态与版本（执行/取消/补偿成功时调用），同步更新变更时刻。
     * cancelledBy/cancelledAt 仅在取消流转时非空，其余场景传原值。
     */
    public void updatePlanState(long id, PlanStatus status, int version, String cancelledBy,
                                Instant cancelledAt, Instant updatedAt) {
        jdbc.update("UPDATE command_plans SET status = ?, version = ?, cancelled_by = ?,"
                        + " cancelled_at = ?, updated_at = ? WHERE id = ?",
                status.name(), version, cancelledBy,
                cancelledAt == null ? null : Timestamp.from(cancelledAt),
                Timestamp.from(updatedAt), id);
    }

    /**
     * 插入步骤（初始 PENDING），返回生成主键。(plan_id, step_key) 唯一。
     */
    public long insertStep(CommandPlanStep step) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO command_plan_steps (plan_id, step_key, seq, effect_text,"
                            + " compensation_text, status, applied_by, applied_at, compensated_by,"
                            + " compensated_at, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, step.planId());
            ps.setString(2, step.stepKey());
            ps.setInt(3, step.seq());
            ps.setString(4, step.effectText());
            ps.setString(5, step.compensationText());
            ps.setString(6, step.status().name());
            ps.setString(7, step.appliedBy());
            ps.setTimestamp(8, step.appliedAt() == null ? null : Timestamp.from(step.appliedAt()));
            ps.setString(9, step.compensatedBy());
            ps.setTimestamp(10, step.compensatedAt() == null ? null
                    : Timestamp.from(step.compensatedAt()));
            ps.setTimestamp(11, Timestamp.from(step.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询计划全部步骤，按执行顺序（seq）返回。
     */
    public List<CommandPlanStep> listSteps(long planId) {
        return jdbc.query("SELECT * FROM command_plan_steps WHERE plan_id = ? ORDER BY seq",
                STEP_MAPPER, planId);
    }

    /**
     * 将步骤置为 APPLIED，记录执行人与 UTC 时刻。
     */
    public void markStepApplied(long stepId, String actor, Instant at) {
        jdbc.update("UPDATE command_plan_steps SET status = 'APPLIED', applied_by = ?,"
                        + " applied_at = ? WHERE id = ?",
                actor, Timestamp.from(at), stepId);
    }

    /**
     * 将步骤置为 COMPENSATED，记录补偿人与 UTC 时刻。
     */
    public void markStepCompensated(long stepId, String actor, Instant at) {
        jdbc.update("UPDATE command_plan_steps SET status = 'COMPENSATED', compensated_by = ?,"
                        + " compensated_at = ? WHERE id = ?",
                actor, Timestamp.from(at), stepId);
    }

    /**
     * 追加一条补偿证据（每次补偿尝试均记录，成功或失败），返回生成主键。
     */
    public long insertCompensation(CommandPlanCompensation comp) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO command_plan_compensations (plan_id, step_id, step_key, evidence,"
                            + " outcome, diagnostics, actor, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, comp.planId());
            ps.setLong(2, comp.stepId());
            ps.setString(3, comp.stepKey());
            ps.setString(4, comp.evidence());
            ps.setString(5, comp.outcome());
            ps.setString(6, comp.diagnostics());
            ps.setString(7, comp.actor());
            ps.setTimestamp(8, Timestamp.from(comp.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询计划全部补偿证据，按记录顺序返回。
     */
    public List<CommandPlanCompensation> listCompensations(long planId) {
        return jdbc.query("SELECT * FROM command_plan_compensations WHERE plan_id = ? ORDER BY id",
                COMP_MAPPER, planId);
    }
}
