package com.example.starter.observation;

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
 * 设备偏移与观测提交的并发边界测试：真实并发下按事务提交顺序裁决，
 * 验证串行化后的最终一致性、同键并发重放与区间重叠冲突（真实 H2 内存库）。
 */
@SpringBootTest
class DeviceObservationConcurrencyTest {

    @Autowired
    private DeviceObservationService deviceObservationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_reorder");
        jdbcTemplate.update("DELETE FROM device_observation");
        jdbcTemplate.update("DELETE FROM device_offset");
        jdbcTemplate.update("DELETE FROM request_log");
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

    private void registerOffset(String deviceId, String requestId, String effectiveFromUtc, int offsetSeconds) {
        deviceObservationService.registerOffset(deviceId,
                new RegisterOffsetRequest(requestId, effectiveFromUtc, offsetSeconds));
    }

    private DeviceObservationService.SubmitOutcome submit(String requestId, String observationId,
                                                          String deviceId, String deviceLocalTime) {
        return deviceObservationService.submit(new SubmitObservationRequest(
                requestId, observationId, deviceId, deviceLocalTime, "站点A", "1.0", "并发"));
    }

    @Test
    void concurrentSubmitsAssignUniqueVersionsAndConsistentOrder() throws Exception {
        registerOffset("dev-1", "req-p0", "2026-09-25T00:00:00Z", 0);

        // 4 个并发提交同一观测：版本号唯一且为 1..4，合并顺序为 1..4 的排列
        List<Callable<DeviceObservationService.SubmitOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int index = i;
            tasks.add(() -> submit("req-p" + (index + 1), "obs-c1", "dev-1",
                    "2026-09-25T10:00:0" + index));
        }
        List<DeviceObservationService.SubmitOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).extracting(outcome -> outcome.body().version())
                .containsExactlyInAnyOrder(1, 2, 3, 4);

        List<ObservationVersionResponse> versions =
                deviceObservationService.listObservationVersions("obs-c1");
        assertThat(versions).hasSize(4);
        assertThat(versions).extracting(ObservationVersionResponse::mergeSeq)
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
        // 合并顺序与矫正后时刻升序一致
        assertThat(versions).extracting(ObservationVersionResponse::correctedAtUtc)
                .isSorted();
        // 胜者为矫正后时刻最晚的版本
        ObservationStateResponse state = deviceObservationService.getObservationState("obs-c1");
        assertThat(state.correctedAtUtc().toString()).isEqualTo("2026-09-25T10:00:03Z");
    }

    @Test
    void concurrentSubmitAndOffsetModifyResolveByCommitOrder() throws Exception {
        registerOffset("dev-g", "req-q0", "2026-09-25T00:00:00Z", 10);

        // 提交与偏移修改并发：无论谁先提交，最终矫正后时刻都按新偏移换算——
        // 提交先落库则被修改事务的重建覆盖，修改先提交则提交按新偏移计算。
        List<Callable<String>> tasks = List.of(
                () -> {
                    DeviceObservationService.SubmitOutcome outcome =
                            submit("req-q1", "obs-c2", "dev-g", "2026-09-25T10:00:00");
                    return "submit-" + outcome.status();
                },
                () -> {
                    DeviceObservationService.OffsetOutcome outcome = deviceObservationService.modifyOffset(
                            "dev-g", new ModifyOffsetRequest("req-q2", "2026-09-25T00:00:00Z", 1));
                    return "modify-" + outcome.status();
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("submit-201", "modify-200");
        ObservationStateResponse state = deviceObservationService.getObservationState("obs-c2");
        assertThat(state.correctedAtUtc().toString()).isEqualTo("2026-09-25T10:00:01Z");
        assertThat(state.deviceLocalTime()).isEqualTo(java.time.LocalDateTime.of(2026, 9, 25, 10, 0, 0));
        // 两个请求均成功落键
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id IN ('req-q1', 'req-q2')", Integer.class);
        assertThat(requestRows).isEqualTo(2);
    }

    @Test
    void concurrentSameRequestIdSubmitsReplaySingleResult() throws Exception {
        registerOffset("dev-1", "req-r0", "2026-09-25T00:00:00Z", 5);

        // 同一 requestId、相同参数并发提交 4 次：全部返回同一结果，只产生一个版本
        List<Callable<DeviceObservationService.SubmitOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> submit("req-r1", "obs-c3", "dev-1", "2026-09-25T10:00:00"));
        }
        List<DeviceObservationService.SubmitOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome -> outcome.body().version() == 1);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_observation WHERE observation_id = 'obs-c3'", Integer.class);
        assertThat(versionRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-r1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentOverlappingRegistrationsYieldSingleOffsetRecord() throws Exception {
        // 两个并发登记同一设备同一起始时刻：恰好一个成功，另一个 409（区间重叠）
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        deviceObservationService.registerOffset("dev-k",
                                new RegisterOffsetRequest("req-k1", "2026-09-25T00:00:00Z", 10));
                        return "created";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        deviceObservationService.registerOffset("dev-k",
                                new RegisterOffsetRequest("req-k2", "2026-09-25T00:00:00Z", 20));
                        return "created";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("created", "409");
        List<OffsetResponse> offsets = deviceObservationService.listOffsets("dev-k");
        assertThat(offsets).hasSize(1);
        // 失败请求不占键：request_log 中只有成功者的记录
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id IN ('req-k1', 'req-k2')", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }
}
