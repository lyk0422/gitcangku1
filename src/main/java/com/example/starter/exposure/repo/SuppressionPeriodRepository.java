package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.SuppressionPeriod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 访客抑制周期历史数据访问。每次加入名单生成一行；解除补写止期。
 */
@Repository
public class SuppressionPeriodRepository {

    private static final String COLUMNS =
            "suppression_id, visitor_id, effective_from_utc, effective_to_utc, cancelled_count, "
                    + "released_quota, join_request_id, release_request_id, created_at_utc";

    private static final RowMapper<SuppressionPeriod> MAPPER = (rs, rowNum) -> new SuppressionPeriod(
            rs.getString("suppression_id"),
            rs.getString("visitor_id"),
            rs.getLong("effective_from_utc"),
            (Long) rs.getObject("effective_to_utc"),
            rs.getInt("cancelled_count"),
            rs.getInt("released_quota"),
            rs.getString("join_request_id"),
            rs.getString("release_request_id"),
            rs.getLong("created_at_utc"));

    private final JdbcTemplate jdbc;

    public SuppressionPeriodRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入新的抑制周期。 */
    public void insert(SuppressionPeriod period) {
        jdbc.update("INSERT INTO suppression_period (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                period.suppressionId(),
                period.visitorId(),
                period.effectiveFromUtc(),
                period.effectiveToUtc(),
                period.cancelledCount(),
                period.releasedQuota(),
                period.joinRequestId(),
                period.releaseRequestId(),
                period.createdAtUtc());
    }

    /** 行锁读取访客当前未解除的周期；正常至多一行。必须在事务内调用。 */
    public Optional<SuppressionPeriod> lockActiveByVisitor(String visitorId) {
        List<SuppressionPeriod> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM suppression_period "
                        + "WHERE visitor_id = ? AND effective_to_utc IS NULL FOR UPDATE",
                MAPPER, visitorId);
        return list.stream().findFirst();
    }

    /** 补写周期止期与解除幂等键。 */
    public void close(String suppressionId, long effectiveToUtc, String releaseRequestId) {
        int rows = jdbc.update("UPDATE suppression_period SET effective_to_utc = ?, release_request_id = ? "
                        + "WHERE suppression_id = ?",
                effectiveToUtc, releaseRequestId, suppressionId);
        if (rows != 1) {
            throw new IllegalStateException("suppression period missing: " + suppressionId);
        }
    }

    /** 按生效时刻倒序读取访客全部抑制周期。 */
    public List<SuppressionPeriod> listByVisitor(String visitorId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM suppression_period WHERE visitor_id = ? "
                        + "ORDER BY effective_from_utc DESC, suppression_id DESC",
                MAPPER, visitorId);
    }
}
