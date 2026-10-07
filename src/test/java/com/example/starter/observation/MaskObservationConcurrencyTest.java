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
 * 栅格观测并发与幂等边界（真实 H2 + 真实并发线程）：
 * 后到事务必须在行锁内基于最新服务器版重算整张合并图再做连通核验，不能只验两张各自合法的图。
 */
@SpringBootTest
class MaskObservationConcurrencyTest {

    @Autowired
    private MaskObservationService maskObservationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM mask_version_cell");
        jdbcTemplate.update("DELETE FROM mask_observation_version");
        jdbcTemplate.update("DELETE FROM mask_observation_current");
    }

    private static List<Integer> cells(String... rows) {
        List<Integer> values = new ArrayList<>();
        for (String row : rows) {
            for (int col = 0; col < MaskGrid.SIZE; col++) {
                values.add(row.charAt(col) == '1' ? 1 : 0);
            }
        }
        return values;
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

    private record AttemptResult(int status, Integer version) {
    }

    private AttemptResult attemptMerge(String requestId, String id, int baseVersion,
                                       String operator, List<Integer> cells) {
        try {
            MaskObservationService.MaskOutcome outcome = maskObservationService.merge(id,
                    new MergeMaskRequest(requestId, baseVersion, operator, cells));
            return new AttemptResult(outcome.status(), outcome.body().version());
        } catch (ApiException e) {
            return new AttemptResult(e.status().value(), e.currentVersion());
        }
    }

    @Test
    void concurrentDeletionsRecomputeWholeMergeAndRejectDisconnectedLoser() throws Exception {
        // 基版田字 (1,1)(1,2)(2,1)(2,2)
        maskObservationService.create(new CreateMaskRequest("req-cc0", "mask-c1", "root",
                cells("1100", "1100", "0000", "0000")));

        // 两个客户端都基于版本 1：A 删 (1,2)=下标1，B 删 (2,1)=下标4；两张完整图各自连通，
        // 但先后应用后剩斜角两格。后到者必须基于版本 2 重算并得到 422，而不能各自判合法后落库。
        List<Callable<AttemptResult>> tasks = List.of(
                () -> attemptMerge("req-cc1", "mask-c1", 1, "clientA",
                        cells("1000", "1100", "0000", "0000")),
                () -> attemptMerge("req-cc2", "mask-c1", 1, "clientB",
                        cells("1100", "0100", "0000", "0000")));
        List<AttemptResult> results = runConcurrently(tasks);

        assertThat(results).hasSize(2);
        AttemptResult winner = results.stream().filter(r -> r.status() == 200).findFirst().orElseThrow();
        AttemptResult loser = results.stream().filter(r -> r.status() == 422).findFirst().orElseThrow();
        assertThat(winner.version()).isEqualTo(2);
        assertThat(loser.version()).isEqualTo(2);

        MaskResponse current = maskObservationService.getCurrent("mask-c1");
        assertThat(current.version()).isEqualTo(2);
        // 版本与格值停在先到者的连通三格，未补桥
        List<Integer> expected = current.operator().equals("clientA")
                ? cells("1000", "1100", "0000", "0000")
                : cells("1100", "0100", "0000", "0000");
        assertThat(current.cells()).isEqualTo(expected);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mask_observation_version WHERE observation_id = 'mask-c1'", Integer.class);
        assertThat(versionRows).isEqualTo(2);
    }

    @Test
    void concurrentSameRequestIdReplaysSingleMaskResult() throws Exception {
        maskObservationService.create(new CreateMaskRequest("req-ci0", "mask-c2", "root",
                cells("1100", "1100", "0000", "0000")));

        // 同 requestId 同参并发 4 次：全部返回首次结果，只占一键、只产生一个新版本
        List<Callable<AttemptResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> attemptMerge("req-ci1", "mask-c2", 1, "alice",
                    cells("1110", "1100", "0000", "0000")));
        }
        List<AttemptResult> results = runConcurrently(tasks);

        assertThat(results).allMatch(r -> r.status() == 200 && r.version() == 2);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-ci1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mask_observation_version WHERE observation_id = 'mask-c2'", Integer.class);
        assertThat(versionRows).isEqualTo(2);
    }

    @Test
    void concurrentMergeAndDeleteSerializeOnRowLock() throws Exception {
        maskObservationService.create(new CreateMaskRequest("req-cd0", "mask-c3", "root",
                cells("1100", "1100", "0000", "0000")));

        List<Callable<String>> tasks = List.of(
                () -> {
                    AttemptResult r = attemptMerge("req-cd1", "mask-c3", 1, "alice",
                            cells("1110", "1100", "0000", "0000"));
                    return "merge-" + r.status();
                },
                () -> {
                    try {
                        maskObservationService.delete("mask-c3",
                                new DeleteMaskRequest("req-cd2", 1, "root"));
                        return "delete-200";
                    } catch (ApiException e) {
                        return "delete-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 先删则合并 410；先合并则删除版本不匹配 409；墓碑绝不复活
        boolean mergeWon = results.contains("merge-200");
        assertThat(results).contains(mergeWon ? "delete-409" : "merge-410");
        MaskResponse current = maskObservationService.getCurrent("mask-c3");
        if (mergeWon) {
            assertThat(current.deleted()).isFalse();
            assertThat(current.version()).isEqualTo(2);
        } else {
            assertThat(current.deleted()).isTrue();
            assertThat(current.version()).isEqualTo(2);
            assertThat(current.cells()).isNull();
        }
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mask_observation_version WHERE observation_id = 'mask-c3'", Integer.class);
        assertThat(versionRows).isEqualTo(2);
    }
}
