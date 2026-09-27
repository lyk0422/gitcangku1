package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotentRequest;

/**
 * 幂等请求快照持久化。request_id 主键冲突由唯一约束保证；仅成功提交写入，失败不占键。
 */
@Repository
public class IdempotentRequestRepository {

    private static final RowMapper<IdempotentRequest> MAPPER = (rs, rowNum) -> new IdempotentRequest(
            rs.getString("request_id"),
            rs.getString("scope"),
            rs.getString("actor"),
            rs.getString("operation_type"),
            rs.getString("fingerprint"),
            rs.getInt("status_code"),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotentRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取已成功提交的请求快照（不加锁）。
     */
    public Optional<IdempotentRequest> find(String requestId) {
        return jdbc.query("SELECT * FROM idempotent_request WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 写入成功请求快照；request_id 冲突抛出 DuplicateKeyException（并发下另一请求已先提交）。
     */
    public void insert(IdempotentRequest request) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO idempotent_request "
                            + "(request_id, scope, actor, operation_type, fingerprint, "
                            + "status_code, response_body, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            ps.setString(1, request.requestId());
            ps.setString(2, request.scope());
            ps.setString(3, request.actor());
            ps.setString(4, request.operationType());
            ps.setString(5, request.fingerprint());
            ps.setInt(6, request.statusCode());
            ps.setString(7, request.responseBody());
            ps.setObject(8, JdbcTimes.toDb(request.createdAt()));
            return ps;
        });
    }

    /**
     * 业务成功后补全响应快照（与占位行同事务，须在事务内调用）。
     */
    public void updateSnapshot(String requestId, int statusCode, String responseBody) {
        jdbc.update("UPDATE idempotent_request SET status_code = ?, response_body = ? WHERE request_id = ?",
                statusCode, responseBody, requestId);
    }
}
