package com.example.starter.archival.repo;

import com.example.starter.archival.domain.IdempotentRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 幂等请求仓储，requestId 主键保证并发下只有一条首次记录。
 */
@Repository
public class IdempotentRequestRepository {

    private static final RowMapper<IdempotentRequest> MAPPER = (rs, rowNum) -> new IdempotentRequest(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("fingerprint"),
            rs.getString("result_json"),
            (Long) rs.getObject("batch_id"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public IdempotentRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入首次请求记录。 */
    public int insert(String requestId, String operation, String fingerprint, String resultJson,
                      Long batchId, LocalDateTime createdAt) {
        return jdbc.update(
                "INSERT INTO idempotent_request (request_id, operation, fingerprint, result_json, batch_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                requestId, operation, fingerprint, resultJson, batchId, Timestamp.valueOf(createdAt));
    }

    /** 按 requestId 查询首次记录。 */
    public Optional<IdempotentRequest> find(String requestId) {
        List<IdempotentRequest> list = jdbc.query(
                "SELECT request_id, operation, fingerprint, result_json, batch_id, created_at "
                        + "FROM idempotent_request WHERE request_id = ?",
                MAPPER, requestId);
        return list.stream().findFirst();
    }
}
