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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不良事件与紧急揭盲并发边界（真实多线程 + CountDownLatch 同时释放，带超时断言）：
 * 常规批准与多个紧急揭盲竞争同一分配时按提交顺序只成功揭盲一次；
 * 同 requestId 并发紧急揭盲全部回放同一结果；eventKey 并发恰好一条报告。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdverseEventConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private void setupParticipant(String expId, String pid, String prefix) {
        assertEquals(201, rest.exchange("/api/experiments/" + expId, HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":2}",
                        headers("coord-1", "COORDINATOR", prefix + "-create")), String.class)
                .getStatusCode().value());
        assertEquals(201, rest.exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST,
                new HttpEntity<>(null, headers("coord-1", "COORDINATOR", prefix + "-alloc")),
                String.class).getStatusCode().value());
    }

    private void report(String expId, String pid, String eventKey, String reqId) {
        assertEquals(201, rest.exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/adverse-events",
                HttpMethod.POST,
                new HttpEntity<>("{\"eventKey\":\"" + eventKey
                                + "\",\"severity\":\"SEVERE\",\"description\":\"并发严重事件\"}",
                        headers("coord-1", "COORDINATOR", reqId)),
                String.class).getStatusCode().value());
    }

    @Test
    void regularApprovalAndEmergency_race_sameAllocation_unblindsExactlyOnce() throws Exception {
        setupParticipant("AC-1", "P1", "ac1");
        report("AC-1", "P1", "EV-S", "ac1-report");

        // 常规待审申请先行（两条路径互不阻塞）
        ResponseEntity<String> apply = rest.exchange(
                "/api/experiments/AC-1/participants/P1/unblind-requests", HttpMethod.POST,
                new HttpEntity<>("{\"reason\":\"常规申请\"}",
                        headers("coord-1", "COORDINATOR", "ac1-apply")), String.class);
        assertEquals(201, apply.getStatusCode().value());
        String regularId = extractId(apply);

        int emergencyThreads = 5;
        int totalThreads = emergencyThreads + 1;
        ExecutorService pool = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch ready = new CountDownLatch(totalThreads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            // 一个常规批准
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return rest.exchange(
                        "/api/unblind-requests/" + regularId + "/approval", HttpMethod.POST,
                        new HttpEntity<>(null,
                                headers("rev-9", "REVIEWER", "ac1-regular-approve")),
                        String.class).getStatusCode().value();
            }));
            // 多个紧急揭盲（不同操作者、不同 requestId、不同理由）
            for (int i = 1; i <= emergencyThreads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return rest.exchange(
                            "/api/experiments/AC-1/participants/P1/emergency-unblind",
                            HttpMethod.POST,
                            new HttpEntity<>(
                                    "{\"eventKey\":\"EV-S\",\"reason\":\"紧急理由" + idx + "\"}",
                                    headers("rev-" + idx, "REVIEWER", "ac1-emergency-" + idx)),
                            String.class).getStatusCode().value();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "工作线程应全部就绪");
            start.countDown();

            AtomicInteger success = new AtomicInteger();
            AtomicInteger conflict = new AtomicInteger();
            for (Future<Integer> future : futures) {
                int status = future.get(30, TimeUnit.SECONDS);
                if (status == 200) {
                    success.incrementAndGet();
                } else if (status == 409) {
                    conflict.incrementAndGet();
                } else {
                    throw new AssertionError("非预期状态码: " + status);
                }
            }
            assertEquals(1, success.get(), "两条通道合计只能成功揭盲一次");
            assertEquals(totalThreads - 1, conflict.get(), "其余操作对已揭盲/待审分配返回 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终数据：仅一次终局揭盲
        assertEquals(1, jdbc.queryForObject(
                "SELECT unblinded FROM allocation WHERE experiment_id = 'AC-1' "
                        + "AND participant_id = 'P1'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE treatment IS NOT NULL",
                Integer.class));
        // URGENT_REVIEW 已随成功揭盲清除
        assertEquals(0, jdbc.queryForObject(
                "SELECT urgent_review FROM allocation WHERE experiment_id = 'AC-1' "
                        + "AND participant_id = 'P1'", Integer.class));
    }

    @Test
    void sameRequestIdConcurrent_emergency_allReplaySingleResult() throws Exception {
        setupParticipant("AC-2", "P1", "ac2");
        report("AC-2", "P1", "EV-S", "ac2-report");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return rest.exchange(
                            "/api/experiments/AC-2/participants/P1/emergency-unblind",
                            HttpMethod.POST,
                            new HttpEntity<>(
                                    "{\"eventKey\":\"EV-S\",\"reason\":\"同键并发紧急揭盲\"}",
                                    headers("rev-2", "REVIEWER", "ac2-same-key")),
                            String.class);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            Set<String> bodies = new HashSet<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
                assertEquals(200, response.getStatusCode().value(), "同键同参并发均回放成功");
                bodies.add(response.getBody());
            }
            assertEquals(1, bodies.size(), "所有回放响应体必须一致");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE unblind_type = 'EMERGENCY'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'ac2-same-key'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT unblinded FROM allocation WHERE experiment_id = 'AC-2' "
                        + "AND participant_id = 'P1'", Integer.class));
    }

    @Test
    void sameEventKeyConcurrentReports_exactlyOneCreated() throws Exception {
        setupParticipant("AC-3", "P1", "ac3");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return rest.exchange(
                            "/api/experiments/AC-3/participants/P1/adverse-events",
                            HttpMethod.POST,
                            new HttpEntity<>(
                                    "{\"eventKey\":\"EV-RACE\",\"severity\":\"SEVERE\","
                                            + "\"description\":\"同键并发报告\"}",
                                    headers("coord-1", "COORDINATOR", "ac3-report-"
                                            + Thread.currentThread().threadId() + "-"
                                            + System.nanoTime())),
                            String.class).getStatusCode().value();
                }));
            }
            // 注意：report 的业务键 (allocation, eventKey) 唯一，但每个线程用不同 requestId，
            // 因此唯一索引兜底并发：恰好一条 201，其余 409。
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            AtomicInteger created = new AtomicInteger();
            AtomicInteger conflict = new AtomicInteger();
            for (Future<Integer> future : futures) {
                int status = future.get(30, TimeUnit.SECONDS);
                if (status == 201) {
                    created.incrementAndGet();
                } else if (status == 409) {
                    conflict.incrementAndGet();
                } else {
                    throw new AssertionError("非预期状态码: " + status);
                }
            }
            assertEquals(1, created.get(), "同一 eventKey 并发只允许一条报告");
            assertEquals(threads - 1, conflict.get());
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM adverse_event_report WHERE event_key = 'EV-RACE'",
                Integer.class));
        // SEVERE 唯一一条仍应完成 URGENT_REVIEW 标记
        assertEquals(1, jdbc.queryForObject(
                "SELECT urgent_review FROM allocation WHERE experiment_id = 'AC-3' "
                        + "AND participant_id = 'P1'", Integer.class));
    }

    @Test
    void emergencyVersusWithdrawal_race_finalStateConsistent() throws Exception {
        setupParticipant("AC-4", "P1", "ac4");
        report("AC-4", "P1", "EV-S", "ac4-report");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> emergency = pool.submit(() -> {
                ready.countDown();
                start.await();
                return rest.exchange(
                        "/api/experiments/AC-4/participants/P1/emergency-unblind",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                "{\"eventKey\":\"EV-S\",\"reason\":\"与退组竞争\"}",
                                headers("rev-2", "REVIEWER", "ac4-emergency")),
                        String.class).getStatusCode().value();
            });
            Future<Integer> withdrawal = pool.submit(() -> {
                ready.countDown();
                start.await();
                return rest.exchange(
                        "/api/experiments/AC-4/participants/P1/withdrawal",
                        HttpMethod.POST,
                        new HttpEntity<>(null,
                                headers("coord-1", "COORDINATOR", "ac4-withdraw")),
                        String.class).getStatusCode().value();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            int emergencyStatus = emergency.get(30, TimeUnit.SECONDS);
            int withdrawStatus = withdrawal.get(30, TimeUnit.SECONDS);

            // 退组总是成功（ASSIGNED -> WITHDRAWN 一次）；紧急揭盲仅在退组提交前成功
            assertEquals(200, withdrawStatus);
            assertTrue(emergencyStatus == 200 || emergencyStatus == 409,
                    "紧急揭盲只能成功或因退组/已揭盲冲突: " + emergencyStatus);

            Integer unblinded = jdbc.queryForObject(
                    "SELECT unblinded FROM allocation WHERE experiment_id = 'AC-4' "
                            + "AND participant_id = 'P1'", Integer.class);
            String allocStatus = jdbc.queryForObject(
                    "SELECT status FROM allocation WHERE experiment_id = 'AC-4' "
                            + "AND participant_id = 'P1'", String.class);
            Integer emergencyCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM unblind_request WHERE unblind_type = 'EMERGENCY'",
                    Integer.class);
            assertEquals("WITHDRAWN", allocStatus);
            if (emergencyStatus == 200) {
                assertEquals(1, unblinded);
                assertEquals(1, emergencyCount);
            } else {
                assertEquals(0, unblinded, "退组先行时紧急揭盲必须失败且不揭盲");
                assertEquals(0, emergencyCount);
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private String extractId(ResponseEntity<String> response) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(response.getBody()).path("requestId").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
