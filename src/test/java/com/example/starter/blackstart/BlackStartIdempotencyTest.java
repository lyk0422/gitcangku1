package com.example.starter.blackstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 幂等与终止语义测试：requestId 同参重放、异参 409、终止后只读、重启须新建计划。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
class BlackStartIdempotencyTest {

    private static final Instant T0 = Instant.parse("2026-10-04T09:00:00Z");

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

    private ConfirmStepRequest ok(String requestId) {
        return new ConfirmStepRequest(requestId, "NORMAL", 400.0, 50.0, null);
    }

    @Test
    void sameRequestIdSameParamsReplaysFirstResult() {
        PlanView plan = service.createPlan();
        ConfirmStepResponse first = service.confirmStep(plan.planId(), 1, ok("idem-1"));
        ConfirmStepResponse replay = service.confirmStep(plan.planId(), 1, ok("idem-1"));

        assertEquals("SUCCESS", replay.outcome());
        assertEquals(first.recordedAt(), replay.recordedAt());
        Integer attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM black_start_step_attempt WHERE plan_id = ?",
                Integer.class, plan.planId());
        assertEquals(1, attempts);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() {
        PlanView plan = service.createPlan();
        service.confirmStep(plan.planId(), 1, ok("idem-2"));

        BlackStartException ex = assertThrows(BlackStartException.class,
                () -> service.confirmStep(plan.planId(), 1,
                        new ConfirmStepRequest("idem-2", "NORMAL", 401.0, 50.0, null)));
        assertEquals(HttpStatus.CONFLICT, ex.httpStatus());
    }

    @Test
    void rejectedAttemptAlsoReplaysByRequestId() {
        PlanView plan = service.createPlan();
        ConfirmStepRequest bad = new ConfirmStepRequest("idem-3", "NORMAL", 300.0, 50.0, null);
        ConfirmStepResponse first = service.confirmStep(plan.planId(), 1, bad);
        ConfirmStepResponse replay = service.confirmStep(plan.planId(), 1, bad);

        assertEquals("REJECTED", first.outcome());
        assertEquals("REJECTED", replay.outcome());
        assertEquals(first.rejectReason(), replay.rejectReason());
        Integer attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM black_start_step_attempt WHERE plan_id = ?",
                Integer.class, plan.planId());
        assertEquals(1, attempts);
    }

    @Test
    void terminatedPlanIsReadOnlyAndRestartNeedsNewPlan() {
        PlanView plan = service.createPlan();
        service.confirmStep(plan.planId(), 1, ok("term-1"));

        clock.setInstant(T0.plusSeconds(30));
        PlanView terminated = service.terminate(plan.planId(), new TerminateRequest("值班员手动终止"));
        assertEquals("TERMINATED", terminated.status());
        assertEquals(T0.plusSeconds(30), terminated.terminatedAt());
        assertEquals("值班员手动终止", terminated.terminationReason());

        BlackStartException ex = assertThrows(BlackStartException.class,
                () -> service.confirmStep(plan.planId(), 2, ok("term-2")));
        assertEquals(HttpStatus.CONFLICT, ex.httpStatus());

        PlanView again = service.terminate(plan.planId(), new TerminateRequest("重复终止"));
        assertEquals("TERMINATED", again.status());
        assertEquals(T0.plusSeconds(30), again.terminatedAt());

        PlanView newPlan = service.createPlan();
        assertEquals("IN_PROGRESS", newPlan.status());
        PlanView history = service.getPlan(plan.planId());
        assertEquals(1, history.steps().get(0).attempts().size());
        assertTrue(history.steps().get(0).succeeded());
    }

    @Test
    void startedPlanCannotBeTerminated() {
        PlanView plan = service.createPlan();
        service.confirmStep(plan.planId(), 1, ok("fin-1"));
        service.confirmStep(plan.planId(), 2, ok("fin-2"));
        service.confirmStep(plan.planId(), 3,
                new ConfirmStepRequest("fin-3", "NORMAL", 400.0, 50.0, 100.0));

        BlackStartException ex = assertThrows(BlackStartException.class,
                () -> service.terminate(plan.planId(), new TerminateRequest("太迟了")));
        assertEquals(HttpStatus.CONFLICT, ex.httpStatus());
        assertNotNull(service.getPlan(plan.planId()).completedAt());
    }
}