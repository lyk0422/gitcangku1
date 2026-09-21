package com.example.starter.incident;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 历史流水表访问，全部使用参数化 SQL。
 */
@Repository
public class IncidentEventRepository {

    private static final RowMapper<IncidentEvent> MAPPER = (rs, rowNum) -> new IncidentEvent(
            rs.getLong("id"),
            rs.getLong("incident_id"),
            rs.getString("event_type"),
            rs.getString("actor_id"),
            rs.getString("from_status"),
            rs.getString("to_status"),
            rs.getString("from_commander_id"),
            rs.getString("to_commander_id"),
            rs.getString("detail"),
            UtcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IncidentEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(IncidentEvent event) {
        jdbc.update(
                "INSERT INTO incident_event (incident_id, event_type, actor_id, from_status, to_status,"
                        + " from_commander_id, to_commander_id, detail, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                event.incidentId(),
                event.eventType(),
                event.actorId(),
                event.fromStatus(),
                event.toStatus(),
                event.fromCommanderId(),
                event.toCommanderId(),
                event.detail(),
                UtcTimes.toDb(event.createdAt()));
    }

    public List<IncidentEvent> findByIncidentId(long incidentId) {
        return jdbc.query(
                "SELECT id, incident_id, event_type, actor_id, from_status, to_status, from_commander_id,"
                        + " to_commander_id, detail, created_at FROM incident_event"
                        + " WHERE incident_id = ? ORDER BY id",
                MAPPER, incidentId);
    }
}
