package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.RouteService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.DeclareRouteRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.RouteMatrixResponse;
import com.example.starter.plan.web.dto.RoutePairRequest;
import com.example.starter.plan.web.dto.UpdateRouteMatrixRequest;
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
 * 进路互斥并发裁决测试（H2 真实事务）：矩阵更新与发布竞争不留冲突占用、
 * 同版本并发矩阵更新仅一次成功、同进路并发声明仅一行、同键并发重放首次响应。
 */
@SpringBootTest
class RouteMatrixConcurrencyTest {

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
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("UPDATE rail_route_matrix SET version = 1 WHERE id = 1");
    }

    @Test
    void concurrentMatrixUpdateAndPublishNeverLeaveConflict() throws Exception {
        String route1 = key("R");
        String route2 = key("R");
        declare(route1);
        declare(route2);
        // R1 已发布 10:00-10:10；草稿 B 申请 R2 10:05-10:20（不同区段）
        String planA = key("SCH");
        createDraft(planA, key("SEC"), route1, 10, 0, 10, 10);
        planService.publish(planA, key("REQ"));
        String planB = key("SCH");
        createDraft(planB, key("SEC"), route2, 10, 5, 10, 20);
        long baseVersion = routeService.getMatrix().version();
        int basePairs = routeService.getMatrix().conflicts().size();

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        planService.publish(planB, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("ROUTE_CONFLICT");
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        routeService.updateMatrix(new UpdateRouteMatrixRequest(key("RID"), "op", baseVersion,
                                List.of(new RoutePairRequest(route1, route2)), List.of()));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("ROUTE_MATRIX_CONFLICT");
                        return Outcome.CONFLICT;
                    }
                }));

        // 恰有一个成功，且最终状态自洽：要么 B 发布且矩阵未变，要么矩阵升级且 B 仍草稿
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        RouteMatrixResponse matrix = routeService.getMatrix();
        PlanResponse b = planService.getPlan(planB);
        if (outcomes.get(0) == Outcome.OK) {
            assertThat(b.status()).isEqualTo("PUBLISHED");
            assertThat(matrix.version()).isEqualTo(baseVersion);
            assertThat(matrix.conflicts()).hasSize(basePairs);
        } else {
            assertThat(matrix.version()).isEqualTo(baseVersion + 1);
            assertThat(matrix.conflicts()).hasSize(basePairs + 1);
            assertThat(b.status()).isEqualTo("DRAFT");
        }
    }

    @Test
    void concurrentMatrixUpdatesSameVersionOnlyOneSucceeds() throws Exception {
        String route1 = key("R");
        String route2 = key("R");
        String route3 = key("R");
        declare(route1);
        declare(route2);
        declare(route3);

        long baseVersion = routeService.getMatrix().version();
        int basePairs = routeService.getMatrix().conflicts().size();
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> updateMatrix(key("RID"), baseVersion, route1, route2),
                () -> updateMatrix(key("RID"), baseVersion, route1, route3)));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        RouteMatrixResponse matrix = routeService.getMatrix();
        assertThat(matrix.version()).isEqualTo(baseVersion + 1);
        assertThat(matrix.conflicts()).hasSize(basePairs + 1);
    }

    private Outcome updateMatrix(String requestId, long expectedVersion, String a, String b) {
        try {
            routeService.updateMatrix(new UpdateRouteMatrixRequest(requestId, "op",
                    expectedVersion, List.of(new RoutePairRequest(a, b)), List.of()));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
            return Outcome.CONFLICT;
        }
    }

    @Test
    void concurrentDeclareSameRouteKeepsSingleRow() throws Exception {
        String routeId = key("R");
        int threads = 4;
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                routeService.declareRoute(routeId, new DeclareRouteRequest(key("RID"), "op"));
                return Outcome.OK;
            });
        }
        List<Outcome> outcomes = runConcurrently(tasks);
        assertThat(outcomes).allMatch(o -> o == Outcome.OK);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_route WHERE route_id = ?", Integer.class, routeId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdMatrixUpdateReplaysFirstResponse() throws Exception {
        String route1 = key("R");
        String route2 = key("R");
        declare(route1);
        declare(route2);
        String requestId = key("RID");
        long baseVersion = routeService.getMatrix().version();
        int basePairs = routeService.getMatrix().conflicts().size();
        UpdateRouteMatrixRequest request = new UpdateRouteMatrixRequest(requestId, "op",
                baseVersion, List.of(new RoutePairRequest(route1, route2)), List.of());

        List<RouteMatrixResponse> responses = new ArrayList<>();
        List<Callable<RouteMatrixResponse>> tasks = List.of(
                () -> routeService.updateMatrix(request),
                () -> routeService.updateMatrix(request));
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<RouteMatrixResponse>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        for (Future<RouteMatrixResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 两个并发同键请求都拿到首次完整响应，版本只递增一次
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(responses.get(0).version()).isEqualTo(baseVersion + 1);
        assertThat(routeService.getMatrix().version()).isEqualTo(baseVersion + 1);
        assertThat(routeService.getMatrix().conflicts()).hasSize(basePairs + 1);
    }
    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private void declare(String routeId) {
        routeService.declareRoute(routeId, new DeclareRouteRequest(key("RID"), "op"));
    }

    private void createDraft(String scheduleKey, String section, String route,
                             int startHour, int startMinute, int endHour, int endMinute) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section, route,
                        at(startHour, startMinute), at(endHour, endMinute)))));
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

    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
