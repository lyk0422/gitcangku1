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

    /**
     * 活动日结统计视图：某活动某 UTC 日每张预占单的小时起点与状态。
     *
     * @param hourStartUtc 申请时刻固化的小时起点（epoch 毫秒，UTC）
     * @param status       预占状态
     */
    public record ActivityHourStatus(long hourStartUtc, ReservationStatus status) {
    }

    private static final RowMapper<Reservation> MAPPER = (rs, rowNum) -> new Reservation(
            rs.getString("reservation_id"),
            rs.getString("campaign_id"),
            rs.getString("visitor_id"),
            rs.getDate("utc_date"),
            ReservationStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at_utc"),
            rs.getLong("expires_at_utc"),
            (Long) rs.getObject("terminal_at_utc"),
            rs.getString("activity_id"),
            (Long) rs.getObject("hour_start_utc"));

    private static final String COLUMNS =
            "reservation_id, campaign_id, visitor_id, utc_date, status, "
                    + "created_at_utc, expires_at_utc, terminal_at_utc, activity_id, hour_start_utc";

    public void insert(Reservation reservation) {
        jdbc.update("INSERT INTO exposure_reservation (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                reservation.reservationId(),
                reservation.campaignId(),
                reservation.visitorId(),
                reservation.utcDate(),
                reservation.status().name(),
                reservation.createdAtUtc(),
                reservation.expiresAtUtc(),
                reservation.terminalAtUtc(),
                reservation.activityId(),
                reservation.hourStartUtc());
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
     * 行锁查询某公告下已到期（now &gt;= expires_at_utc）但仍为 RESERVED 的预占单。
     */
    public List<Reservation> lockExpiredReserved(String campaignId, long nowUtc) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_reservation "
                        + "WHERE campaign_id = ? AND status = 'RESERVED' AND expires_at_utc <= ? FOR UPDATE",
                MAPPER, campaignId, nowUtc);
    }

    /**
     * 行锁查询某活动下已到期（now &gt;= expires_at_utc）但仍为 RESERVED 的预占单。
     */
    public List<Reservation> lockExpiredReservedByActivity(String activityId, long nowUtc) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_reservation "
                        + "WHERE activity_id = ? AND status = 'RESERVED' AND expires_at_utc <= ? FOR UPDATE",
                MAPPER, activityId, nowUtc);
    }

    /**
     * 某公告某 UTC 日的预占历史，按创建时刻、编号升序，保证排序稳定。
     */
    public List<Reservation> listByCampaignAndDate(String campaignId, LocalDate utcDate) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_reservation "
                        + "WHERE campaign_id = ? AND utc_date = ? "
                        + "ORDER BY created_at_utc ASC, reservation_id ASC",
                MAPPER, campaignId, java.sql.Date.valueOf(utcDate));
    }

    /**
     * 某活动某 UTC 日全部预占单的小时起点与状态，用于日结统计。
     */
    public List<ActivityHourStatus> listHourStatusByActivityAndDate(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT hour_start_utc, status FROM exposure_reservation "
                        + "WHERE activity_id = ? AND utc_date = ?",
                (rs, rowNum) -> new ActivityHourStatus(
                        rs.getLong("hour_start_utc"),
                        ReservationStatus.valueOf(rs.getString("status"))),
                activityId, java.sql.Date.valueOf(utcDate));
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
}
