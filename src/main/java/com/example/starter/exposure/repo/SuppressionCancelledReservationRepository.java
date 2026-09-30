package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.SuppressionCancelledReservation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.List;

/**
 * 抑制取消预占审计明细数据访问。
 */
@Repository
public class SuppressionCancelledReservationRepository {

    private static final String COLUMNS =
            "suppression_id, reservation_id, campaign_id, utc_date, released_quota, cancelled_at_utc";

    private static final RowMapper<SuppressionCancelledReservation> MAPPER = (rs, rowNum) ->
            new SuppressionCancelledReservation(
                    rs.getString("suppression_id"),
                    rs.getString("reservation_id"),
                    rs.getString("campaign_id"),
                    rs.getDate("utc_date").toLocalDate(),
                    rs.getInt("released_quota"),
                    rs.getLong("cancelled_at_utc"));

    private final JdbcTemplate jdbc;

    public SuppressionCancelledReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 批量写入取消审计明细。 */
    public void insertBatch(List<SuppressionCancelledReservation> items) {
        jdbc.batchUpdate("INSERT INTO suppression_cancelled_reservation (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                items,
                items.size(),
                (ps, item) -> {
                    ps.setString(1, item.suppressionId());
                    ps.setString(2, item.reservationId());
                    ps.setString(3, item.campaignId());
                    ps.setDate(4, Date.valueOf(item.utcDate()));
                    ps.setInt(5, item.releasedQuota());
                    ps.setLong(6, item.cancelledAtUtc());
                });
    }

    /** 读取某抑制周期取消的全部预占明细，按预占单编号排序。 */
    public List<SuppressionCancelledReservation> listBySuppression(String suppressionId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM suppression_cancelled_reservation "
                        + "WHERE suppression_id = ? ORDER BY reservation_id",
                MAPPER, suppressionId);
    }
}
