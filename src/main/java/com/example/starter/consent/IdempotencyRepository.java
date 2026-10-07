package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求持久化访问：成功结果与业务变更同事务保存，应用重启后幂等语义仍成立。
 * 关联的主体／用途／代次用于清除任务定位并 scrub 已撤代次的写入快照。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRow> MAPPER = (rs, rowNum) -> {
        String purpose = rs.getString("purpose");
        int epoch = rs.getInt("epoch");
        return new IdempotencyRow(
                rs.getString("request_id"),
                rs.getString("operation"),
                rs.getString("params_fingerprint"),
                rs.getString("response_body"),
                rs.getString("subject_key"),
                purpose == null ? null : Purpose.valueOf(purpose),
                rs.wasNull() ? null : epoch);
    };

    private static final String SELECT_COLUMNS =
            "SELECT request_id, operation, params_fingerprint, response_body, subject_key, purpose, epoch"
                    + " FROM idempotency_request";

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等请求行。
     *
     * @param requestId         幂等请求标识
     * @param operation         操作类型：GRANT / WRITE / REVOKE / ERASE
     * @param paramsFingerprint 规范化参数指纹（载荷仅存哈希），用于检测同 requestId 参数变更
     * @param responseBody      成功响应快照（JSON），已撤代次的写入快照载荷被置为 null
     * @param subjectKey        关联主体标识（合成字符串）
     * @param purpose           关联用途
     * @param epoch             关联授权代次
     */
    public record IdempotencyRow(String requestId, String operation, String paramsFingerprint,
                                 String responseBody, String subjectKey, Purpose purpose, Integer epoch) {
    }

    Optional<IdempotencyRow> find(String requestId) {
        List<IdempotencyRow> rows = jdbc.query(
                SELECT_COLUMNS + " WHERE request_id = ?",
                MAPPER, requestId);
        return rows.stream().findFirst();
    }

    void insert(String requestId, String operation, String paramsFingerprint, String responseBody,
                String subjectKey, Purpose purpose, Integer epoch) {
        jdbc.update(
                "INSERT INTO idempotency_request"
                        + " (request_id, operation, params_fingerprint, response_body, subject_key, purpose, epoch)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                requestId, operation, paramsFingerprint, responseBody,
                subjectKey, purpose == null ? null : purpose.name(), epoch);
    }

    /**
     * 查询指定代次的全部写入成功快照，供清除任务 scrub 载荷原文。
     */
    List<IdempotencyRow> findWriteRows(String subjectKey, Purpose purpose, int epoch) {
        return jdbc.query(
                SELECT_COLUMNS + " WHERE operation = 'WRITE' AND subject_key = ? AND purpose = ? AND epoch = ?",
                MAPPER, subjectKey, purpose.name(), epoch);
    }

    /**
     * 用清除后的快照（载荷置空）覆盖原响应体，保留 requestId 与操作身份。
     */
    void updateResponseBody(String requestId, String responseBody) {
        jdbc.update(
                "UPDATE idempotency_request SET response_body = ? WHERE request_id = ?",
                responseBody, requestId);
    }
}
