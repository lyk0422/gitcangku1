package com.example.starter.blind;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 二级盲码并发边界（真实多线程、闩锁同时起跑、超时兜底）：
 * 同一样本并发接收仅一名分析员成功；同键并发结果写入恰好一次且全部回放一致；
 * 接收与退组竞争时最终状态一致。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SampleConcurrencyTest extends AbstractBlindIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

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

    private String prepareSample(String expId, String pid, String keyPrefix) throws Exception {
        assertEquals(201, post("/api/experiments/" + expId,
                headers("c1", "COORDINATOR", keyPrefix + "-create"),
                "{\"blockCount\":2}").getStatusCode().value());
        assertEquals(201, post("/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                headers("c1", "COORDINATOR", keyPrefix + "-alloc"), null).getStatusCode().value());
        ResponseEntity<String> sample = post(
                "/api/experiments/" + expId + "/participants/" + pid + "/sample",
                headers("c1", "COORDINATOR", keyPrefix + "-sample"), null);
        assertEquals(201, sample.getStatusCode().value());
        return mapper.readTree(sample.getBody()).path("primaryCode").asText();
    }

    private String receive(String primary, String analyst, String reqId) throws Exception {
        ResponseEntity<String> recv = post("/api/samples/" + primary + "/receipt",
                headers(analyst, "ANALYST", reqId), null);
        assertEquals(200, recv.getStatusCode().value());
        return mapper.readTree(recv.getBody()).path("secondaryCode").asText();
    }

    @Test
    void concurrentReceive_sameSample_exactlyOneAnalystBound() throws Exception {
        String primary = prepareSample("SC-1", "P1", "sc1");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final String analyst = "A" + i;
                final String reqId = "sc1-recv-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return post("/api/samples/" + primary + "/receipt",
                            headers(analyst, "ANALYST", reqId), null).getStatusCode().value();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, statuses.stream().filter(s -> s == 200).count(),
                    "一样本仅一名分析员接收成功");
            assertEquals(threads - 1, statuses.stream().filter(s -> s == 409).count());
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals("RECEIVED", jdbc.queryForObject(
                "SELECT status FROM blind_sample WHERE experiment_id = 'SC-1'", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT analyst_actor) FROM blind_sample "
                        + "WHERE experiment_id = 'SC-1'", Integer.class));
    }

    @Test
    void concurrentResult_sameKeySameValue_exactlyOneWriteAllReplay() throws Exception {
        String secondary = receive(prepareSample("SC-2", "P1", "sc2"), "L", "sc2-recv");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return post("/api/samples/" + secondary + "/result",
                            headers("L", "ANALYST", "sc2-result"), "{\"value\":15}");
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Set<String> bodies = new HashSet<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
                assertEquals(200, response.getStatusCode().value(), "同键同参并发均应回放成功");
                bodies.add(response.getBody());
            }
            assertEquals(1, bodies.size(), "所有回放响应体必须一致");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        // 恰好一次业务写入：结果总数不增
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM blind_sample WHERE experiment_id = 'SC-2' "
                        + "AND result_value IS NOT NULL", Integer.class));
        assertEquals(15, jdbc.queryForObject(
                "SELECT result_value FROM blind_sample WHERE experiment_id = 'SC-2'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'sc2-result'",
                Integer.class));
    }

    @Test
    void concurrentResult_differentKeys_oneSuccessRestConflict() throws Exception {
        String secondary = receive(prepareSample("SC-3", "P1", "sc3"), "L", "sc3-recv");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String reqId = "sc3-result-" + i;
                final int value = i + 1;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return post("/api/samples/" + secondary + "/result",
                            headers("L", "ANALYST", reqId), "{\"value\":" + value + "}")
                            .getStatusCode().value();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, statuses.stream().filter(s -> s == 200).count(),
                    "一样本仅一个结果写入成功");
            assertEquals(threads - 1, statuses.stream().filter(s -> s == 409).count());
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM blind_sample WHERE experiment_id = 'SC-3' "
                        + "AND result_value IS NOT NULL", Integer.class));
    }

    @Test
    void receiveVsWithdrawal_race_consistentFinalState() throws Exception {
        String primary = prepareSample("SC-4", "P1", "sc4");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> withdraw = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return post("/api/experiments/SC-4/participants/P1/withdrawal",
                        headers("c1", "COORDINATOR", "sc4-withdraw"), null).getStatusCode().value();
            });
            Future<Integer> receive = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return post("/api/samples/" + primary + "/receipt",
                        headers("L", "ANALYST", "sc4-recv"), null).getStatusCode().value();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int withdrawStatus = withdraw.get(30, TimeUnit.SECONDS);
            int receiveStatus = receive.get(30, TimeUnit.SECONDS);

            // 退组对在组参与者必然成功；接收要么先于退组提交（200），要么被拒（409）
            assertEquals(200, withdrawStatus);
            assertTrue(receiveStatus == 200 || receiveStatus == 409,
                    "接收只能成功或被退组拒绝，实际: " + receiveStatus);

            // 最终状态一致：分配必为已退组；样本状态与接收结果一致
            assertEquals("WITHDRAWN", jdbc.queryForObject(
                    "SELECT status FROM allocation WHERE experiment_id = 'SC-4'", String.class));
            String expectedSampleStatus = receiveStatus == 200 ? "RECEIVED" : "CREATED";
            assertEquals(expectedSampleStatus, jdbc.queryForObject(
                    "SELECT status FROM blind_sample WHERE experiment_id = 'SC-4'", String.class));
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
