package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Suppression;
import com.example.starter.exposure.domain.SuppressionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 访客抑制名单当前状态数据访问。每个访客至多一行；
 * 调用方须先取得 visitor_lock 行锁后再读写，保证名单变更与曝光操作串行。
 */
@Repository
public class SuppressionRepository {

    private static final String COLUMNS =
            "visitor_id, status, effective_from_utc, effective_to_utc, request_id, "
                    + "cancelled_count, released_quota, updated_at_utc";

    private static final RowMapper<Suppression> MAPPER = (rs, rowNum) -> new Suppression(
            rs.getString("visitor_id"),
            SuppressionStatus.valueOf(rs.getString("status")),
            rs.getLong("effective_from_utc"),
            (Long) rs.getObject("effective_to_utc"),
            rs.getString("request_id"),
            rs.getInt("cancelled_count"),
            rs.getInt("released_quota"),
            rs.getLong("updated_at_utc"));

    private final JdbcTemplate jdbc;

    public SuppressionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按访客读取当前抑制状态（无锁）；从未加入名单返回 empty。 */
    public Optional<Suppression> findById(String visitorId) {
        List<Suppression> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM suppression_list WHERE visitor_id = ?",
                MAPPER, visitorId);
        return list.stream().findFirst();
    }

    /** 行锁读取当前抑制状态。必须在事务内调用。 */
    public Optional<Suppression> lockById(String visitorId) {
        List<Suppression> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM suppression_list WHERE visitor_id = ? FOR UPDATE",
                MAPPER, visitorId);
        return list.stream().findFirst();
    }

    /** 首次加入名单时插入 ACTIVE 行。 */
    public void insert(Suppression suppression) {
        jdbc.update("INSERT INTO suppression_list (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                suppression.visitorId(),
                suppression.status().name(),
                suppression.effectiveFromUtc(),
                suppression.effectiveToUtc(),
                suppression.requestId(),
                suppression.cancelledCount(),
                suppression.releasedQuota(),
                suppression.updatedAtUtc());
    }

    /** 解除后再次加入：以新周期数据重新激活当前行。 */
    public void reactivate(Suppression suppression) {
        int rows = jdbc.update("UPDATE suppression_list SET status = ?, effective_from_utc = ?, "
                        + "effective_to_utc = NULL, request_id = ?, cancelled_count = ?, "
                        + "released_quota = ?, updated_at_utc = ? WHERE visitor_id = ?",
                suppression.status().name(),
                suppression.effectiveFromUtc(),
                suppression.requestId(),
                suppression.cancelledCount(),
                suppression.releasedQuota(),
                suppression.updatedAtUtc(),
                suppression.visitorId());
        if (rows != 1) {
            throw new IllegalStateException("suppression row missing: " + suppression.visitorId());
        }
    }

    /** 解除：ACTIVE -> RELEASED，记录止期与解除幂等键。 */
    public void markReleased(String visitorId, long effectiveToUtc,
                             String requestId, long updatedAtUtc) {
        int rows = jdbc.update("UPDATE suppression_list SET status = 'RELEASED', effective_to_utc = ?, "
                        + "request_id = ?, updated_at_utc = ? WHERE visitor_id = ?",
                effectiveToUtc, requestId, updatedAtUtc, visitorId);
        if (rows != 1) {
            throw new IllegalStateException("suppression row missing: " + visitorId);
        }
    }
}
