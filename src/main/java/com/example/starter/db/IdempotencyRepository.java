package com.example.starter.db;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 幂等去重记录数据访问。 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRow> MAPPER = (rs, rowNum) -> new IdempotencyRow(
            rs.getString("request_id"),
            rs.getString("actor_id"),
            rs.getString("role"),
            rs.getString("operation"),
            rs.getString("params_hash"),
            (Integer) rs.getObject("response_status") == null ? 0 : rs.getInt("response_status"),
            rs.getString("response_body"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入幂等占位记录（响应字段为空），键冲突时抛出重复键异常。 */
    public void insert(IdempotencyRow row) {
        jdbc.update(
                "INSERT INTO idempotency_record (request_id, actor_id, role, operation, params_hash, "
                        + "response_status, response_body, created_at) VALUES (?, ?, ?, ?, ?, NULL, NULL, ?)",
                row.requestId(), row.actorId(), row.role(), row.operation(),
                row.paramsHash(), Timestamp.from(row.createdAt()));
    }

    /** 按 requestId 查询幂等记录。操作者与角色由服务层比对。 */
    public Optional<IdempotencyRow> findByRequestId(String requestId) {
        return jdbc.query(
                        "SELECT request_id, actor_id, role, operation, params_hash, response_status, "
                                + "response_body, created_at FROM idempotency_record WHERE request_id = ?",
                        MAPPER, requestId)
                .stream().findFirst();
    }

    /** 业务成功后原子写回原成功响应。 */
    public void saveResponse(String requestId, int responseStatus, String responseBody) {
        jdbc.update(
                "UPDATE idempotency_record SET response_status = ?, response_body = ? WHERE request_id = ?",
                responseStatus, responseBody, requestId);
    }
}
