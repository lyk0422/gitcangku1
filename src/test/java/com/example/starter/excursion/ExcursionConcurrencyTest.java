package com.example.starter.excursion;

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
 * 越限观测与分析的并发边界测试：真实并发提交，验证分析与修订/删除共同核验、
 * 同键并发重放只占一键、同站同分钟唯一约束在竞争下不泄漏异常。
 */
@SpringBootTest
class ExcursionConcurrencyTest {

    @Autowired
    private ExcursionService excursionService;

    @Autowired
    private ExcursionAnalysisService analysisService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM excursion_analysis");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM excursion_version");
        jdbcTemplate.update("DELETE FROM excursion_current");
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

    private void createObs(String requestId, String id, String stationId, String minuteUtc, String reading) {
        excursionService.create(new CreateExcursionRequest(requestId, "tester", id, stationId, minuteUtc, reading));
    }

    private AnalyzeExcursionRequest analyzeRequest(String requestId, int n, String... idAndVersionPairs) {
        List<AnalyzeExcursionRequest.ObservationRef> refs = new ArrayList<>();
        for (int i = 0; i < idAndVersionPairs.length; i += 2) {
            refs.add(new AnalyzeExcursionRequest.ObservationRef(
                    idAndVersionPairs[i], Integer.parseInt(idAndVersionPairs[i + 1])));
        }
        return new AnalyzeExcursionRequest(requestId, "analyst", "10", n, refs);
    }

    @Test
    void concurrentAnalyzeAndMergeAreMutuallyVerified() throws Exception {
        createObs("req-c0", "obs-c0", "ST-1", "2026-10-07T10:00:00Z", "11");
        createObs("req-c1", "obs-c1", "ST-1", "2026-10-07T10:01:00Z", "11");

        // 分析引用 obs-c1@1；并发合并把 obs-c1 推进到版本 2
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        analysisService.analyze(analyzeRequest("req-ca", 2, "obs-c0", "1", "obs-c1", "1"));
                        return "analyze-ok";
                    } catch (ApiException e) {
                        return "analyze-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        excursionService.merge("obs-c1", new MergeExcursionRequest(
                                "req-cm", "tester", 1, "ST-1", "2026-10-07T10:01:00Z", "12"));
                        return "merge-ok";
                    } catch (ApiException e) {
                        return "merge-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 行锁串行化：合并一定成功；分析要么先锁定成功（引用 v1），要么看到 v2 后 409
        assertThat(results).contains("merge-ok");
        assertThat(results).anyMatch(r -> r.equals("analyze-ok") || r.equals("analyze-409"));

        ExcursionResponse current = excursionService.getCurrent("obs-c1");
        assertThat(current.version()).isEqualTo(2);
        Integer analysisRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM excursion_analysis", Integer.class);
        if (results.contains("analyze-ok")) {
            // 分析先成功：结果冻结在 v1 引用上，源已更新
            assertThat(analysisRows).isEqualTo(1);
            String analysisId = jdbcTemplate.queryForObject(
                    "SELECT analysis_id FROM excursion_analysis", String.class);
            assertThat(analysisService.getAnalysis(analysisId).sourceUpdated()).isTrue();
            assertThat(analysisService.getAnalysis(analysisId).intervals()).hasSize(1);
        } else {
            // 合并先成功：分析 409，整次不写分析
            assertThat(analysisRows).isZero();
        }
    }

    @Test
    void concurrentAnalyzeAndDeleteNeverProducePartialAnalysis() throws Exception {
        createObs("req-d0", "obs-d0", "ST-1", "2026-10-07T10:00:00Z", "11");
        createObs("req-d1", "obs-d1", "ST-1", "2026-10-07T10:01:00Z", "11");

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        analysisService.analyze(analyzeRequest("req-da", 2, "obs-d0", "1", "obs-d1", "1"));
                        return "analyze-ok";
                    } catch (ApiException e) {
                        return "analyze-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        excursionService.delete("obs-d1", new DeleteExcursionRequest("req-dd", "tester", 1));
                        return "delete-ok";
                    } catch (ApiException e) {
                        return "delete-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).contains("delete-ok");
        // 分析要么先锁定成功，要么看到墓碑后 422（或版本前进后 409）
        assertThat(results).anyMatch(r -> r.equals("analyze-ok") || r.equals("analyze-422")
                || r.equals("analyze-409"));
        ExcursionResponse tombstone = excursionService.getCurrent("obs-d1");
        assertThat(tombstone.deleted()).isTrue();
        Integer analysisRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM excursion_analysis", Integer.class);
        if (results.contains("analyze-ok")) {
            assertThat(analysisRows).isEqualTo(1);
        } else {
            assertThat(analysisRows).isZero();
        }
    }

    @Test
    void concurrentSameRequestIdAnalyzeReplaysSingleResult() throws Exception {
        createObs("req-e0", "obs-e0", "ST-1", "2026-10-07T10:00:00Z", "11");
        createObs("req-e1", "obs-e1", "ST-1", "2026-10-07T10:01:00Z", "11");

        List<Callable<ExcursionAnalysisService.AnalyzeOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> analysisService.analyze(analyzeRequest("req-ea", 2, "obs-e0", "1", "obs-e1", "1")));
        }
        List<ExcursionAnalysisService.AnalyzeOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome -> outcome.body().intervals().size() == 1);
        // 全部返回同一分析结果
        assertThat(outcomes.stream().map(o -> o.body().analysisId()).distinct().count()).isEqualTo(1);
        Integer analysisRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM excursion_analysis", Integer.class);
        assertThat(analysisRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-ea'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentCreatesOnSameStationMinuteYieldSingleRecord() throws Exception {
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    excursionService.create(new CreateExcursionRequest("req-f" + index, "tester",
                            "obs-f" + index, "ST-1", "2026-10-07T10:00:00Z", "11"));
                    return "ok";
                } catch (ApiException e) {
                    return String.valueOf(e.status().value());
                }
            });
        }
        List<String> results = runConcurrently(tasks);

        // 同站同分钟唯一约束串行化竞争：恰好一个成功，其余 409，不泄漏唯一键异常
        assertThat(results).filteredOn("ok"::equals).hasSize(1);
        assertThat(results).filteredOn("409"::equals).hasSize(2);
        Integer currentRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM excursion_current WHERE station_id = 'ST-1'", Integer.class);
        assertThat(currentRows).isEqualTo(1);
    }
}
