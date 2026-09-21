package com.example.starter.consent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 授权代次仓库，基于 JdbcTemplate 的参数化 SQL 实现。
 */
@Repository
public class ConsentGrantRepository {

    private static final GrantRowMapper MAPPER = new GrantRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public ConsentGrantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询某主体+用途当前有效的授权并加行锁（FOR UPDATE），用于写入与撤回串行化。
     */
    public Optional<ConsentGrant> findActiveForUpdate(String subjectKey, Purpose purpose) {
        List<ConsentGrant> rows = jdbcTemplate.query(
                "SELECT id, subject_key, purpose, epoch, status, request_id, created_at, revoked_at"
                        + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND status = 'ACTIVE'"
                        + " ORDER BY epoch DESC LIMIT 1 FOR UPDATE",
                MAPPER, subjectKey, purpose.name());
        return rows.stream().findFirst();
    }

    /**
     * 查询某主体+用途指定代次的授权并加行锁，用于撤回与写入串行化。
     */
    public Optional<ConsentGrant> findByEpochForUpdate(String subjectKey, Purpose purpose, int epoch) {
        List<ConsentGrant> rows = jdbcTemplate.query(
                "SELECT id, subject_key, purpose, epoch, status, request_id, created_at, revoked_at"
                        + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? FOR UPDATE",
                MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    /**
     * 查询某主体+用途最新一代授权（不限状态），用于计算下一代 epoch。
     */
    public Optional<ConsentGrant> findLatest(String subjectKey, Purpose purpose) {
        List<ConsentGrant> rows = jdbcTemplate.query(
                "SELECT id, subject_key, purpose, epoch, status, request_id, created_at, revoked_at"
                        + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ?"
                        + " ORDER BY epoch DESC LIMIT 1",
                MAPPER, subjectKey, purpose.name());
        return rows.stream().findFirst();
    }

    /**
     * 查询某主体+用途最新一代授权并加行锁，用于授权时与并发撤回串行化。
     */
    public Optional<ConsentGrant> findLatestForUpdate(String subjectKey, Purpose purpose) {
        List<ConsentGrant> rows = jdbcTemplate.query(
                "SELECT id, subject_key, purpose, epoch, status, request_id, created_at, revoked_at"
                        + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ?"
                        + " ORDER BY epoch DESC LIMIT 1 FOR UPDATE",
                MAPPER, subjectKey, purpose.name());
        return rows.stream().findFirst();
    }

    /**
     * 插入新一代授权（状态 ACTIVE），返回插入后的实体。
     */
    public ConsentGrant insert(String subjectKey, Purpose purpose, int epoch, String requestId) {
        jdbcTemplate.update(
                "INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?)",
                subjectKey, purpose.name(), epoch, requestId);
        return findByEpochForUpdate(subjectKey, purpose, epoch).orElseThrow();
    }

    /**
     * 将指定授权代次标记为已撤回。
     */
    public void markRevoked(long id) {
        jdbcTemplate.update(
                "UPDATE consent_grant SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP WHERE id = ?",
                id);
    }

    private static final class GrantRowMapper implements RowMapper<ConsentGrant> {

        @Override
        public ConsentGrant mapRow(ResultSet rs, int rowNum) throws SQLException {
            var revokedAt = rs.getTimestamp("revoked_at");
            return new ConsentGrant(
                    rs.getLong("id"),
                    rs.getString("subject_key"),
                    Purpose.valueOf(rs.getString("purpose")),
                    rs.getInt("epoch"),
                    GrantStatus.valueOf(rs.getString("status")),
                    rs.getString("request_id"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    revokedAt == null ? null : revokedAt.toLocalDateTime());
        }
    }
}
