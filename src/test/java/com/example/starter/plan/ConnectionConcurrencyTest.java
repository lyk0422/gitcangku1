package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.ConnectionService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchGuaranteeRequest;
import com.example.starter.plan.web.dto.ConnectionRegisterRequest;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.RescheduleRequest;
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
 * 接续保障并发裁决测试：同一后车的并发批量保障改签只形成一个生效时刻；
 * 同一前后车对的并发登记只形成一条生效接续。
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
        clock.setInstant(Instant.parse("2026-09-21T00:00:00Z"));
    }

    @Test
    void concurrentBatchRescheduleSameSuccessorOnlyOneTakesEffect() throws Exception {
        String sectionA = key("SEC");
        String pred = key("SCH");
        createDraft(pred, "G1", sectionA, at(8, 0), at(10, 0));
        planService.publish(pred, key("REQ"));
        String succ = key("SCH");
        createDraft(succ, "G2", key("SEC"), at(10, 45), at(12, 0));
        planService.publish(succ, key("REQ"));
        connectionService.register(new ConnectionRegisterRequest(key("REQ"), pred, succ, 30,
                "西安北", "西安北", false));
        // 前车改签延后，后车进入风险状态
        String predNew = key("SCH");
        createDraft(predNew, "G1", sectionA, at(8, 0), at(10, 30));
        planService.reschedule(pred, new RescheduleRequest(key("REQ"), predNew, 1, 1));
        assertThat(connectionService.getDiagnostics(succ).connectionRisk())
                .isEqualTo("CONNECTION_AT_RISK");

        // 两个并发批量改签（不同幂等键、相同期望版本）争夺同一后车
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> batch(succ, key("REQ")),
                () -> batch(succ, key("REQ"))));

        // 恰好一个成功，另一个因版本已被推进而 409
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);

        // 只形成一个生效时刻：恰好平移一次到 11:00，版本 2，风险清除
        assertThat(planService.getPlan(succ).version()).isEqualTo(2);
        assertThat(connectionService.getDiagnostics(succ).connectionRisk()).isEqualTo("NONE");
        List<Instant> starts = jdbc.queryForList(
                "SELECT start_utc FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                        + " WHERE p.schedule_key = ? ORDER BY o.seq",
                Long.class, succ).stream().map(Instant::ofEpochMilli).toList();
        assertThat(starts).containsExactly(Instant.parse(iso(11, 0)));
    }

    @Test
    void concurrentRegisterSamePairOnlyOneConnectionActive() throws Exception {
        String pred = key("SCH");
        createDraft(pred, "G1", key("SEC"), at(8, 0), at(10, 0));
        planService.publish(pred, key("REQ"));
        String succ = key("SCH");
        createDraft(succ, "G2", key("SEC"), at(11, 0), at(12, 0));
        planService.publish(succ, key("REQ"));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> register(pred, succ, key("REQ")),
                () -> register(pred, succ, key("REQ"))));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_connection c"
                        + " JOIN rail_day_plan p1 ON p1.id = c.predecessor_plan_id"
                        + " JOIN rail_day_plan p2 ON p2.id = c.successor_plan_id"
                        + " WHERE p1.schedule_key = ? AND p2.schedule_key = ? AND c.status = 'ACTIVE'",
                Integer.class, pred, succ);
        assertThat(count).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private Outcome batch(String succ, String requestKey) {
        try {
            connectionService.batchGuaranteeReschedule(new BatchGuaranteeRequest(requestKey,
                    List.of(new BatchGuaranteeRequest.Item(succ, 1))));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            return Outcome.CONFLICT;
        }
    }

    private Outcome register(String pred, String succ, String requestKey) {
        try {
            connectionService.register(new ConnectionRegisterRequest(requestKey, pred, succ, 30,
                    "西安北", "西安北", false));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            return Outcome.CONFLICT;
        }
    }

    private void createDraft(String scheduleKey, String train, String section,
                             Instant start, Instant end) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest(train, section, start, end))));
    }

    private List<Outcome> runConcurrently(List<Callable<Outcome>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return t.call();
                })).toList();
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        return outcomes;
    }

    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant();
    }

    private static String iso(int hour, int minute) {
        return at(hour, minute).toString();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}