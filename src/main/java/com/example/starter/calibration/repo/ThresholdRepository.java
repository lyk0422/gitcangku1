package com.example.starter.calibration.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.InstrumentThreshold;

/**
 * 仪器不确定度阈值持久化。版本化只增不改；放行读取当时最新版本并固化快照。
 */
@Repository
public class ThresholdRepository {

    private static final RowMapper<InstrumentThreshold> MAPPER = (rs, rowNum) -> new InstrumentThreshold(
            rs.getLong("id"),
            rs.getString("instrument_id"),
            rs.getBigDecimal("threshold"),
            rs.getInt("version"),
            rs.getString("created_by"),
            rs.getString("request_id"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ThresholdRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记新版本阈值（须在持有仪器锁的事务内调用，保证版本号连续递增）。
     */
    public void insert(String instrumentId, BigDecimal threshold, int version,
                       String createdBy, String requestId, Instant createdAt) {
        jdbc.update("INSERT INTO instrument_threshold "
                        + "(instrument_id, threshold, version, created_by, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                instrumentId, threshold, version, createdBy, requestId, JdbcTimes.toDb(createdAt));
    }

    /**
     * 查询仪器当前最新版本阈值；不存在返回空。
     */
    public Optional<InstrumentThreshold> findCurrent(String instrumentId) {
        return jdbc.query("SELECT * FROM instrument_threshold WHERE instrument_id = ? "
                        + "ORDER BY version DESC LIMIT 1", MAPPER, instrumentId)
                .stream().findFirst();
    }

    /**
     * 查询仪器当前最大版本号；无阈值时返回 0。
     */
    public int currentVersion(String instrumentId) {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM instrument_threshold WHERE instrument_id = ?",
                Integer.class, instrumentId);
        return version == null ? 0 : version;
    }

    /**
     * 查询仪器全部阈值版本（按版本升序，稳定排序）。
     */
    public List<InstrumentThreshold> findHistory(String instrumentId) {
        return jdbc.query("SELECT * FROM instrument_threshold WHERE instrument_id = ? ORDER BY version",
                MAPPER, instrumentId);
    }
}
