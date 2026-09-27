package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.UncertaintyComponent;

/**
 * 不确定度分量持久化。分量按 (测量, 预算版本) 组织，历史预算版本只增不删；
 * 同一预算版本内名称由唯一约束保证唯一。
 */
@Repository
public class UncertaintyComponentRepository {

    private static final RowMapper<UncertaintyComponent> MAPPER = (rs, rowNum) -> new UncertaintyComponent(
            rs.getLong("id"),
            rs.getLong("measurement_id"),
            rs.getLong("budget_version"),
            rs.getString("component_name"),
            rs.getBigDecimal("magnitude"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public UncertaintyComponentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个分量到指定预算版本。名称重复时由唯一约束抛出 DuplicateKeyException。
     */
    public long insert(long measurementId, long budgetVersion, String name,
                       java.math.BigDecimal magnitude, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO uncertainty_component "
                            + "(measurement_id, budget_version, component_name, magnitude, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, measurementId);
            ps.setLong(2, budgetVersion);
            ps.setString(3, name);
            ps.setBigDecimal(4, magnitude);
            ps.setObject(5, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询某测量当前（最大预算版本）的全部分量，按名称升序保证排序稳定。
     */
    public List<UncertaintyComponent> findCurrent(long measurementId) {
        return jdbc.query(
                "SELECT * FROM uncertainty_component WHERE measurement_id = ? "
                        + "AND budget_version = (SELECT COALESCE(MAX(budget_version), 0) "
                        + "FROM uncertainty_component WHERE measurement_id = ?) "
                        + "ORDER BY component_name",
                MAPPER, measurementId, measurementId);
    }

    /**
     * 查询某测量指定预算版本的全部分量，按名称升序。
     */
    public List<UncertaintyComponent> findByBudgetVersion(long measurementId, long budgetVersion) {
        return jdbc.query(
                "SELECT * FROM uncertainty_component WHERE measurement_id = ? AND budget_version = ? "
                        + "ORDER BY component_name",
                MAPPER, measurementId, budgetVersion);
    }

    /**
     * 查询某测量的最大预算版本号；从未登记分量时返回 0。
     */
    public long currentBudgetVersion(long measurementId) {
        Long version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(budget_version), 0) FROM uncertainty_component WHERE measurement_id = ?",
                Long.class, measurementId);
        return version == null ? 0L : version;
    }
}
