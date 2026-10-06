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
 * 带量纲读数并发边界测试：真实并发提交，验证等价规范值合并只推进一次版本、
 * 同键并发重放只占一键且不重复收件（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
class DimensionalReadingConcurrencyTest {

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM reading_receipt");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
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

    @Test
    void concurrentEquivalentMergesBumpVersionOnlyOnce() throws Exception {
        observationService.create(new CreateObservationRequest(
                "req-e0", "obs-eq", "站点A", "1", "基线", "LENGTH", "m", null));

        // 两个离线端都基于 v1：200cm 与 2m 规范值等价（均为 2m），并发提交
        List<Callable<ObservationService.WriteOutcome>> tasks = List.of(
                () -> observationService.merge("obs-eq", new MergeObservationRequest(
                        "req-e1", 1, "站点A", "200", "基线", "LENGTH", "cm", null)),
                () -> observationService.merge("obs-eq", new MergeObservationRequest(
                        "req-e2", 1, "站点A", "2", "基线", "LENGTH", "m", null)));
        List<ObservationService.WriteOutcome> outcomes = runConcurrently(tasks);

        // 两个请求都成功：先到者推进到 v2，后到者基于最新版本判定为等价，不再加版本
        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        ObservationResponse current = observationService.getCurrent("obs-eq");
        assertThat(current.version()).isEqualTo(2);
        // 规范值按数值核对：先到者可能是 200cm（规范化为 2.00）或 2m，二者数值相等
        assertThat(new java.math.BigDecimal(current.normalizedReading()))
                .isEqualByComparingTo(new java.math.BigDecimal("2"));
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-eq'", Integer.class);
        assertThat(versionRows).isEqualTo(2);
        // 两次成功源输入都必须留下收件（含未推进版本的一次）
        Integer receiptRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reading_receipt WHERE observation_id = 'obs-eq'", Integer.class);
        assertThat(receiptRows).isEqualTo(3);
    }

    @Test
    void concurrentSameRequestIdReplaysSingleDimensionalResult() throws Exception {
        observationService.create(new CreateObservationRequest(
                "req-r0", "obs-rep", "站点A", "1", "基线", "LENGTH", "m", null));

        // 同一 requestId、相同带量纲参数并发 4 次：全部返回同一结果，只占一键、只收件一次
        List<Callable<ObservationService.WriteOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> observationService.merge("obs-rep", new MergeObservationRequest(
                    "req-r1", 1, "站点A", "150", "基线", "LENGTH", "cm", null)));
        }
        List<ObservationService.WriteOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome -> outcome.body().version() == 2);
        assertThat(outcomes).allMatch(outcome -> "1.5".equals(outcome.body().normalizedReading()));
        ObservationResponse current = observationService.getCurrent("obs-rep");
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.normalizedReading()).isEqualTo("1.5");
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-r1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        Integer receiptRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reading_receipt WHERE request_id = 'req-r1'", Integer.class);
        assertThat(receiptRows).isEqualTo(1);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-rep'", Integer.class);
        assertThat(versionRows).isEqualTo(2);
    }

    @Test
    void concurrentCrossDimensionSubmissionFailsCleanly() throws Exception {
        observationService.create(new CreateObservationRequest(
                "req-x0", "obs-xdim", "站点A", "1", "基线", "LENGTH", "m", null));

        // 并发提交一个合法合并与一个跨量纲合并：跨量纲必须 400，且不留下任何部分结果
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        observationService.merge("obs-xdim", new MergeObservationRequest(
                                "req-x1", 1, "站点A", "2", "基线", "LENGTH", "m", null));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        observationService.merge("obs-xdim", new MergeObservationRequest(
                                "req-x2", 1, "站点A", "1", "基线", "TEMPERATURE", "C", null));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "400");
        ObservationResponse current = observationService.getCurrent("obs-xdim");
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.normalizedReading()).isEqualTo("2");
        // 跨量纲失败不占键、不留收件
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-x2'", Integer.class);
        assertThat(requestRows).isZero();
        Integer receiptRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reading_receipt WHERE observation_id = 'obs-xdim'", Integer.class);
        assertThat(receiptRows).isEqualTo(2);
    }
}
