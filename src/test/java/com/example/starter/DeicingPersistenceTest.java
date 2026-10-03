package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实H2库验证：唯一约束、外键约束与事务回滚。 */
@SpringBootTest
@Import(TestClockConfig.class)
@Sql(scripts = "/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class DeicingPersistenceTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private static Timestamp ts(String iso) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(Instant.parse(iso), ZoneOffset.UTC));
    }

    private void insertSlot(String requestId) {
        jdbc.update("INSERT INTO deicing_slot (vehicle_id, flight_id, slot_start, slot_end, status,"
                + " request_id, request_hash, created_at) VALUES (1, 1, ?, ?, 'BOOKED', ?, 'h', ?)",
                ts("2026-01-01T10:00:00Z"), ts("2026-01-01T10:30:00Z"), requestId,
                ts("2026-01-01T09:00:00Z"));
    }

    @Test
    void duplicateRequestIdViolatesUniqueConstraint() {
        insertSlot("uniq-1");
        assertThrows(DuplicateKeyException.class, () -> insertSlot("uniq-1"));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM deicing_slot", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void foreignKeyRejectsUnknownFlight() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO deicing_slot (vehicle_id, flight_id, slot_start, slot_end, status,"
                + " request_id, request_hash, created_at) VALUES (1, 999, ?, ?, 'BOOKED', 'fk-1', 'h', ?)",
                ts("2026-01-01T10:00:00Z"), ts("2026-01-01T10:30:00Z"), ts("2026-01-01T09:00:00Z")));
    }

    @Test
    void completionSlotIdIsUnique() {
        insertSlot("cmp-1");
        jdbc.update("INSERT INTO deicing_completion (slot_id, fluid_type, completed_at, deadline_at)"
                + " VALUES (1, 'A', ?, ?)", ts("2026-01-01T10:00:00Z"), ts("2026-01-01T10:20:00Z"));
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "INSERT INTO deicing_completion (slot_id, fluid_type, completed_at, deadline_at)"
                + " VALUES (1, 'B', ?, ?)", ts("2026-01-01T10:01:00Z"), ts("2026-01-01T10:36:00Z")));
    }

    @Test
    void rollbackLeavesNoPartialWrites() {
        assertThrows(IllegalStateException.class, () -> transactionTemplate.execute(status -> {
            insertSlot("rb-1");
            jdbc.update("INSERT INTO takeoff_clearance (flight_id, completion_id, request_id,"
                    + " request_hash, status, reason, confirmed_at) VALUES (1, NULL, 'rb-tk', 'h',"
                    + " 'REJECTED', '除冰作业未完成', ?)", ts("2026-01-01T10:00:00Z"));
            throw new IllegalStateException("强制回滚");
        }));
        Integer slots = jdbc.queryForObject("SELECT COUNT(*) FROM deicing_slot", Integer.class);
        Integer clearances = jdbc.queryForObject("SELECT COUNT(*) FROM takeoff_clearance",
                Integer.class);
        assertEquals(0, slots, "回滚后不应残留时隙");
        assertEquals(0, clearances, "回滚后不应残留确认记录");
    }
}