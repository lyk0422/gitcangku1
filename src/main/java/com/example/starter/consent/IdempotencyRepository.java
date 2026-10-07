package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求持久化访问：成功结果与业务变更同事务保存，应用重启后幂等语义仍成立。
 *
 * <p>每行携带结构化身份：操作类型、资源（主体＋用途）、期望版本（代次）与规范化参数指纹；
 * 指纹不含载荷原文（载荷以 SHA-256 参与），撤回后该代写入快照中的载荷被物理清除。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRow> MAPPER = (rs, rowNum) -> new IdempotencyRow(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("params_fingerprint"),
            rs.getString("response_body"),
            rs.getString("subject_key"),
            rs.getString("purpose"),
            (Integer) rs.getObject("epoch"));

    private static final String SELECT_COLUMNS =
            "request_id, operation, params_fingerprint, response_body, subject_key, purpose, epoch";

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等请求行。
     *
     * @param requestId         幂等请求标识
     * @param operation         操作类型：GRANT / WRITE / REVOKE / ERASE
     * @param paramsFingerprint 规范化参数指纹（含操作、资源、全参数及期望版本），用于检测同 requestId 参数变更
     * @param responseBody      成功响应快照（JSON）；撤回后所属代次的写入快照载荷被置空
     * @param subjectKey        资源主体标识
     * @param purpose           资源用途（枚举名字符串）
     * @param epoch             期望版本（授权代次）
     */
    public record IdempotencyRow(String requestId, String operation, String paramsFingerprint,
                                 String responseBody, String subjectKey, String purpose, Integer epoch) {
    }

    Optional<IdempotencyRow> find(String requestId) {
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM idempotency_request WHERE request_id = ?",
                MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 查询指定代次、指定操作类型的全部幂等行，用于撤回后清除缓存中的载荷原文。
     */
    List<IdempotencyRow> findByScope(String operation, String subjectKey, Purpose purpose, int epoch) {
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM idempotency_request"
                        + " WHERE operation = ? AND subject_key = ? AND purpose = ? AND epoch = ?",
                MAPPER, operation, subjectKey, purpose.name(), epoch);
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
     * 覆盖已保存的响应快照，用于撤回后清除快照中的载荷原文；身份与指纹保持不变。
     */
    void updateResponseBody(String requestId, String responseBody) {
        jdbc.update(
                "UPDATE idempotency_request SET response_body = ? WHERE request_id = ?",
                responseBody, requestId);
    }
}
