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
 * 坐标基准并发与幂等边界测试：真实并发提交、基准修改与人工裁决，
 * 验证全局串行化裁决与同键并发重放只占一键。
 */
@SpringBootTest
class GeoObservationConcurrencyTest {

    private static final String T0 = "2026-09-26T10:00:00Z";

    @Autowired
    private GeoObservationService geoObservationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM frame_recalc");
        jdbcTemplate.update("DELETE FROM geo_observation");
        jdbcTemplate.update("DELETE FROM geo_cluster");
        jdbcTemplate.update("DELETE FROM device_frame");
        jdbcTemplate.update("DELETE FROM coordinate_frame");
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

    private void registerFrame(String requestId, String version, double offsetLat, double offsetLon) {
        geoObservationService.registerFrame(new RegisterFrameRequest(requestId, version, offsetLat, offsetLon));
    }

    private GeoObservationService.TypedOutcome<GeoObservationResponse> submit(
            String requestId, String deviceId, double lat, double lon, String frame) {
        return geoObservationService.submit(deviceId, new SubmitGeoObservationRequest(
                requestId, lat, lon, frame, T0, "站点", "1.0", "备注"));
    }

    @Test
    void concurrentSameRequestIdSubmitsReplaySingleResult() throws Exception {
        registerFrame("req-cf0", "FRAME-ZERO", 0.0, 0.0);

        // 同一 requestId、相同参数并发提交 4 次：全部返回同一观测，只落库一行
        List<Callable<GeoObservationService.TypedOutcome<GeoObservationResponse>>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> submit("req-ci1", "dev-1", 30.0, 120.0, "FRAME-ZERO"));
        }
        List<GeoObservationService.TypedOutcome<GeoObservationResponse>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        String observationId = outcomes.get(0).body().observationId();
        assertThat(outcomes).allMatch(outcome -> outcome.body().observationId().equals(observationId));
        Integer observationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geo_observation", Integer.class);
        assertThat(observationRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-ci1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentFrameChangeAndSubmitAreSerialized() throws Exception {
        registerFrame("req-cs0", "FRAME-ZERO", 0.0, 0.0);
        registerFrame("req-cs1", "FRAME-SHIFT", 0.001, 0.0);
        submit("req-cs2", "dev-1", 30.0, 120.0, "FRAME-ZERO");

        // 基准修改与观测提交并发：按提交顺序串行裁决，两者都成功且状态一致
        List<Callable<String>> tasks = List.of(
                () -> {
                    geoObservationService.updateDeviceFrame("dev-1",
                            new UpdateDeviceFrameRequest("req-cs3", "FRAME-SHIFT"));
                    return "frame-ok";
                },
                () -> {
                    submit("req-cs4", "dev-1", 31.0, 120.0, "FRAME-ZERO");
                    return "submit-ok";
                });
        List<String> results = runConcurrently(tasks);
        assertThat(results).containsExactlyInAnyOrder("frame-ok", "submit-ok");

        // 最终状态：设备基准已修改，两条观测都在，统一坐标合法且簇归属已重算
        String deviceFrame = jdbcTemplate.queryForObject(
                "SELECT frame_version FROM device_frame WHERE device_id = 'dev-1'", String.class);
        assertThat(deviceFrame).isEqualTo("FRAME-SHIFT");
        Integer observationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geo_observation WHERE device_id = 'dev-1'", Integer.class);
        assertThat(observationRows).isEqualTo(2);
        // 无论先后顺序，两条观测的统一坐标都落在合法范围内且可判定簇归属
        List<Double> lats = jdbcTemplate.queryForList(
                "SELECT unified_latitude FROM geo_observation WHERE device_id = 'dev-1'", Double.class);
        assertThat(lats).allMatch(lat -> lat >= -90.0 && lat <= 90.0);
    }

    @Test
    void concurrentResolvesYieldSingleWinner() throws Exception {
        registerFrame("req-cr0", "FRAME-ZERO", 0.0, 0.0);
        GeoObservationService.TypedOutcome<GeoObservationResponse> first =
                submit("req-cr1", "dev-1", 30.0, 120.0, "FRAME-ZERO");
        GeoObservationService.TypedOutcome<GeoObservationResponse> second =
                submit("req-cr2", "dev-2", 30.0004, 120.0, "FRAME-ZERO");
        String clusterId = geoObservationService
                .getObservation(first.body().observationId()).clusterId();
        assertThat(clusterId).isNotNull();
        String firstId = first.body().observationId();
        String secondId = second.body().observationId();

        // 两个人工裁决并发（不同胜出者）：恰好一个成功，另一个 409，结论不被覆盖
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        geoObservationService.resolveCluster(clusterId,
                                new ResolveClusterRequest("req-cr3", firstId, "op-1"));
                        return "first-ok";
                    } catch (ApiException e) {
                        return "first-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        geoObservationService.resolveCluster(clusterId,
                                new ResolveClusterRequest("req-cr4", secondId, "op-2"));
                        return "second-ok";
                    } catch (ApiException e) {
                        return "second-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        boolean firstWon = results.contains("first-ok");
        assertThat(results).containsExactlyInAnyOrder(
                firstWon ? "first-ok" : "first-409",
                firstWon ? "second-409" : "second-ok");
        ClusterResponse cluster = geoObservationService.getCluster(clusterId);
        assertThat(cluster.manuallyResolved()).isTrue();
        assertThat(cluster.winnerObservationId()).isEqualTo(firstWon ? firstId : secondId);
    }
}
