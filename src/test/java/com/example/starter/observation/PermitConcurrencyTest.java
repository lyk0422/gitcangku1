package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
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
 * 许可并发与幂等边界测试：真实并发提交，验证许可计数上限不超、
 * 同 permitKey 并发只占一键、并发创建交集重叠许可只成功一个。
 */
@SpringBootTest
class PermitConcurrencyTest {

    private static final Instant VALID_FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant VALID_TO = Instant.parse("2026-12-31T00:00:00Z");
    private static final Instant COLLECTED_AT = Instant.parse("2026-03-01T00:00:00Z");

    @Autowired
    private ObservationService observationService;

    @Autowired
    private PermitService permitService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM permit_review_snapshot");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        jdbcTemplate.update("DELETE FROM sampling_permit");
        jdbcTemplate.update("DELETE FROM permit_device_lock");
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

    private void createPermit(String requestId, String permitId, String deviceId, int maxObservations) {
        permitService.create(new CreatePermitRequest(requestId, permitId, deviceId,
                0, 100, 0, 100, VALID_FROM, VALID_TO, maxObservations, 1));
    }

    private CreateObservationRequest submitRequest(String requestId, String observationId) {
        return new CreateObservationRequest(requestId, observationId, "站点A", "1.0", "备注",
                "DEV-1", 5, 5, COLLECTED_AT);
    }

    @Test
    void concurrentSubmitsNeverExceedPermitLimit() throws Exception {
        createPermit("req-p0", "permit-1", "DEV-1", 3);

        // 6 个并发提交争夺上限为 3 的许可：恰好 3 个成功、3 个 422，计数不超上限
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    observationService.create(submitRequest("req-c" + index, "obs-c" + index));
                    return "ok";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("ok"::equals).hasSize(3);
        assertThat(results).filteredOn("422"::equals).hasSize(3);
        Integer used = jdbcTemplate.queryForObject(
                "SELECT used_count FROM sampling_permit WHERE permit_id = 'permit-1'", Integer.class);
        assertThat(used).isEqualTo(3);
        Integer observations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_current", Integer.class);
        assertThat(observations).isEqualTo(3);
    }

    @Test
    void concurrentSamePermitKeyReplaysSingleResult() throws Exception {
        createPermit("req-p1", "permit-1", "DEV-1", 100);

        // 同一 permitKey（requestId）、相同参数并发提交 4 次：全部返回同一结果，只扣减一次计数
        List<Callable<ObservationService.WriteOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> observationService.create(submitRequest("req-same", "obs-same")));
        }
        List<ObservationService.WriteOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome -> outcome.body().version() == 1);
        Integer used = jdbcTemplate.queryForObject(
                "SELECT used_count FROM sampling_permit WHERE permit_id = 'permit-1'", Integer.class);
        assertThat(used).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-same'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        Integer observations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_current WHERE observation_id = 'obs-same'", Integer.class);
        assertThat(observations).isEqualTo(1);
    }

    @Test
    void concurrentOverlappingPermitCreatesYieldSinglePermit() throws Exception {
        // 3 个并发创建同设备、网格与时间均重叠的许可：恰好一个成功，其余 409
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    permitService.create(new CreatePermitRequest("req-o" + index, "permit-o" + index, "DEV-1",
                            0, 100, 0, 100, VALID_FROM, VALID_TO, 100, 1));
                    return "ok";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("ok"::equals).hasSize(1);
        assertThat(results).filteredOn("409"::equals).hasSize(2);
        Integer permits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sampling_permit WHERE device_id = 'DEV-1'", Integer.class);
        assertThat(permits).isEqualTo(1);
    }
}
