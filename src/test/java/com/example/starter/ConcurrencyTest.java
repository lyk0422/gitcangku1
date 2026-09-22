package com.example.starter;

import com.example.starter.api.dto.CreateReviewRequest;
import com.example.starter.api.dto.CreateRouteRequest;
import com.example.starter.api.dto.CreateZoneRequest;
import com.example.starter.api.dto.PointDto;
import com.example.starter.api.dto.ReplaceRouteRequest;
import com.example.starter.api.dto.ReviewResponse;
import com.example.starter.api.dto.ZoneResponse;
import com.example.starter.dao.AirspaceDao;
import com.example.starter.dao.RouteDao;
import com.example.starter.error.ConflictException;
import com.example.starter.service.ReviewService;
import com.example.starter.service.RouteService;
import com.example.starter.service.ZoneService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发与幂等边界测试：真实并发调用服务，验证版本一致性、键去重与快照隔离。
 */
class ConcurrencyTest extends IntegrationTestBase {

    @Autowired
    private ZoneService zoneService;

    @Autowired
    private RouteService routeService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private RouteDao routeDao;

    @Autowired
    private AirspaceDao airspaceDao;

    @Test
    void concurrentSameKeyZoneCreationReplaysSingleResult() throws Exception {
        CreateZoneRequest request = new CreateZoneRequest("cc-1", "zone-a", 0, 0, 10, 10);
        List<ZoneResponse> results = runConcurrently(
                () -> zoneService.create(request),
                () -> zoneService.create(request));
        // 两个并发同键同参请求都拿到同一成功结果，且只创建一次、版本只加一。
        assertEquals(results.get(0), results.get(1));
        assertEquals(1, countRows("zones"));
        assertEquals(1, airspaceVersion());
        assertEquals(1, countRows("idempotency_keys"));
    }

    @Test
    void concurrentZoneCreationsIncrementVersionAtomically() throws Exception {
        runConcurrently(
                () -> zoneService.create(new CreateZoneRequest("cc-2a", "zone-a", 0, 0, 10, 10)),
                () -> zoneService.create(new CreateZoneRequest("cc-2b", "zone-b", 20, 20, 30, 30)),
                () -> zoneService.create(new CreateZoneRequest("cc-2c", "zone-c", 40, 40, 50, 50)),
                () -> zoneService.create(new CreateZoneRequest("cc-2d", "zone-d", 60, 60, 70, 70)));
        // 全局版本无丢失更新：4 次变更后版本为 4。
        assertEquals(4, airspaceVersion());
        assertEquals(4, countRows("zones"));
    }

    @Test
    void concurrentRouteReplaceAllowsExactlyOneSuccess() throws Exception {
        routeService.create(new CreateRouteRequest("cc-3", "route-a",
                List.of(new PointDto(0, 0), new PointDto(10, 10))));
        List<Object> outcomes = runConcurrentlyAllowingConflict(
                () -> routeService.replace("route-a",
                        new ReplaceRouteRequest("cc-3a", 1,
                                List.of(new PointDto(0, 0), new PointDto(20, 20)))),
                () -> routeService.replace("route-a",
                        new ReplaceRouteRequest("cc-3b", 1,
                                List.of(new PointDto(0, 0), new PointDto(30, 30)))));
        long successes = outcomes.stream().filter(o -> !(o instanceof ConflictException)).count();
        long conflicts = outcomes.stream().filter(ConflictException.class::isInstance).count();
        assertEquals(1, successes);
        assertEquals(1, conflicts);
        assertEquals(2, routeDao.findById("route-a").orElseThrow().version());
    }

    @Test
    void concurrentReviewWithSameKeyYieldsSingleReview() throws Exception {
        routeService.create(new CreateRouteRequest("cc-4", "route-a",
                List.of(new PointDto(0, 0), new PointDto(10, 10))));
        CreateReviewRequest request = new CreateReviewRequest("cc-4r", "route-a", 1, 0L);
        List<ReviewResponse> results = runConcurrently(
                () -> reviewService.create(request),
                () -> reviewService.create(request));
        assertEquals(results.get(0).reviewId(), results.get(1).reviewId());
        assertEquals(1, countRows("reviews"));
    }

