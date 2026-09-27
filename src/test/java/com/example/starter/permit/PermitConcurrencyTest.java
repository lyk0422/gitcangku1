package com.example.starter.permit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
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
 * 采样许可并发与幂等边界测试：真实并发线程与真实 H2 行锁（FOR UPDATE），
 * 验证同设备许可签发交集串行化、配额不超限、同 requestId 并发只生效一次。
 */
@SpringBootTest
class PermitConcurrencyTest {

    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant T1 = Instant.parse("2026-01-02T00:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(T0, ZoneOffset.UTC);
        }
    }

    @Autowired
    private PermitService permitService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM permit_cluster_resolution");
        jdbcTemplate.update("DELETE FROM permit_observation_version");
        jdbcTemplate.update("DELETE FROM permit_observation");
        jdbcTemplate.update("DELETE FROM permit_rejection_log");
        jdbcTemplate.update("DELETE FROM sampling_permit");
        jdbcTemplate.update("DELETE FROM sampling_device");
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
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
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

    private CreatePermitRequest permitRequest(String requestId, String permitId, String deviceId, int version) {
        return new CreatePermitRequest(requestId, permitId, deviceId, version,
                new BigDecimal("100.0"), new BigDecimal("30.0"),
                new BigDecimal("101.0"), new BigDecimal("31.0"), T0, T1, 5);
    }

    @Test
    void concurrentOverlappingPermitCreationsYieldSinglePermit() throws Exception {
        // 3 个线程为同一设备并发签发同范围许可：版本号均为 1，恰好一个成功，其余 409（重叠或版本冲突）
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    permitService.createPermit(
                            permitRequest("req-pc" + index, "permit-" + index, "dev-1", 1));
                    return "ok";
                } catch (com.example.starter.observation.ApiException e) {
                    return e.status().value() + ":" + e.reasonCode();
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("ok"::equals).hasSize(1);
        assertThat(results).filteredOn(r -> r.startsWith("409:")).hasSize(2);
        Integer permits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sampling_permit WHERE device_id = 'dev-1'", Integer.class);
        assertThat(permits).isEqualTo(1);
    }

    @Test
    void concurrentSubmissionsNeverExceedQuota() throws Exception {
        permitService.createPermit(new CreatePermitRequest("req-q0", "permit-1", "dev-1", 1,
                new BigDecimal("100.0"), new BigDecimal("30.0"),
                new BigDecimal("101.0"), new BigDecimal("31.0"), T0, T1, 3));

        // 6 个并发提交打向同一许可（配额 3）：恰好 3 个成功，其余 422 QUOTA_EXCEEDED
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    permitService.submit(new SubmitObservationRequest(
                            "req-q" + (index + 1), "dev-1",
                            new BigDecimal("100." + index), new BigDecimal("30." + index),
                            T0, "观测" + index));
                    return "ok";
                } catch (com.example.starter.observation.ApiException e) {
                    return e.status().value() + ":" + e.reasonCode();
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("ok"::equals).hasSize(3);
        assertThat(results).filteredOn("422:QUOTA_EXCEEDED"::equals).hasSize(3);
        Integer accepted = jdbcTemplate.queryForObject(
                "SELECT accepted_count FROM sampling_permit WHERE permit_id = 'permit-1'", Integer.class);
        assertThat(accepted).isEqualTo(3);
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM permit_observation", Integer.class);
        assertThat(rows).isEqualTo(3);
    }

    @Test
    void concurrentSameRequestIdSubmitsReplaySingleObservation() throws Exception {
        permitService.createPermit(permitRequest("req-r0", "permit-1", "dev-1", 1));

        // 同一 requestId、相同参数并发 4 次：全部返回同一观测，只扣减一次计数
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> permitService.submit(new SubmitObservationRequest(
                    "req-r1", "dev-1", new BigDecimal("100.5"), new BigDecimal("30.5"),
                    T0, "并发重放观测")).status());
        }
        List<Integer> statuses = runConcurrently(tasks);

        assertThat(statuses).containsOnly(201);
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM permit_observation", Integer.class);
        assertThat(rows).isEqualTo(1);
        Integer accepted = jdbcTemplate.queryForObject(
                "SELECT accepted_count FROM sampling_permit WHERE permit_id = 'permit-1'", Integer.class);
        assertThat(accepted).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-r1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentBatchMergesRespectAggregateQuotaAndRollbackIndependently() throws Exception {
        // 配额 2：两个并发批次各含 2 条不同观测；串行化后恰好一个批次成功，另一个整批 422 回滚
        permitService.createPermit(new CreatePermitRequest("req-b0", "permit-1", "dev-1", 1,
                new BigDecimal("100.0"), new BigDecimal("30.0"),
                new BigDecimal("102.0"), new BigDecimal("32.0"), T0, T1, 2));

        List<BatchMergeRequest.MergeItem> batchA = List.of(
                new BatchMergeRequest.MergeItem("dev-1", new BigDecimal("100.1"), new BigDecimal("30.1"), T0, "A1"),
                new BatchMergeRequest.MergeItem("dev-1", new BigDecimal("100.2"), new BigDecimal("30.2"), T0, "A2"));
        List<BatchMergeRequest.MergeItem> batchB = List.of(
                new BatchMergeRequest.MergeItem("dev-1", new BigDecimal("100.3"), new BigDecimal("30.3"), T0, "B1"),
                new BatchMergeRequest.MergeItem("dev-1", new BigDecimal("100.4"), new BigDecimal("30.4"), T0, "B2"));

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        permitService.batchMerge(new BatchMergeRequest("req-b1", batchA, List.of()));
                        return "ok";
                    } catch (com.example.starter.observation.ApiException e) {
                        return e.status().value() + ":" + e.reasonCode();
                    }
                },
                () -> {
                    try {
                        permitService.batchMerge(new BatchMergeRequest("req-b2", batchB, List.of()));
                        return "ok";
                    } catch (com.example.starter.observation.ApiException e) {
                        return e.status().value() + ":" + e.reasonCode();
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("ok"::equals).hasSize(1);
        assertThat(results).filteredOn("422:QUOTA_EXCEEDED"::equals).hasSize(1);
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM permit_observation", Integer.class);
        assertThat(rows).isEqualTo(2);
        Integer accepted = jdbcTemplate.queryForObject(
                "SELECT accepted_count FROM sampling_permit WHERE permit_id = 'permit-1'", Integer.class);
        assertThat(accepted).isEqualTo(2);
    }
}
