package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.ActualMovementService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ActualActionRequest;
import com.example.starter.plan.web.dto.ActualMovementResponse;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 实际进出并发测试：同占用并发进入、同 requestId 并发重放、
 * 驶出与进入串行、进入与取消串行（真实 H2 事务与行锁）。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
class ActualMovementConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private ActualMovementService actualService;

    @Autowired
    private PlanService planService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentEnterSameOccupancyOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        String plan = key("SCH");
        createAndPublish(plan, section, 10, 0, 10, 10);
        setClock(10, 1);

        int threads = 4;
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    actualService.enter(plan, 0, new ActualActionRequest(key("REQ"), "op", 1));
                    return Outcome.OK;
                } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("OCCUPANCY_STATE_CONFLICT");
                    return Outcome.CONFLICT;
                }
            });
        }
        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(threads - 1);
        assertThat(countEvents(plan)).isEqualTo(1);
        assertThat(actualService.getActualLocks(DAY, section)).hasSize(1);
    }

    @Test
    void concurrentSameRequestIdEnterReturnsFirstResponse() throws Exception {
        String section = key("SEC");
        String plan = key("SCH");
        createAndPublish(plan, section, 10, 0, 10, 10);
        setClock(10, 1);

        String requestId = key("REQ");
        int threads = 4;
        List<Callable<ActualMovementResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> actualService.enter(plan, 0,
                    new ActualActionRequest(requestId, "op", 1)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ActualMovementResponse>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<ActualMovementResponse> responses = new ArrayList<>();
        for (Future<ActualMovementResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 全部返回首次响应，区段只获取一次，事件与幂等记录各一条
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(countEvents(plan)).isEqualTo(1);
        assertThat(actualService.getActualLocks(DAY, section)).hasSize(1);
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM actual_write_record WHERE request_id = ?",
                Integer.class, requestId);
        assertThat(records).isEqualTo(1);
    }

    @Test
    void concurrentExitAndEnterOnSameSectionSerialize() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createAndPublish(planA, section, 10, 0, 10, 10);
        createAndPublish(planB, section, 10, 10, 10, 20);
        setClock(10, 1);
        actualService.enter(planA, 0, new ActualActionRequest(key("REQ"), "op", 1));

        // A 未驶出，B 时隙已开始：驶出与进入并发，串行裁决
        setClock(10, 11);
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    actualService.exit(planA, 0, new ActualActionRequest(key("REQ"), "op", 1));
                    return Outcome.OK;
                },
                () -> {
                    try {
                        actualService.enter(planB, 0, new ActualActionRequest(key("REQ"), "op", 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("SECTION_ACTUALLY_LOCKED");
                        return Outcome.CONFLICT;
                    }
                }));

        // 驶出必须成功；进入要么在驶出后成功，要么 409
        assertThat(outcomes.get(0)).isEqualTo(Outcome.OK);
        if (outcomes.get(1) == Outcome.CONFLICT) {
            // 实际锁已释放：重试进入成功
            actualService.enter(planB, 0, new ActualActionRequest(key("REQ"), "op", 1));
        }
        assertThat(actualService.getActualLocks(DAY, section)).hasSize(1);
        assertThat(actualService.getActualLocks(DAY, section).get(0).scheduleKey())
                .isEqualTo(planB);
        assertThat(actualService.getActualOccupancies(planA).get(0).actualStatus())
                .isEqualTo("EXITED");
    }

    @Test
    void concurrentEnterAndCancelSerialize() throws Exception {
        String section = key("SEC");
        String plan = key("SCH");
        createAndPublish(plan, section, 10, 0, 10, 10);
        setClock(10, 1);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        actualService.enter(plan, 0, new ActualActionRequest(key("REQ"), "op", 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        planService.cancel(plan, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        // 恰好一个成功：进入先则取消 409（含 INSIDE），取消先则进入 409（计划已取消）
        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.OK, Outcome.CONFLICT);
        if (outcomes.get(0) == Outcome.OK) {
            assertThat(planService.getPlan(plan).status()).isEqualTo("PUBLISHED");
            assertThat(actualService.getActualOccupancies(plan).get(0).actualStatus())
                    .isEqualTo("INSIDE");
            assertThat(actualService.getActualLocks(DAY, section)).hasSize(1);
        } else {
            assertThat(planService.getPlan(plan).status()).isEqualTo("CANCELLED");
            assertThat(actualService.getActualOccupancies(plan).get(0).actualStatus())
                    .isEqualTo("NOT_ENTERED");
            assertThat(countEvents(plan)).isZero();
        }
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
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

    private int countEvents(String scheduleKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_actual_event e JOIN rail_day_plan p ON p.id = e.plan_id"
                        + " WHERE p.schedule_key = ?",
                Integer.class, scheduleKey);
        return count == null ? 0 : count;
    }

    private void setClock(int hour, int minute) {
        clock.setInstant(DAY.atTime(hour, minute).atZone(SH).toInstant());
    }

    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
