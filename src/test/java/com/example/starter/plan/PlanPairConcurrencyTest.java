package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanPairResponse;
import com.example.starter.plan.web.dto.PublishPairRequest;
import com.example.starter.plan.web.dto.PublishedSlotView;
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
 * 夜间计划对并发测试：两个计划对争抢同一跨零点区段最多一个成功；
 * 同键并发联合发布重放首次结果；并发下任何查询都观察不到只发布一张的中间状态。
 */
@SpringBootTest
class PlanPairConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 24);

    @Autowired
    private PlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPairPublishSameOvernightSectionOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        int pairs = 3;
        List<String> pairKeys = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            String pairKey = key("NP");
            // 首计划：同一跨零点区段 22:00 → 次日 06:00；次日计划：各自独立区段
            service.createDraft(new CreatePlanRequest(key("REQ"), key("SCH"), DAY, true, pairKey,
                    List.of(new OccupancyRequest("G1-" + i, section, at(DAY, 22),
                            at(DAY.plusDays(1), 6)))));
            service.createDraft(new CreatePlanRequest(key("REQ"), key("SCH"), DAY.plusDays(1),
                    false, pairKey,
                    List.of(new OccupancyRequest("G2-" + i, key("SEC"),
                            at(DAY.plusDays(1), 6), at(DAY.plusDays(1), 8)))));
            pairKeys.add(pairKey);
        }

        List<Outcome> outcomes = runConcurrently(pairKeys.stream()
                .<Callable<Outcome>>map(pk -> () -> {
                    try {
                        service.publishPair(new PublishPairRequest(key("REQ"), pk, 1, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.code()).isEqualTo("SLOT_CONFLICT");
                        return Outcome.CONFLICT;
                    }
                }).toList());

        // 同一跨零点区段最多一个计划对发布成功
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(pairs - 1);

        // 最终该区段跨零点时隙只有一张计划生效（当日与次日查询一致）
        List<PublishedSlotView> daySlots = service.getPublishedSlots(DAY, section);
        assertThat(daySlots).hasSize(1);
        List<PublishedSlotView> nextSlots = service.getPublishedSlots(DAY.plusDays(1), section);
        assertThat(nextSlots).hasSize(1);
        assertThat(nextSlots.get(0).scheduleKey()).isEqualTo(daySlots.get(0).scheduleKey());

        // 不存在只发布一张的计划对（逐对核验两张成员状态一致）
        for (String pairKey : pairKeys) {
            List<String> statuses = jdbc.queryForList(
                    "SELECT status FROM rail_day_plan WHERE night_pair_key = ?",
                    String.class, pairKey);
            assertThat(statuses).hasSize(2);
            assertThat(statuses).allMatch(s -> s.equals(statuses.get(0)));
        }
    }

    @Test
    void concurrentPairPublishSameRequestKeyReplaysFirstResult() throws Exception {
        String section = key("SEC");
        String pairKey = key("NP");
        String first = key("SCH");
        String second = key("SCH");
        service.createDraft(new CreatePlanRequest(key("REQ"), first, DAY, true, pairKey,
                List.of(new OccupancyRequest("G1", section, at(DAY, 22),
                        at(DAY.plusDays(1), 6)))));
        service.createDraft(new CreatePlanRequest(key("REQ"), second, DAY.plusDays(1), false,
                pairKey,
                List.of(new OccupancyRequest("G2", key("SEC"), at(DAY.plusDays(1), 6),
                        at(DAY.plusDays(1), 8)))));

        String requestKey = key("REQ");
        int threads = 4;
        List<Callable<PlanPairResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.publishPair(new PublishPairRequest(requestKey, pairKey, 1, 1)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PlanPairResponse>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<PlanPairResponse> responses = new ArrayList<>();
        for (Future<PlanPairResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 全部返回首次结果，计划对记录仅一条，两张计划各发布一次
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(responses.get(0).firstPlan().status()).isEqualTo("PUBLISHED");
        assertThat(responses.get(0).secondPlan().status()).isEqualTo("PUBLISHED");
        Integer pairCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_night_pair WHERE night_pair_key = ?",
                Integer.class, pairKey);
        assertThat(pairCount).isEqualTo(1);
        assertThat(service.getPlan(first).version()).isEqualTo(1);
        assertThat(service.getPlan(second).version()).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
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
