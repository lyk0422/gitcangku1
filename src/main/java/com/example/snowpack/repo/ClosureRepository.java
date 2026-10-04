package com.example.snowpack.repo;

import com.example.snowpack.repo.Rows.ClosureRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 封闭命令存取。 */
@Repository
public class ClosureRepository {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_LIFTED = "LIFTED";
    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_MANUAL = "MANUAL";

    private static final RowMapper<ClosureRow> MAPPER = (rs, rowNum) -> new ClosureRow(
            rs.getLong("id"), rs.getString("zone_id"), rs.getString("request_id"),
            rs.getString("source"), rs.getString("reason"), rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("lifted_at") == null ? null : rs.getTimestamp("lifted_at").toInstant());

    private final JdbcTemplate jdbc;

    public ClosureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String zoneId, String requestId, String source, String reason, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO closure_command (zone_id, request_id, source, reason, status, created_at, lifted_at)"
                            + " VALUES (?,?,?,?,?,?,NULL)",
                    new String[] {"id"});
            ps.setString(1, zoneId);
            ps.setString(2, requestId);
            ps.setString(3, source);
            ps.setString(4, reason);
            ps.setString(5, STATUS_ACTIVE);
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ClosureRow> findActive(String zoneId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM closure_command WHERE zone_id = ? AND status = ? ORDER BY id DESC LIMIT 1",
                    MAPPER, zoneId, STATUS_ACTIVE));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<ClosureRow> findByRequestId(String zoneId, String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM closure_command WHERE zone_id = ? AND request_id = ?", MAPPER,
                    zoneId, requestId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public void markLifted(long closureId, Instant liftedAt) {
        jdbc.update("UPDATE closure_command SET status = ?, lifted_at = ? WHERE id = ?",
                STATUS_LIFTED, Timestamp.from(liftedAt), closureId);
    }

    public List<ClosureRow> listByZone(String zoneId) {
        return jdbc.query("SELECT * FROM closure_command WHERE zone_id = ? ORDER BY id", MAPPER, zoneId);
    }
}