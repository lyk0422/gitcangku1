package com.example.starter.plan.repo;

import com.example.starter.plan.model.ConnectionRisk;
import com.example.starter.plan.model.ConnectionStatus;
import com.example.starter.plan.model.GuaranteeDecision;
import com.example.starter.plan.model.PlanConnectionState;
import com.example.starter.plan.model.RailConnection;
import com.example.starter.plan.model.RiskStatus;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 接续关系、风险判定历史、风险标记与保障改签决策的 JDBC 持久化。
 * 所有时刻以 UTC 毫秒存储；风险与决策记录只追加不改写。
 */
@Repository
public class ConnectionRepository {

    private static final String CONNECTION_COLUMNS = "id, connection_key, predecessor_plan_id,"
            + " successor_plan_id, guarantee_minutes, cross_midnight, status,"
            + " reg_predecessor_version, reg_arrival_utc, reg_departure_utc";

    private static final RowMapper<RailConnection> CONNECTION_MAPPER = (rs, n) -> new RailConnection(
            rs.getLong("id"),
            rs.getString("connection_key"),
            rs.getLong("predecessor_plan_id"),
            rs.getLong("successor_plan_id"),
            rs.getInt("guarantee_minutes"),
            rs.getBoolean("cross_midnight"),
            ConnectionStatus.valueOf(rs.getString("status")),
            rs.getInt("reg_predecessor_version"),
            rs.getLong("reg_arrival_utc"),
            rs.getLong("reg_departure_utc"));

    private static final RowMapper<ConnectionRisk> RISK_MAPPER = (rs, n) -> new ConnectionRisk(
            rs.getLong("id"),
            rs.getLong("connection_id"),
            rs.getLong("predecessor_plan_id"),
            rs.getLong("successor_plan_id"),
            rs.getInt("predecessor_version"),
            Instant.ofEpochMilli(rs.getLong("old_arrival_utc")),
            Instant.ofEpochMilli(rs.getLong("new_arrival_utc")),
            Instant.ofEpochMilli(rs.getLong("old_departure_utc")),
            Instant.ofEpochMilli(rs.getLong("new_departure_utc")),
            rs.getInt("guarantee_minutes"),
            rs.getLong("actual_minutes"),
            rs.getLong("shortfall_minutes"),
            Instant.ofEpochMilli(rs.getLong("window_start_utc")),
            Instant.ofEpochMilli(rs.getLong("window_end_utc")),
            rs.getBoolean("marked"),
            rs.getLong("created_at"));

