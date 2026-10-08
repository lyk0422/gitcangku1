package com.example.starter.crosssection;

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
 * 断面成果并发与幂等竞争测试：真实线程并发提交，验证 requestId 占位原子性、
 * resultId 唯一竞争给业务结果、源点行锁串行化与最终冻结数据一致。
 */
@SpringBootTest
class CrossSectionConcurrencyTest {

    @Autowired
    private CrossSectionService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM cross_section_result_point");
        jdbcTemplate.update("DELETE FROM cross_section_result");
        jdbcTemplate.update("DELETE FROM cross_section_resolution");
        jdbcTemplate.update("DELETE FROM cross_section_pending_conflict");
        jdbcTemplate.update("DELETE FROM cross_section_version");
        jdbcTemplate.update("DELETE FROM cross_section_current");
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<T>> synchronizedTasks = tasks.stream().<Callable<T>>map(task -> () -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return task.call();
        }).toList();
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

    private void createPoint(String reqId, String obsId, int x, int z) {
        service.create(new CreateCrossSectionRequest(reqId, obsId, "S1", x, z, "op-1"));
    }

    private CreateCrossSectionResultRequest resultRequest(String reqId, String resultId) {
        return new CreateCrossSectionResultRequest(reqId, resultId, 5,
                List.of(new CreateCrossSectionResultRequest.SourcePointRef("pt-0", 1),
                        new CreateCrossSectionResultRequest.SourcePointRef("pt-10", 1)),
                "op-1");
    }

    @Test
    void concurrentSameRequestIdCreatesExactlyOneResult() throws Exception {
        createPoint("req-c0", "pt-0", 0, 0);
        createPoint("req-c1", "pt-10", 10, 10);

        List<Callable<CrossSectionService.ResultOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> service.createResult(resultRequest("req-same", "res-same")));
        }
        List<CrossSectionService.ResultOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome ->
                "25".equals(outcome.body().areaNumerator()) && "2".equals(outcome.body().areaDenominator()));
        assertThat(outcomes).allMatch(outcome -> outcome.body().resultId().equals("res-same"));

        Integer resultRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cross_section_result", Integer.class);
        Integer pointRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cross_section_result_point", Integer.class);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-same'", Integer.class);
        assertThat(resultRows).isEqualTo(1);
        assertThat(pointRows).isEqualTo(2);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentDistinctResultIdsAllSucceedWithOwnFrozenCopies() throws Exception {
        createPoint("req-c0", "pt-0", 0, 0);
        createPoint("req-c1", "pt-10", 10, 10);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    return service.createResult(resultRequest("req-r" + index, "res-r" + index)).status();
                } catch (CrossSectionApiException e) {
                    return e.status().value();
                }
            });
        }
        List<Integer> statuses = runConcurrently(tasks);
        assertThat(statuses).containsOnly(201);

        Integer resultRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cross_section_result", Integer.class);
        Integer pointRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cross_section_result_point", Integer.class);
        assertThat(resultRows).isEqualTo(4);
        assertThat(pointRows).isEqualTo(8);
    }

    @Test
    void concurrentResultCreationAndSourceMergeSerializeOnRowLock() throws Exception {
        createPoint("req-c0", "pt-0", 0, 0);
        createPoint("req-c1", "pt-10", 10, 10);

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        service.createResult(resultRequest("req-res", "res-lock"));
                        return "result-ok";
                    } catch (CrossSectionApiException e) {
                        return "result-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        // 与成果并发修改源点高程：行锁串行化二者
                        service.merge("pt-0", new MergeCrossSectionRequest(
                                "req-merge", 1, "S1", 0, 2, "op-1"));
                        return "merge-ok";
                    } catch (CrossSectionApiException e) {
                        return "merge-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);
        assertThat(results).containsOnlyOnce("merge-ok");
        assertThat(service.getCurrent("pt-0").version()).isEqualTo(2);

        if (results.contains("result-ok")) {
            // 成果先执行：冻结旧高程 0；合并后成果查询提示源已更新但面积不重算
            CrossSectionResultResponse result = service.getResult("res-lock");
            assertThat(result.points().get(0).zMm()).isZero();
            assertThat(result.areaNumerator()).isEqualTo("25");
            assertThat(result.areaDenominator()).isEqualTo("2");
            assertThat(result.sourceUpdated()).isTrue();
        } else {
            // 合并先生效：成果按旧期望版本引用失败 409，不生成任何成果
            assertThat(results).contains("result-409");
            Integer resultRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cross_section_result", Integer.class);
            assertThat(resultRows).isZero();
        }
    }

    @Test
    void concurrentSameResultIdWithDifferentParamsConflictsOrReplays() throws Exception {
        createPoint("req-c0", "pt-0", 0, 0);
        createPoint("req-c1", "pt-10", 10, 10);

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        service.createResult(resultRequest("req-a", "res-x"));
                        return "201";
                    } catch (CrossSectionApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        // 相同 resultId、不同 requestId 且不同 H：唯一竞争后应 409
                        service.createResult(new CreateCrossSectionResultRequest(
                                "req-b", "res-x", 6,
                                List.of(new CreateCrossSectionResultRequest.SourcePointRef("pt-0", 1),
                                        new CreateCrossSectionResultRequest.SourcePointRef("pt-10", 1)),
                                "op-1"));
                        return "201";
                    } catch (CrossSectionApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);
        // 相同 resultId 唯一竞争：恰好一个成功、另一个异参 409；谁先提交谁赢，不预设胜者。
        assertThat(results).containsExactlyInAnyOrder("201", "409");

        Integer resultRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cross_section_result WHERE result_id = 'res-x'", Integer.class);
        assertThat(resultRows).isEqualTo(1);
        Integer h = jdbcTemplate.queryForObject(
                "SELECT water_level_mm FROM cross_section_result WHERE result_id = 'res-x'", Integer.class);
        assertThat(h).isIn(5, 6);
        // 落库 H 与胜者一致，败者参数不得污染成果
        Integer pointRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cross_section_result_point WHERE result_id = 'res-x'", Integer.class);
        assertThat(pointRows).isEqualTo(2);
    }
}
