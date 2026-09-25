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
 * 中心协议序列与修订的并发/幂等边界（真实多线程，断言最终数据而非打印）：
 * 并发中心登记不重发同一条盲码序列；同 requestId 并发恰好一次业务执行并回放一致；
 * 并发修订只允许一条待生效版本。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CenterConcurrencyTest extends AbstractBlindIntegrationTest {

    static final long BASE = 1_700_000_000_000L;

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

    private void postOk(String path, String actor, String role, String requestId, String body) {
        assertEquals(201, rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers(actor, role, requestId)), String.class)
                .getStatusCode().value());
    }

    @Test
    void concurrentCenterRegistrations_exactlyCapacitySucceed_noSequenceDoubleIssue()
            throws Exception {
        postOk("/api/experiments/CC-1", "coord-1", "COORDINATOR", "cc1-create",
                "{\"blockCount\":2}");
        postOk("/api/experiments/CC-1/centers/C1", "coord-1", "COORDINATOR", "cc1-center",
                "{\"targetCap\":8}");

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final String pid = "CP" + i;
                final String reqId = "cc1-alloc-" + i;
                futures.add(pool.submit(() -> rest.exchange(
                        "/api/experiments/CC-1/centers/C1/participants/" + pid + "/allocations",
                        HttpMethod.POST,
                        new HttpEntity<>(null, headers("coord-1", "COORDINATOR", reqId)),
                        String.class).getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            long success = statuses.stream().filter(s -> s == 201).count();
            long full = statuses.stream().filter(s -> s == 422).count();
            assertEquals(8, success, "恰好容量个中心登记成功");
            assertEquals(4, full, "超出容量的登记 422");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终数据：8 条中心分配，8 条序列 ISSUED，无重复盲码/参与者，且无悬空绑定
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'CC-1' AND center_id = 'C1'",
                Integer.class));
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_code_sequence WHERE experiment_id = 'CC-1' "
                        + "AND status = 'ISSUED'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_code_sequence WHERE experiment_id = 'CC-1' "
                        + "AND status = 'ISSUED' AND allocation_id IS NULL", Integer.class));
        List<String> codes = jdbc.queryForList(
                "SELECT blind_code FROM allocation WHERE experiment_id = 'CC-1'", String.class);
        assertEquals(8, new HashSet<>(codes).size(), "盲码不得重复");
        List<Long> bound = jdbc.queryForList(
                "SELECT allocation_id FROM center_code_sequence WHERE experiment_id = 'CC-1' "
                        + "AND status = 'ISSUED'", Long.class);
        assertEquals(8, new HashSet<>(bound).size(), "同一分配不得绑定两条序列");
    }

    @Test
    void sameRequestIdConcurrent_centerAllocation_exactlyOneExecutionIdenticalReplay()
            throws Exception {
        postOk("/api/experiments/CC-2", "coord-1", "COORDINATOR", "cc2-create",
                "{\"blockCount\":2}");
        postOk("/api/experiments/CC-2/centers/C1", "coord-1", "COORDINATOR", "cc2-center",
                "{\"targetCap\":8}");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> rest.exchange(
                        "/api/experiments/CC-2/centers/C1/participants/ONLY/allocations",
                        HttpMethod.POST,
                        new HttpEntity<>(null, headers("coord-1", "COORDINATOR", "cc2-same-key")),
                        String.class)));
            }
            Set<String> bodies = new HashSet<>();
            for (Future<ResponseEntity<String>> f : futures) {
                ResponseEntity<String> resp = f.get(30, TimeUnit.SECONDS);
                assertEquals(201, resp.getStatusCode().value(), "同键同参并发均应回放成功");
                bodies.add(resp.getBody());
            }
            assertEquals(1, bodies.size(), "所有回放响应体必须一致");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'CC-2'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_code_sequence WHERE experiment_id = 'CC-2' "
                        + "AND status = 'ISSUED'", Integer.class));
    }

    @Test
    void concurrentAmendmentCreation_onlyOnePendingVersion() throws Exception {
        postOk("/api/experiments/CC-3", "coord-1", "COORDINATOR", "cc3-create",
                "{\"blockCount\":2}");
        postOk("/api/experiments/CC-3/centers/C1", "coord-1", "COORDINATOR", "cc3-center",
                "{\"targetCap\":50}");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int ratio = 60 + i; // 60..65，配对 40..35，均和为 100
                final String body = "{\"ratioA\":" + ratio + ",\"ratioB\":" + (100 - ratio)
                        + ",\"effectiveAt\":" + (BASE + 60_000) + "}";
                // 不同 requestId、不同参数并发：至多一条 PENDING 成功，其余 409
                futures.add(pool.submit(() -> rest.exchange(
                        "/api/experiments/CC-3/protocol-versions", HttpMethod.POST,
                        new HttpEntity<>(body, headers("coord-1", "COORDINATOR",
                                "cc3-amend-" + ratio)), String.class).getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            long created = statuses.stream().filter(s -> s == 201).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, created, "并发修订只允许一条待生效版本");
            assertEquals(threads - 1L, conflict, "其余修订冲突 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_version WHERE experiment_id = 'CC-3' "
                        + "AND status = 'PENDING'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_version WHERE experiment_id = 'CC-3'",
                Integer.class), "仅 v1 与一条 PENDING v2");
    }
}
