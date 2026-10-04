package com.example.starter.blind;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 处理包发放并发边界（真实多线程、闩门协调、超时兜底）：
 * 两名同代码参与者只剩一包时并发领取仅一人成功，另一人 422；
 * 领取与退组、关闭一次提交，不双发、不发给已退组者。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KitConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private ResponseEntity<String> post(String path, HttpHeaders headers, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private void createExperiment(String exp, int blocks, String reqId) {
        assertEquals(201, post("/api/experiments/" + exp,
                headers("c1", "COORDINATOR", reqId), "{\"blockCount\":" + blocks + "}")
                .getStatusCode().value());
    }

    private void register(String exp, String pid, String reqId) {
        assertEquals(201, post("/api/experiments/" + exp + "/participants/" + pid + "/allocations",
                headers("c1", "COORDINATOR", reqId), null).getStatusCode().value());
    }

    private void registerKit(String exp, String kitNo, String treatment, String reqId) {
        assertEquals(201, post("/api/experiments/" + exp + "/kits",
                headers("s1", "SUPPLY", reqId),
                "{\"kitNo\":\"" + kitNo + "\",\"treatment\":\"" + treatment + "\"}")
                .getStatusCode().value());
    }

    /** 两个操作在同一闩门信号下并发提交，返回各自响应。 */
    private List<ResponseEntity<String>> runConcurrently(Callable<ResponseEntity<String>> op1,
                                                         Callable<ResponseEntity<String>> op2)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<ResponseEntity<String>> wrap1 = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return op1.call();
            };
            Callable<ResponseEntity<String>> wrap2 = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return op2.call();
            };
            Future<ResponseEntity<String>> f1 = pool.submit(wrap1);
            Future<ResponseEntity<String>> f2 = pool.submit(wrap2);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentPickup_twoParticipantsSameCodeOneKit_exactlyOneSuccess_retryReplays()
            throws Exception {
        createExperiment("EXP-CK1", 2, "ck-create-1");
        // 区组1 前两席均为 A：P1、P2 同代码
        register("EXP-CK1", "P1", "ck-alloc-1");
        register("EXP-CK1", "P2", "ck-alloc-2");
        registerKit("EXP-CK1", "KIT-ONLY", "A", "ck-kit-1");

        List<ResponseEntity<String>> results = runConcurrently(
                () -> post("/api/experiments/EXP-CK1/participants/P1/kit-pickup",
                        headers("c1", "COORDINATOR", "ck-pick-1"), null),
                () -> post("/api/experiments/EXP-CK1/participants/P2/kit-pickup",
                        headers("c2", "COORDINATOR", "ck-pick-2"), null));
        long success = results.stream().filter(r -> r.getStatusCode().value() == 201).count();
        long unprocessable = results.stream().filter(r -> r.getStatusCode().value() == 422).count();
        assertEquals(1, success, "只剩一包时并发领取仅一人成功");
        assertEquals(1, unprocessable, "另一人无匹配包 422");

        // 最终数据：恰好一条领取记录，该包只发一次
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'EXP-CK1'", Integer.class));
        String winnerPid = jdbc.queryForObject(
                "SELECT participant_id FROM kit_pickup WHERE experiment_id = 'EXP-CK1'",
                String.class);
        String winnerReqId = "P1".equals(winnerPid) ? "ck-pick-1" : "ck-pick-2";
        String winnerActor = "P1".equals(winnerPid) ? "c1" : "c2";

        // 重试成功方：同一 requestId 回放同一包装码，且无 A/B
        ResponseEntity<String> retry = post(
                "/api/experiments/EXP-CK1/participants/" + winnerPid + "/kit-pickup",
                headers(winnerActor, "COORDINATOR", winnerReqId), null);
        assertEquals(201, retry.getStatusCode().value());
        JsonNode node = mapper.readTree(retry.getBody());
        String firstBody = results.stream()
                .filter(r -> r.getStatusCode().value() == 201).findFirst().orElseThrow().getBody();
        assertNotNull(firstBody);
        assertEquals(mapper.readTree(firstBody).path("packageCode").asText(),
                node.path("packageCode").asText());
        assertFalse(node.has("treatment"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'EXP-CK1'", Integer.class));
    }

    @Test
    void concurrentPickupAndWithdraw_atomic_noKitToWithdrawnParticipant() throws Exception {
        createExperiment("EXP-CK2", 2, "ck-create-2");
        register("EXP-CK2", "P1", "ck-alloc-21");
        registerKit("EXP-CK2", "KIT-1", "A", "ck-kit-21");

        List<ResponseEntity<String>> results = runConcurrently(
                () -> post("/api/experiments/EXP-CK2/participants/P1/kit-pickup",
                        headers("c1", "COORDINATOR", "ck-pick-21"), null),
                () -> post("/api/experiments/EXP-CK2/participants/P1/withdrawal",
                        headers("c2", "COORDINATOR", "ck-wd-21"), null));
        int pickupStatus = results.get(0).getStatusCode().value();
        int withdrawStatus = results.get(1).getStatusCode().value();
        assertEquals(200, withdrawStatus, "退组始终生效");
        Integer pickups = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'EXP-CK2'", Integer.class);
        if (pickupStatus == 201) {
            // 领取先提交：包已发出不回库，退组随后生效
            assertEquals(1, pickups);
        } else {
            // 退组先提交：领取必须 409，不得发给已退组者
            assertEquals(409, pickupStatus);
            assertEquals(0, pickups);
        }
        assertEquals("WITHDRAWN", jdbc.queryForObject(
                "SELECT status FROM allocation WHERE experiment_id = 'EXP-CK2' "
                        + "AND participant_id = 'P1'", String.class));
    }

    @Test
    void concurrentPickupAndClose_atomic_noKitAfterClose() throws Exception {
        createExperiment("EXP-CK3", 2, "ck-create-3");
        register("EXP-CK3", "P1", "ck-alloc-31");
        registerKit("EXP-CK3", "KIT-1", "A", "ck-kit-31");

        List<ResponseEntity<String>> results = runConcurrently(
                () -> post("/api/experiments/EXP-CK3/participants/P1/kit-pickup",
                        headers("c1", "COORDINATOR", "ck-pick-31"), null),
                () -> post("/api/experiments/EXP-CK3/close",
                        headers("c2", "COORDINATOR", "ck-close-31"), null));
        int pickupStatus = results.get(0).getStatusCode().value();
        assertEquals(200, results.get(1).getStatusCode().value(), "关闭始终生效");
        Integer pickups = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'EXP-CK3'", Integer.class);
        if (pickupStatus == 201) {
            assertEquals(1, pickups);
        } else {
            assertEquals(409, pickupStatus);
            assertEquals(0, pickups);
        }
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM experiment WHERE id = 'EXP-CK3'", String.class));
    }
}