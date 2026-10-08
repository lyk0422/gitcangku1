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
 * 圆弧裁定并发边界测试：真实并发提交，验证裁定与合并/删除共同核对版本、
 * 同键并发重放只占一键、同 adjudicationId 并发只生成一条裁定记录。
 */
@SpringBootTest
class ArcAdjudicationConcurrencyTest {

    @Autowired
    private ArcObservationService arcObservationService;

    @Autowired
    private ObservationService observationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM arc_adjudication");
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

    private void createArc(String id, int startAngle, int arcLength, String requestId) {
        arcObservationService.create(new CreateArcObservationRequest(
                requestId, id, startAngle, arcLength, "operator-setup"));
    }

    private AdjudicateArcsRequest adjudicateRequest(String requestId, String adjudicationId,
                                                    String... observationIds) {
        List<AdjudicateArcsRequest.ArcReference> references = new ArrayList<>();
        for (String observationId : observationIds) {
            references.add(new AdjudicateArcsRequest.ArcReference(observationId, 1));
        }
        return new AdjudicateArcsRequest(requestId, adjudicationId, "operator-a", references);
    }

    @Test
    void concurrentAdjudicateAndMergeCheckVersionsJointly() throws Exception {
        createArc("arc-c1", 350000, 20000, "req-cc1");
        createArc("arc-c2", 355000, 10000, "req-cc2");

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        arcObservationService.adjudicate(adjudicateRequest("req-cc3", "adj-c1", "arc-c1", "arc-c2"));
                        return "adjudicate-201";
                    } catch (ApiException e) {
                        return "adjudicate-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        arcObservationService.merge("arc-c1", new MergeArcObservationRequest(
                                "req-cc4", 1, 300000, 5000, "operator-b"));
                        return "merge-200";
                    } catch (ApiException e) {
                        return "merge-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 合并不受裁定影响必然成功；裁定先拿到锁则 201（冻结版本 1），否则版本不是当前 409
        assertThat(results).contains("merge-200");
        assertThat(results).anyMatch(result -> result.equals("adjudicate-201") || result.equals("adjudicate-409"));
        ObservationResponse current = observationService.getCurrent("arc-c1");
        assertThat(current.version()).isEqualTo(2);
        if (results.contains("adjudicate-201")) {
            ArcAdjudicationRecord record = arcObservationService.getAdjudication("adj-c1");
            assertThat(record.sources()).allMatch(source -> source.version() == 1);
            assertThat(record.components()).containsExactly(new CircularArc(355000, 10000));
        } else {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM arc_adjudication", Integer.class);
            assertThat(count).isZero();
        }
    }

    @Test
    void concurrentSameAdjudicateRequestReplaysSingleResult() throws Exception {
        createArc("arc-c3", 350000, 20000, "req-ci1");
        createArc("arc-c4", 355000, 10000, "req-ci2");

        List<Callable<ArcObservationService.AdjudicateOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> arcObservationService.adjudicate(
                    adjudicateRequest("req-ci3", "adj-c2", "arc-c3", "arc-c4")));
        }
        List<ArcObservationService.AdjudicateOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome -> outcome.body().adjudicationId().equals("adj-c2"));
        assertThat(outcomes).allMatch(outcome -> outcome.body().components()
                .equals(List.of(new CircularArc(355000, 10000))));
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM arc_adjudication", Integer.class);
        assertThat(records).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-ci3'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentSameAdjudicationIdYieldsSingleRecord() throws Exception {
        createArc("arc-c5", 350000, 20000, "req-ck1");
        createArc("arc-c6", 355000, 10000, "req-ck2");

        List<Callable<ArcObservationService.AdjudicateOutcome>> tasks = List.of(
                () -> arcObservationService.adjudicate(adjudicateRequest("req-ck3", "adj-c3", "arc-c5", "arc-c6")),
                () -> arcObservationService.adjudicate(adjudicateRequest("req-ck4", "adj-c3", "arc-c6", "arc-c5")));
        List<ArcObservationService.AdjudicateOutcome> outcomes = runConcurrently(tasks);

        // 引用集合相同（顺序不同）：两个请求都应得到同一裁定结果，记录只有一条
        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM arc_adjudication WHERE adjudication_id = 'adj-c3'", Integer.class);
        assertThat(records).isEqualTo(1);
    }

    @Test
    void concurrentAdjudicateAndDeleteNeverUsesTombstone() throws Exception {
        createArc("arc-c7", 350000, 20000, "req-cd1");
        createArc("arc-c8", 355000, 10000, "req-cd2");

        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        arcObservationService.adjudicate(adjudicateRequest("req-cd3", "adj-c4", "arc-c7", "arc-c8"));
                        return "adjudicate-201";
                    } catch (ApiException e) {
                        return "adjudicate-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        observationService.delete("arc-c8", new DeleteObservationRequest("req-cd4", 1));
                        return "delete-200";
                    } catch (ApiException e) {
                        return "delete-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        // 删除必然成功；裁定先于删除则 201，否则引用墓碑 422
        assertThat(results).contains("delete-200");
        assertThat(results).anyMatch(result -> result.equals("adjudicate-201") || result.equals("adjudicate-422"));
        ObservationResponse current = observationService.getCurrent("arc-c8");
        assertThat(current.deleted()).isTrue();
        if (results.contains("adjudicate-201")) {
            ArcAdjudicationRecord record = arcObservationService.getAdjudication("adj-c4");
            assertThat(record.components()).containsExactly(new CircularArc(355000, 10000));
        }
    }
}
