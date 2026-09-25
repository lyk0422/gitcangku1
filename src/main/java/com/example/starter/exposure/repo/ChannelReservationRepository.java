package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.ChannelReservation;
import com.example.starter.exposure.domain.ReservationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 渠道预占记录数据访问。记录在创建时固化公告、访客、UTC 日、渠道与预占时刻，
 * 不随公告渠道迁移变更；状态与主预占单在同一事务内同步迁移。
 */
@Repository
public class ChannelReservationRepository {

    /** 渠道下按公告归属的统计视图。 */
    public record CampaignChannelStats(String campaignId, int reserved, int confirmed,
                                       int cancelled, int expired) {
    }

    private static final RowMapper<ChannelReservation> MAPPER = (rs, rowNum) -> new ChannelReservation(
            rs.getString("reservation_id"),
            rs.getString("channel_key"),
            rs.getString("campaign_id"),
            rs.getString("visitor_id"),
            rs.getDate("utc_date"),
            ReservationStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at_utc"),
            (Long) rs.getObject("terminal_at_utc"));

    private static final String COLUMNS =
            "reservation_id, channel_key, campaign_id, visitor_id, utc_date, status, "
                    + "created_at_utc, terminal_at_utc";

    private final JdbcTemplate jdbc;

    public ChannelReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ChannelReservation record) {
        jdbc.update("INSERT INTO channel_reservation (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                record.reservationId(),
                record.channelKey(),
                record.campaignId(),
                record.visitorId(),
                record.utcDate(),
                record.status().name(),
                record.createdAtUtc(),
                record.terminalAtUtc());
    }

    public Optional<ChannelReservation> findById(String reservationId) {
        List<ChannelReservation> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM channel_reservation WHERE reservation_id = ?",
                MAPPER, reservationId);
        return list.stream().findFirst();
    }

    /**
     * 与主预占单同步状态迁移：仅当当前状态为 expect 时改为 target 并记录终态时刻。
     *
     * @return 是否更新成功
     */
    public boolean compareAndSetStatus(String reservationId, ReservationStatus expect,
                                       ReservationStatus target, long terminalAtUtc) {
        int rows = jdbc.update("UPDATE channel_reservation SET status = ?, terminal_at_utc = ? "
                        + "WHERE reservation_id = ? AND status = ?",
                target.name(), terminalAtUtc, reservationId, expect.name());
        return rows == 1;
    }

    /** 查询渠道某 UTC 日的预占明细，按预占时刻升序。 */
    public List<ChannelReservation> listByChannelAndDate(String channelKey, LocalDate utcDate) {
        return jdbc.query("SELECT " + COLUMNS + " FROM channel_reservation "
                        + "WHERE channel_key = ? AND utc_date = ? ORDER BY created_at_utc, reservation_id",
                MAPPER, channelKey, java.sql.Date.valueOf(utcDate));
    }

    /** 查询渠道下仍为 RESERVED 的记录，用于渠道维度过期结算。 */
    public List<ChannelReservation> listReservedByChannel(String channelKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM channel_reservation "
                        + "WHERE channel_key = ? AND status = 'RESERVED'",
                MAPPER, channelKey);
    }

    /** 按公告归属统计渠道某 UTC 日各状态预占数。 */
    public List<CampaignChannelStats> statsByCampaign(String channelKey, LocalDate utcDate) {
        return jdbc.query("SELECT campaign_id, "
                        + "SUM(CASE WHEN status = 'RESERVED' THEN 1 ELSE 0 END) AS reserved, "
                        + "SUM(CASE WHEN status = 'CONFIRMED' THEN 1 ELSE 0 END) AS confirmed, "
                        + "SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled, "
                        + "SUM(CASE WHEN status = 'EXPIRED' THEN 1 ELSE 0 END) AS expired "
                        + "FROM channel_reservation WHERE channel_key = ? AND utc_date = ? "
                        + "GROUP BY campaign_id ORDER BY campaign_id",
                (rs, rowNum) -> new CampaignChannelStats(
                        rs.getString("campaign_id"),
                        rs.getInt("reserved"),
                        rs.getInt("confirmed"),
                        rs.getInt("cancelled"),
                        rs.getInt("expired")),
                channelKey, java.sql.Date.valueOf(utcDate));
    }
}
