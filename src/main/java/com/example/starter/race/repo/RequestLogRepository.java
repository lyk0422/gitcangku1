package com.example.starter.race.repo;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等去重表数据访问。仅记录成功请求；失败请求随事务回滚不占键。
 */
@Repository
public class RequestLogRepository {

    private final JdbcTemplate jdbc;

    public RequestLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按请求ID查询已成功的请求记录。
     */
    public Optional<RequestLogRow> find(String requestId) {
        return jdbc.query("SELECT request_id, action, fingerprint, response_body FROM request_log"
                        + " WHERE request_id = ?",
                (rs, i) -> new RequestLogRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4)), requestId)
                .stream().findFirst();
    }

    /**
     * 记录成功请求及其响应快照，与业务变更同事务提交。
     */
    public void insert(String requestId, String action, String fingerprint, String responseBody) {
        jdbc.update("INSERT INTO request_log (request_id, action, fingerprint, response_body)"
                + " VALUES (?, ?, ?, ?)", requestId, action, fingerprint, responseBody);
    }
}
