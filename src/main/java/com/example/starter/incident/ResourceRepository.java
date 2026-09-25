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
 * 共享资源及资源资质的 JDBC 仓储。
 * 写路径先以 FOR UPDATE 锁定资源行，资质登记/撤销与版本递增同事务提交；
 * 资质撤销对租约的影响在持有租约域锁的事务内完成。
 */
@Repository
public class ResourceRepository {

    private final JdbcTemplate jdbc;

    public ResourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Resource> RESOURCE_MAPPER = (rs, n) -> new Resource(
            rs.getLong("id"), rs.getString("resource_key"), rs.getInt("version"),
            rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<ResourceCredential> CREDENTIAL_MAPPER = (rs, n) -> mapCredential(rs);

    private static ResourceCredential mapCredential(ResultSet rs) throws SQLException {
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new ResourceCredential(
                rs.getLong("id"), rs.getLong("resource_id"), rs.getString("credential_code"),
                rs.getTimestamp("valid_from").toInstant(),
                rs.getTimestamp("valid_until").toInstant(),
                rs.getBoolean("revoked"),
                revokedAt == null ? null : revokedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 插入新资源（初始版本 1），返回生成主键。resource_key 唯一约束兜底并发重复插入。
     */
    public long insert(Resource resource) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO resources (resource_key, version, created_by, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, resource.resourceKey());
            ps.setInt(2, resource.version());
            ps.setString(3, resource.createdBy());
            ps.setTimestamp(4, Timestamp.from(resource.createdAt()));
            ps.setTimestamp(5, Timestamp.from(resource.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询资源（不加锁），用于只读场景。
     */
    public Optional<Resource> findByKey(String resourceKey) {
        List<Resource> rows = jdbc.query("SELECT * FROM resources WHERE resource_key = ?",
                RESOURCE_MAPPER, resourceKey);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询资源（不加锁），用于租约/风险记录关联查询。
     */
    public Optional<Resource> findById(long id) {
        List<Resource> rows = jdbc.query("SELECT * FROM resources WHERE id = ?",
                RESOURCE_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定资源行（SELECT ... FOR UPDATE），用于写路径串行化。
     */
    public Optional<Resource> lockByKey(String resourceKey) {
        List<Resource> rows = jdbc.query("SELECT * FROM resources WHERE resource_key = ? FOR UPDATE",
                RESOURCE_MAPPER, resourceKey);
        return rows.stream().findFirst();
    }

    /**
     * 资源版本递增（资质登记或提前撤销时），返回递增后的版本号。
     */
    public int bumpVersion(long id, Instant updatedAt) {
        jdbc.update("UPDATE resources SET version = version + 1, updated_at = ? WHERE id = ?",
                Timestamp.from(updatedAt), id);
        Integer version = jdbc.queryForObject("SELECT version FROM resources WHERE id = ?",
                Integer.class, id);
        return version == null ? 0 : version;
    }

    /**
     * 登记资质，返回生成主键。(resource_id, credential_code) 唯一约束兜底并发重复登记。
     */
    public long insertCredential(ResourceCredential credential) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO resource_credentials (resource_id, credential_code, valid_from,"
                            + " valid_until, revoked, revoked_at, created_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, credential.resourceId());
            ps.setString(2, credential.credentialCode());
            ps.setTimestamp(3, Timestamp.from(credential.validFrom()));
            ps.setTimestamp(4, Timestamp.from(credential.validUntil()));
            ps.setBoolean(5, credential.revoked());
            ps.setTimestamp(6, credential.revokedAt() == null ? null : Timestamp.from(credential.revokedAt()));
            ps.setTimestamp(7, Timestamp.from(credential.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 已撤销的同代码资质重新登记：覆盖有效期并复位撤销标记。
     */
    public void reregisterCredential(long id, Instant validFrom, Instant validUntil) {
        jdbc.update("UPDATE resource_credentials SET valid_from = ?, valid_until = ?,"
                        + " revoked = 0, revoked_at = NULL WHERE id = ?",
                Timestamp.from(validFrom), Timestamp.from(validUntil), id);
    }

    /**
     * 按资源与资质代码查询资质。
     */
    public Optional<ResourceCredential> findCredential(long resourceId, String credentialCode) {
        List<ResourceCredential> rows = jdbc.query(
                "SELECT * FROM resource_credentials WHERE resource_id = ? AND credential_code = ?",
                CREDENTIAL_MAPPER, resourceId, credentialCode);
        return rows.stream().findFirst();
    }

    /**
     * 查询资源全部资质，按资质代码排序返回。
     */
    public List<ResourceCredential> listCredentials(long resourceId) {
        return jdbc.query("SELECT * FROM resource_credentials WHERE resource_id = ?"
                + " ORDER BY credential_code", CREDENTIAL_MAPPER, resourceId);
    }

    /**
     * 条件撤销资质（仅未撤销行生效），记录撤销 UTC 时刻；
     * 返回更新行数，0 表示不存在或已撤销。
     */
    public int revokeCredential(long resourceId, String credentialCode, Instant revokedAt) {
        return jdbc.update("UPDATE resource_credentials SET revoked = 1, revoked_at = ?"
                        + " WHERE resource_id = ? AND credential_code = ? AND revoked = 0",
                Timestamp.from(revokedAt), resourceId, credentialCode);
    }
}
