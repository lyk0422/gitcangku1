package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 附件脱敏版本持久化：attachment_redaction 行只增不改；撤销仅更新状态、原因与撤销时刻。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class AttachmentRedactionRepository {

    private static final RowMapper<AttachmentRedactionRecord> REDACTION_MAPPER =
            (rs, rowNum) -> new AttachmentRedactionRecord(
                    rs.getString("redaction_id"),
                    rs.getString("observation_id"),
                    rs.getString("attachment_id"),
                    rs.getInt("redaction_version"),
                    rs.getString("request_id"),
                    rs.getString("rule_version"),
                    rs.getString("display_summary"),
                    rs.getString("replacement_note"),
                    "ACTIVE".equals(rs.getString("status")),
                    rs.getString("revoke_reason"),
                    rs.getTimestamp("created_at_utc").toInstant(),
                    rs.getTimestamp("revoked_at_utc") == null ? null : rs.getTimestamp("revoked_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public AttachmentRedactionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一个脱敏版本；唯一键冲突（同附件同规则版本、同附件同版本号、同脱敏请求同附件）抛出重复键异常。
     */
    public void insert(AttachmentRedactionRecord record) {
        jdbcTemplate.update(
                "INSERT INTO attachment_redaction (redaction_id, observation_id, attachment_id, redaction_version, "
                        + "request_id, rule_version, display_summary, replacement_note, status, revoke_reason, "
                        + "created_at_utc, revoked_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.redactionId(), record.observationId(), record.attachmentId(), record.redactionVersion(),
                record.requestId(), record.ruleVersion(), record.displaySummary(), record.replacementNote(),
                record.active() ? "ACTIVE" : "REVOKED", record.revokeReason(),
                Timestamp.from(record.createdAtUtc()),
                record.revokedAtUtc() == null ? null : Timestamp.from(record.revokedAtUtc()));
    }

    /**
     * 查询某附件当前最大脱敏版本号；无脱敏版本时返回空。
     */
    public Optional<Integer> findMaxVersion(String attachmentId) {
        return jdbcTemplate.query(
                        "SELECT MAX(redaction_version) AS max_version FROM attachment_redaction WHERE attachment_id = ?",
                        (rs, rowNum) -> rs.getInt("max_version"), attachmentId)
                .stream().filter(v -> v > 0).findFirst();
    }

    /**
     * 查询某附件最新有效（ACTIVE）脱敏版本；无有效活跃版本时返回空。
     */
    public Optional<AttachmentRedactionRecord> findLatestActive(String attachmentId) {
        return jdbcTemplate.query(
                        "SELECT redaction_id, observation_id, attachment_id, redaction_version, request_id, "
                                + "rule_version, display_summary, replacement_note, status, revoke_reason, "
                                + "created_at_utc, revoked_at_utc FROM attachment_redaction "
                                + "WHERE attachment_id = ? AND status = 'ACTIVE' "
                                + "ORDER BY redaction_version DESC LIMIT 1",
                        REDACTION_MAPPER, attachmentId)
                .stream().findFirst();
    }

    /**
     * 按附件与脱敏版本号查询；不存在时返回空。
     */
    public Optional<AttachmentRedactionRecord> findByVersion(String attachmentId, int redactionVersion) {
        return jdbcTemplate.query(
                        "SELECT redaction_id, observation_id, attachment_id, redaction_version, request_id, "
                                + "rule_version, display_summary, replacement_note, status, revoke_reason, "
                                + "created_at_utc, revoked_at_utc FROM attachment_redaction "
                                + "WHERE attachment_id = ? AND redaction_version = ?",
                        REDACTION_MAPPER, attachmentId, redactionVersion)
                .stream().findFirst();
    }

    /**
     * 查询某附件全部脱敏版本，按版本号升序，保证历史顺序稳定。
     */
    public List<AttachmentRedactionRecord> findByAttachmentId(String attachmentId) {
        return jdbcTemplate.query(
                "SELECT redaction_id, observation_id, attachment_id, redaction_version, request_id, "
                        + "rule_version, display_summary, replacement_note, status, revoke_reason, "
                        + "created_at_utc, revoked_at_utc FROM attachment_redaction "
                        + "WHERE attachment_id = ? ORDER BY redaction_version ASC",
                REDACTION_MAPPER, attachmentId);
    }

    /**
     * 按脱敏请求标识查询该请求生成的全部脱敏版本，按附件标识排序，保证重放响应顺序稳定。
     */
    public List<AttachmentRedactionRecord> findByRedactionId(String redactionId) {
        return jdbcTemplate.query(
                "SELECT redaction_id, observation_id, attachment_id, redaction_version, request_id, "
                        + "rule_version, display_summary, replacement_note, status, revoke_reason, "
                        + "created_at_utc, revoked_at_utc FROM attachment_redaction "
                        + "WHERE redaction_id = ? ORDER BY attachment_id ASC",
                REDACTION_MAPPER, redactionId);
    }

    /**
     * 按附件与规则版本查询既有脱敏版本；用于判定同一附件同一规则版本是否已脱敏。
     */
    public Optional<AttachmentRedactionRecord> findByAttachmentIdAndRuleVersion(String attachmentId,
                                                                                String ruleVersion) {
        return jdbcTemplate.query(
                        "SELECT redaction_id, observation_id, attachment_id, redaction_version, request_id, "
                                + "rule_version, display_summary, replacement_note, status, revoke_reason, "
                                + "created_at_utc, revoked_at_utc FROM attachment_redaction "
                                + "WHERE attachment_id = ? AND rule_version = ?",
                        REDACTION_MAPPER, attachmentId, ruleVersion)
                .stream().findFirst();
    }

    /**
     * 统计 UTC 区间 [fromUtc, toUtc)（左闭右开）内生成的脱敏版本数量。
     */
    public long countCreatedBetween(java.time.Instant fromUtc, java.time.Instant toUtc) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE created_at_utc >= ? AND created_at_utc < ?",
                Long.class, Timestamp.from(fromUtc), Timestamp.from(toUtc));
        return count == null ? 0 : count;
    }

    /**
     * 撤销一个脱敏版本的公开展示资格：置状态、原因与撤销时刻；行与替换说明永久保留。
     */
    public void revoke(String attachmentId, int redactionVersion, String reason, java.time.Instant revokedAtUtc) {
        jdbcTemplate.update(
                "UPDATE attachment_redaction SET status = 'REVOKED', revoke_reason = ?, revoked_at_utc = ? "
                        + "WHERE attachment_id = ? AND redaction_version = ?",
                reason, Timestamp.from(revokedAtUtc), attachmentId, redactionVersion);
    }
}
