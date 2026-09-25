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
 * 遏制时限挂起区间仓储。区间记录只增不改：挂起插入起始半区，恢复以条件更新
 * （resumed_at IS NULL）封口一次；起始半区字段无更新路径，历史不可改写。
 * 所有写调用均处于先锁定事件行的写事务内，并发挂起/恢复按事务提交顺序串行化。
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
                rs.getLong("id"), rs.getLong("incident_id"),
                rs.getString("suspend_key"), rs.getString("reason"),
                rs.getString("suspended_by"),
                rs.getTimestamp("suspended_at").toInstant(),
                rs.getString("resumed_by"), rs.getString("resume_note"),
                resumedAt == null ? null : resumedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 插入生效挂起区间（恢复列为空），返回生成主键。
     */
    public long insert(Suspension suspension) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_suspensions (incident_id, suspend_key, reason,"
                            + " suspended_by, suspended_at, resumed_by, resume_note, resumed_at,"
                            + " created_at) VALUES (?,?,?,?,?,NULL,NULL,NULL,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, suspension.incidentId());
            ps.setString(2, suspension.suspendKey());
            ps.setString(3, suspension.reason());
            ps.setString(4, suspension.suspendedBy());
            ps.setTimestamp(5, Timestamp.from(suspension.suspendedAt()));
            ps.setTimestamp(6, Timestamp.from(suspension.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按主键查询区间记录。
     */
    public Optional<Suspension> findById(long id) {
        List<Suspension> rows = jdbc.query("SELECT * FROM incident_suspensions WHERE id = ?",
                MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件当前生效中的挂起区间（resumed_at 为空），不存在返回空。
     */
    public Optional<Suspension> findActive(long incidentId) {
        List<Suspension> rows = jdbc.query(
                "SELECT * FROM incident_suspensions WHERE incident_id = ? AND resumed_at IS NULL",
                MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部挂起区间，按落库顺序返回。
     */
    public List<Suspension> listByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_suspensions WHERE incident_id = ? ORDER BY id",
                MAPPER, incidentId);
    }

    /**
     * 将生效区间以恢复时刻封口；条件包含 resumed_at IS NULL，
     * 已被并发恢复的区间更新行数为 0。
     */
    public int resume(long id, String resumedBy, String resumeNote, Instant resumedAt) {
        return jdbc.update("UPDATE incident_suspensions SET resumed_by = ?, resume_note = ?,"
                        + " resumed_at = ? WHERE id = ? AND resumed_at IS NULL",
                resumedBy, resumeNote, Timestamp.from(resumedAt), id);
    }
}
