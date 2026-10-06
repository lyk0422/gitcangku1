package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.Direction;
import com.example.starter.calibration.model.SessionEntry;

/**
 * 会话条目持久化。(session_id, seq) 唯一约束保证只允许顺序追加，不产生历史插入。
 */
@Repository
public class SessionEntryRepository {

    private static final RowMapper<SessionEntry> MAPPER = (rs, rowNum) -> new SessionEntry(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getInt("seq"),
            rs.getBigDecimal("raw_value"),
            Direction.valueOf(rs.getString("direction")),
            rs.getBigDecimal("corrected_value"),
            rs.getBigDecimal("lower_limit"),
            rs.getBigDecimal("upper_limit"),
            rs.getBoolean("passed"),
            rs.getString("submitted_by"),
            rs.getLong("measurement_id"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public SessionEntryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条会话条目并返回生成的条目 ID。
     */
    public long insert(SessionEntry entry) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO session_entry "
                            + "(session_id, seq, raw_value, direction, corrected_value, lower_limit, upper_limit, "
                            + "passed, submitted_by, measurement_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, entry.sessionId());
            ps.setInt(2, entry.seq());
            ps.setBigDecimal(3, entry.rawValue());
            ps.setString(4, entry.direction().name());
            ps.setBigDecimal(5, entry.correctedValue());
            ps.setBigDecimal(6, entry.lowerLimit());
            ps.setBigDecimal(7, entry.upperLimit());
            ps.setBoolean(8, entry.passed());
            ps.setString(9, entry.submittedBy());
            ps.setLong(10, entry.measurementId());
            ps.setObject(11, JdbcTimes.toDb(entry.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询某会话的全部条目（按序号升序）。
     */
    public List<SessionEntry> findBySessionId(long sessionId) {
        return jdbc.query("SELECT * FROM session_entry WHERE session_id = ? ORDER BY seq", MAPPER, sessionId);
    }
}
