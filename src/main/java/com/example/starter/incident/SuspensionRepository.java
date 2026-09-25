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
 * 遏制时限挂起区间仓储。区间只追加、恢复列只允许从空写一次，历史不可改写。
 * 所有调用均处于先锁定事件行的写事务内，并发挂起/恢复按事件行锁串行化；
 * 未封口区间的事件内唯一约束兜底重复挂起。
 */
@Repository
public class SuspensionRepository {

    private final JdbcTemplate jdbc;

    public SuspensionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Suspension> MAPPER = (rs, n) -> map(rs);

    private static Suspension map(ResultSet rs) throws SQLException {
        Timestamp resumedAt = rs.getTimestamp("resumed_at");
        return new Suspension(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getString("suspend_key"),
                rs.getString("reason"), rs.getString("suspended_by"),
                rs.getTimestamp("suspended_at").toInstant(),
                rs.getString("resume_note"), rs.getString("resumed_by"),
                resumedAt == null ? null : resumedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /** 追加一条未封口挂起区间，返回生成主键。 */
    public long insert(Suspension suspension) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_suspensions (incident_id, suspend_key, reason, suspended_by,"
                            + " suspended_at, resume_note, resumed_by, resumed_at, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,NULL,NULL,NULL,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, suspension.incidentId());
            ps.setString(2, suspension.suspendKey());
            ps.setString(3, suspension.reason());
            ps.setString(4, suspension.suspendedBy());
            ps.setTimestamp(5, Timestamp.from(suspension.suspendedAt()));
            ps.setTimestamp(6, Timestamp.from(suspension.createdAt()));
            ps.setTimestamp(7, Timestamp.from(suspension.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 查询事件当前未封口（生效中）挂起区间。 */
    public Optional<Suspension> findOpen(long incidentId) {
        List<Suspension> rows = jdbc.query(
                "SELECT * FROM incident_suspensions WHERE incident_id = ? AND resumed_at IS NULL",
                MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /** 按主键查询区间。 */
    public Optional<Suspension> findById(long id) {
        List<Suspension> rows = jdbc.query(
                "SELECT * FROM incident_suspensions WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 原子封口指定区间：条件包含 resumed_at IS NULL，
     * 已封口或不存在时更新 0 行；挂起止列固化后不再允许变更。
     */
    public int close(long id, String resumeNote, String resumedBy, Instant resumedAt) {
        return jdbc.update("UPDATE incident_suspensions SET resume_note = ?, resumed_by = ?,"
                        + " resumed_at = ?, updated_at = ? WHERE id = ? AND resumed_at IS NULL",
                resumeNote, resumedBy, Timestamp.from(resumedAt), Timestamp.from(resumedAt), id);
    }

    /**
     * 遏制登记并发封口：将事件仍生效（resumed_at 为空）的挂起区间以遏制时刻封口，
     * 避免挂起先提交、遏制后提交时残留无意义的生效区间。条件更新，至多 1 行。
     */
    public int closeOpenForIncident(long incidentId, String resumeNote, String resumedBy,
                                    Instant resumedAt) {
        return jdbc.update("UPDATE incident_suspensions SET resume_note = ?, resumed_by = ?,"
                        + " resumed_at = ?, updated_at = ? WHERE incident_id = ? AND resumed_at IS NULL",
                resumeNote, resumedBy, Timestamp.from(resumedAt), Timestamp.from(resumedAt), incidentId);
    }

    /** 查询事件全部挂起区间（含已封口与生效中），按发生顺序返回。 */
    public List<Suspension> listByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_suspensions WHERE incident_id = ? ORDER BY id",
                MAPPER, incidentId);
    }
}
