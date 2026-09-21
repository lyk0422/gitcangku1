package com.example.starter.incident;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 处置记录表访问，全部使用参数化 SQL。
 */
@Repository
public class IncidentActionRepository {

    private static final RowMapper<IncidentAction> MAPPER = (rs, rowNum) -> new IncidentAction(
            rs.getLong("id"),
            rs.getLong("incident_id"),
            rs.getString("action_key"),
            rs.getString("actor_id"),
            UtcTimes.fromDb(rs.getObject("occurred_at", LocalDateTime.class)),
            rs.getString("action_type"),
            rs.getString("description"),
            UtcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IncidentActionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public IncidentAction insert(IncidentAction action) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO incident_action (incident_id, action_key, actor_id, occurred_at, action_type,"
                            + " description, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, action.incidentId());
            ps.setString(2, action.actionKey());
            ps.setString(3, action.actorId());
            ps.setObject(4, UtcTimes.toDb(action.occurredAt()));
            ps.setString(5, action.actionType());
            ps.setString(6, action.description());
            ps.setObject(7, UtcTimes.toDb(action.createdAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new IncidentAction(
                key == null ? null : key.longValue(),
                action.incidentId(),
                action.actionKey(),
                action.actorId(),
                action.occurredAt(),
                action.actionType(),
                action.description(),
                action.createdAt());
    }

    public Optional<IncidentAction> findByActionKey(long incidentId, String actionKey) {
        return jdbc.query(
                        "SELECT id, incident_id, action_key, actor_id, occurred_at, action_type, description,"
                                + " created_at FROM incident_action WHERE incident_id = ? AND action_key = ?",
                        MAPPER, incidentId, actionKey)
                .stream()
                .findFirst();
    }

    public List<IncidentAction> findByIncidentId(long incidentId) {
        return jdbc.query(
                "SELECT id, incident_id, action_key, actor_id, occurred_at, action_type, description, created_at"
                        + " FROM incident_action WHERE incident_id = ? ORDER BY id",
                MAPPER, incidentId);
    }
}
