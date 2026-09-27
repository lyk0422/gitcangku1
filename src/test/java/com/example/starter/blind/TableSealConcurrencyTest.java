package com.example.starter.blind;

import com.example.starter.blind.service.SequenceDigest;
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
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 封存/扩容/分配并发边界（真实多线程 + CyclicBarrier 协调，断言最终业务数据，不靠睡眠）：
 * 封存与首次分配竞争按事务提交顺序裁决；同序号并发分配最多一人成功；
 * 扩容按版本链行锁提交顺序裁决；同 requestId 并发扩容恰好一次生效并回放同一结果。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TableSealConcurrencyTest extends AbstractBlindIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private static final List<String> V1_SEQUENCE = List.of("A", "A", "B", "B");

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

    private int post(String path, String body, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class)
                .getStatusCode().value();
    }

    private int put(String path, String body, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class)
                .getStatusCode().value();
    }

    private void createExperiment(String expId, int blockCount) {
        assertEquals(201, post("/api/experiments/" + expId, "{\"blockCount\":" + blockCount + "}",
                headers("c1", "COORDINATOR", expId + "-create")));
    }

    private String digest(String key, String expId, int blockNo) {
        return SequenceDigest.compute(key, expId, blockNo, 1, 4, "A,B", V1_SEQUENCE);
    }

    @Test
    void sealRacingFirstAllocation_onlyOneOutcomeWins_finalStateConsistent() throws Exception {
        createExperiment("CRACE-1", 2);
        String sealPath = "/api/experiments/CRACE-1/blocks/1/seals/race-key";
        String sealBody = "{\"sequenceDigest\":\"" + digest("race-key", "CRACE-1", 1) + "\"}";
        String allocPath = "/api/experiments/CRACE-1/participants/RP1/allocations";

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> sealFuture = pool.submit(() -> {
                await(barrier);
                return put(sealPath, sealBody, headers("c1", "COORDINATOR", "race-seal"));
            });
            Future<Integer> allocFuture = pool.submit(() -> {
                await(barrier);
                return post(allocPath, null, headers("c1", "COORDINATOR", "race-alloc"));
            });
            // 封存与分配竞争：封存 201 或 409（分配先提交），分配恒 201（封存不阻止分配）
            int sealStatus = sealFuture.get(30, TimeUnit.SECONDS);
            int allocStatus = allocFuture.get(30, TimeUnit.SECONDS);
            assertTrue(sealStatus == 201 || sealStatus == 409,
                    "封存只可能胜出(201)或因先有分配而冲突(409)，实际: " + sealStatus);
            assertEquals(201, allocStatus, "封存不阻止按版本剩余序列分配");

            long sealCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM table_seal WHERE experiment_id = 'CRACE-1' AND block_no = 1",
                    Long.class);
            if (sealStatus == 201) {
                assertEquals(1L, sealCount, "封存胜出时恰有一条封存记录");
            } else {
                assertEquals(0L, sealCount, "分配胜出时不得有封存记录");
            }
            // 无论谁胜出，唯一一条分配固化在 v1 且只占一个序号
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'CRACE-1'",
                    Integer.class));
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentAllocationsOnExpandedVersions_sameSequenceWonByAtMostOne() throws Exception {
        // 两个区组都封存并扩容：关闭两个 v1 的各 4 个余量，各建容量 2 的 v2；共 4 个 ACTIVE 名额
        createExperiment("CRACE-2", 2);
        for (int blockNo : new int[]{1, 2}) {
            String key = "race-key-" + blockNo;
            assertEquals(201, put("/api/experiments/CRACE-2/blocks/" + blockNo + "/seals/" + key,
                    "{\"sequenceDigest\":\"" + digest(key, "CRACE-2", blockNo) + "\"}",
                    headers("c1", "COORDINATOR", "race-seal-" + blockNo)));
            assertEquals(201, post(
                    "/api/experiments/CRACE-2/blocks/" + blockNo + "/table-versions",
                    "{\"sealKey\":\"" + key + "\",\"expectedRemaining\":4,\"newCapacity\":2}",
                    headers("c1", "COORDINATOR", "race-expand-" + blockNo)));
        }
        // 先占 2 个名额，剩 2 个 ACTIVE 名额；4 线程并发：恰好 2 个成功、2 个满额 422
        assertEquals(201, post("/api/experiments/CRACE-2/participants/E1/allocations", null,
                headers("c1", "COORDINATOR", "race-e1")));
        assertEquals(201, post("/api/experiments/CRACE-2/participants/E2/allocations", null,
                headers("c1", "COORDINATOR", "race-e2")));

        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final String pid = "CP" + i;
                final String reqId = "race-cp-" + i;
                futures.add(pool.submit(() -> {
                    await(barrier);
                    return post("/api/experiments/CRACE-2/participants/" + pid + "/allocations",
                            null, headers("c1", "COORDINATOR", reqId));
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(2, statuses.stream().filter(s -> s == 201).count(),
                    "恰好剩余名额个分配成功");
            assertEquals(2, statuses.stream().filter(s -> s == 422).count(),
                    "其余并发分配必须满额 422，同一序号不得两人成功");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终数据：共 4 条分配；版本内序号两两不同，唯一约束实际生效
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'CRACE-2'", Integer.class));
        List<String> occupied = jdbc.queryForList(
                "SELECT CONCAT(table_version_id, '-', seat_no) FROM allocation "
                        + "WHERE experiment_id = 'CRACE-2'", String.class);
        assertEquals(4, new HashSet<>(occupied).size(), "同一版本同一序号最多一人占用");
        // 被关闭的 v1 余量没有任何新分配
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation a JOIN random_table_version v ON v.id = a.table_version_id "
                        + "WHERE v.experiment_id = 'CRACE-2' AND v.version_no = 1", Integer.class));
    }

    @Test
    void sameRequestIdConcurrentExpand_exactlyOneVersionCreated_allReplaySameResult()
            throws Exception {
        createExperiment("CRACE-3", 2);
        String key = "anchor-3";
        assertEquals(201, put("/api/experiments/CRACE-3/blocks/1/seals/" + key,
                "{\"sequenceDigest\":\"" + digest(key, "CRACE-3", 1) + "\"}",
                headers("c1", "COORDINATOR", "race3-seal")));

        int threads = 5;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    await(barrier);
                    return rest.exchange(
                            "/api/experiments/CRACE-3/blocks/1/table-versions",
                            HttpMethod.POST,
                            new HttpEntity<>(
                                    "{\"sealKey\":\"anchor-3\",\"expectedRemaining\":4,\"newCapacity\":6}",
                                    headers("c1", "COORDINATOR", "race3-expand")),
                            String.class);
                }));
            }
            Set<String> bodies = new HashSet<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
                assertEquals(201, response.getStatusCode().value(), "同键同参并发均应回放成功");
                bodies.add(response.getBody());
            }
            assertEquals(1, bodies.size(), "所有回放必须返回同一后继版本");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 只产生一个后继版本；v1 关闭、v2 容量 6 ACTIVE；无半成品版本
        List<Integer> versions = jdbc.queryForList(
                "SELECT version_no FROM random_table_version WHERE experiment_id = 'CRACE-3' "
                        + "AND block_no = 1 ORDER BY version_no", Integer.class);
        assertEquals(List.of(1, 2), versions);
        assertEquals(6, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat s JOIN random_table_version v ON v.id = s.table_version_id "
                        + "WHERE v.experiment_id = 'CRACE-3' AND v.block_no = 1 AND v.version_no = 2",
                Integer.class));
    }

    @Test
    void distinctRequestIdConcurrentExpand_chainByCommitOrder_noHalfProduct() throws Exception {
        createExperiment("CRACE-4", 2);
        String key = "anchor-4";
        assertEquals(201, put("/api/experiments/CRACE-4/blocks/1/seals/" + key,
                "{\"sequenceDigest\":\"" + digest(key, "CRACE-4", 1) + "\"}",
                headers("c1", "COORDINATOR", "race4-seal")));

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            // 两次独立扩容（不同 requestId、相同余量要求）：按提交顺序串行生效，
            // 第二次在第一次提交后读到的最新 ACTIVE 版本上再扩容。
            for (int i = 1; i <= threads; i++) {
                final String reqId = "race4-expand-" + i;
                futures.add(pool.submit(() -> {
                    await(barrier);
                    return post("/api/experiments/CRACE-4/blocks/1/table-versions",
                            "{\"sealKey\":\"anchor-4\",\"expectedRemaining\":4,\"newCapacity\":4}",
                            headers("c1", "COORDINATOR", reqId));
                }));
            }
            for (Future<Integer> future : futures) {
                assertEquals(201, future.get(30, TimeUnit.SECONDS),
                        "不同 requestId 的两次扩容都应按提交顺序成功");
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 版本链 v1(CLOSED) -> v2(CLOSED) -> v3(ACTIVE)，每次扩容恰好关闭一个旧版本
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM random_table_version WHERE experiment_id = 'CRACE-4' "
                        + "AND block_no = 1 ORDER BY version_no", String.class);
        assertEquals(List.of("CLOSED", "CLOSED", "ACTIVE"), statuses);
        List<Integer> capacities = jdbc.queryForList(
                "SELECT capacity FROM random_table_version WHERE experiment_id = 'CRACE-4' "
                        + "AND block_no = 1 ORDER BY version_no", Integer.class);
        assertEquals(List.of(4, 4, 4), capacities);
        // v2 无分配即被关闭，其 4 个余量成为死名额；v3 有 4 个可分配名额
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat s JOIN random_table_version v ON v.id = s.table_version_id "
                        + "WHERE v.experiment_id = 'CRACE-4' AND v.block_no = 1 AND v.version_no = 3",
                Integer.class));
        // 随后的新分配落在 v3 序号 1
        assertEquals(201, post("/api/experiments/CRACE-4/participants/AFTER/allocations", null,
                headers("c1", "COORDINATOR", "race4-after")));
        assertEquals(3, jdbc.queryForObject(
                "SELECT v.version_no FROM allocation a JOIN random_table_version v "
                        + "ON v.id = a.table_version_id WHERE a.experiment_id = 'CRACE-4' "
                        + "AND a.participant_id = 'AFTER'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT seat_no FROM allocation WHERE experiment_id = 'CRACE-4' "
                        + "AND participant_id = 'AFTER'", Integer.class));
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (BrokenBarrierException | java.util.concurrent.TimeoutException e) {
            throw new RuntimeException(e);
        }
    }
}
