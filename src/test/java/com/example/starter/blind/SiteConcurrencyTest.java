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
 * 中心门禁并发边界（真实多线程，不用睡眠代替断言）：
 * 并发分配不超上限且席位不重；同键并发恰好一次业务执行；
 * 双人确认并发按事务提交顺序裁决，恰好产生一代激活记录；
 * 暂停与分配并发由行锁串行，结果必为两种合法时序之一。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private void createActiveSite(String expId, String siteCode, int limit, String key,
                                  String requestIdPrefix) {
        assertEquals(201, post("/api/experiments/" + expId,
                headers("c1", "COORDINATOR", requestIdPrefix + "-exp"),
                "{\"blockCount\":4}").getStatusCode().value());
        assertEquals(201, post("/api/experiments/" + expId + "/sites/" + siteCode,
                headers("m1", "UNBLINDED_MANAGER", requestIdPrefix + "-site"),
                "{\"targetEnrollmentLimit\":" + limit + "}").getStatusCode().value());
        assertEquals(202, post("/api/experiments/" + expId + "/sites/" + siteCode
                        + "/activation-confirmations",
                headers("m1", "UNBLINDED_MANAGER", requestIdPrefix + "-c1"),
                "{\"activationKey\":\"" + key + "\"}").getStatusCode().value());
        assertEquals(200, post("/api/experiments/" + expId + "/sites/" + siteCode
                        + "/activation-confirmations",
                headers("m2", "UNBLINDED_MANAGER", requestIdPrefix + "-c2"),
                "{\"activationKey\":\"" + key + "\"}").getStatusCode().value());
    }

    @Test
    void concurrentSiteAllocations_neverExceedLimit_andNoDuplicateSeat() throws Exception {
        // 中心上限 3，实验席位 16；8 个不同参与者并发：恰好 3 个 201、5 个 422
        createActiveSite("SC-1", "S1", 3, "K1", "sc1");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final String pid = "SP" + i;
                final String reqId = "sc1-alloc-" + i;
                futures.add(pool.submit(() -> post("/api/experiments/SC-1/sites/S1/participants/"
                                + pid + "/allocations",
                        headers("c1", "COORDINATOR", reqId), null).getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long success = statuses.stream().filter(s -> s == 201).count();
            long full = statuses.stream().filter(s -> s == 422).count();
            assertEquals(3, success, "恰好上限个分配成功");
            assertEquals(5, full, "其余因中心上限 422");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'SC-1' AND site_code = 'S1'",
                Integer.class));
        List<String> seats = jdbc.queryForList(
                "SELECT CONCAT(block_no, '-', seat_no) FROM allocation "
                        + "WHERE experiment_id = 'SC-1'", String.class);
        assertEquals(3, new HashSet<>(seats).size(), "席位不得重复");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site WHERE experiment_id = 'SC-1' AND site_code = 'S1' "
                        + "AND status = 'ACTIVE'", Long.class));
    }

    @Test
    void concurrentSameRequestIdAllocation_exactlyOneExecutionAllReplay() throws Exception {
        createActiveSite("SC-2", "S1", 5, "K1", "sc2");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> post(
                        "/api/experiments/SC-2/sites/S1/participants/P1/allocations",
                        headers("c1", "COORDINATOR", "sc2-same-key"), null)));
            }
            Set<String> bodies = new HashSet<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
                assertEquals(201, response.getStatusCode().value(), "同键并发均应回放成功");
                bodies.add(response.getBody());
            }
            assertEquals(1, bodies.size(), "所有回放响应体必须一致");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'SC-2'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'sc2-same-key'",
                Integer.class));
    }

    @Test
    void concurrentDualConfirmation_exactlyOneGenerationCommitted() throws Exception {
        assertEquals(201, post("/api/experiments/SC-3",
                headers("c1", "COORDINATOR", "sc3-exp"), "{\"blockCount\":2}")
                .getStatusCode().value());
        assertEquals(201, post("/api/experiments/SC-3/sites/S1",
                headers("m1", "UNBLINDED_MANAGER", "sc3-site"),
                "{\"targetEnrollmentLimit\":2}").getStatusCode().value());

        // 两名不同管理人员同时提交同一 activationKey：
        // 按提交顺序，一个成为首确认（202），另一个完成激活（200）
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(() -> post(
                    "/api/experiments/SC-3/sites/S1/activation-confirmations",
                    headers("m1", "UNBLINDED_MANAGER", "sc3-conf-a"),
                    "{\"activationKey\":\"K1\"}").getStatusCode().value()));
            futures.add(pool.submit(() -> post(
                    "/api/experiments/SC-3/sites/S1/activation-confirmations",
                    headers("m2", "UNBLINDED_MANAGER", "sc3-conf-b"),
                    "{\"activationKey\":\"K1\"}").getStatusCode().value()));
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(Set.of(200, 202), new HashSet<>(statuses),
                    "并发双人确认应一个首确认、一个完成激活");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM site WHERE experiment_id = 'SC-3' AND site_code = 'S1'",
                String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT generation FROM site WHERE experiment_id = 'SC-3' AND site_code = 'S1'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_record "
                        + "WHERE experiment_id = 'SC-3' AND site_code = 'S1'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_pending WHERE experiment_id = 'SC-3'",
                Integer.class));
    }

    @Test
    void concurrentSuspendAndAllocation_serializedByCommitOrder() throws Exception {
        createActiveSite("SC-4", "S1", 5, "K1", "sc4");

        // 暂停与分配并发：中心行锁串行化，结果必为两种合法时序之一
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(() -> post("/api/experiments/SC-4/sites/S1/suspension",
                    headers("m1", "UNBLINDED_MANAGER", "sc4-suspend"), null)
                    .getStatusCode().value()));
            futures.add(pool.submit(() -> post(
                    "/api/experiments/SC-4/sites/S1/participants/P1/allocations",
                    headers("c1", "COORDINATOR", "sc4-alloc"), null).getStatusCode().value()));
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            int suspendStatus = statuses.get(0);
            int allocStatus = statuses.get(1);
            assertEquals(200, suspendStatus, "ACTIVE 中心暂停应成功");
            assertTrue(allocStatus == 201 || allocStatus == 409,
                    "分配按提交顺序在暂停前成功或暂停后被拒: " + allocStatus);
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals("SUSPENDED", jdbc.queryForObject(
                "SELECT status FROM site WHERE experiment_id = 'SC-4' AND site_code = 'S1'",
                String.class));
        // 暂停后新分配一律 409
        assertEquals(409, post("/api/experiments/SC-4/sites/S1/participants/P2/allocations",
                headers("c1", "COORDINATOR", "sc4-alloc-2"), null).getStatusCode().value());
    }

    @Test
    void concurrentCloseAndUnblindApply_closeWinsOrUnblindBlocks() throws Exception {
        createActiveSite("SC-5", "S1", 5, "K1", "sc5");
        assertEquals(201, post("/api/experiments/SC-5/sites/S1/participants/P1/allocations",
                headers("c1", "COORDINATOR", "sc5-alloc"), null).getStatusCode().value());

        // 关闭与揭盲申请并发：中心行锁串行化，按提交顺序裁决——
        // 申请先提交则关闭 422；关闭先提交则申请 409（关闭后不再接受新申请）。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(() -> post("/api/experiments/SC-5/sites/S1/closure",
                    headers("m1", "UNBLINDED_MANAGER", "sc5-close"), null)
                    .getStatusCode().value()));
            futures.add(pool.submit(() -> post(
                    "/api/experiments/SC-5/participants/P1/unblind-requests",
                    headers("c1", "COORDINATOR", "sc5-ub"),
                    "{\"reason\":\"并发核对\"}").getStatusCode().value()));
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            int closeStatus = statuses.get(0);
            int applyStatus = statuses.get(1);
            boolean closeFirst = closeStatus == 200 && applyStatus == 409;
            boolean applyFirst = closeStatus == 422 && applyStatus == 201;
            assertTrue(closeFirst || applyFirst,
                    "并发关闭与揭盲申请须按提交顺序裁决: close=" + closeStatus
                            + " apply=" + applyStatus);
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
