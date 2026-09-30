package com.example.starter.billing.repo;

import com.example.starter.billing.domain.Commitment;
import com.example.starter.billing.domain.DemandOffer;
import com.example.starter.billing.domain.MeterReading;
import com.example.starter.billing.domain.ReadingKind;
import com.example.starter.billing.domain.Settlement;
import com.example.starter.billing.domain.SettlementType;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 削峰结算业务链的 JDBC 仓储；所有 SQL 均使用参数绑定。
 */
@Repository
public class BillingRepository {

    private final JdbcTemplate jdbcTemplate;

    public BillingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 邀约 ====================

    public void insertOffer(DemandOffer offer) {
        jdbcTemplate.update("""
                INSERT INTO demand_offer (id, start_time, end_time, created_at)
                VALUES (?, ?, ?, ?)
                """, offer.id(), ts(offer.startTime()), ts(offer.endTime()), ts(offer.createdAt()));
    }

    public DemandOffer findOffer(String offerId) {
        List<DemandOffer> list = jdbcTemplate.query(
                "SELECT id, start_time, end_time, created_at FROM demand_offer WHERE id = ?",
                OFFER_MAPPER, offerId);
        return list.isEmpty() ? null : list.get(0);
    }

    // ==================== 承诺 ====================

    public Commitment insertCommitment(String offerId, String userId, BigDecimal committedKwh,
                                       String requestId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO commitment (offer_id, user_id, committed_kwh, request_id, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, offerId);
            ps.setString(2, userId);
            ps.setBigDecimal(3, committedKwh);
            ps.setString(4, requestId);
            ps.setTimestamp(5, ts(now));
            return ps;
        }, keyHolder);
        return findCommitment(offerId, userId);
    }

    /**
     * 行锁查询承诺；读数登记与结算事务先锁该用户业务链，保证结算看到的基线/实际版本一致。
     */
    public Commitment lockCommitment(String offerId, String userId) {
        List<Commitment> list = jdbcTemplate.query(
                "SELECT id, offer_id, user_id, committed_kwh, request_id, created_at"
                        + " FROM commitment WHERE offer_id = ? AND user_id = ? FOR UPDATE",
                COMMITMENT_MAPPER, offerId, userId);
        return list.isEmpty() ? null : list.get(0);
    }

    public Commitment findCommitmentByRequestId(String requestId) {
        List<Commitment> list = jdbcTemplate.query(
                "SELECT id, offer_id, user_id, committed_kwh, request_id, created_at"
                        + " FROM commitment WHERE request_id = ?",
                COMMITMENT_MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    public Commitment findCommitment(String offerId, String userId) {
        List<Commitment> list = jdbcTemplate.query(
                "SELECT id, offer_id, user_id, committed_kwh, request_id, created_at"
                        + " FROM commitment WHERE offer_id = ? AND user_id = ?",
                COMMITMENT_MAPPER, offerId, userId);
        return list.isEmpty() ? null : list.get(0);
    }

    // ==================== 读数 ====================

    public MeterReading insertReading(String offerId, String userId, ReadingKind kind, int version,
                                      BigDecimal valueKwh, Instant periodStart, Instant periodEnd,
                                      String correctionReason, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO meter_reading
                        (offer_id, user_id, kind, version, value_kwh, period_start, period_end,
                         correction_reason, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, offerId);
            ps.setString(2, userId);
            ps.setString(3, kind.name());
            ps.setInt(4, version);
            ps.setBigDecimal(5, valueKwh);
            ps.setTimestamp(6, ts(periodStart));
            ps.setTimestamp(7, ts(periodEnd));
            ps.setString(8, correctionReason);
            ps.setTimestamp(9, ts(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return findReadingById(key.longValue());
    }

    public MeterReading findReadingById(long id) {
        List<MeterReading> list = jdbcTemplate.query(
                "SELECT id, offer_id, user_id, kind, version, value_kwh, period_start, period_end,"
                        + " correction_reason, created_at FROM meter_reading WHERE id = ?",
                READING_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 查询某类读数的最新版本（不加锁）。
     */
    public MeterReading findLatestReading(String offerId, String userId, ReadingKind kind) {
        String sql = "SELECT id, offer_id, user_id, kind, version, value_kwh, period_start, period_end,"
                + " correction_reason, created_at FROM meter_reading"
                + " WHERE offer_id = ? AND user_id = ? AND kind = ?"
                + " ORDER BY version DESC LIMIT 1";
        List<MeterReading> list = jdbcTemplate.query(sql, READING_MAPPER,
                offerId, userId, kind.name());
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 锁定某用户业务链下某类读数的全部版本行（调用方须先锁承诺行），按版本倒序返回。
     * 不取 FETCH FIRST，以兼容 H2/MySQL 的 FOR UPDATE 语法；调用方取首行即最新版本。
     */
    public List<MeterReading> lockReadings(String offerId, String userId, ReadingKind kind) {
        String sql = "SELECT id, offer_id, user_id, kind, version, value_kwh, period_start, period_end,"
                + " correction_reason, created_at FROM meter_reading"
                + " WHERE offer_id = ? AND user_id = ? AND kind = ?"
                + " ORDER BY version DESC FOR UPDATE";
        return jdbcTemplate.query(sql, READING_MAPPER, offerId, userId, kind.name());
    }

    // ==================== 结算 ====================

    public Settlement insertSettlement(Settlement settlement) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO settlement
                        (offer_id, user_id, type, baseline_reading_id, actual_reading_id,
                         baseline_version, actual_version, committed_kwh, baseline_kwh, actual_kwh,
                         reduction_kwh, reward_kwh, amount_fen, total_amount_fen,
                         corrected_settlement_id, correction_reason, request_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, settlement.offerId());
            ps.setString(2, settlement.userId());
            ps.setString(3, settlement.type().name());
            ps.setObject(4, settlement.baselineReadingId(), java.sql.Types.BIGINT);
            ps.setObject(5, settlement.actualReadingId(), java.sql.Types.BIGINT);
            ps.setInt(6, settlement.baselineVersion());
            ps.setInt(7, settlement.actualVersion());
            ps.setBigDecimal(8, settlement.committedKwh());
            ps.setBigDecimal(9, settlement.baselineKwh());
            ps.setBigDecimal(10, settlement.actualKwh());
            ps.setBigDecimal(11, settlement.reductionKwh());
            ps.setBigDecimal(12, settlement.rewardKwh());
            ps.setLong(13, settlement.amountFen());
            ps.setLong(14, settlement.totalAmountFen());
            ps.setObject(15, settlement.correctedSettlementId(), java.sql.Types.BIGINT);
            ps.setString(16, settlement.correctionReason());
            ps.setString(17, settlement.requestId());
            ps.setTimestamp(18, ts(settlement.createdAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return findSettlementById(key.longValue());
    }

    public Settlement findSettlementById(long id) {
        List<Settlement> list = jdbcTemplate.query(
                "SELECT id, offer_id, user_id, type, baseline_reading_id, actual_reading_id,"
                        + " baseline_version, actual_version, committed_kwh, baseline_kwh, actual_kwh,"
                        + " reduction_kwh, reward_kwh, amount_fen, total_amount_fen,"
                        + " corrected_settlement_id, correction_reason, request_id, created_at"
                        + " FROM settlement WHERE id = ?",
                SETTLEMENT_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public Settlement findSettlementByRequestId(String requestId) {
        List<Settlement> list = jdbcTemplate.query(
                "SELECT id, offer_id, user_id, type, baseline_reading_id, actual_reading_id,"
                        + " baseline_version, actual_version, committed_kwh, baseline_kwh, actual_kwh,"
                        + " reduction_kwh, reward_kwh, amount_fen, total_amount_fen,"
                        + " corrected_settlement_id, correction_reason, request_id, created_at"
                        + " FROM settlement WHERE request_id = ?",
                SETTLEMENT_MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 锁定某用户业务链的全部账目行（调用方须先锁承诺行），按入账顺序返回。
     * 不取 FETCH FIRST，以兼容 H2/MySQL 的 FOR UPDATE 语法；调用方自行定位首次/最近一笔。
     */
    public List<Settlement> lockSettlements(String offerId, String userId) {
        String sql = "SELECT id, offer_id, user_id, type, baseline_reading_id, actual_reading_id,"
                + " baseline_version, actual_version, committed_kwh, baseline_kwh, actual_kwh,"
                + " reduction_kwh, reward_kwh, amount_fen, total_amount_fen,"
                + " corrected_settlement_id, correction_reason, request_id, created_at"
                + " FROM settlement WHERE offer_id = ? AND user_id = ? ORDER BY id FOR UPDATE";
        return jdbcTemplate.query(sql, SETTLEMENT_MAPPER, offerId, userId);
    }

    public List<Settlement> findSettlements(String offerId, String userId) {
        return jdbcTemplate.query(
                "SELECT id, offer_id, user_id, type, baseline_reading_id, actual_reading_id,"
                        + " baseline_version, actual_version, committed_kwh, baseline_kwh, actual_kwh,"
                        + " reduction_kwh, reward_kwh, amount_fen, total_amount_fen,"
                        + " corrected_settlement_id, correction_reason, request_id, created_at"
                        + " FROM settlement WHERE offer_id = ? AND user_id = ? ORDER BY id",
                SETTLEMENT_MAPPER, offerId, userId);
    }

    // ==================== 行映射 ====================

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<DemandOffer> OFFER_MAPPER = (rs, rowNum) -> new DemandOffer(
            rs.getString("id"),
            instant(rs, "start_time"),
            instant(rs, "end_time"),
            instant(rs, "created_at"));

    private static final RowMapper<Commitment> COMMITMENT_MAPPER = (rs, rowNum) -> new Commitment(
            rs.getLong("id"),
            rs.getString("offer_id"),
            rs.getString("user_id"),
            rs.getBigDecimal("committed_kwh"),
            rs.getString("request_id"),
            instant(rs, "created_at"));

    private static final RowMapper<MeterReading> READING_MAPPER = (rs, rowNum) -> new MeterReading(
            rs.getLong("id"),
            rs.getString("offer_id"),
            rs.getString("user_id"),
            ReadingKind.valueOf(rs.getString("kind")),
            rs.getInt("version"),
            rs.getBigDecimal("value_kwh"),
            instant(rs, "period_start"),
            instant(rs, "period_end"),
            rs.getString("correction_reason"),
            instant(rs, "created_at"));

    private static final RowMapper<Settlement> SETTLEMENT_MAPPER = (rs, rowNum) -> new Settlement(
            rs.getLong("id"),
            rs.getString("offer_id"),
            rs.getString("user_id"),
            SettlementType.valueOf(rs.getString("type")),
            (Long) rs.getObject("baseline_reading_id"),
            (Long) rs.getObject("actual_reading_id"),
            rs.getInt("baseline_version"),
            rs.getInt("actual_version"),
            rs.getBigDecimal("committed_kwh"),
            rs.getBigDecimal("baseline_kwh"),
            rs.getBigDecimal("actual_kwh"),
            rs.getBigDecimal("reduction_kwh"),
            rs.getBigDecimal("reward_kwh"),
            rs.getLong("amount_fen"),
            rs.getLong("total_amount_fen"),
            (Long) rs.getObject("corrected_settlement_id"),
            rs.getString("correction_reason"),
            rs.getString("request_id"),
            instant(rs, "created_at"));
}
