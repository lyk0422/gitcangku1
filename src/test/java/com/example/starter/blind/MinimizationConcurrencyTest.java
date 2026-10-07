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
 * MINIMIZE 并发与幂等边界（真实多线程，真实 H2）：
 * 并发同特征登记等价于某个合法串行顺序（A/B 各半、席位不重复）；
 * 同 requestId 同参重放原响应、异参 409、失败不占键。
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

    private ResponseEntity<String> post(String path, HttpHeaders headers, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void concurrentSameFeatureRegistrations_serializableBalanceAndDistinctSeats() throws Exception {
        // 容量 4（A/B 各 2）；8 个不同参与者并发登记同特征 (0,0)
        assertEquals(201, post("/api/experiments/MINC-1",
                headers("c1", "COORDINATOR", "minc-create-1"),
                "{\"mode\":\"MINIMIZE\",\"capacity\":4}").getStatusCode().value());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final String pid = "MP" + i;
                final String reqId = "minc-alloc-" + i;
                futures.add(pool.submit(() -> post(
                        "/api/experiments/MINC-1/participants/" + pid + "/allocations",
                        headers("c1", "COORDINATOR", reqId),
                        "{\"feature1\":0,\"feature2\":0}").getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(4, statuses.stream().filter(s -> s == 201).count(), "恰好容量个成功");
            assertEquals(4, statuses.stream().filter(s -> s == 422).count(), "其余满额 422");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终数据等价于合法串行顺序：同特征 (0,0) 依次分 A、B、A、B
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM allocation a JOIN seat s "
                + "ON s.experiment_id = a.experiment_id AND s.block_no = a.block_no "
                + "AND s.seat_no = a.seat_no "
                + "WHERE a.experiment_id = 'MINC-1' AND s.treatment = 'A'", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM allocation a JOIN seat s "
                + "ON s.experiment_id = a.experiment_id AND s.block_no = a.block_no "
                + "AND s.seat_no = a.seat_no "
                + "WHERE a.experiment_id = 'MINC-1' AND s.treatment = 'B'", Long.class));
        List<Integer> seats = jdbc.queryForList(
                "SELECT seat_no FROM allocation WHERE experiment_id = 'MINC-1'", Integer.class);
        assertEquals(Set.of(1, 2, 3, 4), new HashSet<>(seats), "四席各占一次，不得重复占席");
    }

    @Test
    void idempotency_replaySameParams_conflictOnDifferentParams_failureDoesNotConsumeKey()
            throws Exception {
        // 创建：同键同参重放原响应；异参 409
        ResponseEntity<String> created = post("/api/experiments/MINC-2",
                headers("c1", "COORDINATOR", "minc-key-create"),
                "{\"mode\":\"MINIMIZE\",\"capacity\":4}");
        assertEquals(201, created.getStatusCode().value());
        ResponseEntity<String> replay = post("/api/experiments/MINC-2",
                headers("c1", "COORDINATOR", "minc-key-create"),
                "{\"mode\":\"MINIMIZE\",\"capacity\":4}");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(created.getBody(), replay.getBody());
        assertEquals(409, post("/api/experiments/MINC-2",
                headers("c1", "COORDINATOR", "minc-key-create"),
                "{\"mode\":\"MINIMIZE\",\"capacity\":6}").getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment WHERE id = 'MINC-2'", Integer.class));

        // 登记：同键同参重放原响应；同键异特征 409
        ResponseEntity<String> alloc = post(
                "/api/experiments/MINC-2/participants/P1/allocations",
                headers("c1", "COORDINATOR", "minc-key-alloc"),
                "{\"feature1\":0,\"feature2\":1}");
        assertEquals(201, alloc.getStatusCode().value());
        ResponseEntity<String> allocReplay = post(
                "/api/experiments/MINC-2/participants/P1/allocations",
                headers("c1", "COORDINATOR", "minc-key-alloc"),
                "{\"feature1\":0,\"feature2\":1}");
        assertEquals(201, allocReplay.getStatusCode().value());
        assertEquals(alloc.getBody(), allocReplay.getBody());
        assertEquals(409, post("/api/experiments/MINC-2/participants/P1/allocations",
                headers("c1", "COORDINATOR", "minc-key-alloc"),
                "{\"feature1\":1,\"feature2\":1}").getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'MINC-2'", Integer.class));

        // 失败不占键：非法特征 400 后，同键换合法参数成功
        assertEquals(400, post("/api/experiments/MINC-2/participants/P2/allocations",
                headers("c1", "COORDINATOR", "minc-key-fail"),
                "{\"feature1\":5,\"feature2\":0}").getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'minc-key-fail'",
                Integer.class));
        assertEquals(201, post("/api/experiments/MINC-2/participants/P2/allocations",
                headers("c1", "COORDINATOR", "minc-key-fail"),
                "{\"feature1\":1,\"feature2\":0}").getStatusCode().value());

        // 十进制同值同参：capacity 4 与 4.0 视为同参，重放原响应
        ResponseEntity<String> decimal = post("/api/experiments/MINC-3",
                headers("c1", "COORDINATOR", "minc-key-dec"),
                "{\"mode\":\"MINIMIZE\",\"capacity\":4.0}");
        assertEquals(201, decimal.getStatusCode().value());
        ResponseEntity<String> decimalReplay = post("/api/experiments/MINC-3",
                headers("c1", "COORDINATOR", "minc-key-dec"),
                "{\"mode\":\"MINIMIZE\",\"capacity\":4}");
        assertEquals(201, decimalReplay.getStatusCode().value());
        assertEquals(decimal.getBody(), decimalReplay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment WHERE id = 'MINC-3'", Integer.class));
    }
}
