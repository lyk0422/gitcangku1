package com.example.starter.plan.repo;

import com.example.starter.plan.model.Connection;
import com.example.starter.plan.model.ConnectionRisk;
import com.example.starter.plan.model.ConnectionStatus;
import com.example.starter.plan.model.GuaranteeDecision;
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
 * 接续关系、接续风险记录与保障改签决策的 JDBC 持久化。
 * 风险记录与决策记录为追加式历史，只插不改；时刻均为 UTC 毫秒。
 */
@Repository
public class ConnectionRepository {

    private static final String CONNECTION_COLUMNS = "id, predecessor_plan_id, successor_plan_id,"
            + " transfer_station, guarantee_minutes, cross_midnight, predecessor_version,"
            + " status, created_at, cancelled_at";

    private static final RowMapper<Connection> CONNECTION_MAPPER = (rs, n) -> new Connection(
            rs.getLong("id"),
            rs.getLong("predecessor_plan_id"),
            rs.getLong("successor_plan_id"),
            rs.getString("transfer_station"),
            rs.getInt("guarantee_minutes"),
            rs.getBoolean("cross_midnight"),
            rs.getInt("predecessor_version"),
            ConnectionStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at"),
            rs.getObject("cancelled_at") == null ? null : rs.getLong("cancelled_at"));

    private static final RowMapper<ConnectionRisk> RISK_MAPPER = (rs, n) -> new ConnectionRisk(
            rs.getLong("id"),
            rs.getLong("connection_id"),
            rs.getLong("predecessor_plan_id"),
            rs.getInt("predecessor_version"),
            rs.getLong("successor_plan_id"),
            Instant.ofEpochMilli(rs.getLong("predecessor_old_arrival_utc")),
            Instant.ofEpochMilli(rs.getLong("predecessor_new_arrival_utc")),
            rs.getInt("required_minutes"),
            rs.getLong("actual_minutes"),
            rs.getLong("deficit_minutes"),
            Instant.ofEpochMilli(rs.getLong("window_start_utc")),
            Instant.ofEpochMilli(rs.getLong("window_end_utc")),
            rs.getLong("created_at"));

    private static final RowMapper<GuaranteeDecision> DECISION_MAPPER = (rs, n) -> new GuaranteeDecision(
            rs.getLong("id"),
            rs.getString("batch_key"),
            rs.getLong("successor_plan_id"),
            rs.getInt("successor_version"),
            rs.getInt("predecessor_version"),
            Instant.ofEpochMilli(rs.getLong("old_first_start_utc")),
            Instant.ofEpochMilli(rs.getLong("new_first_start_utc")),
            rs.getLong("shift_minutes"),
            rs.getLong("deficit_minutes"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public ConnectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入接续关系（ACTIVE），返回自增主键。
     */
    public long insertConnection(long predecessorPlanId, long successorPlanId, String transferStation,
                                 int guaranteeMinutes, boolean crossMidnight, int predecessorVersion,
                                 long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_connection (predecessor_plan_id, successor_plan_id,"
                            + " transfer_station, guarantee_minutes, cross_midnight,"
                            + " predecessor_version, status, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, predecessorPlanId);
            ps.setLong(2, successorPlanId);
            ps.setString(3, transferStation);
            ps.setInt(4, guaranteeMinutes);
            ps.setBoolean(5, crossMidnight);
            ps.setInt(6, predecessorVersion);
            ps.setLong(7, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按主键查询接续（不加锁）。
     */
    public Optional<Connection> findById(long connectionId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection WHERE id = ?",
                CONNECTION_MAPPER, connectionId).stream().findFirst();
    }

    /**
     * 按主键查询接续并加行级写锁，须在事务内调用。
     */
    public Optional<Connection> findByIdForUpdate(long connectionId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE id = ? FOR UPDATE",
                CONNECTION_MAPPER, connectionId).stream().findFirst();
    }

    /**
     * 查询以前车为直接前驱的生效接续。
     */
    public List<Connection> findActiveByPredecessor(long predecessorPlanId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE predecessor_plan_id = ? AND status = 'ACTIVE' ORDER BY id",
                CONNECTION_MAPPER, predecessorPlanId);
    }

    /**
     * 查询后车全部生效接续（风险重估与批量保障改签使用）。
     */
    public List<Connection> findActiveBySuccessor(long successorPlanId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE successor_plan_id = ? AND status = 'ACTIVE' ORDER BY id",
                CONNECTION_MAPPER, successorPlanId);
    }

    /**
     * 查询指定前后车对之间的生效接续（重复登记判定）。
     */
    public Optional<Connection> findActiveByPair(long predecessorPlanId, long successorPlanId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE predecessor_plan_id = ? AND successor_plan_id = ?"
                        + " AND status = 'ACTIVE'",
                CONNECTION_MAPPER, predecessorPlanId, successorPlanId).stream().findFirst();
    }

