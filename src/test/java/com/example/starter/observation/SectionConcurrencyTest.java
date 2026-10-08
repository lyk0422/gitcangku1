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
 * 断面成果与 SECTION 观测的并发边界测试：真实并发提交，验证幂等去重只占一键、
 * 唯一键竞争给出业务结果、源版本并发变更时成果与合并串行化。
 */
@SpringBootTest
class SectionConcurrencyTest {

    @Autowired
    private SectionResultService sectionResultService;

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM section_result");
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

    private void createSection(String id, String requestId, String sectionNo, int lateralMm, int elevationMm) {
        observationService.create(new CreateObservationRequest(requestId, id, "SECTION",
                null, null, null, sectionNo, lateralMm, elevationMm));
    }

    private CreateSectionResultRequest resultRequest(String requestId, String resultId, long waterLevelMm) {
        return new CreateSectionResultRequest(requestId, resultId, "operator-a",
                List.of(new CreateSectionResultRequest.SourceRef("sec-a", 1),
                        new CreateSectionResultRequest.SourceRef("sec-b", 1)),
                waterLevelMm);
    }

    private int statusOf(Callable<SectionResultService.SectionResultOutcome> task) {
        try {
            return task.call().status();
        } catch (ApiException e) {
            return e.status().value();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void concurrentSameRequestIdCreatesSingleResult() throws Exception {
        createSection("sec-a", "req-c0", "S1", 0, 0);
        createSection("sec-b", "req-c1", "S1", 10, 10);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            CreateSectionResultRequest request = resultRequest("req-cr1", "result-1", 5);
            tasks.add(() -> statusOf(() -> sectionResultService.create(request)));
        }
        List<Integer> statuses = runConcurrently(tasks);

        assertThat(statuses).containsOnly(201);
        Integer resultRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM section_result WHERE result_id = 'result-1'", Integer.class);
        assertThat(resultRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-cr1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        SectionResultResponse response = sectionResultService.get("result-1");
        assertThat(response.areaExact()).isEqualTo("25/2");
    }

    @Test
    void concurrentDifferentResultIdsBothSucceed() throws Exception {
        createSection("sec-a", "req-c2", "S1", 0, 0);
        createSection("sec-b", "req-c3", "S1", 10, 10);

        List<Callable<Integer>> tasks = List.of(
                () -> statusOf(() -> sectionResultService.create(resultRequest("req-cr2", "result-1", 5))),
                () -> statusOf(() -> sectionResultService.create(resultRequest("req-cr3", "result-2", 5))));
        List<Integer> statuses = runConcurrently(tasks);

        assertThat(statuses).containsOnly(201);
        Integer resultRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM section_result", Integer.class);
        assertThat(resultRows).isEqualTo(2);
    }

    @Test
    void concurrentSameResultIdDifferentParamsYieldSingleResult() throws Exception {
        createSection("sec-a", "req-c4", "S1", 0, 0);
        createSection("sec-b", "req-c5", "S1", 10, 10);

        List<Callable<Integer>> tasks = List.of(
                () -> statusOf(() -> sectionResultService.create(resultRequest("req-cr4", "result-1", 5))),
                () -> statusOf(() -> sectionResultService.create(resultRequest("req-cr5", "result-1", 8))));
        List<Integer> statuses = runConcurrently(tasks);

        // 唯一键竞争给出业务结果：恰好一个 201，另一个异参 409
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        Integer resultRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM section_result WHERE result_id = 'result-1'", Integer.class);
        assertThat(resultRows).isEqualTo(1);
    }

    @Test
    void concurrentResultCreationAndSourceMergeSerialize() throws Exception {
        createSection("sec-a", "req-c6", "S1", 0, 0);
        createSection("sec-b", "req-c7", "S1", 10, 10);

        List<Callable<String>> tasks = List.of(
                () -> String.valueOf(statusOf(
                        () -> sectionResultService.create(resultRequest("req-cr6", "result-1", 5)))),
                () -> {
                    try {
                        observationService.merge("sec-b",
                                new MergeObservationRequest("req-cr7", 1,
                                        null, null, null, "S1", 10, 20));
                        return "merge-200";
                    } catch (ApiException e) {
                        return "merge-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 合并不受成果创建影响，始终成功
        assertThat(results).contains("merge-200");
        String resultStatus = results.stream().filter(r -> !r.startsWith("merge-")).findFirst().orElseThrow();
        ObservationResponse current = observationService.getCurrent("sec-b");
        assertThat(current.version()).isEqualTo(2);
        if ("201".equals(resultStatus)) {
            // 成果先提交：冻结版本 1，源随后更新
            SectionResultResponse response = sectionResultService.get("result-1");
            assertThat(response.sourceUpdated()).isTrue();
            assertThat(response.areaExact()).isEqualTo("25/2");
        } else {
            // 合并先提交：成果看到旧期望版本，409 且不生成成果
            assertThat(resultStatus).isEqualTo("409");
            Integer resultRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM section_result", Integer.class);
            assertThat(resultRows).isZero();
        }
    }

    @Test
    void concurrentSectionMergesOnSameGroupYieldSingleWinner() throws Exception {
        createSection("sec-a", "req-c8", "S1", 0, 0);

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        observationService.merge("sec-a",
                                new MergeObservationRequest("req-cr8", 1,
                                        null, null, null, "S1", 10, 5));
                        return "200";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        observationService.merge("sec-a",
                                new MergeObservationRequest("req-cr9", 1,
                                        null, null, null, "S1", 20, 8));
                        return "200";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 版本组整体冲突：恰好一个成功，另一个 409，版本只前进一次
        assertThat(results).containsExactlyInAnyOrder("200", "409");
        ObservationResponse current = observationService.getCurrent("sec-a");
        assertThat(current.version()).isEqualTo(2);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'sec-a'", Integer.class);
        assertThat(versionRows).isEqualTo(2);
    }
}
