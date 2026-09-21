package com.example.starter.incident;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 事件主表访问，全部使用参数化 SQL。
 */
@Repository
public class IncidentRepository {

    private static final String COLUMNS =
            "id, incident_key, severity, summary, reporter, status, commander_id, pending_commander_id,"
                    + " version, created_at, updated_at";

    private static final RowMapper<Incident> MAPPER = (rs, rowNum) -> new Incident(
            rs.getLong("id"),
            rs.getString("incident_key"),
            Severity.valueOf(rs.getString("severity")),
            rs.getString("summary"),
            rs.getString("reporter"),
            IncidentStatus.valueOf(rs.getString("status")),
            rs.getString("commander_id"),
            rs.getString("pending_commander_id"),
            rs.getLong("version"),
            UtcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)),
            UtcTimes.fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IncidentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Incident insert(Incident incident) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO incident (incident_key, severity, summary, reporter, status, commander_id,"
                            + " pending_commander_id, version, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, incident.incidentKey());
            ps.setString(2, incident.severity().name());
            ps.setString(3, incident.summary());
            ps.setString(4, incident.reporter());
            ps.setString(5, incident.status().name());
            ps.setString(6, incident.commanderId());
            ps.setString(7, incident.pendingCommanderId());
            ps.setLong(8, incident.version());
            ps.setObject(9, UtcTimes.toDb(incident.createdAt()));
            ps.setObject(10, UtcTimes.toDb(incident.updatedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new Incident(
                key == null ? null : key.longValue(),
                incident.incidentKey(),
                incident.severity(),
                incident.summary(),
                incident.reporter(),
                incident.status(),
                incident.commanderId(),
                incident.pendingCommanderId(),
                incident.version(),
                incident.createdAt(),
                incident.updatedAt());
    }

    public Optional<Incident> findByKey(String incidentKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM incident WHERE incident_key = ?", MAPPER, incidentKey)
                .stream()
                .findFirst();
    }

    /**
     * 按业务键查询并对事件行加排他锁，须在事务内调用，用于串行化同一事件上的并发命令。
     */
    public Optional<Incident> findByKeyForUpdate(String incidentKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM incident WHERE incident_key = ? FOR UPDATE",
                        MAPPER, incidentKey)
                .stream()
                .findFirst();
    }

    public void update(Incident incident) {
        jdbc.update(
                "UPDATE incident SET status = ?, commander_id = ?, pending_commander_id = ?,"
                        + " version = ?, updated_at = ? WHERE id = ?",
                incident.status().name(),
                incident.commanderId(),
                incident.pendingCommanderId(),
                incident.version(),
                UtcTimes.toDb(incident.updatedAt()),
                incident.id());
    }
}
