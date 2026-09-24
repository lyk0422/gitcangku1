package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IsolationInterval;

/**
 * 追溯隔离区间持久化。区间只增不改；解除只写入解除信息，不删除历史。
 */
@Repository
public class IsolationIntervalRepository {

    private static final String COLUMNS =
            "id, check_id, instrument_id, range_from, range_to, resolved_by_check_id, resolved_at, created_at";

    private static final RowMapper<IsolationInterval> MAPPER = (rs, rowNum) -> new IsolationInterval(
            rs.getLong("id"),
            rs.getLong("check_id"),
            rs.getString("instrument_id"),
            JdbcTimes.fromDb(rs.getObject("range_from", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("range_to", LocalDateTime.class)),
            (Long) rs.getObject("resolved_by_check_id", Long.class),
            JdbcTimes.fromDb(rs.getObject("resolved_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IsolationIntervalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入隔离区间并返回生成的 ID。 */
    public long insert(long checkId, String instrumentId, Instant rangeFrom, Instant rangeTo,
                       Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO isolation_interval "
                            + "(check_id, instrument_id, range_from, range_to, "
                            + "resolved_by_check_id, resolved_at, created_at) "
                            + "VALUES (?, ?, ?, ?, NULL, NULL, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, checkId);
            ps.setString(2, instrumentId);
            ps.setObject(3, JdbcTimes.toDb(rangeFrom));
            ps.setObject(4, JdbcTimes.toDb(rangeTo));
            ps.setObject(5, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按触发的 FAIL 核查 ID 查询。 */
    public Optional<IsolationInterval> findByCheckId(long checkId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM isolation_interval WHERE check_id = ?",
                MAPPER, checkId).stream().findFirst();
    }

    /** 按主键查询并加行锁（须在事务内调用）。 */
    public Optional<IsolationInterval> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM isolation_interval WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    /** 查询区间历史：可按仪器与“是否已解除”过滤，按区间起点、ID 升序。 */
    public List<IsolationInterval> findIntervals(String instrumentId, Boolean resolved) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS)
                .append(" FROM isolation_interval WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (instrumentId != null) {
            sql.append(" AND instrument_id = ?");
            args.add(instrumentId);
        }
        if (resolved != null) {
            if (resolved) {
                sql.append(" AND resolved_by_check_id IS NOT NULL");
            } else {
                sql.append(" AND resolved_by_check_id IS NULL");
            }
        }
        sql.append(" ORDER BY range_from, id");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /**
     * 该仪器仍未解除且覆盖 instant 时刻的隔离区间（instant 位于 [range_from, range_to) 内），
     * 对区间行加 FOR UPDATE 锁（须在事务内调用）。用于放行与解除时的并发裁决。
     */
    public List<IsolationInterval> findOpenCoveringForUpdate(String instrumentId, Instant instant) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM isolation_interval "
                        + "WHERE instrument_id = ? AND resolved_by_check_id IS NULL "
                        + "AND range_from <= ? AND range_to > ? "
                        + "ORDER BY id FOR UPDATE",
                MAPPER, instrumentId, JdbcTimes.toDb(instant), JdbcTimes.toDb(instant));
    }

    /**
     * 该仪器仍未解除且被 checkedAt 时刻的 PASS 覆盖的隔离区间：
     * range_to &lt;= checkedAt（FAIL 严格早于本次 PASS），对区间行加 FOR UPDATE 锁。
     */
    public List<IsolationInterval> findOpenResolvedByPassForUpdate(String instrumentId,
                                                                   Instant checkedAt) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM isolation_interval "
                        + "WHERE instrument_id = ? AND resolved_by_check_id IS NULL AND range_to <= ? "
                        + "ORDER BY id FOR UPDATE",
                MAPPER, instrumentId, JdbcTimes.toDb(checkedAt));
    }

    /** 标记区间已被更晚的 PASS 核查解除。 */
    public void markResolved(long id, long resolvedByCheckId, Instant resolvedAt) {
        jdbc.update("UPDATE isolation_interval SET resolved_by_check_id = ?, resolved_at = ? WHERE id = ?",
                resolvedByCheckId, JdbcTimes.toDb(resolvedAt), id);
    }
}
