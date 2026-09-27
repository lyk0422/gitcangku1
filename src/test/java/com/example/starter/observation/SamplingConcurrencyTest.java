package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * 采样许可并发与幂等边界测试：真实并发提交验证容量不超发、相交许可串行裁决只成一张、同键重放只扣一次。
 */
@SpringBootTest
class SamplingConcurrencyTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-10-10T08:00:00Z");

    @Autowired
    private SamplingService samplingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM merge_cluster");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        jdbcTemplate.update("DELETE FROM permit_sample_key");
        jdbcTemplate.update("DELETE FROM sampling_permit");
        jdbcTemplate.update("DELETE FROM permit_device_lock");
        Mockito.when(clock.instant()).thenReturn(NOW);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(2, tasks.size()));
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
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("workers ready in time").isTrue();
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

    private void createPermit(String permitId, String deviceId, int gridMin, int gridMax, int max) {
        samplingService.createPermit(new CreatePermitRequest(
                "req-permit-" + permitId, permitId, deviceId, "v1",
                gridMin, gridMax, FROM, TO, max));
    }

    @Test
    void concurrentSamplesNeverExceedPermitCapacity() throws Exception {
        createPermit("permit-1", "dev-1", 10, 20, 5);

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    samplingService.submitSample(new SampleSubmitRequest(
                            "req-sample-" + index, "obs-" + index, "dev-1",
                            10 + (index % 10), AT, "站点" + index, "1.0", "备注"));
                    return "ok";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value()) + ":" + e.reason();
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        long ok = results.stream().filter("ok"::equals).count();
        long limitRejected = results.stream().filter(r -> r.equals("422:LIMIT_EXCEEDED")).count();
        assertThat(ok).isEqualTo(5);
        assertThat(limitRejected).isEqualTo(7);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT observed_count FROM sampling_permit WHERE permit_id = 'permit-1'", Integer.class);
        assertThat(count).isEqualTo(5);
        Integer obsRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_current WHERE observation_status = 'RECEIVED'", Integer.class);
        assertThat(obsRows).isEqualTo(5);
    }

    @Test
    void concurrentOverlappingPermitCreationsYieldSingleWinner() throws Exception {
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    samplingService.createPermit(new CreatePermitRequest(
                            "req-overlap-" + index, "permit-" + index, "dev-1", "v1",
                            10, 20, FROM, TO, 5));
                    return "ok";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value()) + ":" + e.reason();
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "409:PERMIT_OVERLAP");
        Integer permitRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sampling_permit WHERE device_id = 'dev-1'", Integer.class);
        assertThat(permitRows).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdReplaysSingleSampleAndCountsOnce() throws Exception {
        createPermit("permit-1", "dev-1", 10, 20, 5);

        List<Callable<SamplingService.SampleOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> samplingService.submitSample(new SampleSubmitRequest(
                    "req-same-key", "obs-1", "dev-1", 15, AT, "站点A", "1.0", "备注")));
        }
        List<SamplingService.SampleOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(o -> o.status() == 201);
        // 同键并发重放返回同一 permitKey
        String permitKey = outcomes.get(0).body().permitKey();
        assertThat(outcomes).allMatch(o -> o.body().permitKey().equals(permitKey));
        Integer count = jdbcTemplate.queryForObject(
                "SELECT observed_count FROM sampling_permit WHERE permit_id = 'permit-1'", Integer.class);
        assertThat(count).isEqualTo(1);
        Integer obsRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_current WHERE observation_id = 'obs-1'", Integer.class);
        assertThat(obsRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-same-key'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentBatchesEnforceFinalCapacityAcrossWholeBatch() throws Exception {
        // 容量 4：一个批 3 条与一个批 3 条并发，按完整最终计数，仅一批应成功
        createPermit("permit-1", "dev-1", 10, 20, 4);

        Callable<String> batchA = () -> {
            try {
                samplingService.batchMerge(new BatchMergeRequest("req-batch-a", List.of(
                        new BatchMergeRequest.Item("a1", null, "dev-1", 10, AT, "A1", "1.0", "n"),
                        new BatchMergeRequest.Item("a2", null, "dev-1", 11, AT, "A2", "1.0", "n"),
                        new BatchMergeRequest.Item("a3", null, "dev-1", 12, AT, "A3", "1.0", "n"))));
                return "ok";
            } catch (ApiException e) {
                return String.valueOf(e.status().value()) + ":" + e.reason();
            }
        };
        Callable<String> batchB = () -> {
            try {
                samplingService.batchMerge(new BatchMergeRequest("req-batch-b", List.of(
                        new BatchMergeRequest.Item("b1", null, "dev-1", 13, AT, "B1", "1.0", "n"),
                        new BatchMergeRequest.Item("b2", null, "dev-1", 14, AT, "B2", "1.0", "n"),
                        new BatchMergeRequest.Item("b3", null, "dev-1", 15, AT, "B3", "1.0", "n"))));
                return "ok";
            } catch (ApiException e) {
                return String.valueOf(e.status().value()) + ":" + e.reason();
            }
        };
        List<String> results = runConcurrently(List.of(batchA, batchB));

        long ok = results.stream().filter("ok"::equals).count();
        long rejected = results.stream().filter(r -> r.equals("422:LIMIT_EXCEEDED")).count();
        assertThat(ok).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT observed_count FROM sampling_permit WHERE permit_id = 'permit-1'", Integer.class);
        assertThat(count).isEqualTo(3);
        Integer obsRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM observation_current", Integer.class);
        assertThat(obsRows).isEqualTo(3);
        // 失败批不留半成品：成功批 3 条全部落库，失败批 0 条
        Integer aRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_current WHERE observation_id IN ('a1','a2','a3')", Integer.class);
        Integer bRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_current WHERE observation_id IN ('b1','b2','b3')", Integer.class);
        assertThat(aRows + bRows).isEqualTo(3);
        assertThat(aRows).isIn(0, 3);
        assertThat(bRows).isIn(0, 3);
        assertThat(aRows + bRows).isEqualTo(3);
    }
}
