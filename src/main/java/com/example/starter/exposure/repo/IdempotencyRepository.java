package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 写操作幂等记录数据访问。request_id 全局唯一；
 * 异参重放由服务层比对指纹后返回 409，失败回滚不占键。
 */
@Repository
public class IdempotencyRepository {

    /**
     * 幂等记录视图。
     *
     * @param requestId          幂等键
     * @param operation          操作类型
     * @param requestFingerprint 请求参数指纹（不含 requestId）
     * @param responseJson       原成功响应 JSON
     */
    public record IdempotencyRecord(String requestId, String operation,
                                    String requestFingerprint, String responseJson) {
    }

    private static final RowMapper<IdempotencyRecord> MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("request_fingerprint"),
            rs.getString("response_json"));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 行锁读取幂等记录；不存在返回 empty。 */
    public Optional<IdempotencyRecord> lockById(String requestId) {
        List<IdempotencyRecord> list = jdbc.query(
                "SELECT request_id, operation, request_fingerprint, response_json "
                        + "FROM idempotency_record WHERE request_id = ? FOR UPDATE",
                MAPPER, requestId);
        return list.stream().findFirst();
    }

    /** 普通读取（无锁），用于并发同键竞争中读取胜出者已提交的记录。 */
    public Optional<IdempotencyRecord> findById(String requestId) {
        List<IdempotencyRecord> list = jdbc.query(
                "SELECT request_id, operation, request_fingerprint, response_json "
                        + "FROM idempotency_record WHERE request_id = ?",
                MAPPER, requestId);
        return list.stream().findFirst();
    }

    public void insert(IdempotencyRecord record, long createdAtUtc) {
        jdbc.update("INSERT INTO idempotency_record "
                        + "(request_id, operation, request_fingerprint, response_json, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                record.requestId(),
                record.operation(),
                record.requestFingerprint(),
                record.responseJson(),
                createdAtUtc);
    }

    /**
     * 写回事务内预占（pending）幂等行的成功响应 JSON；与业务变更在同一事务提交。
     */
    public void updateResponse(String requestId, String responseJson) {
        int rows = jdbc.update("UPDATE idempotency_record SET response_json = ? WHERE request_id = ?",
                responseJson, requestId);
        if (rows != 1) {
            throw new IllegalStateException("idempotency row missing: " + requestId);
        }
    }
}
