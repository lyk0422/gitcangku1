package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 资源凭据 JDBC 仓储。(resource_key, version) 唯一；版本内容不可变，
 * 更新有效凭据通过登记新版本实现，旧版本保留作为派发/执行历史依据。
 * 派发与执行前检查以 lockByResource 锁住该资源全部版本行，
 * 与撤销事务互斥，竞争结果只取决于已提交的凭据状态。
 */
@Repository
public class ResourceCredentialRepository {

    private final JdbcTemplate jdbc;

    public ResourceCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ResourceCredential> MAPPER = (rs, n) -> map(rs);

    private static ResourceCredential map(ResultSet rs) throws SQLException {
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new ResourceCredential(
                rs.getLong("id"), rs.getString("resource_key"), rs.getString("version"),
                rs.getString("credential_type"), rs.getString("region"),
                CredentialStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("expires_at").toInstant(),
                revokedAt == null ? null : revokedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 登记新版本凭据，返回生成主键。
     *
     * @throws DuplicateKeyException 同资源同版本已存在时抛出
     */
    public long insert(ResourceCredential credential) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO resource_credentials (resource_key, version, credential_type, region,"
                            + " status, expires_at, revoked_at, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, credential.resourceKey());
            ps.setString(2, credential.version());
            ps.setString(3, credential.credentialType());
            ps.setString(4, credential.region());
            ps.setString(5, credential.status().name());
            ps.setTimestamp(6, Timestamp.from(credential.expiresAt()));
            ps.setTimestamp(7, credential.revokedAt() == null ? null : Timestamp.from(credential.revokedAt()));
            ps.setTimestamp(8, Timestamp.from(credential.createdAt()));
            ps.setTimestamp(9, Timestamp.from(credential.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按资源键与版本查询（不加锁）。
     */
    public Optional<ResourceCredential> findByKey(String resourceKey, String version) {
        List<ResourceCredential> rows = jdbc.query(
                "SELECT * FROM resource_credentials WHERE resource_key = ? AND version = ?",
                MAPPER, resourceKey, version);
        return rows.stream().findFirst();
    }

    /**
     * 查询资源全部版本，按版本登记顺序返回。
     */
    public List<ResourceCredential> listByResource(String resourceKey) {
        return jdbc.query(
                "SELECT * FROM resource_credentials WHERE resource_key = ? ORDER BY id",
                MAPPER, resourceKey);
    }

    /**
     * 锁定资源的全部版本行（SELECT ... FOR UPDATE）并按登记顺序返回。
     * 派发、执行前检查与撤销均通过该锁互斥，保证只按已提交状态转移。
     */
    public List<ResourceCredential> lockByResource(String resourceKey) {
        return jdbc.query(
                "SELECT * FROM resource_credentials WHERE resource_key = ? ORDER BY id FOR UPDATE",
                MAPPER, resourceKey);
    }

    /**
     * 条件撤销：仅 ACTIVE 行置为 REVOKED，返回受影响行数（0 表示已撤销或不存在）。
     */
    public int revoke(long id, Instant revokedAt) {
        return jdbc.update(
                "UPDATE resource_credentials SET status = 'REVOKED', revoked_at = ?, updated_at = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                Timestamp.from(revokedAt), Timestamp.from(revokedAt), id);
    }
}
