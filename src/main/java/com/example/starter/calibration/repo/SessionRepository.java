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
import com.example.starter.calibration.model.Direction;
import com.example.starter.calibration.model.SessionStatus;

/**
 * 迟滞校准会话持久化。追加与关闭通过会话行锁串行化，版本校验在行锁内完成。
 */
@Repository
public class SessionRepository {

    private static final RowMapper<CalibrationSession> MAPPER = (rs, rowNum) -> new CalibrationSession(
            rs.getLong("id"),
            rs.getString("instrument_id"),
            rs.getLong("certificate_id"),
            SessionStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getString("last_direction") == null ? null : Direction.valueOf(rs.getString("last_direction")),
            rs.getBigDecimal("last_raw"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("closed_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入 OPEN 状态、版本为 0 的会话并返回生成的会话 ID。
     */
    public long insert(String instrumentId, long certificateId, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO calibration_session (instrument_id, certificate_id, status, version, created_at) "
                            + "VALUES (?, ?, ?, 0, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, instrumentId);
            ps.setLong(2, certificateId);
            ps.setString(3, SessionStatus.OPEN.name());
            ps.setObject(4, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询（不加锁）。
     */
    public Optional<CalibrationSession> findById(long id) {
        return jdbc.query("SELECT * FROM calibration_session WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行锁（须在事务内调用），用于追加、关闭与放行的串行化。
     */
    public Optional<CalibrationSession> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM calibration_session WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 追加成功后推进版本并记录最近方向与原始值（须在持有行锁的事务内调用）。
     */
    public void updateAfterAppend(long id, int newVersion, Direction lastDirection, java.math.BigDecimal lastRaw) {
        jdbc.update("UPDATE calibration_session SET version = ?, last_direction = ?, last_raw = ? WHERE id = ?",
                newVersion, lastDirection.name(), lastRaw, id);
    }

    /**
     * 关闭会话（须在持有行锁的事务内调用）。
     */
    public void markClosed(long id, Instant closedAt) {
        jdbc.update("UPDATE calibration_session SET status = ?, closed_at = ? WHERE id = ?",
                SessionStatus.CLOSED.name(), JdbcTimes.toDb(closedAt), id);
    }

    /**
     * 置为已放行（须在持有行锁的事务内调用）。
     */
    public void markReleased(long id) {
        jdbc.update("UPDATE calibration_session SET status = ? WHERE id = ?",
                SessionStatus.RELEASED.name(), id);
    }
}
