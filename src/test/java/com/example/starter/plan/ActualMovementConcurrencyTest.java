package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.ActualMovementService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ActualActionRequest;
import com.example.starter.plan.web.dto.ActualMovementResponse;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 实际进出并发测试（H2 真实事务 + 可控时钟）：并发进入同一占用仅一个成功、
 * 同 requestId 并发重放返回首次结果、退出与取消串行裁决。
 */
@SpringBootTest
class ActualMovementConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 7);

    @Autowired
    private PlanService planService;

    @Autowired
    private ActualMovementService actualService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    @Test
    void concurrentEnterSameOccupancyOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        String sch = key("SCH");
        createPublished(sch, section, 10, 0, 10, 20);
        nowAt(10, 5);

        int threads = 4;
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    actualService.enter(sch, 0, new ActualActionRequest(key("RID"), "op-c", 1));
                    return Outcome.OK;
                } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("ACTUAL_STATE_CONFLICT");
                    return Outcome.CONFLICT;
                }
            });
        }
        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(threads - 1);
        assertThat(countInside(section)).isEqualTo(1);
        assertThat(countEvents(sch, "ENTER")).isEqualTo(1);
    }

    @Test
    void concurrentEnterSameRequestIdReplaysFirstResponse() throws Exception {
        String section = key("SEC");
        String sch = key("SCH");
        createPublished(sch, section, 10, 0, 10, 20);
        nowAt(10, 5);

        String requestId = key("RID");
        int threads = 4;
        List<Callable<ActualMovementResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> actualService.enter(sch, 0,
                    new ActualActionRequest(requestId, "op-c", 1)));
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

        // 全部返回首次 INSIDE 响应，区段只获取一次，轨迹只有一条
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(responses.get(0).actualStatus()).isEqualTo("INSIDE");
        assertThat(countInside(section)).isEqualTo(1);
        assertThat(countEvents(sch, "ENTER")).isEqualTo(1);
    }

    @Test
    void concurrentExitAndCancelAreSerialized() throws Exception {
        String section = key("SEC");
        String sch = key("SCH");
        createPublished(sch, section, 10, 0, 10, 20);
        nowAt(10, 5);
        actualService.enter(sch, 0, new ActualActionRequest(key("RID"), "op-c", 1));

        nowAt(10, 15);
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    actualService.exit(sch, 0, new ActualActionRequest(key("RID"), "op-c", 1));
                    return Outcome.OK;
                },
                () -> {
                    try {
                        planService.cancel(sch, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 取消先获锁时 INSIDE 仍在，必须 409
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("PLAN_HAS_INSIDE");
                        return Outcome.CONFLICT;
                    }
                }));

        // 退出必然成功；取消按串行提交顺序要么 409（先）要么成功（后）
        assertThat(outcomes.get(0)).isEqualTo(Outcome.OK);
        PlanResponse plan = planService.getPlan(sch);
        if (outcomes.get(1) == Outcome.OK) {
            assertThat(plan.status()).isEqualTo("CANCELLED");
        } else {
            assertThat(plan.status()).isEqualTo("PUBLISHED");
        }
        // 退出轨迹与锁释放已同事务提交：占用终态 EXITED，区段无实际锁
        assertThat(countInside(section)).isEqualTo(0);
        assertThat(countEvents(sch, "EXIT")).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private void nowAt(int hour, int minute) {
        Mockito.when(clock.instant())
                .thenReturn(DAY.atTime(hour, minute).atZone(SH).toInstant());
    }

    private void createPublished(String scheduleKey, String section,
                                 int startHour, int startMinute, int endHour, int endMinute) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        at(startHour, startMinute), at(endHour, endMinute)))));
        planService.publish(scheduleKey, key("REQ"));
    }

    private int countInside(String sectionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_occupancy"
                        + " WHERE section_id = ? AND actual_status = 'INSIDE'",
                Integer.class, sectionId);
        return count == null ? 0 : count;
    }

    private int countEvents(String scheduleKey, String eventType) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_actual_event e JOIN rail_day_plan p ON p.id = e.plan_id"
                        + " WHERE p.schedule_key = ? AND e.event_type = ?",
                Integer.class, scheduleKey, eventType);
        return count == null ? 0 : count;
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
