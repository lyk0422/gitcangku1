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
 * 带量纲读数的并发与幂等边界测试：真实并发提交，验证等价不同单位合并只增一次版本、
 * 同键并发重放只占一键且只留一条收件。真实 H2 内存库（MODE=MySQL）。
 */
@SpringBootTest
class ObservationDimensionConcurrencyTest {

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_receipt");
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

    private int receiptCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_receipt WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentEquivalentUnitMergesBumpVersionOnlyOnce() throws Exception {
        observationService.create(new CreateObservationRequest(
                "req-p0", "obs-p1", "站点A", "1", "基线", "LENGTH", "m", null));

        // 两个离线端并发提交等价读数的不同单位：2m 与 200cm 规范值相同
        List<Callable<ObservationService.WriteOutcome>> tasks = List.of(
                () -> observationService.merge("obs-p1",
                        new MergeObservationRequest("req-p1", 1, "站点A", "2", "基线", "m", null)),
                () -> observationService.merge("obs-p1",
                        new MergeObservationRequest("req-p2", 1, "站点A", "200", "基线", "cm", null)));
        List<ObservationService.WriteOutcome> outcomes = runConcurrently(tasks);

        // 串行化后先到者加版本，后到者按最新当前判定为等价：都成功，版本只加一次
        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome -> outcome.body().version() == 2);
        ObservationResponse current = observationService.getCurrent("obs-p1");
        assertThat(current.version()).isEqualTo(2);
        assertThat(new java.math.BigDecimal(current.reading()))
                .isEqualByComparingTo(new java.math.BigDecimal("2"));
        // 两次成功收件都留痕（含等价不增版的一次）
        assertThat(receiptCount("obs-p1")).isEqualTo(3);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-p1'", Integer.class);
        assertThat(versionRows).isEqualTo(2);
    }

    @Test
    void concurrentSameRequestIdWithUnitsReplaysSingleReceipt() throws Exception {
        observationService.create(new CreateObservationRequest(
                "req-q0", "obs-p2", "站点A", "1", "基线", "LENGTH", "m", null));

        // 同一 requestId、相同带单位参数并发提交 4 次：同一结果，只占一键、只留一条收件
        List<Callable<ObservationService.WriteOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> observationService.merge("obs-p2",
                    new MergeObservationRequest("req-q1", 1, "站点A", "150", "并发重放", "cm", null)));
        }
        List<ObservationService.WriteOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome -> outcome.body().version() == 2);
        assertThat(outcomes).allMatch(outcome -> "1.50".equals(outcome.body().reading()));
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-q1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        Integer receiptRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_receipt WHERE request_id = 'req-q1'", Integer.class);
        assertThat(receiptRows).isEqualTo(1);
        assertThat(receiptCount("obs-p2")).isEqualTo(2);
    }
}
