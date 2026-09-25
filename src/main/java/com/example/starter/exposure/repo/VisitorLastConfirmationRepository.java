package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.VisitorLastConfirmation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 访客对公告最近一次 CONFIRMED 确认时刻数据访问。
 * 仅确认成功事务内更新；取消与过期不调用。行锁用于：
 * 申请阶段冷却判定与并发确认按提交顺序串行，确认阶段串行化当日衰减序号。
 */
@Repository
public class VisitorLastConfirmationRepository {

    private static final RowMapper<VisitorLastConfirmation> MAPPER = (rs, rowNum) ->
            new VisitorLastConfirmation(
                    rs.getString("campaign_id"),
                    rs.getString("visitor_id"),
                    rs.getLong("last_confirmed_at_utc"));

    private final JdbcTemplate jdbc;

    public VisitorLastConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 普通读取（无锁），用于冷却状态查询；行不存在表示该访客从未确认过该公告。 */
    public Optional<VisitorLastConfirmation> find(String campaignId, String visitorId) {
        List<VisitorLastConfirmation> list = jdbc.query(
                "SELECT campaign_id, visitor_id, last_confirmed_at_utc "
                        + "FROM visitor_last_confirmation WHERE campaign_id = ? AND visitor_id = ?",
                MAPPER, campaignId, visitorId);
        return list.stream().findFirst();
    }

    /**
     * 行锁读取最近确认时刻；行不存在返回 empty。必须在事务内调用。
     * 申请冷却判定与并发确认通过本行锁按提交顺序串行。
     */
    public Optional<VisitorLastConfirmation> lock(String campaignId, String visitorId) {
        List<VisitorLastConfirmation> list = jdbc.query(
                "SELECT campaign_id, visitor_id, last_confirmed_at_utc "
                        + "FROM visitor_last_confirmation WHERE campaign_id = ? AND visitor_id = ? FOR UPDATE",
                MAPPER, campaignId, visitorId);
        return list.stream().findFirst();
    }

    /**
     * 更新最近确认时刻；行不存在则插入，并发首次插入冲突时重试更新。
     * 写入即持有行写锁至事务提交，同访客并发确认据此串行（当日衰减序号互斥）。
     */
    public void upsert(String campaignId, String visitorId, long confirmedAtUtc) {
        int rows = jdbc.update("UPDATE visitor_last_confirmation SET last_confirmed_at_utc = ? "
                        + "WHERE campaign_id = ? AND visitor_id = ?",
                confirmedAtUtc, campaignId, visitorId);
        if (rows == 0) {
            try {
                jdbc.update("INSERT INTO visitor_last_confirmation "
                                + "(campaign_id, visitor_id, last_confirmed_at_utc) VALUES (?, ?, ?)",
                        campaignId, visitorId, confirmedAtUtc);
            } catch (DuplicateKeyException concurrentInsert) {
                // 并发首次确认：胜出事务已插入，改为更新
                jdbc.update("UPDATE visitor_last_confirmation SET last_confirmed_at_utc = ? "
                                + "WHERE campaign_id = ? AND visitor_id = ?",
                        confirmedAtUtc, campaignId, visitorId);
            }
        }
    }
}
