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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 揭盲终止机制的真实并发边界（H2 行锁与唯一占位，不用睡眠代替断言）：
 * 到期后并发重新申请最多一个成功；批准/拒绝/撤销竞争只能形成一个终态，
 * 失败方回滚不残留盲底、占位或幂等键。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnblindConcurrencyTest extends AbstractBlindIntegrationTest {

    private static final long MINUTE = 60_000L;

    @Autowired
    private TestRestTemplate rest;

    private HttpHeaders headers(String actor, String role, String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Actor-Id", actor);
        h.set("X-Role", role);
        h.set("X-Request-Id", requestId);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private void setupParticipant(String expId, String prefix) {
        assertEquals(201, rest.exchange("/api/experiments/" + expId, HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":2}",
                        headers("c1", "COORDINATOR", prefix + "-create")), String.class)
                .getStatusCode().value());
        assertEquals(201, rest.exchange(
                "/api/experiments/" + expId + "/participants/PA/allocations",
                HttpMethod.POST, new HttpEntity<>(null,
                        headers("c1", "COORDINATOR", prefix + "-alloc")), String.class)
                .getStatusCode().value());
    }

    private String apply(String expId, String key, String body) {
        return rest.exchange(
                "/api/experiments/" + expId + "/participants/PA/unblind-requests",
                HttpMethod.POST, new HttpEntity<>(body,
                        headers("c1", "COORDINATOR", key)), String.class).getBody();
    }

    private long allocationId(String expId) {
        return jdbc.queryForObject(
                "SELECT id FROM allocation WHERE experiment_id = ? AND participant_id = 'PA'",
                Long.class, expId);
    }

    @Test
    void concurrentReapplyAfterExpiry_atMostOneNewPendingSucceeds() throws Exception {
        setupParticipant("UC-1", "uc1");
        // 制造一个已到期占位
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        String expiredId = mapper.readTree(apply("UC-1", "uc1-apply-old",
                "{\"reason\":\"旧申请\",\"validMinutes\":1}")).path("requestId").asText();
        clock.advance(MINUTE);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String key = "uc1-reapply-" + i;
                futures.add(pool.submit(() -> rest.exchange(
                        "/api/experiments/UC-1/participants/PA/unblind-requests",
                        HttpMethod.POST, new HttpEntity<>("{\"reason\":\"并发重新申请\"}",
                                headers("c1", "COORDINATOR", key)), String.class)
                        .getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long success = statuses.stream().filter(s -> s == 201).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, success, "并发新申请最多一个成功");
            assertEquals(threads - 1L, conflict, "其余全部 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        long allocId = allocationId("UC-1");
        // 恰好一个新 PENDING；旧占位已归档 EXPIRED；历史两行均保留
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ? AND status = 'PENDING'",
                Long.class, allocId));
        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, expiredId));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE allocation_id = ?", Long.class,
                allocId));
        // 仅成功请求占用一个幂等键
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE operation = 'unblind.apply' "
                        + "AND request_id LIKE 'uc1-reapply-%'", Long.class));
    }

    @Test
    void concurrentApproveAndCancel_exactlyOneTerminal_noResidueOnFailure() throws Exception {
        setupParticipant("UC-2", "uc2");
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        String ubId = mapper.readTree(apply("UC-2", "uc2-apply",
                "{\"reason\":\"竞争终态\"}")).path("requestId").asText();
        clock.advance(1000);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> approve = pool.submit(() -> rest.exchange(
                    "/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                    new HttpEntity<>(null, headers("rev-2", "REVIEWER", "uc2-approve")),
                    String.class));
            Future<ResponseEntity<String>> cancel = pool.submit(() -> rest.exchange(
                    "/api/unblind-requests/" + ubId + "/cancellation", HttpMethod.POST,
                    new HttpEntity<>(null, headers("c1", "COORDINATOR", "uc2-cancel")),
                    String.class));

            int approveStatus = approve.get(30, TimeUnit.SECONDS).getStatusCode().value();
            int cancelStatus = cancel.get(30, TimeUnit.SECONDS).getStatusCode().value();
            // 只能形成一个终态
            assertEquals(1, List.of(approveStatus, cancelStatus).stream()
                    .filter(s -> s == 200).count());
            assertEquals(1, List.of(approveStatus, cancelStatus).stream()
                    .filter(s -> s == 409).count());
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        String finalStatus = jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, ubId);
        assertTrue(List.of("APPROVED", "CANCELLED").contains(finalStatus));
        // 占位已释放；若撤销胜出则不得残留盲底
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ? "
                        + "AND pending_allocation_id IS NOT NULL", Long.class, ubId));
        if ("CANCELLED".equals(finalStatus)) {
            assertEquals(null, jdbc.queryForObject(
                    "SELECT treatment FROM unblind_request WHERE id = ?", String.class, ubId));
        }
        // 失败方不占幂等键
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id IN "
                        + "('uc2-approve','uc2-cancel')", Integer.class);
        assertEquals(1, keyCount);
    }

    @Test
    void concurrentApproveAndReject_exactlyOneTerminal() throws Exception {
        setupParticipant("UC-3", "uc3");
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        String ubId = mapper.readTree(apply("UC-3", "uc3-apply",
                "{\"reason\":\"审批准拒竞争\"}")).path("requestId").asText();
        clock.advance(500);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> approve = pool.submit(() -> rest.exchange(
                    "/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                    new HttpEntity<>(null, headers("rev-2", "REVIEWER", "uc3-approve")),
                    String.class).getStatusCode().value());
            Future<Integer> reject = pool.submit(() -> rest.exchange(
                    "/api/unblind-requests/" + ubId + "/rejection", HttpMethod.POST,
                    new HttpEntity<>("{\"rejectReason\":\"拒绝胜出\"}",
                            headers("rev-3", "REVIEWER", "uc3-reject")),
                    String.class).getStatusCode().value());

            int approveStatus = approve.get(30, TimeUnit.SECONDS);
            int rejectStatus = reject.get(30, TimeUnit.SECONDS);
            assertEquals(1, List.of(approveStatus, rejectStatus).stream()
                    .filter(s -> s == 200).count());
            assertEquals(1, List.of(approveStatus, rejectStatus).stream()
                    .filter(s -> s == 409).count());
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        String finalStatus = jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, ubId);
        assertTrue(List.of("APPROVED", "REJECTED").contains(finalStatus));
        if ("REJECTED".equals(finalStatus)) {
            // 拒绝胜出：无盲底残留，拒绝原因/处理人落库
            assertEquals(null, jdbc.queryForObject(
                    "SELECT treatment FROM unblind_request WHERE id = ?", String.class, ubId));
            assertEquals("拒绝胜出", jdbc.queryForObject(
                    "SELECT reject_reason FROM unblind_request WHERE id = ?", String.class, ubId));
            assertEquals("rev-3", jdbc.queryForObject(
                    "SELECT terminated_actor FROM unblind_request WHERE id = ?", String.class,
                    ubId));
        }
    }
}
