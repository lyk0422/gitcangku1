package com.example.starter.exposure.budget.repo;

import com.example.starter.exposure.budget.domain.BudgetReservation;
import com.example.starter.exposure.domain.ReservationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 预算预占单数据访问。归属活动在创建时冻结；加锁/CAS 方法必须在事务内调用。
 */
@Repository
public class BudgetReservationRepository {

    private final JdbcTemplate jdbc;

    public BudgetReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "reservation_id, campaign_id, visitor_id, status, created_at_utc, expires_at_utc, terminal_at_utc";

    private static final RowMapper<BudgetReservation> MAPPER = (rs, rowNum) -> new BudgetReservation(
            rs.getString("reservation_id"),
            rs.getString("campaign_id"),
            rs.getString("visitor_id"),
            ReservationStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at_utc"),
            rs.getLong("expires_at_utc"),
            (Long) rs.getObject("terminal_at_utc"));

    public void insert(BudgetReservation reservation) {
        jdbc.update("INSERT INTO budget_reservation (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                reservation.reservationId(),
                reservation.campaignId(),
                reservation.visitorId(),
                reservation.status().name(),
                reservation.createdAtUtc(),
                reservation.expiresAtUtc(),
                reservation.terminalAtUtc());
    }

    public Optional<BudgetReservation> findById(String reservationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM budget_reservation WHERE reservation_id = ?",
                        MAPPER, reservationId)
                .stream()
                .findFirst();
    }

    /** 行锁读取单个预占单。 */
    public Optional<BudgetReservation> lockById(String reservationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM budget_reservation WHERE reservation_id = ? FOR UPDATE",
                        MAPPER, reservationId)
                .stream()
                .findFirst();
    }

    /** 行锁查询某活动下已到期（now &gt;= expires_at_utc）但仍为 RESERVED 的预占单。 */
    public List<BudgetReservation> lockExpiredReserved(String campaignId, long nowUtc) {
        return jdbc.query("SELECT " + COLUMNS + " FROM budget_reservation "
                        + "WHERE campaign_id = ? AND status = 'RESERVED' AND expires_at_utc <= ? FOR UPDATE",
                MAPPER, campaignId, nowUtc);
    }

    /**
     * 行锁锁定某活动全部在途（RESERVED）预占单。转移激活结算前调用：
     * 阻塞并发确认/取消/过期释放改动这些行，保证在途与已确认计数的一致性快照。
     */
    public List<BudgetReservation> lockReservedByCampaign(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM budget_reservation "
                        + "WHERE campaign_id = ? AND status = 'RESERVED' FOR UPDATE",
                MAPPER, campaignId);
    }

    /**
     * 条件 CAS：仅当当前状态为 expect 时改为 target 并记录终态时刻。
     *
     * @return 是否更新成功（并发终态竞争时只有一个返回 true）
     */
    public boolean compareAndSetStatus(String reservationId, ReservationStatus expect,
                                       ReservationStatus target, long terminalAtUtc) {
        int rows = jdbc.update("UPDATE budget_reservation SET status = ?, terminal_at_utc = ? "
                        + "WHERE reservation_id = ? AND status = ?",
                target.name(), terminalAtUtc, reservationId, expect.name());
        return rows == 1;
    }

    /** 统计某活动在途（RESERVED）数量。调用方须已结算过期预占。 */
    public long countReserved(String campaignId) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_reservation WHERE campaign_id = ? AND status = 'RESERVED'",
                Long.class, campaignId);
        return value == null ? 0L : value;
    }

    /**
     * 预览专用只读计数：统计某活动尚未到期（expiresAt &gt; now）的在途预占数，
     * 不结算、不写库；已到期但尚未被结算的在途单不计入可转余额。
     */
    public long countUnexpiredReserved(String campaignId, long nowUtc) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_reservation "
                        + "WHERE campaign_id = ? AND status = 'RESERVED' AND expires_at_utc > ?",
                Long.class, campaignId, nowUtc);
        return value == null ? 0L : value;
    }

    /** 统计某活动已确认（CONFIRMED）数量，已确认曝光不可回收。 */
    public long countConfirmed(String campaignId) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_reservation WHERE campaign_id = ? AND status = 'CONFIRMED'",
                Long.class, campaignId);
        return value == null ? 0L : value;
    }
}
