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
 * 联合指挥交接单、闭包事件与不可变快照的 JDBC 仓储。
 * 交接写路径与单事件写路径一样先按固定顺序锁定全部闭包事件行，
 * 保证接受与任务完成/修订、升级确认、单事件转交按事务提交顺序串行。
 */
@Repository
public class JointHandoverRepository {

    private final JdbcTemplate jdbc;

    public JointHandoverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<JointHandover> HANDOVER_MAPPER = (rs, n) -> mapHandover(rs);
    private static final RowMapper<JointHandoverIncident> INCIDENT_MAPPER = (rs, n) ->
            new JointHandoverIncident(rs.getLong("id"), rs.getLong("handover_id"),
                    rs.getLong("incident_id"), rs.getString("incident_key"),
                    rs.getBoolean("in_submitted"), rs.getBoolean("in_closure"),
                    rs.getInt("seq_no"));
    private static final RowMapper<JointHandoverSnapshot> SNAPSHOT_MAPPER = (rs, n) -> mapSnapshot(rs);

    private static JointHandover mapHandover(ResultSet rs) throws SQLException {
        Timestamp acceptedAt = rs.getTimestamp("accepted_at");
        return new JointHandover(
                rs.getLong("id"), rs.getString("handover_key"),
                rs.getString("from_commander"), rs.getString("to_commander"),
                HandoverStatus.valueOf(rs.getString("status")),
                rs.getString("handover_version"),
                rs.getString("submitted_incident_keys"),
                rs.getString("closure_incident_keys"),
                rs.getString("frozen_summary"),
                acceptedAt == null ? null : acceptedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static JointHandoverSnapshot mapSnapshot(ResultSet rs) throws SQLException {
        long escalationVersion = rs.getLong("escalation_version");
        return new JointHandoverSnapshot(
                rs.getLong("id"), rs.getLong("handover_id"),
                rs.getLong("incident_id"), rs.getString("incident_key"),
                rs.getString("commander"), rs.getString("incident_status"),
                rs.getLong("incident_version"), rs.getString("open_tasks_json"),
                rs.wasNull() ? null : escalationVersion,
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 插入 PENDING 交接单（含闭包与冻结摘要），返回生成主键。
     * handover_key 唯一约束兜底并发/重放重复插入。
     */
    public long insert(JointHandover handover) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO joint_handovers (handover_key, from_commander, to_commander, status,"
                            + " handover_version, submitted_incident_keys, closure_incident_keys,"
                            + " frozen_summary, accepted_at, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, handover.handoverKey());
            ps.setString(2, handover.fromCommander());
            ps.setString(3, handover.toCommander());
            ps.setString(4, handover.status().name());
            ps.setString(5, handover.handoverVersion());
            ps.setString(6, handover.submittedIncidentKeys());
            ps.setString(7, handover.closureIncidentKeys());
            ps.setString(8, handover.frozenSummary());
            ps.setTimestamp(9, handover.acceptedAt() == null ? null
                    : Timestamp.from(handover.acceptedAt()));
            ps.setTimestamp(10, Timestamp.from(handover.createdAt()));
            ps.setTimestamp(11, Timestamp.from(handover.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键锁定交接单行（SELECT ... FOR UPDATE）。
     */
    public Optional<JointHandover> lockByKey(String handoverKey) {
        List<JointHandover> rows = jdbc.query(
                "SELECT * FROM joint_handovers WHERE handover_key = ? FOR UPDATE",
                HANDOVER_MAPPER, handoverKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询交接单（不加锁），用于只读查询。
     */
    public Optional<JointHandover> findByKey(String handoverKey) {
        List<JointHandover> rows = jdbc.query(
                "SELECT * FROM joint_handovers WHERE handover_key = ?", HANDOVER_MAPPER, handoverKey);
        return rows.stream().findFirst();
    }

    /**
     * 接受成功：置为 ACCEPTED 并记录接受 UTC 时刻。
     */
    public void markAccepted(long id, Instant acceptedAt) {
        jdbc.update("UPDATE joint_handovers SET status = 'ACCEPTED', accepted_at = ?,"
                        + " updated_at = ? WHERE id = ?",
                Timestamp.from(acceptedAt), Timestamp.from(acceptedAt), id);
    }

    /**
     * 追加一条闭包事件行（发起时写入，之后不可变）。
     */
    public void insertIncident(JointHandoverIncident row) {
        jdbc.update("INSERT INTO joint_handover_incidents (handover_id, incident_id, incident_key,"
                        + " in_submitted, in_closure, seq_no) VALUES (?,?,?,?,?,?)",
                row.handoverId(), row.incidentId(), row.incidentKey(),
                row.inSubmitted(), row.inClosure(), row.seqNo());
    }

    /**
     * 查询交接单全部闭包事件行，按序号返回。
     */
    public List<JointHandoverIncident> listIncidents(long handoverId) {
        return jdbc.query("SELECT * FROM joint_handover_incidents WHERE handover_id = ? ORDER BY seq_no",
                INCIDENT_MAPPER, handoverId);
    }

    /**
     * 追加一条不可变闭包快照行（接受成功时写入）。
     */
    public void insertSnapshot(JointHandoverSnapshot snapshot) {
        jdbc.update("INSERT INTO joint_handover_snapshots (handover_id, incident_id, incident_key,"
                        + " commander, incident_status, incident_version, open_tasks_json,"
                        + " escalation_version, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                snapshot.handoverId(), snapshot.incidentId(), snapshot.incidentKey(),
                snapshot.commander(), snapshot.incidentStatus(), snapshot.incidentVersion(),
                snapshot.openTasksJson(),
                snapshot.escalationVersion() == null ? null : snapshot.escalationVersion(),
                Timestamp.from(snapshot.createdAt()));
    }

    /**
     * 查询交接单全部闭包快照行，按事件键排序返回。
     */
    public List<JointHandoverSnapshot> listSnapshots(long handoverId) {
        return jdbc.query("SELECT * FROM joint_handover_snapshots WHERE handover_id = ?"
                + " ORDER BY incident_key", SNAPSHOT_MAPPER, handoverId);
    }

    /**
     * 查询接收人参与的交接单历史（作为发起人或接收人），按发起顺序倒序返回。
     */
    public List<JointHandover> listHistoryForCommander(String commander) {
        return jdbc.query("SELECT * FROM joint_handovers WHERE from_commander = ? OR to_commander = ?"
                + " ORDER BY id DESC", HANDOVER_MAPPER, commander, commander);
    }
}
