package com.example.starter.repository;

import com.example.starter.model.Commitment;
import com.example.starter.model.Invitation;
import com.example.starter.model.LedgerEntry;
import com.example.starter.model.MeterReading;
import com.example.starter.model.Settlement;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 削峰业务仓储：全部 SQL 使用参数绑定；时间列存 UTC，金额列为整数分。
 */
@Repository
public class DemandResponseRepository {

    private final JdbcTemplate jdbc;

    public DemandResponseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertInvitation(Instant periodStart, Instant periodEnd, int rateFenPerKwh, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO invitation (period_start, period_end, rate_fen_per_kwh, created_at) "
                            + "VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setTimestamp(1, Timestamp.from(periodStart));
            ps.setTimestamp(2, Timestamp.from(periodEnd));
            ps.setInt(3, rateFenPerKwh);
            ps.setTimestamp(4, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Invitation> findInvitation(long invitationId) {
        return queryOne(
                "SELECT id, period_start, period_end, rate_fen_per_kwh, created_at "
                        + "FROM invitation WHERE id = ?",
                invitationRowMapper(), invitationId);
    }

    public long insertCommitment(long invitationId, String userId, BigDecimal reductionKwh,
                                 String requestId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO commitment (invitation_id, user_id, reduction_kwh, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, invitationId);
            ps.setString(2, userId);
            ps.setBigDecimal(3, reductionKwh);
            ps.setString(4, requestId);
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Commitment> findCommitment(long invitationId, String userId) {
        return queryOne(
                "SELECT id, invitation_id, user_id, reduction_kwh, request_id, created_at "
                        + "FROM commitment WHERE invitation_id = ? AND user_id = ?",
                commitmentRowMapper(), invitationId, userId);
    }

    /**
     * 锁定该用户在该邀约下的承诺行；读数提交与结算都先取此锁，从而串行化、避免版本拼接。
     */
    public Optional<Commitment> lockCommitment(long invitationId, String userId) {
        return queryOne(
                "SELECT id, invitation_id, user_id, reduction_kwh, request_id, created_at "
                        + "FROM commitment WHERE invitation_id = ? AND user_id = ? FOR UPDATE",
                commitmentRowMapper(), invitationId, userId);
    }

    public Optional<Commitment> findCommitmentByRequestId(String requestId) {
        return queryOne(
                "SELECT id, invitation_id, user_id, reduction_kwh, request_id, created_at "
                        + "FROM commitment WHERE request_id = ?",
                commitmentRowMapper(), requestId);
    }

    /**
     * 当前最新读数版本；无任何读数时返回空。
     */
    public Optional<Integer> findMaxReadingVersion(long invitationId, String userId) {
        Integer version = jdbc.queryForObject(
                "SELECT MAX(version) FROM meter_reading WHERE invitation_id = ? AND user_id = ?",
                Integer.class, invitationId, userId);
        return Optional.ofNullable(version);
    }

    public void insertReading(MeterReading reading) {
        jdbc.update("INSERT INTO meter_reading "
                        + "(invitation_id, user_id, reading_type, version, energy_kwh, "
                        + "period_start, period_end, corrected_reason, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                reading.invitationId(), reading.userId(), reading.readingType(), reading.version(),
                reading.energyKwh(), Timestamp.from(reading.periodStart()), Timestamp.from(reading.periodEnd()),
                reading.correctedReason(), Timestamp.from(reading.createdAt()));
    }

    public Optional<MeterReading> findReading(long invitationId, String userId, String readingType, int version) {
        return queryOne(
                "SELECT id, invitation_id, user_id, reading_type, version, energy_kwh, "
                        + "period_start, period_end, corrected_reason, created_at "
                        + "FROM meter_reading WHERE invitation_id = ? AND user_id = ? "
                        + "AND reading_type = ? AND version = ?",
                readingRowMapper(), invitationId, userId, readingType, version);
    }

    public List<MeterReading> findReadings(long invitationId, String userId) {
        return jdbc.query(
                "SELECT id, invitation_id, user_id, reading_type, version, energy_kwh, "
                        + "period_start, period_end, corrected_reason, created_at "
                        + "FROM meter_reading WHERE invitation_id = ? AND user_id = ? ORDER BY version, id",
                readingRowMapper(), invitationId, userId);
    }

    public long insertSettlement(long invitationId, String userId, long commitmentId, int readingVersion,
                                 BigDecimal rewardKwh, long amountFen, String requestId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO settlement "
                            + "(invitation_id, user_id, commitment_id, reading_version, reward_kwh, "
                            + "amount_fen, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, invitationId);
            ps.setString(2, userId);
            ps.setLong(3, commitmentId);
            ps.setInt(4, readingVersion);
            ps.setBigDecimal(5, rewardKwh);
            ps.setLong(6, amountFen);
            ps.setString(7, requestId);
            ps.setTimestamp(8, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Settlement> findSettlement(long invitationId, String userId) {
        return queryOne(
                "SELECT id, invitation_id, user_id, commitment_id, reading_version, reward_kwh, "
                        + "amount_fen, request_id, created_at FROM settlement "
                        + "WHERE invitation_id = ? AND user_id = ?",
                settlementRowMapper(), invitationId, userId);
    }

    public Optional<Settlement> findSettlementByRequestId(String requestId) {
        return queryOne(
                "SELECT id, invitation_id, user_id, commitment_id, reading_version, reward_kwh, "
                        + "amount_fen, request_id, created_at FROM settlement WHERE request_id = ?",
                settlementRowMapper(), requestId);
    }

    public long insertLedger(long invitationId, String userId, Long settlementId, String entryType,
                             Integer oldVersion, int newVersion, BigDecimal rewardKwh,
                             long amountFen, String reason, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ledger_entry "
                            + "(invitation_id, user_id, settlement_id, entry_type, old_version, new_version, "
                            + "reward_kwh, amount_fen, reason, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, invitationId);
            ps.setString(2, userId);
            if (settlementId == null) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, settlementId);
            }
            ps.setString(4, entryType);
            if (oldVersion == null) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setInt(5, oldVersion);
            }
            ps.setInt(6, newVersion);
            ps.setBigDecimal(7, rewardKwh);
            ps.setLong(8, amountFen);
            ps.setString(9, reason);
            ps.setTimestamp(10, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<LedgerEntry> findLedger(long invitationId, String userId) {
        return jdbc.query(
                "SELECT id, invitation_id, user_id, settlement_id, entry_type, old_version, new_version, "
                        + "reward_kwh, amount_fen, reason, created_at FROM ledger_entry "
                        + "WHERE invitation_id = ? AND user_id = ? ORDER BY id",
                ledgerRowMapper(), invitationId, userId);
    }

    private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        try {
            return Optional.of(jdbc.queryForObject(sql, mapper, args));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private static RowMapper<Invitation> invitationRowMapper() {
        return (rs, n) -> new Invitation(
                rs.getLong("id"),
                rs.getTimestamp("period_start").toInstant(),
                rs.getTimestamp("period_end").toInstant(),
                rs.getInt("rate_fen_per_kwh"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static RowMapper<Commitment> commitmentRowMapper() {
        return (rs, n) -> new Commitment(
                rs.getLong("id"),
                rs.getLong("invitation_id"),
                rs.getString("user_id"),
                rs.getBigDecimal("reduction_kwh"),
                rs.getString("request_id"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static RowMapper<MeterReading> readingRowMapper() {
        return (rs, n) -> new MeterReading(
                rs.getLong("id"),
                rs.getLong("invitation_id"),
                rs.getString("user_id"),
                rs.getString("reading_type"),
                rs.getInt("version"),
                rs.getBigDecimal("energy_kwh"),
                rs.getTimestamp("period_start").toInstant(),
                rs.getTimestamp("period_end").toInstant(),
                rs.getString("corrected_reason"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static RowMapper<Settlement> settlementRowMapper() {
        return (rs, n) -> new Settlement(
                rs.getLong("id"),
                rs.getLong("invitation_id"),
                rs.getString("user_id"),
                rs.getLong("commitment_id"),
                rs.getInt("reading_version"),
                rs.getBigDecimal("reward_kwh"),
                rs.getLong("amount_fen"),
                rs.getString("request_id"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static RowMapper<LedgerEntry> ledgerRowMapper() {
        return (rs, n) -> new LedgerEntry(
                rs.getLong("id"),
                rs.getLong("invitation_id"),
                rs.getString("user_id"),
                (Long) rs.getObject("settlement_id"),
                rs.getString("entry_type"),
                (Integer) rs.getObject("old_version"),
                rs.getInt("new_version"),
                rs.getBigDecimal("reward_kwh"),
                rs.getLong("amount_fen"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant());
    }
}
