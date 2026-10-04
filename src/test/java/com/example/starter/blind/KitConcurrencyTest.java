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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 处理包并发边界（真实多线程与真实 H2 事务，不用睡眠代替断言）：
 * 两名同代码参与者只剩一包时并发领取仅一人成功，另一人 422；
 * 成功方重试返回同一包装码且无代码；领取与退组、关闭并发保持原子。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KitConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private void createExperiment(String experimentId, int blockCount, String requestId) {
        assertEquals(201, post("/api/experiments/" + experimentId,
                headers("c1", "COORDINATOR", requestId),
                "{\"blockCount\":" + blockCount + "}").getStatusCode().value());
    }

    private void register(String experimentId, String participantId, String requestId) {
        assertEquals(201, post(
                "/api/experiments/" + experimentId + "/participants/" + participantId
                        + "/allocations",
                headers("c1", "COORDINATOR", requestId), null).getStatusCode().value());
    }

    private void registerKit(String experimentId, String packageNo, String treatment,
                             String requestId) {
        assertEquals(201, post("/api/experiments/" + experimentId + "/kits",
                headers("s1", "SUPPLY", requestId),
                "{\"packageNo\":\"" + packageNo + "\",\"treatment\":\"" + treatment + "\"}")
                .getStatusCode().value());
    }

    private String treatmentOf(String experimentId, String participantId) {
        return jdbc.queryForObject(
                "SELECT s.treatment FROM seat s JOIN allocation a "
                        + "ON a.experiment_id = s.experiment_id AND a.block_no = s.block_no "
                        + "AND a.seat_no = s.seat_no "
                        + "WHERE a.experiment_id = ? AND a.participant_id = ?",
                String.class, experimentId, participantId);
    }

    /** 两个任务同闩并发执行，带超时返回。 */
    private List<Future<ResponseEntity<String>>> runConcurrently(CountDownLatch latch,
                                                                 Callable<ResponseEntity<String>> first,
                                                                 Callable<ResponseEntity<String>> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ResponseEntity<String>> gatedFirst = () -> {
                assertTrue(latch.await(10, TimeUnit.SECONDS));
                return first.call();
            };
            Callable<ResponseEntity<String>> gatedSecond = () -> {
                assertTrue(latch.await(10, TimeUnit.SECONDS));
                return second.call();
            };
            Future<ResponseEntity<String>> f1 = pool.submit(gatedFirst);
            Future<ResponseEntity<String>> f2 = pool.submit(gatedSecond);
            latch.countDown();
            return List.of(f1, f2);
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }
    @Test
    void concurrentPickups_sameTreatmentOneKit_exactlyOneIssued_other422_winnerReplayStable()
            throws Exception {
        createExperiment("KCONC-1", 2, "kc1-create");
        // 每区组前两席同代码：P1、P2 必为同一处理代码
        register("KCONC-1", "P1", "kc1-alloc-1");
        register("KCONC-1", "P2", "kc1-alloc-2");
        String treatment = treatmentOf("KCONC-1", "P1");
        assertEquals(treatment, treatmentOf("KCONC-1", "P2"));
        // 只剩一包匹配
        registerKit("KCONC-1", "K-ONLY", treatment, "kc1-kit-1");

        CountDownLatch latch = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = runConcurrently(latch,
                () -> post("/api/experiments/KCONC-1/participants/P1/kit-pickups",
                        headers("c1", "COORDINATOR", "kc1-pickup-1"), null),
                () -> post("/api/experiments/KCONC-1/participants/P2/kit-pickups",
                        headers("c1", "COORDINATOR", "kc1-pickup-2"), null));
        ResponseEntity<String> r1 = futures.get(0).get(30, TimeUnit.SECONDS);
        ResponseEntity<String> r2 = futures.get(1).get(30, TimeUnit.SECONDS);

        int s1 = r1.getStatusCode().value();
        int s2 = r2.getStatusCode().value();
        // 恰好一人 201，另一人 422；不双发
        assertTrue((s1 == 201 && s2 == 422) || (s1 == 422 && s2 == 201),
                "应恰好一人成功另一人 422，实际: " + s1 + "/" + s2);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'KCONC-1'", Integer.class));
        assertEquals("ISSUED", jdbc.queryForObject(
                "SELECT status FROM treatment_kit WHERE experiment_id = 'KCONC-1' "
                        + "AND package_no = 'K-ONLY'", String.class));

        // 成功方用同一 requestId 重试：返回同一包装码，且响应无 A/B
        ResponseEntity<String> winner = s1 == 201 ? r1 : r2;
        String winnerRequestId = s1 == 201 ? "kc1-pickup-1" : "kc1-pickup-2";
        String winnerParticipant = s1 == 201 ? "P1" : "P2";
        assertFalse(winner.getBody().contains("\"treatment\""));
        assertFalse(winner.getBody().contains("\"packageNo\""));

        ResponseEntity<String> replay = post(
                "/api/experiments/KCONC-1/participants/" + winnerParticipant + "/kit-pickups",
                headers("c1", "COORDINATOR", winnerRequestId), null);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(winner.getBody(), replay.getBody(), "重试应回放同一包装码与领取编号");
        assertFalse(replay.getBody().contains("\"" + treatment + "\""), "重放响应不得含处理代码值");
        // 重试不产生第二次发放
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'KCONC-1'", Integer.class));
    }

    @Test
    void concurrentPickupAndWithdraw_atomic_noIssueToWithdrawn() throws Exception {
        createExperiment("KCONC-2", 2, "kc2-create");
        register("KCONC-2", "P1", "kc2-alloc-1");
        String treatment = treatmentOf("KCONC-2", "P1");
        registerKit("KCONC-2", "K-1", treatment, "kc2-kit-1");

        CountDownLatch latch = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = runConcurrently(latch,
                () -> post("/api/experiments/KCONC-2/participants/P1/kit-pickups",
                        headers("c1", "COORDINATOR", "kc2-pickup-1"), null),
                () -> post("/api/experiments/KCONC-2/participants/P1/withdrawal",
                        headers("c1", "COORDINATOR", "kc2-withdraw-1"), null));
        int pickupStatus = futures.get(0).get(30, TimeUnit.SECONDS).getStatusCode().value();
        int withdrawStatus = futures.get(1).get(30, TimeUnit.SECONDS).getStatusCode().value();

        // 退组一定成功；领取要么先于退组完成（201），要么看到已退组（409），不得发给已退组者
        assertEquals(200, withdrawStatus);
        assertTrue(pickupStatus == 201 || pickupStatus == 409,
                "领取只能 201 或 409，实际: " + pickupStatus);
        assertEquals("WITHDRAWN", jdbc.queryForObject(
                "SELECT status FROM allocation WHERE experiment_id = 'KCONC-2' "
                        + "AND participant_id = 'P1'", String.class));
        if (pickupStatus == 409) {
            // 未发出：包仍在库、无领取记录
            assertEquals("STOCK", jdbc.queryForObject(
                    "SELECT status FROM treatment_kit WHERE experiment_id = 'KCONC-2' "
                            + "AND package_no = 'K-1'", String.class));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'KCONC-2'",
                    Integer.class));
        } else {
            // 已发出：包与领取记录一致绑定，绝不双发
            assertEquals("ISSUED", jdbc.queryForObject(
                    "SELECT status FROM treatment_kit WHERE experiment_id = 'KCONC-2' "
                            + "AND package_no = 'K-1'", String.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'KCONC-2'",
                    Integer.class));
        }
    }

    @Test
    void concurrentPickupAndClose_atomic_noIssueAfterClose() throws Exception {
        createExperiment("KCONC-3", 2, "kc3-create");
        register("KCONC-3", "P1", "kc3-alloc-1");
        String treatment = treatmentOf("KCONC-3", "P1");
        registerKit("KCONC-3", "K-1", treatment, "kc3-kit-1");

        CountDownLatch latch = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = runConcurrently(latch,
                () -> post("/api/experiments/KCONC-3/participants/P1/kit-pickups",
                        headers("c1", "COORDINATOR", "kc3-pickup-1"), null),
                () -> post("/api/experiments/KCONC-3/close",
                        headers("c1", "COORDINATOR", "kc3-close-1"), null));
        int pickupStatus = futures.get(0).get(30, TimeUnit.SECONDS).getStatusCode().value();
        int closeStatus = futures.get(1).get(30, TimeUnit.SECONDS).getStatusCode().value();

        // 关闭一定成功；领取要么先于关闭（201），要么看到已关闭（409）
        assertEquals(200, closeStatus);
        assertTrue(pickupStatus == 201 || pickupStatus == 409,
                "领取只能 201 或 409，实际: " + pickupStatus);
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM experiment WHERE id = 'KCONC-3'", String.class));
        String kitStatus = jdbc.queryForObject(
                "SELECT status FROM treatment_kit WHERE experiment_id = 'KCONC-3' "
                        + "AND package_no = 'K-1'", String.class);
        int pickupRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'KCONC-3'", Integer.class);
        if (pickupStatus == 409) {
            assertEquals("STOCK", kitStatus);
            assertEquals(0, pickupRows);
        } else {
            assertEquals("ISSUED", kitStatus);
            assertEquals(1, pickupRows);
        }
    }
}