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
 * 联合指挥交接单及闭包成员的 JDBC 仓储。
 * 接受路径先 lockByKey 锁定交接单行，再按 id 升序锁定闭包全部事件行（固定顺序避免死锁），
 * 保证并发接受与任务/升级/单事件交接等写操作按事务提交顺序生效。
 */
@Repository
public class IncidentHandoverRepository {

    private final JdbcTemplate jdbc;

    public IncidentHandoverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IncidentHandover> MAPPER = (rs, n) -> map(rs);

    private static IncidentHandover map(ResultSet rs) throws SQLException {
        Timestamp acceptedAt = rs.getTimestamp("accepted_at");
        return new IncidentHandover(
                rs.getLong("id"), rs.getString("handover_key"), rs.getString("from_commander"),
                rs.getString("to_commander"), HandoverStatus.valueOf(rs.getString("status")),
                rs.getInt("incident_count"), rs.getString("closure_json"),
                rs.getString("snapshot_json"), rs.getString("handover_version"),
                rs.getTimestamp("created_at").toInstant(),
                acceptedAt == null ? null : acceptedAt.toInstant());
    }

    /**
     * 创建 PENDING 联合交接单（快照与版本列为空），返回生成主键。
     * handover_key 唯一约束兜底并发重复发起。
     */
    public long insert(IncidentHandover handover) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_handovers (handover_key, from_commander, to_commander,"
                            + " status, incident_count, closure_json, snapshot_json,"
                            + " handover_version, created_at, accepted_at)"
                            + " VALUES (?,?,?,?,?,?,NULL,NULL,?,NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, handover.handoverKey());
            ps.setString(2, handover.fromCommander());
            ps.setString(3, handover.toCommander());
            ps.setString(4, handover.status().name());
            ps.setInt(5, handover.incidentCount());
            ps.setString(6, handover.closureJson());
            ps.setTimestamp(7, Timestamp.from(handover.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询交接单（不加锁），用于只读场景。
     */
    public Optional<IncidentHandover> findByKey(String handoverKey) {
        List<IncidentHandover> rows = jdbc.query(
                "SELECT * FROM incident_handovers WHERE handover_key = ?", MAPPER, handoverKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定交接单行（SELECT ... FOR UPDATE），用于接受路径串行化。
     */
    public Optional<IncidentHandover> lockByKey(String handoverKey) {
        List<IncidentHandover> rows = jdbc.query(
                "SELECT * FROM incident_handovers WHERE handover_key = ? FOR UPDATE",
                MAPPER, handoverKey);
        return rows.stream().findFirst();
    }

    /**
     * 将交接单原子置为 ACCEPTED 并保存不可变闭包快照与对应交接版本；
     * 条件包含 status='PENDING'，已被并发接受的单更新行数为 0。
     */
    public int markAccepted(long id, String snapshotJson, String handoverVersion,
                            Instant acceptedAt) {
        return jdbc.update("UPDATE incident_handovers SET status = 'ACCEPTED', snapshot_json = ?,"
                        + " handover_version = ?, accepted_at = ?"
                        + " WHERE id = ? AND status = 'PENDING'",
                snapshotJson, handoverVersion, Timestamp.from(acceptedAt), id);
    }

    /**
     * 追加闭包事件成员。(handover_id, incident_id) 唯一。
     */
    public void insertMember(long handoverId, long incidentId) {
        jdbc.update("INSERT INTO incident_handover_incidents (handover_id, incident_id)"
                + " VALUES (?,?)", handoverId, incidentId);
    }

    /**
     * 查询交接单闭包事件 id 列表，按事件 id 升序返回（与多行加锁顺序一致）。
     */
    public List<Long> listMemberIncidentIds(long handoverId) {
        return jdbc.queryForList(
                "SELECT incident_id FROM incident_handover_incidents WHERE handover_id = ?"
                        + " ORDER BY incident_id",
                Long.class, handoverId);
    }

    /**
     * 查询涉及指定事件的全部联合交接单，按发起顺序返回。
     */
    public List<IncidentHandover> listByIncident(long incidentId) {
        return jdbc.query("SELECT h.* FROM incident_handovers h"
                        + " JOIN incident_handover_incidents m ON m.handover_id = h.id"
                        + " WHERE m.incident_id = ? ORDER BY h.id",
                MAPPER, incidentId);
    }
}
