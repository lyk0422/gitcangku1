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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启用门禁并发边界（真实多线程 + 真实 H2 行锁，用栅栏协调，不用睡眠判断）：
 * 启用提交与分配并发时不超冻结量、不在启用提交前消耗名额；
 * 已启用快照下并发分配恰好冻结量成功；同参与者并发只占一席；
 * 负责人/复核人并发交叉确认恰好一次启用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteActivationConcurrencyTest extends AbstractBlindIntegrationTest {


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

    private int postStatus(String path, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(null, headers), String.class)
                .getStatusCode().value();
    }

    private void createSite(String siteId, String lead) {
        assertEquals(201, rest.exchange("/api/sites/" + siteId, HttpMethod.POST,
                new HttpEntity<>("{\"leadActorId\":\"" + lead + "\"}",
                        headers(lead, "COORDINATOR", siteId.toLowerCase() + "-create")),
                String.class).getStatusCode().value());
    }

    private String submitConfig(String siteId, String lead, String reqId, String blocksJson) {
        ResponseEntity<String> resp = rest.exchange(
                "/api/sites/" + siteId + "/activation-requests", HttpMethod.POST,
                new HttpEntity<>("{\"blocks\":" + blocksJson + "}",
                        headers(lead, "COORDINATOR", reqId)), String.class);
        assertEquals(201, resp.getStatusCode().value());
        return jdbc.queryForObject(
                "SELECT id FROM site_activation_request WHERE site_id = ? ORDER BY version DESC LIMIT 1",
                String.class, siteId);
    }

    @Test
    void activationConcurrentWithAllocation_noConsumeBeforeCommit_andNeverExceedsFrozen()
            throws Exception {
        createSite("CC", "lead-c");
        // 首次启用：中心 INACTIVE，区组1冻结10个名额
        String activationId = submitConfig("CC", "lead-c", "cc-submit",
                "[{\"blockNo\":1,\"quota\":10}]");
        int activeVersion = jdbc.queryForObject(
                "SELECT version FROM site_activation_request WHERE id = ?",
                Integer.class, activationId);
        // 负责人先确认，仅差复核确认；中心仍 INACTIVE
        assertEquals(200, postStatus(
                "/api/sites/activation-requests/" + activationId + "/lead-confirmation",
                headers("lead-c", "COORDINATOR", "cc-lead")));
        assertEquals("INACTIVE", jdbc.queryForObject(
                "SELECT status FROM site WHERE id = 'CC'", String.class));

        int allocThreads = 15;
        CyclicBarrier barrier = new CyclicBarrier(allocThreads + 1);
        ExecutorService pool = Executors.newFixedThreadPool(allocThreads);
        List<Future<Integer>> allocFutures = new ArrayList<>();
        try {
            for (int i = 1; i <= allocThreads; i++) {
                final String pid = "CCP" + i;
                final String reqId = "cc-alloc-" + i;
                allocFutures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return postStatus(
                            "/api/sites/CC/participants/" + pid + "/allocations",
                            headers("lead-c", "COORDINATOR", reqId));
                }));
            }
            // 主线程与所有分配线程同时放行：复核确认（启用提交）与分配争抢同一中心行锁
            barrier.await(30, TimeUnit.SECONDS);
            int activateStatus = postStatus(
                    "/api/sites/activation-requests/" + activationId + "/reviewer-confirmation",
                    headers("rev-c", "REVIEWER", "cc-reviewer"));
            assertEquals(200, activateStatus);

            List<Integer> waveStatuses = new ArrayList<>();
            for (Future<Integer> future : allocFutures) {
                waveStatuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long waveSuccess = waveStatuses.stream().filter(s -> s == 201).count();
            long waveConflict = waveStatuses.stream().filter(s -> s == 409).count();
            assertEquals(allocThreads, waveSuccess + waveConflict,
                    "并发分配只允许 201 或 409，实际: " + waveStatuses);
            assertTrue(waveSuccess <= 10, "启用窗口内成功数绝不能超过冻结量 10");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 所有已落库分配都必须基于启用提交后的同一快照：启用事务提交前不可能消耗名额
        assertEquals(activeVersion, jdbc.queryForObject(
                "SELECT active_version FROM site WHERE id = 'CC'", Integer.class));
        List<Integer> versions = jdbc.queryForList(
                "SELECT DISTINCT version FROM site_allocation WHERE site_id = 'CC'",
                Integer.class);
        assertTrue(versions.isEmpty() || versions.equals(List.of(activeVersion)),
                "只存在启用提交后版本的分配，实际版本: " + versions);
        long current = jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_allocation WHERE site_id = 'CC'", Long.class);
        assertTrue(current <= 10);

        // 顺序补齐到冻结量 10
        int seq = allocThreads + 1;
        while (current < 10) {
            int status = postStatus(
                    "/api/sites/CC/participants/CCS" + seq + "/allocations",
                    headers("lead-c", "COORDINATOR", "cc-fill-" + seq));
            assertEquals(201, status, "冻结量内应可继续分配");
            seq++;
            current++;
        }
        // 第 11 个被拒
        assertEquals(409, postStatus(
                "/api/sites/CC/participants/CCSOVER/allocations",
                headers("lead-c", "COORDINATOR", "cc-over")));
        assertEquals(10, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_allocation WHERE site_id = 'CC'", Integer.class));
        assertEquals(10, jdbc.queryForObject(
                "SELECT frozen_quota FROM site_block_snapshot "
                        + "WHERE site_id = 'CC' AND version = ? AND block_no = 1",
                Integer.class, activeVersion));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_block_snapshot WHERE site_id = 'CC' AND version = ?",
                Integer.class, activeVersion));
    }

    @Test
    void concurrentAllocationsOnActiveSite_exactlyFrozenSucceed() throws Exception {
        createSite("CD", "lead-d");
        String v1 = submitConfig("CD", "lead-d", "cd-v1",
                "[{\"blockNo\":1,\"quota\":10}]");
        assertEquals(200, postStatus(
                "/api/sites/activation-requests/" + v1 + "/lead-confirmation",
                headers("lead-d", "COORDINATOR", "cd-lead")));
        assertEquals(200, postStatus(
                "/api/sites/activation-requests/" + v1 + "/reviewer-confirmation",
                headers("rev-d", "REVIEWER", "cd-reviewer")));

        int threads = 15;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final String pid = "CDP" + i;
                final String reqId = "cd-alloc-" + i;
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return postStatus(
                            "/api/sites/CD/participants/" + pid + "/allocations",
                            headers("lead-d", "COORDINATOR", reqId));
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(10, statuses.stream().filter(s -> s == 201).count(),
                    "恰好冻结量 10 个成功");
            assertEquals(5, statuses.stream().filter(s -> s == 409).count(),
                    "其余超出冻结量 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(10, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_allocation WHERE site_id = 'CD'", Integer.class));
        Set<String> participants = new HashSet<>(jdbc.queryForList(
                "SELECT participant_id FROM site_allocation WHERE site_id = 'CD'",
                String.class));
        assertEquals(10, participants.size(), "参与者不得重复占席");
        List<String> blocks = jdbc.queryForList(
                "SELECT DISTINCT block_no FROM site_allocation WHERE site_id = 'CD'",
                String.class);
        assertEquals(Set.of("1"), new HashSet<>(blocks));
    }

    @Test
    void sameParticipantConcurrentAllocations_onlyOneSeatTaken() throws Exception {
        createSite("CE", "lead-e");
        String v1 = submitConfig("CE", "lead-e", "ce-v1",
                "[{\"blockNo\":1,\"quota\":10}]");
        assertEquals(200, postStatus(
                "/api/sites/activation-requests/" + v1 + "/lead-confirmation",
                headers("lead-e", "COORDINATOR", "ce-lead")));
        assertEquals(200, postStatus(
                "/api/sites/activation-requests/" + v1 + "/reviewer-confirmation",
                headers("rev-e", "REVIEWER", "ce-reviewer")));

        int threads = 6;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final String reqId = "ce-same-p-" + i;
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return postStatus(
                            "/api/sites/CE/participants/DUP/allocations",
                            headers("lead-e", "COORDINATOR", reqId));
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, statuses.stream().filter(s -> s == 201).count());
            assertEquals(threads - 1L, statuses.stream().filter(s -> s == 409).count());
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_allocation WHERE site_id = 'CE' AND participant_id = 'DUP'",
                Integer.class));
    }

    @Test
    void crossOrderConcurrentConfirmations_activateExactlyOnce() throws Exception {
        createSite("CF", "lead-f");
        String v1 = submitConfig("CF", "lead-f", "cf-v1",
                "[{\"blockNo\":1,\"quota\":4},{\"blockNo\":2,\"quota\":4}]");

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> leadFuture = pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                return postStatus(
                        "/api/sites/activation-requests/" + v1 + "/lead-confirmation",
                        headers("lead-f", "COORDINATOR", "cf-lead"));
            });
            Future<Integer> reviewerFuture = pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                return postStatus(
                        "/api/sites/activation-requests/" + v1 + "/reviewer-confirmation",
                        headers("rev-f", "REVIEWER", "cf-reviewer"));
            });
            // 并发交叉确认下，先到的一方可能见 PENDING，但最终双方齐备后恰为一次启用
            int leadStatus = leadFuture.get(30, TimeUnit.SECONDS);
            int reviewerStatus = reviewerFuture.get(30, TimeUnit.SECONDS);
            assertTrue(leadStatus == 200 && reviewerStatus == 200);
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 行锁串行化保证：后提交的一方必在同一事务见到对方确认并触发唯一一次启用
        assertEquals("ACTIVATED", jdbc.queryForObject(
                "SELECT status FROM site_activation_request WHERE id = ?", String.class, v1));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM site WHERE id = 'CF'", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_request WHERE id = ? AND status = 'ACTIVATED'",
                Integer.class, v1));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_block_snapshot WHERE site_id = 'CF' AND version = 1",
                Integer.class), "恰好冻结两个区组各一次");
    }
}
