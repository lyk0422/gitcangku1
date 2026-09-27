package com.example.starter.plan.repo;

import com.example.starter.plan.model.Connection;
import com.example.starter.plan.model.ConnectionDecision;
import com.example.starter.plan.model.ConnectionRisk;
import com.example.starter.plan.model.ConnectionStatus;
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
 * 接续登记、风险判定证据与批量保障改签决策的 JDBC 持久化。
 * 风险与决策记录只插不改；所有时刻以 UTC 毫秒存储。
 */
@Repository
public class ConnectionRepository {

    private static final String CONNECTION_COLS = "id, predecessor_plan_id, successor_plan_id,"
            + " transfer_section_id, guarantee_minutes, predecessor_version, cross_midnight,"
            + " status, created_at, cancelled_at";

    private static final RowMapper<Connection> CONNECTION_MAPPER = (rs, n) -> new Connection(
            rs.getLong("id"),
            rs.getLong("predecessor_plan_id"),
            rs.getLong("successor_plan_id"),
            rs.getString("transfer_section_id"),
            rs.getInt("guarantee_minutes"),
            rs.getInt("predecessor_version"),
            rs.getBoolean("cross_midnight"),
            ConnectionStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at"),
            rs.getObject("cancelled_at", Long.class));

    private static final RowMapper<ConnectionRisk> RISK_MAPPER = (rs, n) -> new ConnectionRisk(
            rs.getLong("id"),
            rs.getLong("connection_id"),
            rs.getLong("predecessor_plan_id"),
            rs.getLong("successor_plan_id"),
            rs.getLong("predecessor_old_plan_id"),
            rs.getLong("predecessor_new_plan_id"),
            rs.getInt("predecessor_base_version"),
            rs.getInt("predecessor_rescheduled_version"),
            Instant.ofEpochMilli(rs.getLong("old_pred_arrival_utc")),
            Instant.ofEpochMilli(rs.getLong("new_pred_arrival_utc")),
            Instant.ofEpochMilli(rs.getLong("old_successor_departure_utc")),
            toInstant(rs.getObject("new_successor_departure_utc", Long.class)),
            rs.getInt("guarantee_minutes"),
            rs.getInt("required_gap_minutes"),
            rs.getLong("actual_gap_minutes"),
            rs.getLong("shortfall_minutes"),
            toInstant(rs.getObject("candidate_window_start_utc", Long.class)),
            toInstant(rs.getObject("candidate_window_end_utc", Long.class)),
            rs.getBoolean("candidate_available"),
            rs.getBoolean("successor_started"),
            rs.getLong("created_at"));

