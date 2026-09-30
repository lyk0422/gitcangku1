package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 接收方承诺持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 */
@Repository
public class AttestationRepository {

    private static final RowMapper<AttestationRow> MAPPER = (rs, rowNum) -> new AttestationRow(
            rs.getString("recipient_key"),
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getTimestamp("expires_at").toInstant(),
            AttestationStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbc;

    public AttestationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 接收方承诺行。
     *
     * @param recipientKey 接收方标识
     * @param subjectKey   授权主体标识
     * @param purpose      承诺用途
     * @param epoch        承诺对应的授权代次
     * @param expiresAt    承诺到期时刻（UTC）
     * @param status       状态：ACTIVE 有效 / REVOKED 已撤回
     */
    public record AttestationRow(String recipientKey, String subjectKey, Purpose purpose, int epoch,
                                 Instant expiresAt, AttestationStatus status) {
    }

    Optional<AttestationRow> find(String recipientKey, String subjectKey, Purpose purpose, int epoch) {
        List<AttestationRow> rows = jdbc.query(
                "SELECT recipient_key, subject_key, purpose, epoch, expires_at, status"
                        + " FROM recipient_attestation"
                        + " WHERE recipient_key = ? AND subject_key = ? AND purpose = ? AND epoch = ?",
                MAPPER, recipientKey, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    void insert(String recipientKey, String subjectKey, Purpose purpose, int epoch,
                Instant expiresAt, String requestId, Instant now) {
        jdbc.update(
                "INSERT INTO recipient_attestation"
                        + " (recipient_key, subject_key, purpose, epoch, expires_at, status, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                recipientKey, subjectKey, purpose.name(), epoch,
                Timestamp.from(expiresAt), requestId, Timestamp.from(now));
    }

    /**
     * 仅当承诺当前为 ACTIVE 时撤回；返回是否实际发生状态变更。
     */
    boolean revoke(String recipientKey, String subjectKey, Purpose purpose, int epoch, Instant now) {
        int updated = jdbc.update(
                "UPDATE recipient_attestation SET status = 'REVOKED', revoked_at = ?"
                        + " WHERE recipient_key = ? AND subject_key = ? AND purpose = ? AND epoch = ?"
                        + " AND status = 'ACTIVE'",
                Timestamp.from(now), recipientKey, subjectKey, purpose.name(), epoch);
        return updated > 0;
    }

    List<AttestationRow> findByRecipient(String recipientKey) {
        return jdbc.query(
                "SELECT recipient_key, subject_key, purpose, epoch, expires_at, status"
                        + " FROM recipient_attestation WHERE recipient_key = ?"
                        + " ORDER BY subject_key, purpose, epoch",
                MAPPER, recipientKey);
    }

    List<AttestationRow> findByRecipientAndSubject(String recipientKey, String subjectKey) {
        return jdbc.query(
                "SELECT recipient_key, subject_key, purpose, epoch, expires_at, status"
                        + " FROM recipient_attestation WHERE recipient_key = ? AND subject_key = ?"
                        + " ORDER BY purpose, epoch",
                MAPPER, recipientKey, subjectKey);
    }
}
