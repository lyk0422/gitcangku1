package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 接收方承诺持久化访问：每个“接收方＋主体＋用途＋授权代次”至多一条承诺。
 * 所有 SQL 使用参数绑定；到期时刻以 UTC 毫秒（BIGINT）存储，精确到毫秒且跨数据库行为一致。
 */
@Repository
public class AttestationRepository {

    private static final RowMapper<AttestationRow> MAPPER = (rs, rowNum) -> new AttestationRow(
            rs.getString("recipient_key"),
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            Instant.ofEpochMilli(rs.getLong("expires_at")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("request_id"));

    private final JdbcTemplate jdbc;

    public AttestationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 承诺行。
     *
     * @param recipientKey 接收方标识
     * @param subjectKey   授权主体标识
     * @param purpose      承诺用途
     * @param epoch        承诺的授权代次
     * @param expiresAt    UTC 到期时刻，到期时刻恰好到达即失效
     * @param createdAt    UTC 提交时刻
     * @param requestId    创建本承诺的幂等请求标识
     */
    public record AttestationRow(String recipientKey, String subjectKey, Purpose purpose, int epoch,
                                 Instant expiresAt, Instant createdAt, String requestId) {
    }

    Optional<AttestationRow> find(String recipientKey, String subjectKey, Purpose purpose, int epoch) {
        List<AttestationRow> rows = jdbc.query(
                "SELECT recipient_key, subject_key, purpose, epoch, expires_at, created_at, request_id"
                        + " FROM recipient_attestation"
                        + " WHERE recipient_key = ? AND subject_key = ? AND purpose = ? AND epoch = ?",
                MAPPER, recipientKey, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    Optional<AttestationRow> findForUpdate(String recipientKey, String subjectKey, Purpose purpose, int epoch) {
        List<AttestationRow> rows = jdbc.query(
                "SELECT recipient_key, subject_key, purpose, epoch, expires_at, created_at, request_id"
                        + " FROM recipient_attestation"
                        + " WHERE recipient_key = ? AND subject_key = ? AND purpose = ? AND epoch = ?"
                        + " FOR UPDATE",
                MAPPER, recipientKey, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    List<AttestationRow> findByRecipient(String recipientKey) {
        return jdbc.query(
                "SELECT recipient_key, subject_key, purpose, epoch, expires_at, created_at, request_id"
                        + " FROM recipient_attestation WHERE recipient_key = ? ORDER BY created_at, id",
                MAPPER, recipientKey);
    }

    /**
     * 查询接收方对某主体某代次的全部承诺（任意用途），用于判定“用途不符”分支；
     * 调用方持有授权代次行锁，承诺行只能随新提交追加，撤回不改变承诺行。
     */
    List<AttestationRow> findByRecipientAndEpoch(String recipientKey, String subjectKey, int epoch) {
        return jdbc.query(
                "SELECT recipient_key, subject_key, purpose, epoch, expires_at, created_at, request_id"
                        + " FROM recipient_attestation"
                        + " WHERE recipient_key = ? AND subject_key = ? AND epoch = ?",
                MAPPER, recipientKey, subjectKey, epoch);
    }

    void insert(AttestationRow row) {
        jdbc.update(
                "INSERT INTO recipient_attestation"
                        + " (recipient_key, subject_key, purpose, epoch, expires_at, created_at, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.recipientKey(), row.subjectKey(), row.purpose().name(), row.epoch(),
                row.expiresAt().toEpochMilli(), Timestamp.from(row.createdAt()), row.requestId());
    }
}
