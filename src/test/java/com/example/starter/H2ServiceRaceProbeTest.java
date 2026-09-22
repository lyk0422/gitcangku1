package com.example.starter;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 禁飞区创建与审核真实并发测试（H2，MODE=MySQL）。
 *
 * <p>每组协调真实线程同时发起，断言每次只可能出现两种合法结果：
 * 审核先于建区提交 → CLEAR 且携带建区前空域版本；
 * 建区先于审核提交 → 409 VERSION_CONFLICT。
 * 绝不允许“旧空域版本 + 命中新区域的 BLOCKED”这类不一致结论。</p>
 */
@SpringBootTest
@DisplayName("建区与审核并发：不产生版本/区域错配结论")
class H2ServiceRaceProbeTest {

    @Autowired
    private com.example.starter.service.AirspaceReviewService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("同时起跑：结果只能是旧快照 CLEAR 或 409，无脏 BLOCKED")
    void simultaneousStartNeverMixesVersionsAndZones() throws Exception {
        runRounds(20, false);
    }

    @Test
    @DisplayName("建区先提交后审核并发发起：旧版本审核必须 409")
    void zoneCommitsFirstThenReviewConflicts() throws Exception {
        runRounds(10, true);
    }

    private void runRounds(int iterations, boolean zoneFirst) throws Exception {
        int clearByOldSnapshot = 0;
        int conflictByCommittedZone = 0;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < iterations; i++) {
                int iter = i;
                cleanup();
                String routeId = "pr" + iter;
                String zoneId = "pz" + iter;
                service.createRoute(new RouteCreateRequest(routeId,
                        List.of(new RoutePointDto(0, 10), new RoutePointDto(100, 10)),
                        "preq-route-" + iter));

                Future<Object> zoneFuture;
                Future<Object> reviewFuture;
                if (zoneFirst) {
                    // 建区先完整提交，再立即用旧空域版本发起审核
                    Object zr = service.createZone(new ZoneCreateRequest(
                            zoneId, 40, 5, 60, 15, "preq-zone-" + iter));
                    assertTrue(zr instanceof MutationResponse);
                    CountDownLatch done = new CountDownLatch(1);
                    reviewFuture = pool.submit(() -> {
                        done.countDown();
                        try {
                            return service.review(
                                    new ReviewRequest(routeId, 1, 0L, "preq-rv-" + iter));
                        } catch (ApiException ex) {
                            return ex;
                        }
                    });
                    done.await(5, TimeUnit.SECONDS);
                    zoneFuture = java.util.concurrent.CompletableFuture.completedFuture(zr);
                } else {
                    CyclicBarrier barrier = new CyclicBarrier(2);
                    reviewFuture = pool.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            return service.review(
                                    new ReviewRequest(routeId, 1, 0L, "preq-rv-" + iter));
                        } catch (ApiException ex) {
                            return ex;
                        }
                    });
                    zoneFuture = pool.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            return service.createZone(new ZoneCreateRequest(
                                    zoneId, 40, 5, 60, 15, "preq-zone-" + iter));
                        } catch (ApiException ex) {
                            return ex;
                        }
                    });
                }

                Object reviewResult = reviewFuture.get(15, TimeUnit.SECONDS);
                Object zoneResult = zoneFuture.get(15, TimeUnit.SECONDS);
                assertTrue(zoneResult instanceof MutationResponse, "建区必须成功一次");

                if (reviewResult instanceof MutationResponse mr) {
                    // 审核先提交：看到的是建区前一致状态，只能 CLEAR 且版本为 0
                    ReviewResultDto dto = objectMapper.convertValue(mr.data(), ReviewResultDto.class);
                    assertEquals("CLEAR", dto.conclusion(),
                            "旧空域版本审核不得使用并发新建区域判定");
                    assertEquals(0L, dto.airspaceVersion());
                    clearByOldSnapshot++;
                } else {
                    // 建区先提交：版本推进到 1，旧版本审核必须 409
                    ApiException ex = (ApiException) reviewResult;
                    assertEquals(HttpStatus.CONFLICT, ex.status());
                    conflictByCommittedZone++;
                }
            }
        } finally {
            pool.shutdownNow();
            cleanup();
        }
        if (zoneFirst) {
            assertEquals(iterations, conflictByCommittedZone,
                    "建区先提交时，全部旧版本审核都必须 409");
        } else {
            assertEquals(iterations, clearByOldSnapshot + conflictByCommittedZone);
        }
    }

    private void cleanup() {
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM request_dedup");
        jdbc.update("DELETE FROM route_point");
        jdbc.update("DELETE FROM route");
        jdbc.update("DELETE FROM no_fly_zone");
        jdbc.update("UPDATE airspace_meta SET global_version = 0 WHERE id = 1");
        jdbc.update("UPDATE coord_lock SET touched = 0 WHERE id = 1");
    }
}
