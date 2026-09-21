package com.example.starter.consent;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 幂等请求仓库；幂等记录与业务写入在同一事务内提交，失败请求随回滚不占用 requestId。
 */
@Repository
public class IdempotencyRepository {

    private static final IdempotentRowMapper MAPPER = new IdempotentRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按 requestId 查询已成功的幂等记录。
     */
    public Optional<IdempotentRequest> find(String requestId) {
        List<IdempotentRequest> rows = jdbcTemplate.query(
                "SELECT request_id, request_type, fingerprint, http_status, response_body, created_at"
                        + " FROM idempotency_request WHERE request_id = ?",
                MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 尝试插入幂等记录；requestId 已存在时返回 false。
     */
    public boolean tryInsert(IdempotentRequest request) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO idempotency_request (request_id, request_type, fingerprint, http_status, response_body)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    request.requestId(), request.requestType(), request.fingerprint(),
                    request.httpStatus(), request.responseBody());
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    private static final class IdempotentRowMapper implements RowMapper<IdempotentRequest> {

        @Override
        public IdempotentRequest mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new IdempotentRequest(
                    rs.getString("request_id"),
                    rs.getString("request_type"),
                    rs.getString("fingerprint"),
                    rs.getInt("http_status"),
                    rs.getString("response_body"),
                    rs.getTimestamp("created_at").toLocalDateTime());
        }
    }
}
