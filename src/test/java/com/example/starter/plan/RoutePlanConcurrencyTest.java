package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.repo.RouteGraphRepository;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.RouteService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.RouteGraphReplaceRequest;
import com.example.starter.plan.web.dto.RoutePlanRequest;
import com.example.starter.plan.web.dto.RouteSegmentRequest;
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
 * 寻径发布并发测试：同一区段并发寻径仅一个成功、寻径与普通发布互斥、
 * 图替换与寻径发布串行化且结果一致（H2 内存库真实事务）。
 */
@SpringBootTest
class RoutePlanConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private RouteService routeService;

    @Autowired
    private PlanService planService;

    @Autowired
    private RouteGraphRepository graphRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentRoutePublishSameSectionOnlyOneSucceeds() throws Exception {
        String s = key("S");
        String t = key("T");
        String segment = key("D1");
        int version = replaceGraph(List.of(s, t),
                List.of(new RouteSegmentRequest(segment, s, t, 5)));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> routeOutcome(version, s, t),
                () -> routeOutcome(version, s, t),
                () -> routeOutcome(version, s, t)));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.INFEASIBLE).count()).isEqualTo(2);

        // 最终该区段只有一张计划的时隙生效
        Integer published = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                        + " WHERE p.status = 'PUBLISHED' AND o.section_id = ?",
                Integer.class, segment);
        assertThat(published).isEqualTo(1);
    }

    @Test
    void concurrentRoutePublishAndNormalPublishAreMutuallyExclusive() throws Exception {
        String s = key("S");
        String t = key("T");
        String segment = key("D1");
        int version = replaceGraph(List.of(s, t),
                List.of(new RouteSegmentRequest(segment, s, t, 5)));

        // 普通草稿计划占用同一区段同一窗口
        String scheduleKey = key("SCH");
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G0", segment, at(8, 0), at(8, 5)))));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> routeOutcome(version, s, t),
                () -> {
                    try {
                        planService.publish(scheduleKey, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.INFEASIBLE;
                    }
                }));

        // 发布锁串行化：两者恰好一个成功
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        Integer published = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                        + " WHERE p.status = 'PUBLISHED' AND o.section_id = ?",
                Integer.class, segment);
        assertThat(published).isEqualTo(1);
    }

    @Test
    void concurrentGraphReplaceAndRoutePublishSeeConsistentView() throws Exception {
        String s = key("S");
        String t = key("T");
        String segment = key("D1");
        int version = replaceGraph(List.of(s, t),
                List.of(new RouteSegmentRequest(segment, s, t, 5)));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        routeService.findAndPublish(routeRequest(version, s, t));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 换图先提交时寻径应得 409 版本冲突
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("GRAPH_VERSION_CONFLICT");
                        return Outcome.STALE;
                    }
                },
                () -> {
                    routeService.replaceGraph(new RouteGraphReplaceRequest(key("REQ"), "tester",
                            version, List.of(s, t),
                            List.of(new RouteSegmentRequest(key("D2"), s, t, 6))));
                    return Outcome.OK;
                }));

        // 换图始终成功（版本前进一格）；寻径要么以旧版本成功，要么 409，无中间态
        assertThat(outcomes.get(1)).isEqualTo(Outcome.OK);
        assertThat(graphRepo.currentVersion()).isEqualTo(version + 1);
        assertThat(outcomes.get(0)).isIn(Outcome.OK, Outcome.STALE);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        INFEASIBLE,
        STALE
    }

    private Outcome routeOutcome(int version, String from, String to) {
        try {
            routeService.findAndPublish(routeRequest(version, from, to));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            return Outcome.INFEASIBLE;
        }
    }

    private RoutePlanRequest routeRequest(int version, String from, String to) {
        return new RoutePlanRequest(key("REQ"), "tester", version, "G-" + UUID.randomUUID(),
                from, to, at(8, 0), at(8, 5));
    }

    private int replaceGraph(List<String> stations, List<RouteSegmentRequest> segments) {
        return routeService.replaceGraph(new RouteGraphReplaceRequest(key("REQ"), "tester",
                graphRepo.currentVersion(), stations, segments)).version();
    }

    private List<Outcome> runConcurrently(List<Callable<Outcome>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = tasks.stream()
                .map(task -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
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
