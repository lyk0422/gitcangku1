package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
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
            ReservationStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at_utc"),
            rs.getLong("expires_at_utc"),
            (Long) rs.getObject("terminal_at_utc"),
            rs.getString("bundle_id"));

    private static final String COLUMNS =
            "reservation_id, campaign_id, visitor_id, utc_date, status, "
                    + "created_at_utc, expires_at_utc, terminal_at_utc, bundle_id";

    public void insert(Reservation reservation) {
        jdbc.update("INSERT INTO exposure_reservation (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                reservation.reservationId(),
                reservation.campaignId(),
                reservation.visitorId(),
                reservation.utcDate(),
                reservation.status().name(),
                reservation.createdAtUtc(),
                reservation.expiresAtUtc(),
                reservation.terminalAtUtc(),
                reservation.bundleId());
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
     * 无锁查询某公告下已到期（now &gt;= expires_at_utc）但仍为 RESERVED 的预占单。
     * 调用方随后按单条或按组加锁结算，避免持成员行锁再取组行锁造成死锁。
     */
    public List<Reservation> findExpiredReserved(String campaignId, long nowUtc) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_reservation "
                        + "WHERE campaign_id = ? AND status = 'RESERVED' AND expires_at_utc <= ?",
                MAPPER, campaignId, nowUtc);
    }

    /**
     * 按组编号查询成员预占单，固定按公告号排序，保证加锁/释放顺序确定。
     */
    public List<Reservation> findByBundleId(String bundleId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_reservation "
                        + "WHERE bundle_id = ? ORDER BY campaign_id, reservation_id",
                MAPPER, bundleId);
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
