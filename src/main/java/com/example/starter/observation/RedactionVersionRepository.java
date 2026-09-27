package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 附件脱敏版本持久化。版本行只新增不删除；撤销只更新公开展示资格字段。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class RedactionVersionRepository {

    private static final String COLUMNS = "attachment_id, observation_id, version, rule_version, "
            + "redacted_summary, replacement_note, status, request_id, operator, created_at_utc, "
            + "revoked_at_utc, revoke_reason, revoked_by, revoke_request_id";

    private static final RowMapper<RedactionVersionRecord> MAPPER = (rs, rowNum) -> new RedactionVersionRecord(
            rs.getString("attachment_id"),
            rs.getString("observation_id"),
            rs.getInt("version"),
            rs.getString("rule_version"),
            rs.getString("redacted_summary"),
            rs.getString("replacement_note"),
            RedactionStatus.fromValue(rs.getString("status")),
            rs.getString("request_id"),
            rs.getString("operator"),
            rs.getTimestamp("created_at_utc").toInstant(),
            toInstant(rs.getTimestamp("revoked_at_utc")),
            rs.getString("revoke_reason"),
            rs.getString("revoked_by"),
            rs.getString("revoke_request_id"));

    private final JdbcTemplate jdbcTemplate;

    public RedactionVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 追加一个脱敏版本（状态 ACTIVE）。
     */
    public void insert(RedactionVersionRecord record) {
        jdbcTemplate.update(
                "INSERT INTO redaction_version (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL)",
                record.attachmentId(), record.observationId(), record.version(), record.ruleVersion(),
                record.redactedSummary(), record.replacementNote(), record.status().name(),
                record.requestId(), record.operator(), Timestamp.from(record.createdAtUtc()));
    }

    /**
     * 按附件与版本号读取脱敏版本。
     */
    public Optional<RedactionVersionRecord> find(String attachmentId, int version) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM redaction_version WHERE attachment_id = ? AND version = ?",
                        MAPPER, attachmentId, version)
                .stream().findFirst();
    }

    /**
     * 读取某附件的全部脱敏版本（按版本号升序）。
     */
    public List<RedactionVersionRecord> findByAttachment(String attachmentId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM redaction_version WHERE attachment_id = ? ORDER BY version ASC",
                MAPPER, attachmentId);
    }

    /**
     * 读取某附件当前最新有效（ACTIVE）脱敏版本；无有效版本时返回空。
     */
    public Optional<RedactionVersionRecord> findLatestActive(String attachmentId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM redaction_version "
                                + "WHERE attachment_id = ? AND status = 'ACTIVE' "
                                + "ORDER BY version DESC LIMIT 1",
                        MAPPER, attachmentId)
                .stream().findFirst();
    }

    /**
     * 读取某附件指定规则版本的脱敏版本；用于 (attachment, ruleVersion) 唯一约束的判定。
     */
    public Optional<RedactionVersionRecord> findByAttachmentAndRule(String attachmentId, String ruleVersion) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM redaction_version "
                                + "WHERE attachment_id = ? AND rule_version = ?",
                        MAPPER, attachmentId, ruleVersion)
                .stream().findFirst();
    }

    /**
     * 计算某附件当前最大脱敏版本号；无版本时返回 0。调用方需持有附件行锁。
     */
    public int maxVersion(String attachmentId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM redaction_version WHERE attachment_id = ?",
                Integer.class, attachmentId);
        return max == null ? 0 : max;
    }

    /**
     * 按观测与可选时间区间 [from, to) 查询脱敏版本（按生成时刻、附件、版本稳定排序）。
     */
    public List<RedactionVersionRecord> findByObservation(String observationId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + COLUMNS + " FROM redaction_version WHERE observation_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(observationId);
        if (from != null) {
            sql.append(" AND created_at_utc >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND created_at_utc < ?");
            args.add(Timestamp.from(to));
        }
        sql.append(" ORDER BY created_at_utc ASC, attachment_id ASC, version ASC");
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    /**
     * 统计某观测当前公开展示资格有效（ACTIVE）的脱敏版本数。
     */
    public int countByObservationAndStatus(String observationId, RedactionStatus status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM redaction_version WHERE observation_id = ? AND status = ?",
                Integer.class, observationId, status.name());
        return count == null ? 0 : count;
    }

    /**
     * 撤销脱敏版本公开展示资格：记录撤销时刻、理由、操作者与请求标识。版本行不删除。
     */
    public void revoke(String attachmentId, int version, Instant revokedAtUtc,
                       String reason, String revokedBy, String revokeRequestId) {
        jdbcTemplate.update(
                "UPDATE redaction_version SET status = 'REVOKED', revoked_at_utc = ?, revoke_reason = ?, "
                        + "revoked_by = ?, revoke_request_id = ? WHERE attachment_id = ? AND version = ?",
                Timestamp.from(revokedAtUtc), reason, revokedBy, revokeRequestId, attachmentId, version);
    }
}
