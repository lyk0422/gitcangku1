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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退役与分配、揭盲申请、区组扩容的并发裁决（真实多线程，设超时，核对最终数据）：
 * 同一序号不能同时被分配和封存；待揭盲申请与退役按提交顺序串行；
 * 扩容与退役互斥后终态一致。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RetirementConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private void createExperiment(String id, String requestId) {
        assertEquals(201, post("/api/experiments/" + id, "c1", "COORDINATOR", requestId,
                "{\"blockCount\":2}"));
    }

    @Test
    void concurrentAllocateVsRetire_sameSeatNeverAllocatedAndSealed() throws Exception {
        // 多轮独立场景：每轮只剩最后一个 B 席，并发“登记该席”与“退役 B”
        for (int round = 1; round <= 4; round++) {
            final String tag = "cr" + round;
            String expId = "CONC-R" + round;
            createExperiment(expId, tag + "-create");
            for (int i = 1; i <= 7; i++) {
                assertEquals(201, post(
                        "/api/experiments/" + expId + "/participants/P" + i + "/allocations",
                        "c1", "COORDINATOR", tag + "-a" + i, null));
            }
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                String allocatePath = "/api/experiments/" + expId + "/participants/P8/allocations";
                Future<Integer> allocate = pool.submit(() ->
                        post(allocatePath, "c1", "COORDINATOR", tag + "-race-alloc", null));
                Future<Integer> retire = pool.submit(() ->
                        post("/api/experiments/" + expId + "/code-retirements",
                                "c1", "COORDINATOR", tag + "-race-retire",
                                "{\"codes\":[\"B\"],\"reason\":\"并发退役\"}"));
                int allocateStatus = allocate.get(30, TimeUnit.SECONDS);
                int retireStatus = retire.get(30, TimeUnit.SECONDS);

                // 两种合法终局：登记胜出（201+退役判已耗尽409），或退役胜出（201+登记满额422）
                boolean allocateWon = allocateStatus == 201 && retireStatus == 409;
                boolean retireWon = retireStatus == 201 && allocateStatus == 422;
                assertTrue(allocateWon || retireWon,
                        "非法并发终局: allocate=" + allocateStatus + " retire=" + retireStatus);

                // 最终数据：席位 (2,4) 要么被 P8 占用且未封存，要么已封存且无分配
                Integer allocated = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM allocation WHERE experiment_id = ? "
                                + "AND block_no = 2 AND seat_no = 4", Integer.class, expId);
                String seatStatus = jdbc.queryForObject(
                        "SELECT status FROM seat WHERE experiment_id = ? "
                                + "AND block_no = 2 AND seat_no = 4", String.class, expId);
                Integer sealed = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sealed_seat WHERE experiment_id = ? "
                                + "AND block_no = 2 AND seat_no = 4", Integer.class, expId);
                if (allocateWon) {
                    assertEquals(1, allocated);
                    assertEquals("ACTIVE", seatStatus);
                    assertEquals(0, sealed);
                } else {
                    assertEquals(0, allocated);
                    assertEquals("SEALED", seatStatus);
                    assertEquals(1, sealed);
                }
            } finally {
                pool.shutdown();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void concurrentUnblindApplyVsRetire_serializedByCommitOrder() throws Exception {
        // 多轮独立场景：并发“对 A 席参与者提揭盲申请”与“退役 A”
        for (int round = 1; round <= 4; round++) {
            final String tag = "cu" + round;
            String expId = "CONC-U" + round;
            createExperiment(expId, tag + "-create");
            assertEquals(201, post("/api/experiments/" + expId + "/participants/PA/allocations",
                    "c1", "COORDINATOR", tag + "-alloc", null));

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> apply = pool.submit(() ->
                        post("/api/experiments/" + expId + "/participants/PA/unblind-requests",
                                "c1", "COORDINATOR", tag + "-race-apply",
                                "{\"reason\":\"紧急\"}"));
                Future<Integer> retire = pool.submit(() ->
                        post("/api/experiments/" + expId + "/code-retirements",
                                "c1", "COORDINATOR", tag + "-race-retire",
                                "{\"codes\":[\"A\"],\"reason\":\"并发退役\"}"));
                int applyStatus = apply.get(30, TimeUnit.SECONDS);
                int retireStatus = retire.get(30, TimeUnit.SECONDS);

                // 申请始终合法（分配存在）；退役若后于申请提交则必须看到待审并拒绝
                assertEquals(201, applyStatus);
                assertTrue(retireStatus == 201 || retireStatus == 409,
                        "退役只能成功或冲突: " + retireStatus);
                Integer pending = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM unblind_request WHERE experiment_id = ? "
                                + "AND status = 'PENDING'", Integer.class, expId);
                Integer retiredCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM retired_code WHERE experiment_id = ? "
                                + "AND treatment = 'A'", Integer.class, expId);
                // 终态一致：待审申请与退役不能由“先申请后退役却漏检”产生矛盾；
                // 若退役成功，则它一定先于申请提交（申请随后落在已退役代码的既有分配上，合法）
                assertTrue(pending == 1, "申请必须存在");
                assertTrue(retiredCount == (retireStatus == 201 ? 1 : 0));
            } finally {
                pool.shutdown();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void concurrentExpansionVsRetirement_consistentFinalState() throws Exception {
        // 并发“扩容 2 个区组”与“退役 A”：两种提交顺序都合法，但终态必须一致
        for (int round = 1; round <= 4; round++) {
            final String tag = "cx" + round;
            String expId = "CONC-X" + round;
            createExperiment(expId, tag + "-create");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> expand = pool.submit(() ->
                        post("/api/experiments/" + expId + "/blocks",
                                "c1", "COORDINATOR", tag + "-race-expand",
                                "{\"additionalBlocks\":2}"));
                Future<Integer> retire = pool.submit(() ->
                        post("/api/experiments/" + expId + "/code-retirements",
                                "c1", "COORDINATOR", tag + "-race-retire",
                                "{\"codes\":[\"A\"],\"reason\":\"并发退役\"}"));
                int expandStatus = expand.get(30, TimeUnit.SECONDS);
                int retireStatus = retire.get(30, TimeUnit.SECONDS);
                assertEquals(201, expandStatus);
                assertEquals(201, retireStatus);

                // 终态：4 个区组；不存在任何 ACTIVE 的 A 席；A 席要么未建（先退役后扩容）
                // 要么已封存（先扩容后退役）；封存数与库内 A 席总数一致
                assertEquals(4, jdbc.queryForObject(
                        "SELECT block_count FROM experiment WHERE id = ?", Integer.class, expId));
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM seat WHERE experiment_id = ? "
                                + "AND treatment = 'A' AND status = 'ACTIVE'",
                        Integer.class, expId));
                Integer aSeats = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM seat WHERE experiment_id = ? AND treatment = 'A'",
                        Integer.class, expId);
                Integer sealedA = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sealed_seat WHERE experiment_id = ? "
                                + "AND treatment = 'A'", Integer.class, expId);
                assertEquals(aSeats, sealedA, "所有已建 A 席都必须被封存");
                assertTrue(aSeats == 4 || aSeats == 8,
                        "A 席总数取决于提交顺序: " + aSeats);
                // B 席不受退役影响：4 区组 × 2 = 8，全部 ACTIVE
                assertEquals(8, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM seat WHERE experiment_id = ? "
                                + "AND treatment = 'B' AND status = 'ACTIVE'",
                        Integer.class, expId));
            } finally {
                pool.shutdown();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }
}
