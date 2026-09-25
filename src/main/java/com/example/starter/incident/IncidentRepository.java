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
 * 事件及关联表（处置记录、交接单、状态历史、依赖边、升级、通知）的 JDBC 仓储。
 * 所有写路径先以 FOR UPDATE 锁定事件行，保证同一事件的并发写按事务提交顺序生效。
 * 所有事件查询均按隔离域（REAL/DRILL）过滤，两域数据互不可见。
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
    private static final RowMapper<IncidentDependency> DEPENDENCY_MAPPER = (rs, n) -> new IncidentDependency(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getLong("blocked_by_incident_id"),
            rs.getTimestamp("created_at").toInstant());
    private static final RowMapper<IncidentEscalation> ESCALATION_MAPPER = (rs, n) -> new IncidentEscalation(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getString("from_severity"),
            rs.getString("to_severity"), rs.getString("reason"), rs.getString("actor"),
            rs.getTimestamp("created_at").toInstant());

    private static Incident mapIncident(ResultSet rs) throws SQLException {
        return new Incident(rs.getLong("id"), rs.getString("incident_key"),
                Domain.valueOf(rs.getString("domain")), rs.getString("drill_batch_key"),
                rs.getString("severity"), rs.getString("summary"), rs.getString("reporter"),
                IncidentStatus.valueOf(rs.getString("status")), rs.getString("commander"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 按域与业务键查询事件（不加锁），用于只读场景。
     */
    public Optional<Incident> findByKey(Domain domain, String incidentKey) {
        List<Incident> rows = jdbc.query(
                "SELECT * FROM incidents WHERE domain = ? AND incident_key = ?",
                INCIDENT_MAPPER, domain.name(), incidentKey);
        return rows.stream().findFirst();
    }

    /**
     * 按域与业务键查询并锁定事件行（SELECT ... FOR UPDATE），用于写路径串行化。
     */
    public Optional<Incident> lockByKey(Domain domain, String incidentKey) {
        List<Incident> rows = jdbc.query(
                "SELECT * FROM incidents WHERE domain = ? AND incident_key = ? FOR UPDATE",
                INCIDENT_MAPPER, domain.name(), incidentKey);
        return rows.stream().findFirst();
    }

    /**
     * 插入新事件，返回生成主键。
     */
    public long insert(Incident incident) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incidents (incident_key, domain, drill_batch_key, severity, summary,"
                            + " reporter, status, commander, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, incident.incidentKey());
            ps.setString(2, incident.domain().name());
            ps.setString(3, incident.drillBatchKey());
            ps.setString(4, incident.severity());
            ps.setString(5, incident.summary());
            ps.setString(6, incident.reporter());
            ps.setString(7, incident.status().name());
            ps.setString(8, incident.commander());
            ps.setTimestamp(9, Timestamp.from(incident.createdAt()));
            ps.setTimestamp(10, Timestamp.from(incident.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 更新事件状态与指挥人（commander 可为 null 表示不变更时传入原值）。
     */
    public void updateState(long id, IncidentStatus status, String commander, Instant updatedAt) {
        jdbc.update("UPDATE incidents SET status = ?, commander = ?, updated_at = ? WHERE id = ?",
                status.name(), commander, Timestamp.from(updatedAt), id);
    }

    /**
     * 更新事件严重等级（升级生效）。
     */
    public void updateSeverity(long id, String severity, Instant updatedAt) {
        jdbc.update("UPDATE incidents SET severity = ?, updated_at = ? WHERE id = ?",
                severity, Timestamp.from(updatedAt), id);
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
     * 建立处置任务依赖边（被阻塞事件）。重复边由唯一约束拒绝。
     */
    public void insertDependency(IncidentDependency dependency) {
        jdbc.update("INSERT INTO incident_dependencies (incident_id, blocked_by_incident_id, created_at)"
                + " VALUES (?,?,?)", dependency.incidentId(), dependency.blockedByIncidentId(),
                Timestamp.from(dependency.createdAt()));
    }

    /**
     * 查询事件的全部依赖边，按建立顺序返回。
     */
    public List<IncidentDependency> listDependencies(long incidentId) {
        return jdbc.query("SELECT * FROM incident_dependencies WHERE incident_id = ? ORDER BY id",
                DEPENDENCY_MAPPER, incidentId);
    }

    /**
     * 追加升级记录。
     */
    public void insertEscalation(IncidentEscalation escalation) {
        jdbc.update("INSERT INTO incident_escalations (incident_id, from_severity, to_severity, reason,"
                + " actor, created_at) VALUES (?,?,?,?,?,?)", escalation.incidentId(),
                escalation.fromSeverity(), escalation.toSeverity(), escalation.reason(),
                escalation.actor(), Timestamp.from(escalation.createdAt()));
    }

    /**
     * 查询事件全部升级记录，按升级顺序返回。
     */
    public List<IncidentEscalation> listEscalations(long incidentId) {
        return jdbc.query("SELECT * FROM incident_escalations WHERE incident_id = ? ORDER BY id",
                ESCALATION_MAPPER, incidentId);
    }

    /**
     * 记录真实域升级触发的通知副作用；演练域绝不调用。
     */
    public void insertNotification(long incidentId, Domain domain, String channel, String payload,
                                   Instant now) {
        jdbc.update("INSERT INTO incident_notifications (incident_id, domain, channel, payload, created_at)"
                + " VALUES (?,?,?,?,?)", incidentId, domain.name(), channel, payload, Timestamp.from(now));
    }

    /**
     * 统计某事件产生的通知数（用于断言演练升级零副作用）。
     */
    public int countNotifications(long incidentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_notifications WHERE incident_id = ?",
                Integer.class, incidentId);
        return count == null ? 0 : count;
    }

    /**
     * 按主键查询事件（清理删除联表用）。
     */
    public List<Incident> listByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        return jdbc.query("SELECT * FROM incidents WHERE id IN (" + placeholders + ") ORDER BY id",
                INCIDENT_MAPPER, ids.toArray());
    }

    /**
     * 锁定并返回某演练批次的全部事件行（FOR UPDATE），供清理事务串行化与终态校验。
     */
    public List<Incident> lockByDrillBatch(String batchKey) {
        return jdbc.query(
                "SELECT * FROM incidents WHERE domain = 'DRILL' AND drill_batch_key = ? FOR UPDATE",
                INCIDENT_MAPPER, batchKey);
    }

    /**
     * 查询某演练批次的全部事件（不加锁），供清单查询。
     */
    public List<Incident> listByDrillBatch(String batchKey) {
        return jdbc.query(
                "SELECT * FROM incidents WHERE domain = 'DRILL' AND drill_batch_key = ? ORDER BY id",
                INCIDENT_MAPPER, batchKey);
    }

    /**
     * 查询某域全部事件，按键排序（统计/域查询用）。
     */
    public List<Incident> listByDomain(Domain domain) {
        return jdbc.query("SELECT * FROM incidents WHERE domain = ? ORDER BY incident_key",
                INCIDENT_MAPPER, domain.name());
    }

    /**
     * 批量删除给定事件的子表记录与事件行本身；清理事务内调用，调用方负责域隔离条件。
     */
    public void deleteIncidentsCascade(List<Long> incidentIds) {
        if (incidentIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", incidentIds.stream().map(i -> "?").toList());
        Object[] args = incidentIds.toArray();
        jdbc.update("DELETE FROM incident_dependencies WHERE incident_id IN (" + placeholders
                + ") OR blocked_by_incident_id IN (" + placeholders + ")",
                concatArgs(args, args));
        jdbc.update("DELETE FROM incident_escalations WHERE incident_id IN (" + placeholders + ")", args);
        jdbc.update("DELETE FROM incident_status_history WHERE incident_id IN (" + placeholders + ")", args);
        jdbc.update("DELETE FROM incident_transfers WHERE incident_id IN (" + placeholders + ")", args);
        jdbc.update("DELETE FROM incident_actions WHERE incident_id IN (" + placeholders + ")", args);
        jdbc.update("DELETE FROM incidents WHERE id IN (" + placeholders + ")", args);
    }

    private static Object[] concatArgs(Object[] first, Object[] second) {
        Object[] all = new Object[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
