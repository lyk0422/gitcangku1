package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.RouteService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.CreateRouteRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.RouteResponse;
import com.example.starter.plan.web.dto.UpdateRouteMatrixRequest;
import com.example.starter.plan.web.dto.ConflictPairRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 进路互斥并发测试：矩阵更新与发布竞争不留下冲突占用；同 requestId 并发注册进路
 * 只生效一次且全部返回首次响应。
 */
@SpringBootTest
class RouteConflictConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService planService;

    @Autowired
    private RouteService routeService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanRouteTables() {
        jdbc.update("DELETE FROM rail_route_conflict");
        jdbc.update("DELETE FROM rail_route");
        jdbc.update("UPDATE rail_route_matrix SET version = 1 WHERE id = 1");
    }

    @Test
    void matrixUpdateRacingPublishLeavesNoConflictingOccupancy() throws Exception {
        String r1 = key("R");
        String r2 = key("R");
        routeService.createRoute(new CreateRouteRequest(key("REQ"), "tester", r1));
        routeService.createRoute(new CreateRouteRequest(key("REQ"), "tester", r2));
        // R1 10:00-10:10 已发布；R2 草稿 10:05-10:15 与之重叠
        String planA = key("SCH");
        planService.createDraft(new CreatePlanRequest(key("REQ"), planA, DAY,
                List.of(occ("G1", key("SEC"), r1, 10, 0, 10, 10))));
        planService.publish(planA, key("REQ"));
        String planB = key("SCH");
        planService.createDraft(new CreatePlanRequest(key("REQ"), planB, DAY,
                List.of(occ("G2", key("SEC"), r2, 10, 5, 10, 15))));

        // 竞争：发布 B（无冲突对，当前可过） vs 矩阵新增 (R1, R2) 冲突对
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        planService.publish(planB, key("REQ"));
                        return Outcome.PUBLISH_OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("ROUTE_CONFLICT");
                        return Outcome.PUBLISH_REJECTED;
                    }
                },
                () -> {
                    try {
                        routeService.updateMatrix(new UpdateRouteMatrixRequest(key("REQ"), "tester",
                                1, List.of(new ConflictPairRequest(r1, r2)), List.of()));
                        return Outcome.MATRIX_OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("ROUTE_MATRIX_CONFLICT");
                        return Outcome.MATRIX_REJECTED;
                    }
                }));

        // 串行化裁决：恰好一个操作成功
        boolean publishOk = outcomes.contains(Outcome.PUBLISH_OK);
        boolean matrixOk = outcomes.contains(Outcome.MATRIX_OK);
        assertThat(publishOk).isNotEqualTo(matrixOk);

        // 最终状态一致：要么 B 已发布且矩阵无冲突对，要么矩阵有冲突对且 B 仍草稿
        boolean pairDeclared = routeService.getMatrix().conflicts().size() == 1;
        String statusB = planService.getPlan(planB).status();
        if (matrixOk) {
            assertThat(pairDeclared).isTrue();
            assertThat(statusB).isEqualTo("DRAFT");
        } else {
            assertThat(pairDeclared).isFalse();
            assertThat(statusB).isEqualTo("PUBLISHED");
        }
    }

    @Test
    void concurrentCreateRouteSameRequestIdReturnsFirstResponse() throws Exception {
        String routeId = key("R");
        String requestId = key("REQ");
        int threads = 4;

        List<Callable<RouteResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> routeService.createRoute(
                    new CreateRouteRequest(requestId, "tester", routeId)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<RouteResponse>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<RouteResponse> responses = new ArrayList<>();
        for (Future<RouteResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 全部返回首次响应，库里只有一条进路、一条幂等记录
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        Integer routeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_route WHERE route_id = ?", Integer.class, routeId);
        assertThat(routeCount).isEqualTo(1);
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE op_type = 'ROUTE_CREATE'"
                        + " AND request_key = ?", Integer.class, requestId);
        assertThat(idemCount).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        PUBLISH_OK,
        PUBLISH_REJECTED,
        MATRIX_OK,
        MATRIX_REJECTED
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

    private static OccupancyRequest occ(String train, String section, String route,
                                        int startHour, int startMinute, int endHour, int endMinute) {
        return new OccupancyRequest(train, section, route,
                at(startHour, startMinute), at(endHour, endMinute));
    }

    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}