package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IsolationInterval;

/**
 * FAIL 核查追溯隔离区间持久化。区间只增不改；解除仅置 resolved 与解除信息，不删除历史。
 */
@Repository
public class IsolationIntervalRepository {

    private static final RowMapper<IsolationInterval> MAPPER = (rs, rowNum) -> new IsolationInterval(
            rs.getLong("id"),
            rs.getLong("check_id"),
            rs.getString("check_key"),
            rs.getString("instrument_id"),
            JdbcTimes.fromDb(rs.getObject("range_from", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("range_to", LocalDateTime.class)),
            rs.getBoolean("resolved"),
            (Long) rs.getObject("resolved_by_check_id"),
            rs.getString("resolved_by_check_key"),
            JdbcTimes.fromDb(rs.getObject("resolved_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IsolationIntervalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入隔离区间并返回区间 ID。
     */
    public long insert(long checkId, String checkKey, String instrumentId,
                       Instant rangeFrom, Instant rangeTo, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO isolation_interval "
                            + "(check_id, check_key, instrument_id, range_from, range_to, resolved, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, FALSE, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, checkId);
            ps.setString(2, checkKey);
            ps.setString(3, instrumentId);
            ps.setObject(4, JdbcTimes.toDb(rangeFrom));
            ps.setObject(5, JdbcTimes.toDb(rangeTo));
            ps.setObject(6, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询该仪器中终点不晚于 passCheckedAt 且尚未解除的隔离区间（即可被本次 PASS 解除者），按 id 升序。
     */
    public List<IsolationInterval> findOpenForPass(String instrumentId, Instant passCheckedAt) {
        return jdbc.query(
                "SELECT * FROM isolation_interval "
                        + "WHERE instrument_id = ? AND resolved = FALSE AND range_to <= ? ORDER BY id",
                MAPPER, instrumentId, JdbcTimes.toDb(passCheckedAt));
    }

    /**
     * 将区间置为已解除（须在事务内调用），记录解除它的 PASS 核查与时刻。
     */
    public void markResolved(long intervalId, long passCheckId, String passCheckKey, Instant resolvedAt) {
        jdbc.update(
                "UPDATE isolation_interval SET resolved = TRUE, resolved_by_check_id = ?, "
                        + "resolved_by_check_key = ?, resolved_at = ? WHERE id = ? AND resolved = FALSE",
                passCheckId, passCheckKey, JdbcTimes.toDb(resolvedAt), intervalId);
    }

    /**
     * 隔离区间历史：instrumentId 非空时按仪器过滤；按创建顺序（id）升序。
     */
    public List<IsolationInterval> findHistory(String instrumentId) {
        if (instrumentId == null) {
            return jdbc.query("SELECT * FROM isolation_interval ORDER BY id", MAPPER);
        }
        return jdbc.query("SELECT * FROM isolation_interval WHERE instrument_id = ? ORDER BY id",
                MAPPER, instrumentId);
    }

    /**
     * 按触发隔离的 FAIL 核查业务键查询区间。
     */
    public List<IsolationInterval> findByCheckKey(String checkKey) {
        return jdbc.query("SELECT * FROM isolation_interval WHERE check_key = ? ORDER BY id",
                MAPPER, checkKey);
    }

    /**
     * 查询覆盖指定测量时刻的全部未解除隔离区间（range_from &lt;= at &lt; range_to），按 id 升序。
     * 用于放行拦截：区间内待放行结果禁止放行，并指明触发的 checkKey。
     */
    public List<IsolationInterval> findOpenAt(String instrumentId, Instant at) {
        return jdbc.query(
                "SELECT * FROM isolation_interval "
                        + "WHERE instrument_id = ? AND resolved = FALSE AND range_from <= ? AND range_to > ? "
                        + "ORDER BY id",
                MAPPER, instrumentId, JdbcTimes.toDb(at), JdbcTimes.toDb(at));
    }
}
