package com.example.starter.blind;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中心并发与幂等边界（真实多线程，不用睡眠代替断言）：
 * 并发分配不超中心上限；并发双人确认按事务提交顺序恰好激活一次；
 * 同 requestId 并发重放一致；同 assignmentKey 异参并发仅一次成功。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private void createExperiment(String id, int blockCount, String requestId) {
        assertEquals(201, rest.exchange("/api/experiments/" + id, HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":" + blockCount + "}",
                        headers("c1", "COORDINATOR", requestId)), String.class)
                .getStatusCode().value());
    }

    private void activateSite(String exp, String site, int cap, String tag) {
        assertEquals(201, rest.exchange("/api/experiments/" + exp + "/sites/" + site,
                HttpMethod.POST, new HttpEntity<>("{\"targetCap\":" + cap + "}",
                        headers("c1", "COORDINATOR", tag + "-site")), String.class)
                .getStatusCode().value());
        assertEquals(200, rest.exchange(
                "/api/experiments/" + exp + "/sites/" + site + "/activation-confirmations",
                HttpMethod.POST, new HttpEntity<>("{\"activationKey\":\"KEY-" + tag + "\"}",
                        headers("m1", "COORDINATOR", tag + "-c1")), String.class)
                .getStatusCode().value());
        assertEquals(200, rest.exchange(
                "/api/experiments/" + exp + "/sites/" + site + "/activation-confirmations",
                HttpMethod.POST, new HttpEntity<>("{\"activationKey\":\"KEY-" + tag + "\"}",
                        headers("m2", "COORDINATOR", tag + "-c2")), String.class)
                .getStatusCode().value());
    }

    @Test
    void concurrentAllocations_neverExceedSiteCap() throws Exception {
        // 实验 8 席，中心上限 4：8 个不同参与者并发，恰好 4 个 201、4 个 422
        createExperiment("EXP-C1", 2, "req-c1-exp");
        activateSite("EXP-C1", "SITE-1", 4, "c1");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final String pid = "CP" + i;
                final String key = "ASG-C1-" + i;
                final String reqId = "req-c1-alloc-" + i;
                futures.add(pool.submit(() -> rest.exchange(
                        "/api/experiments/EXP-C1/sites/SITE-1/participants/" + pid
                                + "/allocations",
                        HttpMethod.POST, new HttpEntity<>("{\"assignmentKey\":\"" + key + "\"}",
                                headers("c1", "COORDINATOR", reqId)), String.class)
                        .getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long success = statuses.stream().filter(s -> s == 201).count();
            long capReached = statuses.stream().filter(s -> s == 422).count();
            assertEquals(4, success, "恰好中心上限个分配成功");
            assertEquals(4, capReached, "其余因中心上限 422");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation "
                        + "WHERE experiment_id = 'EXP-C1' AND site_code = 'SITE-1'",
                Integer.class));
        List<String> seats = jdbc.queryForList(
                "SELECT CONCAT(block_no, '-', seat_no) AS k FROM allocation "
                        + "WHERE experiment_id = 'EXP-C1'", String.class);
        assertEquals(4, new HashSet<>(seats).size(), "席位不得重复");
    }

    @Test
    void concurrentDualConfirms_exactlyOneActivationInCommitOrder() throws Exception {
        createExperiment("EXP-C2", 2, "req-c2-exp");
        assertEquals(201, rest.exchange("/api/experiments/EXP-C2/sites/SITE-1",
                HttpMethod.POST, new HttpEntity<>("{\"targetCap\":5}",
                        headers("c1", "COORDINATOR", "req-c2-site")), String.class)
                .getStatusCode().value());

        // 两名不同操作者同键并发确认：按事务提交顺序，一人成为首次确认，另一人完成激活
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= 2; i++) {
                final String actor = "m" + i;
                final String reqId = "req-c2-confirm-" + i;
                futures.add(pool.submit(() -> rest.exchange(
                        "/api/experiments/EXP-C2/sites/SITE-1/activation-confirmations",
                        HttpMethod.POST, new HttpEntity<>("{\"activationKey\":\"KEY-C2\"}",
                                headers(actor, "COORDINATOR", reqId)), String.class)));
            }
            List<JsonNode> bodies = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
                assertEquals(200, response.getStatusCode().value());
                bodies.add(mapper.readTree(response.getBody()));
            }
            long activated = bodies.stream()
                    .filter(b -> b.path("activated").asBoolean()).count();
            long firstOnly = bodies.stream()
                    .filter(b -> !b.path("activated").asBoolean()).count();
            assertEquals(1, activated, "恰好一人完成激活");
            assertEquals(1, firstOnly, "另一人为首次确认");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM site "
                        + "WHERE experiment_id = 'EXP-C2' AND site_code = 'SITE-1'",
                String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT generation FROM site "
                        + "WHERE experiment_id = 'EXP-C2' AND site_code = 'SITE-1'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation "
                        + "WHERE experiment_id = 'EXP-C2' AND site_code = 'SITE-1'",
                Integer.class));
        // 双人确认记录确为两名不同操作者
        List<String> actors = jdbc.query(
                "SELECT first_actor, second_actor FROM site_activation "
                        + "WHERE experiment_id = 'EXP-C2' AND site_code = 'SITE-1'",
                (rs, n) -> rs.getString(1) + "|" + rs.getString(2));
        assertEquals(1, actors.size());
        String[] pair = actors.get(0).split("\\|");
        assertTrue(!pair[0].equals(pair[1]), "两名确认人必须不同");
        assertEquals(Set.of("m1", "m2"), Set.of(pair[0], pair[1]));
    }

    @Test
    void sameRequestIdConcurrent_confirm_exactlyOneExecutionAllReplay() throws Exception {
        createExperiment("EXP-C3", 2, "req-c3-exp");
        assertEquals(201, rest.exchange("/api/experiments/EXP-C3/sites/SITE-1",
                HttpMethod.POST, new HttpEntity<>("{\"targetCap\":5}",
                        headers("c1", "COORDINATOR", "req-c3-site")), String.class)
                .getStatusCode().value());

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> rest.exchange(
                        "/api/experiments/EXP-C3/sites/SITE-1/activation-confirmations",
                        HttpMethod.POST, new HttpEntity<>("{\"activationKey\":\"KEY-C3\"}",
                                headers("m1", "COORDINATOR", "req-c3-same")), String.class)));
            }
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

        // 首次确认只记录一次
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site "
                        + "WHERE experiment_id = 'EXP-C3' AND site_code = 'SITE-1' "
                        + "AND pending_actor = 'm1'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'req-c3-same'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation "
                        + "WHERE experiment_id = 'EXP-C3' AND site_code = 'SITE-1'",
                Integer.class));
    }

    @Test
    void sameAssignmentKeyConcurrent_differentParticipants_oneSuccessRestConflict()
            throws Exception {
        createExperiment("EXP-C4", 2, "req-c4-exp");
        activateSite("EXP-C4", "SITE-1", 5, "c4");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                // 同一 assignmentKey 绑定不同受试者：仅一次成功，其余 409
                final String pid = "CP" + i;
                final String reqId = "req-c4-alloc-" + i;
                futures.add(pool.submit(() -> rest.exchange(
                        "/api/experiments/EXP-C4/sites/SITE-1/participants/" + pid
                                + "/allocations",
                        HttpMethod.POST,
                        new HttpEntity<>("{\"assignmentKey\":\"ASG-SHARED\"}",
                                headers("c1", "COORDINATOR", reqId)), String.class)
                        .getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long success = statuses.stream().filter(s -> s == 201).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, success, "同 assignmentKey 异参并发仅一次成功");
            assertEquals(threads - 1L, conflict, "其余全部异参冲突 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation "
                        + "WHERE experiment_id = 'EXP-C4' AND site_code = 'SITE-1'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE assignment_key = 'ASG-SHARED'",
                Integer.class));
    }
}
