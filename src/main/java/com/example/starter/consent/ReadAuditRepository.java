package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 接收方读取审计持久化访问：成功与被拒绝的读取都留痕，历史记录不随承诺撤回而删除。
 */
@Repository
public class ReadAuditRepository {

    private static final RowMapper<ReadAuditRow> MAPPER = (rs, rowNum) -> new ReadAuditRow(
            rs.getLong("id"),
            rs.getString("subject_key"),
            rs.getString("recipient_key"),
            rs.getString("purpose"),
            (Integer) rs.getObject("epoch"),
            rs.getString("record_key"),
            rs.getString("result"),
            rs.getString("deny_reason"),
            rs.getTimestamp("attestation_expires_at") == null
                    ? null : rs.getTimestamp("attestation_expires_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public ReadAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取审计行。
     *
     * @param id                   自增主键
     * @param subjectKey           授权主体标识
     * @param recipientKey         接收方标识
     * @param purpose              读取用途
     * @param epoch                依据的授权代次；拒绝且无授权代次时为 null
     * @param recordKey            请求读取的记录键
     * @param result               结果：SUCCESS / DENIED
     * @param denyReason           拒绝原因码，成功为 null
     * @param attestationExpiresAt 依据承诺的到期时刻（UTC），拒绝为 null
     * @param createdAt            审计时间（UTC）
     */
    public record ReadAuditRow(Long id, String subjectKey, String recipientKey, String purpose, Integer epoch,
                               String recordKey, String result, String denyReason,
                               Instant attestationExpiresAt, Instant createdAt) {
    }

    void insert(String subjectKey, String recipientKey, Purpose purpose, Integer epoch, String recordKey,
                boolean success, String denyReason, Instant attestationExpiresAt, Instant now) {
        jdbc.update(
                "INSERT INTO recipient_read_audit"
                        + " (subject_key, recipient_key, purpose, epoch, record_key, result, deny_reason,"
                        + " attestation_expires_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                subjectKey, recipientKey, purpose.name(), epoch, recordKey,
                success ? "SUCCESS" : "DENIED", denyReason,
                attestationExpiresAt == null ? null : Timestamp.from(attestationExpiresAt),
                Timestamp.from(now));
    }

    List<ReadAuditRow> findBySubject(String subjectKey) {
        return jdbc.query(
                "SELECT id, subject_key, recipient_key, purpose, epoch, record_key, result, deny_reason,"
                        + " attestation_expires_at, created_at FROM recipient_read_audit"
                        + " WHERE subject_key = ? ORDER BY id",
                MAPPER, subjectKey);
    }
}
