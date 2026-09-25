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
 * 替补并发边界（真实多线程）：
 * 同一原参与者的并发替补恰好一次成功；同 requestId 并发回放一致；
 * 并发替补与退组按事务提交顺序裁决，最终数据一致。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReplacementConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private void setupWithdrawnParticipant(String expId, String requestIdPrefix) {
        assertEquals(201, post("/api/experiments/" + expId,
                headers("c1", "COORDINATOR", requestIdPrefix + "-create"), "{\"blockCount\":2}")
                .getStatusCode().value());
        assertEquals(201, post(
                "/api/experiments/" + expId + "/participants/P1/allocations",
                headers("c1", "COORDINATOR", requestIdPrefix + "-alloc"), null)
                .getStatusCode().value());
        assertEquals(200, post(
                "/api/experiments/" + expId + "/participants/P1/withdrawal",
                headers("c1", "COORDINATOR", requestIdPrefix + "-withdraw"), null)
                .getStatusCode().value());
    }

    @Test
    void concurrentReplacements_sameOriginal_exactlyOneSucceeds() throws Exception {
        setupWithdrawnParticipant("RC-1", "rc1");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int n = i;
                futures.add(pool.submit(() -> post(
                        "/api/experiments/RC-1/participants/P1/replacement",
                        headers("c1", "COORDINATOR", "rc1-replace-" + n),
                        "{\"replaceKey\":\"RC1-RK-" + n + "\","
                                + "\"newParticipantId\":\"RC1-N" + n + "\"}")
                        .getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long success = statuses.stream().filter(s -> s == 201).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, success, "同一原参与者的并发替补恰好一次成功");
            assertEquals(threads - 1L, conflict, "其余并发替补全部 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终数据：恰好一条替补记录；分配行只有一个且归属胜出者；区组名额守恒
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement WHERE experiment_id = 'RC-1'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'RC-1'", Integer.class));
        String winner = jdbc.queryForObject(
                "SELECT new_participant_id FROM replacement WHERE experiment_id = 'RC-1'",
                String.class);
        assertEquals(winner, jdbc.queryForObject(
                "SELECT participant_id FROM allocation WHERE experiment_id = 'RC-1'",
                String.class));
        assertEquals("ASSIGNED", jdbc.queryForObject(
                "SELECT status FROM allocation WHERE experiment_id = 'RC-1'", String.class));
        Long active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'RC-1' "
                        + "AND block_no = 1 AND status = 'ASSIGNED'", Long.class);
        assertEquals(1L, active);
    }

    @Test
    void concurrentSameRequestId_replacement_allReplaySameResult() throws Exception {
        setupWithdrawnParticipant("RC-2", "rc2");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> post(
                        "/api/experiments/RC-2/participants/P1/replacement",
                        headers("c1", "COORDINATOR", "rc2-same-key"),
                        "{\"replaceKey\":\"RC2-RK\",\"newParticipantId\":\"RC2-N1\"}")));
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
                "SELECT COUNT(*) FROM replacement WHERE experiment_id = 'RC-2'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'rc2-same-key'",
                Integer.class));
    }

    @Test
    void concurrentReplaceAndWithdraw_committedOrderDecides_finalStateConsistent()
            throws Exception {
        // P1 在组：并发“退组 P1”与“替补 P1 -> N1”。
        // 退组先提交则替补成功；替补先提交则其 409（未退组不可替补）且退组成功。
        // 两种时序都合法，断言最终状态自洽且名额守恒。
        assertEquals(201, post("/api/experiments/RC-3",
                headers("c1", "COORDINATOR", "rc3-create"), "{\"blockCount\":2}")
                .getStatusCode().value());
        assertEquals(201, post("/api/experiments/RC-3/participants/P1/allocations",
                headers("c1", "COORDINATOR", "rc3-alloc"), null).getStatusCode().value());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> withdrawFuture;
        Future<Integer> replaceFuture;
        try {
            withdrawFuture = pool.submit(() -> post(
                    "/api/experiments/RC-3/participants/P1/withdrawal",
                    headers("c1", "COORDINATOR", "rc3-withdraw"), null)
                    .getStatusCode().value());
            replaceFuture = pool.submit(() -> post(
                    "/api/experiments/RC-3/participants/P1/replacement",
                    headers("c1", "COORDINATOR", "rc3-replace"),
                    "{\"replaceKey\":\"RC3-RK\",\"newParticipantId\":\"RC3-N1\"}")
                    .getStatusCode().value());
            int withdrawStatus = withdrawFuture.get(30, TimeUnit.SECONDS);
            int replaceStatus = replaceFuture.get(30, TimeUnit.SECONDS);
            assertEquals(200, withdrawStatus, "在组参与者的退组应成功");
            assertTrue(replaceStatus == 201 || replaceStatus == 409,
                    "替补结果取决于提交顺序：退组先提交则 201，否则 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        Integer replacements = jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement WHERE experiment_id = 'RC-3'", Integer.class);
        String status = jdbc.queryForObject(
                "SELECT status FROM allocation WHERE experiment_id = 'RC-3'", String.class);
        if (replacements == 1) {
            // 退组先提交、替补生效：P1 处于 REPLACED 终态，N1 在组继承席位
            assertEquals("ASSIGNED", status);
            assertEquals("RC3-N1", jdbc.queryForObject(
                    "SELECT participant_id FROM allocation WHERE experiment_id = 'RC-3'",
                    String.class));
        } else {
            // 替补先提交被拒（未退组）：P1 保持退组终态
            assertEquals(0, replacements);
            assertEquals("WITHDRAWN", status);
            assertEquals("P1", jdbc.queryForObject(
                    "SELECT participant_id FROM allocation WHERE experiment_id = 'RC-3'",
                    String.class));
        }
        // 账目守恒：活跃数与替补结果一致（生效则 1，否则退组后 0），可用名额随之确定
        Long active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'RC-3' "
                        + "AND block_no = 1 AND status = 'ASSIGNED'", Long.class);
        assertEquals(replacements.longValue(), active,
                "替补生效则活跃数为 1，否则退组后活跃数为 0");
    }
}
