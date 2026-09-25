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
 * 坐标基准并发与幂等边界测试：提交、基准变更与人工裁决按提交顺序串行裁决，
 * 同键并发重放只占一键（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
class GeoConcurrencyTest {

    @Autowired
    private GeoService geoService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM frame_recalc");
        jdbcTemplate.update("DELETE FROM observation_geo");
        jdbcTemplate.update("DELETE FROM conflict_cluster");
        jdbcTemplate.update("DELETE FROM device_frame");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
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

    private GeoService.Outcome<SubmissionResponse> submit(String requestId, String observationId,
                                                          String deviceId, String frameVersion,
                                                          double latitude, double longitude,
                                                          String capturedAt) {
        return geoService.submit(new SubmitObservationRequest(requestId, observationId, deviceId,
                frameVersion, latitude, longitude, Instant.parse(capturedAt), "站点", "1.0", "备注"));
    }

    @Test
    void concurrentSubmitsWithSameRequestIdReplaySingleResult() throws Exception {
        // 同一 requestId、相同参数并发提交 4 次：全部返回首个结果，只产生一条观测
        List<Callable<GeoService.Outcome<SubmissionResponse>>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> submit("req-cs1", "geo-c1", "dev-c1", "GCJ02",
                    39.90625, 116.40625, "2026-09-25T10:00:00Z"));
        }
        List<GeoService.Outcome<SubmissionResponse>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome -> outcome.body().observationId().equals("geo-c1"));
        assertThat(outcomes).allMatch(outcome -> outcome.body().unifiedLatitude() == 39.8984375);
        Integer geoRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_geo WHERE observation_id = 'geo-c1'", Integer.class);
        assertThat(geoRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-cs1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'geo-c1'", Integer.class);
        assertThat(versionRows).isEqualTo(1);
    }

    @Test
    void concurrentSubmitAndFrameChangeAreSerialized() throws Exception {
        // 提交与基准变更并发：两种提交顺序均可，最终状态必须自洽
        List<Callable<String>> tasks = List.of(
                () -> {
                    submit("req-cs2", "geo-c2", "dev-c2", "GCJ02",
                            39.90625, 116.40625, "2026-09-25T10:00:00Z");
                    return "submit-ok";
                },
                () -> {
                    geoService.updateDeviceFrame("dev-c2",
                            new UpdateDeviceFrameRequest("req-cf2", "WGS84"));
                    return "frame-ok";
                });
        List<String> results = runConcurrently(tasks);
        assertThat(results).containsExactlyInAnyOrder("submit-ok", "frame-ok");

        // 设备基准最终为 WGS84
        String deviceFrame = jdbcTemplate.queryForObject(
                "SELECT frame_version FROM device_frame WHERE device_id = 'dev-c2'", String.class);
        assertThat(deviceFrame).isEqualTo("WGS84");
        CoordinatesResponse coordinates = geoService.getCoordinates("geo-c2");
        if ("WGS84".equals(coordinates.currentFrameVersion())) {
            // 提交先完成：观测被同事务重算为 WGS84 统一坐标
            assertThat(coordinates.unifiedLatitude()).isEqualTo(39.90625);
            assertThat(coordinates.unifiedLongitude()).isEqualTo(116.40625);
        } else {
            // 基准变更先完成：观测保持提交时按 GCJ02 的转换结果
            assertThat(coordinates.currentFrameVersion()).isEqualTo("GCJ02");
            assertThat(coordinates.unifiedLatitude()).isEqualTo(39.8984375);
            assertThat(coordinates.unifiedLongitude()).isEqualTo(116.40234375);
        }
        // 原始坐标与原基准版本在任何顺序下都不可改写
        assertThat(coordinates.rawLatitude()).isEqualTo(39.90625);
        assertThat(coordinates.originalFrameVersion()).isEqualTo("GCJ02");
    }

    @Test
    void concurrentClusterResolvesWithDifferentChoicesYieldSingleWinner() throws Exception {
        submit("req-cr1", "obs-c1", "dev-a", "WGS84", 30.0, 120.0, "2026-09-25T10:00:00Z");
        submit("req-cr2", "obs-c2", "dev-b", "WGS84", 30.0, 120.0, "2026-09-25T10:00:30Z");
        String clusterId = geoService.getCoordinates("obs-c2").clusterId();
        assertThat(clusterId).isNotNull();

        // 两个操作者对同一簇选择不同胜出记录：恰好一个成功，另一个 409
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        geoService.resolveCluster(clusterId,
                                new ResolveClusterRequest("req-cr3", "obs-c1", "op-1"));
                        return "resolve-obs-c1";
                    } catch (ApiException e) {
                        return "resolve-obs-c1-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        geoService.resolveCluster(clusterId,
                                new ResolveClusterRequest("req-cr4", "obs-c2", "op-2"));
                        return "resolve-obs-c2";
                    } catch (ApiException e) {
                        return "resolve-obs-c2-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);
        assertThat(results).containsExactlyInAnyOrder(
                results.contains("resolve-obs-c1") ? "resolve-obs-c2-409" : "resolve-obs-c1-409",
                results.contains("resolve-obs-c1") ? "resolve-obs-c1" : "resolve-obs-c2");

        ClusterResponse cluster = geoService.getCluster(clusterId);
        assertThat(cluster.manuallyResolved()).isTrue();
        String expectedWinner = results.contains("resolve-obs-c1") ? "obs-c1" : "obs-c2";
        assertThat(cluster.resolvedObservationId()).isEqualTo(expectedWinner);
        assertThat(cluster.winnerObservationId()).isEqualTo(expectedWinner);
    }

    @Test
    void concurrentFrameChangesOnSameDeviceAreSerialized() throws Exception {
        submit("req-cf0", "obs-c4", "dev-c4", "GCJ02", 39.90625, 116.40625, "2026-09-25T10:00:00Z");

        // 两个基准变更并发：按提交顺序串行裁决，最终基准与统一坐标自洽
        List<Callable<GeoService.Outcome<DeviceFrameResponse>>> tasks = List.of(
                () -> geoService.updateDeviceFrame("dev-c4",
                        new UpdateDeviceFrameRequest("req-cf1", "WGS84")),
                () -> geoService.updateDeviceFrame("dev-c4",
                        new UpdateDeviceFrameRequest("req-cf2", "CGCS2000")));
        List<GeoService.Outcome<DeviceFrameResponse>> outcomes = runConcurrently(tasks);
        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);

        String deviceFrame = jdbcTemplate.queryForObject(
                "SELECT frame_version FROM device_frame WHERE device_id = 'dev-c4'", String.class);
        assertThat(deviceFrame).isIn("WGS84", "CGCS2000");
        CoordinatesResponse coordinates = geoService.getCoordinates("obs-c4");
        // 最终统一坐标必须等于原始坐标按最终基准的转换结果
        assertThat(coordinates.currentFrameVersion()).isEqualTo(deviceFrame);
        if ("WGS84".equals(deviceFrame)) {
            assertThat(coordinates.unifiedLatitude()).isEqualTo(39.90625);
        } else {
            assertThat(coordinates.unifiedLatitude()).isEqualTo(39.91015625);
        }
        // 两次变更各产生一次重算（后到者基于先到者的结果继续重算）
        Integer geoRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_geo WHERE observation_id = 'obs-c4'", Integer.class);
        assertThat(geoRows).isEqualTo(1);
    }
}
