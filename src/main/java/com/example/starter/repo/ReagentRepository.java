package com.example.starter.repo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 试剂瓶、领用流水与事件记录的 JDBC 访问。 */
@Repository
public class ReagentRepository {

    /** 试剂瓶行快照；时刻均为 UTC，未开封/未隔离字段可为 null。 */
    public record BottleRow(long id, String lotNumber, BigDecimal capacityMl, BigDecimal remainingMl,
            Instant supplierExpiryUtc, Instant openedAtUtc, Instant effectiveDeadlineUtc,
            String deadlineSource, boolean quarantined, String quarantineReason, Instant quarantinedAtUtc) {}

    /** 领用流水行快照。 */
    public record DispenseRow(long id, long bottleId, String requestId, BigDecimal amountMl,
            BigDecimal remainingAfterMl, String deadlineSource, Instant dispensedAtUtc) {}

    /** 事件行快照。 */
    public record EventRow(long id, long bottleId, String eventType, Instant occurredAtUtc, String detail) {}

    private static final String BOTTLE_COLS = "id, lot_number, capacity_ml, remaining_ml, supplier_expiry_utc,"
            + " opened_at_utc, effective_deadline_utc, deadline_source, quarantined, quarantine_reason,"
            + " quarantined_at_utc";

    private static final RowMapper<BottleRow> BOTTLE_MAPPER = (rs, n) -> new BottleRow(
            rs.getLong("id"), rs.getString("lot_number"), rs.getBigDecimal("capacity_ml"),
            rs.getBigDecimal("remaining_ml"), rs.getTimestamp("supplier_expiry_utc").toInstant(),
            toInstant(rs.getTimestamp("opened_at_utc")), toInstant(rs.getTimestamp("effective_deadline_utc")),
            rs.getString("deadline_source"), rs.getBoolean("quarantined"),
            rs.getString("quarantine_reason"), toInstant(rs.getTimestamp("quarantined_at_utc")));

    private static final RowMapper<DispenseRow> DISPENSE_MAPPER = (rs, n) -> new DispenseRow(
            rs.getLong("id"), rs.getLong("bottle_id"), rs.getString("request_id"),
            rs.getBigDecimal("amount_ml"), rs.getBigDecimal("remaining_after_ml"),
            rs.getString("deadline_source"), rs.getTimestamp("dispensed_at_utc").toInstant());

    private static final RowMapper<EventRow> EVENT_MAPPER = (rs, n) -> new EventRow(
            rs.getLong("id"), rs.getLong("bottle_id"), rs.getString("event_type"),
            rs.getTimestamp("occurred_at_utc").toInstant(), rs.getString("detail"));

    private final JdbcTemplate jdbc;

    public ReagentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public long insertBottle(String lotNumber, BigDecimal capacityMl, Instant supplierExpiryUtc, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO reagent_bottle (lot_number, capacity_ml, remaining_ml, supplier_expiry_utc,"
                            + " created_at_utc) VALUES (?, ?, ?, ?, ?)",
                    new String[] {"id"});
            ps.setString(1, lotNumber);
            ps.setBigDecimal(2, capacityMl);
            ps.setBigDecimal(3, capacityMl);
            ps.setTimestamp(4, Timestamp.from(supplierExpiryUtc));
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<BottleRow> findBottle(long id) {
        var rows = jdbc.query("SELECT " + BOTTLE_COLS + " FROM reagent_bottle WHERE id = ?", BOTTLE_MAPPER, id);
        return rows.stream().findFirst();
    }

    public Optional<BottleRow> findBottleForUpdate(long id) {
        var rows = jdbc.query("SELECT " + BOTTLE_COLS + " FROM reagent_bottle WHERE id = ? FOR UPDATE",
                BOTTLE_MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<BottleRow> findBottlesByLotForUpdate(String lotNumber) {
        return jdbc.query("SELECT " + BOTTLE_COLS + " FROM reagent_bottle WHERE lot_number = ? FOR UPDATE",
                BOTTLE_MAPPER, lotNumber);
    }

    public void markOpened(long id, Instant openedAt, Instant effectiveDeadline, String source) {
        jdbc.update("UPDATE reagent_bottle SET opened_at_utc = ?, effective_deadline_utc = ?,"
                        + " deadline_source = ? WHERE id = ?",
                Timestamp.from(openedAt), Timestamp.from(effectiveDeadline), source, id);
    }

    public void updateRemaining(long id, BigDecimal remainingMl) {
        jdbc.update("UPDATE reagent_bottle SET remaining_ml = ? WHERE id = ?", remainingMl, id);
    }

    public void markQuarantined(long id, String reason, Instant quarantinedAt) {
        jdbc.update("UPDATE reagent_bottle SET quarantined = 1, quarantine_reason = ?,"
                        + " quarantined_at_utc = ? WHERE id = ?",
                reason, Timestamp.from(quarantinedAt), id);
    }

    public long insertDispense(long bottleId, String requestId, BigDecimal amountMl,
            BigDecimal remainingAfterMl, String deadlineSource, Instant dispensedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO dispense_record (bottle_id, request_id, amount_ml, remaining_after_ml,"
                            + " deadline_source, dispensed_at_utc) VALUES (?, ?, ?, ?, ?, ?)",
                    new String[] {"id"});
            ps.setLong(1, bottleId);
            ps.setString(2, requestId);
            ps.setBigDecimal(3, amountMl);
            ps.setBigDecimal(4, remainingAfterMl);
            ps.setString(5, deadlineSource);
            ps.setTimestamp(6, Timestamp.from(dispensedAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<DispenseRow> findDispenseByRequestId(String requestId) {
        var rows = jdbc.query("SELECT id, bottle_id, request_id, amount_ml, remaining_after_ml,"
                + " deadline_source, dispensed_at_utc FROM dispense_record WHERE request_id = ?",
                DISPENSE_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    public List<DispenseRow> findDispensesByBottle(long bottleId) {
        return jdbc.query("SELECT id, bottle_id, request_id, amount_ml, remaining_after_ml,"
                + " deadline_source, dispensed_at_utc FROM dispense_record WHERE bottle_id = ? ORDER BY id",
                DISPENSE_MAPPER, bottleId);
    }

    public void insertEvent(long bottleId, String eventType, Instant occurredAt, String detail) {
        jdbc.update("INSERT INTO bottle_event (bottle_id, event_type, occurred_at_utc, detail)"
                        + " VALUES (?, ?, ?, ?)",
                bottleId, eventType, Timestamp.from(occurredAt), detail);
    }

    public List<EventRow> findEventsByBottle(long bottleId) {
        return jdbc.query("SELECT id, bottle_id, event_type, occurred_at_utc, detail"
                        + " FROM bottle_event WHERE bottle_id = ? ORDER BY id",
                EVENT_MAPPER, bottleId);
    }
}