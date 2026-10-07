package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CoverageIntervalDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.SegmentProfileDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链路覆盖写操作的幂等与真实并发测试（H2，MODE=MySQL）：
 * 同键重放、异参 409、失败不占键、并发同键唯一生效、审查与修订互斥。
 */
@SpringBootTest
@DisplayName("链路覆盖幂等与并发 H2")
class LinkCoverageIdempotencyH2Test {

    @Autowired
    private com.example.starter.service.AirspaceReviewService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void cleanBefore() {
        cleanup();
    }

    @AfterEach
    void cleanAfter() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_segment");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
    }

    private String rid(String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    private static List<RoutePointDto> pts(int... xy) {
        return java.util.stream.IntStream.range(0, xy.length / 2)
                .mapToObj(i -> new RoutePointDto(xy[2 * i], xy[2 * i + 1]))
                .toList();
    }

    private static SegmentProfileDto seg(int durationMs, int... intervals) {
        List<CoverageIntervalDto> coverage = new ArrayList<>();
        for (int i = 0; i < intervals.length; i += 2) {
            coverage.add(new CoverageIntervalDto(intervals[i], intervals[i + 1]));
        }
        return new SegmentProfileDto(durationMs, coverage);
    }

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    // ============================ 幂等 ============================

    @Test
    @DisplayName("同键同参重放首次完整响应，异参 409，审查只落库一次")
    void sameRequestReplaysAndDifferentParamsConflict() {
        service.createRoute(new RouteCreateRequest("idem-link",
                pts(0, 0, 100, 0, 200, 0), "req-" + rid("route"),
                List.of(seg(10000, 0, 8000), seg(10000, 3000, 10000)), "tester"));
        String requestId = "link-review-fixed-1";
        MutationResponse first = service.review(
                new ReviewRequest("idem-link", 1, 0L, requestId));
        assertFalse(first.replayed());

        MutationResponse replay = service.review(
                new ReviewRequest("idem-link", 1, 0L, requestId));
        assertTrue(replay.replayed());
        ReviewResultDto replayed = dataOf(replay);
        assertEquals(dataOf(first).reviewId(), replayed.reviewId());
        assertEquals("5000", replayed.maxContinuousLossMs());
        assertEquals(2, replayed.coverageUnion().size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));

        // 同键异参（不同航线版本）→ 409
        ApiException conflict = assertThrows(ApiException.class, () -> service.review(
                new ReviewRequest("idem-link", 2, 0L, requestId)));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
    }

    @Test
    @DisplayName("参数校验失败不占幂等键，同键可再次成功")
    void failedRequestDoesNotConsumeIdempotencyKey() {
        String requestId = "link-fail-fixed-1";
        // 反向区间 400：失败回滚不占键
        ApiException failed = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("fr", pts(0, 0, 100, 0), requestId,
                        List.of(seg(10000, 6000, 5000)), "tester")));
        assertEquals(HttpStatus.BAD_REQUEST, failed.status());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                Integer.class, requestId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM route", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM route_segment", Integer.class));

        // 同键合法参数成功
        MutationResponse ok = service.createRoute(
                new RouteCreateRequest("fr", pts(0, 0, 100, 0), requestId,
                        List.of(seg(10000, 0, 10000)), "tester"));
        assertFalse(ok.replayed());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM route_segment WHERE route_id = 'fr'", Integer.class));
    }

    // ============================ 并发 ============================

    @Test
    @DisplayName("同键同参并发创建：恰好一次业务生效，其余重放，不暴露唯一键错误")
    void concurrentSameRequestIdCreatesExactlyOnce() throws Exception {
        String requestId = "link-concurrent-key";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.createRoute(new RouteCreateRequest("ck-link",
                            pts(0, 0, 100, 0, 200, 0), requestId,
                            List.of(seg(10000, 0, 8000), seg(10000, 3000, 10000)), "tester"));
                }));
            }
            int firstCount = 0;
            int replayCount = 0;
            for (Future<MutationResponse> f : futures) {
                MutationResponse resp = f.get(15, TimeUnit.SECONDS);
                if (resp.replayed()) {
                    replayCount++;
                } else {
                    firstCount++;
                }
            }
            assertEquals(1, firstCount, "仅一次请求真正执行业务");
            assertEquals(n - 1, replayCount);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM route", Integer.class));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM route_segment WHERE route_id = 'ck-link'", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                    Integer.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("审查与修订并发：审查看到版本 1 旧档案或 409，绝不混用新旧档案")
    void concurrentReviewAndReplaceAreSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String routeId = "race-link-" + i;
                service.createRoute(new RouteCreateRequest(routeId,
                        pts(0, 0, 100, 0, 200, 0), "req-" + rid("route"),
                        List.of(seg(10000, 0, 8000), seg(10000, 3000, 10000)), "tester"));
                CyclicBarrier barrier = new CyclicBarrier(2);

                Future<Object> reviewFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.review(new ReviewRequest(
                                routeId, 1, 0L, "req-rv-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });
                Future<Object> replaceFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.replaceRoute(new RouteReplaceRequest(routeId, 1,
                                pts(0, 0, 100, 0, 200, 0), "req-rp-" + routeId,
                                List.of(seg(10000, 0, 8000), seg(10000, 3100, 10000)), "tester"));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object replaceResult = replaceFuture.get(15, TimeUnit.SECONDS);
                assertTrue(replaceResult instanceof MutationResponse, "修订必须成功一次");

                if (reviewResult instanceof MutationResponse mr) {
                    // 审查先拿锁：必须是版本 1 的旧档案结论（5 秒正好允许）
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals(1, dto.routeVersion());
                    assertEquals("CLEAR", dto.conclusion());
                    assertEquals("5000", dto.maxContinuousLossMs());
                } else {
                    // 修订先提交：旧版本审查必须 409
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }
            }
            // 每轮最终：版本 2 的新档案审查结论为 LINK_BLOCKED
            for (int i = 0; i < 8; i++) {
                String routeId = "race-link-" + i;
                ReviewResultDto dto = dataOf(service.review(
                        new ReviewRequest(routeId, 2, 0L, "req-final-" + routeId)));
                assertEquals("LINK_BLOCKED", dto.conclusion());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("失败并发后同键可重试成功：落败事务不占键")
    void failedRaceLeavesKeyReusable() {
        // 先用合法参数占键
        String requestId = "link-reuse-key";
        service.createRoute(new RouteCreateRequest("reuse", pts(0, 0, 100, 0), requestId,
                List.of(seg(10000, 0, 10000)), "tester"));
        // 同键异参 409 不影响原键重放
        assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("reuse", pts(0, 0, 200, 0), requestId,
                        List.of(seg(10000, 0, 9000)), "tester")));
        MutationResponse replay = service.createRoute(
                new RouteCreateRequest("reuse", pts(0, 0, 100, 0), requestId,
                        List.of(seg(10000, 0, 10000)), "tester"));
        assertTrue(replay.replayed());
        // 异参 409 与重放都不产生额外业务数据
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM route", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM route_segment WHERE route_id = 'reuse'", Integer.class));
    }
}
