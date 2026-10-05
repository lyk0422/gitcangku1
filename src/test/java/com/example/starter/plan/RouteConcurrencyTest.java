package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.RouteService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.GraphSegmentRequest;
import com.example.starter.plan.web.dto.ReplaceGraphRequest;
import com.example.starter.plan.web.dto.RoutePlanResponse;
import com.example.starter.plan.web.dto.RoutePublishRequest;
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
 * 寻径发布与图替换的并发测试：并发寻径互斥（仅一个成功占用）、
 * 并发图替换按提交顺序裁决（仅一个推进版本）、同 requestId 并发重放首次结果。
 */
@SpringBootTest
class RouteConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private RouteService routeService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentRoutePublishSameSegmentOnlyOneSucceeds() throws Exception {
        String segmentId = key("SEG");
        int version = replaceGraph(new GraphSegmentRequest(segmentId, "S", "T", 5));
        int contenders = 4;
        String train = "G" + UUID.randomUUID();

        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            tasks.add(() -> {
                try {
                    routeService.publishRoute(new RoutePublishRequest(key("REQ"), "dispatcher-1",
                            version, train, "S", "T", at(8), at(8).plusSeconds(600)));
                    return Outcome.OK;
                } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("ROUTE_INFEASIBLE");
                    return Outcome.CONFLICT;
                }
            });
        }
        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(contenders - 1);
        // 最终该区段只有一个生效占用、一条寻径快照
        Integer slots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                        + " WHERE p.status = 'PUBLISHED' AND o.section_id = ?",
                Integer.class, segmentId);
        assertThat(slots).isEqualTo(1);
        Integer details = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_route_plan_detail WHERE train_no = ?",
                Integer.class, train);
        assertThat(details).isEqualTo(1);
    }

    @Test
    void concurrentGraphReplaceSameVersionOnlyOneSucceeds() throws Exception {
        int current = routeService.getGraph().version();
        List<GraphSegmentRequest> segments =
                List.of(new GraphSegmentRequest(key("SEG"), "S", "T", 5));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> replaceOrConflict(current, segments),
                () -> replaceOrConflict(current, segments)));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        assertThat(routeService.getGraph().version()).isEqualTo(current + 1);
    }

    @Test
    void concurrentSameRequestIdReplaysFirstResult() throws Exception {
        String segmentId = key("SEG");
        int version = replaceGraph(new GraphSegmentRequest(segmentId, "S", "T", 5));
        String requestId = key("REQ");
        RoutePublishRequest request = new RoutePublishRequest(requestId, "dispatcher-1",
                version, "G" + UUID.randomUUID(), "S", "T", at(8), at(8).plusSeconds(600));
        int threads = 4;

        List<Callable<RoutePlanResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> routeService.publishRoute(request));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<RoutePlanResponse>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<RoutePlanResponse> responses = new ArrayList<>();
        for (Future<RoutePlanResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 全部返回首次结果，库里只有一张计划、一条幂等记录
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        Integer plans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_day_plan WHERE schedule_key = ?",
                Integer.class, responses.get(0).scheduleKey());
        assertThat(plans).isEqualTo(1);
        Integer idem = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record"
                        + " WHERE op_type = 'ROUTE_PUBLISH' AND request_key = ?",
                Integer.class, requestId);
        assertThat(idem).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private int replaceGraph(GraphSegmentRequest... segments) {
        int current = routeService.getGraph().version();
        return routeService.replaceGraph(new ReplaceGraphRequest(key("REQ"), "dispatcher-1",
                current, List.of(segments))).version();
    }

    private Outcome replaceOrConflict(int expectedVersion, List<GraphSegmentRequest> segments) {
        try {
            routeService.replaceGraph(new ReplaceGraphRequest(key("REQ"), "dispatcher-1",
                    expectedVersion, segments));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("GRAPH_VERSION_CONFLICT");
            return Outcome.CONFLICT;
        }
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
