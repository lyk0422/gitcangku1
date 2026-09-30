package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Suppression;
import com.example.starter.exposure.domain.SuppressionCancellation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 访客抑制名单数据访问。加锁/条件更新方法必须在事务内调用，
 * 依赖 visitor_id 行锁与条件 UPDATE 保证加入名单、解除、曝光预占/确认之间的串行化。
 */
@Repository
public class SuppressionRepository {

    /**
     * 某批次按公告/UTC 日聚合的取消笔数。
     *
     * @param campaignId 公告编号
     * @param utcDate    额度所属 UTC 日
     * @param count      该公告当日取消的预占笔数（总/访客两级各释放同数次）
     */
    public record QuotaChangeRow(String campaignId, LocalDate utcDate, int count) {
    }

    private final JdbcTemplate jdbc;

    public SuppressionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SUPPRESSION_COLUMNS =
            "visitor_id, active, suppressed_at_utc, released_at_utc, cancelled_count, quota_released";

    private static final RowMapper<Suppression> MAPPER = (rs, rowNum) -> new Suppression(
            rs.getString("visitor_id"),
            rs.getBoolean("active"),
            (Long) rs.getObject("suppressed_at_utc"),
            (Long) rs.getObject("released_at_utc"),
            rs.getInt("cancelled_count"),
            rs.getInt("quota_released"));

    private static final RowMapper<SuppressionCancellation> CANCELLATION_MAPPER = (rs, rowNum) ->
            new SuppressionCancellation(
                    rs.getString("reservation_id"),
                    rs.getString("campaign_id"),
                    rs.getString("visitor_id"),
                    rs.getDate("utc_date"),
                    rs.getLong("suppressed_at_utc"));

    /** 确保访客抑制状态行存在（不存在则以未抑制创建），供行锁串行化使用。 */
    public void ensureVisitorRow(String visitorId) {
        jdbc.update("INSERT INTO visitor_suppression (visitor_id, active) "
                        + "SELECT ?, FALSE WHERE NOT EXISTS ("
                        + "SELECT 1 FROM visitor_suppression WHERE visitor_id = ?)",
                visitorId, visitorId);
    }

    /** 行锁读取访客抑制状态；行不存在返回 empty。 */
    public Optional<Suppression> lockByVisitor(String visitorId) {
        List<Suppression> list = jdbc.query(
                "SELECT " + SUPPRESSION_COLUMNS + " FROM visitor_suppression "
                        + "WHERE visitor_id = ? FOR UPDATE",
                MAPPER, visitorId);
        return list.stream().findFirst();
    }

    /** 普通读取访客抑制状态；行不存在返回 empty。 */
    public Optional<Suppression> findByVisitor(String visitorId) {
        List<Suppression> list = jdbc.query(
                "SELECT " + SUPPRESSION_COLUMNS + " FROM visitor_suppression WHERE visitor_id = ?",
                MAPPER, visitorId);
        return list.stream().findFirst();
    }

    /**
     * 条件激活：仅当当前非生效（active = FALSE）时置为生效，记录生效时刻并清空解除时刻。
     *
     * @return 是否激活成功；false 表示该访客已在名单中
     */
    public boolean activateIfInactive(String visitorId, long suppressedAtUtc) {
        int rows = jdbc.update("UPDATE visitor_suppression "
                        + "SET active = TRUE, suppressed_at_utc = ?, released_at_utc = NULL "
                        + "WHERE visitor_id = ? AND active = FALSE",
                suppressedAtUtc, visitorId);
        return rows == 1;
    }

    /**
     * 条件解除：仅当当前生效（active = TRUE）时置为非生效并记录解除时刻。
     *
     * @return 是否解除成功；false 表示该访客当前不在名单中
     */
    public boolean deactivateIfActive(String visitorId, long releasedAtUtc) {
        int rows = jdbc.update("UPDATE visitor_suppression "
                        + "SET active = FALSE, released_at_utc = ? "
                        + "WHERE visitor_id = ? AND active = TRUE",
                releasedAtUtc, visitorId);
        return rows == 1;
    }

    /** 记录最近一次加入名单的取消笔数与释放额度次数。 */
    public void updateCancellationStats(String visitorId, int cancelledCount, int quotaReleased) {
        int rows = jdbc.update("UPDATE visitor_suppression "
                        + "SET cancelled_count = ?, quota_released = ? WHERE visitor_id = ?",
                cancelledCount, quotaReleased, visitorId);
        if (rows != 1) {
            throw new IllegalStateException("suppression row missing for visitor " + visitorId);
        }
    }

    /** 插入一条抑制取消明细；预占单已被取消时由唯一约束拒绝。 */
    public void insertCancellation(SuppressionCancellation cancellation) {
        jdbc.update("INSERT INTO suppression_cancellation "
                        + "(reservation_id, campaign_id, visitor_id, utc_date, suppressed_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                cancellation.reservationId(),
                cancellation.campaignId(),
                cancellation.visitorId(),
                cancellation.utcDate(),
                cancellation.suppressedAtUtc());
    }

    /** 查询某访客指定抑制批次（生效时刻）取消的全部预占明细，按预占单编号排序。 */
    public List<SuppressionCancellation> findCancellations(String visitorId, long suppressedAtUtc) {
        return jdbc.query("SELECT reservation_id, campaign_id, visitor_id, utc_date, suppressed_at_utc "
                        + "FROM suppression_cancellation "
                        + "WHERE visitor_id = ? AND suppressed_at_utc = ? ORDER BY reservation_id",
                CANCELLATION_MAPPER, visitorId, suppressedAtUtc);
    }

    /** 按公告/UTC 日聚合某抑制批次的取消笔数（即两级额度各释放的次数）。 */
    public List<QuotaChangeRow> aggregateQuotaChanges(String visitorId, long suppressedAtUtc) {
        return jdbc.query("SELECT campaign_id, utc_date, COUNT(*) AS cnt FROM suppression_cancellation "
                        + "WHERE visitor_id = ? AND suppressed_at_utc = ? "
                        + "GROUP BY campaign_id, utc_date ORDER BY campaign_id, utc_date",
                (rs, rowNum) -> new QuotaChangeRow(
                        rs.getString("campaign_id"),
                        rs.getDate("utc_date").toLocalDate(),
                        rs.getInt("cnt")),
                visitorId, suppressedAtUtc);
    }
}
