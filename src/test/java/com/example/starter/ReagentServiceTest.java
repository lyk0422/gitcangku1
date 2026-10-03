package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.domain.DeadlineBasis;
import com.example.starter.domain.ReagentBottle;
import com.example.starter.service.BizException;
import com.example.starter.service.ReagentService;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 试剂瓶业务真实 H2 测试：成功主流程、参数与状态错误、到期边界（左闭右开）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class ReagentServiceTest {

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

    private ReagentBottle receiveOpened(String batch, String capacity, Instant supplierExpiry) {
        ReagentBottle bottle = service.receive(batch, new BigDecimal(capacity), supplierExpiry);
        return service.open(bottle.id());
    }

    @Test
    void receiveOpenDispenseFlow() {
        Instant supplierExpiry = TestClockConfig.T0.plus(100, ChronoUnit.DAYS);
        ReagentBottle bottle = receiveOpened("B1", "10.0", supplierExpiry);

        assertEquals(new BigDecimal("10.0"), bottle.remainingMl());
        assertEquals(TestClockConfig.T0, bottle.openedAt());
        assertEquals(TestClockConfig.T0.plus(30, ChronoUnit.DAYS), bottle.effectiveDeadline());
        assertEquals(DeadlineBasis.OPEN_EXPIRY, bottle.deadlineBasis());

        var outcome = service.dispense(bottle.id(), "r1", new BigDecimal("6.0"));
        assertFalse(outcome.replayed());
        assertEquals(new BigDecimal("4.0"), outcome.record().remainingAfterMl());
        assertEquals(DeadlineBasis.OPEN_EXPIRY, outcome.record().deadlineBasis());

        var detail = service.detail(bottle.id());
        assertEquals(new BigDecimal("4.0"), detail.bottle().remainingMl());
        assertEquals(1, detail.dispenses().size());
        assertEquals("r1", detail.dispenses().get(0).requestId());
    }

    @Test
    void reopenKeepsFirstOpenedAt() {
        ReagentBottle bottle = receiveOpened("B1", "10.0",
                TestClockConfig.T0.plus(100, ChronoUnit.DAYS));
        clock.setInstant(TestClockConfig.T0.plus(5, ChronoUnit.DAYS));
        ReagentBottle reopened = service.open(bottle.id());
        assertEquals(TestClockConfig.T0, reopened.openedAt());
    }

    @Test
    void dispenseRejectsUnopenedAndFailedRequestDoesNotHoldKey() {
        ReagentBottle bottle = service.receive("B1", new BigDecimal("10.0"),
                TestClockConfig.T0.plus(100, ChronoUnit.DAYS));

        BizException ex = assertThrows(BizException.class,
                () -> service.dispense(bottle.id(), "r-open", new BigDecimal("1.0")));
        assertEquals("BOTTLE_UNOPENED", ex.code());
        assertEquals(0, countRows("dispense_record"));

        service.open(bottle.id());
        var outcome = service.dispense(bottle.id(), "r-open", new BigDecimal("1.0"));
        assertFalse(outcome.replayed());
        assertEquals(new BigDecimal("9.0"), outcome.record().remainingAfterMl());
    }

    @Test
    void invalidAmountsRejectedWithoutWrites() {
        ReagentBottle bottle = receiveOpened("B1", "10.0",
                TestClockConfig.T0.plus(100, ChronoUnit.DAYS));
        for (String bad : new String[] {"0", "-1.0", "0.15", "10.01"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> service.dispense(bottle.id(), "r-" + bad, new BigDecimal(bad)));
            assertEquals("INVALID_AMOUNT", ex.code());
        }
        assertEquals(0, countRows("dispense_record"));
        assertEquals(new BigDecimal("10.0"), service.detail(bottle.id()).bottle().remainingMl());
    }

    @Test
    void overRemainingRejected() {
        ReagentBottle bottle = receiveOpened("B1", "10.0",
                TestClockConfig.T0.plus(100, ChronoUnit.DAYS));
        BizException ex = assertThrows(BizException.class,
                () -> service.dispense(bottle.id(), "r-big", new BigDecimal("10.1")));
        assertEquals("INSUFFICIENT_REMAINING", ex.code());
        assertEquals(0, countRows("dispense_record"));
    }

    @Test
    void supplierExpiryBoundaryIsLeftClosedRightOpen() {
        Instant supplierExpiry = TestClockConfig.T0.plus(10, ChronoUnit.DAYS);
        ReagentBottle bottle = receiveOpened("B1", "10.0", supplierExpiry);
        assertEquals(DeadlineBasis.SUPPLIER_EXPIRY, bottle.deadlineBasis());

        clock.setInstant(supplierExpiry.minus(1, ChronoUnit.SECONDS));
        var ok = service.dispense(bottle.id(), "r-before", new BigDecimal("1.0"));
        assertEquals(DeadlineBasis.SUPPLIER_EXPIRY, ok.record().deadlineBasis());

        clock.setInstant(supplierExpiry);
        BizException ex = assertThrows(BizException.class,
                () -> service.dispense(bottle.id(), "r-at", new BigDecimal("1.0")));
        assertEquals("BOTTLE_EXPIRED", ex.code());
        assertEquals(1, countRows("dispense_record"));
    }

    @Test
    void openExpiryBoundaryIsLeftClosedRightOpen() {
        Instant supplierExpiry = TestClockConfig.T0.plus(100, ChronoUnit.DAYS);
        ReagentBottle bottle = receiveOpened("B1", "10.0", supplierExpiry);
        Instant openDeadline = TestClockConfig.T0.plus(30, ChronoUnit.DAYS);

        clock.setInstant(openDeadline.minus(1, ChronoUnit.SECONDS));
        service.dispense(bottle.id(), "r-before", new BigDecimal("1.0"));

        clock.setInstant(openDeadline);
        BizException ex = assertThrows(BizException.class,
                () -> service.dispense(bottle.id(), "r-at", new BigDecimal("1.0")));
        assertEquals("BOTTLE_EXPIRED", ex.code());
        assertEquals(1, countRows("dispense_record"));
    }

    private int countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}