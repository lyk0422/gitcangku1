package com.example.starter.repo;

import com.example.starter.domain.Rows.IdempotencyRow;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 幂等请求键存取。 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRow> MAPPER = (rs, n) -> new IdempotencyRow(
            rs.getString("request_id"),
            rs.getString("endpoint"),
            rs.getString("params_hash"),
            rs.getInt("response_status"),
            rs.getString("response_body"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按请求编号查询已存响应。 */
    public Optional<IdempotencyRow> find(String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT request_id, endpoint, params_hash, response_status, response_body,"
                            + " created_at FROM idempotency_key WHERE request_id = ?",
                    MAPPER, requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 保存请求键与首次响应；键冲突时抛出重复键异常。 */
    public void insert(IdempotencyRow row) {
        jdbc.update(
                "INSERT INTO idempotency_key (request_id, endpoint, params_hash, response_status,"
                        + " response_body, created_at) VALUES (?,?,?,?,?,?)",
                row.requestId(), row.endpoint(), row.paramsHash(), row.responseStatus(),
                row.responseBody(), Timestamp.from(row.createdAt()));
    }
}