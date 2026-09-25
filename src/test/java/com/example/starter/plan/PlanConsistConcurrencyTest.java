package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.TimeService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.AdjustPlatformRequest;
import com.example.starter.plan.web.dto.BatchPublishRequest;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.RegisterConsistRequest;
import com.example.starter.plan.web.dto.RegisterPlatformRequest;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 编组/站台/批量发布的并发提交顺序裁决与同键并发幂等测试（真实 H2 数据库 + 真实线程）。
 */
@SpringBootTest
class PlanConsistConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TimeService timeService;

    @BeforeEach
    void pinClock() {
        timeService.pin(DAY.minusDays(2).atStartOfDay(SH).toInstant());
    }

    @AfterEach
    void resetClock() {
        timeService.reset();
    }

    @Test
    void concurrentPlatformRegisterSameKeyReturnsFirstResult() throws Exception {
        String platformCode = key("PLT");
        String requestKey = key("REQ");
        int threads = 4;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.registerPlatform(
                    new RegisterPlatformRequest(requestKey, platformCode, 500)));
        }
        List<Object> responses = runConcurrently(tasks);
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_platform WHERE platform_code = ?",
                Integer.class, platformCode);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentConsistRegisterSameKeyReturnsFirstResult() throws Exception {
        String platform = key("PLT");
        service.registerPlatform(new RegisterPlatformRequest(key("REQ"), platform, 500));
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, key("SEC"), 8, 9);
        String requestKey = key("REQ");
        List<String> cars = List.of("C01", "C02");
        int threads = 4;

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.registerConsist(scheduleKey,
                    new RegisterConsistRequest(requestKey, 1, 400, platform, "op-1", cars)));
        }
        List<Object> responses = runConcurrently(tasks);
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        PlanResponse plan = service.getPlan(scheduleKey);
        assertThat(plan.version()).isEqualTo(2);
        Integer carCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_consist_car WHERE plan_id ="
                        + " (SELECT id FROM rail_day_plan WHERE schedule_key = ?)",
                Integer.class, scheduleKey);
        assertThat(carCount).isEqualTo(2);
    }

    @Test
    void concurrentConsistRegisterDifferentKeysOneWins() throws Exception {
        String platform = key("PLT");
        service.registerPlatform(new RegisterPlatformRequest(key("REQ"), platform, 500));
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, key("SEC"), 8, 9);

        // 两个不同 requestKey 都基于版本 1 并发改编组：恰好一个成功，另一个 409
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.registerConsist(scheduleKey, new RegisterConsistRequest(
                                key("REQ"), 1, 300, platform, "op-1", List.of("C01")));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        service.registerConsist(scheduleKey, new RegisterConsistRequest(
                                key("REQ"), 1, 400, platform, "op-2", List.of("C02")));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.OK, Outcome.CONFLICT);
        // 版本只递增一次
        assertThat(service.getPlan(scheduleKey).version()).isEqualTo(2);
    }

    @Test
    void concurrentPublishSamePlatformOnlyOneSucceeds() throws Exception {
        String platform = key("PLT");
        service.registerPlatform(new RegisterPlatformRequest(key("REQ"), platform, 500));
        String first = key("SCH");
        String second = key("SCH");
        createDraft(first, key("SEC"), 8, 9);
        createDraft(second, key("SEC"), 8, 9);
        service.registerConsist(first, new RegisterConsistRequest(
                key("REQ"), 1, 300, platform, "op-1", List.of("C01")));
        service.registerConsist(second, new RegisterConsistRequest(
                key("REQ"), 1, 300, platform, "op-1", List.of("C01")));

        List<Outcome> outcomes = runConcurrently(List.of(
                publishOutcome(first), publishOutcome(second)));

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.OK, Outcome.CONFLICT);
        long published = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_day_plan WHERE status = 'PUBLISHED'"
                        + " AND schedule_key IN (?, ?)",
                Long.class, first, second);
        assertThat(published).isEqualTo(1);
    }

    @Test
    void concurrentBatchPublishOverlappingBatchesOnlyOneBatchWins() throws Exception {
        String platform = key("PLT");
        service.registerPlatform(new RegisterPlatformRequest(key("REQ"), platform, 500));
        // 三个草稿：A、B 同站台时段重叠；批次1=[A,B]，批次2=[C]（C 与 A 同站台重叠）
        String a = key("SCH");
        String b = key("SCH");
        String c = key("SCH");
        createDraft(a, key("SEC"), 8, 10);
        createDraft(b, key("SEC"), 8, 9);
        createDraft(c, key("SEC"), 9, 11);
        for (String sk : List.of(a, b, c)) {
            service.registerConsist(sk, new RegisterConsistRequest(
                    key("REQ"), 1, 200, platform, "op-1", List.of("C01")));
        }

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.batchPublish(new BatchPublishRequest(
                                key("REQ"), List.of(a, b)));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        service.batchPublish(new BatchPublishRequest(
                                key("REQ"), List.of(c)));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }));

        // 含 A、B 同站台重叠的批次必然失败；C 批次取决于与失败事务的提交顺序：
        // 失败事务回滚不占用时隙，因此 C 必然成功（A 最终仍草稿）
        assertThat(outcomes.get(0)).isEqualTo(Outcome.CONFLICT);
        assertThat(outcomes.get(1)).isEqualTo(Outcome.OK);
        assertThat(service.getPlan(a).status()).isEqualTo("DRAFT");
        assertThat(service.getPlan(b).status()).isEqualTo("DRAFT");
        assertThat(service.getPlan(c).status()).isEqualTo("PUBLISHED");
    }

    @Test
    void concurrentShortenAndPublishArbitratedByCommitOrder() throws Exception {
        String platform = key("PLT");
        service.registerPlatform(new RegisterPlatformRequest(key("REQ"), platform, 500));
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, key("SEC"), 8, 9);
        service.registerConsist(scheduleKey, new RegisterConsistRequest(
                key("REQ"), 1, 400, platform, "op-1", List.of("C01")));

        // 两个事务都经全局发布锁串行：先提交者决定最终裁决
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    service.adjustPlatform(platform,
                            new AdjustPlatformRequest(key("REQ"), 1, 300));
                    return Outcome.OK;
                },
                () -> {
                    try {
                        service.publish(scheduleKey, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }));

        // 下调本身成功；发布要么先于下调成功（随后被同事务回查标记风险），
        // 要么晚于下调因超长 422，计划保持草稿
        assertThat(outcomes.get(0)).isEqualTo(Outcome.OK);
        PlanResponse plan = service.getPlan(scheduleKey);
        if (outcomes.get(1) == Outcome.OK) {
            assertThat(plan.status()).isEqualTo("PUBLISHED");
            assertThat(plan.platformRisk()).isTrue();
            assertThat(plan.riskSnapshot()).isNotNull();
            assertThat(plan.riskSnapshot().platformLengthSnapshot()).isEqualTo(500);
        } else {
            assertThat(plan.status()).isEqualTo("DRAFT");
            assertThat(plan.platformRisk()).isFalse();
        }
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private Callable<Outcome> publishOutcome(String scheduleKey) {
        return () -> {
            try {
                service.publish(scheduleKey, key("REQ"));
                return Outcome.OK;
            } catch (ApiException e) {
                assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                return Outcome.CONFLICT;
            }
        };
    }

    private void createDraft(String scheduleKey, String section, int startHour, int endHour) {
        service.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        at(startHour), at(endHour)))));
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
