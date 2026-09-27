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
            rs.getDate("utc_date"),
            rs.getString("activity_id"),
            (Integer) rs.getObject("utc_hour"),
            ReservationStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at_utc"),
            rs.getLong("expires_at_utc"),
            (Long) rs.getObject("terminal_at_utc"));

    private static final String COLUMNS =
            "reservation_id, campaign_id, visitor_id, utc_date, activity_id, utc_hour, status, "
                    + "created_at_utc, expires_at_utc, terminal_at_utc";

    public void insert(Reservation reservation) {
        jdbc.update("INSERT INTO exposure_reservation (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                reservation.reservationId(),
                reservation.campaignId(),
                reservation.visitorId(),
                reservation.utcDate(),
                reservation.activityId(),
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
     * 查询属于指定公告或指定活动的全部已到期 RESERVED 预占单（普通读，按编号升序）。
     * 申请预占前结算使用：既释放本公告跨迁移（旧活动/无活动）到期单的公告/访客额度，
     * 也释放同活动其他公告到期单的小时名额。调用方按本顺序逐行 CAS，
     * 保证跨事务共享单据的加锁相对顺序一致，避免交叉加锁死锁。
     */
    public List<Reservation> findExpiredForCampaignOrActivity(String campaignId,
                                                              String activityId, long nowUtc) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_reservation "
                        + "WHERE status = 'RESERVED' AND expires_at_utc <= ? "
                        + "AND (campaign_id = ? OR activity_id = ?) "
                        + "ORDER BY reservation_id",
                MAPPER, nowUtc, campaignId, activityId);
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
     * 只读历史查询：按公告（可叠加 UTC 日、状态过滤）倒序返回预占单，
     * 排序固定为 created_at_utc 降序、reservation_id 降序，保证分页稳定。
     */
    public List<Reservation> listHistory(String campaignId, LocalDate utcDate,
                                         ReservationStatus status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS
                + " FROM exposure_reservation WHERE campaign_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(campaignId);
        if (utcDate != null) {
            sql.append(" AND utc_date = ?");
            args.add(java.sql.Date.valueOf(utcDate));
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY created_at_utc DESC, reservation_id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }
}
