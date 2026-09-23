package com.example.starter.restitution.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 写操作幂等记录仓储：仅成功结果占键，失败不写入。
 */
@Repository
public class RequestRecordRepository {

    private static final RowMapper<RequestRecordRow> MAPPER = (rs, n) -> new RequestRecordRow(
            rs.getString("request_id"),
            rs.getString("actor_id"),
            rs.getString("op_key"),
            rs.getString("fingerprint"),
            rs.getInt("http_status"),
            rs.getString("response_body"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public RequestRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RequestRecordRow find(String requestId) {
        List<RequestRecordRow> rows = jdbc.query(
                "select request_id, actor_id, op_key, fingerprint, http_status, response_body, created_at "
                        + "from request_record where request_id = ?",
                MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 加行锁读取幂等记录；不存在返回 null。
     */
    public RequestRecordRow lock(String requestId) {
        List<RequestRecordRow> rows = jdbc.query(
                "select request_id, actor_id, op_key, fingerprint, http_status, response_body, created_at "
                        + "from request_record where request_id = ? for update",
                MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 尝试占用幂等键；主键冲突返回 0 表示已被并发请求占用。
     */
    public int tryInsert(String requestId, String actorId, String opKey, String fingerprint) {
        return jdbc.update(
                "insert into request_record (request_id, actor_id, op_key, fingerprint, http_status, response_body) "
                        + "values (?, ?, ?, ?, 0, null)",
                requestId, actorId, opKey, fingerprint);
    }

    /**
     * 首次执行成功后回填响应；要求行仍为占位状态（http_status = 0），避免并发双填。
     */
    public int complete(String requestId, int httpStatus, String responseBody) {
        return jdbc.update(
                "update request_record set http_status = ?, response_body = ? "
                        + "where request_id = ? and http_status = 0",
                httpStatus, responseBody, requestId);
    }

    /**
     * 首次执行失败时释放占位，保证“失败不占键”。
     */
    public void release(String requestId) {
        jdbc.update("delete from request_record where request_id = ? and http_status = 0", requestId);
    }
}
