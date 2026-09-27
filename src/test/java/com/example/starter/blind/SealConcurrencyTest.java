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
 * 封存与分配并发裁决（真实 H2 + 多线程，设超时断言业务结果）：
 * 同一序号并发分配最多一人成功；同区组并发封存仅一次成功；
 * 封存与首次分配并发时结果只能是“先封存（分配固化封存版本）”或“先分配（封存409）”。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SealConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private int post(String path, String actor, String role, String requestId, String body) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers(actor, role, requestId)), String.class)
                .getStatusCode().value();
    }

    private void createExperiment(String expId, String reqId) {
        assertEquals(201, post("/api/experiments/" + expId, "c1", "COORDINATOR", reqId,
                "{\"blockCount\":2}"));
    }

    @Test
    void concurrentAllocation_forLastSequence_onlyOneSucceeds() throws Exception {
        createExperiment("SC-1", "sc1-create");
        assertEquals(201, post("/api/experiments/SC-1/blocks/1/seals/SC1-S1",
                "c1", "COORDINATOR", "sc1-seal-1", null));
        // 区组2封存后立即关闭全部余量，使全实验只剩区组1序号4一个名额
        assertEquals(201, post("/api/experiments/SC-1/blocks/2/seals/SC1-S2",
                "c1", "COORDINATOR", "sc1-seal-2", null));
        assertEquals(200, post("/api/experiments/SC-1/blocks/2/seals/SC1-S2/closure",
                "c1", "COORDINATOR", "sc1-close-2", null));
        for (int i = 1; i <= 3; i++) {
            assertEquals(201, post("/api/experiments/SC-1/participants/P" + i + "/allocations",
                    "c1", "COORDINATOR", "sc1-alloc-" + i, null));
        }

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String pid = "RACE" + i;
                final String reqId = "sc1-race-" + i;
                futures.add(pool.submit(() -> post(
                        "/api/experiments/SC-1/participants/" + pid + "/allocations",
                        "c1", "COORDINATOR", reqId, null)));
            }
            int success = 0;
            int full = 0;
            for (Future<Integer> future : futures) {
                int status = future.get(30, TimeUnit.SECONDS);
                assertTrue(status == 201 || status == 422, "只允许成功或满额: " + status);
                if (status == 201) {
                    success++;
                } else {
                    full++;
                }
            }
            assertEquals(1, success, "同一序号并发最多一人成功");
            assertEquals(threads - 1, full, "其余满额 422");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终数据：序号4恰好一条分配，且固化封存版本
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'SC-1' "
                        + "AND block_no = 1 AND seat_no = 4", Integer.class));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'SC-1'", Integer.class));
        assertEquals(4L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'SC-1' AND seal_id = 'SC1-S1'",
                Long.class));
    }

    @Test
    void concurrentSeal_sameBlock_onlyOneSucceeds() throws Exception {
        createExperiment("SC-2", "sc2-create");
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String sealId = "SC2-S" + i;
                final String reqId = "sc2-seal-" + i;
                futures.add(pool.submit(() -> post(
                        "/api/experiments/SC-2/blocks/1/seals/" + sealId,
                        "c1", "COORDINATOR", reqId, null)));
            }
            int created = 0;
            int conflicts = 0;
            for (Future<Integer> future : futures) {
                int status = future.get(30, TimeUnit.SECONDS);
                if (status == 201) {
                    created++;
                } else {
                    assertEquals(409, status);
                    conflicts++;
                }
            }
            assertEquals(1, created, "并发封存仅一次成功");
            assertEquals(threads - 1, conflicts);
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_seal WHERE experiment_id = 'SC-2' AND block_no = 1",
                Integer.class));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'SC-2' AND block_no = 1",
                Integer.class), "失败封存不得改动随机表");
    }

    @Test
    void concurrentSealAndFirstAllocation_eitherOrderLeavesConsistentState() throws Exception {
        createExperiment("SC-3", "sc3-create");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> sealFuture = pool.submit(() -> post(
                    "/api/experiments/SC-3/blocks/1/seals/SC3-S1",
                    "c1", "COORDINATOR", "sc3-seal", null));
            Future<Integer> allocFuture = pool.submit(() -> post(
                    "/api/experiments/SC-3/participants/P1/allocations",
                    "c1", "COORDINATOR", "sc3-alloc", null));
            int sealStatus = sealFuture.get(30, TimeUnit.SECONDS);
            int allocStatus = allocFuture.get(30, TimeUnit.SECONDS);
            assertEquals(201, allocStatus, "区组初始有名额，分配必成功");

            Integer allocationVersion = jdbc.queryForObject(
                    "SELECT version_no FROM allocation WHERE experiment_id = 'SC-3' "
                            + "AND participant_id = 'P1'", Integer.class);
            String allocationSeal = jdbc.queryForObject(
                    "SELECT seal_id FROM allocation WHERE experiment_id = 'SC-3' "
                            + "AND participant_id = 'P1'", String.class);
            long sealCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM random_table_seal WHERE experiment_id = 'SC-3' "
                            + "AND block_no = 1", Long.class);
            if (sealStatus == 201) {
                // 封存先提交：分配必须固化该封存版本
                assertEquals(1L, sealCount);
                assertEquals(1, allocationVersion);
                assertEquals("SC3-S1", allocationSeal);
            } else {
                // 分配先提交：封存按 409 拒绝，封存前历史分配的版本与封存编号保持 NULL
                assertEquals(409, sealStatus);
                assertEquals(0L, sealCount);
                assertEquals(null, allocationVersion);
                assertEquals(null, allocationSeal);
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
