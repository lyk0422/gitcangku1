package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.CalibrationSession;
import com.example.starter.calibration.model.SessionStatus;

/**
 * 迟滞校准会话持久化。session_key 全局唯一；追加与关闭通过行锁串行化，
 * version 等于已追加条目数，按期望版本乐观校验。
 */
@Repository
public class SessionRepository {

    private static final RowMapper<CalibrationSession> MAPPER = (rs, rowNum) -> new CalibrationSession(
            rs.getLong("id"),
            rs.getString("session_key"),
            rs.getString("instrument_id"),
            rs.getLong("certificate_id"),
            rs.getBigDecimal("lower_limit"),
            rs.getBigDecimal("upper_limit"),
            SessionStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getString("created_by"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("closed_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入会话并返回生成的 ID；session_key 冲突时抛出 DuplicateKeyException。
     */
    public long insert(CalibrationSession session) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO calibration_session "
                            + "(session_key, instrument_id, certificate_id, lower_limit, upper_limit, "
                            + "status, version, created_by, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, session.sessionKey());
            ps.setString(2, session.instrumentId());
            ps.setLong(3, session.certificateId());
            ps.setBigDecimal(4, session.lowerLimit());
            ps.setBigDecimal(5, session.upperLimit());
            ps.setString(6, session.status().name());
            ps.setInt(7, session.version());
            ps.setString(8, session.createdBy());
            ps.setObject(9, JdbcTimes.toDb(session.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按会话键查询（不加锁）。
     */
    public Optional<CalibrationSession> findByKey(String sessionKey) {
        return jdbc.query("SELECT * FROM calibration_session WHERE session_key = ?", MAPPER, sessionKey)
                .stream().findFirst();
    }

    /**
     * 按会话键查询并加行锁（须在事务内调用），用于追加/关闭/放行的串行化。
     */
    public Optional<CalibrationSession> findByKeyForUpdate(String sessionKey) {
        return jdbc.query("SELECT * FROM calibration_session WHERE session_key = ? FOR UPDATE",
                MAPPER, sessionKey)
                .stream().findFirst();
    }

    /**
     * 版本加一（须在持有行锁的事务内调用）。
     */
    public void incrementVersion(long id) {
        jdbc.update("UPDATE calibration_session SET version = version + 1 WHERE id = ?", id);
    }

    /**
     * 关闭会话（须在持有行锁的事务内调用）：置 CLOSED 并记录关闭时间。
     */
    public void markClosed(long id, Instant closedAt) {
        jdbc.update("UPDATE calibration_session SET status = ?, closed_at = ? WHERE id = ?",
                SessionStatus.CLOSED.name(), JdbcTimes.toDb(closedAt), id);
    }
}
