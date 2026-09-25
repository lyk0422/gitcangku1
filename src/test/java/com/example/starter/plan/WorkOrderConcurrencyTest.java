package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.work.service.WorkOrderService;
import com.example.starter.work.web.dto.CreateSectionRequest;
import com.example.starter.work.web.dto.CreateWorkOrderRequest;
import com.example.starter.work.web.dto.UpdateWorkOrderRequest;
import com.example.starter.work.web.dto.WorkOrderActionRequest;
import com.example.starter.work.web.dto.WorkOrderResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 施工占用窗口并发与幂等竞态测试：并发创建重叠施工单互斥、同键并发创建重放、
 * 并发修改按提交顺序生效、并发取消只生效一次、施工窗口与计划发布串行裁决。
 */
@SpringBootTest
class WorkOrderConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    private static final LocalDate FUTURE_DAY = LocalDate.of(2099, 1, 1);

    @Autowired
    private WorkOrderService workService;

    @Autowired
    private PlanService planService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentCreateOverlappingWorkOrdersOnlyOneSucceeds() throws Exception {
        String section = registerSection();
        int contenders = 4;

        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String workKey = key("WK");
            tasks.add(() -> {
                try {
                    workService.create(new CreateWorkOrderRequest(key("REQ"), "op", workKey,
                            at(DAY, 8), at(DAY, 10), List.of(section)));
                    return Outcome.OK;
                } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("WORK_WINDOW_OVERLAP");
                    return Outcome.CONFLICT;
                }
            });
        }
        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(contenders - 1);
        // 最终该区段只有一个生效施工窗口
        assertThat(workService.getWorkWindows(section)).hasSize(1);
    }

    @Test
    void concurrentCreateSameKeySameParamsReturnsFirstResult() throws Exception {
        String section = registerSection();
        String workKey = key("WK");
        String requestKey = key("REQ");
        int threads = 4;

        CreateWorkOrderRequest request = new CreateWorkOrderRequest(requestKey, "op", workKey,
                at(DAY, 8), at(DAY, 9), List.of(section));
        List<Callable<WorkOrderResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> workService.create(request));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<WorkOrderResponse>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<WorkOrderResponse> responses = new ArrayList<>();
        for (Future<WorkOrderResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 全部返回首次结果，且库里只有一张施工单
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_work_order WHERE work_key = ?",
                Integer.class, workKey);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentUpdateSameWorkOrderAppliesInCommitOrder() throws Exception {
        String section = registerSection();
        String workKey = key("WK");
        workService.create(new CreateWorkOrderRequest(key("REQ"), "op", workKey,
                at(DAY, 8), at(DAY, 9), List.of(section)));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        workService.update(workKey, new UpdateWorkOrderRequest(
                                key("REQ"), "op-a", 1, at(DAY, 10), at(DAY, 11), List.of(section)));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        workService.update(workKey, new UpdateWorkOrderRequest(
                                key("REQ"), "op-b", 1, at(DAY, 12), at(DAY, 13), List.of(section)));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        // 两个修改都以 expectedVersion=1 竞争：恰好一个成功，版本只加一
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        WorkOrderResponse after = workService.getWorkOrder(workKey);
        assertThat(after.version()).isEqualTo(2);
    }

    @Test
    void concurrentCancelOnlyOneSucceedsAndSingleRecord() throws Exception {
        String section = registerSection();
        String workKey = key("WK");
        workService.create(new CreateWorkOrderRequest(key("REQ"), "op", workKey,
                at(FUTURE_DAY, 8), at(FUTURE_DAY, 10), List.of(section)));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        workService.cancel(workKey, new WorkOrderActionRequest(key("REQ"), "op-a"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        workService.cancel(workKey, new WorkOrderActionRequest(key("REQ"), "op-b"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        // 取消记录不可变且只有一条
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_work_cancel_record WHERE work_key = ?",
                Integer.class, workKey);
        assertThat(records).isEqualTo(1);
        assertThat(workService.getWorkOrder(workKey).status()).isEqualTo("CANCELLED");
    }

    @Test
    void concurrentPublishAndWorkOrderCreateSerializeInCommitOrder() throws Exception {
        String section = registerSection();
        String scheduleKey = key("SCH");
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        at(DAY, 8), at(DAY, 9)))));
        String workKey = key("WK");

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        planService.publish(scheduleKey, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 施工单先提交时发布应得 422 施工窗口冲突
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.code()).isEqualTo("WORK_WINDOW_CONFLICT");
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    workService.create(new CreateWorkOrderRequest(key("REQ"), "op", workKey,
                            at(DAY, 8), at(DAY, 10), List.of(section)));
                    return Outcome.OK;
                }));

        // 施工单创建总是成功；发布要么先于创建成功，要么因窗口生效而 422
        assertThat(outcomes.get(1)).isEqualTo(Outcome.OK);
        if (outcomes.get(0) == Outcome.OK) {
            assertThat(planService.getPlan(scheduleKey).status()).isEqualTo("PUBLISHED");
        } else {
            assertThat(planService.getPlan(scheduleKey).status()).isEqualTo("DRAFT");
        }
        assertThat(workService.getWorkOrder(workKey).status()).isEqualTo("ACTIVE");
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private String registerSection() {
        String sectionId = key("SEC");
        workService.registerSection(new CreateSectionRequest(sectionId));
        return sectionId;
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

    private static Instant at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
