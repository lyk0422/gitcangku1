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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结果与分析的并发边界（真实多线程，超时兜底）：
 * 并发提交结果全部成功且版本唯一；关闭与提交串行决定最终集合；
 * 同 requestId 并发分析恰好一条统计记录且全部回放一致。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalysisConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private void createExperiment(String exp, String requestId) {
        assertEquals(201, post("/api/experiments/" + exp,
                headers("c1", "COORDINATOR", requestId), "{\"blockCount\":2}")
                .getStatusCode().value());
    }

    private void register(String exp, String pid, String requestId) {
        assertEquals(201, post("/api/experiments/" + exp + "/participants/" + pid + "/allocations",
                headers("c1", "COORDINATOR", requestId), null).getStatusCode().value());
    }

    @Test
    void concurrentResultSubmissions_allSucceedWithVersionOne() throws Exception {
        createExperiment("ACC-1", "acc1-create");
        for (int i = 1; i <= 8; i++) {
            register("ACC-1", "P" + i, "acc1-alloc-" + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= 8; i++) {
                final String pid = "P" + i;
                final String reqId = "acc1-sub-" + i;
                futures.add(pool.submit(() -> post(
                        "/api/experiments/ACC-1/participants/" + pid + "/results",
                        headers("c1", "COORDINATOR", reqId), "{\"value\":50}")
                        .getStatusCode().value()));
            }
            for (Future<Integer> future : futures) {
                assertEquals(201, future.get(30, TimeUnit.SECONDS).intValue());
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM result_version WHERE experiment_id = 'ACC-1' AND version = 1",
                Integer.class));
    }

    @Test
    void concurrentCloseAndSubmit_serializesFinalSet() throws Exception {
        createExperiment("ACC-2", "acc2-create");
        for (int i = 1; i <= 4; i++) {
            register("ACC-2", "P" + i, "acc2-alloc-" + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(() -> post("/api/experiments/ACC-2/close",
                    headers("c1", "COORDINATOR", "acc2-close"), null).getStatusCode().value()));
            for (int i = 1; i <= 4; i++) {
                final String pid = "P" + i;
                final String reqId = "acc2-sub-" + i;
                futures.add(pool.submit(() -> post(
                        "/api/experiments/ACC-2/participants/" + pid + "/results",
                        headers("c1", "COORDINATOR", reqId), "{\"value\":60}")
                        .getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            // 关闭成功；每个提交要么先于关闭成功（201），要么被串行拒绝（409）
            assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "关闭恰好成功一次");
            long submitted = statuses.stream().filter(s -> s == 201).count();
            long rejected = statuses.stream().filter(s -> s == 409).count();
            assertEquals(4, submitted + rejected, "提交结果只能是 201 或 409");
            // 最终集合与串行结果一致：库中结果数 == 成功提交数
            assertEquals((int) submitted, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM result_version WHERE experiment_id = 'ACC-2'",
                    Integer.class));
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM experiment WHERE id = 'ACC-2'", String.class));
    }

    @Test
    void sameRequestIdConcurrentAnalysis_exactlyOneRecordAllReplaySame() throws Exception {
        createExperiment("ACC-3", "acc3-create");
        for (int i = 1; i <= 4; i++) {
            register("ACC-3", "P" + i, "acc3-alloc-" + i);
            assertEquals(201, post("/api/experiments/ACC-3/participants/P" + i + "/results",
                    headers("c1", "COORDINATOR", "acc3-sub-" + i), "{\"value\":" + (i * 10) + "}")
                    .getStatusCode().value());
        }
        assertEquals(200, post("/api/experiments/ACC-3/close",
                headers("c1", "COORDINATOR", "acc3-close"), null).getStatusCode().value());

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> post("/api/experiments/ACC-3/analyses",
                        headers("r1", "REVIEWER", "acc3-an-key"),
                        "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\"],\"resultVersion\":1}")));
            }
            Set<String> bodies = new HashSet<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
                assertEquals(201, response.getStatusCode().value(), "同键同参并发均应回放成功");
                bodies.add(response.getBody());
            }
            assertEquals(1, bodies.size(), "所有回放响应体必须一致");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM analysis WHERE experiment_id = 'ACC-3'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'acc3-an-key'",
                Integer.class));
    }
}
