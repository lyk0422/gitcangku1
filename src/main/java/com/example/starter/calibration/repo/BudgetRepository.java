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

import com.example.starter.calibration.model.UncertaintyBudget;
import com.example.starter.calibration.model.UncertaintyComponent;
import com.example.starter.calibration.model.UncertaintyMethod;

/**
 * 不确定度预算版本与分量持久化。预算只增不改，分量随预算版本不可变；
 * (measurement_id, budget_version) 与 (budget_id, component_name) 由唯一约束兜底。
 */
@Repository
public class BudgetRepository {

    private static final RowMapper<UncertaintyBudget> BUDGET_MAPPER = (rs, rowNum) -> new UncertaintyBudget(
            rs.getLong("id"),
            rs.getLong("measurement_id"),
            rs.getInt("budget_version"),
            UncertaintyMethod.valueOf(rs.getString("calc_method")),
            rs.getBigDecimal("combined_uncertainty"),
            rs.getLong("threshold_id"),
            rs.getInt("threshold_version"),
            rs.getBigDecimal("threshold_value"),
            rs.getString("created_by"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)),
            List.of());

    private static final RowMapper<UncertaintyComponent> COMPONENT_MAPPER = (rs, rowNum) ->
            new UncertaintyComponent(
                    rs.getLong("id"),
                    rs.getLong("budget_id"),
                    rs.getString("component_name"),
                    rs.getBigDecimal("component_value"),
                    rs.getInt("ordinal"),
                    JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public BudgetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入预算版本头并返回新 ID。
     */
    public long insertBudget(UncertaintyBudget budget) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO uncertainty_budget "
                            + "(measurement_id, budget_version, calc_method, combined_uncertainty, "
                            + "threshold_id, threshold_version, threshold_value, created_by, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, budget.measurementId());
            ps.setInt(2, budget.budgetVersion());
            ps.setString(3, budget.method().name());
            ps.setBigDecimal(4, budget.combinedUncertainty());
            ps.setLong(5, budget.thresholdId());
            ps.setInt(6, budget.thresholdVersion());
            ps.setBigDecimal(7, budget.thresholdValue());
            ps.setString(8, budget.createdBy());
            ps.setObject(9, JdbcTimes.toDb(budget.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 插入分量（须在插入预算版本头之后、同一事务内调用）。
     */
    public void insertComponent(long budgetId, String name, BigDecimal value, int ordinal, Instant createdAt) {
        jdbc.update("INSERT INTO uncertainty_component "
                        + "(budget_id, component_name, component_value, ordinal, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                budgetId, name, value, ordinal, JdbcTimes.toDb(createdAt));
    }

    /**
     * 按预算版本 ID 查询头（不含分量）。
     */
    public Optional<UncertaintyBudget> findById(long budgetId) {
        return jdbc.query("SELECT * FROM uncertainty_budget WHERE id = ?", BUDGET_MAPPER, budgetId)
                .stream().findFirst();
    }

    /**
     * 按预算版本 ID 查询全部分量，按 (ordinal, id) 稳定升序。
     */
    public List<UncertaintyComponent> findComponents(long budgetId) {
        return jdbc.query(
                "SELECT * FROM uncertainty_component WHERE budget_id = ? ORDER BY ordinal, id",
                COMPONENT_MAPPER, budgetId);
    }

    /**
     * 查询测量的全部预算版本头（不含分量），按版本号升序，保证历史排序稳定。
     */
    public List<UncertaintyBudget> findVersionsByMeasurement(long measurementId) {
        return jdbc.query(
                "SELECT * FROM uncertainty_budget WHERE measurement_id = ? ORDER BY budget_version",
                BUDGET_MAPPER, measurementId);
    }

    /**
     * 测量内当前最大预算版本号；从未登记返回 0。须在持有测量行锁的事务内调用。
     */
    public int nextBudgetVersionNumber(long measurementId) {
        Integer v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(budget_version), 0) FROM uncertainty_budget WHERE measurement_id = ?",
                Integer.class, measurementId);
        return v == null ? 0 : v;
    }
}
