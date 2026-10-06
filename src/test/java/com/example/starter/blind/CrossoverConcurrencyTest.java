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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交叉阶段并发裁决（真实多线程、真实 H2 事务）：
 * 同阶段并发开始仅一次成功；结束与退组并发由实验行锁串行裁决，结果必为两种合法终态之一。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrossoverConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private int post(String path, String requestId) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(null, headers("c1", "COORDINATOR", requestId)), String.class)
                .getStatusCode().value();
    }

    private void createCrossoverAndRegister(String expId, String pid, String keyPrefix) {
        assertEquals(201, rest.exchange("/api/experiments/" + expId, HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":2,\"washoutMinutes\":5}",
                        headers("c1", "COORDINATOR", keyPrefix + "-create")), String.class)
                .getStatusCode().value());
        assertEquals(201, post(
                "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                keyPrefix + "-alloc"));
    }

    @Test
    void concurrentStartFirstPhase_exactlyOneSuccess() throws Exception {
        createCrossoverAndRegister("EXP-CC1", "P1", "req-cc1");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String reqId = "req-cc1-s1-" + i;
                futures.add(pool.submit(() -> post(
                        "/api/experiments/EXP-CC1/participants/P1/phases/1/start", reqId)));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long success = statuses.stream().filter(s -> s == 200).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, success, "同阶段并发开始仅一次成功");
            assertEquals(threads - 1L, conflict, "其余全部重复开始冲突 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终数据：第一阶段恰好开始一次，版本为 1
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM crossover_phase WHERE experiment_id = 'EXP-CC1'",
                Integer.class));
        assertNotNull(jdbc.queryForObject(
                "SELECT first_started_at FROM crossover_phase WHERE experiment_id = 'EXP-CC1'",
                Long.class));
    }

    @Test
    void concurrentEndAndWithdraw_seriallyAdjudicated() throws Exception {
        createCrossoverAndRegister("EXP-CC2", "P1", "req-cc2");
        assertEquals(200, post("/api/experiments/EXP-CC2/participants/P1/phases/1/start",
                "req-cc2-s1"));

        // 结束第一阶段与退组并发：两者共用实验行锁串行裁决
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> endFuture;
        Future<Integer> withdrawFuture;
        try {
            endFuture = pool.submit(() -> post(
                    "/api/experiments/EXP-CC2/participants/P1/phases/1/end", "req-cc2-e1"));
            withdrawFuture = pool.submit(() -> post(
                    "/api/experiments/EXP-CC2/participants/P1/withdrawal", "req-cc2-wd"));
            int endStatus = endFuture.get(30, TimeUnit.SECONDS);
            int withdrawStatus = withdrawFuture.get(30, TimeUnit.SECONDS);

            // 退组必成功；结束要么先于退组成功（200），要么在退组后被拒（410）
            assertEquals(200, withdrawStatus, "退组不受阶段状态影响，必须成功");
            assertTrue(endStatus == 200 || endStatus == 410,
                    "结束阶段只能 200（先裁决）或 410（退组后），实际: " + endStatus);
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终数据与裁决结果一致：退组成立；结束成功则时间戳已写入，否则保持 NULL
        assertEquals("WITHDRAWN", jdbc.queryForObject(
                "SELECT status FROM allocation WHERE experiment_id = 'EXP-CC2'",
                String.class));
        Long firstEndedAt = jdbc.queryForObject(
                "SELECT first_ended_at FROM crossover_phase WHERE experiment_id = 'EXP-CC2'",
                Long.class);
        ResponseEntity<String> view = rest.exchange(
                "/api/experiments/EXP-CC2/participants/P1/phases", HttpMethod.GET,
                new HttpEntity<>(null, headers("c1", "COORDINATOR", null)), String.class);
        assertEquals(200, view.getStatusCode().value());
        if (firstEndedAt == null) {
            assertEquals(1, jdbc.queryForObject(
                    "SELECT version FROM crossover_phase WHERE experiment_id = 'EXP-CC2'",
                    Integer.class));
        } else {
            assertEquals(2, jdbc.queryForObject(
                    "SELECT version FROM crossover_phase WHERE experiment_id = 'EXP-CC2'",
                    Integer.class));
        }
        // 退组后任何新阶段操作 410
        assertEquals(410, post("/api/experiments/EXP-CC2/participants/P1/phases/2/start",
                "req-cc2-s2"));
    }

    @Test
    void concurrentSameRequestIdStart_exactlyOneExecutionAllReplay() throws Exception {
        createCrossoverAndRegister("EXP-CC3", "P1", "req-cc3");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> rest.exchange(
                        "/api/experiments/EXP-CC3/participants/P1/phases/1/start",
                        HttpMethod.POST,
                        new HttpEntity<>(null, headers("c1", "COORDINATOR", "req-cc3-same")),
                        String.class)));
            }
            List<String> bodies = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
                assertEquals(200, response.getStatusCode().value(), "同键同参并发均应回放成功");
                bodies.add(response.getBody());
            }
            assertEquals(1, bodies.stream().distinct().count(), "所有回放响应体必须一致");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 业务恰好执行一次
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM crossover_phase WHERE experiment_id = 'EXP-CC3'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'req-cc3-same'",
                Integer.class));
    }
}
