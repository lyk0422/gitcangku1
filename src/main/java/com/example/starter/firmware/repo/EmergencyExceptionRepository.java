package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * 冻结窗口内紧急双人例外放行记录数据访问，历史不可改。
 */
@Repository
public class EmergencyExceptionRepository {

    public record EmergencyException(long id, long freezeId, String eventNo, String confirmer1,
                                     String confirmer2, String operation, String reference,
                                     String requestId, String createdAtUtc) {
    }

    private static final RowMapper<EmergencyException> MAPPER = (rs, rowNum) -> new EmergencyException(
            rs.getLong("id"), rs.getLong("freeze_id"), rs.getString("event_no"),
            rs.getString("confirmer1"), rs.getString("confirmer2"), rs.getString("operation"),
            rs.getString("reference"), rs.getString("request_id"), rs.getString("created_at_utc"));

    private static final String COLUMNS = "id, freeze_id, event_no, confirmer1, confirmer2, operation,"
            + " reference, request_id, created_at_utc";

    private final JdbcTemplate jdbc;

    public EmergencyExceptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long freezeId, String eventNo, String confirmer1, String confirmer2,
                       String operation, String reference, String requestId, String createdAtUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO freeze_emergency_exception (freeze_id, event_no, confirmer1, confirmer2,"
                            + " operation, reference, request_id, created_at_utc)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", new String[]{"id"});
            ps.setLong(1, freezeId);
            ps.setString(2, eventNo);
            ps.setString(3, confirmer1);
            ps.setString(4, confirmer2);
            ps.setString(5, operation);
            ps.setString(6, reference);
            ps.setString(7, requestId);
            ps.setString(8, createdAtUtc);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<EmergencyException> findByFreeze(long freezeId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM freeze_emergency_exception WHERE freeze_id = ? ORDER BY id",
                MAPPER, freezeId);
    }
}