    private static final RowMapper<ConnectionDecision> DECISION_MAPPER = (rs, n) -> new ConnectionDecision(
            rs.getLong("id"),
            rs.getString("batch_request_key"),
            rs.getObject("successor_plan_id", Long.class),
            rs.getObject("old_successor_plan_id", Long.class),
            rs.getObject("new_successor_plan_id", Long.class),
            rs.getObject("connection_id", Long.class),
            rs.getObject("predecessor_plan_id", Long.class),
            rs.getObject("successor_base_version", Integer.class),
            toInstant(rs.getObject("old_departure_utc", Long.class)),
            toInstant(rs.getObject("new_departure_utc", Long.class)),
            toInstant(rs.getObject("predecessor_arrival_utc", Long.class)),
            rs.getObject("guarantee_minutes", Integer.class),
            rs.getObject("required_gap_minutes", Integer.class),
            rs.getObject("actual_gap_minutes", Long.class),
            rs.getObject("shortfall_minutes", Long.class),
            rs.getString("item_result"),
            rs.getString("reason_code"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public ConnectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(Long epochMilli) {
        return epochMilli == null ? null : Instant.ofEpochMilli(epochMilli);
    }

    /**
     * 插入接续登记（ACTIVE），返回自增主键；同一对前后车唯一冲突时抛 DuplicateKeyException。
     */
    public long insertConnection(long predecessorPlanId, long successorPlanId, String transferSectionId,
                                 int guaranteeMinutes, int predecessorVersion, boolean crossMidnight,
                                 long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_connection (predecessor_plan_id, successor_plan_id,"
                            + " transfer_section_id, guarantee_minutes, predecessor_version,"
                            + " cross_midnight, status, created_at, cancelled_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, predecessorPlanId);
            ps.setLong(2, successorPlanId);
            ps.setString(3, transferSectionId);
            ps.setInt(4, guaranteeMinutes);
            ps.setInt(5, predecessorVersion);
            ps.setBoolean(6, crossMidnight);
            ps.setLong(7, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按主键查询接续登记。
     */
    public Optional<Connection> findById(long connectionId) {
        return jdbc.query("SELECT " + CONNECTION_COLS + " FROM rail_connection WHERE id = ?",
                CONNECTION_MAPPER, connectionId).stream().findFirst();
    }

    /**
     * 查询以前车计划登记的全部生效接续。
     */
    public List<Connection> findActiveByPredecessor(long predecessorPlanId) {
        return jdbc.query("SELECT " + CONNECTION_COLS + " FROM rail_connection"
                        + " WHERE predecessor_plan_id = ? AND status = 'ACTIVE' ORDER BY id",
                CONNECTION_MAPPER, predecessorPlanId);
    }

    /**
     * 查询以后车计划登记的全部生效接续。
     */
    public List<Connection> findActiveBySuccessor(long successorPlanId) {
        return jdbc.query("SELECT " + CONNECTION_COLS + " FROM rail_connection"
                        + " WHERE successor_plan_id = ? AND status = 'ACTIVE' ORDER BY id",
                CONNECTION_MAPPER, successorPlanId);
    }

    /**
     * 查询指定计划（任一侧）的全部接续（含已取消），按登记顺序。
     */
    public List<Connection> findByPlan(long planId) {
        return jdbc.query("SELECT " + CONNECTION_COLS + " FROM rail_connection"
                        + " WHERE predecessor_plan_id = ? OR successor_plan_id = ? ORDER BY id",
                CONNECTION_MAPPER, planId, planId);
    }

    /**
     * 取消接续登记（仅状态翻转，历史保留）。
     */
    public void cancelConnection(long connectionId, long nowMillis) {
        jdbc.update("UPDATE rail_connection SET status = 'CANCELLED', cancelled_at = ? WHERE id = ?",
                nowMillis, connectionId);
    }

    /**
     * 追加风险判定证据（不可变）。
     */
    public void insertRisk(ConnectionRisk risk) {
        jdbc.update("INSERT INTO rail_connection_risk (connection_id, predecessor_plan_id,"
                        + " successor_plan_id, predecessor_old_plan_id, predecessor_new_plan_id,"
                        + " predecessor_base_version, predecessor_rescheduled_version,"
                        + " old_pred_arrival_utc, new_pred_arrival_utc, old_successor_departure_utc,"
                        + " new_successor_departure_utc, guarantee_minutes, required_gap_minutes,"
                        + " actual_gap_minutes, shortfall_minutes, candidate_window_start_utc,"
                        + " candidate_window_end_utc, candidate_available, successor_started, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                risk.connectionId(), risk.predecessorPlanId(), risk.successorPlanId(),
                risk.predecessorOldPlanId(), risk.predecessorNewPlanId(),
                risk.predecessorBaseVersion(), risk.predecessorRescheduledVersion(),
                risk.oldPredArrivalUtc().toEpochMilli(), risk.newPredArrivalUtc().toEpochMilli(),
                risk.oldSuccessorDepartureUtc().toEpochMilli(),
                risk.newSuccessorDepartureUtc() == null ? null
                        : risk.newSuccessorDepartureUtc().toEpochMilli(),
                risk.guaranteeMinutes(), risk.requiredGapMinutes(), risk.actualGapMinutes(),
                risk.shortfallMinutes(),
                risk.candidateWindowStartUtc() == null ? null
                        : risk.candidateWindowStartUtc().toEpochMilli(),
                risk.candidateWindowEndUtc() == null ? null
                        : risk.candidateWindowEndUtc().toEpochMilli(),
                risk.candidateAvailable(), risk.successorStarted(), risk.createdAt());
    }

    /**
     * 查询后车计划的全部风险记录，按生成顺序（id 升序，稳定排序）。
     */
    public List<ConnectionRisk> findRisksBySuccessor(long successorPlanId) {
        return jdbc.query("SELECT * FROM rail_connection_risk WHERE successor_plan_id = ? ORDER BY id",
                RISK_MAPPER, successorPlanId);
    }

    /**
     * 查询接续登记的全部风险记录，按生成顺序。
     */
    public List<ConnectionRisk> findRisksByConnection(long connectionId) {
        return jdbc.query("SELECT * FROM rail_connection_risk WHERE connection_id = ? ORDER BY id",
                RISK_MAPPER, connectionId);
    }

    /**
     * 追加批量保障改签决策证据（不可变）。
     */
    public void insertDecision(ConnectionDecision d) {
        jdbc.update("INSERT INTO rail_connection_decision (batch_request_key, successor_plan_id,"
                        + " old_successor_plan_id, new_successor_plan_id, connection_id,"
                        + " predecessor_plan_id, successor_base_version, old_departure_utc,"
                        + " new_departure_utc, predecessor_arrival_utc, guarantee_minutes,"
                        + " required_gap_minutes, actual_gap_minutes, shortfall_minutes,"
                        + " item_result, reason_code, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                d.batchRequestKey(), d.successorPlanId(), d.oldSuccessorPlanId(),
                d.newSuccessorPlanId(), d.connectionId(), d.predecessorPlanId(),
                d.successorBaseVersion(),
                d.oldDepartureUtc() == null ? null : d.oldDepartureUtc().toEpochMilli(),
                d.newDepartureUtc() == null ? null : d.newDepartureUtc().toEpochMilli(),
                d.predecessorArrivalUtc() == null ? null : d.predecessorArrivalUtc().toEpochMilli(),
                d.guaranteeMinutes(), d.requiredGapMinutes(), d.actualGapMinutes(),
                d.shortfallMinutes(), d.itemResult(), d.reasonCode(), d.createdAt());
    }

    /**
     * 查询后车计划相关的全部决策记录，按生成顺序。
     */
    public List<ConnectionDecision> findDecisionsBySuccessorPlan(long planId) {
        return jdbc.query("SELECT * FROM rail_connection_decision"
                        + " WHERE successor_plan_id = ? OR old_successor_plan_id = ?"
                        + " OR new_successor_plan_id = ? ORDER BY id",
                DECISION_MAPPER, planId, planId, planId);
    }

    /**
     * 查询指定批次的全部决策记录，按生成顺序。
     */
    public List<ConnectionDecision> findDecisionsByBatch(String batchRequestKey) {
        return jdbc.query("SELECT * FROM rail_connection_decision"
                        + " WHERE batch_request_key = ? ORDER BY id",
                DECISION_MAPPER, batchRequestKey);
    }
}
