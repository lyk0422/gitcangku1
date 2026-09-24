package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 写操作幂等记录持久化。requestId 全局唯一；仅在业务事务成功提交时写入，故失败不占键。
 *
 * @param requestId   请求幂等键
 * @param operation   操作类型
 * @param fingerprint 归一化请求参数指纹；同参放行重放，异参冲突
 * @param checkKey    成功产出的核查业务键
 * @param createdAt   首次成功提交时间（UTC）
 */
@Repository
public class RequestRecordRepository {

    /**
     * 幂等记录视图。
     */
    public record RequestRecord(String requestId, String operation, String fingerprint,
                                String checkKey, Instant createdAt) {
    }

    private static final RowMapper<RequestRecord> MAPPER = (rs, rowNum) -> new RequestRecord(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("fingerprint"),
            rs.getString("check_key"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public RequestRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入幂等记录；requestId 已存在时由唯一约束抛出 DuplicateKeyException。
     */
    public void insert(String requestId, String operation, String fingerprint,
                       String checkKey, Instant createdAt) {
        jdbc.update("INSERT INTO request_record (request_id, operation, fingerprint, check_key, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                requestId, operation, fingerprint, checkKey, JdbcTimes.toDb(createdAt));
    }

    /**
     * 按 requestId 查询已提交的幂等记录。
     */
    public Optional<RequestRecord> findById(String requestId) {
        return jdbc.query("SELECT * FROM request_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }
}
