package com.example.starter.repo;

import com.example.starter.domain.IdempotencyRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求记录存取。
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按作用域与请求编号查询记录。 */
    public Optional<IdempotencyRecord> find(String scope, String requestId) {
        List<IdempotencyRecord> rows = jdbc.query(
                "SELECT * FROM idempotency_record WHERE scope = ? AND request_id = ?",
                (rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("scope"),
                        rs.getString("request_id"),
                        rs.getString("payload_hash"),
                        rs.getLong("entity_id"),
                        rs.getInt("http_status"),
                        rs.getString("error_message")),
                scope, requestId);
        return rows.stream().findFirst();
    }

    /** 写入幂等记录。 */
    public void insert(IdempotencyRecord record) {
        jdbc.update(
                "INSERT INTO idempotency_record (scope, request_id, payload_hash, entity_id, http_status,"
                        + " error_message) VALUES (?,?,?,?,?,?)",
                record.scope(), record.requestId(), record.payloadHash(), record.entityId(),
                record.httpStatus(), record.errorMessage());
    }
}