package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.CompensationCoefficient;

/**
 * 仪器型号与环境补偿系数版本持久化。
 * 型号行同时作为版本发布互斥锁；同一型号通过 active_model 唯一列保证仅一个生效版本。
 */
@Repository
public class CompensationCoefficientRepository {

    private static final RowMapper<CompensationCoefficient> MAPPER = (rs, rowNum) -> new CompensationCoefficient(
            rs.getLong("id"),
            rs.getString("instrument_model"),
            rs.getInt("version_no"),
            rs.getBigDecimal("k0"),
            rs.getBigDecimal("k_temperature"),
            rs.getBigDecimal("k_humidity"),
            rs.getBigDecimal("temp_min"),
            rs.getBigDecimal("temp_max"),
            rs.getBigDecimal("humidity_min"),
            rs.getBigDecimal("humidity_max"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public CompensationCoefficientRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 获取型号级互斥锁（须在事务内调用）：不存在型号行则插入，随后对该行 FOR UPDATE，
     * 串行化同一型号的系数版本发布。
     */
    public void lockModel(String model) {
        try {
            jdbc.update("INSERT INTO compensation_model (instrument_model, created_at) VALUES (?, ?)",
                    model, JdbcTimes.toDb(Instant.now()));
        } catch (DuplicateKeyException ignored) {
            // 型号行已存在，直接进入行锁等待
        }
        jdbc.queryForObject(
                "SELECT instrument_model FROM compensation_model WHERE instrument_model = ? FOR UPDATE",
                String.class, model);
    }

    /**
     * 查询型号当前最大版本号；无版本返回 0。
     */
    public int maxVersionNo(String model) {
        Integer n = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM compensation_coefficient WHERE instrument_model = ?",
                Integer.class, model);
        return n == null ? 0 : n;
    }

    /**
     * 插入新生效版本（active_model=型号）并返回版本 ID；调用方须先将旧生效行置为历史行。
     */
    public long insertActive(CompensationCoefficient c) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO compensation_coefficient "
                            + "(instrument_model, version_no, k0, k_temperature, k_humidity, "
                            + "temp_min, temp_max, humidity_min, humidity_max, active_model, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, c.instrumentModel());
            ps.setInt(2, c.versionNo());
            ps.setBigDecimal(3, c.k0());
            ps.setBigDecimal(4, c.kTemperature());
            ps.setBigDecimal(5, c.kHumidity());
            ps.setBigDecimal(6, c.tempMin());
            ps.setBigDecimal(7, c.tempMax());
            ps.setBigDecimal(8, c.humidityMin());
            ps.setBigDecimal(9, c.humidityMax());
            ps.setString(10, c.instrumentModel());
            ps.setObject(11, JdbcTimes.toDb(c.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 将型号旧生效版本置为历史行（active_model=NULL）。
     */
    public int deactivateModel(String model) {
        return jdbc.update(
                "UPDATE compensation_coefficient SET active_model = NULL WHERE active_model = ?", model);
    }

    /**
     * 查询型号当前生效系数版本（不加锁）。
     */
    public Optional<CompensationCoefficient> findActive(String model) {
        return jdbc.query(
                "SELECT * FROM compensation_coefficient WHERE active_model = ?", MAPPER, model)
                .stream().findFirst();
    }

    /**
     * 按版本 ID 查询（不加锁），用于固化快照回放。
     */
    public Optional<CompensationCoefficient> findById(long id) {
        return jdbc.query("SELECT * FROM compensation_coefficient WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询型号全部版本（按版本号升序）。
     */
    public java.util.List<CompensationCoefficient> findByModel(String model) {
        return jdbc.query(
                "SELECT * FROM compensation_coefficient WHERE instrument_model = ? ORDER BY version_no",
                MAPPER, model);
    }
}
