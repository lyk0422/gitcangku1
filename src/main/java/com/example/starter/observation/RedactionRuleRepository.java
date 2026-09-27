package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 脱敏规则版本登记持久化。
 */
@Repository
public class RedactionRuleRepository {

    private static final RowMapper<RedactionRuleRecord> MAPPER = (rs, rowNum) -> new RedactionRuleRecord(
            rs.getString("rule_version"),
            "ACTIVE".equals(rs.getString("status")),
            rs.getString("description"),
            rs.getTimestamp("created_at_utc").toInstant(),
            Optional.ofNullable(rs.getTimestamp("revoked_at_utc"))
                    .map(java.sql.Timestamp::toInstant).orElse(null));

    private final JdbcTemplate jdbcTemplate;

    public RedactionRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 登记 ACTIVE 规则版本。
     */
    public void insert(RedactionRuleRecord record) {
        jdbcTemplate.update(
                "INSERT INTO redaction_rule (rule_version, status, description, created_at_utc, revoked_at_utc) "
                        + "VALUES (?, 'ACTIVE', ?, ?, NULL)",
                record.ruleVersion(), record.description(), Timestamp.from(record.createdAtUtc()));
    }

    /**
     * 按规则版本查询。
     */
    public Optional<RedactionRuleRecord> find(String ruleVersion) {
        return jdbcTemplate.query(
                        "SELECT rule_version, status, description, created_at_utc, revoked_at_utc "
                                + "FROM redaction_rule WHERE rule_version = ?",
                        MAPPER, ruleVersion)
                .stream().findFirst();
    }

    /**
     * 按规则版本查询并加锁，用于撤销与脱敏提交并发时的状态裁决。
     */
    public Optional<RedactionRuleRecord> findForUpdate(String ruleVersion) {
        return jdbcTemplate.query(
                        "SELECT rule_version, status, description, created_at_utc, revoked_at_utc "
                                + "FROM redaction_rule WHERE rule_version = ? FOR UPDATE",
                        MAPPER, ruleVersion)
                .stream().findFirst();
    }

    /**
     * 撤销规则：置 REVOKED 与撤销时刻（幂等调用时状态不再变化）。
     */
    public void markRevoked(String ruleVersion, java.time.Instant revokedAtUtc) {
        jdbcTemplate.update(
                "UPDATE redaction_rule SET status = 'REVOKED', revoked_at_utc = ? WHERE rule_version = ?",
                Timestamp.from(revokedAtUtc), ruleVersion);
    }
}
