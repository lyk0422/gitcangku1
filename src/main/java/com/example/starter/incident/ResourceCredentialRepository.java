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
 * 资源凭据 JDBC 仓储。
 * 同一资源的版本登记在单行版本锁内串行化，版本号自增；(resource_key, version) 唯一兜底。
 * 派发/执行复检对凭据的读取使用 SELECT ... FOR UPDATE，在任务所属事件行锁之后再锁凭据行，
 * 使撤销与执行启动按凭据行上的事务提交顺序裁决。
 */
@Repository
public class ResourceCredentialRepository {

    /** 凭据版本号分配的全局单行锁主键。 */
    private static final long SEQ_LOCK_ID = 1L;

    private final JdbcTemplate jdbc;

    public ResourceCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ResourceCredential> MAPPER = (rs, n) -> map(rs);

    private static ResourceCredential map(ResultSet rs) throws SQLException {
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new ResourceCredential(
                rs.getLong("id"), rs.getString("resource_key"), rs.getInt("version"),
                rs.getString("credential_type"), rs.getString("region"),
                rs.getTimestamp("expires_at").toInstant(),
                CredentialStatus.valueOf(rs.getString("status")),
                rs.getString("created_by"),
                revokedAt == null ? null : revokedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 持有版本号全局锁（单行 SELECT ... FOR UPDATE），串行化同一（乃至全部）资源的版本分配。
     * 锁行不存在时先插入，并发首次插入由主键约束串行化。
     */
    public void lockSequence() {
        try {
            jdbc.update("INSERT INTO resource_credential_lock (id) VALUES (?)", SEQ_LOCK_ID);
        } catch (DuplicateKeyException e) {
            // 锁行已存在（含并发事务已提交），继续加锁
        }
        jdbc.queryForObject("SELECT id FROM resource_credential_lock WHERE id = ? FOR UPDATE",
                Long.class, SEQ_LOCK_ID);
    }

    /**
     * 查询资源当前最大版本号，无记录返回 0。调用前应持有 lockSequence()。
     */
    public int maxVersion(String resourceKey) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM resource_credentials WHERE resource_key = ?",
                Integer.class, resourceKey);
        return max == null ? 0 : max;
    }

    /**
     * 插入一条凭据版本，返回生成主键。(resource_key, version) 唯一约束兜底并发登记。
     */
    public long insert(ResourceCredential credential) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO resource_credentials (resource_key, version, credential_type, region,"
                            + " expires_at, status, created_by, revoked_at, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, credential.resourceKey());
            ps.setInt(2, credential.version());
            ps.setString(3, credential.credentialType());
            ps.setString(4, credential.region());
            ps.setTimestamp(5, Timestamp.from(credential.expiresAt()));
            ps.setString(6, credential.status().name());
            ps.setString(7, credential.createdBy());
            ps.setTimestamp(8, credential.revokedAt() == null ? null
                    : Timestamp.from(credential.revokedAt()));
            ps.setTimestamp(9, Timestamp.from(credential.createdAt()));
            ps.setTimestamp(10, Timestamp.from(credential.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按资源与版本号查询凭据（不加锁）。
     */
    public Optional<ResourceCredential> findVersion(String resourceKey, int version) {
        List<ResourceCredential> rows = jdbc.query(
                "SELECT * FROM resource_credentials WHERE resource_key = ? AND version = ?",
                MAPPER, resourceKey, version);
        return rows.stream().findFirst();
    }

    /**
     * 按资源与版本号查询并锁定凭据行（SELECT ... FOR UPDATE），
     * 用于派发与执行前复检，使撤销与执行启动按已提交状态串行裁决。
     */
    public Optional<ResourceCredential> lockVersion(String resourceKey, int version) {
        List<ResourceCredential> rows = jdbc.query(
                "SELECT * FROM resource_credentials WHERE resource_key = ? AND version = ? FOR UPDATE",
                MAPPER, resourceKey, version);
        return rows.stream().findFirst();
    }

    /**
     * 查询资源最新版本凭据并锁定该行（SELECT ... FOR UPDATE），
     * 用于派发：在语句级读已提交下取得当前已提交的最新版本并加行锁，
     * 与该版本的撤销、以及新版本登记按提交顺序裁决。
     */
    public Optional<ResourceCredential> lockLatest(String resourceKey) {
        List<ResourceCredential> rows = jdbc.query(
                "SELECT * FROM resource_credentials WHERE resource_key = ? AND version ="
                        + " (SELECT MAX(version) FROM resource_credentials WHERE resource_key = ?)"
                        + " FOR UPDATE",
                MAPPER, resourceKey, resourceKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询资源全部版本，按版本号升序返回。
     */
    public List<ResourceCredential> listByResource(String resourceKey) {
        return jdbc.query(
                "SELECT * FROM resource_credentials WHERE resource_key = ? ORDER BY version",
                MAPPER, resourceKey);
    }

    /**
     * 撤销凭据：仅 ACTIVE 可撤销，条件更新返回受影响行数（0 表示不存在、已撤销或并发抢先）。
     */
    public int revoke(long id, Instant revokedAt) {
        return jdbc.update(
                "UPDATE resource_credentials SET status = 'REVOKED', revoked_at = ?, updated_at = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                Timestamp.from(revokedAt), Timestamp.from(revokedAt), id);
    }
}
