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
 * 事件及关联表（处置记录、任务、依赖边、交接单、升级、状态历史、演练批次）的 JDBC 仓储。
 * 所有事件读写均携带 domain；同一 incidentKey 在 REAL/DRILL 两域各有独立行。
 * 写路径先以 FOR UPDATE 锁定事件行（或演练批次行），保证并发写按事务提交顺序生效。
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
    private static final RowMapper<Task> TASK_MAPPER = (rs, n) -> new Task(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getString("task_key"),
            rs.getString("title"), TaskStatus.valueOf(rs.getString("status")),
            rs.getString("actor"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
    private static final RowMapper<Escalation> ESCALATION_MAPPER = (rs, n) -> new Escalation(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getString("escalate_to"),
            rs.getString("reason"), rs.getString("actor"),
            rs.getTimestamp("created_at").toInstant());
    private static final RowMapper<DrillBatch> BATCH_MAPPER = (rs, n) -> new DrillBatch(
            rs.getString("batch_key"), rs.getString("drill_key"),
            DrillBatchStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("cleaned_at") == null ? null : rs.getTimestamp("cleaned_at").toInstant());
    private static final RowMapper<CleanupRecord> CLEANUP_MAPPER = (rs, n) -> new CleanupRecord(
            rs.getLong("id"), rs.getString("cleanup_key"), rs.getString("batch_key"),
            rs.getInt("deleted_incidents"), rs.getString("actor"),
            rs.getTimestamp("created_at").toInstant());

    private static Incident mapIncident(ResultSet rs) throws SQLException {
        return new Incident(rs.getLong("id"), Domain.valueOf(rs.getString("domain")),
                rs.getString("incident_key"), rs.getString("drill_key"), rs.getString("drill_batch"),
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
     * 按主键查询事件（用于依赖边的跨域校验）。
     */
    public Optional<Incident> findById(long id) {
        List<Incident> rows = jdbc.query("SELECT * FROM incidents WHERE id = ?", INCIDENT_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 列出某域全部事件，按创建顺序返回（默认只查真实域）。
     */
    public List<Incident> listByDomain(Domain domain) {
        return jdbc.query("SELECT * FROM incidents WHERE domain = ? ORDER BY id",
                INCIDENT_MAPPER, domain.name());
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
     * 插入新事件，初始状态 REPORTED、无指挥人，返回生成主键。
     */
    public long insert(Incident incident) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incidents (domain, incident_key, drill_key, drill_batch, severity,"
                            + " summary, reporter, status, commander, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, incident.domain().name());
            ps.setString(2, incident.incidentKey());
            ps.setString(3, incident.drillKey());
            ps.setString(4, incident.drillBatch());
            ps.setString(5, incident.severity());
            ps.setString(6, incident.summary());
            ps.setString(7, incident.reporter());
            ps.setString(8, incident.status().name());
            ps.setString(9, incident.commander());
            ps.setTimestamp(10, Timestamp.from(incident.createdAt()));
            ps.setTimestamp(11, Timestamp.from(incident.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 更新事件状态与指挥人（commander 传入当前值，取消时可传 null）。
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
     * 创建处置任务，返回生成主键。
     */
    public long insertTask(Task task) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_tasks (incident_id, task_key, title, status, actor,"
                            + " created_at, completed_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, task.incidentId());
            ps.setString(2, task.taskKey());
            ps.setString(3, task.title());
            ps.setString(4, task.status().name());
            ps.setString(5, task.actor());
            ps.setTimestamp(6, Timestamp.from(task.createdAt()));
            ps.setTimestamp(7, task.completedAt() == null ? null : Timestamp.from(task.completedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 追加任务对前置事件的依赖边。
     */
    public void insertTaskBlocker(long taskId, long blockerIncidentId) {
        jdbc.update("INSERT INTO incident_task_blockers (task_id, blocker_incident_id) VALUES (?,?)",
                taskId, blockerIncidentId);
    }

    /**
     * 按事件与 taskKey 查询任务，用于幂等比对。
     */
    public Optional<Task> findTask(long incidentId, String taskKey) {
        List<Task> rows = jdbc.query(
                "SELECT * FROM incident_tasks WHERE incident_id = ? AND task_key = ?",
                TASK_MAPPER, incidentId, taskKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询某任务的全部前置阻塞事件 id。
     */
    public List<Long> listBlockerIncidentIds(long taskId) {
        return jdbc.queryForList(
                "SELECT blocker_incident_id FROM incident_task_blockers WHERE task_id = ? ORDER BY id",
                Long.class, taskId);
    }

    /**
     * 查询事件全部任务，按落库顺序返回。
     */
    public List<Task> listTasks(long incidentId) {
        return jdbc.query("SELECT * FROM incident_tasks WHERE incident_id = ? ORDER BY id",
                TASK_MAPPER, incidentId);
    }

    /**
     * 将任务标记为完成。
     */
    public void completeTask(long taskId, Instant completedAt) {
        jdbc.update("UPDATE incident_tasks SET status = 'DONE', completed_at = ? WHERE id = ?",
                Timestamp.from(completedAt), taskId);
    }

    /**
     * 追加升级记录，返回生成主键。
     */
    public long insertEscalation(Escalation escalation) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_escalations (incident_id, escalate_to, reason, actor,"
                            + " created_at) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, escalation.incidentId());
            ps.setString(2, escalation.escalateTo());
            ps.setString(3, escalation.reason());
            ps.setString(4, escalation.actor());
            ps.setTimestamp(5, Timestamp.from(escalation.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件全部升级记录，按发生顺序返回。
     */
    public List<Escalation> listEscalations(long incidentId) {
        return jdbc.query("SELECT * FROM incident_escalations WHERE incident_id = ? ORDER BY id",
                ESCALATION_MAPPER, incidentId);
    }

    /**
     * 追加真实域通知出站记录；演练升级绝不调用本方法。
     */
    public void insertNotification(long incidentId, String kind, String target, String payload, Instant now) {
        jdbc.update("INSERT INTO notification_outbox (incident_id, kind, target, payload, created_at)"
                + " VALUES (?,?,?,?,?)", incidentId, kind, target, payload, Timestamp.from(now));
    }

    /**
     * 统计通知出站记录数量（用于断言演练域零副作用）。
     */
    public int countNotifications() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox", Integer.class);
        return n == null ? 0 : n;
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
     * 查询演练批次（不加锁）。
     */
    public Optional<DrillBatch> findBatch(String batchKey) {
        List<DrillBatch> rows = jdbc.query("SELECT * FROM drill_batches WHERE batch_key = ?",
                BATCH_MAPPER, batchKey);
        return rows.stream().findFirst();
    }

    /**
     * 锁定演练批次行（SELECT ... FOR UPDATE），清理与演练写入的并发裁决依赖此锁。
     */
    public Optional<DrillBatch> lockBatch(String batchKey) {
        List<DrillBatch> rows = jdbc.query("SELECT * FROM drill_batches WHERE batch_key = ? FOR UPDATE",
                BATCH_MAPPER, batchKey);
        return rows.stream().findFirst();
    }

    /**
     * 创建活跃演练批次。
     */
    public void insertBatch(String batchKey, String drillKey, Instant now) {
        jdbc.update("INSERT INTO drill_batches (batch_key, drill_key, status, created_at, cleaned_at)"
                + " VALUES (?,?, 'ACTIVE', ?, NULL)", batchKey, drillKey, Timestamp.from(now));
    }

    /**
     * 将演练批次标记为已清理（保留墓碑）。
     */
    public void markBatchCleaned(String batchKey, Instant cleanedAt) {
        jdbc.update("UPDATE drill_batches SET status = 'CLEANED', cleaned_at = ? WHERE batch_key = ?",
                Timestamp.from(cleanedAt), batchKey);
    }

    /**
     * 锁定列出某演练批次的全部事件（清理事务内 FOR UPDATE 后再做终态校验与删除）。
     */
    public List<Incident> lockIncidentsByBatch(String batchKey) {
        return jdbc.query(
                "SELECT * FROM incidents WHERE domain = 'DRILL' AND drill_batch = ? FOR UPDATE",
                INCIDENT_MAPPER, batchKey);
    }

    /**
     * 查询某演练批次的全部事件（只读清单，按创建顺序）。
     */
    public List<Incident> listIncidentsByBatch(String batchKey) {
        return jdbc.query(
                "SELECT * FROM incidents WHERE domain = 'DRILL' AND drill_batch = ? ORDER BY id",
                INCIDENT_MAPPER, batchKey);
    }

    /**
     * 原子删除某演练批次事件及其全部关联数据。所有删除在同一事务内执行，
     * 通过先锁定批次行与事件行保证与演练写入按提交顺序裁决。
     */
    public void deleteBatchIncidents(String batchKey, List<Long> incidentIds) {
        if (incidentIds.isEmpty()) {
            return;
        }
        String in = String.join(",", incidentIds.stream().map(x -> "?").toList());
        jdbc.update("DELETE FROM incident_task_blockers WHERE task_id IN ("
                + "SELECT id FROM incident_tasks WHERE incident_id IN (" + in + "))",
                incidentIds.toArray());
        jdbc.update("DELETE FROM incident_tasks WHERE incident_id IN (" + in + ")",
                incidentIds.toArray());
        jdbc.update("DELETE FROM incident_actions WHERE incident_id IN (" + in + ")",
                incidentIds.toArray());
        jdbc.update("DELETE FROM incident_transfers WHERE incident_id IN (" + in + ")",
                incidentIds.toArray());
        jdbc.update("DELETE FROM incident_escalations WHERE incident_id IN (" + in + ")",
                incidentIds.toArray());
        jdbc.update("DELETE FROM incident_status_history WHERE incident_id IN (" + in + ")",
                incidentIds.toArray());
        jdbc.update("DELETE FROM incidents WHERE domain = 'DRILL' AND drill_batch = ?", batchKey);
    }

    /**
     * 追加清理历史。
     */
    public long insertCleanup(String cleanupKey, String batchKey, int deletedIncidents,
                              String actor, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO drill_cleanups (cleanup_key, batch_key, deleted_incidents, actor,"
                            + " created_at) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, cleanupKey);
            ps.setString(2, batchKey);
            ps.setInt(3, deletedIncidents);
            ps.setString(4, actor);
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按清理幂等键查询历史（用于重放）。
     */
    public Optional<CleanupRecord> findCleanup(String cleanupKey) {
        List<CleanupRecord> rows = jdbc.query("SELECT * FROM drill_cleanups WHERE cleanup_key = ?",
                CLEANUP_MAPPER, cleanupKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询清理历史，可按批次过滤（batchKey 为空时返回全部），按发生顺序。
     */
    public List<CleanupRecord> listCleanups(String batchKey) {
        if (batchKey == null) {
            return jdbc.query("SELECT * FROM drill_cleanups ORDER BY id", CLEANUP_MAPPER);
        }
        return jdbc.query("SELECT * FROM drill_cleanups WHERE batch_key = ? ORDER BY id",
                CLEANUP_MAPPER, batchKey);
    }

    /**
     * 按域与状态统计事件数量（用于两域统计独立断言）。
     */
    public int countByDomainAndStatus(Domain domain, IncidentStatus status) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incidents WHERE domain = ? AND status = ?",
                Integer.class, domain.name(), status.name());
        return n == null ? 0 : n;
    }
}
