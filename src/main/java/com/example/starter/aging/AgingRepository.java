package com.example.starter.aging;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;

/**
 * 熟成批次四张表的 JDBC 存取，全部使用参数绑定。
 */
@Repository
public class AgingRepository {

    private final JdbcTemplate jdbc;

    public AgingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AgingBatch> BATCH_MAPPER = (rs, n) -> new AgingBatch(
            rs.getLong("id"), rs.getString("batch_code"), rs.getString("request_id"),
            rs.getTimestamp("stored_at").toInstant(), rs.getInt("target_days"),
            rs.getString("status"), rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<TurnRecord> TURN_MAPPER = (rs, n) -> new TurnRecord(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getDate("turn_date").toLocalDate(),
            rs.getTimestamp("turned_at").toInstant(), rs.getString("operator_name"),
            rs.getString("request_id"));

    private static final RowMapper<TemperatureRecord> TEMP_MAPPER = (rs, n) -> new TemperatureRecord(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getDate("recorded_date").toLocalDate(),
            rs.getBigDecimal("celsius"), rs.getTimestamp("recorded_at").toInstant(),
            rs.getString("status"), rs.getString("void_reason"),
            toInstant(rs, "voided_at"), rs.getString("void_request_id"), rs.getString("request_id"));

    private static final RowMapper<ReleaseRecord> RELEASE_MAPPER = (rs, n) -> new ReleaseRecord(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("request_id"),
            rs.getTimestamp("released_at").toInstant(), rs.getString("snapshot_json"));

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    public long insertBatch(String batchCode, String requestId, Instant storedAt, int targetDays, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO aging_batch (batch_code, request_id, stored_at, target_days, status, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchCode);
            ps.setString(2, requestId);
            ps.setTimestamp(3, Timestamp.from(storedAt));
            ps.setInt(4, targetDays);
            ps.setString(5, AgingBatch.STATUS_AGING);
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<AgingBatch> findBatchById(long id) {
        return jdbc.query("SELECT * FROM aging_batch WHERE id = ?", BATCH_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<AgingBatch> findBatchByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM aging_batch WHERE request_id = ?", BATCH_MAPPER, requestId)
                .stream().findFirst();
    }

    public void markBatchReleased(long batchId) {
        jdbc.update("UPDATE aging_batch SET status = ? WHERE id = ?",
                AgingBatch.STATUS_RELEASED, batchId);
    }

    public long insertTurn(long batchId, LocalDate turnDate, Instant turnedAt, String operator, String requestId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO turn_record (batch_id, turn_date, turned_at, operator_name, request_id)"
                            + " VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setDate(2, Date.valueOf(turnDate));
            ps.setTimestamp(3, Timestamp.from(turnedAt));
            ps.setString(4, operator);
            ps.setString(5, requestId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<TurnRecord> findTurnByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM turn_record WHERE request_id = ?", TURN_MAPPER, requestId)
                .stream().findFirst();
    }

    public Optional<TurnRecord> findTurn(long batchId, LocalDate date) {
        return jdbc.query("SELECT * FROM turn_record WHERE batch_id = ? AND turn_date = ?",
                TURN_MAPPER, batchId, Date.valueOf(date)).stream().findFirst();
    }

    public List<TurnRecord> findTurns(long batchId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT * FROM turn_record WHERE batch_id = ? AND turn_date BETWEEN ? AND ?"
                + " ORDER BY turn_date", TURN_MAPPER, batchId, Date.valueOf(from), Date.valueOf(to));
    }
    public long insertTemperature(long batchId, LocalDate date, java.math.BigDecimal celsius,
            Instant recordedAt, String requestId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO temperature_record (batch_id, recorded_date, celsius, recorded_at, status, request_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setDate(2, Date.valueOf(date));
            ps.setBigDecimal(3, celsius);
            ps.setTimestamp(4, Timestamp.from(recordedAt));
            ps.setString(5, TemperatureRecord.STATUS_VALID);
            ps.setString(6, requestId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<TemperatureRecord> findTemperatureById(long id) {
        return jdbc.query("SELECT * FROM temperature_record WHERE id = ?", TEMP_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<TemperatureRecord> findTemperatureByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM temperature_record WHERE request_id = ?", TEMP_MAPPER, requestId)
                .stream().findFirst();
    }

    public Optional<TemperatureRecord> findTemperatureByVoidRequestId(String voidRequestId) {
        return jdbc.query("SELECT * FROM temperature_record WHERE void_request_id = ?", TEMP_MAPPER,
                voidRequestId).stream().findFirst();
    }

    public List<TemperatureRecord> findTemperatures(long batchId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT * FROM temperature_record WHERE batch_id = ?"
                + " AND recorded_date BETWEEN ? AND ? ORDER BY recorded_date, id",
                TEMP_MAPPER, batchId, Date.valueOf(from), Date.valueOf(to));
    }

    public void markTemperatureVoided(long id, String reason, Instant voidedAt, String voidRequestId) {
        jdbc.update("UPDATE temperature_record SET status = ?, void_reason = ?, voided_at = ?,"
                + " void_request_id = ? WHERE id = ?",
                TemperatureRecord.STATUS_VOID, reason, Timestamp.from(voidedAt), voidRequestId, id);
    }

    public long insertRelease(long batchId, String requestId, Instant releasedAt, String snapshotJson) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_record (batch_id, request_id, released_at, snapshot_json)"
                            + " VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, requestId);
            ps.setTimestamp(3, Timestamp.from(releasedAt));
            ps.setString(4, snapshotJson);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<ReleaseRecord> findReleaseByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM release_record WHERE batch_id = ?", RELEASE_MAPPER, batchId)
                .stream().findFirst();
    }

    public Optional<ReleaseRecord> findReleaseByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM release_record WHERE request_id = ?", RELEASE_MAPPER, requestId)
                .stream().findFirst();
    }
}