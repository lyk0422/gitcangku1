package com.example.starter.airspace.service;

import com.example.starter.airspace.error.ApiException;
import com.example.starter.airspace.geom.Geometry.Point;
import com.example.starter.airspace.repo.AirspaceRepository;
import com.example.starter.airspace.service.AirspaceService.OperationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实并发下的 H2 数据库边界测试（MODE=MySQL，行锁与事务）：
 * <ol>
 *   <li>禁飞区创建/撤销与审核并发：成功结论必须与其声明空域版本下的有效区域集一致，
 *       不允许出现“携带新版本、使用旧区域”（或反之）的结论；</li>
 *   <li>同 requestId 并发：业务只执行一次，其余重放原成功结果；</li>
 *   <li>同 requestId 异参并发：至多一次成功，其余 409；</li>
 *   <li>航线替换与审核并发：版本判定与点列快照一致。</li>
 * </ol>
 */
@SpringBootTest
class AirspaceConcurrencyTest {

    @Autowired
    private AirspaceService service;
    @Autowired
    private AirspaceRepository repo;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private final List<Point> crossingPoints =
            List.of(new Point(-50, 5), new Point(50, 5));

    @BeforeEach
    void cleanTables() {
        jdbc.execute("DELETE FROM request_record");
        jdbc.execute("DELETE FROM route_review");
        jdbc.execute("DELETE FROM route_point");
        jdbc.execute("DELETE FROM route");
        jdbc.execute("DELETE FROM no_fly_zone");
        jdbc.execute("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
    }

    @Test
    void concurrentZoneChangesAndReviewsNeverMixVersions() throws Exception {
        String routeId = "route-concurrent";
        service.createRoute(UUID.randomUUID().toString(), routeId, crossingPoints);

        int writerPairs = 15;
        int writerThreads = 2;
        int reviewerThreads = 4;
        int reviewAttempts = 60;

        ExecutorService pool = Executors.newFixedThreadPool(writerThreads + reviewerThreads);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        // 区域写线程：反复 创建(固定矩形[0,0,10,10])→撤销，每次创建/撤销各使空域版本加一
        for (int w = 0; w < writerThreads; w++) {
            int writerIndex = w;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writerPairs; i++) {
                        String zoneId = "zone-w" + writerIndex + "-" + i + "-" + UUID.randomUUID();
                        service.createZone(UUID.randomUUID().toString(), zoneId, 0, 0, 10, 10);
                        service.revokeZone(UUID.randomUUID().toString(), zoneId);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }

        // 审核线程：提交前读取当时版本；409 为正常竞争失败；成功则结论必须与该版本区域集一致
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        for (int r = 0; r < reviewerThreads; r++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < reviewAttempts; i++) {
                        int airspaceVersion = repo.getGlobalVersion();
                        try {
                            OperationResult result = service.submitReview(
                                    UUID.randomUUID().toString(), routeId, 1, airspaceVersion);
                            successCount.incrementAndGet();
                            assertReviewConsistent(result.rawBody());
                        } catch (ApiException ex) {
                            if (ex.getStatus().value() == 409) {
                                conflictCount.incrementAndGet();
                            } else {
                                throw ex;
                            }
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(List.of(), errors, "并发线程不应出现异常");
        assertTrue(successCount.get() > 0, "应有审核在竞争中成功");
        assertTrue(conflictCount.get() > 0, "也应有审核因版本竞争返回409");

        // 两个写线程各 15 对 创建/撤销 => 60 次版本递增
        assertEquals(60, repo.getGlobalVersion());
    }

    /**
     * 复核成功审核：按其 airspaceVersion 从数据库重建当时有效区域集，
     * 结论（含全部命中 zoneId 的字典序去重集合）必须与重算结果完全一致。
     */
    private void assertReviewConsistent(String rawBody) throws Exception {
        JsonNode body = objectMapper.readTree(rawBody);
        int version = body.path("airspaceVersion").asInt();
        List<String> expectedHits = jdbc.queryForList(
                "SELECT zone_id FROM no_fly_zone "
                        + "WHERE created_version <= ? "
                        + "AND (revoked_version IS NULL OR revoked_version > ?) "
                        + "ORDER BY zone_id ASC",
                String.class, version, version);
        String expectedConclusion = expectedHits.isEmpty() ? "CLEAR" : "BLOCKED";
        assertEquals(expectedConclusion, body.path("conclusion").asText(),
                "结论与版本 " + version + " 的有效区域集不一致");

        List<String> actualHits = new ArrayList<>();
        body.path("hitZoneIds").forEach(n -> actualHits.add(n.asText()));
        assertEquals(expectedHits, actualHits, "命中 zoneId 集合必须完整、去重且字典序");
    }

    @Test
    void concurrentSameRequestIdExecutesOnceAndReplaysSameResult() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String zoneId = "zone-idem-" + UUID.randomUUID();
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OperationResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.createZone(requestId, zoneId, 0, 0, 10, 10);
            }));
        }
        start.countDown();

        List<String> bodies = new ArrayList<>();
        for (Future<OperationResult> f : futures) {
            OperationResult result = f.get(30, TimeUnit.SECONDS);
            assertEquals(201, result.statusCode());
            bodies.add(result.rawBody());
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // 所有并发调用返回完全相同的原成功结果
        assertEquals(1, bodies.stream().distinct().count());
        // 业务只执行一次
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM no_fly_zone WHERE zone_id = ?",
                Integer.class, zoneId));
        assertEquals(1, repo.getGlobalVersion());
    }

    @Test
    void concurrentSameRequestIdDifferentParamsAtMostOneSuccess() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String zoneId = "zone-idem-mix-" + UUID.randomUUID();
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            int upper = 10 + i; // 每个线程参数不同
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    service.createZone(requestId, zoneId, 0, 0, upper, 10);
                    success.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409
                            && "IDEMPOTENCY_PARAM_MISMATCH".equals(ex.getError())) {
                        conflict.incrementAndGet();
                    } else {
                        errors.add(ex);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(List.of(), errors);
        assertEquals(1, success.get(), "同键异参并发只允许一个成功");
        assertEquals(threads - 1, conflict.get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM no_fly_zone WHERE zone_id = ?",
                Integer.class, zoneId));
    }

    @Test
    void concurrentRouteReplaceAndReviewKeepVersionConsistency() throws Exception {
        String routeId = "route-replace-race";
        service.createRoute(UUID.randomUUID().toString(), routeId, crossingPoints);
        // v1 航线下没有禁飞区
        List<Point> v2Points = List.of(new Point(-50, 50), new Point(50, 50));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger replaceOk = new AtomicInteger();
        AtomicInteger replaceConflict = new AtomicInteger();
        AtomicInteger reviewOk = new AtomicInteger();
        AtomicInteger reviewConflict = new AtomicInteger();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 3; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    service.replaceRoute(UUID.randomUUID().toString(), routeId, 1, v2Points);
                    replaceOk.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        replaceConflict.incrementAndGet();
                    } else {
                        errors.add(ex);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        for (int i = 0; i < 3; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int attempt = 0; attempt < 20; attempt++) {
                        try {
                            // 空域版本始终为 0；航线版本提交 1，替换成功后必然 409
                            service.submitReview(UUID.randomUUID().toString(), routeId, 1, 0);
                            reviewOk.incrementAndGet();
                        } catch (ApiException ex) {
                            if (ex.getStatus().value() == 409) {
                                reviewConflict.incrementAndGet();
                            } else {
                                throw ex;
                            }
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(List.of(), errors);
        assertEquals(1, replaceOk.get(), "expectedVersion=1 的替换只能成功一次");
        assertEquals(2, replaceConflict.get());
        assertTrue(reviewOk.get() > 0);
        assertTrue(reviewConflict.get() > 0);
        assertEquals(2, repo.findRoute(routeId).version());

        // 所有成功的 v1 审核必须基于 v1 点列（无禁飞区 ⇒ CLEAR）
        List<Integer> badConclusions = jdbc.queryForList(
                "SELECT COUNT(*) FROM route_review WHERE route_version = 1 AND conclusion <> 'CLEAR'",
                Integer.class);
        assertEquals(0, badConclusions.get(0));
    }
}
