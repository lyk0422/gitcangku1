package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 幂等去重持久化。request_id 全局唯一；记录与业务写入同一事务原子提交，
 * 业务失败回滚则不占键；并发重复插入由唯一键兜底并翻译为 409，不泄露唯一键异常。
 */
@Repository
public class IdempotencyRepository {

    /**
     * 已保存的幂等记录。
     *
     * @param fingerprint    请求身份指纹（结构化 JSON）
     * @param responseStatus 首次成功响应的 HTTP 状态码
     * @param responseBody   首次成功响应的完整 JSON 报文
     */
    public record StoredRecord(String fingerprint, int responseStatus, String responseBody) {
    }

    private static final RowMapper<StoredRecord> MAPPER = (rs, rowNum) -> new StoredRecord(
            rs.getString("fingerprint"),
            rs.getInt("response_status"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 requestId 查询已保存记录（不加锁）。
     */
    public Optional<StoredRecord> findByRequestId(String requestId) {
        return jdbc.query("SELECT fingerprint, response_status, response_body "
                        + "FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 保存幂等记录；request_id 冲突时抛出 DuplicateKeyException（由调用方翻译为 409）。
     */
    public void insert(String requestId, String fingerprint, int responseStatus,
                       String responseBody, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record "
                        + "(request_id, fingerprint, response_status, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                requestId, fingerprint, responseStatus, responseBody, JdbcTimes.toDb(createdAt));
    }
}
