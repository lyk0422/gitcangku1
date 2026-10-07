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
 * 双人监护限时在场凭证的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内；(task_id, role, generation) 唯一约束
 * 兜底并发重复签到，同代次重插由唯一键拒绝。
 */
@Repository
public class PresenceCredentialRepository {

    private final JdbcTemplate jdbc;

    public PresenceCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PresenceCredential> MAPPER = (rs, n) -> map(rs);

    private static PresenceCredential map(ResultSet rs) throws SQLException {
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new PresenceCredential(
                rs.getLong("id"), rs.getLong("task_id"), rs.getString("role"),
                rs.getString("operator"), rs.getInt("generation"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                revokedAt == null ? null : revokedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 插入新代次凭证，返回生成主键。
     */
    public long insert(PresenceCredential credential) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO task_presence_credentials (task_id, role, operator, generation,"
                            + " issued_at, expires_at, revoked_at, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, credential.taskId());
            ps.setString(2, credential.role());
            ps.setString(3, credential.operator());
            ps.setInt(4, credential.generation());
            ps.setTimestamp(5, Timestamp.from(credential.issuedAt()));
            ps.setTimestamp(6, Timestamp.from(credential.expiresAt()));
            ps.setTimestamp(7, credential.revokedAt() == null ? null : Timestamp.from(credential.revokedAt()));
            ps.setTimestamp(8, Timestamp.from(credential.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询任务某角色当前最新代次凭证（代次最大者），无签到记录时为空。
     */
    public Optional<PresenceCredential> findLatest(long taskId, String role) {
        List<PresenceCredential> rows = jdbc.query(
                "SELECT * FROM task_presence_credentials WHERE task_id = ? AND role = ?"
                        + " ORDER BY generation DESC LIMIT 1",
                MAPPER, taskId, role);
        return rows.stream().findFirst();
    }

    /**
     * 查询任务某角色指定代次凭证，用于完成时核对请求引用的代次。
     */
    public Optional<PresenceCredential> findByGeneration(long taskId, String role, int generation) {
        List<PresenceCredential> rows = jdbc.query(
                "SELECT * FROM task_presence_credentials WHERE task_id = ? AND role = ?"
                        + " AND generation = ?",
                MAPPER, taskId, role, generation);
        return rows.stream().findFirst();
    }

    /**
     * 撤销指定凭证（写入撤销 UTC 时刻）。
     */
    public void revoke(long id, Instant at) {
        jdbc.update("UPDATE task_presence_credentials SET revoked_at = ? WHERE id = ?",
                Timestamp.from(at), id);
    }
}
