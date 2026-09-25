package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.CrewService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.TimeSource;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.CrewReplacementRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PublishPlanRequest;
import com.example.starter.plan.web.dto.RegisterQualificationRequest;
import com.example.starter.plan.web.dto.TerminateQualificationRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 乘务资质并发裁决测试（真实 H2）：同一资质并发提前终止按提交顺序只成功一次；
 * 同一 requestKey 并发发布重放首次完整结果；风险计划并发换人只解除一次门禁。
 */
@SpringBootTest
class CrewConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);
    private static final Instant FIXED_NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final Instant VALID_EXPIRY = Instant.parse("2026-09-26T00:00:00Z");

    @Autowired
    private PlanService planService;

    @Autowired
    private CrewService crewService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TimeSource timeSource;

    @BeforeEach
    void fixClock() {
        timeSource.setFixed(FIXED_NOW);
    }

    @AfterEach
    void resetClock() {
        timeSource.reset();
    }

    @Test
    void concurrentTerminateSameQualificationOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        String driver = key("DRV");
        String conductor = key("CON");
        String driverQual = "Q-" + UUID.randomUUID();
        crewService.register(new RegisterQualificationRequest(key("REQ"), driver, driverQual,
                List.of(section), VALID_EXPIRY));
        crewService.register(new RegisterQualificationRequest(key("REQ"), conductor,
                "Q-" + UUID.randomUUID(), List.of(section), VALID_EXPIRY));
        List<String> planKeys = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String planKey = key("SCH");
            createDraftWithCrew(planKey, section, 8 + i);
            planService.publish(planKey, new PublishPlanRequest(key("REQ"), "op-1", 1,
                    driver, conductor));
            planKeys.add(planKey);
        }

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> terminateOutcome(driver, driverQual, key("REQ")),
                () -> terminateOutcome(driver, driverQual, key("REQ"))));

        // 同一资质的两次终止恰好一次成功，另一次版本冲突（409）
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);

        // 资质只终止一次，版本恰为 2
        Integer version = jdbc.queryForObject(
                "SELECT version FROM rail_crew_qualification WHERE crew_id = ?"
                        + " AND qualification_code = ?",
                Integer.class, driver, driverQual);
        Boolean terminated = jdbc.queryForObject(
                "SELECT terminated FROM rail_crew_qualification WHERE crew_id = ?"
                        + " AND qualification_code = ?",
                Boolean.class, driver, driverQual);
        assertThat(version).isEqualTo(2);
        assertThat(terminated).isTrue();

        // 两个未来计划均有且仅有一条不可变风险记录并置门禁
        for (String planKey : planKeys) {
            Integer riskCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rail_plan_risk_record r"
                            + " JOIN rail_day_plan p ON p.id = r.plan_id"
                            + " WHERE p.schedule_key = ? AND r.crew_id = ?",
                    Integer.class, planKey, driver);
            assertThat(riskCount).isEqualTo(1);
            assertThat(planService.getPlan(planKey).riskBlocked()).isTrue();
        }
    }

    @Test
    void concurrentPublishWithSameRequestKeyReplaysFirstResult() throws Exception {
        String section = key("SEC");
        String driver = key("DRV");
        String conductor = key("CON");
        crewService.register(new RegisterQualificationRequest(key("REQ"), driver,
                "Q-" + UUID.randomUUID(), List.of(section), VALID_EXPIRY));
        crewService.register(new RegisterQualificationRequest(key("REQ"), conductor,
                "Q-" + UUID.randomUUID(), List.of(section), VALID_EXPIRY));
        String planKey = key("SCH");
        createDraftWithCrew(planKey, section, 8);
        String requestKey = key("REQ");

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> publishOutcome(planKey, requestKey, driver, conductor),
                () -> publishOutcome(planKey, requestKey, driver, conductor)));

        // 同键并发均成功：一次真实发布、一次重放首次结果
        assertThat(outcomes).containsExactly(Outcome.OK, Outcome.OK);
        assertThat(planService.getPlan(planKey).status()).isEqualTo("PUBLISHED");
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE op_type = 'PUBLISH'"
                        + " AND request_key = ?",
                Integer.class, requestKey);
        assertThat(idemCount).isEqualTo(1);
    }

    @Test
    void concurrentCrewReplacementOnlyOneSucceedsAndGateClears() throws Exception {
        String section = key("SEC");
        String driver = key("DRV");
        String conductor = key("CON");
        String driverQual = "Q-" + UUID.randomUUID();
        crewService.register(new RegisterQualificationRequest(key("REQ"), driver, driverQual,
                List.of(section), VALID_EXPIRY));
        crewService.register(new RegisterQualificationRequest(key("REQ"), conductor,
                "Q-" + UUID.randomUUID(), List.of(section), VALID_EXPIRY));
        String planKey = key("SCH");
        createDraftWithCrew(planKey, section, 8);
        planService.publish(planKey, new PublishPlanRequest(key("REQ"), "op-1", 1,
                driver, conductor));
        crewService.terminate(driver, driverQual,
                new TerminateQualificationRequest(key("REQ"), 1));
        assertThat(planService.getPlan(planKey).riskBlocked()).isTrue();

        String newDriver = key("DRV");
        crewService.register(new RegisterQualificationRequest(key("REQ"), newDriver,
                "Q-" + UUID.randomUUID(), List.of(section), VALID_EXPIRY));
        String requestKey = key("REQ");

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> replaceOutcome(planKey, requestKey, newDriver, conductor),
                () -> replaceOutcome(planKey, requestKey, newDriver, conductor)));

        // 一次真实换人、一次幂等重放，门禁最终解除
        assertThat(outcomes).containsExactly(Outcome.OK, Outcome.OK);
        var plan = planService.getPlan(planKey);
        assertThat(plan.riskBlocked()).isFalse();
        assertThat(plan.driverId()).isEqualTo(newDriver);
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE op_type = 'CREW_REPLACE'"
                        + " AND request_key = ?",
                Integer.class, requestKey);
        assertThat(idemCount).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private Outcome terminateOutcome(String crewId, String code, String requestKey) {
        try {
            crewService.terminate(crewId, code,
                    new TerminateQualificationRequest(requestKey, 1));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            return Outcome.CONFLICT;
        }
    }

    private Outcome publishOutcome(String planKey, String requestKey,
                                   String driver, String conductor) {
        try {
            planService.publish(planKey, new PublishPlanRequest(requestKey, "op-1", 1,
                    driver, conductor));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            return Outcome.CONFLICT;
        }
    }

    private Outcome replaceOutcome(String planKey, String requestKey,
                                   String driver, String conductor) {
        try {
            crewService.replaceCrew(planKey, new CrewReplacementRequest(requestKey, 1,
                    driver, conductor));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            return Outcome.CONFLICT;
        }
    }

    private void createDraftWithCrew(String scheduleKey, String section, int hour) {
        Instant start = DAY.atTime(hour, 0).atZone(SH).toInstant();
        Instant end = DAY.atTime(hour + 1, 0).atZone(SH).toInstant();
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section, start, end))));
    }

    private List<Outcome> runConcurrently(List<Callable<Outcome>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
