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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 紧急揭盲与常规批准、紧急揭盲之间的真实并发裁决：
 * 同一分配只成功揭盲一次，另一路径对已揭盲分配返回 409；按事务提交顺序定胜负。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmergencyUnblindConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private ResponseEntity<String> exchange(String path, HttpMethod method,
                                            HttpHeaders headers, String body) {
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    /** 建实验、登记 PA、上报 SEVERE、常规申请揭盲，返回常规申请编号。 */
    private String setupPendingRegularRequest(String expId) throws Exception {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("coord-1", "COORDINATOR", expId + "-create"),
                "{\"blockCount\":2}").getStatusCode().value());
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/PA/allocations",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", expId + "-alloc"), null)
                .getStatusCode().value());
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/PA/adverse-events",
                HttpMethod.POST, headers("rev-1", "REVIEWER", expId + "-sev"),
                "{\"eventKey\":\"EV-SEV\",\"severity\":\"SEVERE\",\"description\":\"重度\"}")
                .getStatusCode().value());
        ResponseEntity<String> apply = exchange(
                "/api/experiments/" + expId + "/participants/PA/unblind-requests",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", expId + "-apply"),
                "{\"reason\":\"常规核对\"}");
        assertEquals(201, apply.getStatusCode().value());
        JsonNode applyBody = mapper.readTree(apply.getBody());
        return applyBody.path("requestId").asText();
    }

    private ResponseEntity<String> emergency(String expId, String actor, String requestId) {
        return exchange("/api/experiments/" + expId + "/participants/PA/emergency-unblind",
                HttpMethod.POST, headers(actor, "REVIEWER", requestId),
                "{\"eventKey\":\"EV-SEV\",\"reason\":\"并发紧急揭盲\"}");
    }

    @Test
    void emergencyVsRegularApproval_exactlyOneUnblinds() throws Exception {
        String ubId = setupPendingRegularRequest("EMC-1");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> regularApprove = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return exchange("/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                        headers("rev-2", "REVIEWER", "emc1-approve"), null)
                        .getStatusCode().value();
            });
            Future<Integer> emergencyUnblind = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return emergency("EMC-1", "rev-1", "emc1-emg").getStatusCode().value();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int approveStatus = regularApprove.get(30, TimeUnit.SECONDS);
            int emergencyStatus = emergencyUnblind.get(30, TimeUnit.SECONDS);

            // 恰好一条路径成功（常规批准 200 或紧急揭盲 201），另一条 409
            boolean regularWon = approveStatus == 200 && emergencyStatus == 409;
            boolean emergencyWon = emergencyStatus == 201 && approveStatus == 409;
            assertTrue(regularWon || emergencyWon,
                    "应恰好一条路径成功: approve=" + approveStatus + ", emergency=" + emergencyStatus);
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终数据：分配只揭盲一次，APPROVED 记录恰好一条
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'EMC-1' "
                        + "AND participant_id = 'PA' AND unblinded_at IS NOT NULL", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE experiment_id = 'EMC-1' "
                        + "AND status = 'APPROVED'", Integer.class));
        assertEquals("N", jdbc.queryForObject(
                "SELECT urgent_review FROM allocation WHERE experiment_id = 'EMC-1' "
                        + "AND participant_id = 'PA'", String.class));
    }

    @Test
    void concurrentEmergencyUnblinds_exactlyOneSucceeds() throws Exception {
        // 无需常规申请，直接构造 URGENT_REVIEW 状态
        assertEquals(201, exchange("/api/experiments/EMC-2", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "emc2-create"), "{\"blockCount\":2}")
                .getStatusCode().value());
        assertEquals(201, exchange("/api/experiments/EMC-2/participants/PA/allocations",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "emc2-alloc"), null)
                .getStatusCode().value());
        assertEquals(201, exchange("/api/experiments/EMC-2/participants/PA/adverse-events",
                HttpMethod.POST, headers("rev-1", "REVIEWER", "emc2-sev"),
                "{\"eventKey\":\"EV-SEV\",\"severity\":\"SEVERE\",\"description\":\"重度\"}")
                .getStatusCode().value());

        int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String actor = "rev-" + i;
                final String reqId = "emc2-emg-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return emergency("EMC-2", actor, reqId).getStatusCode().value();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int success = 0;
            int conflict = 0;
            for (Future<Integer> future : futures) {
                int status = future.get(30, TimeUnit.SECONDS);
                if (status == 201) {
                    success++;
                } else if (status == 409) {
                    conflict++;
                }
            }
            assertEquals(1, success, "并发紧急揭盲恰好一次成功");
            assertEquals(threads - 1, conflict, "其余全部 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE experiment_id = 'EMC-2' "
                        + "AND request_type = 'EMERGENCY'", Integer.class));
        Long unblindedAt = jdbc.queryForObject(
                "SELECT unblinded_at FROM allocation WHERE experiment_id = 'EMC-2' "
                        + "AND participant_id = 'PA'", Long.class);
        assertNotNull(unblindedAt);
    }
}
