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
 * 事件及关联表（处置记录、交接单、状态历史）的 JDBC 仓储。
 * 所有写路径先以 FOR UPDATE 锁定事件行，保证同一事件的并发写按事务提交顺序生效。
 */
@Repository
public class IncidentRepository {

    private final JdbcTemplate jdbc;

    public IncidentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Incident> INCIDENT_MAPPER = (rs, n) -> mapIncident(rs);
    private static final RowMapper<IncidentAction> ACTION_MAPPER = (rs, n) -> new IncidentAction(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getString("action_key"),
            rs.getString("action_type"), rs.getString("note"),
            rs.getTimestamp("occurred_at").toInstant(), rs.getString("actor"),
            rs.getTimestamp("created_at").toInstant());
    private static final RowMapper<IncidentTransfer> TRANSFER_MAPPER = (rs, n) -> new IncidentTransfer(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getString("from_commander"),
            rs.getString("to_commander"), TransferStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("accepted_at") == null ? null : rs.getTimestamp("accepted_at").toInstant());
    private static final RowMapper<StatusChange> STATUS_MAPPER = (rs, n) -> new StatusChange(
            rs.getLong("id"), rs.getLong("incident_id"),
            rs.getString("from_status") == null ? null : IncidentStatus.valueOf(rs.getString("from_status")),
            IncidentStatus.valueOf(rs.getString("to_status")),
            rs.getString("actor"), rs.getTimestamp("occurred_at").toInstant());
    private static final RowMapper<Escalation> ESCALATION_MAPPER = (rs, n) -> {
        Timestamp acknowledgedAt = rs.getTimestamp("acknowledged_at");
        return new Escalation(rs.getLong("id"), rs.getLong("incident_id"),
                EscalationStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("deadline").toInstant(),
                rs.getTimestamp("triggered_at").toInstant(),
                rs.getString("commander"),
                rs.getString("disposition_note"),
                rs.getString("acknowledged_by"),
                acknowledgedAt == null ? null : acknowledgedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    };

    private static Incident mapIncident(ResultSet rs) throws SQLException {
        Timestamp deadline = rs.getTimestamp("containment_deadline");
        return new Incident(rs.getLong("id"), rs.getString("incident_key"), rs.getString("severity"),
                rs.getString("summary"), rs.getString("reporter"),
                IncidentStatus.valueOf(rs.getString("status")), rs.getString("commander"),
                deadline == null ? null : deadline.toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 按业务键查询事件（不加锁），用于只读场景。
     */
    public Optional<Incident> findByKey(String incidentKey) {
        List<Incident> rows = jdbc.query("SELECT * FROM incidents WHERE incident_key = ?",
                INCIDENT_MAPPER, incidentKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定事件行（SELECT ... FOR UPDATE），用于写路径串行化。
     */
    public Optional<Incident> lockByKey(String incidentKey) {
        List<Incident> rows = jdbc.query("SELECT * FROM incidents WHERE incident_key = ? FOR UPDATE",
                INCIDENT_MAPPER, incidentKey);
        return rows.stream().findFirst();
    }

    /**
     * 插入新事件，初始状态 REPORTED、无指挥人、无遏制期限，返回生成主键。
     */
    public long insert(Incident incident) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incidents (incident_key, severity, summary, reporter, status, commander,"
                            + " containment_deadline, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, incident.incidentKey());
            ps.setString(2, incident.severity());
            ps.setString(3, incident.summary());
            ps.setString(4, incident.reporter());
            ps.setString(5, incident.status().name());
            ps.setString(6, incident.commander());
            ps.setTimestamp(7, incident.containmentDeadline() == null
                    ? null : Timestamp.from(incident.containmentDeadline()));
            ps.setTimestamp(8, Timestamp.from(incident.createdAt()));
            ps.setTimestamp(9, Timestamp.from(incident.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 首次接管原子更新：置 COMMANDING、写入当前指挥人与永不重置的遏制期限。
     */
    public void takeover(long id, String commander, Instant containmentDeadline, Instant now) {
        jdbc.update("UPDATE incidents SET status = 'COMMANDING', commander = ?,"
                + " containment_deadline = ?, updated_at = ? WHERE id = ?",
                commander, Timestamp.from(containmentDeadline), Timestamp.from(now), id);
    }

    /**
     * 更新事件状态与指挥人（commander 可为 null 表示不变更时传入原值）。
     */
    public void updateState(long id, IncidentStatus status, String commander, Instant updatedAt) {
        jdbc.update("UPDATE incidents SET status = ?, commander = ?, updated_at = ? WHERE id = ?",
                status.name(), commander, Timestamp.from(updatedAt), id);
    }

    /**
     * 追加处置记录，返回生成主键。
     */
    public long insertAction(IncidentAction action) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_actions (incident_id, action_key, action_type, note, occurred_at,"
                            + " actor, created_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, action.incidentId());
            ps.setString(2, action.actionKey());
            ps.setString(3, action.actionType());
            ps.setString(4, action.note());
            ps.setTimestamp(5, Timestamp.from(action.occurredAt()));
            ps.setString(6, action.actor());
            ps.setTimestamp(7, Timestamp.from(action.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按事件与 actionKey 查询处置记录，用于幂等比对。
     */
    public Optional<IncidentAction> findAction(long incidentId, String actionKey) {
        List<IncidentAction> rows = jdbc.query(
                "SELECT * FROM incident_actions WHERE incident_id = ? AND action_key = ?",
                ACTION_MAPPER, incidentId, actionKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部处置记录，按落库顺序返回。
     */
    public List<IncidentAction> listActions(long incidentId) {
        return jdbc.query("SELECT * FROM incident_actions WHERE incident_id = ? ORDER BY id",
                ACTION_MAPPER, incidentId);
    }

    /**
     * 创建交接单（PENDING），返回生成主键。
     */
    public long insertTransfer(IncidentTransfer transfer) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_transfers (incident_id, from_commander, to_commander, status,"
                            + " created_at, accepted_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, transfer.incidentId());
            ps.setString(2, transfer.fromCommander());
            ps.setString(3, transfer.toCommander());
            ps.setString(4, transfer.status().name());
            ps.setTimestamp(5, Timestamp.from(transfer.createdAt()));
            ps.setTimestamp(6, transfer.acceptedAt() == null ? null : Timestamp.from(transfer.acceptedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件当前待接受的交接单。
     */
    public Optional<IncidentTransfer> findPendingTransfer(long incidentId) {
        List<IncidentTransfer> rows = jdbc.query(
                "SELECT * FROM incident_transfers WHERE incident_id = ? AND status = 'PENDING'",
                TRANSFER_MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 将交接单标记为已接受。
     */
    public void acceptTransfer(long transferId, Instant acceptedAt) {
        jdbc.update("UPDATE incident_transfers SET status = 'ACCEPTED', accepted_at = ? WHERE id = ?",
                Timestamp.from(acceptedAt), transferId);
    }

    /**
     * 查询事件全部交接单，按发起顺序返回。
     */
    public List<IncidentTransfer> listTransfers(long incidentId) {
        return jdbc.query("SELECT * FROM incident_transfers WHERE incident_id = ? ORDER BY id",
                TRANSFER_MAPPER, incidentId);
    }

    /**
     * 追加状态流转历史。
     */
    public void insertStatusChange(StatusChange change) {
        jdbc.update("INSERT INTO incident_status_history (incident_id, from_status, to_status, actor,"
                        + " occurred_at) VALUES (?,?,?,?,?)",
                change.incidentId(),
                change.fromStatus() == null ? null : change.fromStatus().name(),
                change.toStatus().name(), change.actor(), Timestamp.from(change.occurredAt()));
    }

    /**
     * 查询事件全部状态流转历史，按发生顺序返回。
     */
    public List<StatusChange> listStatusHistory(long incidentId) {
        return jdbc.query("SELECT * FROM incident_status_history WHERE incident_id = ? ORDER BY id",
                STATUS_MAPPER, incidentId);
    }

    /**
     * 查询事件唯一的升级记录；每个事件至多一条，无则 empty。
     */
    public Optional<Escalation> findEscalation(long incidentId) {
        List<Escalation> rows = jdbc.query(
                "SELECT * FROM incident_escalations WHERE incident_id = ?", ESCALATION_MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件升级记录并锁定行（SELECT ... FOR UPDATE），用于确认/取消路径串行化。
     */
    public Optional<Escalation> lockEscalation(long incidentId) {
        List<Escalation> rows = jdbc.query(
                "SELECT * FROM incident_escalations WHERE incident_id = ? FOR UPDATE",
                ESCALATION_MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部升级记录（当前至多一条），按落库顺序返回。
     */
    public List<Escalation> listEscalations(long incidentId) {
        return jdbc.query("SELECT * FROM incident_escalations WHERE incident_id = ? ORDER BY id",
                ESCALATION_MAPPER, incidentId);
    }

    /**
     * 追加 OPEN 升级记录，返回生成主键；incident_id 唯一约束保证每个事件至多一条。
     */
    public long insertEscalation(Escalation escalation) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_escalations (incident_id, status, deadline, triggered_at,"
                            + " commander, disposition_note, acknowledged_by, acknowledged_at, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, escalation.incidentId());
            ps.setString(2, escalation.status().name());
            ps.setTimestamp(3, Timestamp.from(escalation.deadline()));
            ps.setTimestamp(4, Timestamp.from(escalation.triggeredAt()));
            ps.setString(5, escalation.commander());
            ps.setString(6, escalation.dispositionNote());
            ps.setString(7, escalation.acknowledgedBy());
            ps.setTimestamp(8, escalation.acknowledgedAt() == null
                    ? null : Timestamp.from(escalation.acknowledgedAt()));
            ps.setTimestamp(9, Timestamp.from(escalation.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 将 OPEN 升级记录置为 ACKNOWLEDGED 并写入处置说明、确认人与确认时刻；仅在仍为 OPEN 时生效。
     *
     * @return 受影响行数：1 表示确认成功，0 表示记录已不在 OPEN 状态
     */
    public int acknowledgeEscalation(long escalationId, String dispositionNote, String acknowledgedBy,
                                     Instant acknowledgedAt) {
        return jdbc.update("UPDATE incident_escalations SET status = 'ACKNOWLEDGED', disposition_note = ?,"
                        + " acknowledged_by = ?, acknowledged_at = ? WHERE id = ? AND status = 'OPEN'",
                dispositionNote, acknowledgedBy, Timestamp.from(acknowledgedAt), escalationId);
    }

    /**
     * 遏制时把事件仍 OPEN 的升级记录原子置为 CANCELLED；已确认记录保留。
     *
     * @return 受影响行数（0 或 1，每事件至多一条）
     */
    public int cancelOpenEscalations(long incidentId) {
        return jdbc.update(
                "UPDATE incident_escalations SET status = 'CANCELLED' WHERE incident_id = ? AND status = 'OPEN'",
                incidentId);
    }
}
