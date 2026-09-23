package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.model.CapacitySwap;
import com.example.starter.plan.repo.CapacitySwapRepository;
import com.example.starter.plan.service.CapacitySwapService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.SwapCreateRequest;
import com.example.starter.plan.web.dto.SwapItemRequest;
import com.example.starter.plan.web.dto.SwapResponse;
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

/**
 * 容量交换并发裁决测试（真实 H2，真实并发线程与事务）：
 * 两交换单竞争同一时隙时按事务提交顺序只出现一个完整后态；
 * 交换与普通发布竞争时恰好一方生效；同一交换单并发激活只成功一次并幂等重放。
 */
@SpringBootTest
class CapacitySwapConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 24);

    @Autowired
    private CapacitySwapService swapService;

    @Autowired
    private PlanService planService;

    @Autowired
    private CapacitySwapRepository swapRepo;

    @Test
    void twoSwapsCompetingForSameSlotsProduceSingleFinalState() throws Exception {
        // 时隙 8-10、10-12、12-14 分属 A、B、C；两个交换单是同一闭环的两个旋转方向。
        String section = key("SEC");
        Plan a = publishOne("A", section, 8, 10);
        Plan b = publishOne("B", section, 10, 12);
        Plan c = publishOne("C", section, 12, 14);

        // swap1：A->10-12, B->12-14, C->8-10
        String swap1 = key("SWAP");
        createSwap(swap1, List.of(
                item(a, 1, section, 8, 10, 10, 12),
                item(b, 1, section, 10, 12, 12, 14),
                item(c, 1, section, 12, 14, 8, 10)));

        // swap2：相反旋转 A->12-14, B->8-10, C->10-12（完整后态与 swap1 互斥）
        String swap2 = key("SWAP");
        createSwap(swap2, List.of(
                item(a, 1, section, 8, 10, 12, 14),
                item(b, 1, section, 10, 12, 8, 10),
                item(c, 1, section, 12, 14, 10, 12)));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> activate(swap1),
                () -> activate(swap2)));

        long ok = outcomes.stream().filter(o -> o == Outcome.OK).count();
        long conflict = outcomes.stream().filter(o -> o == Outcome.CONFLICT).count();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);

        // 恰好一个完整后态：三张计划都只旋转一次（版本都为 2），时隙仍为三段且互不重叠
        CapacitySwap s1 = swapRepo.findByKey(swap1).orElseThrow();
        CapacitySwap s2 = swapRepo.findByKey(swap2).orElseThrow();
        assertThat(List.of(s1.status().name(), s2.status().name()))
                .containsExactlyInAnyOrder("ACTIVE", "PREVIEW");
        for (Plan p : List.of(a, b, c)) {
            assertThat(planService.getPlan(p.key()).version()).isEqualTo(2);
            assertThat(planService.getPlan(p.key()).status()).isEqualTo("PUBLISHED");
        }
        // 每个原始时隙恰好被一张计划占用（完整后态，无丢失无重复）
        for (int hour : List.of(8, 10, 12)) {
            long count = planService.getPublishedSlots(DAY, section).stream()
                    .filter(s -> s.startUtc().equals(at(hour))).count();
            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    void swapAndPlainPublishRaceForReleasedSlot() throws Exception {
        String section = key("SEC");
        Plan a = publishOne("A", section, 8, 10);
        Plan b = publishOne("B", section, 10, 12);

        // 交换后 A 占用 10-12（B 释放的时隙）
        String swapKey = key("SWAP");
        createSwap(swapKey, List.of(
                item(a, 1, section, 8, 10, 10, 12),
                item(b, 1, section, 10, 12, 8, 10)));

        // 第三方草稿竞争 B 释放的 10-12
        String third = "SCH-TP-" + UUID.randomUUID();
        createDraft(third, "G-TP-" + UUID.randomUUID(), section, 10, 12);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> activate(swapKey),
                () -> {
                    try {
                        planService.publish(third, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        long activeCount = swapRepo.findByKey(swapKey).orElseThrow().status().name()
                .equals("ACTIVE") ? 1 : 0;
        boolean thirdPublished = planService.getPlan(third).status().equals("PUBLISHED");
        assertThat(activeCount == 1).isNotEqualTo(thirdPublished);

        // 10-12 时隙最终只有一张生效计划
        long count = planService.getPublishedSlots(DAY, section).stream()
                .filter(s -> s.startUtc().equals(at(10))).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentActivateSameSwapIsIdempotent() throws Exception {
        String section = key("SEC");
        Plan a = publishOne("A", section, 8, 10);
        Plan b = publishOne("B", section, 10, 12);
        String swapKey = key("SWAP");
        String requestKey = key("REQ");
        createSwap(swapKey, List.of(
                item(a, 1, section, 8, 10, 10, 12),
                item(b, 1, section, 10, 12, 8, 10)));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SwapResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return swapService.activate(swapKey, requestKey);
            }));
        }
        ready.await();
        go.countDown();
        List<SwapResponse> responses = new ArrayList<>();
        for (Future<SwapResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 所有线程拿到同一首次成功响应；版本只递增一次
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(planService.getPlan(a.key()).version()).isEqualTo(2);
        assertThat(planService.getPlan(b.key()).version()).isEqualTo(2);
        assertThat(swapRepo.findByKey(swapKey).orElseThrow().status().name()).isEqualTo("ACTIVE");
    }

    // ---------- 辅助 ----------

    private record Plan(String key, String train) {
    }

    private enum Outcome {
        OK,
        CONFLICT
    }

    private Outcome activate(String swapKey) {
        try {
            swapService.activate(swapKey, key("REQ"));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isIn(HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_ENTITY);
            return Outcome.CONFLICT;
        }
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

    private void createDraft(String scheduleKey, String train, String section,
                             int startHour, int endHour) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest(train, section,
                        at(startHour), at(endHour)))));
    }

    private Plan publishOne(String prefix, String section, int startHour, int endHour) {
        String scheduleKey = "SCH-" + prefix + "-" + UUID.randomUUID();
        String train = "TR-" + prefix + "-" + UUID.randomUUID();
        createDraft(scheduleKey, train, section, startHour, endHour);
        planService.publish(scheduleKey, key("REQ"));
        return new Plan(scheduleKey, train);
    }

    private void createSwap(String swapKey, List<SwapItemRequest> items) {
        swapService.createPreview(new SwapCreateRequest(key("REQ"), swapKey, DAY, items));
    }

    /**
     * 构造参与项：当前占用取计划发布时的真实占用（列车号/区段一致），目标仅改起止时刻。
     */
    private SwapItemRequest item(Plan plan, int version, String section,
                                 int curStart, int curEnd, int tgtStart, int tgtEnd) {
        return new SwapItemRequest(plan.key(), version,
                List.of(new OccupancyRequest(plan.train(), section, at(curStart), at(curEnd))),
                List.of(new OccupancyRequest(plan.train(), section, at(tgtStart), at(tgtEnd))));
    }

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
