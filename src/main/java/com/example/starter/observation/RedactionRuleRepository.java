package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 脱敏规则版本持久化。规则定义登记后不可变；撤销只更新状态与撤销字段（不可逆）。
 * 所有 SQL 使用参数化查询；敏感词条列表以 JSON 原文存储。
 */
@Repository
public class RedactionRuleRepository {

    private static final TypeReference<List<String>> TERMS_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RedactionRuleRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<RedactionRuleRecord> MAPPER = (rs, rowNum) -> new RedactionRuleRecord(
            rs.getString("rule_version"),
            rs.getString("description"),
            readTerms(rs.getString("mask_terms")),
            rs.getString("replacement_token"),
            RuleStatus.fromValue(rs.getString("status")),
            rs.getTimestamp("registered_at_utc").toInstant(),
            toInstant(rs.getTimestamp("revoked_at_utc")),
            rs.getString("revoke_reason"));

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 按规则版本标识读取（不加锁）。
     */
    public Optional<RedactionRuleRecord> find(String ruleVersion) {
        return jdbcTemplate.query(
                        "SELECT rule_version, description, mask_terms, replacement_token, status, "
                                + "registered_at_utc, revoked_at_utc, revoke_reason "
                                + "FROM redaction_rule WHERE rule_version = ?",
                        MAPPER, ruleVersion)
                .stream().findFirst();
    }

    /**
     * 按规则版本标识读取并加行锁（SELECT ... FOR UPDATE），用于撤销事务内串行化。
     */
    public Optional<RedactionRuleRecord> findForUpdate(String ruleVersion) {
        return jdbcTemplate.query(
                        "SELECT rule_version, description, mask_terms, replacement_token, status, "
                                + "registered_at_utc, revoked_at_utc, revoke_reason "
                                + "FROM redaction_rule WHERE rule_version = ? FOR UPDATE",
                        MAPPER, ruleVersion)
                .stream().findFirst();
    }

    /**
     * 登记新规则版本（状态 ACTIVE）。
     */
    public void insert(RedactionRuleRecord record) {
        jdbcTemplate.update(
                "INSERT INTO redaction_rule (rule_version, description, mask_terms, replacement_token, "
                        + "status, registered_at_utc, revoked_at_utc, revoke_reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)",
                record.ruleVersion(), record.description(), writeTerms(record.maskTerms()),
                record.replacementToken(), record.status().name(),
                Timestamp.from(record.registeredAtUtc()));
    }

    /**
     * 撤销规则版本：置状态为 REVOKED 并记录撤销时刻与理由。不可逆。
     */
    public void revoke(String ruleVersion, java.time.Instant revokedAtUtc, String reason) {
        jdbcTemplate.update(
                "UPDATE redaction_rule SET status = 'REVOKED', revoked_at_utc = ?, revoke_reason = ? "
                        + "WHERE rule_version = ?",
                Timestamp.from(revokedAtUtc), reason, ruleVersion);
    }

    private String writeTerms(List<String> terms) {
        try {
            return objectMapper.writeValueAsString(terms);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize mask terms", e);
        }
    }

    private List<String> readTerms(String json) {
        try {
            return objectMapper.readValue(json, TERMS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize mask terms", e);
        }
    }
}
