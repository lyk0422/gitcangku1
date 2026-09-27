package com.example.starter.calibration.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.UncertaintyComponent;

/**
 * 不确定度分量持久化。批量修改整体替换：先删后插，须在持有测量行锁的事务内调用。
 */
@Repository
public class UncertaintyComponentRepository {

    private static final RowMapper<UncertaintyComponent> MAPPER = (rs, rowNum) -> new UncertaintyComponent(
            rs.getLong("id"),
            rs.getLong("measurement_id"),
            rs.getString("component_name"),
            rs.getBigDecimal("component_value"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public UncertaintyComponentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询某测量的全部分量（按分量名称升序，保证稳定排序）。
     */
    public List<UncertaintyComponent> findByMeasurementId(long measurementId) {
        return jdbc.query("SELECT * FROM uncertainty_component WHERE measurement_id = ? "
                        + "ORDER BY component_name", MAPPER, measurementId);
    }

    /**
     * 删除某测量的全部分量（批量替换的第一步）。
     */
    public void deleteByMeasurementId(long measurementId) {
        jdbc.update("DELETE FROM uncertainty_component WHERE measurement_id = ?", measurementId);
    }

    /**
     * 插入一个分量。
     */
    public void insert(long measurementId, String name, BigDecimal value, Instant createdAt) {
        jdbc.update("INSERT INTO uncertainty_component "
                        + "(measurement_id, component_name, component_value, created_at) VALUES (?, ?, ?, ?)",
                measurementId, name, value, JdbcTimes.toDb(createdAt));
    }
}
