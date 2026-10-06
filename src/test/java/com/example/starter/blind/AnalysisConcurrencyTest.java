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
 * 结果与分析的并发边界（真实多线程，不用睡眠代替断言）：
 * 关闭与结果提交、退组串行决定最终集合；同键并发分析恰好执行一次且全部回放一致。
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

    private void createAndRegister(String expId, String keyPrefix) throws Exception {
        assertEquals(201, post("/api/experiments/" + expId,
                headers("c1", "COORDINATOR", keyPrefix + "-create"), "{\"blockCount\":2}")
                .getStatusCode().value());
        for (int i = 1; i <= 4; i++) {
            assertEquals(201, post(
                    "/api/experiments/" + expId + "/participants/P" + i + "/allocations",
                    headers("c1", "COORDINATOR", keyPrefix + "-alloc-" + i), null)
                    .getStatusCode().value());
        }
    }

    @Test
    void concurrentSubmitAndClose_serialized_finalStateConsistent() throws Exception {
        createAndRegister("EXP-C1", "c1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> submit = pool.submit(() -> post(
                    "/api/experiments/EXP-C1/participants/P1/result",
                    headers("c1", "COORDINATOR", "c1-result"), "{\"value\":50}")
                    .getStatusCode().value());
            Future<Integer> close = pool.submit(() -> post("/api/experiments/EXP-C1/close",
                    headers("c1", "COORDINATOR", "c1-close"), null).getStatusCode().value());
            int submitStatus = submit.get(30, TimeUnit.SECONDS);
            int closeStatus = close.get(30, TimeUnit.SECONDS);

            // 串行后：关闭必成功；提交要么先于关闭成功，要么因已关闭 409
            assertEquals(200, closeStatus);
            assertTrue(submitStatus == 201 || submitStatus == 409,
                    "提交只能 201 或 409，实际: " + submitStatus);
            int resultRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM participant_result WHERE experiment_id = 'EXP-C1'",
                    Integer.class);
            assertEquals(submitStatus == 201 ? 1 : 0, resultRows,
                    "结果行数必须与提交胜负一致");
            assertEquals("CLOSED", jdbc.queryForObject(
                    "SELECT status FROM experiment WHERE id = 'EXP-C1'", String.class));
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentWithdrawAndSubmit_serialized_finalStateConsistent() throws Exception {
        createAndRegister("EXP-C2", "c2");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> withdraw = pool.submit(() -> post(
                    "/api/experiments/EXP-C2/participants/P1/withdrawal",
                    headers("c1", "COORDINATOR", "c2-withdraw"), null).getStatusCode().value());
            Future<Integer> submit = pool.submit(() -> post(
                    "/api/experiments/EXP-C2/participants/P1/result",
                    headers("c1", "COORDINATOR", "c2-result"), "{\"value\":50}")
                    .getStatusCode().value());
            int withdrawStatus = withdraw.get(30, TimeUnit.SECONDS);
            int submitStatus = submit.get(30, TimeUnit.SECONDS);

            // 退组必成功；提交要么先于退组成功，要么因已退组 409
            assertEquals(200, withdrawStatus);
            assertTrue(submitStatus == 201 || submitStatus == 409,
                    "提交只能 201 或 409，实际: " + submitStatus);
            int resultRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM participant_result WHERE experiment_id = 'EXP-C2'",
                    Integer.class);
            assertEquals(submitStatus == 201 ? 1 : 0, resultRows);
            assertEquals("WITHDRAWN", jdbc.queryForObject(
                    "SELECT status FROM allocation WHERE experiment_id = 'EXP-C2' "
                            + "AND participant_id = 'P1'", String.class));
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentAnalysis_sameKey_exactlyOneRecordAllReplaySame() throws Exception {
        createAndRegister("EXP-C3", "c3");
        int[] values = {0, 0, 100, 100};
        for (int i = 1; i <= 4; i++) {
            assertEquals(201, post(
                    "/api/experiments/EXP-C3/participants/P" + i + "/result",
                    headers("c1", "COORDINATOR", "c3-result-" + i),
                    "{\"value\":" + values[i - 1] + "}").getStatusCode().value());
        }
        assertEquals(200, post("/api/experiments/EXP-C3/close",
                headers("c1", "COORDINATOR", "c3-close"), null).getStatusCode().value());

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> post("/api/experiments/EXP-C3/analysis",
                        headers("r1", "REVIEWER", "c3-ana"),
                        "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\"],\"version\":1}")));
            }
            Set<String> bodies = new HashSet<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
                assertEquals(201, response.getStatusCode().value(), "同键并发均应回放成功");
                bodies.add(response.getBody());
            }
            assertEquals(1, bodies.size(), "所有回放响应体必须一致");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM analysis WHERE experiment_id = 'EXP-C3'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'c3-ana'",
                Integer.class));
        assertEquals("2/6", jdbc.queryForObject(
                "SELECT CONCAT(k, '/', n) FROM analysis WHERE experiment_id = 'EXP-C3'",
                String.class));
    }
}
