package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 代次令牌数据访问；令牌有效性在使用时由其代次状态决定，本表不存有效位。
 */
@Repository
public class AccessTokenRepository {

    /** 令牌行。 */
    public record AccessTokenRow(
            String tokenId,
            long generationId,
            String experimentId,
            String actorId,
            String roleName,
            long issuedAt) {
    }

    private static final RowMapper<AccessTokenRow> MAPPER = (rs, n) -> new AccessTokenRow(
            rs.getString("token_id"),
            rs.getLong("generation_id"),
            rs.getString("experiment_id"),
            rs.getString("actor_id"),
            rs.getString("role_name"),
            rs.getLong("issued_at"));

    private final JdbcTemplate jdbc;

    public AccessTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AccessTokenRow row) {
        jdbc.update("INSERT INTO access_token (token_id, generation_id, experiment_id, actor_id, "
                        + "role_name, issued_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.tokenId(), row.generationId(), row.experimentId(), row.actorId(),
                row.roleName(), row.issuedAt());
    }

    public AccessTokenRow findById(String tokenId) {
        List<AccessTokenRow> rows = jdbc.query(
                "SELECT token_id, generation_id, experiment_id, actor_id, role_name, issued_at "
                        + "FROM access_token WHERE token_id = ?",
                MAPPER, tokenId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
