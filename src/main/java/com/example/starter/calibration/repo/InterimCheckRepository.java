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

import com.example.starter.calibration.model.CheckResult;
import com.example.starter.calibration.model.InterimCheck;

/**
 * 仪器期间核查持久化。记录只增不改不删；check_key 全局唯一，
 * (instrument_id, checked_at) 同一仪器同一时刻唯一。
 */
@Repository
public class InterimCheckRepository {

    private static final RowMapper<InterimCheck> MAPPER = (rs, rowNum) -> new InterimCheck(
            rs.getLong("id"),
            rs.getString("check_key"),
            rs.getString("instrument_id"),
            JdbcTimes.fromDb(rs.getObject("checked_at", LocalDateTime.class)),
            rs.getBigDecimal("standard_value"),
            rs.getBigDecimal("actual_value"),
            rs.getBigDecimal("tolerance"),
            CheckResult.valueOf(rs.getString("result")),
            rs.getString("checked_by"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public InterimCheckRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入核查记录并返回生成 ID。check_key 或 (instrument_id, checked_at) 冲突时抛 DuplicateKeyException。
     */
    public long insert(InterimCheck check) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO interim_check "
                            + "(check_key, instrument_id, checked_at, standard_value, actual_value, "
                            + "tolerance, result, checked_by, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, check.checkKey());
            ps.setString(2, check.instrumentId());
            ps.setObject(3, JdbcTimes.toDb(check.checkedAt()));
            ps.setBigDecimal(4, check.standardValue());
            ps.setBigDecimal(5, check.actualValue());
            ps.setBigDecimal(6, check.tolerance());
            ps.setString(7, check.result().name());
            ps.setString(8, check.checkedBy());
            ps.setObject(9, JdbcTimes.toDb(check.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按核查业务键查询（不加锁）。
     */
    public Optional<InterimCheck> findByKey(String checkKey) {
        return jdbc.query("SELECT * FROM interim_check WHERE check_key = ?", MAPPER, checkKey)
                .stream().findFirst();
    }

    /**
     * 同一仪器同一核查时刻是否已存在记录。
     */
    public boolean existsAt(String instrumentId, Instant checkedAt) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interim_check WHERE instrument_id = ? AND checked_at = ?",
                Integer.class, instrumentId, JdbcTimes.toDb(checkedAt));
        return count != null && count > 0;
    }

    /**
     * 查询该仪器严格早于 before 时刻的最近一条 PASS 核查时刻；不存在返回空。
     */
    public Optional<Instant> findLatestPassAtBefore(String instrumentId, Instant before) {
        LocalDateTime value = jdbc.queryForObject(
                "SELECT MAX(checked_at) FROM interim_check "
                        + "WHERE instrument_id = ? AND result = 'PASS' AND checked_at < ?",
                LocalDateTime.class, instrumentId, JdbcTimes.toDb(before));
        return Optional.ofNullable(value).map(JdbcTimes::fromDb);
    }

    /**
     * 核查历史：按仪器过滤（instrumentId 为 null 时全部），核查时刻升序、同刻按 id 升序。
     */
    public List<InterimCheck> findHistory(String instrumentId) {
        if (instrumentId == null) {
            return jdbc.query("SELECT * FROM interim_check ORDER BY checked_at, id", MAPPER);
        }
        return jdbc.query("SELECT * FROM interim_check WHERE instrument_id = ? ORDER BY checked_at, id",
                MAPPER, instrumentId);
    }
}
