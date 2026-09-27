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
 * 不确定度分量持久化。当前集合按测量整体替换（先删后插，须在持有测量行锁的事务内调用）。
 */
@Repository
public class ComponentRepository {

    private static final RowMapper<UncertaintyComponent> MAPPER = (rs, rowNum) -> new UncertaintyComponent(
            rs.getLong("id"),
            rs.getLong("measurement_id"),
            rs.getString("component_name"),
            rs.getBigDecimal("component_value"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ComponentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 整体替换某测量的分量集合：先清空再逐条插入（须在持有测量行锁的事务内调用）。
     */
    public void replaceAll(long measurementId, List<ComponentValue> components, Instant now) {
        jdbc.update("DELETE FROM uncertainty_component WHERE measurement_id = ?", measurementId);
        for (ComponentValue component : components) {
            jdbc.update("INSERT INTO uncertainty_component "
                            + "(measurement_id, component_name, component_value, created_at) VALUES (?, ?, ?, ?)",
                    measurementId, component.name(), component.value(), JdbcTimes.toDb(now));
        }
    }

    /**
     * 查询某测量当前的全部分量，按名称升序（稳定排序）。
     */
    public List<UncertaintyComponent> findByMeasurementId(long measurementId) {
        return jdbc.query("SELECT * FROM uncertainty_component WHERE measurement_id = ? "
                        + "ORDER BY component_name", MAPPER, measurementId);
    }

    /**
     * 分量名值对（写入用）。
     *
     * @param name  分量名称
     * @param value 分量值，非负
     */
    public record ComponentValue(String name, BigDecimal value) {
    }
}