    private static final RowMapper<GuaranteeDecision> DECISION_MAPPER = (rs, n) -> new GuaranteeDecision(
            rs.getLong("id"),
            rs.getString("batch_key"),
            rs.getLong("connection_id"),
            rs.getLong("predecessor_plan_id"),
            rs.getLong("successor_plan_id"),
            rs.getInt("predecessor_version"),
            Instant.ofEpochMilli(rs.getLong("old_departure_utc")),
            Instant.ofEpochMilli(rs.getLong("new_departure_utc")),
            rs.getInt("shift_minutes"),
            rs.getLong("shortfall_before_minutes"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public ConnectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入接续关系（状态 ACTIVE），返回自增主键。
     */
    public long insertConnection(String connectionKey, long predecessorPlanId, long successorPlanId,
                                 int guaranteeMinutes, boolean crossMidnight,
                                 int regPredecessorVersion, long regArrivalUtc, long regDepartureUtc,
                                 long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_connection (connection_key, predecessor_plan_id, successor_plan_id,"
                            + " guarantee_minutes, cross_midnight, status, reg_predecessor_version,"
                            + " reg_arrival_utc, reg_departure_utc, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, connectionKey);
            ps.setLong(2, predecessorPlanId);
            ps.setLong(3, successorPlanId);
            ps.setInt(4, guaranteeMinutes);
            ps.setBoolean(5, crossMidnight);
            ps.setInt(6, regPredecessorVersion);
            ps.setLong(7, regArrivalUtc);
            ps.setLong(8, regDepartureUtc);
            ps.setLong(9, nowMillis);
            ps.setLong(10, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按主键查询接续（不加锁）。
     */
    public Optional<RailConnection> findById(long id) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection WHERE id = ?",
                CONNECTION_MAPPER, id).stream().findFirst();
    }

    /**
     * 按业务键查询接续（不加锁）。
     */
    public Optional<RailConnection> findByKey(String connectionKey) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE connection_key = ?",
                CONNECTION_MAPPER, connectionKey).stream().findFirst();
    }

    /**
     * 按业务键查询接续并加行级写锁，须在事务内调用。
     */
    public Optional<RailConnection> findByKeyForUpdate(String connectionKey) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE connection_key = ? FOR UPDATE",
                CONNECTION_MAPPER, connectionKey).stream().findFirst();
    }

    /**
     * 查询指定前车计划的全部生效接续。
     */
    public List<RailConnection> findActiveByPredecessor(long predecessorPlanId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE predecessor_plan_id = ? AND status = 'ACTIVE' ORDER BY id",
                CONNECTION_MAPPER, predecessorPlanId);
    }

    /**
     * 查询指定后车计划的全部生效接续。
     */
    public List<RailConnection> findActiveBySuccessor(long successorPlanId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE successor_plan_id = ? AND status = 'ACTIVE' ORDER BY id",
                CONNECTION_MAPPER, successorPlanId);
    }
    /**
     * 查询同一前后车组合的生效接续（用于重复登记裁决）。
     */
    public Optional<RailConnection> findActivePair(long predecessorPlanId, long successorPlanId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE predecessor_plan_id = ? AND successor_plan_id = ? AND status = 'ACTIVE'",
                CONNECTION_MAPPER, predecessorPlanId, successorPlanId).stream().findFirst();
    }

    /**
     * 查询指定计划参与的全部接续（含已取消历史，任一端均可），按创建顺序。
     */
    public List<RailConnection> findAllByPlan(long planId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE predecessor_plan_id = ? OR successor_plan_id = ?"
                        + " ORDER BY created_at, id",
                CONNECTION_MAPPER, planId, planId);
    }

    /**
     * 更新接续状态（取消）。
     */
    public void updateStatus(long id, ConnectionStatus status, long nowMillis) {
        jdbc.update("UPDATE rail_connection SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), nowMillis, id);
    }

    /**
     * 前车改签后接续指向新计划。
     */
    public void updatePredecessor(long id, long newPredecessorPlanId, long nowMillis) {
        jdbc.update("UPDATE rail_connection SET predecessor_plan_id = ?, updated_at = ? WHERE id = ?",
                newPredecessorPlanId, nowMillis, id);
    }

    /**
     * 后车改签后接续指向新计划。
     */
    public void updateSuccessor(long id, long newSuccessorPlanId, long nowMillis) {
        jdbc.update("UPDATE rail_connection SET successor_plan_id = ?, updated_at = ? WHERE id = ?",
                newSuccessorPlanId, nowMillis, id);
    }

    /**
     * 追加风险判定历史（不可变）。
     */
    public void insertRisk(ConnectionRisk risk) {
        jdbc.update("INSERT INTO rail_connection_risk (connection_id, predecessor_plan_id,"
                        + " successor_plan_id, predecessor_version, old_arrival_utc, new_arrival_utc,"
                        + " old_departure_utc, new_departure_utc, guarantee_minutes, actual_minutes,"
                        + " shortfall_minutes, window_start_utc, window_end_utc, marked, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                risk.connectionId(), risk.predecessorPlanId(), risk.successorPlanId(),
                risk.predecessorVersion(), risk.oldArrivalUtc().toEpochMilli(),
                risk.newArrivalUtc().toEpochMilli(), risk.oldDepartureUtc().toEpochMilli(),
                risk.newDepartureUtc().toEpochMilli(), risk.guaranteeMinutes(),
                risk.actualMinutes(), risk.shortfallMinutes(),
                risk.windowStartUtc().toEpochMilli(), risk.windowEndUtc().toEpochMilli(),
                risk.marked(), risk.createdAtMillis());
    }

    /**
     * 查询指定后车计划的风险判定历史，按判定顺序（只读，不改写）。
     */
    public List<ConnectionRisk> findRisksBySuccessor(long successorPlanId) {
        return jdbc.query("SELECT id, connection_id, predecessor_plan_id, successor_plan_id,"
                        + " predecessor_version, old_arrival_utc, new_arrival_utc, old_departure_utc,"
                        + " new_departure_utc, guarantee_minutes, actual_minutes, shortfall_minutes,"
                        + " window_start_utc, window_end_utc, marked, created_at"
                        + " FROM rail_connection_risk WHERE successor_plan_id = ?"
                        + " ORDER BY created_at, id",
                RISK_MAPPER, successorPlanId);
    }

    /**
     * 查询计划当前风险标记。
     */
    public Optional<PlanConnectionState> findState(long planId) {
        return jdbc.query("SELECT plan_id, risk_status, risk_connection_id"
                        + " FROM rail_plan_connection_state WHERE plan_id = ?",
                (rs, n) -> new PlanConnectionState(
                        rs.getLong("plan_id"),
                        RiskStatus.valueOf(rs.getString("risk_status")),
                        (Long) rs.getObject("risk_connection_id")),
                planId).stream().findFirst();
    }

    /**
     * 写入或更新计划风险标记（MERGE 按主键）。
     */
    public void upsertState(long planId, RiskStatus riskStatus, Long riskConnectionId, long nowMillis) {
        jdbc.update("MERGE INTO rail_plan_connection_state"
                        + " (plan_id, risk_status, risk_connection_id, updated_at)"
                        + " KEY(plan_id) VALUES (?, ?, ?, ?)",
                planId, riskStatus.name(), riskConnectionId, nowMillis);
    }

    /**
     * 追加保障改签决策历史（不可变）。
     */
    public void insertDecision(GuaranteeDecision decision) {
        jdbc.update("INSERT INTO rail_guarantee_decision (batch_key, connection_id,"
                        + " predecessor_plan_id, successor_plan_id, predecessor_version,"
                        + " old_departure_utc, new_departure_utc, shift_minutes,"
                        + " shortfall_before_minutes, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                decision.batchKey(), decision.connectionId(), decision.predecessorPlanId(),
                decision.successorPlanId(), decision.predecessorVersion(),
                decision.oldDepartureUtc().toEpochMilli(), decision.newDepartureUtc().toEpochMilli(),
                decision.shiftMinutes(), decision.shortfallBeforeMinutes(), decision.createdAtMillis());
    }

    /**
     * 查询指定后车计划的保障改签决策历史，按决策顺序（只读，不改写）。
     */
    public List<GuaranteeDecision> findDecisionsBySuccessor(long successorPlanId) {
        return jdbc.query("SELECT id, batch_key, connection_id, predecessor_plan_id,"
                        + " successor_plan_id, predecessor_version, old_departure_utc,"
                        + " new_departure_utc, shift_minutes, shortfall_before_minutes, created_at"
                        + " FROM rail_guarantee_decision WHERE successor_plan_id = ?"
                        + " ORDER BY created_at, id",
                DECISION_MAPPER, successorPlanId);
    }
}