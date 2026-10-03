package com.example.starter.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.plan.PlanDtos.CommandView;
import com.example.starter.plan.PlanDtos.ConfirmRequest;
import com.example.starter.plan.PlanDtos.CreatePlanRequest;
import com.example.starter.plan.PlanDtos.ExecuteRequest;
import com.example.starter.plan.PlanDtos.IssueRequest;
import com.example.starter.plan.PlanDtos.PersonEntryRequest;
import com.example.starter.plan.PlanDtos.PlanView;
import com.example.starter.plan.PlanDtos.ReportView;
import com.example.starter.plan.PlanRepository.CommandRow;
import com.example.starter.plan.PlanRepository.ReportRow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 基于真实 H2（MODE=MySQL）的服务层集成测试：成功、失败、过期、幂等与并发场景。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfiguration.class)
class PlanServiceTest {

    private static final List<String> CHECKPOINTS = List.of("CP-NORTH", "CP-EAST", "CP-GATE");

    @Autowired
    private PlanService service;

    @Autowired
    private PlanRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clean();
        clock.setInstant(TestClockConfiguration.T0);
    }

    private void clean() {
        jdbc.update("DELETE FROM execution_report");
        jdbc.update("DELETE FROM blast_command");
        jdbc.update("DELETE FROM checkpoint_confirmation");
        jdbc.update("DELETE FROM clearance_round");
        jdbc.update("DELETE FROM person_entry_event");
        jdbc.update("DELETE FROM plan_checkpoint");
        jdbc.update("DELETE FROM blast_plan");
    }

    private long newPlan() {
        return service.createPlan(new CreatePlanRequest("PLAN-1", "采石场A区：半径300米警戒区", CHECKPOINTS))
                .planId();
    }

    private void confirmAll(long planId) {
        for (String cp : CHECKPOINTS) {
            service.confirmCheckpoint(planId, cp, new ConfirmRequest("安全员李四", 0, null));
        }
    }

    private CommandView issue(long planId, String requestId) {
        return service.issueCommand(planId, new IssueRequest(requestId, "班长张三"));
    }

    @Test
    void issueRejectedUntilAllCheckpointsConfirmedAndFailureDoesNotConsumeRequestId() {
        long planId = newPlan();
        service.confirmCheckpoint(planId, "CP-NORTH", new ConfirmRequest("安全员李四", 0, null));
        service.confirmCheckpoint(planId, "CP-EAST", new ConfirmRequest("安全员王五", 0, null));
        ApiException ex = assertThrows(ApiException.class, () -> issue(planId, "REQ-1"));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertTrue(repository.findCommands(planId).isEmpty());
        service.confirmCheckpoint(planId, "CP-GATE", new ConfirmRequest("安全员赵六", 0, null));
        CommandView cmd = issue(planId, "REQ-1");
        assertEquals("ISSUED", cmd.status());
        assertEquals(cmd.issuedAt().plus(Duration.ofMinutes(5)), cmd.expiresAt());
        PlanView view = service.getPlan(planId);
        assertEquals(1, view.rounds().size());
        assertEquals("COMPLETED", view.rounds().get(0).status());
        assertEquals(3, view.rounds().get(0).confirmations().size());
    }

    @Test
    void issueRejectedWhenConfirmationOlderThan15Minutes() {
        long planId = newPlan();
        confirmAll(planId);
        clock.advance(Duration.ofMinutes(16));
        ApiException ex = assertThrows(ApiException.class, () -> issue(planId, "REQ-1"));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertTrue(ex.getMessage().contains("15"));
        assertTrue(repository.findCommands(planId).isEmpty());
    }

    @Test
    void executionRejectedWhenCommandExpired() {
        long planId = newPlan();
        confirmAll(planId);
        CommandView cmd = issue(planId, "REQ-1");
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.reportExecution(planId, cmd.commandId(), new ExecuteRequest("EX-1", "爆破员孙七")));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertTrue(repository.findReports(planId).isEmpty());
    }

    @Test
    void personEntryAtMinute4InvalidatesCommandAndNewRoundCannotReuseOldConfirmations() {
        long planId = newPlan();
        confirmAll(planId);
        CommandView cmd = issue(planId, "REQ-1");
        clock.advance(Duration.ofMinutes(4));
        service.recordPersonEntry(planId, new PersonEntryRequest("警戒员", null));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.reportExecution(planId, cmd.commandId(), new ExecuteRequest("EX-1", "爆破员孙七")));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("INVALIDATED", repository.findCommandById(cmd.commandId()).orElseThrow().status());
        PlanView afterEntry = service.getPlan(planId);
        assertTrue(afterEntry.occupied());
        assertEquals(1, afterEntry.rounds().size());
        assertEquals("CLOSED", afterEntry.rounds().get(0).status());
        assertEquals(3, afterEntry.rounds().get(0).confirmations().size());
        service.confirmCheckpoint(planId, "CP-NORTH", new ConfirmRequest("安全员李四", 0, null));
        assertThrows(ApiException.class, () -> issue(planId, "REQ-2"));
        service.confirmCheckpoint(planId, "CP-EAST", new ConfirmRequest("安全员王五", 0, null));
        service.confirmCheckpoint(planId, "CP-GATE", new ConfirmRequest("安全员赵六", 0, null));
        CommandView cmd2 = issue(planId, "REQ-2");
        ReportView report = service.reportExecution(planId, cmd2.commandId(),
                new ExecuteRequest("EX-2", "爆破员孙七"));
        assertEquals("SUCCESS", report.result());
        PlanView view = service.getPlan(planId);
        assertEquals("COMPLETED", view.status());
        assertEquals(2, view.rounds().size());
    }
    @Test
    void issueRequestIdReplaySameParamsAndConflictOnDifferentParams() {
        long planId = newPlan();
        confirmAll(planId);
        CommandView cmd = issue(planId, "REQ-1");
        CommandView replayed = issue(planId, "REQ-1");
        assertEquals(cmd.commandId(), replayed.commandId());
        ApiException ex = assertThrows(ApiException.class,
                () -> service.issueCommand(planId, new IssueRequest("REQ-1", "另一个班长")));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertThrows(ApiException.class, () -> issue(planId, "REQ-2"));
        clock.advance(Duration.ofMinutes(6));
        CommandView cmd2 = issue(planId, "REQ-2");
        assertEquals("ISSUED", cmd2.status());
    }

    @Test
    void executeRequestIdReplayAndPlanEndsAfterSuccess() {
        long planId = newPlan();
        confirmAll(planId);
        CommandView cmd = issue(planId, "REQ-1");
        ReportView report = service.reportExecution(planId, cmd.commandId(),
                new ExecuteRequest("EX-1", "爆破员孙七"));
        assertEquals("SUCCESS", report.result());
        ReportView replayed = service.reportExecution(planId, cmd.commandId(),
                new ExecuteRequest("EX-1", "爆破员孙七"));
        assertEquals(report.reportId(), replayed.reportId());
        ApiException ex = assertThrows(ApiException.class,
                () -> service.reportExecution(planId, cmd.commandId(), new ExecuteRequest("EX-1", "别人")));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("COMPLETED", service.getPlan(planId).status());
        assertThrows(ApiException.class, () -> issue(planId, "REQ-2"));
        assertThrows(ApiException.class,
                () -> service.confirmCheckpoint(planId, "CP-NORTH", new ConfirmRequest("安全员", 0, null)));
        assertThrows(ApiException.class,
                () -> service.recordPersonEntry(planId, new PersonEntryRequest("警戒员", null)));
    }

    @Test
    void confirmRejectsNonZeroHeadcountAndUnknownTargets() {
        long planId = newPlan();
        ApiException ex = assertThrows(ApiException.class,
                () -> service.confirmCheckpoint(planId, "CP-NORTH", new ConfirmRequest("安全员李四", 1, null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        ex = assertThrows(ApiException.class,
                () -> service.confirmCheckpoint(planId, "CP-X", new ConfirmRequest("安全员李四", 0, null)));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        ex = assertThrows(ApiException.class, () -> service.getPlan(999L));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
    }

    @Test
    void concurrentIssueAllowsOnlyOneValidCommand() throws Exception {
        long planId = newPlan();
        confirmAll(planId);
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "REQ-C-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    return service.issueCommand(planId, new IssueRequest(requestId, "班长张三"));
                } catch (ApiException e) {
                    return e;
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(15, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, results.stream().filter(CommandView.class::isInstance).count());
        assertEquals(threads - 1, results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT)
                .count());
        assertEquals(1, repository.findCommands(planId).stream()
                .filter(c -> "ISSUED".equals(c.status())).count());
    }

    @Test
    void personEntryAndExecutionRaceResolvesByCommitOrder() throws Exception {
        for (int iteration = 0; iteration < 3; iteration++) {
            clean();
            long planId = newPlan();
            confirmAll(planId);
            CommandView cmd = issue(planId, "REQ-R");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Object> entryFuture = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                try {
                    return service.recordPersonEntry(planId, new PersonEntryRequest("警戒员", null));
                } catch (ApiException e) {
                    return e;
                }
            });
            Future<Object> execFuture = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                try {
                    return service.reportExecution(planId, cmd.commandId(),
                            new ExecuteRequest("EX-R", "爆破员孙七"));
                } catch (ApiException e) {
                    return e;
                }
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            entryFuture.get(15, TimeUnit.SECONDS);
            Object execResult = execFuture.get(15, TimeUnit.SECONDS);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            CommandRow after = repository.findCommandById(cmd.commandId()).orElseThrow();
            List<ReportRow> reports = repository.findReports(planId);
            if (execResult instanceof ReportView) {
                assertEquals("EXECUTED", after.status());
                assertEquals(1, reports.size());
                assertEquals("COMPLETED", service.getPlan(planId).status());
            } else {
                assertInstanceOf(ApiException.class, execResult);
                assertEquals("INVALIDATED", after.status());
                assertTrue(reports.isEmpty());
            }
        }
    }
}