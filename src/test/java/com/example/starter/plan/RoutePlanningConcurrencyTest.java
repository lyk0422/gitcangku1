package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.RoutePlanningService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.LineSegmentRequest;
import com.example.starter.plan.web.dto.ReplaceGraphRequest;
import com.example.starter.plan.web.dto.RoutePublishRequest;
import com.example.starter.plan.web.dto.RoutePublishResponse;
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
 * 寻径发布与图替换的并发与幂等竞态测试：同一路径并发寻径仅一单成功、
 * 同键并发寻径重放首次结果、并发图替换仅一单推进版本。
 */
@SpringBootTest
class RoutePlanningConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private RoutePlanningService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentRoutePublishSamePathOnlyOneSucceeds() throws Exception {
        String p = suffix();
        int version = replaceGraph(p, seg("SEC-ST-" + p, "S-" + p, "T-" + p, 5));
        int contenders = 4;
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String scheduleKey = key("SCH");
            String requestId = key("REQ");
            tasks.add(() -> {
                try {
                    service.publishRoute(routeReq(requestId, scheduleKey, version, p));
                    return Outcome.OK;
                } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("NO_FEASIBLE_ROUTE");
                    return Outcome.CONFLICT;
                }
            });
        }
        List<Outcome> outcomes = runConcurrently(tasks);
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(contenders - 1);

        // 最终该区段只有一张计划的占用生效
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_occupancy WHERE section_id = ?",
                Integer.class, "SEC-ST-" + p);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentRoutePublishSameRequestIdReturnsFirstResult() throws Exception {
        String p = suffix();
        int version = replaceGraph(p, seg("SEC-ST-" + p, "S-" + p, "T-" + p, 5));
        String scheduleKey = key("SCH");
        String requestId = key("REQ");
        int threads = 4;
        List<Callable<RoutePublishResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.publishRoute(routeReq(requestId, scheduleKey, version, p)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<RoutePublishResponse>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<RoutePublishResponse> responses = new ArrayList<>();
        for (Future<RoutePublishResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 全部返回首次结果，且库里只有一张计划一条占用
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        Integer plans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_day_plan WHERE schedule_key = ?",
                Integer.class, scheduleKey);
        assertThat(plans).isEqualTo(1);
        Integer legs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_route rt JOIN rail_day_plan dp ON dp.id = rt.plan_id"
                        + " WHERE dp.schedule_key = ?",
                Integer.class, scheduleKey);
        assertThat(legs).isEqualTo(1);
    }

    @Test
    void concurrentGraphReplaceOnlyOneAdvancesVersion() throws Exception {
        String p = suffix();
        int before = service.getGraph().version();
        int contenders = 4;
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String requestId = key("REQ");
            tasks.add(() -> {
                try {
                    service.replaceGraph(new ReplaceGraphRequest(requestId, "dispatcher-test",
                            before, List.of(seg("SEC-C-" + p, "A-" + p, "B-" + p, 3))));
                    return Outcome.OK;
                } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("GRAPH_VERSION_CONFLICT");
                    return Outcome.CONFLICT;
                }
            });
        }
        List<Outcome> outcomes = runConcurrently(tasks);
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(contenders - 1);
        assertThat(service.getGraph().version()).isEqualTo(before + 1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private int replaceGraph(String p, LineSegmentRequest... segments) {
        int version = service.getGraph().version();
        return service.replaceGraph(new ReplaceGraphRequest(key("REQ"), "dispatcher-test",
                version, List.of(segments))).version();
    }

    private static RoutePublishRequest routeReq(String requestId, String scheduleKey,
                                                int version, String p) {
        return new RoutePublishRequest(requestId, "dispatcher-test", scheduleKey, version,
                "G-" + p, "S-" + p, "T-" + p, at(8, 0), at(8, 30));
    }

    private static LineSegmentRequest seg(String sectionId, String from, String to, int minutes) {
        return new LineSegmentRequest(sectionId, from, to, minutes);
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

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
