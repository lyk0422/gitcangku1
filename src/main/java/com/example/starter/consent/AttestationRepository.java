package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 接收方证明与接收方状态的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 */
@Repository
public class AttestationRepository {

    private static final RowMapper<AttestationRow> ATTESTATION_MAPPER = (rs, rowNum) -> new AttestationRow(
            rs.getLong("id"),
            rs.getString("recipient_id"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getInt("version"),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getString("statement_digest"),
            AttestationStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbc;

    public AttestationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 证明版本行。
     *
     * @param id              证明记录主键
     * @param recipientId     数据接收方标识
     * @param purpose         用途
     * @param epoch           证明适用的授权代次
     * @param version         证明版本，同一接收方＋用途＋代次内从 1 开始递增
     * @param expiresAt       UTC 到期时刻
     * @param statementDigest 声明摘要
     * @param status          状态：ACTIVE 生效 / SUPERSEDED 已被续签取代 / REVOKED 已撤销
     */
    public record AttestationRow(long id, String recipientId, Purpose purpose, int epoch, int version,
                                 Instant expiresAt, String statementDigest, AttestationStatus status) {
    }

    Optional<AttestationRow> findActive(String recipientId, Purpose purpose, int epoch) {
        List<AttestationRow> rows = jdbc.query(
                "SELECT id, recipient_id, purpose, epoch, version, expires_at, statement_digest, status"
                        + " FROM recipient_attestation"
                        + " WHERE recipient_id = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'",
                ATTESTATION_MAPPER, recipientId, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    Optional<AttestationRow> findLatest(String recipientId, Purpose purpose, int epoch) {
        List<AttestationRow> rows = jdbc.query(
                "SELECT id, recipient_id, purpose, epoch, version, expires_at, statement_digest, status"
                        + " FROM recipient_attestation"
                        + " WHERE recipient_id = ? AND purpose = ? AND epoch = ?"
                        + " ORDER BY version DESC LIMIT 1",
                ATTESTATION_MAPPER, recipientId, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    List<AttestationRow> findHistory(String recipientId, Purpose purpose, Integer epoch) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, recipient_id, purpose, epoch, version, expires_at, statement_digest, status"
                        + " FROM recipient_attestation WHERE recipient_id = ?");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(recipientId);
        if (purpose != null) {
            sql.append(" AND purpose = ?");
            args.add(purpose.name());
        }
        if (epoch != null) {
            sql.append(" AND epoch = ?");
            args.add(epoch);
        }
        sql.append(" ORDER BY purpose, epoch, version DESC");
        return jdbc.query(sql.toString(), ATTESTATION_MAPPER, args.toArray());
    }

    void insert(String recipientId, Purpose purpose, int epoch, int version,
                Instant expiresAt, String statementDigest, String attestKey) {
        jdbc.update(
                "INSERT INTO recipient_attestation"
                        + " (recipient_id, purpose, epoch, version, expires_at, statement_digest, status, attest_key)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
                recipientId, purpose.name(), epoch, version,
                Timestamp.from(expiresAt), statementDigest, attestKey);
    }

    /**
     * 将指定证明版本标记为已被续签取代；仅当前为 ACTIVE 时生效，返回是否实际变更。
     */
    boolean supersede(long id) {
        int updated = jdbc.update(
                "UPDATE recipient_attestation SET status = 'SUPERSEDED' WHERE id = ? AND status = 'ACTIVE'",
                id);
        return updated > 0;
    }

    /**
     * 撤销指定证明版本；仅当前为 ACTIVE 时生效，返回是否实际变更。
     */
    boolean revoke(long id) {
        int updated = jdbc.update(
                "UPDATE recipient_attestation SET status = 'REVOKED' WHERE id = ? AND status = 'ACTIVE'",
                id);
        return updated > 0;
    }

    boolean isRecipientDisabled(String recipientId) {
        List<Boolean> rows = jdbc.query(
                "SELECT disabled FROM recipient_state WHERE recipient_id = ?",
                (rs, rowNum) -> rs.getBoolean("disabled"), recipientId);
        return rows.stream().findFirst().orElse(false);
    }

    /**
     * 将接收方置为禁用；已存在记录时更新，不存在时插入，并发插入冲突时以已提交行为准。
     */
    void disableRecipient(String recipientId) {
        int updated = jdbc.update(
                "UPDATE recipient_state SET disabled = TRUE, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE recipient_id = ?",
                recipientId);
        if (updated == 0) {
            try {
                jdbc.update(
                        "INSERT INTO recipient_state (recipient_id, disabled) VALUES (?, TRUE)",
                        recipientId);
            } catch (org.springframework.dao.DuplicateKeyException concurrent) {
                jdbc.update(
                        "UPDATE recipient_state SET disabled = TRUE, updated_at = CURRENT_TIMESTAMP"
                                + " WHERE recipient_id = ?",
                        recipientId);
            }
        }
    }
}
