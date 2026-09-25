package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.PlatformService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ConsistUpdateRequest;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PlatformCreateRequest;
import com.example.starter.plan.web.dto.PlatformLengthRequest;
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
 * 编组与站台并发裁决测试（H2 内存库）：并发编组变更按提交顺序生效、
 * 同键并发重放只占一键、发布与站台长度下调经全局锁串行化且结果一致。
 */
@SpringBootTest
class ConsistConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    /** 未来运营日（相对真实时钟），保证站台下调回查命中。 */
    private static final LocalDate DAY = LocalDate.now(SH).plusDays(30);

    @Autowired
    private PlanService service;

    @Autowired
    private PlatformService platformService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentConsistUpdatesOnlyOneWins() throws Exception {
        String platform = key("PLA");
        platformService.createPlatform(new PlatformCreateRequest(key("REQ"), "op-1", platform, 10));
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, 8, 9);

        ConsistUpdateRequest reqA = new ConsistUpdateRequest(key("REQ"), "op-1", 1, 2,
                List.of("1", "2"), List.of(platform));
        ConsistUpdateRequest reqB = new ConsistUpdateRequest(key("REQ"), "op-2", 1, 3,
                List.of("1", "2", "3"), List.of(platform));
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.updateConsist(scheduleKey, reqA);
                        return Outcome.OK_A;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        service.updateConsist(scheduleKey, reqB);
                        return Outcome.OK_B;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
                        return Outcome.CONFLICT;
                    }
                }));

        // 恰好一个成功，版本只加一，落库编组与胜者一致
        long okCount = outcomes.stream().filter(o -> o != Outcome.CONFLICT).count();
        assertThat(okCount).isEqualTo(1);
        assertThat(outcomes).contains(Outcome.CONFLICT);
        PlanResponse plan = service.getPlan(scheduleKey);
        assertThat(plan.version()).isEqualTo(2);
        if (outcomes.get(0) == Outcome.OK_A) {
            assertThat(plan.consistLength()).isEqualTo(2);
            assertThat(plan.cars()).containsExactly("1", "2");
        } else {
            assertThat(plan.consistLength()).isEqualTo(3);
            assertThat(plan.cars()).containsExactly("1", "2", "3");
        }
    }

    @Test
    void concurrentSameConsistRequestKeyReplaysOnce() throws Exception {
        String platform = key("PLA");
        platformService.createPlatform(new PlatformCreateRequest(key("REQ"), "op-1", platform, 10));
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, 8, 9);
        String requestKey = key("REQ");
        int threads = 4;

        List<Callable<PlanResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.updateConsist(scheduleKey,
                    new ConsistUpdateRequest(requestKey, "op-1", 1, 2,
                            List.of("2", "1"), List.of(platform))));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PlanResponse>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<PlanResponse> responses = new ArrayList<>();
        for (Future<PlanResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 全部返回首次结果，版本只加一，幂等记录仅一条
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        PlanResponse plan = service.getPlan(scheduleKey);
        assertThat(plan.version()).isEqualTo(2);
        assertThat(plan.cars()).containsExactly("1", "2");
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE op_type = 'CONSIST'"
                        + " AND request_key = ?",
                Integer.class, requestKey);
        assertThat(idemCount).isEqualTo(1);
    }

    @Test
    void concurrentPublishAndDowngradeAreSerialized() throws Exception {
        String platform = key("PLA");
        platformService.createPlatform(new PlatformCreateRequest(key("REQ"), "op-1", platform, 6));
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, 8, 9);
        service.updateConsist(scheduleKey, new ConsistUpdateRequest(key("REQ"), "op-1", 1, 6,
                List.of("1", "2", "3", "4", "5", "6"), List.of(platform)));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.publish(scheduleKey, key("REQ"));
                        return Outcome.OK_A;
                    } catch (ApiException e) {
                        // 下调先提交时发布应得 422 站台复核冲突
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.code()).isEqualTo("PLATFORM_CONFLICT");
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    platformService.adjustLength(platform,
                            new PlatformLengthRequest(key("REQ"), "op-1", 5));
                    return Outcome.OK_B;
                }));

        // 下调必须成功；发布与下调按提交顺序裁决，最终状态自洽
        assertThat(outcomes.get(1)).isEqualTo(Outcome.OK_B);
        PlanResponse plan = service.getPlan(scheduleKey);
        if (outcomes.get(0) == Outcome.OK_A) {
            // 发布先生效：计划已发布，下调回查命中并标记 PLATFORM_RISK（不自动取消）
            assertThat(plan.status()).isEqualTo("PUBLISHED");
            assertThat(plan.platformRisk()).isTrue();
            assertThat(service.getPlanRisks(scheduleKey)).hasSize(1);
            assertThat(service.getPlanRisks(scheduleKey).get(0).previousLength()).isEqualTo(6);
            assertThat(service.getPlanRisks(scheduleKey).get(0).resolvedAt()).isNull();
        } else {
            // 下调先生效：发布被门禁拦截，计划保持草稿且无风险标记
            assertThat(plan.status()).isEqualTo("DRAFT");
            assertThat(plan.platformRisk()).isFalse();
            assertThat(service.getPlanRisks(scheduleKey)).isEmpty();
        }
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK_A,
        OK_B,
        CONFLICT
    }

    private void createDraft(String scheduleKey, int startHour, int endHour) {
        service.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), key("SEC"),
                        at(startHour), at(endHour)))));
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
