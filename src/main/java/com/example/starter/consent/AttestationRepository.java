package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 接收方证明持久化访问：证明按“接收方＋用途＋代次”精确作用域，版本化保存，不覆盖历史。
 *
 * <p>作用域行（attestation_scope）是同作用域并发裁决的串行化点：续签、撤销与批次查询
 * 在同一事务内对作用域行加行锁（{@code SELECT ... FOR UPDATE}），版本号只在锁内分配，
 * 保证按事务提交顺序裁决。旧用途或旧代次因作用域键不同而天然无法复用证明。
 */
@Repository
public class AttestationRepository {

    private static final String COLUMNS =
            "attestation_id, version, recipient_id, purpose, epoch, expires_at, claim_digest,"
                    + " status, submitted_at, revoked_at";

    private static final RowMapper<AttestationRow> MAPPER = (rs, rowNum) -> new AttestationRow(
            rs.getString("attestation_id"),
            rs.getInt("version"),
            rs.getString("recipient_id"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getString("claim_digest"),
            AttestationStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("submitted_at").toInstant(),
            rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant());

    private final JdbcTemplate jdbc;

    public AttestationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 证明版本行。
     *
     * @param attestationId 证明逻辑标识（接收方#用途#代次）
     * @param version       版本号，从 1 递增
     * @param recipientId   接收方标识
     * @param purpose       证明对应授权用途
     * @param epoch         证明对应授权代次
     * @param expiresAt     到期时刻（UTC）
     * @param claimDigest   声明摘要
     * @param status        ACTIVE / SUPERSEDED / REVOKED
     * @param submittedAt   提交时刻（UTC）
     * @param revokedAt     撤销时刻（UTC），未撤销为 null
     */
    public record AttestationRow(String attestationId, int version, String recipientId, Purpose purpose,
                                 int epoch, Instant expiresAt, String claimDigest, AttestationStatus status,
                                 Instant submittedAt, Instant revokedAt) {
    }

    /**
     * 在事务内锁定作用域行并返回证明逻辑标识；作用域不存在时返回空（不产生间隙锁语义依赖）。
     */
    Optional<String> lockScope(String recipientId, Purpose purpose, int epoch) {
        List<String> ids = jdbc.query(
                "SELECT attestation_id FROM attestation_scope"
                        + " WHERE recipient_id = ? AND purpose = ? AND epoch = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString(1), recipientId, purpose.name(), epoch);
        return ids.stream().findFirst();
    }

    void insertScope(String attestationId, String recipientId, Purpose purpose, int epoch) {
        jdbc.update(
                "INSERT INTO attestation_scope (attestation_id, recipient_id, purpose, epoch, created_at)"
                        + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                attestationId, recipientId, purpose.name(), epoch);
    }

    /**
     * 非加锁读取作用域证明逻辑标识（历史查询用）；作用域不存在返回空。
     */
    Optional<String> findScopeId(String recipientId, Purpose purpose, int epoch) {
        List<String> ids = jdbc.query(
                "SELECT attestation_id FROM attestation_scope"
                        + " WHERE recipient_id = ? AND purpose = ? AND epoch = ?",
                (rs, rowNum) -> rs.getString(1), recipientId, purpose.name(), epoch);
        return ids.stream().findFirst();
    }

    /**
     * 取某作用域当前生效版本（status=ACTIVE），不加锁。
     */
    Optional<AttestationRow> findActive(String recipientId, Purpose purpose, int epoch) {
        return queryActive(recipientId, purpose, epoch, false).stream().findFirst();
    }

    /**
     * 在事务内锁定某作用域当前 ACTIVE 版本行（与续签/撤销互斥）。
     */
    Optional<AttestationRow> findActiveForUpdate(String recipientId, Purpose purpose, int epoch) {
        return queryActive(recipientId, purpose, epoch, true).stream().findFirst();
    }

    private List<AttestationRow> queryActive(String recipientId, Purpose purpose, int epoch, boolean forUpdate) {
        String sql = "SELECT " + COLUMNS + " FROM recipient_attestation"
                + " WHERE recipient_id = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'"
                + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.query(sql, MAPPER, recipientId, purpose.name(), epoch);
    }

    /**
     * 取某作用域全部版本（含历史），按版本升序。
     */
    List<AttestationRow> findHistory(String attestationId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM recipient_attestation"
                        + " WHERE attestation_id = ? ORDER BY version ASC",
                MAPPER, attestationId);
    }

    /**
     * 按证明逻辑标识与版本取指定版本（快照核验用），不加锁。
     */
    Optional<AttestationRow> findVersion(String attestationId, int version) {
        List<AttestationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM recipient_attestation"
                        + " WHERE attestation_id = ? AND version = ?",
                MAPPER, attestationId, version);
        return rows.stream().findFirst();
    }

    /**
     * 返回作用域当前最大版本号；无任何版本时返回 0。调用方须已持有作用域行锁。
     */
    int currentMaxVersion(String attestationId) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(version) FROM recipient_attestation WHERE attestation_id = ?",
                Integer.class, attestationId);
        return max == null ? 0 : max;
    }

    void insertVersion(AttestationRow row, String requestId) {
        jdbc.update(
                "INSERT INTO recipient_attestation (attestation_id, version, recipient_id, purpose, epoch,"
                        + " expires_at, claim_digest, status, request_id, submitted_at, revoked_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.attestationId(), row.version(), row.recipientId(), row.purpose().name(), row.epoch(),
                Timestamp.from(row.expiresAt()), row.claimDigest(), row.status().name(),
                requestId, Timestamp.from(row.submittedAt()),
                row.revokedAt() == null ? null : Timestamp.from(row.revokedAt()));
    }

    /**
     * 把某作用域当前 ACTIVE 版本置为 SUPERSEDED（续签时）。调用方须已持有作用域/版本行锁。
     */
    int supersedeActive(String recipientId, Purpose purpose, int epoch) {
        return jdbc.update(
                "UPDATE recipient_attestation SET status = 'SUPERSEDED'"
                        + " WHERE recipient_id = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'",
                recipientId, purpose.name(), epoch);
    }

    /**
     * 把某作用域当前 ACTIVE 版本置为 REVOKED，仅当它当前为 ACTIVE；返回是否实际变更。
     */
    boolean revokeActive(String recipientId, Purpose purpose, int epoch, Instant revokedAt) {
        int updated = jdbc.update(
                "UPDATE recipient_attestation SET status = 'REVOKED', revoked_at = ?"
                        + " WHERE recipient_id = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'",
                Timestamp.from(revokedAt), recipientId, purpose.name(), epoch);
        return updated > 0;
    }
}
