package com.example.starter.blackstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 黑启动主流程与失败分支测试，使用真实 H2（MODE=MySQL）与可控时钟。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
class BlackStartServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-04T08:00:00Z");

    @Autowired
    private BlackStartService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM black_start_step_success");
        jdbc.update("DELETE FROM black_start_step_attempt");
        jdbc.update("DELETE FROM black_start_plan");
        clock.setInstant(T0);
    }

    private PlanView newPlan() {
        return service.createPlan();
    }

    private ConfirmStepRequest ok(String requestId, Double powerKw) {
        return new ConfirmStepRequest(requestId, "NORMAL", 400.0, 50.0, powerKw);
    }

    @Test
    void fullSuccessFlowMarksPlanStarted() {
        PlanView plan = newPlan();
        assertEquals("IN_PROGRESS", plan.status());
        assertEquals(1, plan.currentStep());
        assertEquals(T0, plan.createdAt());

        ConfirmStepResponse r1 = service.confirmStep(plan.planId(), 1, ok("s1", null));
        assertEquals("SUCCESS", r1.outcome());
        assertEquals(2, r1.currentStep());
        assertEquals(T0, r1.recordedAt());

        clock.setInstant(T0.plusSeconds(60));
        service.confirmStep(plan.planId(), 2, ok("s2", null));
        clock.setInstant(T0.plusSeconds(120));
        ConfirmStepResponse r3 = service.confirmStep(plan.planId(), 3, ok("s3", 150.0));
        assertEquals("STARTED", r3.planStatus());

        PlanView view = service.getPlan(plan.planId());
        assertEquals("STARTED", view.status());
        assertEquals(T0.plusSeconds(120), view.completedAt());
        assertNull(view.terminatedAt());
        assertTrue(view.steps().get(0).succeeded());
        assertTrue(view.steps().get(1).succeeded());
        assertTrue(view.steps().get(2).succeeded());
        assertEquals(150.0, view.steps().get(2).attempts().get(0).powerKw());

        BlackStartException ex = assertThrows(BlackStartException.class,
                () -> service.confirmStep(plan.planId(), 3, ok("s4", 100.0)));
        assertEquals(HttpStatus.CONFLICT, ex.httpStatus());
    }

    @Test
    void outOfRangeReadingsRejectedAndStepNotAdvanced() {
        PlanView plan = newPlan();

        ConfirmStepResponse badVoltage = service.confirmStep(plan.planId(), 1,
                new ConfirmStepRequest("v1", "NORMAL", 421.0, 50.0, null));
        assertEquals("REJECTED", badVoltage.outcome());
        assertTrue(badVoltage.rejectReason().contains("电压"));
        assertEquals(1, badVoltage.currentStep());

        ConfirmStepResponse badFreq = service.confirmStep(plan.planId(), 1,
                new ConfirmStepRequest("v2", "NORMAL", 400.0, 49.4, null));
        assertEquals("REJECTED", badFreq.outcome());
        assertTrue(badFreq.rejectReason().contains("频率"));

        ConfirmStepResponse badDevice = service.confirmStep(plan.planId(), 1,
                new ConfirmStepRequest("v3", "FAULT", 400.0, 50.0, null));
        assertEquals("REJECTED", badDevice.outcome());
        assertTrue(badDevice.rejectReason().contains("设备状态"));

        assertEquals(1, service.getPlan(plan.planId()).currentStep());
        assertEquals(3, service.getPlan(plan.planId()).steps().get(0).attempts().size());

        ConfirmStepResponse good = service.confirmStep(plan.planId(), 1, ok("v4", null));
        assertEquals("SUCCESS", good.outcome());
        assertEquals(2, good.currentStep());
    }

    @Test
    void stepSkippingIsRejectedAndRecorded() {
        PlanView plan = newPlan();
        ConfirmStepResponse skipped = service.confirmStep(plan.planId(), 2, ok("skip1", null));
        assertEquals("REJECTED", skipped.outcome());
        assertTrue(skipped.rejectReason().contains("跳步"));
        assertEquals(1, service.getPlan(plan.planId()).currentStep());
    }

    @Test
    void loadPowerAbovePlanLimitRejected() {
        PlanView plan = newPlan();
        service.confirmStep(plan.planId(), 1, ok("p1", null));
        service.confirmStep(plan.planId(), 2, ok("p2", null));

        ConfirmStepResponse tooMuch = service.confirmStep(plan.planId(), 3, ok("p3", 250.0));
        assertEquals("REJECTED", tooMuch.outcome());
        assertTrue(tooMuch.rejectReason().contains("200"));
        assertEquals("IN_PROGRESS", service.getPlan(plan.planId()).status());

        ConfirmStepResponse atLimit = service.confirmStep(plan.planId(), 3, ok("p4", 200.0));
        assertEquals("SUCCESS", atLimit.outcome());
        assertEquals("STARTED", service.getPlan(plan.planId()).status());
    }

    @Test
    void syntheticPlanStopsAtStep2UntilQualifiedRetry() {
        PlanView plan = newPlan();
        service.confirmStep(plan.planId(), 1, ok("t1", null));

        ConfirmStepResponse badFreq = service.confirmStep(plan.planId(), 2,
                new ConfirmStepRequest("t2", "NORMAL", 400.0, 50.6, null));
        assertEquals("REJECTED", badFreq.outcome());
        assertTrue(badFreq.rejectReason().contains("50.6"));
        PlanView stuck = service.getPlan(plan.planId());
        assertEquals(2, stuck.currentStep());
        assertEquals("IN_PROGRESS", stuck.status());

        ConfirmStepResponse retry = service.confirmStep(plan.planId(), 2,
                new ConfirmStepRequest("t3", "NORMAL", 400.0, 50.0, null));
        assertEquals("SUCCESS", retry.outcome());

        ConfirmStepResponse step3 = service.confirmStep(plan.planId(), 3, ok("t4", 120.0));
        assertEquals("SUCCESS", step3.outcome());
        PlanView done = service.getPlan(plan.planId());
        assertEquals("STARTED", done.status());
        assertNotNull(done.completedAt());
        assertEquals(2, done.steps().get(1).attempts().size());
    }
}