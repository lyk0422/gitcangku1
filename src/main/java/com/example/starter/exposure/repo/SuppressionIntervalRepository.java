package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.SuppressionInterval;
import com.example.starter.exposure.domain.SuppressionIntervalStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 访客曝光抑制区间数据访问。加锁/状态变更方法必须在事务内调用。
 *
 * <p>重叠与命中判定只考虑 ACTIVE 与 EARLY_ENDED 区间（DELETED 立即失效、不参与）；
 * 命中判定只考虑 ACTIVE 区间。</p>
 */
@Repository
public class SuppressionIntervalRepository {

    private final JdbcTemplate jdbc;

    public SuppressionIntervalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "interval_id, campaign_id, visitor_id, valid_from_utc, valid_until_utc, "
                    + "original_valid_until_utc, status, created_at_utc, deleted_at_utc";

    private static final RowMapper<SuppressionInterval> MAPPER = (rs, rowNum) -> new SuppressionInterval(
            rs.getString("interval_id"),
            rs.getString("campaign_id"),
            rs.getString("visitor_id"),
            rs.getLong("valid_from_utc"),
            rs.getLong("valid_until_utc"),
            (Long) rs.getObject("original_valid_until_utc"),
            SuppressionIntervalStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at_utc"),
            (Long) rs.getObject("deleted_at_utc"));

    public void insert(SuppressionInterval interval) {
        jdbc.update("INSERT INTO suppression_interval (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                interval.intervalId(),
                interval.campaignId(),
                interval.visitorId(),
                interval.validFromUtc(),
                interval.validUntilUtc(),
                interval.originalValidUntilUtc(),
                interval.status().name(),
                interval.createdAtUtc(),
                interval.deletedAtUtc());
    }

    public Optional<SuppressionInterval> findById(String intervalId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM suppression_interval WHERE interval_id = ?",
                        MAPPER, intervalId)
                .stream()
                .findFirst();
    }

    /** 行锁读取区间。 */
    public Optional<SuppressionInterval> lockById(String intervalId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM suppression_interval WHERE interval_id = ? FOR UPDATE",
                        MAPPER, intervalId)
                .stream()
                .findFirst();
    }

    /**
     * 行锁读取某公告下参与重叠判定的全部区间（ACTIVE 与 EARLY_ENDED），按创建时刻升序。
     */
    public List<SuppressionInterval> lockEffectiveByCampaign(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM suppression_interval "
                        + "WHERE campaign_id = ? AND status IN ('ACTIVE', 'EARLY_ENDED') "
                        + "ORDER BY created_at_utc FOR UPDATE",
                MAPPER, campaignId);
    }

    /**
     * 查询某公告某访客当前时刻命中的 ACTIVE 区间（左闭右开）。不重叠加锁，
     * 由调用方在持公告行锁的事务内调用。
     */
    public Optional<SuppressionInterval> findMatched(String campaignId, String visitorId, long nowUtc) {
        return jdbc.query("SELECT " + COLUMNS + " FROM suppression_interval "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND status = 'ACTIVE' "
                        + "AND valid_from_utc <= ? AND ? < valid_until_utc",
                        MAPPER, campaignId, visitorId, nowUtc, nowUtc)
                .stream()
                .findFirst();
    }

    /**
     * 查询某公告的区间历史（全部状态），可按访客过滤，按创建时刻升序。
     *
     * @param visitorId 为 null 时返回该公告全部区间
     */
    public List<SuppressionInterval> findHistory(String campaignId, String visitorId) {
        if (visitorId == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM suppression_interval "
                            + "WHERE campaign_id = ? ORDER BY created_at_utc, interval_id",
                    MAPPER, campaignId);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM suppression_interval "
                        + "WHERE campaign_id = ? AND visitor_id = ? ORDER BY created_at_utc, interval_id",
                MAPPER, campaignId, visitorId);
    }

    /**
     * 将未开始的 ACTIVE 区间标记为 DELETED（保留不可变删除记录）。
     *
     * @return 是否更新成功（并发下只有一个事务返回 true）
     */
    public boolean markDeleted(String intervalId, long deletedAtUtc) {
        int rows = jdbc.update("UPDATE suppression_interval SET status = 'DELETED', deleted_at_utc = ? "
                        + "WHERE interval_id = ? AND status = 'ACTIVE'",
                deletedAtUtc, intervalId);
        return rows == 1;
    }

    /**
     * 提前结束：缩短生效结束时刻并记录原始结束时刻；结束时刻已过则同时转为 EARLY_ENDED。
     *
     * @param newUntilUtc            新的生效结束时刻（不含）
     * @param originalValidUntilUtc  原始结束时刻（首次提前结束时的 valid_until_utc）
     * @param terminal               true 表示结束时刻已过，状态转 EARLY_ENDED
     * @return 是否更新成功（并发下只有一个事务返回 true）
     */
    public boolean shorten(String intervalId, long newUntilUtc, long originalValidUntilUtc, boolean terminal) {
        String newStatus = terminal
                ? SuppressionIntervalStatus.EARLY_ENDED.name()
                : SuppressionIntervalStatus.ACTIVE.name();
        int rows = jdbc.update("UPDATE suppression_interval SET valid_until_utc = ?, "
                        + "original_valid_until_utc = ?, status = ? "
                        + "WHERE interval_id = ? AND status = 'ACTIVE'",
                newUntilUtc, originalValidUntilUtc, newStatus, intervalId);
        return rows == 1;
    }
}
