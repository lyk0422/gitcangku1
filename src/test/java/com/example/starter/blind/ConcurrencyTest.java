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
 * 并发与幂等边界（真实多线程，不用睡眠代替断言）：
 * 并发分配不重席位；同 requestId 并发恰好一次业务执行且全部得到成功回放；
 * 同键异参并发只有一次成功，其余 409。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrencyTest extends AbstractBlindIntegrationTest {

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

    @Test
    void concurrentRegistrations_distinctParticipants_noDoubleSeatAndExactlyCapacitySucceed()
            throws Exception {
        // 2 区组 = 8 席；12 个不同参与者并发：恰好 8 个 201、4 个 422
        assertEquals(201, rest.exchange("/api/experiments/CONC-1", HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":2}",
                        headers("c1", "COORDINATOR", "conc-create")), String.class)
                .getStatusCode().value());

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final String pid = "CP" + i;
                final String reqId = "conc-alloc-" + i;
                futures.add(pool.submit(() -> rest.exchange(
                        "/api/experiments/CONC-1/participants/" + pid + "/allocations",
                        HttpMethod.POST, new HttpEntity<>(null,
                                headers("c1", "COORDINATOR", reqId)), String.class)
                        .getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long success = statuses.stream().filter(s -> s == 201).count();
            long full = statuses.stream().filter(s -> s == 422).count();
            assertEquals(8, success, "恰好容量个分配成功");
            assertEquals(4, full, "其余因满额 422");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终数据：8 条分配，席位两两不同，覆盖两个区组各 4 席
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'CONC-1'", Integer.class));
        List<String> seats = jdbc.queryForList(
                "SELECT CONCAT(block_no, '-', seat_no) AS k FROM allocation "
                        + "WHERE experiment_id = 'CONC-1'", String.class);
        assertEquals(8, new HashSet<>(seats).size(), "席位不得重复");
        assertEquals(Set.of("1-1", "1-2", "1-3", "1-4", "2-1", "2-2", "2-3", "2-4"),
                new HashSet<>(seats));
        List<String> participantDuplicates = jdbc.queryForList(
                "SELECT participant_id FROM allocation WHERE experiment_id = 'CONC-1' "
                        + "GROUP BY participant_id HAVING COUNT(*) > 1", String.class);
        assertTrue(participantDuplicates.isEmpty(), "同参与者不得占多席");
    }

    @Test
    void sameRequestIdConcurrent_createExperiment_exactlyOneSuccessAllReplaySameResult()
            throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> rest.exchange("/api/experiments/CONC-3",
                        HttpMethod.POST, new HttpEntity<>("{\"blockCount\":2}",
                                headers("c1", "COORDINATOR", "conc-same-key")),
                        String.class)));
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
                "SELECT COUNT(*) FROM experiment WHERE id = 'CONC-3'", Integer.class));
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'CONC-3'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'conc-same-key'",
                Integer.class));
    }

    @Test
    void sameRequestIdConcurrent_differentParams_oneSuccessRestConflict() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                // 每个线程携带互不相同的参数（区组数 2~7），保证除胜出者外都判异参冲突
                final int blockCount = i + 2;
                futures.add(pool.submit(() -> rest.exchange("/api/experiments/CONC-4",
                        HttpMethod.POST, new HttpEntity<>("{\"blockCount\":" + blockCount + "}",
                                headers("c1", "COORDINATOR", "conc-diff-key")),
                        String.class).getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long success = statuses.stream().filter(s -> s == 201).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, success, "同键异参并发仅一次成功");
            assertEquals(threads - 1L, conflict, "其余全部异参冲突 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment WHERE id = 'CONC-4'", Integer.class));
    }
}
