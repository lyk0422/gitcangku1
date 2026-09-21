package com.example.starter.repo.jdbc;

import com.example.starter.domain.RequestRecord;
import com.example.starter.repo.DuplicateKeyException;
import com.example.starter.repo.RequestDedupRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 请求去重记录的 JDBC 持久化实现。
 * 占位插入与结果回填依赖调用方事务，与业务写入同生共死。
 */
@Repository
public class JdbcRequestDedupRepository implements RequestDedupRepository {

    private final JdbcTemplate jdbc;

    public JdbcRequestDedupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertPlaceholder(String requestId, String operation, String fingerprint) {
        try {
            jdbc.update("INSERT INTO request_dedup(request_id, operation, fingerprint,"
                            + " result_json, created_at) VALUES (?,?,?,NULL,?)",
                    requestId, operation, fingerprint, System.currentTimeMillis());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new DuplicateKeyException("requestId 已存在: " + requestId);
        }
    }

    @Override
    public Optional<RequestRecord> find(String requestId) {
        List<RequestRecord> rows = jdbc.query(
                "SELECT request_id, operation, fingerprint, result_json"
                        + " FROM request_dedup WHERE request_id=?",
                (rs, i) -> new RequestRecord(rs.getString("request_id"),
                        rs.getString("operation"), rs.getString("fingerprint"),
                        rs.getString("result_json")),
                requestId);
        return rows.stream().findFirst();
    }

    @Override
    public void complete(String requestId, String resultJson) {
        jdbc.update("UPDATE request_dedup SET result_json=? WHERE request_id=?",
                resultJson, requestId);
    }

    @Override
    public void abandon(String requestId) {
        // 业务失败时占位记录随调用方事务回滚，无需显式删除。
    }
}
