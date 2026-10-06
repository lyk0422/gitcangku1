package com.example.starter.calibration.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.ResolutionConfig;

/**
 * 仪器分辨率格点配置持久化。每台仪器一行当前配置；
 * 更新按 expectedVersion 条件更新实现乐观并发，不换版回算既有测量。
 */
@Repository
public class ResolutionRepository {

    private static final RowMapper<ResolutionConfig> MAPPER = (rs, rowNum) -> new ResolutionConfig(
            rs.getString("instrument_id"),
            rs.getInt("version"),
            rs.getBigDecimal("grid_origin"),
            rs.getBigDecimal("grid_step"),
            rs.getString("updated_by"),
            JdbcTimes.fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ResolutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按仪器查询当前配置（不加锁）。
     */
    public Optional<ResolutionConfig> findByInstrument(String instrumentId) {
        return jdbc.query("SELECT * FROM instrument_resolution WHERE instrument_id = ?",
                MAPPER, instrumentId).stream().findFirst();
    }

    /**
     * 按仪器查询当前配置并加行锁（须在事务内调用），使提交测量与配置更新串行化。
     */
    public Optional<ResolutionConfig> findByInstrumentForUpdate(String instrumentId) {
        return jdbc.query("SELECT * FROM instrument_resolution WHERE instrument_id = ? FOR UPDATE",
                MAPPER, instrumentId).stream().findFirst();
    }

    /**
     * 新建配置，版本固定为 1；仪器已存在配置时抛出 DuplicateKeyException。
     */
    public void insert(String instrumentId, BigDecimal origin, BigDecimal step,
                       String updatedBy, Instant updatedAt) {
        jdbc.update("INSERT INTO instrument_resolution "
                        + "(instrument_id, version, grid_origin, grid_step, updated_by, updated_at) "
                        + "VALUES (?, 1, ?, ?, ?, ?)",
                instrumentId, origin, step, updatedBy, JdbcTimes.toDb(updatedAt));
    }

    /**
     * 按期望版本条件更新：仅当当前版本等于 expectedVersion 时生效，版本加 1。
     *
     * @return 实际更新行数；0 表示版本冲突或配置不存在
     */
    public int updateWithVersion(String instrumentId, int expectedVersion,
                                 BigDecimal origin, BigDecimal step,
                                 String updatedBy, Instant updatedAt) {
        return jdbc.update("UPDATE instrument_resolution "
                        + "SET version = version + 1, grid_origin = ?, grid_step = ?, "
                        + "updated_by = ?, updated_at = ? "
                        + "WHERE instrument_id = ? AND version = ?",
                origin, step, updatedBy, JdbcTimes.toDb(updatedAt), instrumentId, expectedVersion);
    }
}
