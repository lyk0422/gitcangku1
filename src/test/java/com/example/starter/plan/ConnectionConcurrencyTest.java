package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.starter.plan.service.ConnectionService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchGuaranteeRequest;
import com.example.starter.plan.web.dto.ConnectionRegisterRequest;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.RescheduleRequest;
import java.time.Clock;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 接续保障并发裁决测试（H2 内存库，可控时钟）：同一后车并发批量保障改签只形成一个
 * 生效时刻；同一对计划并发登记接续只生效一条。
 */
@SpringBootTest
class ConnectionConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    /** 运营日当日 00:00（Asia/Shanghai），早于全部测试计划时刻。 */
    private static final Instant BEFORE_DAY = DAY.atStartOfDay(SH).toInstant();

    @Autowired
    private PlanService planService;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUpClock() {
        when(clock.instant()).thenReturn(BEFORE_DAY);
    }

    @Test
    void concurrentBatchGuaranteeSameSuccessorOnlyOneEffective() throws Exception {
        String section1 = key("SEC");
        String section2 = key("SEC");
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, section1, "STA-A", "STA-X", 8, 9);
        planService.publish(pred, key("REQ"));
        createPlan(succ, section2, "STA-X", "STA-B", 9, 30, 10, 30);
        planService.publish(succ, key("REQ"));
        connectionService.register(new ConnectionRegisterRequest(
                key("REQ"), pred, succ, "STA-X", 30, null));
        // 前车终到延至 09:50，后车进入风险状态
        String predNew = key("SCH");
        createPlan(predNew, section1, "STA-A", "STA-X", 8, 30, 9, 50);
        planService.reschedule(pred, new RescheduleRequest(key("REQ"), predNew, 1, 1));
        assertThat(planService.getPlan(succ).connectionRisk()).isEqualTo("CONNECTION_AT_RISK");

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        connectionService.batchGuarantee(
                                new BatchGuaranteeRequest(key("REQ"), List.of(succ)));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.code()).isEqualTo("PLAN_NOT_AT_RISK");
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        connectionService.batchGuarantee(
                                new BatchGuaranteeRequest(key("REQ"), List.of(succ)));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.code()).isEqualTo("PLAN_NOT_AT_RISK");
                        return Outcome.CONFLICT;
                    }
                }));

        // 同一后车并发改签只成功一次：恰好一个生效时刻
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        assertThat(planService.getPlan(succ).version()).isEqualTo(2);
        assertThat(planService.getPlan(succ).connectionRisk()).isNull();
        assertThat(planService.getPlan(succ).occupancies().get(0).startUtc())
                .isEqualTo(at(10, 20));
        Integer decisionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_guarantee_decision d"
                        + " JOIN rail_day_plan p ON p.id = d.successor_plan_id"
                        + " WHERE p.schedule_key = ?",
                Integer.class, succ);
        assertThat(decisionCount).isEqualTo(1);
    }

    @Test
    void concurrentRegisterSamePairOnlyOneSucceeds() throws Exception {
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, key("SEC"), "STA-A", "STA-X", 8, 9);
        planService.publish(pred, key("REQ"));
        createPlan(succ, key("SEC"), "STA-X", "STA-B", 9, 30, 10, 30);
        planService.publish(succ, key("REQ"));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        connectionService.register(new ConnectionRegisterRequest(
                                key("REQ"), pred, succ, "STA-X", 30, null));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("CONNECTION_EXISTS");
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        connectionService.register(new ConnectionRegisterRequest(
                                key("REQ"), pred, succ, "STA-X", 30, null));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("CONNECTION_EXISTS");
                        return Outcome.CONFLICT;
                    }
                }));

        // 同一对计划并发登记只生效一条
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_connection c"
                        + " JOIN rail_day_plan p ON p.id = c.predecessor_plan_id"
                        + " WHERE p.schedule_key = ? AND c.status = 'ACTIVE'",
                Integer.class, pred);
        assertThat(count).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private void createPlan(String scheduleKey, String section, String from, String to,
                            int startHour, int endHour) {
        createPlan(scheduleKey, section, from, to, startHour, 0, endHour, 0);
    }

    private void createPlan(String scheduleKey, String section, String from, String to,
                            int startHour, int startMinute, int endHour, int endMinute) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section, from, to,
                        at(startHour, startMinute), at(endHour, endMinute)))));
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
