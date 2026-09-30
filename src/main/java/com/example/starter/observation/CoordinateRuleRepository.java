package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 坐标基准转换规则持久化：规则发布后不可变、不可删除。
 * 所有 SQL 使用参数化查询；时刻以 UTC 写入 TIMESTAMP 列。
 */
@Repository
public class CoordinateRuleRepository {

    private static final RowMapper<CoordinateRule> RULE_MAPPER = (rs, rowNum) -> new CoordinateRule(
            rs.getInt("rule_version"),
            rs.getString("target_frame"),
            rs.getBigDecimal("offset_x"),
            rs.getBigDecimal("offset_y"),
            rs.getTimestamp("effective_from_utc").toInstant(),
            effectiveToInstant(rs.getTimestamp("effective_to_utc")));

    private final JdbcTemplate jdbcTemplate;

    public CoordinateRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static Instant effectiveToInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 发布新版本规则（不可变）；版本号冲突由主键抛出重复键异常。
     */
    public void insert(CoordinateRule rule) {
        jdbcTemplate.update(
                "INSERT INTO coordinate_rule (rule_version, target_frame, offset_x, offset_y, "
                        + "effective_from_utc, effective_to_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                rule.ruleVersion(), rule.targetFrame(), rule.offsetX(), rule.offsetY(),
                Timestamp.from(rule.effectiveFromUtc()),
                rule.effectiveToUtc() == null ? null : Timestamp.from(rule.effectiveToUtc()));
    }

    /**
     * 按版本号查询规则；不存在时返回空。
     */
    public Optional<CoordinateRule> findByVersion(int ruleVersion) {
        return jdbcTemplate.query(
                        "SELECT rule_version, target_frame, offset_x, offset_y, effective_from_utc, effective_to_utc "
                                + "FROM coordinate_rule WHERE rule_version = ?",
                        RULE_MAPPER, ruleVersion)
                .stream().findFirst();
    }

    /**
     * 查询在指定 UTC 时刻命中的全部规则：生效区间左闭右开 [from, to)，to 为 NULL 视为长期有效。
     * 调用方须已持有规则守卫行锁，保证与规则发布串行化、结果稳定。
     */
    public List<CoordinateRule> findEffectiveAt(Instant observedAtUtc) {
        Timestamp at = Timestamp.from(observedAtUtc);
        return jdbcTemplate.query(
                "SELECT rule_version, target_frame, offset_x, offset_y, effective_from_utc, effective_to_utc "
                        + "FROM coordinate_rule "
                        + "WHERE effective_from_utc <= ? "
                        + "AND (effective_to_utc IS NULL OR ? < effective_to_utc) "
                        + "ORDER BY rule_version ASC",
                RULE_MAPPER, at, at);
    }
}
