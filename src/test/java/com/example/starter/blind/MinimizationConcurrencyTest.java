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
 * MINIMIZE 并发边界：并发登记等价某个合法串行顺序——
 * 不重席位、不超容量、同参与者同特征并发只落一行且回放同一盲码、异特征并发仅一次成功。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MinimizationConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private void createMinimize(String expId, int capacity, String requestId) {
        assertEquals(201, rest.exchange("/api/experiments/" + expId, HttpMethod.POST,
                new HttpEntity<>("{\"mode\":\"MINIMIZE\",\"capacity\":" + capacity + "}",
                        headers("c1", "COORDINATOR", requestId)), String.class)
                .getStatusCode().value());
    }

    private ResponseEntity<String> register(String expId, String pid, String body, String reqId) {
        return rest.exchange("/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST, new HttpEntity<>(body, headers("c1", "COORDINATOR", reqId)),
                String.class);
    }

    @Test
    void concurrentDistinctParticipants_exactlyCapacitySucceedsAndSeatsUnique() throws Exception {
        createMinimize("MCONC-1", 4, "mconc1-create");
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final String pid = "MP" + i;
                final String reqId = "mconc1-alloc-" + i;
                // 特征在四种组合间轮换，考验并发下的平衡计算
                final String body = "{\"feature1\":" + (i % 2) + ",\"feature2\":" + ((i / 2) % 2) + "}";
                futures.add(pool.submit(() -> register("MCONC-1", pid, body, reqId)
                        .getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(4, statuses.stream().filter(s -> s == 201).count(), "恰好容量个成功");
            assertEquals(6, statuses.stream().filter(s -> s == 422).count(), "其余满额 422");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终数据：4 条分配、席位两两不同、A/B 各不超过一半容量
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'MCONC-1'", Integer.class));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT seat_no) FROM allocation WHERE experiment_id = 'MCONC-1'",
                Integer.class));
        List<String> overHalf = jdbc.queryForList(
                "SELECT s.treatment FROM allocation a JOIN seat s "
                        + "ON s.experiment_id = a.experiment_id AND s.block_no = a.block_no "
                        + "AND s.seat_no = a.seat_no "
                        + "WHERE a.experiment_id = 'MCONC-1' "
                        + "GROUP BY s.treatment HAVING COUNT(*) > 2", String.class);
        assertTrue(overHalf.isEmpty(), "任一处理不得超过一半席位");
    }

    @Test
    void concurrentSameParticipantSameFeatures_oneRowAndSameBlindCodeReplayed() throws Exception {
        createMinimize("MCONC-2", 4, "mconc2-create");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String reqId = "mconc2-alloc-" + i;
                futures.add(pool.submit(() -> register("MCONC-2", "SP",
                        "{\"feature1\":0,\"feature2\":0}", reqId)));
            }
            Set<String> bodies = new HashSet<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
                assertEquals(201, response.getStatusCode().value(),
                        "同参与者同特征并发应全部按原分配重放");
                bodies.add(response.getBody());
            }
            assertEquals(1, bodies.size(), "所有并发响应必须是同一原分配（同一盲码）");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'MCONC-2'", Integer.class));
    }

    @Test
    void concurrentSameParticipantDifferentFeatures_exactlyOneSuccess() throws Exception {
        createMinimize("MCONC-3", 4, "mconc3-create");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String reqId = "mconc3-alloc-" + i;
                // 每个线程携带互不相同的特征组合
                final String body = "{\"feature1\":" + (i % 2) + ",\"feature2\":" + ((i / 2) % 2) + "}";
                futures.add(pool.submit(() -> register("MCONC-3", "DP", body, reqId)
                        .getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, statuses.stream().filter(s -> s == 201).count(), "仅首个登记成功");
            assertEquals(threads - 1L, statuses.stream().filter(s -> s == 409).count(),
                    "其余异特征登记 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'MCONC-3'", Integer.class));
    }
}
