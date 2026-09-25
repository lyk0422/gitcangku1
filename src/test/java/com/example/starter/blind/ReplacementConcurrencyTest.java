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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 替补并发边界（真实多线程 + 真实 H2 行锁，不用睡眠代替断言）：
 * 并发替补同一参与者按事务提交顺序裁决，仅一个成功；
 * 同 requestId 并发重放同一结果；替补与退组并发最终状态一致。
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

    private void setupWithdrawnParticipant(String expId, String pid, String keyPrefix) {
        assertEquals(201, post("/api/experiments/" + expId,
                headers("c1", "COORDINATOR", keyPrefix + "-create"), "{\"blockCount\":2}")
                .getStatusCode().value());
        assertEquals(201, post("/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                headers("c1", "COORDINATOR", keyPrefix + "-alloc"), null).getStatusCode().value());
        assertEquals(200, post("/api/experiments/" + expId + "/participants/" + pid + "/withdrawal",
                headers("c1", "COORDINATOR", keyPrefix + "-withdraw"), null)
                .getStatusCode().value());
    }

    @Test
    void concurrentReplace_differentKeys_exactlyOneSucceeds() throws Exception {
        setupWithdrawnParticipant("RC-1", "P1", "rc1");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return post("/api/experiments/RC-1/participants/P1/replacement",
                            headers("c1", "COORDINATOR", "rc1-replace-" + n),
                            "{\"replaceKey\":\"RK-" + n + "\",\"newParticipantId\":\"NP" + n + "\"}")
                            .getStatusCode().value();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            long success = statuses.stream().filter(s -> s == 201).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, success, "并发替补同一参与者仅一个成功");
            assertEquals(threads - 1L, conflict, "其余按提交顺序裁决为 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        // 最终数据：恰好一条替补记录，分配行数不变（不新建分配序号）
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement WHERE experiment_id = 'RC-1'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'RC-1'", Integer.class));
        assertEquals("ASSIGNED", jdbc.queryForObject(
                "SELECT status FROM allocation WHERE experiment_id = 'RC-1'", String.class));
    }

    @Test
    void concurrentReplace_sameRequestId_allReplaySameResult() throws Exception {
        setupWithdrawnParticipant("RC-2", "P1", "rc2");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return post("/api/experiments/RC-2/participants/P1/replacement",
                            headers("c1", "COORDINATOR", "rc2-same-key"),
                            "{\"replaceKey\":\"RK-S\",\"newParticipantId\":\"NP\"}");
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
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
                "SELECT COUNT(*) FROM replacement WHERE experiment_id = 'RC-2'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'rc2-same-key'",
                Integer.class));
    }

    @Test
    void concurrentReplaceAndWithdraw_commitOrderDecides_finalStateConsistent() throws Exception {
        // P1 已登记未退组：退组与替补并发。
        // 退组先提交则替补可成功；替补先提交则因未退组 409。两种结局都必须状态一致。
        assertEquals(201, post("/api/experiments/RC-3",
                headers("c1", "COORDINATOR", "rc3-create"), "{\"blockCount\":2}")
                .getStatusCode().value());
        assertEquals(201, post("/api/experiments/RC-3/participants/P1/allocations",
                headers("c1", "COORDINATOR", "rc3-alloc"), null).getStatusCode().value());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Integer> withdrawFuture = pool.submit(() -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                return post("/api/experiments/RC-3/participants/P1/withdrawal",
                        headers("c1", "COORDINATOR", "rc3-withdraw"), null).getStatusCode().value();
            });
            Future<Integer> replaceFuture = pool.submit(() -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                return post("/api/experiments/RC-3/participants/P1/replacement",
                        headers("c1", "COORDINATOR", "rc3-replace"),
                        "{\"replaceKey\":\"RK-W\",\"newParticipantId\":\"NP\"}")
                        .getStatusCode().value();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            int withdrawStatus = withdrawFuture.get(30, TimeUnit.SECONDS);
            int replaceStatus = replaceFuture.get(30, TimeUnit.SECONDS);

            assertEquals(200, withdrawStatus, "退组必然成功");
            assertTrue(replaceStatus == 201 || replaceStatus == 409,
                    "替补按提交顺序裁决：退组先提交则 201，否则 409，实际: " + replaceStatus);
            // 最终状态与裁决结果一致
            String status = jdbc.queryForObject(
                    "SELECT status FROM allocation WHERE experiment_id = 'RC-3'", String.class);
            String participant = jdbc.queryForObject(
                    "SELECT participant_id FROM allocation WHERE experiment_id = 'RC-3'",
                    String.class);
            if (replaceStatus == 201) {
                assertEquals("NP", participant);
                assertEquals("ASSIGNED", status);
                assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM replacement WHERE experiment_id = 'RC-3'",
                        Integer.class));
            } else {
                assertEquals("P1", participant);
                assertEquals("WITHDRAWN", status);
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM replacement WHERE experiment_id = 'RC-3'",
                        Integer.class));
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentReplaceAndUnblindApply_commitOrderDecides_finalStateConsistent()
            throws Exception {
        // P1 已退组未揭盲：替补与揭盲申请并发。
        // 申请先提交则两者都成功（既有申请保留）；替补先提交则申请 409。
        setupWithdrawnParticipant("RC-4", "P1", "rc4");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Integer> applyFuture = pool.submit(() -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                return post("/api/experiments/RC-4/participants/P1/unblind-requests",
                        headers("c1", "COORDINATOR", "rc4-apply"),
                        "{\"reason\":\"并发申请\"}").getStatusCode().value();
            });
            Future<Integer> replaceFuture = pool.submit(() -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                return post("/api/experiments/RC-4/participants/P1/replacement",
                        headers("c1", "COORDINATOR", "rc4-replace"),
                        "{\"replaceKey\":\"RK-U\",\"newParticipantId\":\"NP\"}")
                        .getStatusCode().value();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            int applyStatus = applyFuture.get(30, TimeUnit.SECONDS);
            int replaceStatus = replaceFuture.get(30, TimeUnit.SECONDS);

            assertEquals(201, replaceStatus, "退组未揭盲的参与者替补必然成功");
            assertTrue(applyStatus == 201 || applyStatus == 409,
                    "揭盲申请按提交顺序裁决：先提交则 201，替补先提交则 409，实际: " + applyStatus);
            // 最终数据一致：替补记录存在；申请状态与裁决结果一致
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM replacement WHERE experiment_id = 'RC-4'",
                    Integer.class));
            int expectedRequests = applyStatus == 201 ? 1 : 0;
            assertEquals(expectedRequests, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM unblind_request WHERE experiment_id = 'RC-4'",
                    Integer.class));
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
