package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 资源资质登记的 JDBC 仓储。
 * (resource_id, credential_code) 唯一；同键重登（续期）与撤销均使 version 单调递增，
 * 租约指纹记录分配时的版本快照。
 */
@Repository
public class ResourceCredentialRepository {

    private final JdbcTemplate jdbc;

    public ResourceCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ResourceCredential> MAPPER = (rs, n) -> map(rs);

    private static ResourceCredential map(ResultSet rs) throws SQLException {
        Timestamp validFrom = rs.getTimestamp("valid_from");
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new ResourceCredential(
                rs.getLong("id"), rs.getString("resource_id"), rs.getString("credential_code"),
                validFrom == null ? null : validFrom.toInstant(),
                rs.getTimestamp("valid_until").toInstant(),
                CredentialStatus.valueOf(rs.getString("status")),
                rs.getLong("version"), rs.getString("revoked_by"),
                revokedAt == null ? null : revokedAt.toInstant(),
                rs.getString("revoke_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 首次登记 ACTIVE 资质，版本为 1，返回生成主键。
     */
    public long insert(ResourceCredential credential) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO resource_credentials (resource_id, credential_code, valid_from,"
                            + " valid_until, status, version, revoked_by, revoked_at, revoke_reason,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, credential.resourceId());
            ps.setString(2, credential.credentialCode());
            ps.setTimestamp(3, credential.validFrom() == null ? null
                    : Timestamp.from(credential.validFrom()));
            ps.setTimestamp(4, Timestamp.from(credential.validUntil()));
            ps.setString(5, credential.status().name());
            ps.setLong(6, credential.version());
            ps.setString(7, credential.revokedBy());
            ps.setTimestamp(8, credential.revokedAt() == null ? null
                    : Timestamp.from(credential.revokedAt()));
            ps.setString(9, credential.revokeReason());
            ps.setTimestamp(10, Timestamp.from(credential.createdAt()));
            ps.setTimestamp(11, Timestamp.from(credential.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 同键续期：仅 ACTIVE 资质可重登，更新生效/失效时刻并使版本号 +1。
     * 返回受影响行数（0 表示不存在或已撤销，由服务层区分）。
     */
    public int renew(long id, Instant validFrom, Instant validUntil, long newVersion, Instant at) {
        return jdbc.update("UPDATE resource_credentials SET valid_from = ?, valid_until = ?,"
                        + " version = ?, updated_at = ? WHERE id = ? AND status = 'ACTIVE'",
                validFrom == null ? null : Timestamp.from(validFrom), Timestamp.from(validUntil),
                newVersion, Timestamp.from(at), id);
    }

    /**
     * 提前撤销：条件更新仅作用于 ACTIVE 行，版本号 +1。返回受影响行数（0 表示已撤销/不存在）。
     */
    public int revoke(long id, String revokedBy, Instant revokedAt, String reason,
                     long newVersion, Instant at) {
        return jdbc.update("UPDATE resource_credentials SET status = 'REVOKED', revoked_by = ?,"
                        + " revoked_at = ?, revoke_reason = ?, version = ?, updated_at = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                revokedBy, Timestamp.from(revokedAt), reason, newVersion, Timestamp.from(at), id);
    }

    /**
     * 按 (resourceId, credentialCode) 查询。
     */
    public Optional<ResourceCredential> find(String resourceId, String credentialCode) {
        List<ResourceCredential> rows = jdbc.query(
                "SELECT * FROM resource_credentials WHERE resource_id = ? AND credential_code = ?",
                MAPPER, resourceId, credentialCode);
        return rows.stream().findFirst();
    }

    /**
     * 查询资源全部资质，按资质代码字典序返回。
     */
    public List<ResourceCredential> listByResource(String resourceId) {
        return jdbc.query(
                "SELECT * FROM resource_credentials WHERE resource_id = ? ORDER BY credential_code",
                MAPPER, resourceId);
    }
}
