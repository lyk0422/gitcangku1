package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.RollingStockService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.CreateRollingStockRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.RollingStockResponse;
import com.example.starter.plan.web.dto.UpdateTurnaroundRequest;
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
 * 车底交路并发测试：同一车底的并发发布不得产生矛盾衔接结论；
 * 周转参数并发修改按事务提交顺序裁决，仅一个成功。
 */
@SpringBootTest
class PlanTurnaroundConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService planService;

    @Autowired
    private RollingStockService stockService;

    @Test
    void concurrentPublishSameStockOverlappingSegmentsOnlyOneSucceeds() throws Exception {
        String stock = key("STK");
        stockService.createStock(new CreateRollingStockRequest(key("REQ"), stock, 30));
        // 两段始发时刻交错：无论谁先发布，另一段并入后间隔为负，必有一段 422
        String planA = key("SCH");
        String planB = key("SCH");
        createDraftWithStock(planA, stock, "S1", "S2", 8, 0, 9, 0);
        createDraftWithStock(planB, stock, "S2", "S3", 8, 10, 9, 10);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> publishOutcome(planA),
                () -> publishOutcome(planB)));

        // 串行化裁决：恰有一个发布成功，另一个因链不连续 422
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CHAIN_CONFLICT).count()).isEqualTo(1);

        // 最终链中只有一段，不存在两处矛盾的衔接结论
        assertThat(stockService.getChain(stock, DAY).segments()).hasSize(1);
    }

    @Test
    void concurrentTurnaroundUpdatesApplyInCommitOrder() throws Exception {
        String stock = key("STK");
        stockService.createStock(new CreateRollingStockRequest(key("REQ"), stock, 30));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> turnaroundOutcome(stock, 20),
                () -> turnaroundOutcome(stock, 25)));

        // 同一 expectedVersion=1 并发：恰一个成功，另一个 409 版本冲突
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.VERSION_CONFLICT).count())
                .isEqualTo(1);

        // 最终版本为 2：以 expectedVersion=2 再改应成功，证明只生效了一次
        RollingStockResponse again = stockService.updateTurnaround(stock,
                new UpdateTurnaroundRequest(key("REQ"), 2, 15));
        assertThat(again.version()).isEqualTo(3);
        assertThat(again.minTurnaroundMinutes()).isEqualTo(15);
    }

    @Test
    void concurrentCancelAndTurnaroundUpdateSerialized() throws Exception {
        String stock = key("STK");
        stockService.createStock(new CreateRollingStockRequest(key("REQ"), stock, 30));
        String planA = key("SCH");
        String planB = key("SCH");
        createDraftWithStock(planA, stock, "S1", "S2", 8, 0, 9, 0);
        createDraftWithStock(planB, stock, "S2", "S3", 9, 30, 10, 30);
        planService.publish(planA, key("REQ"));
        planService.publish(planB, key("REQ"));

        // 并发：取消首段（允许断链）与收紧周转参数到 40 分钟（间隔 30 不满足）
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        planService.cancel(planA, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        return Outcome.VERSION_CONFLICT;
                    }
                },
                () -> turnaroundOutcome(stock, 40)));

        // 按提交顺序裁决：取消先提交则周转修改重校验时链已断裂（单段无相邻对）→ 成功；
        // 周转修改先提交则因 A→B 间隔不足 422，取消仍成功。两种顺序下取消都必须成功。
        assertThat(outcomes.get(0)).isEqualTo(Outcome.OK);
        PlanResponse a = planService.getPlan(planA);
        assertThat(a.status()).isEqualTo("CANCELLED");
        if (outcomes.get(1) == Outcome.OK) {
            // 取消先提交：断链记录已写入，后续段待重排，周转修改在新链状态下成功
            assertThat(stockService.getBreaks(stock)).hasSize(1);
            assertThat(planService.getPlan(planB).chainState()).isEqualTo("NORMAL");
        } else {
            // 周转修改先提交：422 不生效，取消随后成功并写入断链记录
            assertThat(outcomes.get(1)).isEqualTo(Outcome.CHAIN_CONFLICT);
            assertThat(stockService.getBreaks(stock)).hasSize(1);
            assertThat(planService.getPlan(planB).chainState()).isEqualTo("PENDING_REPLAN");
        }
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CHAIN_CONFLICT,
        VERSION_CONFLICT
    }

    private Outcome publishOutcome(String scheduleKey) {
        try {
            planService.publish(scheduleKey, key("REQ"));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.code()).isEqualTo("CHAIN_LINK_CONFLICT");
            return Outcome.CHAIN_CONFLICT;
        }
    }

    private Outcome turnaroundOutcome(String stock, int minutes) {
        try {
            stockService.updateTurnaround(stock,
                    new UpdateTurnaroundRequest(key("REQ"), 1, minutes));
            return Outcome.OK;
        } catch (ApiException e) {
            if (e.status() == HttpStatus.CONFLICT) {
                assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
                return Outcome.VERSION_CONFLICT;
            }
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            return Outcome.CHAIN_CONFLICT;
        }
    }

    private void createDraftWithStock(String scheduleKey, String stock, String origin, String dest,
                                      int startHour, int startMinute, int endHour, int endMinute) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY, stock,
                origin, dest,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), key("SEC"),
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
