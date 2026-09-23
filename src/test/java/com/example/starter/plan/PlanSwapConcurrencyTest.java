package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.SwapService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.CreateSwapRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.SwapItemRequest;
import com.example.starter.plan.web.dto.SwapResponse;
import com.example.starter.plan.web.dto.SwapSegmentRequest;
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
 * 容量交换并发与竞态测试：两个交换单并发激活同一组计划只产生一个完整后态、
 * 交换与取消并发按提交顺序裁决、同幂等键并发激活只生效一次。
 */
@SpringBootTest
class PlanSwapConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService planService;

    @Autowired
    private SwapService swapService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentActivatesOfTwoSwapsYieldSinglePostState() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createPublished(planA, section, 8, 9);
        createPublished(planB, section, 9, 10);

        // 交换单 1：A、B 互换；交换单 2：A→[10,11) B→[08,09)
        String swap1 = key("SWAP");
        preview(swap1, section, planA, planB, seg(section, 9, 10), seg(section, 8, 9));
        String swap2 = key("SWAP");
        preview(swap2, section, planA, planB, seg(section, 10, 11), seg(section, 8, 9));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> activate(swap1),
                () -> activate(swap2)));

        // 按数据库提交顺序只能出现一个完整后态：恰一个成功，另一个 409
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);

        PlanResponse a = planService.getPlan(planA);
        PlanResponse b = planService.getPlan(planB);
        assertThat(a.status()).isEqualTo("PUBLISHED");
        assertThat(b.status()).isEqualTo("PUBLISHED");
        assertThat(a.version()).isEqualTo(2);
        assertThat(b.version()).isEqualTo(2);
        // 最终占用必为某一个交换单的完整后态，不存在两单混合
        Instant aStart = a.occupancies().get(0).startUtc();
        Instant bStart = b.occupancies().get(0).startUtc();
        boolean swap1Won = aStart.equals(at(9)) && bStart.equals(at(8));
        boolean swap2Won = aStart.equals(at(10)) && bStart.equals(at(8));
        assertThat(swap1Won || swap2Won).isTrue();
    }

    @Test
    void concurrentActivateAndCancelApplyInCommitOrder() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createPublished(planA, section, 8, 9);
        createPublished(planB, section, 9, 10);

        String swapKey = key("SWAP");
        preview(swapKey, section, planA, planB, seg(section, 9, 10), seg(section, 8, 9));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> activate(swapKey),
                () -> {
                    try {
                        planService.cancel(planA, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        return Outcome.CONFLICT;
                    }
                }));

        PlanResponse a = planService.getPlan(planA);
        PlanResponse b = planService.getPlan(planB);
        if (outcomes.get(0) == Outcome.OK) {
            // 交换先提交：占用已轮换，随后取消仍可对已发布的 A 生效
            assertThat(a.occupancies().get(0).startUtc()).isEqualTo(at(9));
            assertThat(b.occupancies().get(0).startUtc()).isEqualTo(at(8));
            assertThat(a.version()).isEqualTo(2);
            assertThat(b.version()).isEqualTo(2);
        } else {
            // 取消先提交：交换 409 整体回滚，两个计划占用都保持原样
            assertThat(outcomes.get(1)).isEqualTo(Outcome.OK);
            assertThat(a.occupancies().get(0).startUtc()).isEqualTo(at(8));
            assertThat(b.occupancies().get(0).startUtc()).isEqualTo(at(9));
            assertThat(a.version()).isEqualTo(1);
            assertThat(b.version()).isEqualTo(1);
            assertThat(a.status()).isEqualTo("CANCELLED");
            assertThat(b.status()).isEqualTo("PUBLISHED");
        }
    }

    @Test
    void concurrentActivateSameRequestKeyActivatesOnce() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createPublished(planA, section, 8, 9);
        createPublished(planB, section, 9, 10);

        String swapKey = key("SWAP");
        preview(swapKey, section, planA, planB, seg(section, 9, 10), seg(section, 8, 9));

        String requestKey = key("REQ");
        int threads = 4;
        List<Callable<SwapResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> swapService.activate(swapKey, requestKey));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SwapResponse>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<SwapResponse> responses = new ArrayList<>();
        for (Future<SwapResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 全部返回同一激活结果，版本只递增一次
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(planService.getPlan(planA).version()).isEqualTo(2);
        assertThat(planService.getPlan(planB).version()).isEqualTo(2);
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE op_type = 'SWAP_ACTIVATE'"
                        + " AND request_key = ?",
                Integer.class, requestKey);
        assertThat(idemCount).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private Outcome activate(String swapKey) {
        try {
            swapService.activate(swapKey, key("REQ"));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            return Outcome.CONFLICT;
        }
    }

    private void createPublished(String scheduleKey, String section, int startHour, int endHour) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        at(startHour), at(endHour)))));
        planService.publish(scheduleKey, key("REQ"));
    }

    private void preview(String swapKey, String section, String planA, String planB,
                         SwapSegmentRequest targetA, SwapSegmentRequest targetB) {
        swapService.preview(new CreateSwapRequest(swapKey, DAY, List.of(
                new SwapItemRequest(planA, 1,
                        List.of(seg(section, 8, 9)), List.of(targetA)),
                new SwapItemRequest(planB, 1,
                        List.of(seg(section, 9, 10)), List.of(targetB)))));
    }

    private static SwapSegmentRequest seg(String section, int startHour, int endHour) {
        return new SwapSegmentRequest(section, at(startHour), at(endHour));
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

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
