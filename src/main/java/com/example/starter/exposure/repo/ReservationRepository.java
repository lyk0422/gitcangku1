package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 曝光预占单数据访问。加锁/CAS 方法必须在事务内调用。
 */
@Repository
public class ReservationRepository {

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Reservation> MAPPER = (rs, rowNum) -> new Reservation(
            rs.getString("reservation_id"),
            rs.getString("campaign_id"),
            rs.getString("visitor_id"),
            rs.getString("channel_key"),
            rs.getDate("utc_date"),
            ReservationStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at_utc"),
            rs.getLong("expires_at_utc"),
            (Long) rs.getObject("terminal_at_utc"));

    private static final String COLUMNS =
            "reservation_id, campaign_id, visitor_id, channel_key, utc_date, status, "
                    + "created_at_utc, expires_at_utc, terminal_at_utc";

    public void insert(Reservation reservation) {
        jdbc.update("INSERT INTO exposure_reservation (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                reservation.reservationId(),
                reservation.campaignId(),
                reservation.visitorId(),
                reservation.channelKey(),
                reservation.utcDate(),
                reservation.status().name(),
                reservation.createdAtUtc(),
                reservation.expiresAtUtc(),
                reservation.terminalAtUtc());
    }

    public Optional<Reservation> findById(String reservationId) {
        List<Reservation> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM exposure_reservation WHERE reservation_id = ?",
                MAPPER, reservationId);
        return list.stream().findFirst();
    }

    /**
     * 行锁读取预占单。
     */
    public Optional<Reservation> lockById(String reservationId) {
        List<Reservation> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM exposure_reservation WHERE reservation_id = ? FOR UPDATE",
                MAPPER, reservationId);
        return list.stream().findFirst();
    }

    /**
     * 无锁查询某公告下已到期（now &gt;= expires_at_utc）但仍为 RESERVED 的预占单，
     * 按预占单编号升序；调用方据此先按全局顺序锁定相关账目行，再用
     * {@link #lockById} 对这些预占单重新加锁结算。
     */
    public List<Reservation> findExpiredReserved(String campaignId, long nowUtc) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_reservation "
                        + "WHERE campaign_id = ? AND status = 'RESERVED' AND expires_at_utc <= ? "
                        + "ORDER BY reservation_id",
                MAPPER, campaignId, nowUtc);
    }

    /**
     * 无锁查询全部已到期但仍为 RESERVED 的预占单（不限公告/渠道），按预占单编号升序；
     * 供无过滤条件的预占明细查询先按全局顺序加锁结算。
     */
    public List<Reservation> findAllExpiredReserved(long nowUtc) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_reservation "
                        + "WHERE status = 'RESERVED' AND expires_at_utc <= ? ORDER BY reservation_id",
                MAPPER, nowUtc);
    }

    /**
     * 无锁查询某渠道下已到期但仍为 RESERVED 的固化预占单（申请时未配置渠道、
     * channel_key 为 NULL 的预占不会被查出），供渠道用量查询按全局顺序加锁结算。
     */
    public List<Reservation> findExpiredReservedByChannel(String channelKey, long nowUtc) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_reservation "
                        + "WHERE channel_key = ? AND status = 'RESERVED' AND expires_at_utc <= ? "
                        + "ORDER BY reservation_id",
                MAPPER, channelKey, nowUtc);
    }

    /**
     * 行锁查询某渠道某 UTC 日的全部预占单，按预占单编号升序，
     * 供渠道额度修改在配置行锁之后统计当前已确认数并与并发确认串行。
     */
    public List<Reservation> lockByChannelDate(String channelKey, LocalDate utcDate) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_reservation "
                        + "WHERE channel_key = ? AND utc_date = ? ORDER BY reservation_id FOR UPDATE",
                MAPPER, channelKey, java.sql.Date.valueOf(utcDate));
    }

    /**
     * 条件 CAS：仅当当前状态为 expect 时改为 target 并记录终态时刻。
     *
     * @return 是否更新成功（并发终态竞争时只有一个返回 true）
     */
    public boolean compareAndSetStatus(String reservationId, ReservationStatus expect,
                                       ReservationStatus target, long terminalAtUtc) {
        int rows = jdbc.update("UPDATE exposure_reservation SET status = ?, terminal_at_utc = ? "
                        + "WHERE reservation_id = ? AND status = ?",
                target.name(), terminalAtUtc, reservationId, expect.name());
        return rows == 1;
    }

    /**
     * 预占明细查询：可按公告、渠道、UTC 日任意组合过滤（null 表示不过滤），按创建时刻升序。
     */
    public List<Reservation> search(String campaignId, String channelKey, LocalDate utcDate) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM exposure_reservation WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (campaignId != null) {
            sql.append(" AND campaign_id = ?");
            args.add(campaignId);
        }
        if (channelKey != null) {
            sql.append(" AND channel_key = ?");
            args.add(channelKey);
        }
        if (utcDate != null) {
            sql.append(" AND utc_date = ?");
            args.add(java.sql.Date.valueOf(utcDate));
        }
        sql.append(" ORDER BY created_at_utc ASC, reservation_id ASC");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** 归属统计一行：固化渠道（可能为 null）、状态、计数，单位条。 */
    public record AttributionCount(String channelKey, String status, int count) {
    }

    /**
     * 按公告归属统计：以预占创建时固化的渠道与状态分组，不随公告迁移变化。
     */
    public List<AttributionCount> groupAttribution(String campaignId) {
        return jdbc.query(
                "SELECT channel_key, status, COUNT(*) AS cnt FROM exposure_reservation "
                        + "WHERE campaign_id = ? GROUP BY channel_key, status",
                (rs, rowNum) -> new AttributionCount(
                        rs.getString("channel_key"),
                        rs.getString("status"),
                        rs.getInt("cnt")),
                campaignId);
    }
}
