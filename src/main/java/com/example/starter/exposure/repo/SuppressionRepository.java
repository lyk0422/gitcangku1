package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.SuppressionDeleteRecord;
import com.example.starter.exposure.domain.SuppressionInterval;
import com.example.starter.exposure.domain.SuppressionIntervalStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 访客抑制名单数据访问。区间表保存当前生效区间（含被提前结束缩短的区间）
 * 与未开始即删除的不可变行；删除记录表仅追加。
 * 所有加锁/更新方法必须在事务内调用。
 */
@Repository
public class SuppressionRepository {

    private final JdbcTemplate jdbc;

    public SuppressionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<SuppressionInterval> INTERVAL_MAPPER = (rs, rowNum) ->
            new SuppressionInterval(
                    rs.getString("interval_id"),
                    rs.getString("campaign_id"),
                    rs.getString("visitor_id"),
                    rs.getLong("start_at_utc"),
                    rs.getLong("end_at_utc"),
                    rs.getLong("original_end_at_utc"),
                    SuppressionIntervalStatus.valueOf(rs.getString("status")),
                    rs.getLong("created_at_utc"),
                    rs.getLong("updated_at_utc"),
                    (Long) rs.getObject("deleted_at_utc"),
                    (Long) rs.getObject("ended_early_at_utc"));

    private static final String INTERVAL_COLUMNS =
            "interval_id, campaign_id, visitor_id, start_at_utc, end_at_utc, original_end_at_utc, "
                    + "status, created_at_utc, updated_at_utc, deleted_at_utc, ended_early_at_utc";

    private static final RowMapper<SuppressionDeleteRecord> DELETE_RECORD_MAPPER = (rs, rowNum) ->
            new SuppressionDeleteRecord(
                    rs.getString("record_id"),
                    rs.getString("interval_id"),
                    rs.getString("campaign_id"),
                    rs.getString("visitor_id"),
                    rs.getLong("start_at_utc"),
                    rs.getLong("end_at_utc"),
                    rs.getLong("deleted_at_utc"),
                    rs.getString("request_id"));

    public void insertInterval(SuppressionInterval interval) {
        jdbc.update("INSERT INTO suppression_interval (" + INTERVAL_COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                interval.intervalId(),
                interval.campaignId(),
                interval.visitorId(),
                interval.startAtUtc(),
                interval.endAtUtc(),
                interval.originalEndAtUtc(),
                interval.status().name(),
                interval.createdAtUtc(),
                interval.updatedAtUtc(),
                interval.deletedAtUtc(),
                interval.endedEarlyAtUtc());
    }

    /** 行锁按编号读取区间。 */
    public Optional<SuppressionInterval> lockIntervalById(String intervalId) {
        List<SuppressionInterval> list = jdbc.query(
                "SELECT " + INTERVAL_COLUMNS + " FROM suppression_interval "
                        + "WHERE interval_id = ? FOR UPDATE",
                INTERVAL_MAPPER, intervalId);
        return list.stream().findFirst();
    }

    /**
     * 行锁读取某公告下全部区间（含 ACTIVE 与 DELETED），用于批量更新前
     * 校验完整最终集合；按访客与开始时刻排序，便于重叠检测。
     */
    public List<SuppressionInterval> lockAllByCampaign(String campaignId) {
        return jdbc.query(
                "SELECT " + INTERVAL_COLUMNS + " FROM suppression_interval "
                        + "WHERE campaign_id = ? ORDER BY visitor_id, start_at_utc, interval_id FOR UPDATE",
                INTERVAL_MAPPER, campaignId);
    }

    /** 读取某公告下全部 ACTIVE 区间（无锁），用于历史/状态查询。 */
    public List<SuppressionInterval> findActiveByCampaign(String campaignId) {
        return jdbc.query(
                "SELECT " + INTERVAL_COLUMNS + " FROM suppression_interval "
                        + "WHERE campaign_id = ? AND status = 'ACTIVE' "
                        + "ORDER BY visitor_id, start_at_utc",
                INTERVAL_MAPPER, campaignId);
    }

