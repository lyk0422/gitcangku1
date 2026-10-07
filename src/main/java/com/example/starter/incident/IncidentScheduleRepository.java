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
 * 整案排程提案及事件排程版本的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内：版本推进、STALE 标记与提案写入
 * 相对同一事件的其他写操作串行化，不会产生混合状态的 CURRENT 方案。
 */
@Repository
public class IncidentScheduleRepository {

    private final JdbcTemplate jdbc;

    public IncidentScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IncidentSchedule> MAPPER = (rs, n) -> map(rs);

    private static IncidentSchedule map(ResultSet rs) throws SQLException {
        return new IncidentSchedule(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getInt("schedule_version"),
                ScheduleStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("base_instant").toInstant(),
                rs.getString("snapshot"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 插入新提案（状态 CURRENT），返回生成主键。
     * (incident_id, schedule_version) 唯一约束兜底并发重复插入。
     */
    public long insert(IncidentSchedule schedule) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_schedules (incident_id, schedule_version, status,"
                            + " base_instant, snapshot, created_by, created_at)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, schedule.incidentId());
            ps.setInt(2, schedule.scheduleVersion());
            ps.setString(3, schedule.status().name());
            ps.setTimestamp(4, Timestamp.from(schedule.baseInstant()));
            ps.setString(5, schedule.snapshot());
            ps.setString(6, schedule.createdBy());
            ps.setTimestamp(7, Timestamp.from(schedule.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件最新版本的提案，无则空。
     */
    public Optional<IncidentSchedule> findLatest(long incidentId) {
        List<IncidentSchedule> rows = jdbc.query(
                "SELECT * FROM incident_schedules WHERE incident_id = ?"
                        + " ORDER BY schedule_version DESC LIMIT 1",
                MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部提案，按版本升序返回（历史保留不删除）。
     */
    public List<IncidentSchedule> listByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_schedules WHERE incident_id = ?"
                + " ORDER BY schedule_version", MAPPER, incidentId);
    }

    /**
     * 将事件当前有效的提案标记为 STALE（被替换或事件版本推进）；历史行保留。
     */
    public void markCurrentStale(long incidentId) {
        jdbc.update("UPDATE incident_schedules SET status = 'STALE'"
                + " WHERE incident_id = ? AND status = 'CURRENT'", incidentId);
    }

    /**
     * 读取事件排程相关版本，从未推进过为 0。
     */
    public long currentStateVersion(long incidentId) {
        List<Long> rows = jdbc.query(
                "SELECT state_version FROM incident_schedule_state WHERE incident_id = ?",
                (rs, n) -> rs.getLong(1), incidentId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    /**
     * 事件版本推进（任务完成、取消或指挥交接接受）：版本 +1，
     * 并将当前有效的提案标记为 STALE。调用前必须已锁定事件行。
     */
    public void advanceStateVersion(long incidentId) {
        int updated = jdbc.update("UPDATE incident_schedule_state SET state_version = state_version + 1"
                + " WHERE incident_id = ?", incidentId);
        if (updated == 0) {
            jdbc.update("INSERT INTO incident_schedule_state (incident_id, state_version)"
                    + " VALUES (?, 1)", incidentId);
        }
        markCurrentStale(incidentId);
    }
}