    /**
     * 查询计划参与的全部接续（作为前车或后车），按 id 升序，含已取消历史。
     */
    public List<Connection> findByPlan(long planId) {
        return jdbc.query("SELECT " + CONNECTION_COLUMNS + " FROM rail_connection"
                        + " WHERE predecessor_plan_id = ? OR successor_plan_id = ? ORDER BY id",
                CONNECTION_MAPPER, planId, planId);
    }

    /**
     * 前车改签后接续随链指向新计划。
     */
    public void updatePredecessor(long connectionId, long newPredecessorPlanId) {
        jdbc.update("UPDATE rail_connection SET predecessor_plan_id = ? WHERE id = ?",
                newPredecessorPlanId, connectionId);
    }

    /**
     * 取消接续：状态置 CANCELLED 并记录取消时刻，历史字段不改写。
     */
    public void cancel(long connectionId, long nowMillis) {
        jdbc.update("UPDATE rail_connection SET status = 'CANCELLED', cancelled_at = ? WHERE id = ?",
                nowMillis, connectionId);
    }

    /**
     * 追加接续风险记录（历史，只插不改）。
     */
    public void insertRisk(ConnectionRisk risk) {
        jdbc.update("INSERT INTO rail_connection_risk (connection_id, predecessor_plan_id,"
                        + " predecessor_version, successor_plan_id, predecessor_old_arrival_utc,"
                        + " predecessor_new_arrival_utc, required_minutes, actual_minutes,"
                        + " deficit_minutes, window_start_utc, window_end_utc, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                risk.connectionId(), risk.predecessorPlanId(), risk.predecessorVersion(),
                risk.successorPlanId(), risk.predecessorOldArrivalUtc().toEpochMilli(),
                risk.predecessorNewArrivalUtc().toEpochMilli(), risk.requiredMinutes(),
                risk.actualMinutes(), risk.deficitMinutes(),
                risk.windowStartUtc().toEpochMilli(), risk.windowEndUtc().toEpochMilli(),
                risk.createdAt());
    }

    /**
     * 查询后车全部风险历史，按 id 升序（时间顺序）。
     */
    public List<ConnectionRisk> findRisksBySuccessor(long successorPlanId) {
        return jdbc.query("SELECT id, connection_id, predecessor_plan_id, predecessor_version,"
                        + " successor_plan_id, predecessor_old_arrival_utc, predecessor_new_arrival_utc,"
                        + " required_minutes, actual_minutes, deficit_minutes, window_start_utc,"
                        + " window_end_utc, created_at"
                        + " FROM rail_connection_risk WHERE successor_plan_id = ? ORDER BY id",
                RISK_MAPPER, successorPlanId);
    }

    /**
     * 追加保障改签决策记录（历史，只插不改）。
     */
    public void insertDecision(GuaranteeDecision decision) {
        jdbc.update("INSERT INTO rail_guarantee_decision (batch_key, successor_plan_id,"
                        + " successor_version, predecessor_version, old_first_start_utc,"
                        + " new_first_start_utc, shift_minutes, deficit_minutes, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                decision.batchKey(), decision.successorPlanId(), decision.successorVersion(),
                decision.predecessorVersion(), decision.oldFirstStartUtc().toEpochMilli(),
                decision.newFirstStartUtc().toEpochMilli(), decision.shiftMinutes(),
                decision.deficitMinutes(), decision.createdAt());
    }

    /**
     * 查询后车全部保障改签决策历史，按 id 升序（时间顺序）。
     */
    public List<GuaranteeDecision> findDecisionsBySuccessor(long successorPlanId) {
        return jdbc.query("SELECT id, batch_key, successor_plan_id, successor_version,"
                        + " predecessor_version, old_first_start_utc, new_first_start_utc,"
                        + " shift_minutes, deficit_minutes, created_at"
                        + " FROM rail_guarantee_decision WHERE successor_plan_id = ? ORDER BY id",
                DECISION_MAPPER, successorPlanId);
    }
}
