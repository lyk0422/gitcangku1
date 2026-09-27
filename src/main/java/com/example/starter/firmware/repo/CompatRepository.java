package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.CompatRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 固件兼容矩阵数据访问。同型号+来源+目标版本唯一，重复登记按最新参数覆盖。
 */
@Repository
public class CompatRepository {

    private static final RowMapper<CompatRule> MAPPER = (rs, n) -> new CompatRule(
            rs.getLong("id"), rs.getString("model"), rs.getString("from_version"),
            rs.getString("to_version"), rs.getBoolean("allowed"), rs.getString("updated_by"));

    private static final String COLUMNS = "id, model, from_version, to_version, allowed, updated_by";

    private final JdbcTemplate jdbc;

    public CompatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入或覆盖（H2 MODE=MySQL 支持 ON DUPLICATE KEY UPDATE）。
     */
    public void upsert(String model, String fromVersion, String toVersion, boolean allowed, String operator) {
        jdbc.update("INSERT INTO firmware_compat (model, from_version, to_version, allowed, updated_by)"
                + " VALUES (?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE allowed = VALUES(allowed), updated_by = VALUES(updated_by),"
                + " updated_at = CURRENT_TIMESTAMP", model, fromVersion, toVersion, allowed, operator);
    }

    public Optional<CompatRule> find(String model, String fromVersion, String toVersion) {
        return jdbc.query("SELECT " + COLUMNS + " FROM firmware_compat"
                        + " WHERE model = ? AND from_version = ? AND to_version = ?",
                MAPPER, model, fromVersion, toVersion).stream().findFirst();
    }

    public List<CompatRule> findByModel(String model) {
        return jdbc.query("SELECT " + COLUMNS + " FROM firmware_compat WHERE model = ? ORDER BY id",
                MAPPER, model);
    }
}
