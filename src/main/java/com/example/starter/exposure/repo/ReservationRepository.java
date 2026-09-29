package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
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
            rs.getString("activity_id"),
            rs.getDate("utc_date"),
            (Integer) rs.getObject("utc_hour"),
            ReservationStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at_utc"),
            rs.getLong("expires_at_utc"),
            (Long) rs.getObject("terminal_at_utc"));

    private static final String COLUMNS =
            "reservation_id, campaign_id, visitor_id, activity_id, utc_date, utc_hour, status, "
                    + "created_at_utc, expires_at_utc, terminal_at_utc";

    public void insert(Reservation reservation) {
        jdbc.update("INSERT INTO exposure_reservation (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                reservation.reservationId(),
                reservation.campaignId(),
                reservation.visitorId(),
                reservation.activityId(),
                reservation.utcDate(),
                reservation.utcHour(),
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
     * 行锁查询某公告下已到期（now &gt;= expires_at_utc）但仍为 RESERVED 的预占单。
     */
    public List<Reservation> lockExpiredReserved(String campaignId, long nowUtc) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_reservation "
                        + "WHERE campaign_id = ? AND status = 'RESERVED' AND expires_at_utc <= ? FOR UPDATE",
                MAPPER, campaignId, nowUtc);
    }

    /**
     * 行锁查询某活动某日某小时的全部预占单（日结固化前使用，阻塞并发终态写入）。
     */
    public List<Reservation> lockByActivityHour(String activityId, LocalDate utcDate, int utcHour) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_reservation "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ? FOR UPDATE",
                MAPPER, activityId, java.sql.Date.valueOf(utcDate), utcHour);
    }

    /**
     * 只读历史查询：按 活动/公告/访客 可选条件过滤申请时刻区间（左闭右开，epoch 毫秒），
     * 按申请时刻降序、预占单编号升序排序并限制条数。任一过滤条件为 null/空 表示不过滤。
     */
    public List<Reservation> findHistory(String activityId, String campaignId, String visitorId,
                                         Long fromCreatedAtInclusive, Long toCreatedAtExclusive,
                                         int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM exposure_reservation WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (activityId != null && !activityId.isBlank()) {
            sql.append(" AND activity_id = ?");
            args.add(activityId);
        }
        if (campaignId != null && !campaignId.isBlank()) {
            sql.append(" AND campaign_id = ?");
            args.add(campaignId);
        }
        if (visitorId != null && !visitorId.isBlank()) {
            sql.append(" AND visitor_id = ?");
            args.add(visitorId);
        }
        if (fromCreatedAtInclusive != null) {
            sql.append(" AND created_at_utc >= ?");
            args.add(fromCreatedAtInclusive);
        }
        if (toCreatedAtExclusive != null) {
            sql.append(" AND created_at_utc < ?");
            args.add(toCreatedAtExclusive);
        }
        sql.append(" ORDER BY created_at_utc DESC, reservation_id ASC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
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
