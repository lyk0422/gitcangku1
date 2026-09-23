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
 * 遏制逾期升级记录仓储。每个事件至多一条记录（uk_escalation_incident 唯一约束）。
 * 所有调用均处于先锁定事件行的写事务内，并发检查/确认/遏制按事务提交顺序串行化。
 */
@Repository
public class EscalationRepository {

    private final JdbcTemplate jdbc;

    public EscalationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Escalation> MAPPER = (rs, n) -> map(rs);

    private static Escalation map(ResultSet rs) throws SQLException {
        Timestamp ackedAt = rs.getTimestamp("acknowledged_at");
        return new Escalation(
                rs.getLong("id"), rs.getLong("incident_id"),
                rs.getTimestamp("deadline_at").toInstant(),
                rs.getTimestamp("triggered_at").toInstant(),
                rs.getString("triggered_commander"),
                EscalationStatus.valueOf(rs.getString("status")),
                rs.getInt("version"),
                rs.getString("note"), rs.getString("acknowledged_by"),
                ackedAt == null ? null : ackedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 插入 OPEN 升级记录，返回生成主键。每事件唯一约束兜底并发重复插入。
     */
    public long insert(Escalation escalation) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_escalations (incident_id, deadline_at, triggered_at,"
                            + " triggered_commander, status, note, acknowledged_by, acknowledged_at,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, escalation.incidentId());
            ps.setTimestamp(2, Timestamp.from(escalation.deadlineAt()));
            ps.setTimestamp(3, Timestamp.from(escalation.triggeredAt()));
            ps.setString(4, escalation.triggeredCommander());
            ps.setString(5, escalation.status().name());
            ps.setString(6, escalation.note());
            ps.setString(7, escalation.acknowledgedBy());
            ps.setTimestamp(8, escalation.acknowledgedAt() == null
                    ? null : Timestamp.from(escalation.acknowledgedAt()));
            ps.setTimestamp(9, Timestamp.from(escalation.createdAt()));
            ps.setTimestamp(10, Timestamp.from(escalation.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件的唯一升级记录（不存在返回空）。
     */
    public Optional<Escalation> findByIncident(long incidentId) {
        List<Escalation> rows = jdbc.query(
                "SELECT * FROM incident_escalations WHERE incident_id = ?", MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 将指定 OPEN 记录原子置为 ACKNOWLEDGED；条件包含 status='OPEN'，
     * 已被取消的记录更新行数为 0。
     */
    public int acknowledge(long id, String note, String acknowledgedBy, Instant acknowledgedAt) {
        return jdbc.update("UPDATE incident_escalations SET status = 'ACKNOWLEDGED',"
                        + " version = version + 1, note = ?,"
                        + " acknowledged_by = ?, acknowledged_at = ?, updated_at = ?"
                        + " WHERE id = ? AND status = 'OPEN'",
                note, acknowledgedBy, Timestamp.from(acknowledgedAt), Timestamp.from(acknowledgedAt), id);
    }

    /**
     * 遏制提交时将事件仍 OPEN 的记录原子置为 CANCELLED；已确认记录保留。
     * 返回被取消的记录数（0 或 1）。
     */
    public int cancelOpenForIncident(long incidentId, Instant cancelledAt) {
        return jdbc.update("UPDATE incident_escalations SET status = 'CANCELLED',"
                        + " version = version + 1, updated_at = ?"
                        + " WHERE incident_id = ? AND status = 'OPEN'",
                Timestamp.from(cancelledAt), incidentId);
    }

    /**
     * 查询事件全部升级记录（当前每事件至多一条），按落库顺序返回。
     */
    public List<Escalation> listByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_escalations WHERE incident_id = ? ORDER BY id",
                MAPPER, incidentId);
    }
}
