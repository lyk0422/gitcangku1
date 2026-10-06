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
 * 越限分析与分钟观测的并发边界测试：分析生成与观测修订共同核验、
 * 同键并发重放只占一键、同站同分钟并发创建不泄漏唯一键异常。
 */
@SpringBootTest
class ExcursionAnalysisConcurrencyTest {

    @Autowired
    private MinuteObservationService minuteObservationService;

    @Autowired
    private ExcursionAnalysisService excursionAnalysisService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM excursion_analysis");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM minute_observation_version");
        jdbcTemplate.update("DELETE FROM minute_observation_current");
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

    private void createObs(String requestId, String observationId, String minuteUtc, String reading) {
        minuteObservationService.create(new CreateMinuteObservationRequest(
                requestId, "tester", observationId, "ST-A", minuteUtc, reading));
    }

    private int analysisCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM excursion_analysis", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentAnalysisAndMergeAreValidatedAgainstEachOther() throws Exception {
        createObs("req-c1", "mo-1", "2026-10-07T10:00:00Z", "11");
        createObs("req-c2", "mo-2", "2026-10-07T10:01:00Z", "11");

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        ExcursionAnalysisService.AnalysisOutcome outcome = excursionAnalysisService.create(
                                new CreateExcursionAnalysisRequest("req-an1", "analyst",
                                        List.of(new CreateExcursionAnalysisRequest.ObservationRef("mo-1", 1),
                                                new CreateExcursionAnalysisRequest.ObservationRef("mo-2", 1)),
                                        "10", 2));
                        return "analysis-" + outcome.status();
                    } catch (ApiException e) {
                        return "analysis-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        minuteObservationService.merge("mo-1", new MergeMinuteObservationRequest(
                                "req-mg1", "tester", 1, "ST-A", "2026-10-07T10:00:00Z", "5"));
                        return "merge-ok";
                    } catch (ApiException e) {
                        return "merge-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 行锁串行化后：合并总是成功（基于最新版本重判）；分析要么先取锁成功，要么看到版本已偏离而 409
        assertThat(results).contains("merge-ok");
        assertThat(results).anyMatch(r -> r.equals("analysis-201") || r.equals("analysis-409"));
        MinuteObservationResponse current = minuteObservationService.getCurrent("mo-1");
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.reading()).isEqualTo("5");
        if (results.contains("analysis-201")) {
            // 分析先生成：引用的 mo-1 随后被修订，查询时应标记源已更新
            assertThat(analysisCount()).isEqualTo(1);
            String analysisId = jdbcTemplate.queryForObject(
                    "SELECT analysis_id FROM excursion_analysis", String.class);
            ExcursionAnalysisResponse analysis = excursionAnalysisService.get(analysisId);
            assertThat(analysis.sourceUpdated()).isTrue();
            assertThat(analysis.intervals()).hasSize(1);
        } else {
            // 合并先提交：分析因引用版本非当前版本被拒绝，整次不写分析
            assertThat(analysisCount()).isZero();
        }
    }

    @Test
    void concurrentSameRequestIdAnalysisReplaysSingleResult() throws Exception {
        createObs("req-c3", "mo-1", "2026-10-07T10:00:00Z", "11");
        createObs("req-c4", "mo-2", "2026-10-07T10:01:00Z", "11");

        // 同一 requestId、相同参数并发提交 4 次：全部返回同一分析，只落库一条
        List<Callable<ExcursionAnalysisService.AnalysisOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> excursionAnalysisService.create(
                    new CreateExcursionAnalysisRequest("req-an2", "analyst",
                            List.of(new CreateExcursionAnalysisRequest.ObservationRef("mo-1", 1),
                                    new CreateExcursionAnalysisRequest.ObservationRef("mo-2", 1)),
                            "10", 2)));
        }
        List<ExcursionAnalysisService.AnalysisOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes.stream().map(o -> o.body().analysisId()).distinct()).hasSize(1);
        assertThat(analysisCount()).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-an2'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentCreateSameStationMinuteYieldsSingleRecord() throws Exception {
        // 同站同分钟并发创建（不同标识、不同 requestId）：唯一键串行化，恰好一个成功，不泄漏约束异常
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    minuteObservationService.create(new CreateMinuteObservationRequest(
                            "req-cc" + index, "tester", "mo-c" + index, "ST-A",
                            "2026-10-07T10:00:00Z", "11"));
                    return "ok";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        assertThat(results).filteredOn("ok"::equals).hasSize(1);
        assertThat(results).filteredOn("409"::equals).hasSize(2);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM minute_observation_current WHERE station_id = 'ST-A'", Integer.class);
        assertThat(rows).isEqualTo(1);
    }
}
