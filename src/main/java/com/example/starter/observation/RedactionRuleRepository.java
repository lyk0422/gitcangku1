package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 脱敏规则版本持久化：redaction_rule 表登记规则版本；撤销仅更新状态与撤销时刻，登记行永不删除。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class RedactionRuleRepository {

    private static final RowMapper<RedactionRuleRecord> RULE_MAPPER = (rs, rowNum) -> new RedactionRuleRecord(
            rs.getString("rule_version"),
            rs.getString("description"),
            "ACTIVE".equals(rs.getString("status")),
            rs.getString("revoked_reason"),
            rs.getTimestamp("registered_at_utc").toInstant(),
            rs.getTimestamp("revoked_at_utc") == null ? null : rs.getTimestamp("revoked_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public RedactionRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 登记规则版本；rule_version 主键冲突时抛出重复键异常。
     */
    public void insert(RedactionRuleRecord record) {
        jdbcTemplate.update(
                "INSERT INTO redaction_rule (rule_version, description, status, revoked_reason, "
                        + "registered_at_utc, revoked_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.ruleVersion(), record.description(), record.active() ? "ACTIVE" : "REVOKED",
                record.revokedReason(), Timestamp.from(record.registeredAtUtc()),
                record.revokedAtUtc() == null ? null : Timestamp.from(record.revokedAtUtc()));
    }

    /**
     * 按规则版本查询；不存在时返回空。
     */
    public Optional<RedactionRuleRecord> findByRuleVersion(String ruleVersion) {
        return jdbcTemplate.query(
                        "SELECT rule_version, description, status, revoked_reason, registered_at_utc, revoked_at_utc "
                                + "FROM redaction_rule WHERE rule_version = ?",
                        RULE_MAPPER, ruleVersion)
                .stream().findFirst();
    }

    /**
     * 按规则版本查询并加行锁：脱敏提交在观测行锁后对规则加锁，与规则撤销按提交顺序串行裁决。
     */
    public Optional<RedactionRuleRecord> findByRuleVersionForUpdate(String ruleVersion) {
        return jdbcTemplate.query(
                        "SELECT rule_version, description, status, revoked_reason, registered_at_utc, revoked_at_utc "
                                + "FROM redaction_rule WHERE rule_version = ? FOR UPDATE",
                        RULE_MAPPER, ruleVersion)
                .stream().findFirst();
    }

    /**
     * 撤销规则版本：置状态、原因与撤销时刻；仅影响后续新脱敏请求，不回写既有脱敏版本。
     */
    public void revoke(String ruleVersion, String reason, java.time.Instant revokedAtUtc) {
        jdbcTemplate.update(
                "UPDATE redaction_rule SET status = 'REVOKED', revoked_reason = ?, revoked_at_utc = ? "
                        + "WHERE rule_version = ?",
                reason, Timestamp.from(revokedAtUtc), ruleVersion);
    }
}