    /** 读取某公告某访客全部区间（含 DELETED，无锁），用于区间历史查询。 */
    public List<SuppressionInterval> findByCampaignAndVisitor(String campaignId, String visitorId) {
        return jdbc.query(
                "SELECT " + INTERVAL_COLUMNS + " FROM suppression_interval "
                        + "WHERE campaign_id = ? AND visitor_id = ? ORDER BY start_at_utc, interval_id",
                INTERVAL_MAPPER, campaignId, visitorId);
    }

    /**
     * 行锁查询某公告某访客在指定时刻命中的 ACTIVE 区间
     * （start_at_utc &lt;= now 且 end_at_utc &gt; now）。存在即表示该访客被抑制。
     */
    public Optional<SuppressionInterval> lockActiveHitting(String campaignId, String visitorId, long nowUtc) {
        List<SuppressionInterval> list = jdbc.query(
                "SELECT " + INTERVAL_COLUMNS + " FROM suppression_interval "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND status = 'ACTIVE' "
                        + "AND start_at_utc <= ? AND end_at_utc > ? FOR UPDATE",
                INTERVAL_MAPPER, campaignId, visitorId, nowUtc, nowUtc);
        return list.stream().findFirst();
    }

    /**
     * 未开始区间立即删除：ACTIVE -> DELETED 并记录删除时刻。
     * 仅当区间仍为 ACTIVE 且 start_at_utc &gt; nowUtc 时生效。
     *
     * @return 是否更新成功
     */
    public boolean markDeleted(String intervalId, long nowUtc) {
        int rows = jdbc.update("UPDATE suppression_interval "
                        + "SET status = 'DELETED', deleted_at_utc = ?, updated_at_utc = ? "
                        + "WHERE interval_id = ? AND status = 'ACTIVE' AND start_at_utc > ?",
                nowUtc, nowUtc, intervalId, nowUtc);
        return rows == 1;
    }

    /**
     * 已开始区间提前结束：仅缩短 end_at_utc（不得早于当前时刻），
     * original_end_at_utc 保持不变；只能缩短一次。
     *
     * @return 是否更新成功
     */
    public boolean shortenEnd(String intervalId, long newEndUtc, long nowUtc) {
        int rows = jdbc.update("UPDATE suppression_interval "
                        + "SET end_at_utc = ?, ended_early_at_utc = ?, updated_at_utc = ? "
                        + "WHERE interval_id = ? AND status = 'ACTIVE' "
                        + "AND ended_early_at_utc IS NULL AND ? >= start_at_utc AND ? < end_at_utc",
                newEndUtc, nowUtc, nowUtc, intervalId, nowUtc, nowUtc);
        return rows == 1;
    }

    /** 追加一条不可变删除记录。 */
    public void insertDeleteRecord(SuppressionDeleteRecord record) {
        jdbc.update("INSERT INTO suppression_delete_record "
                        + "(record_id, interval_id, campaign_id, visitor_id, start_at_utc, end_at_utc, "
                        + "deleted_at_utc, request_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                record.recordId(),
                record.intervalId(),
                record.campaignId(),
                record.visitorId(),
                record.startAtUtc(),
                record.endAtUtc(),
                record.deletedAtUtc(),
                record.requestId());
    }

    /** 查询某公告某访客的全部不可变删除记录（无锁），按删除时刻排序。 */
    public List<SuppressionDeleteRecord> findDeleteRecords(String campaignId, String visitorId) {
        return jdbc.query(
                "SELECT record_id, interval_id, campaign_id, visitor_id, start_at_utc, end_at_utc, "
                        + "deleted_at_utc, request_id FROM suppression_delete_record "
                        + "WHERE campaign_id = ? AND visitor_id = ? ORDER BY deleted_at_utc, record_id",
                DELETE_RECORD_MAPPER, campaignId, visitorId);
    }
}
