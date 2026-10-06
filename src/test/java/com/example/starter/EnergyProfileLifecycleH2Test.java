package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.EnergyProfileDto;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能量档案生命周期测试：修订使当前审查 STALE、历史审查冻结、
 * 幂等重放与并发替换/审查互斥（H2，MODE=MySQL）。
 */
@SpringBootTest
class EnergyProfileLifecycleH2Test {

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
        jdbc.update("DELETE FROM route_segment_energy");
        jdbc.update("DELETE FROM route_alternate_energy");
        jdbc.update("DELETE FROM route_energy_profile");
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

    private ReviewResultDto dataOf(MutationResponse resp) {
        return objectMapper.convertValue(resp.data(), ReviewResultDto.class);
    }

    private static EnergyProfileDto insufficientProfile() {
        // 出发 100、保留 10、两段各 30、终点备降 40 → 终点需要 110，不足
        return new EnergyProfileDto(100L, 10L, List.of(30L, 30L), List.of(0L, 0L, 40L));
    }

    private static EnergyProfileDto sufficientProfile() {
        // 终点备降 30 → 终点需要 100，合格
        return new EnergyProfileDto(100L, 10L, List.of(30L, 30L), List.of(0L, 0L, 30L));
    }

    // ============================ STALE 与历史冻结 ============================

    @Test
    void profileChangeMakesCurrentStaleAndHistoryFrozen() {
        service.createRoute(new RouteCreateRequest("s1", pts(0, 0, 50, 0, 100, 0),
                insufficientProfile(), "tester", "req-" + rid("route")));
        ReviewResultDto blocked = dataOf(service.review(
                new ReviewRequest("s1", 1, 0L, "req-" + rid("review"))));
        assertEquals("ENERGY_BLOCKED", blocked.conclusion());
        assertEquals(10L, blocked.energyCheck().deficit());
        assertEquals("ENERGY_BLOCKED", service.getCurrentReview("s1").conclusion());

        // 档案变化通过航线修订生成新版本：当前旧审查 STALE
        service.replaceRoute(new RouteReplaceRequest("s1", 1, pts(0, 0, 50, 0, 100, 0),
                sufficientProfile(), "tester", "req-" + rid("replace")));
        ReviewResultDto stale = service.getCurrentReview("s1");
        assertEquals("STALE", stale.conclusion());
        assertFalse(stale.current());

        // 历史审查不随新档案更新：结论与能量快照冻结
        ReviewResultDto historical = service.getReview(blocked.reviewId());
        assertEquals("ENERGY_BLOCKED", historical.conclusion());
        assertEquals(10L, historical.energyCheck().deficit());
        assertEquals(2, historical.energyCheck().blockedPointSeq());
        assertNull(historical.current());

        // 新版本重新审查：能量合格且几何 CLEAR
        ReviewResultDto cleared = dataOf(service.review(
                new ReviewRequest("s1", 2, 0L, "req-" + rid("review"))));
        assertEquals("CLEAR", cleared.conclusion());
        assertTrue(cleared.energyCheck().sufficient());
        assertEquals("CLEAR", service.getCurrentReview("s1").conclusion());
    }

    @Test
    void replaceWithoutProfileDropsEnergyCheck() {
        // 旧版本有档案；修订不携带档案 → 新版本按旧契约审查（无能量判定）
        service.createRoute(new RouteCreateRequest("s2", pts(0, 0, 50, 0, 100, 0),
                insufficientProfile(), "tester", "req-" + rid("route")));
        service.replaceRoute(new RouteReplaceRequest("s2", 1, pts(0, 0, 50, 0, 100, 0),
                "req-" + rid("replace")));
        ReviewResultDto dto = dataOf(service.review(
                new ReviewRequest("s2", 2, 0L, "req-" + rid("review"))));
        assertEquals("CLEAR", dto.conclusion());
        assertNull(dto.energyCheck());
    }

    // ============================ 幂等 ============================

    @Test
    void sameRequestReplaysAndDifferentParamsConflict() {
        String requestId = "idem-energy-1";
        RouteCreateRequest create = new RouteCreateRequest("s3", pts(0, 0, 50, 0, 100, 0),
                insufficientProfile(), "tester", requestId);
        MutationResponse first = service.createRoute(create);
        assertFalse(first.replayed());

        MutationResponse replay = service.createRoute(create);
        assertTrue(replay.replayed());

        // 同键异参（不同档案）→ 409
        ApiException ex = assertThrows(ApiException.class, () -> service.createRoute(
                new RouteCreateRequest("s3", pts(0, 0, 50, 0, 100, 0),
                        sufficientProfile(), "tester", requestId)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("IDEMPOTENT_PARAM_MISMATCH", ex.code());
    }

    @Test
    void reviewReplayKeepsFrozenEnergySnapshot() {
        service.createRoute(new RouteCreateRequest("s4", pts(0, 0, 50, 0, 100, 0),
                insufficientProfile(), "tester", "req-" + rid("route")));
        String requestId = "idem-review-energy";
        MutationResponse first = service.review(new ReviewRequest("s4", 1, 0L, requestId));
        MutationResponse replay = service.review(new ReviewRequest("s4", 1, 0L, requestId));
        assertTrue(replay.replayed());
        ReviewResultDto dto = dataOf(replay);
        assertEquals("ENERGY_BLOCKED", dto.conclusion());
        assertEquals(dataOf(first).reviewId(), dto.reviewId());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM review WHERE route_id = 's4'", Integer.class));
    }

    // ============================ 并发 ============================

    @Test
    void concurrentReplaceAndReviewIsSerialized() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                String routeId = "cr" + i;
                service.createRoute(new RouteCreateRequest(routeId, pts(0, 0, 50, 0, 100, 0),
                        insufficientProfile(), "tester", "req-" + rid("route")));
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
                        return service.replaceRoute(new RouteReplaceRequest(
                                routeId, 1, pts(0, 0, 50, 0, 100, 0),
                                sufficientProfile(), "tester", "req-rp-" + routeId));
                    } catch (ApiException ex) {
                        return ex;
                    }
                });

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object replaceResult = replaceFuture.get(15, TimeUnit.SECONDS);
                assertTrue(replaceResult instanceof MutationResponse, "替换必须成功一次");

                if (reviewResult instanceof MutationResponse mr) {
                    // 审查先拿锁：必须是版本 1 的旧档案结论（能量不足）
                    ReviewResultDto dto = dataOf(mr);
                    assertEquals(1, dto.routeVersion());
                    assertEquals("ENERGY_BLOCKED", dto.conclusion());
                    assertNotNull(dto.energyCheck());
                    assertEquals(2, dto.energyCheck().blockedPointSeq());
                } else {
                    // 替换先提交：旧版本审查必须 409
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) reviewResult).status());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
