package com.example.starter.calibration.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.UncertaintyThreshold;

/**
 * 仪器不确定度阈值持久化。阈值按版本递增固化，旧版本永不改写。
 */
@Repository
public class UncertaintyThresholdRepository {

    private static final RowMapper<UncertaintyThreshold> MAPPER = (rs, rowNum) -> new UncertaintyThreshold(
            rs.getLong("id"),
            rs.getString("instrument_id"),
            rs.getInt("version"),
            rs.getBigDecimal("max_uncertainty"),
            rs.getString("created_by"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public UncertaintyThresholdRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新版本阈值（须在持有仪器锁的事务内调用）。
     */
    public void insert(String instrumentId, int version, BigDecimal maxUncertainty,
                       String createdBy, Instant createdAt) {
        jdbc.update("INSERT INTO uncertainty_threshold "
                        + "(instrument_id, version, max_uncertainty, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                instrumentId, version, maxUncertainty, createdBy, JdbcTimes.toDb(createdAt));
    }

    /**
     * 查询仪器当前最新版本阈值；不存在返回空。
     */
    public Optional<UncertaintyThreshold> findCurrent(String instrumentId) {
        return jdbc.query("SELECT * FROM uncertainty_threshold WHERE instrument_id = ? "
                        + "ORDER BY version DESC LIMIT 1", MAPPER, instrumentId)
                .stream().findFirst();
    }

    /**
     * 查询仪器全部阈值版本（按版本升序，保证稳定排序）。
     */
    public List<UncertaintyThreshold> findHistory(String instrumentId) {
        return jdbc.query("SELECT * FROM uncertainty_threshold WHERE instrument_id = ? ORDER BY version",
                MAPPER, instrumentId);
    }
}
