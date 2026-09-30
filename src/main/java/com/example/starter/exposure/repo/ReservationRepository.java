package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
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
            rs.getString("activity_id"),
            rs.getString("visitor_id"),
            rs.getDate("utc_date"),
            (Integer) rs.getObject("utc_hour"),
            ReservationStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at_utc"),
            rs.getLong("expires_at_utc"),
            (Long) rs.getObject("terminal_at_utc"));

    private static final String COLUMNS =
            "reservation_id, campaign_id, activity_id, visitor_id, utc_date, utc_hour, status, "
                    + "created_at_utc, expires_at_utc, terminal_at_utc";

    public void insert(Reservation reservation) {
        jdbc.update("INSERT INTO exposure_reservation (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                reservation.reservationId(),
                reservation.campaignId(),
                reservation.activityId(),
                reservation.visitorId(),
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
     * 按公告编号、创建时刻排序加锁，与按活动结算的路径保持同一锁序，避免交叉死锁。
     */
    public List<Reservation> lockExpiredReserved(String campaignId, long nowUtc) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_reservation "
                        + "WHERE campaign_id = ? AND status = 'RESERVED' AND expires_at_utc <= ? "
                        + "ORDER BY campaign_id, created_at_utc FOR UPDATE",
                MAPPER, campaignId, nowUtc);
    }

    /**
     * 行锁查询某活动（跨公告）下已到期但仍为 RESERVED 的预占单。
     * 按公告编号、创建时刻排序后加锁，配合调用方固定的账本加锁顺序避免死锁。
     */
    public List<Reservation> lockExpiredReservedByActivity(String activityId, long nowUtc) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_reservation "
                        + "WHERE activity_id = ? AND status = 'RESERVED' AND expires_at_utc <= ? "
                        + "ORDER BY campaign_id, created_at_utc FOR UPDATE",
                MAPPER, activityId, nowUtc);
    }

    /**
     * 按公告/访客/UTC 日区间稳定排序查询预占历史（只读，不加锁）。
     * 排序：创建时刻升序、预占单编号升序，保证时间精度与 null 语义稳定。
     *
     * @param fromUtc 起始 UTC 日（含），null 表示不限制下界
     * @param toUtc   结束 UTC 日（不含，左闭右开），null 表示不限制上界
     */
    public List<Reservation> findHistory(String campaignId, String visitorId,
                                         LocalDate fromUtc, LocalDate toUtc, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS
                + " FROM exposure_reservation WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (campaignId != null && !campaignId.isBlank()) {
            sql.append(" AND campaign_id = ?");
            args.add(campaignId);
        }
        if (visitorId != null && !visitorId.isBlank()) {
            sql.append(" AND visitor_id = ?");
            args.add(visitorId);
        }
        if (fromUtc != null) {
            sql.append(" AND utc_date >= ?");
            args.add(java.sql.Date.valueOf(fromUtc));
        }
        if (toUtc != null) {
            sql.append(" AND utc_date < ?");
            args.add(java.sql.Date.valueOf(toUtc));
        }
        sql.append(" ORDER BY created_at_utc ASC, reservation_id ASC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /**
     * 日结统计：某活动某 UTC 日某小时处于指定状态的预占数。
     * 仅统计固化到该活动与该小时的预占（历史归属不随迁移改写）。
     */
    public int countByActivityDayHourStatus(String activityId, LocalDate utcDate,
                                            int hour, ReservationStatus status) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ? AND status = ?",
                Integer.class, activityId, java.sql.Date.valueOf(utcDate), hour, status.name());
        return value == null ? 0 : value;
    }

    /**
     * 分组统计某活动某 UTC 日各小时各状态的预占数，一条 SQL 完成，供只读诊断/日结使用。
     *
     * @return 扁平的 (hour, status, count) 行；无预占的小时/状态不出现
     */
    public List<StatusCount> countGroupedByHourStatus(String activityId, LocalDate utcDate) {
        return jdbc.query(
                "SELECT utc_hour, status, COUNT(*) AS cnt FROM exposure_reservation "
                        + "WHERE activity_id = ? AND utc_date = ? GROUP BY utc_hour, status",
                (rs, rowNum) -> new StatusCount(rs.getInt("utc_hour"),
                        ReservationStatus.valueOf(rs.getString("status")), rs.getInt("cnt")),
                activityId, java.sql.Date.valueOf(utcDate));
    }

    /** 分组统计行：UTC 小时、状态、该组预占数（单位次）。 */
    public record StatusCount(int hour, ReservationStatus status, int count) {
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
