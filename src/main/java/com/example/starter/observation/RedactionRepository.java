package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 附件脱敏版本持久化：版本只新增，撤销仅更新状态/时刻/理由。
 * 唯一键 (attachment_id, rule_version) 保证同一附件同一规则版本并发只生成一个版本；
 * 唯一键 (attachment_id, version) 保证版本号按附件递增无空洞竞争。
 */
@Repository
public class RedactionRepository {

    private static final RowMapper<RedactionVersionRecord> MAPPER = (rs, rowNum) -> new RedactionVersionRecord(
            rs.getString("redaction_id"),
            rs.getString("observation_id"),
            rs.getString("attachment_id"),
            rs.getInt("version"),
            rs.getString("rule_version"),
            rs.getString("replacement_note"),
            "ACTIVE".equals(rs.getString("status")),
            rs.getString("request_id"),
            rs.getTimestamp("created_at_utc").toInstant(),
            Optional.ofNullable(rs.getTimestamp("revoked_at_utc")).map(Timestamp::toInstant).orElse(null),
            rs.getString("revoke_reason"));

    private final JdbcTemplate jdbcTemplate;

    public RedactionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一个脱敏版本。
     */
    public void insert(RedactionVersionRecord record) {
        jdbcTemplate.update(
                "INSERT INTO attachment_redaction (redaction_id, observation_id, attachment_id, version, "
                        + "rule_version, replacement_note, status, request_id, created_at_utc, "
                        + "revoked_at_utc, revoke_reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, NULL, NULL)",
                record.redactionId(), record.observationId(), record.attachmentId(), record.version(),
                record.ruleVersion(), record.replacementNote(), record.requestId(),
                Timestamp.from(record.createdAtUtc()));
    }

    /**
     * 按全局 redactionId 查询。
     */
    public Optional<RedactionVersionRecord> findById(String redactionId) {
        return jdbcTemplate.query(selectAll() + " WHERE redaction_id = ?", MAPPER, redactionId)
                .stream().findFirst();
    }

    /**
     * 按 redactionId 查询并加锁，用于撤销裁决。
     */
    public Optional<RedactionVersionRecord> findByIdForUpdate(String redactionId) {
        return jdbcTemplate.query(selectAll() + " WHERE redaction_id = ? FOR UPDATE", MAPPER, redactionId)
                .stream().findFirst();
    }

    /**
     * 查询附件在某规则版本下的脱敏版本（并发唯一裁决与重放使用）。
     */
    public Optional<RedactionVersionRecord> findByAttachmentAndRule(String attachmentId, String ruleVersion) {
        return jdbcTemplate.query(
                        selectAll() + " WHERE attachment_id = ? AND rule_version = ?",
                        MAPPER, attachmentId, ruleVersion)
                .stream().findFirst();
    }

    /**
     * 附件当前最大脱敏版本号；无版本时返回空。
     */
    public Optional<Integer> findMaxVersion(String attachmentId) {
        return Optional.ofNullable(jdbcTemplate.queryForObject(
                "SELECT MAX(version) FROM attachment_redaction WHERE attachment_id = ?",
                Integer.class, attachmentId));
    }

    /**
     * 附件全部脱敏版本，按版本号升序（历史排序稳定）。
     */
    public List<RedactionVersionRecord> findByAttachment(String attachmentId) {
        return jdbcTemplate.query(
                selectAll() + " WHERE attachment_id = ? ORDER BY version ASC", MAPPER, attachmentId);
    }

    /**
     * 附件脱敏版本历史，生成时刻落在左闭右开区间 [from, to) 内；任一端点为 null 表示不限制。
     */
    public List<RedactionVersionRecord> findByAttachmentBetween(String attachmentId,
                                                                 Instant from, Instant to) {
        return jdbcTemplate.query(
                selectAll() + " WHERE attachment_id = ? "
                        + "AND (? IS NULL OR created_at_utc >= ?) "
                        + "AND (? IS NULL OR created_at_utc < ?) ORDER BY version ASC",
                MAPPER, attachmentId, ts(from), ts(from), ts(to), ts(to));
    }

    /**
     * 观测下全部脱敏版本，按附件标识、版本号升序（明细/历史查询稳定排序）。
     */
    public List<RedactionVersionRecord> findByObservation(String observationId) {
        return jdbcTemplate.query(
                selectAll() + " WHERE observation_id = ? ORDER BY attachment_id ASC, version ASC",
                MAPPER, observationId);
    }

    /**
     * 观测下脱敏版本历史，生成时刻落在左闭右开区间 [from, to) 内；任一端点为 null 表示不限制。
     */
    public List<RedactionVersionRecord> findByObservationBetween(String observationId,
                                                                  Instant from, Instant to) {
        return jdbcTemplate.query(
                selectAll() + " WHERE observation_id = ? "
                        + "AND (? IS NULL OR created_at_utc >= ?) "
                        + "AND (? IS NULL OR created_at_utc < ?) "
                        + "ORDER BY attachment_id ASC, version ASC",
                MAPPER, observationId, ts(from), ts(from), ts(to), ts(to));
    }

    /**
     * 统计观测下脱敏版本总数。
     */
    public int countByObservation(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE observation_id = ?",
                Integer.class, observationId);
        return count == null ? 0 : count;
    }

    private Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /**
     * 附件最新有效（ACTIVE）版本：版本号最大的 ACTIVE 行；无有效版本时返回空。
     */
    public Optional<RedactionVersionRecord> findLatestActive(String attachmentId) {
        return jdbcTemplate.query(
                        selectAll() + " WHERE attachment_id = ? AND status = 'ACTIVE' "
                                + "ORDER BY version DESC LIMIT 1",
                        MAPPER, attachmentId)
                .stream().findFirst();
    }

    /**
     * 撤销公开展示资格：仅置状态、撤销时刻与理由，版本行与替换说明保留。
     */
    public void markRevoked(String redactionId, Instant revokedAtUtc, String reason) {
        jdbcTemplate.update(
                "UPDATE attachment_redaction SET status = 'REVOKED', revoked_at_utc = ?, revoke_reason = ? "
                        + "WHERE redaction_id = ?",
                Timestamp.from(revokedAtUtc), reason, redactionId);
    }

    private String selectAll() {
        return "SELECT redaction_id, observation_id, attachment_id, version, rule_version, replacement_note, "
                + "status, request_id, created_at_utc, revoked_at_utc, revoke_reason "
                + "FROM attachment_redaction";
    }
}
