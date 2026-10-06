package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.CoverageIntervalDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.SegmentLinkDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通信档案航线的幂等与真实并发 H2 测试（MODE=MySQL）：
 * 同键重放、异参 409、同键并发恰好一次生效、审核与替换互斥。
 */
@SpringBootTest
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
        jdbc.update("DELETE FROM route_segment_coverage");
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

    private static SegmentLinkDto seg(long durationMs, int... fromTo) {
        List<CoverageIntervalDto> coverage = new ArrayList<>();
        for (int i = 0; i < fromTo.length; i += 2) {
            coverage.add(new CoverageIntervalDto(fromTo[i], fromTo[i + 1]));
        }
        return new SegmentLinkDto(durationMs, coverage);
    }

    private RouteCreateRequest profileRoute(String routeId, int cov2From, String requestId) {
        return new RouteCreateRequest(routeId, pts(0, 0, 0, 10, 0, 20),
                List.of(seg(10000, 0, 8000), seg(10000, cov2From, 10000)),
                "tester", requestId);
    }

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    // ============================ 幂等 ============================

    @Test
    void reviewReplayReturnsOriginalLinkBlockedResponse() {
        service.createRoute(profileRoute("li1", 3100, "req-" + rid("route")));
        String requestId = "lk-review-replay";
        MutationResponse first = service.review(new ReviewRequest("li1", 1, 0L, requestId));
        assertEquals("LINK_BLOCKED", dataOf(first).conclusion());

        MutationResponse replay = service.review(new ReviewRequest("li1", 1, 0L, requestId));
        assertTrue(replay.replayed());
        ReviewResultDto dto = dataOf(replay);
        // 重放首次完整响应：结论、reviewId、并集与跨段缺口完全一致
        assertEquals("LINK_BLOCKED", dto.conclusion());
        assertEquals(dataOf(first).reviewId(), dto.reviewId());
        assertEquals(dataOf(first).linkUnion(), dto.linkUnion());
        assertEquals(dataOf(first).linkGaps(), dto.linkGaps());
        assertEquals("5100", dto.linkGaps().get(0).lossMillis());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review", Integer.class));
    }

    @Test
    void sameKeyWithDifferentParamsConflicts() {
        String requestId = "lk-create-key";
        service.createRoute(profileRoute("li2", 3000, requestId));
        // 同键异参（覆盖不同）→ 409
        ApiException diffParams = assertThrows(ApiException.class,
                () -> service.createRoute(profileRoute("li2", 3100, requestId)));
        assertEquals(HttpStatus.CONFLICT, diffParams.status());
        // 同键异种操作 → 409
        ApiException diffKind = assertThrows(ApiException.class, () -> service.replaceRoute(
                new RouteReplaceRequest("li2", 1, pts(0, 0, 0, 10, 0, 20),
                        List.of(seg(10000, 0, 8000), seg(10000, 3000, 10000)),
                        "tester", requestId)));
        assertEquals(HttpStatus.CONFLICT, diffKind.status());
    }

    @Test
    void concurrentSameRequestIdCreatesRouteExactlyOnce() throws Exception {
        String requestId = "lk-concurrent-create";
        int n = 6;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<MutationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.createRoute(profileRoute("li3", 3000, requestId));
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
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM route WHERE route_id = 'li3'", Integer.class));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM route_segment WHERE route_id = 'li3'", Integer.class));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM route_segment_coverage WHERE route_id = 'li3'",
                    Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_dedup WHERE request_id = ?",
                    Integer.class, requestId));
        } finally {
            pool.shutdownNow();
        }
    }

    // ============================ 并发：审核与替换互斥 ============================

    @Test
    void concurrentReplaceAndReviewWithProfileIsSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String routeId = "lc-" + i;
                service.createRoute(profileRoute(routeId, 3000, "req-" + rid("route")));
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
                                pts(0, 0, 0, 10, 0, 20),
                                List.of(seg(10000, 0, 8000), seg(10000, 3100, 10000)),
                                "tester", "req-rp-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object replaceResult = replaceFuture.get(15, TimeUnit.SECONDS);
                assertTrue(replaceResult instanceof MutationResponse, "替换必须成功一次");

                if (reviewResult instanceof MutationResponse mr) {
                    // 审核先拿锁：必须按 v1 档案（70%）得出 CLEAR，缺口 5000ms
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals(1, dto.routeVersion());
                    assertEquals("CLEAR", dto.conclusion());
                    assertEquals("5000", dto.linkGaps().get(0).lossMillis());
                } else {
                    // 替换先提交：v1 审核必须 409，不能产生任何结论
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }
                // 替换后当前结论失效；用 v2 审查必须按新档案（69%）得出 LINK_BLOCKED
                assertEquals("STALE", service.getCurrentReview(routeId).conclusion());
                MutationResponse v2 = service.review(new ReviewRequest(
                        routeId, 2, 0L, "req-rv2-" + routeId));
                assertEquals("LINK_BLOCKED", dataOf(v2).conclusion());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
