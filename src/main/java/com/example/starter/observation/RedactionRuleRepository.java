package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 脱敏规则版本登记持久化：redaction_rule 不可更新内容，只允许把规则版本标记为撤销。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class RedactionRuleRepository {

    private static final RowMapper<RedactionRule> RULE_MAPPER = (rs, rowNum) -> new RedactionRule(
            rs.getString("rule_code"),
            rs.getInt("rule_version"),
            rs.getString("rule_name"),
            rs.getBoolean("revoked"),
            rs.getTimestamp("revoked_at_utc") == null ? null : rs.getTimestamp("revoked_at_utc").toInstant(),
            rs.getTimestamp("registered_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public RedactionRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 登记新的规则版本；(rule_code, rule_version) 已存在时抛出重复键异常。
     */
    public void insert(RedactionRule rule) {
        jdbcTemplate.update(
                "INSERT INTO redaction_rule (rule_code, rule_version, rule_name, revoked, revoked_at_utc, "
                        + "registered_at_utc, created_at) VALUES (?, ?, ?, FALSE, NULL, ?, CURRENT_TIMESTAMP)",
                rule.ruleCode(), rule.ruleVersion(), rule.ruleName(),
                Timestamp.from(rule.registeredAtUtc()));
    }

    /**
     * 按规则身份与版本号查询登记记录；不存在时返回空。
     */
    public Optional<RedactionRule> find(String ruleCode, int ruleVersion) {
        return jdbcTemplate.query(
                        "SELECT rule_code, rule_version, rule_name, revoked, revoked_at_utc, registered_at_utc "
                                + "FROM redaction_rule WHERE rule_code = ? AND rule_version = ?",
                        RULE_MAPPER, ruleCode, ruleVersion)
                .stream().findFirst();
    }

    /**
     * 撤销规则版本登记；仅当当前未撤销时更新成功，返回是否实际更新。
     */
    public boolean markRevoked(String ruleCode, int ruleVersion, Instant revokedAtUtc) {
        int updated = jdbcTemplate.update(
                "UPDATE redaction_rule SET revoked = TRUE, revoked_at_utc = ? "
                        + "WHERE rule_code = ? AND rule_version = ? AND revoked = FALSE",
                Timestamp.from(revokedAtUtc), ruleCode, ruleVersion);
        return updated > 0;
    }
}
