package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.ConnectionService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.GuaranteeRescheduleRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.RegisterConnectionRequest;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.SectionRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 接续保障并发裁决测试：同一前后车的并发登记只生效一条；
 * 同一风险后车的并发批量保障改签只形成一个生效时刻。
 */
@SpringBootTest
class ConnectionConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService planService;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.setInstant(TestClockConfig.DEFAULT_INSTANT);
    }

    @Test
    void concurrentRegisterSamePairOnlyOneConnection() throws Exception {
        String sec1 = key("SEC");
        String sec2 = key("SEC");
        connectionService.registerSection(new SectionRequest(sec1, "Alpha", "Beta"));
        connectionService.registerSection(new SectionRequest(sec2, "Beta", "Gamma"));
        String pred = key("SCH");
        String succ = key("SCH");
        createAndPublish(pred, sec1, 8, 0, 9, 0);
        createAndPublish(succ, sec2, 9, 30, 10, 30);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> register(pred, succ),
                () -> register(pred, succ)));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_connection c JOIN rail_day_plan p"
                        + " ON p.id = c.successor_plan_id WHERE p.schedule_key = ?",
                Integer.class, succ);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentBatchGuaranteeRescheduleOnlyOneEffectiveTime() throws Exception {
        String sec1 = key("SEC");
        String sec2 = key("SEC");
        connectionService.registerSection(new SectionRequest(sec1, "Alpha", "Beta"));
        connectionService.registerSection(new SectionRequest(sec2, "Beta", "Gamma"));
        String pred = key("SCH");
        String succ = key("SCH");
        createAndPublish(pred, sec1, 8, 0, 9, 0);
        createAndPublish(succ, sec2, 9, 30, 10, 30);
        connectionService.register(new RegisterConnectionRequest(key("REQ"), pred, succ, 20, false));
        // 前车改签延后，后车进入风险状态
        String pred2 = key("SCH");
        planService.createDraft(new CreatePlanRequest(key("REQ"), pred2, DAY,
                List.of(new OccupancyRequest("G1", sec1, at(9, 20), at(10, 20)))));
        planService.reschedule(pred, new RescheduleRequest(key("REQ"), pred2, 1, 1));
        assertThat(riskCount(succ)).isEqualTo(1);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> batch(succ),
                () -> batch(succ)));

        // 同一后车并发批量改签：恰好一次成功，只形成一个生效时刻
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        assertThat(planService.getPlan(succ).version()).isEqualTo(2);
        assertThat(planService.getPlan(succ).occupancies().get(0).startUtc())
                .isEqualTo(at(10, 40));
        assertThat(riskCount(succ)).isZero();
        Integer decisions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_connection_event e"
                        + " JOIN rail_connection c ON c.id = e.connection_id"
                        + " JOIN rail_day_plan p ON p.id = c.successor_plan_id"
                        + " WHERE p.schedule_key = ? AND e.event_type = 'RESCHEDULED'",
                Integer.class, succ);
        assertThat(decisions).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private Outcome register(String pred, String succ) {
        try {
            connectionService.register(
                    new RegisterConnectionRequest(key("REQ"), pred, succ, 20, false));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("CONNECTION_EXISTS");
            return Outcome.CONFLICT;
        }
    }

    private Outcome batch(String succ) {
        try {
            connectionService.guaranteeReschedule(
                    new GuaranteeRescheduleRequest(key("REQ"), List.of(succ)));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.code()).isEqualTo("PLAN_NOT_AT_RISK");
            return Outcome.CONFLICT;
        }
    }

    /**
     * 共享库中指定计划当前的风险标记条数（0 或 1）。
     */
    private long riskCount(String scheduleKey) {
        return connectionService.listRisks().stream()
                .filter(r -> r.scheduleKey().equals(scheduleKey)).count();
    }

    private void createAndPublish(String scheduleKey, String section,
                                  int startHour, int startMinute, int endHour, int endMinute) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        at(startHour, startMinute), at(endHour, endMinute)))));
        planService.publish(scheduleKey, key("REQ"));
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

    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}