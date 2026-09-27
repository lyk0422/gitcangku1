package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 附件脱敏版本持久化：attachment_redaction 只追加、永不更新内容；
 * 撤销仅把 public_active 置为 FALSE 并补记撤销时刻/理由。所有 SQL 使用参数化查询。
 */
@Repository
public class RedactionRepository {

    private static final RowMapper<RedactionVersion> VERSION_MAPPER = (rs, rowNum) -> new RedactionVersion(
            rs.getString("redaction_id"),
            rs.getString("batch_id"),
            rs.getString("request_id"),
            rs.getString("attachment_id"),
            rs.getString("observation_id"),
            rs.getInt("attachment_version"),
            rs.getString("rule_code"),
            rs.getInt("rule_version"),
            rs.getString("redacted_summary"),
            rs.getString("replacement_note"),
            rs.getString("reason"),
            rs.getString("operator"),
            rs.getBoolean("public_active"),
            rs.getTimestamp("created_at_utc").toInstant(),
            rs.getTimestamp("revoked_at_utc") == null ? null : rs.getTimestamp("revoked_at_utc").toInstant(),
            rs.getString("revoke_reason"),
            rs.getString("revoked_by"));

    private final JdbcTemplate jdbcTemplate;

    public RedactionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入脱敏批次父行；batch_id 已被其他请求占用时抛出重复键异常（跨请求复用按 409 处理）。
     */
    public void insertBatch(String batchId, String requestId, String observationId,
                            String operator, String reason, Instant createdAtUtc) {
        jdbcTemplate.update(
                "INSERT INTO redaction_batch (batch_id, request_id, observation_id, operator, reason, "
                        + "created_at_utc, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                batchId, requestId, observationId, operator, reason, Timestamp.from(createdAtUtc));
    }

    /**
     * 追加一条脱敏版本；(attachment_id, rule_code, rule_version) 重复时抛出重复键异常。
     */
    public void insert(RedactionVersion version) {
        jdbcTemplate.update(
                "INSERT INTO attachment_redaction (redaction_id, batch_id, request_id, attachment_id, "
                        + "observation_id, attachment_version, rule_code, rule_version, redacted_summary, "
                        + "replacement_note, reason, operator, public_active, created_at_utc, revoked_at_utc, "
                        + "revoke_reason, revoked_by, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, NULL, NULL, NULL, CURRENT_TIMESTAMP)",
                version.redactionId(), version.batchId(), version.requestId(), version.attachmentId(),
                version.observationId(), version.attachmentVersion(), version.ruleCode(), version.ruleVersion(),
                version.redactedSummary(), version.replacementNote(), version.reason(), version.operator(),
                Timestamp.from(version.createdAtUtc()));
    }

    /**
     * 按全局唯一标识查询脱敏版本；不存在时返回空。
     */
    public Optional<RedactionVersion> findById(String redactionId) {        return jdbcTemplate.query(
                        "SELECT redaction_id, batch_id, request_id, attachment_id, observation_id, "
                                + "attachment_version, rule_code, rule_version, redacted_summary, replacement_note, "
                                + "reason, operator, public_active, created_at_utc, revoked_at_utc, revoke_reason, "
                                + "revoked_by FROM attachment_redaction WHERE redaction_id = ?",
                        VERSION_MAPPER, redactionId)
                .stream().findFirst();
    }

    /**
     * 按全局唯一标识查询并加行锁，用于撤销事务内串行化。
     */
    public Optional<RedactionVersion> findByIdForUpdate(String redactionId) {
        return jdbcTemplate.query(
                        "SELECT redaction_id, batch_id, request_id, attachment_id, observation_id, "
                                + "attachment_version, rule_code, rule_version, redacted_summary, replacement_note, "
                                + "reason, operator, public_active, created_at_utc, revoked_at_utc, revoke_reason, "
                                + "revoked_by FROM attachment_redaction WHERE redaction_id = ? FOR UPDATE",
                        VERSION_MAPPER, redactionId)
                .stream().findFirst();
    }

    /**
     * 查询附件的全部脱敏版本并加行锁，用于新脱敏事务内确定下一版本号与重复规则校验。
     */
    public List<RedactionVersion> findByAttachmentForUpdate(String attachmentId) {
        return jdbcTemplate.query(
                "SELECT redaction_id, batch_id, request_id, attachment_id, observation_id, "
                        + "attachment_version, rule_code, rule_version, redacted_summary, replacement_note, "
                        + "reason, operator, public_active, created_at_utc, revoked_at_utc, revoke_reason, "
                        + "revoked_by FROM attachment_redaction WHERE attachment_id = ? "
                        + "ORDER BY attachment_version ASC FOR UPDATE",
                VERSION_MAPPER, attachmentId);
    }

    /**
     * 查询附件的全部脱敏版本（不加锁），按版本号升序。
     */
    public List<RedactionVersion> findByAttachment(String attachmentId) {
        return jdbcTemplate.query(
                "SELECT redaction_id, batch_id, request_id, attachment_id, observation_id, "
                        + "attachment_version, rule_code, rule_version, redacted_summary, replacement_note, "
                        + "reason, operator, public_active, created_at_utc, revoked_at_utc, revoke_reason, "
                        + "revoked_by FROM attachment_redaction WHERE attachment_id = ? "
                        + "ORDER BY attachment_version ASC",
                VERSION_MAPPER, attachmentId);
    }

    /**
     * 查询附件当前仍具公开展示资格的最新版本（版本号最大者）；无有效版本时返回空。
     */
    public Optional<RedactionVersion> findPublicLatest(String attachmentId) {
        return jdbcTemplate.query(
                        "SELECT redaction_id, batch_id, request_id, attachment_id, observation_id, "
                                + "attachment_version, rule_code, rule_version, redacted_summary, replacement_note, "
                                + "reason, operator, public_active, created_at_utc, revoked_at_utc, revoke_reason, "
                                + "revoked_by FROM attachment_redaction WHERE attachment_id = ? AND public_active = TRUE "
                                + "ORDER BY attachment_version DESC LIMIT 1",
                        VERSION_MAPPER, attachmentId)
                .stream().findFirst();
    }

    /**
     * 查询观测记录下全部附件的最新公开版本（每个附件至多一行），按附件标识排序。
     */
    public List<RedactionVersion> findPublicLatestByObservation(String observationId) {
        return jdbcTemplate.query(
                "SELECT r.redaction_id, r.batch_id, r.request_id, r.attachment_id, r.observation_id, "
                        + "r.attachment_version, r.rule_code, r.rule_version, r.redacted_summary, r.replacement_note, "
                        + "r.reason, r.operator, r.public_active, r.created_at_utc, r.revoked_at_utc, "
                        + "r.revoke_reason, r.revoked_by FROM attachment_redaction r "
                        + "INNER JOIN (SELECT attachment_id, MAX(attachment_version) AS max_version "
                        + "FROM attachment_redaction WHERE observation_id = ? AND public_active = TRUE "
                        + "GROUP BY attachment_id) m ON r.attachment_id = m.attachment_id "
                        + "AND r.attachment_version = m.max_version ORDER BY r.attachment_id ASC",
                VERSION_MAPPER, observationId);
    }

    /**
     * 撤销公开展示资格：仅当当前仍有效时更新成功，返回是否实际更新。
     */
    public boolean markRevoked(String redactionId, Instant revokedAtUtc, String reason, String operator) {
        int updated = jdbcTemplate.update(
                "UPDATE attachment_redaction SET public_active = FALSE, revoked_at_utc = ?, "
                        + "revoke_reason = ?, revoked_by = ? WHERE redaction_id = ? AND public_active = TRUE",
                Timestamp.from(revokedAtUtc), reason, operator, redactionId);
        return updated > 0;
    }

    /**
     * 统计 UTC 左闭右开区间 [from, to) 内生成的脱敏版本数。
     */
    public int countCreatedBetween(Instant from, Instant to) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE created_at_utc >= ? AND created_at_utc < ?",
                Integer.class, Timestamp.from(from), Timestamp.from(to));
        return count == null ? 0 : count;
    }

    /**
     * 统计 UTC 左闭右开区间 [from, to) 内撤销的脱敏版本数。
     */
    public int countRevokedBetween(Instant from, Instant to) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE revoked_at_utc >= ? AND revoked_at_utc < ?",
                Integer.class, Timestamp.from(from), Timestamp.from(to));
        return count == null ? 0 : count;
    }

    /**
     * 查询 UTC 左闭右开区间 [from, to) 内最早的脱敏版本生成时刻；无数据时返回空。
     */
    public Optional<Instant> findFirstCreatedAtBetween(Instant from, Instant to) {
        return jdbcTemplate.query(
                        "SELECT MIN(created_at_utc) FROM attachment_redaction "
                                + "WHERE created_at_utc >= ? AND created_at_utc < ?",
                        rs -> rs.next() && rs.getTimestamp(1) != null
                                ? Optional.of(rs.getTimestamp(1).toInstant()) : Optional.empty(),
                        Timestamp.from(from), Timestamp.from(to));
    }

    /**
     * 查询 UTC 左闭右开区间 [from, to) 内最晚的脱敏版本生成时刻；无数据时返回空。
     */
    public Optional<Instant> findLastCreatedAtBetween(Instant from, Instant to) {
        return jdbcTemplate.query(
                        "SELECT MAX(created_at_utc) FROM attachment_redaction "
                                + "WHERE created_at_utc >= ? AND created_at_utc < ?",
                        rs -> rs.next() && rs.getTimestamp(1) != null
                                ? Optional.of(rs.getTimestamp(1).toInstant()) : Optional.empty(),
                        Timestamp.from(from), Timestamp.from(to));
    }
}
