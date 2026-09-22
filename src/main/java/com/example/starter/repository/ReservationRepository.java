package com.example.starter.repository;

import java.util.List;
import java.util.Optional;

import com.example.starter.domain.Reservation;
import com.example.starter.domain.ReservationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 预占表数据访问；所有状态迁移均以条件 UPDATE 或行锁保证单次终态。
 */
@Repository
public class ReservationRepository {

    private static final RowMapper<Reservation> MAPPER = (rs, n) -> {
        Reservation r = new Reservation();
        r.setId(rs.getLong("id"));
        r.setReservationId(rs.getString("reservation_id"));
        r.setCampaignId(rs.getString("campaign_id"));
        r.setVisitorId(rs.getString("visitor_id"));
        r.setUtcDate(rs.getString("utc_date"));
        r.setStatus(ReservationStatus.valueOf(rs.getString("status")));
        r.setExpiresAt(rs.getLong("expires_at"));
        r.setCreatedAt(rs.getLong("created_at"));
        return r;
    };

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Reservation r) {
        jdbc.update("""
                INSERT INTO reservation
                    (reservation_id, campaign_id, visitor_id, utc_date, status, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                r.getReservationId(), r.getCampaignId(), r.getVisitorId(), r.getUtcDate(),
                r.getStatus().name(), r.getExpiresAt(), r.getCreatedAt());
    }

    public Optional<Reservation> findByReservationId(String reservationId) {
        return jdbc.query("SELECT * FROM reservation WHERE reservation_id = ?", MAPPER, reservationId)
                .stream().findFirst();
    }

    /**
     * 行级锁定预占；并发确认/取消/过期回收在此串行化，保证只有一个终态。
     */
    public Optional<Reservation> findByReservationIdForUpdate(String reservationId) {
        return jdbc.query("SELECT * FROM reservation WHERE reservation_id = ? FOR UPDATE",
                        MAPPER, reservationId)
                .stream().findFirst();
    }

    /**
     * 按主键行级锁定预占。
     */
    public Optional<Reservation> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM reservation WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询某公告某 UTC 日所有已到期但仍为 RESERVED 的预占（不加行锁，仅用于确定结算集合）。
     */
    public List<Reservation> findExpired(String campaignId, String utcDate, long now) {
        return jdbc.query("""
                SELECT * FROM reservation
                WHERE campaign_id = ? AND utc_date = ? AND status = 'RESERVED' AND expires_at <= ?
                ORDER BY id
                """, MAPPER, campaignId, utcDate, now);
    }

    /**
     * 条件迁移：仅当当前状态为期望的旧状态时生效，返回受影响行数。
     */
    public int compareAndSetStatus(long id, ReservationStatus expected, ReservationStatus target) {
        return jdbc.update("UPDATE reservation SET status = ? WHERE id = ? AND status = ?",
                target.name(), id, expected.name());
    }
}
