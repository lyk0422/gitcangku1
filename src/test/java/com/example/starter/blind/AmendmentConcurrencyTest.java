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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发与幂等边界（真实多线程 + 真实 H2，协调开始、超时收口）：
 * 并发中心登记不重盲码、恰好容量个成功；同 requestId 并发只执行一次并全部回放；
 * 并发创建修订只允许一条待生效；到点生效同键并发只生效一次；修订指纹含操作者与规范化比例。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AmendmentConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private void createExperiment(String id, String requestId) {
        assertEquals(201, rest.exchange("/api/experiments/" + id, HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":2}",
                        headers("c1", "COORDINATOR", requestId)), String.class)
                .getStatusCode().value());
    }

    private void activateCenter(String expId, String centerId, int cap, String reqId) {
        assertEquals(201, rest.exchange(
                "/api/experiments/" + expId + "/centers/" + centerId, HttpMethod.POST,
                new HttpEntity<>("{\"targetCap\":" + cap + "}",
                        headers("c1", "COORDINATOR", reqId)), String.class)
                .getStatusCode().value());
    }

    @Test
    void concurrentCenterAllocations_exactlyCapSucceed_noCodeDoubleConsumed() throws Exception {
        createExperiment("AC-1", "ac1-create");
        activateCenter("AC-1", "CTR-A", 6, "ac1-center");

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final String pid = "CP" + i;
                final String reqId = "ac1-alloc-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return rest.exchange(
                            "/api/experiments/AC-1/centers/CTR-A/participants/" + pid + "/allocations",
                            HttpMethod.POST, new HttpEntity<>(null,
                                    headers("c1", "COORDINATOR", reqId)), String.class)
                            .getStatusCode().value();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long success = statuses.stream().filter(s -> s == 201).count();
            long full = statuses.stream().filter(s -> s == 422).count();
            assertEquals(6, success, "恰好中心容量 6 个登记成功");
            assertEquals(4, full, "其余因序列耗尽 422");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终数据：6 条分配、6 条不同盲码被消耗、占用 seq_no 1..6
        assertEquals(6, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'AC-1' AND center_id = 'CTR-A'",
                Integer.class));
        assertEquals(6, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence WHERE experiment_id = 'AC-1' "
                        + "AND center_id = 'CTR-A' AND version = 1 AND allocation_id IS NOT NULL",
                Integer.class));
        List<Integer> seqs = jdbc.queryForList(
                "SELECT seq_no FROM center_sequence WHERE experiment_id = 'AC-1' "
                        + "AND center_id = 'CTR-A' AND version = 1 AND allocation_id IS NOT NULL "
                        + "ORDER BY seq_no", Integer.class);
        assertEquals(List.of(1, 2, 3, 4, 5, 6), seqs);
        List<Long> allocationIds = jdbc.queryForList(
                "SELECT allocation_id FROM center_sequence WHERE experiment_id = 'AC-1' "
                        + "AND center_id = 'CTR-A' AND version = 1 AND allocation_id IS NOT NULL "
                        + "ORDER BY seq_no", Long.class);
        assertEquals(6, new HashSet<>(allocationIds).size(), "一条盲码不得被多个分配消耗");
    }

    @Test
    void sameRequestIdConcurrent_centerAllocation_exactlyOneExecutionAllReplay() throws Exception {
        createExperiment("AC-2", "ac2-create");
        activateCenter("AC-2", "CTR-A", 6, "ac2-center");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return rest.exchange(
                            "/api/experiments/AC-2/centers/CTR-A/participants/P1/allocations",
                            HttpMethod.POST, new HttpEntity<>(null,
                                    headers("c1", "COORDINATOR", "ac2-same-key")), String.class);
                }));
            }
            start.countDown();
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
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'AC-2'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence WHERE experiment_id = 'AC-2' "
                        + "AND allocation_id IS NOT NULL", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'ac2-same-key'",
                Integer.class));
    }

    @Test
    void concurrentAmendmentCreates_onlyOnePendingVersion() throws Exception {
        createExperiment("AC-3", "ac3-create");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final String reqId = "ac3-amd-" + i;
                final int ratio = 20 + i * 10; // 30,40,...,80；和均为100
                futures.add(pool.submit(() -> {
                    start.await();
                    return rest.exchange(
                            "/api/experiments/AC-3/protocol-amendments", HttpMethod.POST,
                            new HttpEntity<>("{\"ratioA\":" + ratio + ",\"ratioB\":" + (100 - ratio)
                                    + ",\"effectiveAt\":1700000010000}",
                                    headers("c1", "COORDINATOR", reqId)), String.class)
                            .getStatusCode().value();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long created = statuses.stream().filter(s -> s == 201).count();
            long rejected = statuses.stream().filter(s -> s == 422).count();
            assertEquals(1, created, "并发创建只允许一条待生效修订成功");
            assertEquals(threads - 1L, rejected, "其余全部 422");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_version WHERE experiment_id = 'AC-3' "
                        + "AND status = 'PENDING'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_version WHERE experiment_id = 'AC-3'",
                Integer.class), "仅 V1 + 一条 PENDING");
    }

    @Test
    void sameRequestIdConcurrent_effectAmendment_allReplayOneEffect() throws Exception {
        createExperiment("AC-4", "ac4-create");
        activateCenter("AC-4", "CTR-A", 5, "ac4-center");
        clock.setTime(1_700_000_010_000L);
        assertEquals(201, rest.exchange(
                "/api/experiments/AC-4/protocol-amendments", HttpMethod.POST,
                new HttpEntity<>("{\"ratioA\":30,\"ratioB\":70,\"effectiveAt\":1700000010000}",
                        headers("c1", "COORDINATOR", "ac4-amd")), String.class)
                .getStatusCode().value());

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return rest.exchange(
                            "/api/experiments/AC-4/protocol-amendments/2/effect", HttpMethod.POST,
                            new HttpEntity<>(null,
                                    headers("c1", "COORDINATOR", "ac4-effect-key")), String.class);
                }));
            }
            start.countDown();
            Set<String> bodies = new HashSet<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
                assertEquals(200, response.getStatusCode().value(), "同键并发生效均回放 200");
                bodies.add(response.getBody());
            }
            assertEquals(1, bodies.size(), "生效回放响应体必须一致");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals("EFFECTIVE", jdbc.queryForObject(
                "SELECT status FROM protocol_version WHERE experiment_id = 'AC-4' AND version = 2",
                String.class));
        // 生效只执行一次：CTR-A 的 V2 序列恰好 5 条，不会因重复生效翻倍
        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence WHERE experiment_id = 'AC-4' "
                        + "AND center_id = 'CTR-A' AND version = 2", Integer.class));
    }

    @Test
    void amendmentFingerprint_includesNormalizedRatioAndOperator_failureDoesNotConsumeKey() {
        createExperiment("AC-5", "ac5-create");
        String body = "{\"ratioA\":30,\"ratioB\":70,\"effectiveAt\":1700000010000}";

        // 首次成功
        assertEquals(201, rest.exchange(
                "/api/experiments/AC-5/protocol-amendments", HttpMethod.POST,
                new HttpEntity<>(body, headers("c1", "COORDINATOR", "ac5-key")), String.class)
                .getStatusCode().value());
        // 同键同参同操作者重放：原 201，不新增版本
        ResponseEntity<String> replay = rest.exchange(
                "/api/experiments/AC-5/protocol-amendments", HttpMethod.POST,
                new HttpEntity<>(body, headers("c1", "COORDINATOR", "ac5-key")), String.class);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_version WHERE experiment_id = 'AC-5'",
                Integer.class));
        // 同键异参（比例不同）：409
        assertEquals(409, rest.exchange(
                "/api/experiments/AC-5/protocol-amendments", HttpMethod.POST,
                new HttpEntity<>("{\"ratioA\":80,\"ratioB\":20,\"effectiveAt\":1700000010000}",
                        headers("c1", "COORDINATOR", "ac5-key")), String.class)
                .getStatusCode().value());
        // 同键异操作者：409
        assertEquals(409, rest.exchange(
                "/api/experiments/AC-5/protocol-amendments", HttpMethod.POST,
                new HttpEntity<>(body, headers("c2", "COORDINATOR", "ac5-key")), String.class)
                .getStatusCode().value());

        // 失败（生效时刻过早）不占键：同一 requestId 换合法时刻后成功（先撤销占位版本释放名额）
        assertEquals(200, rest.exchange(
                "/api/experiments/AC-5/protocol-amendments/2/revocation", HttpMethod.POST,
                new HttpEntity<>(null, headers("c1", "COORDINATOR", "ac5-revoke")), String.class)
                .getStatusCode().value());
        assertEquals(422, rest.exchange(
                "/api/experiments/AC-5/protocol-amendments", HttpMethod.POST,
                new HttpEntity<>("{\"ratioA\":30,\"ratioB\":70,\"effectiveAt\":1}",
                        headers("c1", "COORDINATOR", "ac5-new-key")), String.class)
                .getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'ac5-new-key'",
                Integer.class));
        assertEquals(201, rest.exchange(
                "/api/experiments/AC-5/protocol-amendments", HttpMethod.POST,
                new HttpEntity<>(body, headers("c1", "COORDINATOR", "ac5-new-key")),
                String.class).getStatusCode().value());
    }
}
