package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.CompensationProfile;

/**
 * 环境补偿系数版本持久化。版本按仪器型号只增不改：更新时在事务内锁定该型号全部版本，
 * 旧生效版本置为 FALSE 并追加激活的新版本；同一型号恰好一个生效版本。
 */
@Repository
public class CompensationProfileRepository {

    private static final RowMapper<CompensationProfile> MAPPER = (rs, rowNum) -> new CompensationProfile(
            rs.getLong("id"),
            rs.getString("instrument_model"),
            rs.getInt("version_no"),
            rs.getBigDecimal("temp_coeff"),
            rs.getBigDecimal("humidity_coeff"),
            rs.getBigDecimal("temp_min"),
            rs.getBigDecimal("temp_max"),
            rs.getBigDecimal("humidity_min"),
            rs.getBigDecimal("humidity_max"),
            rs.getBoolean("active"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public CompensationProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 获取型号级互斥锁（须在事务内调用）：不存在锁行则插入，随后对该行 FOR UPDATE。
     * 保证同一型号并发更新串行化，即使该型号尚无系数版本行。
     */
    public void lockModel(String model) {
        try {
            jdbc.update("INSERT INTO compensation_model_lock (instrument_model, created_at) VALUES (?, ?)",
                    model, JdbcTimes.toDb(Instant.now()));
        } catch (DuplicateKeyException ignored) {
            // 锁行已存在，直接进入行锁等待
        }
        jdbc.queryForObject(
                "SELECT instrument_model FROM compensation_model_lock WHERE instrument_model = ? FOR UPDATE",
                String.class, model);
    }

    /**
     * 锁定某型号的版本序列（须在事务内调用）：通过对该型号全部版本行加锁，
     * 串行化并发更新，保证版本号连续且生效版本唯一。返回当前最大版本号（无版本为 0）。
     */
    public int lockModelAndMaxVersion(String model) {
        Integer max = jdbc.query(
                "SELECT version_no FROM compensation_profile WHERE instrument_model = ? FOR UPDATE",
                (rs, rowNum) -> rs.getInt("version_no"), model)
                .stream().max(Integer::compareTo).orElse(0);
        return max;
    }

    /**
     * 将该型号当前生效版本置为非生效（须在持有该型号行锁的事务内调用）。
     */
    public void deactivateActive(String model) {
        jdbc.update("UPDATE compensation_profile SET active = FALSE WHERE instrument_model = ? AND active = TRUE",
                model);
    }

    /**
     * 追加一个生效版本并返回其 ID。
     */
    public long insertActive(String model, int versionNo, CompensationProfile profile) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO compensation_profile (instrument_model, version_no, temp_coeff, humidity_coeff, "
                            + "temp_min, temp_max, humidity_min, humidity_max, active, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, model);
            ps.setInt(2, versionNo);
            ps.setBigDecimal(3, profile.tempCoeff());
            ps.setBigDecimal(4, profile.humidityCoeff());
            ps.setBigDecimal(5, profile.tempMin());
            ps.setBigDecimal(6, profile.tempMax());
            ps.setBigDecimal(7, profile.humidityMin());
            ps.setBigDecimal(8, profile.humidityMax());
            ps.setObject(9, JdbcTimes.toDb(profile.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询某型号当前最大版本号（无版本为 0）；须在持有型号锁后调用。
     */
    public int maxVersionNo(String model) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM compensation_profile WHERE instrument_model = ?",
                Integer.class, model);
        return max == null ? 0 : max;
    }

    /**
     * 按 ID 查询（不加锁）。
     */
    public Optional<CompensationProfile> findById(long id) {
        return jdbc.query("SELECT * FROM compensation_profile WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行锁（须在事务内调用），用于提交/重算与版本更新并发时固化快照。
     */
    public Optional<CompensationProfile> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM compensation_profile WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询某型号当前生效版本（不加锁）。
     */
    public Optional<CompensationProfile> findActive(String model) {
        return jdbc.query(
                "SELECT * FROM compensation_profile WHERE instrument_model = ? AND active = TRUE",
                MAPPER, model)
                .stream().findFirst();
    }

    /**
     * 查询某型号全部版本（按版本号升序）。
     */
    public List<CompensationProfile> findByModel(String model) {
        return jdbc.query(
                "SELECT * FROM compensation_profile WHERE instrument_model = ? ORDER BY version_no",
                MAPPER, model);
    }
}
