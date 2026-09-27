package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 脱敏规则版本持久化：登记后仅允许撤销（状态翻转），不允许修改描述或删除。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class RedactionRuleRepository {

    private static final RowMapper<RedactionRule> RULE_MAPPER = (rs, rowNum) -> new RedactionRule(
            rs.getString("rule_version"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getTimestamp("registered_at_utc").toInstant(),
            rs.getTimestamp("revoked_at_utc") == null ? null : rs.getTimestamp("revoked_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public RedactionRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 登记规则版本；rule_version 主键冲突时抛出重复键异常。
     */
    public void insert(RedactionRule rule) {
        jdbcTemplate.update(
                "INSERT INTO redaction_rule (rule_version, description, status, registered_at_utc, revoked_at_utc) "
                        + "VALUES (?, ?, ?, ?, NULL)",
                rule.ruleVersion(), rule.description(), rule.status(),
                Timestamp.from(rule.registeredAtUtc()));
    }

    /**
     * 按规则版本查询；不存在时返回空。
     */
    public Optional<RedactionRule> findByVersion(String ruleVersion) {
        return jdbcTemplate.query(
                        "SELECT rule_version, description, status, registered_at_utc, revoked_at_utc "
                                + "FROM redaction_rule WHERE rule_version = ?",
                        RULE_MAPPER, ruleVersion)
                .stream().findFirst();
    }

    /**
     * 撤销规则版本：置状态与撤销时刻，登记信息与既有脱敏版本不受影响。
     */
    public void markRevoked(String ruleVersion, Timestamp revokedAtUtc) {
        jdbcTemplate.update(
                "UPDATE redaction_rule SET status = ?, revoked_at_utc = ? WHERE rule_version = ?",
                RedactionRule.STATUS_REVOKED, revokedAtUtc, ruleVersion);
    }
}
