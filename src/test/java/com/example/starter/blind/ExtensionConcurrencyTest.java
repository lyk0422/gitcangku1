package com.example.starter.blind;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

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
 * 扩容并发边界（真实多线程 + 真实 H2 行锁，不用睡眠代替断言）：
 * 扩容后同一参与者并发登记最多成功一次；满额实验上扩容与登记并发恰好新增容量个成功登记；
 * 两次扩容并发按 expectedVersion 裁决只成功一次；扩容与关闭并发按提交顺序裁决且数据不腐。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExtensionConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private int postStatus(String path, String actor, String role, String requestId, String body) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers(actor, role, requestId)), String.class)
                .getStatusCode().value();
    }

    private void createExperiment(String id, int blockCount, String requestId) {
        assertEquals(201, postStatus("/api/experiments/" + id, "c1", "COORDINATOR",
                requestId, "{\"blockCount\":" + blockCount + "}"));
    }

    private void registerUntilFull(String expId, int seats, String reqPrefix) {
        for (int i = 1; i <= seats; i++) {
            assertEquals(201, postStatus(
                    "/api/experiments/" + expId + "/participants/P" + i + "/allocations",
                    "c1", "COORDINATOR", reqPrefix + i, null));
        }
    }

    private static final String ONE_VALID_BLOCK =
            "{\"extensionKey\":\"%s\",\"expectedVersion\":%d,\"blocks\":["
                    + "{\"treatments\":[\"A\",\"A\",\"B\",\"B\"]}]}";

    @Test
    void sameParticipantConcurrentRegistration_afterExtension_atMostOneSuccess()
            throws Exception {
        createExperiment("CE-1", 2, "ce1-create");
        registerUntilFull("CE-1", 8, "ce1-alloc-");
        assertEquals(200, postStatus("/api/experiments/CE-1/block-extensions",
                "c1", "COORDINATOR", "ce1-ext", ONE_VALID_BLOCK.formatted("CE1-E", 1)));

        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return postStatus(
                            "/api/experiments/CE-1/participants/NEWBIE/allocations",
                            "c1", "COORDINATOR",
                            "ce1-newbie-" + seq.incrementAndGet(), null);
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long created = statuses.stream().filter(s -> s == 201).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, created, "同一参与者并发登记最多成功一次");
            assertEquals(threads - 1L, conflict, "其余全部参与者重复冲突 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'CE-1' "
                        + "AND participant_id = 'NEWBIE'", Integer.class));
    }

    @Test
    void registrationsAfterExtensionCommit_concurrently_exactlyCapacityNewSucceed()
            throws Exception {
        createExperiment("CE-2", 2, "ce2-create");
        registerUntilFull("CE-2", 8, "ce2-alloc-");
        // 扩容前：满额登记 422（提交顺序裁决的“扩容未提交”一侧）
        assertEquals(422, postStatus(
                "/api/experiments/CE-2/participants/PRE/allocations",
                "c1", "COORDINATOR", "ce2-pre", null));

        // 提交顺序裁决的“扩容先提交”一侧：扩容先成功提交，随后 8 个新参与者并发登记。
        assertEquals(200, postStatus("/api/experiments/CE-2/block-extensions",
                "c1", "COORDINATOR", "ce2-ext", ONE_VALID_BLOCK.formatted("CE2-E", 1)));

        int registrars = 8;
        CyclicBarrier barrier = new CyclicBarrier(registrars);
        ExecutorService pool = Executors.newFixedThreadPool(registrars);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= registrars; i++) {
                final String pid = "NP" + i;
                final String reqId = "ce2-np-" + i;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return postStatus(
                            "/api/experiments/CE-2/participants/" + pid + "/allocations",
                            "c1", "COORDINATOR", reqId, null);
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long created = statuses.stream().filter(s -> s == 201).count();
            long full = statuses.stream().filter(s -> s == 422).count();
            assertEquals(4, created, "扩容先提交后恰好 4 个新参与者占满新区组");
            assertEquals(4L, full, "其余并发登记在新区组占满后满额 422");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终数据：12 条分配，席位互不重复；新参与者全部落在新区组 3，且填其前 4 席
        assertEquals(12, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'CE-2'", Integer.class));
        List<String> seats = jdbc.queryForList(
                "SELECT CONCAT(block_no, '-', seat_no) AS k FROM allocation "
                        + "WHERE experiment_id = 'CE-2'", String.class);
        assertEquals(12, new HashSet<>(seats).size(), "同一席位不得被两名参与者占用");
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'CE-2' AND block_no = 3",
                Integer.class));
        assertEquals(Set.of("3-1", "3-2", "3-3", "3-4"), new HashSet<>(jdbc.queryForList(
                "SELECT CONCAT(block_no, '-', seat_no) AS k FROM allocation "
                        + "WHERE experiment_id = 'CE-2' AND block_no = 3", String.class)));
        // 扩容后版本为 2，区组总数 3
        assertEquals(Integer.valueOf(3), jdbc.queryForObject(
                "SELECT block_count FROM experiment WHERE id = 'CE-2'", Integer.class));
        assertEquals(Integer.valueOf(2), jdbc.queryForObject(
                "SELECT version FROM experiment WHERE id = 'CE-2'", Integer.class));
    }

    @Test
    void twoExtensionsConcurrent_expectedVersionArbitrates_onlyOneSucceeds() throws Exception {
        createExperiment("CE-3", 2, "ce3-create");
        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final String key = "CE3-E" + i;
                final String reqId = "ce3-ext-" + i;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return postStatus("/api/experiments/CE-3/block-extensions",
                            "c1", "COORDINATOR", reqId, ONE_VALID_BLOCK.formatted(key, 1));
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long ok = statuses.stream().filter(s -> s == 200).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, ok, "同版本并发扩容只允许一个先提交");
            assertEquals(1, conflict, "后提交者因版本不再匹配得到 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        // 只追加了一个区组、一条扩容记录，版本恰好到 2
        assertEquals(3, jdbc.queryForObject(
                "SELECT block_count FROM experiment WHERE id = 'CE-3'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM experiment WHERE id = 'CE-3'", Integer.class));
        assertEquals(12, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'CE-3'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM block_extension WHERE experiment_id = 'CE-3'",
                Integer.class));
    }

    @Test
    void extensionConcurrentWithClose_commitOrderArbitrates_stateAlwaysConsistent()
            throws Exception {
        // 多组独立实验上同时发起扩容与关闭；无论提交顺序如何，最终状态必须自洽。
        int pairs = 12;
        int threads = pairs * 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        // 池大小必须不少于栅栏参与方数：阻塞在 barrier 上的任务不释放线程，
        // 否则排队任务永远无法启动，栅栏凑不齐（首跑即在此超时）。
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= pairs; i++) {
                final String expId = "CE-4-" + i;
                createExperiment(expId, 2, "ce4-create-" + i);
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    int status = postStatus(
                            "/api/experiments/" + expId + "/block-extensions",
                            "c1", "COORDINATOR", "ce4-ext-" + expId,
                            ONE_VALID_BLOCK.formatted("CE4-E-" + expId, 1));
                    return expId + ":EXT:" + status;
                }));
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    int status = postStatus("/api/experiments/" + expId + "/close",
                            "c1", "COORDINATOR", "ce4-close-" + expId, null);
                    return expId + ":CLOSE:" + status;
                }));
            }
            Set<String> extensionFirst = new HashSet<>();
            Set<String> closeFirst = new HashSet<>();
            for (Future<String> future : futures) {
                String result = future.get(30, TimeUnit.SECONDS);
                String[] parts = result.split(":");
                String expId = parts[0];
                String op = parts[1];
                int status = Integer.parseInt(parts[2]);
                if ("EXT".equals(op)) {
                    if (status == 200) {
                        extensionFirst.add(expId);
                    } else {
                        // 关闭先提交：扩容 409
                        assertEquals(409, status, expId + " 关闭先提交时扩容必须 409");
                        closeFirst.add(expId);
                    }
                } else {
                    // 关闭本身：先提交 200；扩容先提交后关闭仍 200（OPEN 仍可关闭）
                    assertEquals(200, status, expId + " 关闭应成功（扩容不改变 OPEN 状态）");
                }
            }
            // 12 组并发：两种提交顺序均允许，但每个实验的最终数据必须自洽（提交顺序由行锁裁决）
            assertEquals(pairs, extensionFirst.size() + closeFirst.size());
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 逐实验断言最终状态自洽：扩容成功的实验 3 区组/12 席/version 2 且已 CLOSED；
        // 关闭先提交的实验保持 2 区组/8 席/version 1/CLOSED；均无扩容残留半成品。
        for (int i = 1; i <= pairs; i++) {
            String expId = "CE-4-" + i;
            int blockCount = jdbc.queryForObject(
                    "SELECT block_count FROM experiment WHERE id = '" + expId + "'",
                    Integer.class);
            int version = jdbc.queryForObject(
                    "SELECT version FROM experiment WHERE id = '" + expId + "'", Integer.class);
            String status = jdbc.queryForObject(
                    "SELECT status FROM experiment WHERE id = '" + expId + "'", String.class);
            int extCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM block_extension WHERE experiment_id = '" + expId + "'",
                    Integer.class);
            int seatCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM seat WHERE experiment_id = '" + expId + "'",
                    Integer.class);
            assertEquals("CLOSED", status, expId + " 最终必须为 CLOSED");
            if (extCount == 1) {
                assertEquals(3, blockCount, expId + " 扩容成功应有 3 个区组");
                assertEquals(2, version, expId + " 扩容成功版本应为 2");
                assertEquals(12, seatCount, expId + " 扩容成功应有 12 个席位");
            } else {
                assertEquals(0, extCount, expId + " 扩容失败不得残留扩容记录");
                assertEquals(2, blockCount, expId + " 扩容失败区组不变");
                assertEquals(1, version, expId + " 扩容失败版本不变");
                assertEquals(8, seatCount, expId + " 扩容失败席位不变");
            }
            // 关闭后再次扩容一律 409
            assertEquals(409, postStatus(
                    "/api/experiments/" + expId + "/block-extensions",
                    "c1", "COORDINATOR", "ce4-ext-after-" + i,
                    ONE_VALID_BLOCK.formatted("CE4-AFTER-" + i, version)));
        }
    }
}
