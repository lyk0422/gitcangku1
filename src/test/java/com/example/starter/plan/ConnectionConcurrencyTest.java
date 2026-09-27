package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.ConnectionService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.TimeProvider;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ConnectionView;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.GuaranteeRescheduleRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.RegisterConnectionRequest;
import com.example.starter.plan.web.dto.RescheduleRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * 接续保障并发裁决测试：同一后车并发保障改签只形成一个生效时刻；
 * 同对计划并发登记只形成一条生效接续；同键并发重放返回首次结果。
 */
@SpringBootTest
class ConnectionConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    private static final Instant FIXED_NOW = Instant.parse("2026-09-21T00:00:00Z");

    @Autowired
    private PlanService planService;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private TimeProvider timeProvider;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void fixClock() {
        timeProvider.setClock(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void resetClock() {
        timeProvider.reset();
    }

    @Test
    void concurrentGuaranteeRescheduleSameSuccessorOnlyOneSucceeds() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred = key("SCH");
        String succ = key("SCH");
        createDraft(pred, "G1", secAB, 8, 0, 9, 0);
        planService.publish(pred, key("REQ"));
        createDraft(succ, "G2", secBC, 9, 40, 10, 40);
        planService.publish(succ, key("REQ"));
        connectionService.register(new RegisterConnectionRequest(key("REQ"), pred, succ, 30, false));
        String predV2 = key("SCH");
        createDraft(predV2, "G1", secAB, 9, 0, 10, 0);
        planService.reschedule(pred, new RescheduleRequest(key("REQ"), predV2, 1, 1));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> guarantee(succ), () -> guarantee(succ)));

        // 同一后车并发保障改签：恰好一次成功，只形成一个生效时刻
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        assertThat(planService.getPlan(succ).version()).isEqualTo(2);
        assertThat(planService.getPlan(succ).occupancies().get(0).startUtc())
                .isEqualTo(at(10, 30));
        assertThat(connectionService.getRisk(succ).riskStatus()).isEqualTo("NONE");
        Integer rescheduledEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_connection_event e"
                        + " JOIN rail_day_plan p ON p.id = e.successor_plan_id"
                        + " WHERE p.schedule_key = ? AND e.event_type = 'RESCHEDULED'",
                Integer.class, succ);
        assertThat(rescheduledEvents).isEqualTo(1);
    }

    @Test
    void concurrentRegisterSamePairOnlyOneActive() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred = key("SCH");
        String succ = key("SCH");
        createDraft(pred, "G1", secAB, 8, 0, 9, 0);
        planService.publish(pred, key("REQ"));
        createDraft(succ, "G2", secBC, 10, 0, 11, 0);
        planService.publish(succ, key("REQ"));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> register(pred, succ), () -> register(pred, succ)));

        // 同对计划并发登记：恰好一次成功，另一条 409
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_connection c"
                        + " JOIN rail_day_plan p ON p.id = c.predecessor_plan_id"
                        + " WHERE p.schedule_key = ? AND c.status = 'ACTIVE'",
                Integer.class, pred);
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void concurrentRegisterSameRequestKeyReplaysFirstResult() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred = key("SCH");
        String succ = key("SCH");
        createDraft(pred, "G1", secAB, 8, 0, 9, 0);
        planService.publish(pred, key("REQ"));
        createDraft(succ, "G2", secBC, 10, 0, 11, 0);
        planService.publish(succ, key("REQ"));

        String requestKey = key("REQ");
        List<ConnectionView> views = runConcurrently(List.of(
                () -> connectionService.register(
                        new RegisterConnectionRequest(requestKey, pred, succ, 30, false)),
                () -> connectionService.register(
                        new RegisterConnectionRequest(requestKey, pred, succ, 30, false))));

        // 同键同参并发：都返回首次结果，只形成一条接续
        assertThat(views).hasSize(2);
        assertThat(views.get(0).id()).isEqualTo(views.get(1).id());
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_connection c"
                        + " JOIN rail_day_plan p ON p.id = c.predecessor_plan_id"
                        + " WHERE p.schedule_key = ?",
                Integer.class, pred);
        assertThat(total).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private Outcome guarantee(String succ) {
        try {
            connectionService.guaranteeReschedule(
                    new GuaranteeRescheduleRequest(key("REQ"), List.of(succ)));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.code()).isEqualTo("CONNECTION_NOT_AT_RISK");
            return Outcome.CONFLICT;
        }
    }

    private Outcome register(String pred, String succ) {
        try {
            connectionService.register(
                    new RegisterConnectionRequest(key("REQ"), pred, succ, 30, false));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            return Outcome.CONFLICT;
        }
    }

    private void createDraft(String scheduleKey, String trainNo, String section,
                             int startHour, int startMinute, int endHour, int endMinute) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest(trainNo, section,
                        at(startHour, startMinute), at(endHour, endMinute)))));
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String station() {
        return "S" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String section() {
        return station() + "-" + station();
    }

    private static String sectionFrom(String originStation) {
        return originStation + "-" + station();
    }

    private static String destOf(String sectionId) {
        return sectionId.substring(sectionId.lastIndexOf('-') + 1);
    }
}
