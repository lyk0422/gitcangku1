package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.DedupRecord;

/**
 * 写请求幂等去重持久化。request_id 主键即唯一约束；
 * 去重记录与业务写入同一事务提交，业务失败则随事务回滚。
 */
@Repository
public class RequestDedupRepository {

    private static final RowMapper<DedupRecord> MAPPER = (rs, rowNum) -> new DedupRecord(
            rs.getString("request_id"),
            rs.getString("identity_hash"),
            rs.getString("operation"),
            rs.getString("resource"),
            rs.getString("operator"),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public RequestDedupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 占位插入去重记录（响应体待业务成功后回填）；request_id 冲突时抛出 DuplicateKeyException。
     */
    public void insertPlaceholder(String requestId, String identityHash, String operation,
                                  String resource, String operator, Instant createdAt) {
        jdbc.update("INSERT INTO request_dedup "
                        + "(request_id, identity_hash, operation, resource, operator, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, NULL, ?)",
                requestId, identityHash, operation, resource, operator, JdbcTimes.toDb(createdAt));
    }

    /**
     * 回填首次成功的完整响应体（须在同一事务内调用）。
     */
    public void fillResponse(String requestId, String responseBody) {
        jdbc.update("UPDATE request_dedup SET response_body = ? WHERE request_id = ?",
                responseBody, requestId);
    }

    /**
     * 按 requestId 查询（不加锁）。
     */
    public Optional<DedupRecord> findById(String requestId) {
        return jdbc.query("SELECT * FROM request_dedup WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }
}
