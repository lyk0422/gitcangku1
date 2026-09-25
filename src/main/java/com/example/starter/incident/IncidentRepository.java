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

    private static Incident mapIncident(ResultSet rs) throws SQLException {
        Timestamp deadline = rs.getTimestamp("deadline_at");
        return new Incident(rs.getLong("id"), rs.getString("incident_key"), rs.getString("severity"),
                rs.getString("summary"), rs.getString("reporter"),
                IncidentStatus.valueOf(rs.getString("status")), rs.getString("commander"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                deadline == null ? null : deadline.toInstant());
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
                            + " created_at, updated_at, deadline_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, incident.incidentKey());
            ps.setString(2, incident.severity());
            ps.setString(3, incident.summary());
            ps.setString(4, incident.reporter());
            ps.setString(5, incident.status().name());
            ps.setString(6, incident.commander());
            ps.setTimestamp(7, Timestamp.from(incident.createdAt()));
            ps.setTimestamp(8, Timestamp.from(incident.updatedAt()));
            ps.setTimestamp(9, incident.deadlineAt() == null ? null : Timestamp.from(incident.deadlineAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 首次接管时写入遏制期限（只写一次，交接不重置），并递增事件版本号。
     */
    public void updateDeadline(long id, Instant deadlineAt, Instant updatedAt) {
        jdbc.update("UPDATE incidents SET deadline_at = ?, version = version + 1, updated_at = ?"
                        + " WHERE id = ?",
                Timestamp.from(deadlineAt), Timestamp.from(updatedAt), id);
    }

    /**
     * 更新事件状态与指挥人（commander 可为 null 表示不变更时传入原值），并递增事件版本号。
     */
    public void updateState(long id, IncidentStatus status, String commander, Instant updatedAt) {
        jdbc.update("UPDATE incidents SET status = ?, commander = ?, version = version + 1,"
                        + " updated_at = ? WHERE id = ?",
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
     * 查询任务的全部阻塞事件（含当前状态），按事件键排序返回。
     * 阻塞解除不写回依赖任务，查询时按目标事件当前状态计算。
     */
    public List<Incident> listBlockingIncidents(long taskId) {
        return jdbc.query("SELECT i.* FROM incident_task_blockers b"
                        + " JOIN incidents i ON i.id = b.blocker_incident_id"
                        + " WHERE b.task_id = ? ORDER BY i.incident_key",
                INCIDENT_MAPPER, taskId);
    }
}
