package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.ApplyType;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 曝光预占单数据访问。加锁/CAS 方法必须在事务内调用。
 * 轮换计数（已确认轮换数）跨 UTC 日连续，仅统计 ROTATION 类型。
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
            ApplyType.valueOf(rs.getString("apply_type")),
            rs.getString("creative_id"),
            (Integer) rs.getObject("rotation_seq"),
            rs.getInt("version"),
            rs.getLong("created_at_utc"),
            rs.getLong("expires_at_utc"),
            (Long) rs.getObject("terminal_at_utc"));

    private static final String COLUMNS =
            "reservation_id, campaign_id, visitor_id, utc_date, status, apply_type, creative_id, "
                    + "rotation_seq, version, created_at_utc, expires_at_utc, terminal_at_utc";

    public void insert(Reservation reservation) {
        jdbc.update("INSERT INTO exposure_reservation (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                reservation.reservationId(),
                reservation.campaignId(),
                reservation.visitorId(),
                reservation.utcDate(),
                reservation.status().name(),
                reservation.applyType().name(),
                reservation.creativeId(),
                reservation.rotationSeq(),
                reservation.version(),
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
     * 条件 CAS：仅当当前状态为 expect 时改为 target、记录终态时刻并递增版本号。
     *
     * @return 是否更新成功（并发终态竞争时只有一个返回 true）
     */
    public boolean compareAndSetStatus(String reservationId, ReservationStatus expect,
                                       ReservationStatus target, long terminalAtUtc) {
        int rows = jdbc.update("UPDATE exposure_reservation "
                        + "SET status = ?, terminal_at_utc = ?, version = version + 1 "
                        + "WHERE reservation_id = ? AND status = ?",
                target.name(), terminalAtUtc, reservationId, expect.name());
        return rows == 1;
    }

    /**
     * 该访客在该公告下是否已有任意轮换预占记录（含终态）；
     * 有则表示轮换流程已开始，普通新申请应被拒绝（422）。
     */
    public boolean hasAnyRotation(String campaignId, String visitorId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM exposure_reservation "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND apply_type = 'ROTATION'",
                Integer.class, campaignId, visitorId);
        return count != null && count > 0;
    }

    /**
     * 该访客在该公告下已确认的轮换预占数（跨 UTC 日连续）；
     * 即下一次轮换申请的轮换序号，创意按其对创意总数取模选定。
     */
    public int countConfirmedRotation(String campaignId, String visitorId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM exposure_reservation "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND apply_type = 'ROTATION' "
                        + "AND status = 'CONFIRMED'",
                Integer.class, campaignId, visitorId);
        return count == null ? 0 : count;
    }
}
