package com.example.starter.blackstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 黑启动服务测试：合成场景、读数拒绝、跳步、幂等重放与终止，全部在真实 H2（MODE=MySQL）上执行。
 */
@SpringBootTest
class BlackStartServiceTest {

    @Autowired
    private BlackStartService service;

    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-02-01T00:00:00Z"));

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM step_attempt");
        jdbcTemplate.update("DELETE FROM black_start_plan");
        now.set(Instant.parse("2026-02-01T00:00:00Z"));
        when(clock.instant()).thenAnswer(invocation -> now.get());
    }

    private BlackStartService.ConfirmOutcome confirm(long planId, int stepNo, String requestId,
            String voltage, String frequency, String powerKw, String deviceStatus) {
        return service.confirmStep(planId, stepNo, new ConfirmStepRequest(
                requestId, new BigDecimal(voltage), new BigDecimal(frequency),
                powerKw == null ? null : new BigDecimal(powerKw), deviceStatus));
    }

    @Test
    void syntheticScenarioStopsAtStep2AndCompletesAfterQualifiedRetry() {
        PlanView plan = service.createPlan("合成黑启动计划-测试");
        long planId = plan.planId();
        assertEquals(1, plan.currentStep());

        BlackStartService.ConfirmOutcome step1 = confirm(planId, 1, "s1", "400", "50.0", null, "NORMAL");
        assertEquals(HttpStatus.OK, step1.httpStatus());
        assertEquals(2, step1.response().currentStep());
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), step1.response().recordedAt());

        now.set(Instant.parse("2026-02-01T00:01:00Z"));
        BlackStartService.ConfirmOutcome badFreq = confirm(planId, 2, "s2", "400", "50.6", null, "NORMAL");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, badFreq.httpStatus());
        assertEquals("REJECTED", badFreq.response().outcome());
        assertTrue(badFreq.response().rejectReason().contains("频率"));
        assertEquals(2, badFreq.response().currentStep());
        assertEquals("IN_PROGRESS", service.getPlan(planId).status());

        BlackStartService.ConfirmOutcome replay = confirm(planId, 2, "s2", "400", "50.6", null, "NORMAL");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, replay.httpStatus());
        assertEquals(badFreq.response().recordedAt(), replay.response().recordedAt());
        assertEquals(2, attemptRepository.findByPlanId(planId).size());

        ApiException conflict = assertThrows(ApiException.class,
                () -> confirm(planId, 2, "s2", "400", "50.4", null, "NORMAL"));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals("REQUEST_ID_CONFLICT", conflict.code());

        BlackStartService.ConfirmOutcome step2 = confirm(planId, 2, "s3", "405", "50.1", null, "NORMAL");
        assertEquals(HttpStatus.OK, step2.httpStatus());
        assertEquals(3, step2.response().currentStep());

        BlackStartService.ConfirmOutcome overpower = confirm(planId, 3, "s4", "400", "50.0", "250", "NORMAL");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, overpower.httpStatus());
        assertTrue(overpower.response().rejectReason().contains("功率"));
        assertEquals(3, overpower.response().currentStep());

        now.set(Instant.parse("2026-02-01T00:05:00Z"));
        BlackStartService.ConfirmOutcome step3 = confirm(planId, 3, "s5", "400", "50.0", "150", "NORMAL");
        assertEquals(HttpStatus.OK, step3.httpStatus());
        assertEquals("STARTED", step3.response().planStatus());

        ApiException locked = assertThrows(ApiException.class,
                () -> confirm(planId, 3, "s6", "400", "50.0", "100", "NORMAL"));
        assertEquals(HttpStatus.CONFLICT, locked.status());
        assertEquals("PLAN_ALREADY_STARTED", locked.code());

        PlanView view = service.getPlan(planId);
        assertEquals("STARTED", view.status());
        assertEquals(Instant.parse("2026-02-01T00:05:00Z"), view.completedAt());
        assertNull(view.terminatedAt());
        assertEquals(5, view.steps().size());
        assertEquals(2, view.steps().stream().filter(s -> "REJECTED".equals(s.outcome())).count());
    }

    @Test
    void rejectsOutOfRangeVoltageAndDeviceStatusRegression() {
        long planId = service.createPlan("读数校验").planId();

        BlackStartService.ConfirmOutcome highVoltage = confirm(planId, 1, "v1", "430", "50.0", null, "NORMAL");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, highVoltage.httpStatus());
        assertTrue(highVoltage.response().rejectReason().contains("电压"));

        BlackStartService.ConfirmOutcome lowVoltage = confirm(planId, 1, "v2", "370", "50.0", null, "NORMAL");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, lowVoltage.httpStatus());

        BlackStartService.ConfirmOutcome badDevice = confirm(planId, 1, "v3", "400", "50.0", null, "FAULT");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, badDevice.httpStatus());
        assertTrue(badDevice.response().rejectReason().contains("设备状态"));

        PlanView view = service.getPlan(planId);
        assertEquals(1, view.currentStep());
        assertEquals(3, view.steps().size());
        assertTrue(view.steps().stream().allMatch(s -> "REJECTED".equals(s.outcome())));
    }

    @Test
    void rejectsSkipStepAndKeepsFailureHistory() {
        long planId = service.createPlan("跳步").planId();

        BlackStartService.ConfirmOutcome skipped = confirm(planId, 2, "k1", "400", "50.0", null, "NORMAL");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, skipped.httpStatus());
        assertTrue(skipped.response().rejectReason().contains("跳步"));
        assertEquals(1, service.getPlan(planId).currentStep());

        BlackStartService.ConfirmOutcome step1 = confirm(planId, 1, "k2", "400", "50.0", null, "NORMAL");
        assertEquals(HttpStatus.OK, step1.httpStatus());
        PlanView view = service.getPlan(planId);
        assertEquals(2, view.steps().size());
        assertEquals("REJECTED", view.steps().get(0).outcome());
    }

    @Test
    void terminateLocksPlanAndPreservesHistory() {
        long planId = service.createPlan("终止").planId();
        confirm(planId, 1, "t1", "400", "50.0", null, "NORMAL");

        now.set(Instant.parse("2026-02-01T01:00:00Z"));
        PlanView terminated = service.terminatePlan(planId, "值班员中止");
        assertEquals("TERMINATED", terminated.status());
        assertEquals(Instant.parse("2026-02-01T01:00:00Z"), terminated.terminatedAt());
        assertEquals("值班员中止", terminated.terminationReason());

        ApiException readOnly = assertThrows(ApiException.class,
                () -> confirm(planId, 2, "t2", "400", "50.0", null, "NORMAL"));
        assertEquals(HttpStatus.CONFLICT, readOnly.status());
        assertEquals("PLAN_TERMINATED", readOnly.code());

        PlanView again = service.terminatePlan(planId, "重复终止");
        assertEquals("TERMINATED", again.status());
        assertEquals("值班员中止", again.terminationReason());

        PlanView view = service.getPlan(planId);
        assertEquals(1, view.steps().size());
        assertEquals("SUCCESS", view.steps().get(0).outcome());

        PlanView restarted = service.createPlan("重新启动");
        assertEquals("IN_PROGRESS", restarted.status());
        assertEquals(1, restarted.currentStep());
    }
}