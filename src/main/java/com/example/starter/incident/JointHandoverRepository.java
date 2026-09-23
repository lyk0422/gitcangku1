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
 * 联合指挥交接单、闭包成员与不可变快照的 JDBC 仓储。
 * 写路径均在持有全部闭包事件行锁的事务内；接受时按 handover_key 锁定交接单行，
 * 保证与并发的任务完成/修订、升级确认、单事件转交按事务提交顺序串行。
 */
@Repository
public class JointHandoverRepository {

    private final JdbcTemplate jdbc;

    public JointHandoverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<JointHandover> HANDOVER_MAPPER = (rs, n) -> mapHandover(rs);

    private static JointHandover mapHandover(ResultSet rs) throws SQLException {
        Timestamp acceptedAt = rs.getTimestamp("accepted_at");
        return new JointHandover(
                rs.getLong("id"), rs.getString("handover_key"),
                rs.getString("from_commander"), rs.getString("to_commander"),
                HandoverStatus.valueOf(rs.getString("status")),
                rs.getString("handover_version"), rs.getString("closure_keys"),
                rs.getString("frozen_summary"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                acceptedAt == null ? null : acceptedAt.toInstant());
    }

    private static final RowMapper<HandoverSnapshotIncident> SNAPSHOT_INCIDENT_MAPPER = (rs, n) ->
            new HandoverSnapshotIncident(
                    rs.getLong("id"), rs.getLong("handover_id"), rs.getLong("incident_id"),
                    rs.getString("incident_key"), rs.getString("commander"),
                    IncidentStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("version_at").toInstant(), rs.getInt("ordinal"));

    private static final RowMapper<HandoverSnapshotTask> SNAPSHOT_TASK_MAPPER = (rs, n) ->
            new HandoverSnapshotTask(
                    rs.getLong("id"), rs.getLong("handover_id"), rs.getLong("incident_id"),
                    rs.getLong("task_id"), rs.getString("task_key"),
                    TaskStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("version_at").toInstant(), rs.getString("blocker_keys"),
                    rs.getInt("ordinal"));

    private static final RowMapper<HandoverSnapshotEscalation> SNAPSHOT_ESCALATION_MAPPER = (rs, n) ->
            new HandoverSnapshotEscalation(
                    rs.getLong("id"), rs.getLong("handover_id"), rs.getLong("incident_id"),
                    rs.getLong("escalation_id"), rs.getTimestamp("version_at").toInstant(),
                    rs.getInt("ordinal"));

    /**
     * 插入 PENDING 联合交接单，返回生成主键。handover_key 唯一约束兜底并发重复提交。
     */
    public long insert(JointHandover handover) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO joint_handovers (handover_key, from_commander, to_commander, status,"
                            + " handover_version, closure_keys, frozen_summary, created_at, updated_at,"
                            + " accepted_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, handover.handoverKey());
            ps.setString(2, handover.fromCommander());
            ps.setString(3, handover.toCommander());
            ps.setString(4, handover.status().name());
            ps.setString(5, handover.handoverVersion());
            ps.setString(6, handover.closureKeys());
            ps.setString(7, handover.frozenSummary());
            ps.setTimestamp(8, Timestamp.from(handover.createdAt()));
            ps.setTimestamp(9, Timestamp.from(handover.updatedAt()));
            ps.setTimestamp(10, handover.acceptedAt() == null
                    ? null : Timestamp.from(handover.acceptedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 写入一个闭包成员行。 */
    public void insertMember(long handoverId, long incidentId, int ordinal) {
        jdbc.update("INSERT INTO joint_handover_members (handover_id, incident_id, ordinal)"
                + " VALUES (?,?,?)", handoverId, incidentId, ordinal);
    }

    /** 按业务键查询交接单（不加锁），用于只读场景。 */
    public Optional<JointHandover> findByKey(String handoverKey) {
        List<JointHandover> rows = jdbc.query(
                "SELECT * FROM joint_handovers WHERE handover_key = ?", HANDOVER_MAPPER, handoverKey);
        return rows.stream().findFirst();
    }

    /** 按业务键锁定交接单行（SELECT ... FOR UPDATE），用于接受路径串行化。 */
    public Optional<JointHandover> lockByKey(String handoverKey) {
        List<JointHandover> rows = jdbc.query(
                "SELECT * FROM joint_handovers WHERE handover_key = ? FOR UPDATE",
                HANDOVER_MAPPER, handoverKey);
        return rows.stream().findFirst();
    }

    /** 接受成功：交接单置 ACCEPTED 并记录 UTC 时刻。 */
    public void markAccepted(long id, Instant acceptedAt) {
        jdbc.update("UPDATE joint_handovers SET status = 'ACCEPTED', accepted_at = ?, updated_at = ?"
                + " WHERE id = ?", Timestamp.from(acceptedAt), Timestamp.from(acceptedAt), id);
    }

    /** 查询交接单闭包成员事件 id，按闭包排序序号返回。 */
    public List<Long> listMemberIncidentIds(long handoverId) {
        return jdbc.queryForList(
                "SELECT incident_id FROM joint_handover_members WHERE handover_id = ? ORDER BY ordinal",
                Long.class, handoverId);
    }

    /**
     * 查询与给定事件集合有重叠、且仍 PENDING 的其它交接单业务键（排除自身），
     * 用于拒绝会与既有待接受联合交接冲突的预览冻结。
     */
    public List<String> findPendingKeysOverlapping(List<Long> incidentIds, long excludeHandoverId) {
        if (incidentIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", incidentIds.stream().map(i -> "?").toList());
        return jdbc.queryForList(
                "SELECT DISTINCT h.handover_key FROM joint_handovers h"
                        + " JOIN joint_handover_members m ON m.handover_id = h.id"
                        + " WHERE h.status = 'PENDING' AND h.id <> ?"
                        + " AND m.incident_id IN (" + placeholders + ")",
                String.class,
                        concatArgs(excludeHandoverId, incidentIds).toArray());
    }

    private static List<Object> concatArgs(long excludeId, List<Long> incidentIds) {
        List<Object> args = new java.util.ArrayList<>();
        args.add(excludeId);
        args.addAll(incidentIds);
        return args;
    }

    /** 写入不可变事件快照行。 */
    public void insertSnapshotIncident(HandoverSnapshotIncident row) {
        jdbc.update("INSERT INTO joint_handover_snapshot_incidents (handover_id, incident_id,"
                        + " incident_key, commander, status, version_at, ordinal)"
                        + " VALUES (?,?,?,?,?,?,?)",
                row.handoverId(), row.incidentId(), row.incidentKey(), row.commander(),
                row.status().name(), Timestamp.from(row.versionAt()), row.ordinal());
    }

    /** 写入不可变 OPEN 任务快照行。 */
    public void insertSnapshotTask(HandoverSnapshotTask row) {
        jdbc.update("INSERT INTO joint_handover_snapshot_tasks (handover_id, incident_id, task_id,"
                        + " task_key, status, version_at, blocker_keys, ordinal)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                row.handoverId(), row.incidentId(), row.taskId(), row.taskKey(),
                row.status().name(), Timestamp.from(row.versionAt()), row.blockerKeys(),
                row.ordinal());
    }

    /** 写入不可变未确认升级快照行。 */
    public void insertSnapshotEscalation(HandoverSnapshotEscalation row) {
        jdbc.update("INSERT INTO joint_handover_snapshot_escalations (handover_id, incident_id,"
                        + " escalation_id, version_at, ordinal) VALUES (?,?,?,?,?)",
                row.handoverId(), row.incidentId(), row.escalationId(),
                Timestamp.from(row.versionAt()), row.ordinal());
    }

    /** 查询交接单事件快照，按快照排序序号返回。 */
    public List<HandoverSnapshotIncident> listSnapshotIncidents(long handoverId) {
        return jdbc.query(
                "SELECT * FROM joint_handover_snapshot_incidents WHERE handover_id = ? ORDER BY ordinal",
                SNAPSHOT_INCIDENT_MAPPER, handoverId);
    }

    /** 查询交接单任务快照，按快照排序序号返回。 */
    public List<HandoverSnapshotTask> listSnapshotTasks(long handoverId) {
        return jdbc.query(
                "SELECT * FROM joint_handover_snapshot_tasks WHERE handover_id = ? ORDER BY ordinal",
                SNAPSHOT_TASK_MAPPER, handoverId);
    }

    /** 查询交接单升级快照，按快照排序序号返回。 */
    public List<HandoverSnapshotEscalation> listSnapshotEscalations(long handoverId) {
        return jdbc.query(
                "SELECT * FROM joint_handover_snapshot_escalations WHERE handover_id = ? ORDER BY ordinal",
                SNAPSHOT_ESCALATION_MAPPER, handoverId);
    }

    /**
     * 查询包含指定事件的全部交接单业务键与状态（预览/历史查询用），按交接单 id 返回。
     */
    public List<JointHandover> listByIncident(long incidentId) {
        return jdbc.query("SELECT h.* FROM joint_handovers h"
                        + " JOIN joint_handover_members m ON m.handover_id = h.id"
                        + " WHERE m.incident_id = ? ORDER BY h.id",
                HANDOVER_MAPPER, incidentId);
    }

    /** 判断事件是否仍属于某个 PENDING 联合交接闭包（单事件转交互斥校验用）。 */
    public boolean existsPendingForIncident(long incidentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_handovers h"
                        + " JOIN joint_handover_members m ON m.handover_id = h.id"
                        + " WHERE m.incident_id = ? AND h.status = 'PENDING'",
                Integer.class, incidentId);
        return count != null && count > 0;
    }
}
