package com.example.starter.observation.geo;

import com.example.starter.observation.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 二维坐标观测并发与幂等边界测试：真实并发提交（真实 H2 行锁与事务），
 * 验证确认与合并的原子核对、同键并发重放只占一键、并发创建只落一条。
 */
@SpringBootTest
class GeoObservationConcurrencyTest {

    @Autowired
    private GeoObservationService geoObservationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM consistency_confirmation");
        jdbcTemplate.update("DELETE FROM geo_observation_version");
        jdbcTemplate.update("DELETE FROM geo_observation_current");
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<T>> synchronizedTasks = tasks.stream()
                .<Callable<T>>map(task -> () -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                })
                .toList();
        try {
            List<Future<T>> futures = synchronizedTasks.stream().map(executor::submit).toList();
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers not ready in time");
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private void createGeo(String id, String requestId, String x) {
        geoObservationService.create(
                new CreateGeoObservationRequest(requestId, id, x, "0", "1", "0", "1", "op-seed"));
    }

    @Test
    void concurrentConfirmAndMergeAreAtomic() throws Exception {
        createGeo("geo-ca", "req-p0", "0");
        createGeo("geo-cb", "req-p1", "4");

        // 确认（期望两条均为版本 1）与对 geo-ca 的合并并发：行锁串行化后，
        // 要么确认先完成（合并随后正常生效），要么合并先提交（确认因版本过旧 409）。
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        geoObservationService.confirm(
                                new ConfirmConsistencyRequest("req-p2", "geo-ca", 1, "geo-cb", 1, "op-c"));
                        return "confirm-201";
                    } catch (ApiException e) {
                        return "confirm-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        geoObservationService.merge("geo-ca",
                                new MergeGeoObservationRequest("req-p3", 1, "8", "0", "1", "0", "1", "op-m"));
                        return "merge-200";
                    } catch (ApiException e) {
                        return "merge-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).contains("merge-200");
        assertThat(results).containsAnyOf("confirm-201", "confirm-409");
        GeoObservationResponse current = geoObservationService.getCurrent("geo-ca");
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.x()).isEqualTo("8");
        Integer confirmations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consistency_confirmation", Integer.class);
        if (results.contains("confirm-201")) {
            // 确认先完成：冻结的是版本 1 的完整快照
            assertThat(confirmations).isEqualTo(1);
            String frozenX = jdbcTemplate.queryForObject(
                    "SELECT x_a FROM consistency_confirmation", String.class);
            assertThat(frozenX).isEqualTo("0");
        } else {
            // 合并先提交：确认 409 回滚，不落确认记录
            assertThat(confirmations).isZero();
        }
    }

    @Test
    void concurrentSameRequestIdConfirmReplaysSingleResult() throws Exception {
        createGeo("geo-cc", "req-q0", "0");
        createGeo("geo-cd", "req-q1", "4");

        // 同一 requestId、相同参数并发提交 4 次：全部返回同一确认记录，只落一行
        List<Callable<GeoObservationService.ConfirmOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> geoObservationService.confirm(
                    new ConfirmConsistencyRequest("req-q2", "geo-cc", 1, "geo-cd", 1, "op-c")));
        }
        List<GeoObservationService.ConfirmOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        String confirmationId = outcomes.get(0).body().confirmationId();
        assertThat(outcomes).allMatch(outcome -> outcome.body().confirmationId().equals(confirmationId));
        Integer confirmationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consistency_confirmation", Integer.class);
        assertThat(confirmationRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-q2'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentCreatesOnSameObservationIdYieldSingleRecord() throws Exception {
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    geoObservationService.create(new CreateGeoObservationRequest(
                            "req-r" + index, "geo-ce", String.valueOf(index), "0", "1", "0", "1", "op-c"));
                    return "ok";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("ok"::equals).hasSize(1);
        assertThat(results).filteredOn("409"::equals).hasSize(2);
        assertThat(geoObservationService.getCurrent("geo-ce").version()).isEqualTo(1);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geo_observation_version WHERE observation_id = 'geo-ce'", Integer.class);
        assertThat(versionRows).isEqualTo(1);
    }
}
