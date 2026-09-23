package com.example.starter.calibration.repo;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 请求幂等键持久化。仅业务成功时随业务数据同一事务提交，业务失败回滚则不占用键。
 * 同键同参返回首次成功响应快照；同键异参冲突 409。
 */
@Repository
public class IdempotencyRepository {

    /** 请求类型：复核。 */
    public static final String KIND_REVIEW = "REVIEW";
    /** 请求类型：重新放行。 */
    public static final String KIND_RERELEASE = "RERELEASE";

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等记录。
     *
     * @param requestId    请求幂等键
     * @param kind         请求类型
     * @param fingerprint  归一化参数指纹
     * @param responseJson 首次成功响应快照
     */
    public record StoredRequest(String requestId, String kind, String fingerprint, String responseJson) {
    }

    /**
     * 查询幂等记录（不加锁）。
     */
    public Optional<StoredRequest> find(String requestId) {
        return jdbc.query(
                        "SELECT request_id, kind, fingerprint, response_json FROM request_idempotency "
                                + "WHERE request_id = ?",
                        (rs, n) -> new StoredRequest(
                                rs.getString("request_id"),
                                rs.getString("kind"),
                                rs.getString("fingerprint"),
                                rs.getString("response_json")),
                        requestId)
                .stream().findFirst();
    }

    /**
     * 占用幂等键并保存成功响应；键已存在抛出 DuplicateKeyException（须与业务同事务提交/回滚）。
     */
    public void insert(String requestId, String kind, String fingerprint, String responseJson) {
        jdbc.update("INSERT INTO request_idempotency "
                        + "(request_id, kind, fingerprint, response_json, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                requestId, kind, fingerprint, responseJson,
                JdbcTimes.toDb(java.time.Instant.now()));
    }
}
