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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 揭盲并发边界（真实多线程 + 真实 H2 行锁/唯一约束，不用睡眠代替断言）：
 * 并发新申请同一参与者最多一个成功；到期后并发重新申请也最多一个成功；
 * 批准/拒绝/撤销并发竞争按事务内时钟与行锁裁决，只形成一个终态，失败不残留盲底/占位。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnblindConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private void setupParticipant(String expId, String prefix) {
        assertEquals(201, rest.exchange("/api/experiments/" + expId, HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":2}",
                        headers("c1", "COORDINATOR", prefix + "-create")), String.class)
                .getStatusCode().value());
        assertEquals(201, rest.exchange(
                "/api/experiments/" + expId + "/participants/PA/allocations",
                HttpMethod.POST,
                new HttpEntity<>(null, headers("c1", "COORDINATOR", prefix + "-alloc")),
                String.class).getStatusCode().value());
    }

    private String apply(String expId, String requestId, String body) {
        ResponseEntity<String> resp = rest.exchange(
                "/api/experiments/" + expId + "/participants/PA/unblind-requests",
                HttpMethod.POST, new HttpEntity<>(body,
                        headers("coord-1", "COORDINATOR", requestId)), String.class);
        assertEquals(201, resp.getStatusCode().value(), resp.getBody());
        try {
            return mapper.readTree(resp.getBody()).path("requestId").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void concurrentNewApplications_sameParticipant_atMostOneSucceeds() throws Exception {
        setupParticipant("UC-1", "uc1");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String key = "uc1-apply-" + i;
                futures.add(pool.submit(() -> rest.exchange(
                        "/api/experiments/UC-1/participants/PA/unblind-requests",
                        HttpMethod.POST, new HttpEntity<>("{\"reason\":\"并发申请" + key + "\"}",
                                headers("coord-1", "COORDINATOR", key)), String.class)
                        .getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1L, statuses.stream().filter(s -> s == 201).count(),
                    "并发新申请最多一个成功");
            assertEquals(threads - 1L, statuses.stream().filter(s -> s == 409).count(),
                    "其余全部待审冲突 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE status = 'PENDING'",
                Long.class).longValue());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE pending_allocation_id IS NOT NULL",
                Long.class).longValue());
    }

    @Test
    void concurrentReapplyAfterExpiry_archivesOnce_andOnlyOneNewPending() throws Exception {
        setupParticipant("UC-2", "uc2");
        apply("UC-2", "uc2-apply-old", "{\"reason\":\"旧申请\",\"validMinutes\":1}");
        clock.setTime(1_700_000_000_000L + 61_000L);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String key = "uc2-reapply-" + i;
                futures.add(pool.submit(() -> rest.exchange(
                        "/api/experiments/UC-2/participants/PA/unblind-requests",
                        HttpMethod.POST, new HttpEntity<>("{\"reason\":\"重新申请" + key + "\"}",
                                headers("coord-1", "COORDINATOR", key)), String.class)
                        .getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1L, statuses.stream().filter(s -> s == 201).count(),
                    "到期后并发重新申请最多一个成功");
            assertEquals(threads - 1L, statuses.stream().filter(s -> s == 409).count());
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 旧记录恰好归档一次，新 PENDING 恰好一条，历史共两条
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE status = 'EXPIRED'",
                Long.class).longValue());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE status = 'PENDING'",
                Long.class).longValue());
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request", Long.class).longValue());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE pending_allocation_id IS NOT NULL",
                Long.class).longValue());
    }

    @Test
    void concurrentApproveRejectCancel_exactlyOneTerminal_noLeftoverTreatmentOrPlaceholder()
            throws Exception {
        setupParticipant("UC-3", "uc3");
        String ubId = apply("UC-3", "uc3-apply", "{\"reason\":\"并发裁决\"}");

        int threads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(() -> rest.exchange(
                    "/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                    new HttpEntity<>(null, headers("rev-2", "REVIEWER", "uc3-approve")),
                    String.class).getStatusCode().value()));
            futures.add(pool.submit(() -> rest.exchange(
                    "/api/unblind-requests/" + ubId + "/rejection", HttpMethod.POST,
                    new HttpEntity<>("{\"reason\":\"拒绝竞争\"}",
                            headers("rev-3", "REVIEWER", "uc3-reject")),
                    String.class).getStatusCode().value()));
            futures.add(pool.submit(() -> rest.exchange(
                    "/api/unblind-requests/" + ubId + "/cancellation", HttpMethod.POST,
                    new HttpEntity<>(null, headers("coord-1", "COORDINATOR", "uc3-cancel")),
                    String.class).getStatusCode().value()));
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1L, statuses.stream().filter(s -> s == 200).count(),
                    "裁决竞争只能形成一个终态");
            assertEquals(2L, statuses.stream().filter(s -> s == 409).count(),
                    "落败方均为终态冲突 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 终态唯一：占位已释放；只有 APPROVED 才允许存在处理代码
        String status = jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, ubId);
        assertTrue(List.of("APPROVED", "REJECTED", "CANCELLED").contains(status));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM unblind_request WHERE id = ? "
                        + "AND pending_allocation_id IS NOT NULL", Long.class, ubId).longValue());
        if (!"APPROVED".equals(status)) {
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM unblind_request WHERE id = ? AND treatment IS NOT NULL",
                    Long.class, ubId).longValue(), "非批准终态不得残留盲底");
        }
        // 仅胜出操作占用幂等键，落败方随业务回滚不占键
        int occupiedKeys = 0;
        for (String key : List.of("uc3-approve", "uc3-reject", "uc3-cancel")) {
            occupiedKeys += jdbc.queryForObject(
                    "SELECT COUNT(*) FROM idempotent_request WHERE request_id = ?",
                    Integer.class, key);
        }
        assertEquals(1, occupiedKeys, "裁决竞争中仅胜出的一个操作占键");
    }
}
