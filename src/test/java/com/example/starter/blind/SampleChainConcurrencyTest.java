package com.example.starter.blind;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 样本链并发边界（真实多线程，不用睡眠代替断言）：
 * 并发锁定只形成一对；更正与锁定竞争只形成完整一致配对；
 * 退组与锁定竞争结果一致；同阶段并发登记只有一条有效记录。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SampleChainConcurrencyTest extends AbstractBlindIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private HttpHeaders headers(String actor, String role, String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Actor-Id", actor);
        h.set("X-Role", role);
        if (requestId != null) {
            h.set("X-Request-Id", requestId);
        }
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> post(String path, HttpHeaders headers, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private void setupExperiment(String expId, String keyPrefix) {
        assertEquals(201, post("/api/experiments/" + expId,
                headers("coord-1", "COORDINATOR", keyPrefix + "-create"), "{\"blockCount\":2}")
                .getStatusCode().value());
        assertEquals(201, post("/api/experiments/" + expId + "/participants/PA/allocations",
                headers("coord-1", "COORDINATOR", keyPrefix + "-alloc"), null)
                .getStatusCode().value());
    }

    private void registerBoth(String expId, String keyPrefix, long preAt, long postAt) {
        assertEquals(201, post("/api/experiments/" + expId + "/participants/PA/samples",
                headers("coord-1", "COORDINATOR", keyPrefix + "-pre"),
                "{\"phase\":\"PRE\",\"sampleCode\":\"" + keyPrefix + "-PRE\","
                        + "\"digest\":\"d-pre\",\"collectedAt\":" + preAt + "}")
                .getStatusCode().value());
        assertEquals(201, post("/api/experiments/" + expId + "/participants/PA/samples",
                headers("coord-1", "COORDINATOR", keyPrefix + "-post"),
                "{\"phase\":\"POST\",\"sampleCode\":\"" + keyPrefix + "-POST\","
                        + "\"digest\":\"d-post\",\"collectedAt\":" + postAt + "}")
                .getStatusCode().value());
    }

    private List<Integer> runConcurrent(int threads, java.util.concurrent.Callable<Integer> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(task));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentLock_sameParticipant_exactlyOnePair() throws Exception {
        setupExperiment("EXP-C1", "c1");
        registerBoth("EXP-C1", "c1", 1_700_000_010_000L, 1_700_000_020_000L);

        List<Integer> statuses = runConcurrent(6, () -> post(
                "/api/experiments/EXP-C1/participants/PA/sample-pair/lock",
                headers("coord-1", "COORDINATOR", "c1-lock-" + Thread.currentThread().getId()),
                null).getStatusCode().value());
        long created = statuses.stream().filter(s -> s == 201).count();
        long conflict = statuses.stream().filter(s -> s == 409).count();
        assertEquals(1, created, "并发锁定只能成功一次");
        assertEquals(5, conflict, "其余并发锁定必须 409");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_pair WHERE experiment_id = 'EXP-C1'", Integer.class));
    }

    @Test
    void concurrentCorrectAndLock_onlyConsistentPair() throws Exception {
        // POST 早于 PRE：锁定会 422；一个线程并发更正 POST 使其合法
        setupExperiment("EXP-C2", "c2");
        registerBoth("EXP-C2", "c2", 1_700_000_050_000L, 1_700_000_010_000L);

        List<java.util.concurrent.Callable<Integer>> tasks = new ArrayList<>();
        tasks.add(() -> post("/api/experiments/EXP-C2/participants/PA/samples/corrections",
                headers("coord-1", "COORDINATOR", "c2-correct"),
                "{\"phase\":\"POST\",\"sampleCode\":\"c2-POST-V2\","
                        + "\"digest\":\"d-post-fixed\",\"collectedAt\":1700000090000}")
                .getStatusCode().value());
        for (int i = 0; i < 4; i++) {
            final String key = "c2-lock-" + i;
            tasks.add(() -> post("/api/experiments/EXP-C2/participants/PA/sample-pair/lock",
                    headers("coord-1", "COORDINATOR", key), null).getStatusCode().value());
        }
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<Integer> task : tasks) {
                futures.add(pool.submit(task));
            }
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 更正必须成功；锁定成功数不超过 1
        assertEquals(201, statuses.get(0).intValue(), "更正应成功");
        long locks = statuses.subList(1, statuses.size()).stream().filter(s -> s == 201).count();
        assertTrue(locks <= 1, "至多一个锁定成功");
        int pairCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_pair WHERE experiment_id = 'EXP-C2'", Integer.class);
        assertEquals(locks, pairCount, "锁定成功数必须与配对行数一致");
        if (pairCount == 1) {
            // 形成的配对必须完整一致：POST 晚于 PRE，且冻结的是更正后的摘要
            String postDigest = jdbc.queryForObject(
                    "SELECT post_digest FROM sample_pair WHERE experiment_id = 'EXP-C2'",
                    String.class);
            assertEquals("d-post-fixed", postDigest);
            Long preAt = jdbc.queryForObject(
                    "SELECT pre_collected_at FROM sample_pair WHERE experiment_id = 'EXP-C2'",
                    Long.class);
            Long postAt = jdbc.queryForObject(
                    "SELECT post_collected_at FROM sample_pair WHERE experiment_id = 'EXP-C2'",
                    Long.class);
            assertTrue(postAt > preAt, "配对内 POST 必须晚于 PRE");
        }
    }

    @Test
    void concurrentWithdrawAndLock_consistentOutcome() throws Exception {
        setupExperiment("EXP-C3", "c3");
        registerBoth("EXP-C3", "c3", 1_700_000_010_000L, 1_700_000_020_000L);

        List<java.util.concurrent.Callable<Integer>> tasks = List.of(
                () -> post("/api/experiments/EXP-C3/participants/PA/withdrawal",
                        headers("coord-1", "COORDINATOR", "c3-withdraw"), null)
                        .getStatusCode().value(),
                () -> post("/api/experiments/EXP-C3/participants/PA/sample-pair/lock",
                        headers("coord-1", "COORDINATOR", "c3-lock"), null)
                        .getStatusCode().value());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<Integer> task : tasks) {
                futures.add(pool.submit(task));
            }
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        int withdrawStatus = statuses.get(0);
        int lockStatus = statuses.get(1);
        assertEquals(200, withdrawStatus, "退组应成功");
        int pairCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_pair WHERE experiment_id = 'EXP-C3'", Integer.class);
        String allocationStatus = jdbc.queryForObject(
                "SELECT status FROM allocation WHERE experiment_id = 'EXP-C3'", String.class);
        assertEquals("WITHDRAWN", allocationStatus);
        if (lockStatus == 409) {
            // 退组先胜出：不能形成新锁定
            assertEquals(0, pairCount, "退组后不得形成锁定对");
        } else {
            // 锁定先胜出：配对完整存在，退组随后生效但不撤销已锁定对
            assertEquals(201, lockStatus);
            assertEquals(1, pairCount);
        }
    }

    @Test
    void concurrentRegisterSamePhase_exactlyOneActive() throws Exception {
        setupExperiment("EXP-C4", "c4");

        List<Integer> statuses = runConcurrent(6, () -> {
            String code = "c4-PRE-" + Thread.currentThread().getId();
            String key = "c4-reg-" + Thread.currentThread().getId();
            return post("/api/experiments/EXP-C4/participants/PA/samples",
                    headers("coord-1", "COORDINATOR", key),
                    "{\"phase\":\"PRE\",\"sampleCode\":\"" + code + "\","
                            + "\"digest\":\"d\",\"collectedAt\":1700000010000}")
                    .getStatusCode().value();
        });
        long created = statuses.stream().filter(s -> s == 201).count();
        long conflict = statuses.stream().filter(s -> s == 409).count();
        assertEquals(1, created, "同阶段并发登记只能成功一个");
        assertEquals(5, conflict, "其余必须 409");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE experiment_id = 'EXP-C4' "
                        + "AND phase = 'PRE' AND status = 'ACTIVE'", Integer.class));
    }
}