package com.example.starter.calibration.repo;

import java.math.BigDecimal;
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

import com.example.starter.calibration.model.CoefficientVersion;

/**
 * 环境补偿系数版本持久化。版本只增不改：更新系数即在仪器锁事务内闭合并版本、追加新版本；
 * 生效区间为 UTC 左闭右开，同一仪器任意时刻至多一个版本覆盖该时刻。
 */
@Repository
public class CoefficientVersionRepository {

    private static final RowMapper<CoefficientVersion> MAPPER = (rs, rowNum) -> new CoefficientVersion(
            rs.getLong("id"),
            rs.getString("instrument_id"),
            JdbcTimes.fromDb(rs.getObject("effective_from", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("effective_to", LocalDateTime.class)),
            rs.getBigDecimal("ref_temperature"),
            rs.getBigDecimal("ref_humidity"),
            rs.getBigDecimal("temperature_coeff"),
            rs.getBigDecimal("humidity_coeff"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public CoefficientVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询该仪器当前开放（effective_to 为 NULL）的版本并对其加行锁（须在事务内调用）。
     * 仪器首次写入系数时不存在开放版本。
     */
    public Optional<CoefficientVersion> findOpenForUpdate(String instrumentId) {
        return jdbc.query(
                "SELECT * FROM coefficient_version WHERE instrument_id = ? AND effective_to IS NULL FOR UPDATE",
                MAPPER, instrumentId).stream().findFirst();
    }

    /**
     * 闭合开放版本：effective_to 设为新版本起点（右开）。
     */
    public void closeVersion(long id, Instant effectiveTo) {
        jdbc.update("UPDATE coefficient_version SET effective_to = ? WHERE id = ?",
                JdbcTimes.toDb(effectiveTo), id);
    }

    /**
     * 追加新版本并返回版本 ID。
     */
    public long insert(String instrumentId, Instant effectiveFrom,
                       BigDecimal refTemperature, BigDecimal refHumidity,
                       BigDecimal temperatureCoeff, BigDecimal humidityCoeff,
                       Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO coefficient_version "
                            + "(instrument_id, effective_from, effective_to, ref_temperature, ref_humidity, "
                            + "temperature_coeff, humidity_coeff, created_at) "
                            + "VALUES (?, ?, NULL, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, instrumentId);
            ps.setObject(2, JdbcTimes.toDb(effectiveFrom));
            ps.setBigDecimal(3, refTemperature);
            ps.setBigDecimal(4, refHumidity);
            ps.setBigDecimal(5, temperatureCoeff);
            ps.setBigDecimal(6, humidityCoeff);
            ps.setObject(7, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询（不加锁）。
     */
    public Optional<CoefficientVersion> findById(long id) {
        return jdbc.query("SELECT * FROM coefficient_version WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行锁（须在事务内调用），用于放行时锁定所引用的完整版本。
     */
    public Optional<CoefficientVersion> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM coefficient_version WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 统计指定时刻（含）落在多少个版本的生效区间 [from, to) 内，to 为 NULL 视为开放。
     * 正常数据恒为 0 或 1；大于 1 表示同时命中多个版本，放行须拒绝。
     */
    public int countEffectiveAt(String instrumentId, Instant at) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM coefficient_version "
                        + "WHERE instrument_id = ? AND effective_from <= ? "
                        + "AND (effective_to IS NULL OR effective_to > ?)",
                Integer.class, instrumentId, JdbcTimes.toDb(at), JdbcTimes.toDb(at));
        return count == null ? 0 : count;
    }

    /**
     * 查询指定时刻生效的全部版本（须在事务内、已持有仪器锁时调用，避免与更新并发）。
     */
    public List<CoefficientVersion> findEffectiveAt(String instrumentId, Instant at) {
        return jdbc.query(
                "SELECT * FROM coefficient_version "
                        + "WHERE instrument_id = ? AND effective_from <= ? "
                        + "AND (effective_to IS NULL OR effective_to > ?) ORDER BY id",
                MAPPER, instrumentId, JdbcTimes.toDb(at), JdbcTimes.toDb(at));
    }

    /**
     * 列出仪器全部版本（按生效起点升序），用于查询展示。
     */
    public List<CoefficientVersion> findByInstrument(String instrumentId) {
        return jdbc.query(
                "SELECT * FROM coefficient_version WHERE instrument_id = ? ORDER BY effective_from",
                MAPPER, instrumentId);
    }
}
