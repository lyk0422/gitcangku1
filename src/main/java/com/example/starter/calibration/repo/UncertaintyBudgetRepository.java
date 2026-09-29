package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.UncertaintyBudget;

/**
 * 不确定度预算版本头持久化：每次整体替换追加新版本（含空分量预算），只增不改。
 */
@Repository
public class UncertaintyBudgetRepository {

    private final JdbcTemplate jdbc;

    public UncertaintyBudgetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加预算版本头并返回头 ID。
     */
    public long insert(long measurementId, int budgetVersion, String updatedBy, java.time.Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO uncertainty_budget (measurement_id, budget_version, updated_by, created_at) "
                            + "VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, measurementId);
            ps.setInt(2, budgetVersion);
            ps.setString(3, updatedBy);
            ps.setObject(4, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 某测量当前最大预算版本号；无预算返回 0。
     */
    public int maxBudgetVersion(long measurementId) {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(budget_version), 0) FROM uncertainty_budget WHERE measurement_id = ?",
                Integer.class, measurementId);
        return version == null ? 0 : version;
    }

    /**
     * 读取某测量某预算版本头。
     */
    public Optional<UncertaintyBudget> find(long measurementId, int budgetVersion) {
        return jdbc.query(
                        "SELECT * FROM uncertainty_budget WHERE measurement_id = ? AND budget_version = ?",
                        (rs, n) -> new UncertaintyBudget(
                                rs.getLong("id"),
                                rs.getLong("measurement_id"),
                                rs.getInt("budget_version"),
                                rs.getString("updated_by"),
                                JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class))),
                        measurementId, budgetVersion)
                .stream().findFirst();
    }
}