    @Test
    void zoneChangeConcurrentWithReviewNeverMixesNewVersionWithOldZones() throws Exception {
        // 每轮：读取当前空域版本 v，并发执行“以 v 提交审核”和“创建阻断性禁飞区”。
        // 审核与禁飞区变更在同一把版本行锁下串行，因此审核要么以 v 成功且结论为 CLEAR
        // （快照不含新区域），要么因版本变为 v+1 而 409；不得出现携带新版本却用旧区域的结论。
        for (int i = 0; i < 20; i++) {
            String suffix = "-" + i;
            routeService.create(new CreateRouteRequest("cc-5r" + suffix, "route" + suffix,
                    List.of(new PointDto(-5, 5), new PointDto(15, 5))));
            long versionBefore = airspaceDao.currentVersion();
            CreateReviewRequest reviewRequest = new CreateReviewRequest(
                    "cc-5v" + suffix, "route" + suffix, 1, versionBefore);
            CreateZoneRequest zoneRequest = new CreateZoneRequest(
                    "cc-5z" + suffix, "zone" + suffix, 0, 0, 10, 10);

            List<Object> outcomes = runConcurrentlyAllowingConflict(
                    () -> reviewService.create(reviewRequest),
                    () -> zoneService.create(zoneRequest));

            Object reviewOutcome = outcomes.get(0);
            Object zoneOutcome = outcomes.get(1);
            assertTrue(zoneOutcome instanceof ZoneResponse,
                    "禁飞区创建应成功，实际：" + zoneOutcome);
            if (reviewOutcome instanceof ReviewResponse review) {
                assertEquals(versionBefore, review.airspaceVersion(),
                        "审核成功时必须携带其快照对应的空域版本");
                // 快照不得包含本轮并发创建的新区域（其版本为 v+1）。
                assertTrue(!review.hitZoneIds().contains("zone" + suffix),
                        "携带版本 v 的结论不得使用 v+1 才生效的区域");
                // 此前各轮创建的区域均在快照内且均阻断航线，结论须与之严格一致。
                List<String> expectedHits = java.util.stream.IntStream.range(0, i)
                        .mapToObj(k -> "zone-" + k)
                        .sorted()
                        .toList();
                assertEquals(expectedHits, review.hitZoneIds());
                assertEquals(i == 0 ? "CLEAR" : "BLOCKED", review.conclusion());
            } else {
                assertTrue(reviewOutcome instanceof ConflictException,
                        "审核只允许成功或 409，实际：" + reviewOutcome);
            }
            // 禁飞区变更恰好使版本加一，无丢失更新。
            assertEquals(versionBefore + 1, airspaceDao.currentVersion());
        }
    }

    @SafeVarargs
    private final <T> List<T> runConcurrently(Callable<T>... tasks) throws Exception {
        return runConcurrentlyInternal(List.of(tasks), false);
    }

    @SafeVarargs
    private final List<Object> runConcurrentlyAllowingConflict(Callable<?>... tasks)
            throws Exception {
        return runConcurrentlyInternal(List.of(tasks), true);
    }

    private <T> List<T> runConcurrentlyInternal(List<? extends Callable<? extends T>> tasks,
                                                boolean allowConflict) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = tasks.stream()
                    .map(task -> executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        try {
                            return task.call();
                        } catch (ConflictException e) {
                            if (allowConflict) {
                                @SuppressWarnings("unchecked")
                                T conflict = (T) e;
                                return conflict;
                            }
                            throw e;
                        }
                    }))
                    .toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return futures.stream()
                    .map(future -> {
                        try {
                            return future.get(30, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException("并发任务失败", e);
                        }
                    })
                    .toList();
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
