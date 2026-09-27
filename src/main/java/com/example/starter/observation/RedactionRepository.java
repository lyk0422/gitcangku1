package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 附件脱敏版本持久化：版本只追加不修改；撤销仅更新状态、撤销时刻与撤销理由。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class RedactionRepository {

    private static final RowMapper<RedactionVersion> VERSION_MAPPER = (rs, rowNum) -> {
        Timestamp revokedAt = rs.getTimestamp("revoked_at_utc");
        return new RedactionVersion(
                rs.getString("attachment_id"),
                rs.getInt("redaction_version"),
                rs.getString("rule_version"),
                rs.getString("redacted_summary"),
                rs.getString("replacement_note"),
                rs.getString("status"),
                rs.getTimestamp("created_at_utc").toInstant(),
                revokedAt == null ? null : revokedAt.toInstant(),
                rs.getString("revoke_reason"),
                rs.getString("request_id"));
    };

    private static final String COLUMNS =
            "attachment_id, redaction_version, rule_version, redacted_summary, replacement_note, "
                    + "status, created_at_utc, revoked_at_utc, revoke_reason, request_id";

    private final JdbcTemplate jdbcTemplate;

    public RedactionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一个脱敏版本；(attachment_id, redaction_version) 主键或 (attachment_id, rule_version)
     * 唯一键冲突时抛出重复键异常，由上层裁决并发。
     */
    public void insert(RedactionVersion version) {
        jdbcTemplate.update(
                "INSERT INTO attachment_redaction (attachment_id, redaction_version, rule_version, "
                        + "redacted_summary, replacement_note, status, created_at_utc, revoked_at_utc, "
                        + "revoke_reason, request_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
                version.attachmentId(), version.redactionVersion(), version.ruleVersion(),
                version.redactedSummary(), version.replacementNote(), version.status(),
                Timestamp.from(version.createdAtUtc()), version.requestId());
    }

    /**
     * 撤销指定版本：仅更新状态、撤销时刻与撤销理由，其余字段保持原值。
     */
    public void markRevoked(String attachmentId, int redactionVersion,
                            Timestamp revokedAtUtc, String revokeReason) {
        jdbcTemplate.update(
                "UPDATE attachment_redaction SET status = ?, revoked_at_utc = ?, revoke_reason = ? "
                        + "WHERE attachment_id = ? AND redaction_version = ?",
                RedactionVersion.STATUS_REVOKED, revokedAtUtc, revokeReason,
                attachmentId, redactionVersion);
    }

    /**
     * 按附件与版本号查询；不存在时返回空。
     */
    public Optional<RedactionVersion> find(String attachmentId, int redactionVersion) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM attachment_redaction "
                                + "WHERE attachment_id = ? AND redaction_version = ?",
                        VERSION_MAPPER, attachmentId, redactionVersion)
                .stream().findFirst();
    }

    /**
     * 查询附件的全部脱敏版本，按版本号升序，保证历史顺序稳定。
     */
    public List<RedactionVersion> findByAttachment(String attachmentId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM attachment_redaction "
                        + "WHERE attachment_id = ? ORDER BY redaction_version ASC",
                VERSION_MAPPER, attachmentId);
    }

    /**
     * 查询附件当前最新有效（未撤销）版本；不存在时返回空。
     */
    public Optional<RedactionVersion> findLatestEffective(String attachmentId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM attachment_redaction "
                                + "WHERE attachment_id = ? AND status = ? "
                                + "ORDER BY redaction_version DESC LIMIT 1",
                        VERSION_MAPPER, attachmentId, RedactionVersion.STATUS_EFFECTIVE)
                .stream().findFirst();
    }

    /**
     * 当前最大版本号；无版本时返回 0。
     */
    public int maxVersion(String attachmentId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(redaction_version), 0) FROM attachment_redaction WHERE attachment_id = ?",
                Integer.class, attachmentId);
        return max == null ? 0 : max;
    }

    /**
     * 同一附件是否已存在使用指定规则版本的脱敏版本（含已撤销）。
     */
    public boolean existsByRuleVersion(String attachmentId, String ruleVersion) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE attachment_id = ? AND rule_version = ?",
                Integer.class, attachmentId, ruleVersion);
        return count != null && count > 0;
    }
}
