package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.domain.BottleStatus;
import com.example.starter.domain.ReagentBottle;
import com.example.starter.service.BizException;
import com.example.starter.service.ReagentService;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 幂等与批号召回测试：同参重放、异参 409、失败不占键、召回隔离与历史保留。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class ReagentIdempotencyRecallTest {

    @Autowired
    private ReagentService service;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM dispense_record");
        jdbc.update("DELETE FROM recall_record");
        jdbc.update("DELETE FROM reagent_bottle");
        clock.setInstant(TestClockConfig.T0);
    }

    private ReagentBottle receiveOpened(String batch, String capacity) {
        ReagentBottle bottle = service.receive(batch, new BigDecimal(capacity),
                TestClockConfig.T0.plus(100, ChronoUnit.DAYS));
        return service.open(bottle.id());
    }

    @Test
    void sameParamsReplayReturnsFirstRecord() {
        ReagentBottle bottle = receiveOpened("B1", "10.0");
        var first = service.dispense(bottle.id(), "r1", new BigDecimal("1.0"));
        var replay = service.dispense(bottle.id(), "r1", new BigDecimal("1.0"));

        assertTrue(replay.replayed());
        assertEquals(first.record().id(), replay.record().id());
        assertEquals(first.record().remainingAfterMl(), replay.record().remainingAfterMl());
        assertEquals(1, countRows("dispense_record"));
        assertEquals(new BigDecimal("9.0"), service.detail(bottle.id()).bottle().remainingMl());
    }

    @Test
    void differentParamsSameRequestIdReturns409() {
        ReagentBottle bottle = receiveOpened("B1", "10.0");
        service.dispense(bottle.id(), "r1", new BigDecimal("1.0"));

        BizException ex = assertThrows(BizException.class,
                () -> service.dispense(bottle.id(), "r1", new BigDecimal("2.0")));
        assertEquals("IDEMPOTENCY_CONFLICT", ex.code());
        assertEquals(409, ex.status().value());

        ReagentBottle other = receiveOpened("B2", "10.0");
        BizException crossBottle = assertThrows(BizException.class,
                () -> service.dispense(other.id(), "r1", new BigDecimal("1.0")));
        assertEquals("IDEMPOTENCY_CONFLICT", crossBottle.code());
        assertEquals(1, countRows("dispense_record"));
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() {
        ReagentBottle bottle = receiveOpened("B1", "10.0");
        assertThrows(BizException.class,
                () -> service.dispense(bottle.id(), "k1", new BigDecimal("50.0")));
        assertEquals(0, countRows("dispense_record"));

        var outcome = service.dispense(bottle.id(), "k1", new BigDecimal("1.0"));
        assertFalse(outcome.replayed());
        assertEquals(new BigDecimal("9.0"), outcome.record().remainingAfterMl());
    }

    @Test
    void recallQuarantinesBottlesWithRemainingAndKeepsHistory() {
        ReagentBottle used = receiveOpened("B2", "10.0");
        service.dispense(used.id(), "r-used", new BigDecimal("6.0"));
        ReagentBottle full = receiveOpened("B2", "10.0");
        ReagentBottle empty = receiveOpened("B2", "10.0");
        service.dispense(empty.id(), "r-empty", new BigDecimal("10.0"));
        ReagentBottle otherBatch = receiveOpened("B3", "10.0");

        var recall = service.recall("B2", "供应商通知批次污染");
        assertEquals(List.of(used.id(), full.id()),
                recall.quarantinedBottleIds().stream().sorted().toList());
        assertEquals(1, countRows("recall_record"));

        var usedDetail = service.detail(used.id());
        assertEquals(BottleStatus.QUARANTINED, usedDetail.bottle().status());
        assertEquals("供应商通知批次污染", usedDetail.bottle().quarantineReason());
        assertEquals(1, usedDetail.dispenses().size());
        assertEquals(new BigDecimal("4.0"), usedDetail.bottle().remainingMl());

        assertEquals(BottleStatus.QUARANTINED, service.detail(full.id()).bottle().status());
        assertEquals(BottleStatus.ACTIVE, service.detail(empty.id()).bottle().status());
        assertEquals(BottleStatus.ACTIVE, service.detail(otherBatch.id()).bottle().status());

        BizException ex = assertThrows(BizException.class,
                () -> service.dispense(used.id(), "r-after-recall", new BigDecimal("1.0")));
        assertEquals("BOTTLE_QUARANTINED", ex.code());
        assertEquals(2, countRows("dispense_record"));
    }

    private int countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}