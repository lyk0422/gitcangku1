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
 * 圆弧观测并发与幂等边界测试：真实并发提交，验证裁定与删除共同核对版本串行化、
 * 同键并发裁定只占一键、并发合并冲突只产生一个成功版本且落库未解决冲突标记。
 */
@SpringBootTest
class ArcObservationConcurrencyTest {

    @Autowired
    private ArcObservationService arcObservationService;

    @Autowired
    private ArcAdjudicationService arcAdjudicationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM arc_adjudication_component");
        jdbcTemplate.update("DELETE FROM arc_adjudication_source_arc");
        jdbcTemplate.update("DELETE FROM arc_adjudication_source");
        jdbcTemplate.update("DELETE FROM arc_adjudication");
        jdbcTemplate.update("DELETE FROM arc_observation_arc");
        jdbcTemplate.update("DELETE FROM arc_observation_version");
        jdbcTemplate.update("DELETE FROM arc_observation_current");
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

    private void createArc(String id, String requestId, int startAngle, int arcLength) {
        arcObservationService.create(new CreateArcObservationRequest(
                requestId, id, "op-setup", List.of(new ArcComponent(startAngle, arcLength))));
    }

    private String adjudicateStatus(String requestId, String adjudicationId, List<ArcSourceRef> refs) {
        try {
            ArcAdjudicationService.AdjudicateOutcome outcome = arcAdjudicationService.adjudicate(
                    new AdjudicateArcRequest(requestId, adjudicationId, "op-adj", refs));
            return "adj-" + outcome.status();
        } catch (ApiException e) {
            return "adj-" + e.status().value();
        }
    }

    @Test
    void concurrentAdjudicationAndDeleteSerializeOnVersions() throws Exception {
        createArc("arc-ca", "req-ca0", 350000, 20000);
        createArc("arc-cb", "req-cb0", 355000, 10000);

        List<Callable<String>> tasks = List.of(
                () -> adjudicateStatus("req-cd1", "adj-c1",
                        List.of(new ArcSourceRef("arc-ca", 1), new ArcSourceRef("arc-cb", 1))),
                () -> {
                    try {
                        arcObservationService.delete("arc-ca",
                                new DeleteArcObservationRequest("req-cd2", 1, "op-del"));
                        return "del-200";
                    } catch (ApiException e) {
                        return "del-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 串行化后：删除一定成功；裁定先于删除则 201，后于删除则因版本不是当前而 409
        assertThat(results).contains("del-200");
        assertThat(results).anyMatch(r -> r.equals("adj-201") || r.equals("adj-409"));
        assertThat(results).doesNotContain("adj-500");

        ArcObservationResponse current = arcObservationService.getCurrent("arc-ca");
        assertThat(current.deleted()).isTrue();
        assertThat(current.version()).isEqualTo(2);
        Integer adjudications = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM arc_adjudication", Integer.class);
        if (results.contains("adj-201")) {
            // 裁定先生效：冻结结果保留源版本 1 的原始圆弧，不被后续删除倒改
            assertThat(adjudications).isEqualTo(1);
            AdjudicationRecord record = arcAdjudicationService.getAdjudication("adj-c1");
            assertThat(record.sources()).anyMatch(s -> s.observationId().equals("arc-ca")
                    && s.version() == 1
                    && s.arcs().equals(List.of(new ArcComponent(350000, 20000))));
            assertThat(record.components()).containsExactly(new ArcComponent(355000, 10000));
        } else {
            assertThat(adjudications).isZero();
        }
    }

    @Test
    void concurrentSameRequestIdAdjudicationReplaysSingleResult() throws Exception {
        createArc("arc-ci", "req-ci0", 350000, 20000);
        createArc("arc-cj", "req-cj0", 355000, 10000);

        // 同一 requestId、相同参数并发裁定 4 次：全部返回同一裁定，只落库一条
        List<Callable<ArcAdjudicationService.AdjudicateOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> arcAdjudicationService.adjudicate(new AdjudicateArcRequest(
                    "req-ci1", "adj-c2", "op-adj",
                    List.of(new ArcSourceRef("arc-ci", 1), new ArcSourceRef("arc-cj", 1)))));
        }
        List<ArcAdjudicationService.AdjudicateOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201 || outcome.status() == 200);
        assertThat(outcomes).allMatch(outcome -> outcome.body().adjudicationId().equals("adj-c2"));
        assertThat(outcomes).allMatch(outcome -> outcome.body().components()
                .equals(List.of(new ArcComponent(355000, 10000))));
        Integer adjudications = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM arc_adjudication", Integer.class);
        assertThat(adjudications).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-ci1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentConflictingMergesYieldSingleSuccessAndPendingFlag() throws Exception {
        createArc("arc-cm", "req-cm0", 10000, 5000);

        // 两个离线端都基于版本 1 整组改圆弧：恰好一个成功，另一个整组冲突 409
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        arcObservationService.merge("arc-cm", new MergeArcObservationRequest(
                                "req-cm1", 1, "op-1", List.of(new ArcComponent(20000, 5000))));
                        return "m1-200";
                    } catch (ApiException e) {
                        return "m1-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        arcObservationService.merge("arc-cm", new MergeArcObservationRequest(
                                "req-cm2", 1, "op-2", List.of(new ArcComponent(30000, 5000))));
                        return "m2-200";
                    } catch (ApiException e) {
                        return "m2-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder(
                results.contains("m1-200") ? "m2-409" : "m1-409",
                results.contains("m1-200") ? "m1-200" : "m2-200");

        ArcObservationResponse current = arcObservationService.getCurrent("arc-cm");
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.conflictPending()).isTrue();
        int expectedStart = results.contains("m1-200") ? 20000 : 30000;
        assertThat(current.arcs()).containsExactly(new ArcComponent(expectedStart, 5000));
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM arc_observation_version WHERE observation_id = 'arc-cm'",
                Integer.class);
        assertThat(versionRows).isEqualTo(2);
        // 冲突失败不占键：失败的 requestId 可复用
        String failedRequestId = results.contains("m1-200") ? "req-cm2" : "req-cm1";
        Integer failedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, failedRequestId);
        assertThat(failedRows).isZero();
    }
}
