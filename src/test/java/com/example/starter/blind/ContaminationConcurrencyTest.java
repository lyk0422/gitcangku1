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
 * 泄露传播、审核门禁与隔离确认的真实并发边界（H2 行锁，多线程 + CyclicBarrier，
 * 不靠睡眠充当断言）：同参与者披露按提交顺序串行，不丢边、版本号连续唯一；
 * 同边并发只保留一条边且只生成一个版本；同 requestId 并发全部回放同结果；
 * 门禁/确认与披露竞争只允许“先通过或被拒”两种一致结局，不得 5xx 或旧闭包绕过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContaminationConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertTrue(response.getBody() != null && !response.getBody().isBlank());
        return mapper.readTree(response.getBody());
    }

    private void approvedRoot(String expId, String prefix) throws Exception {
        assertEquals(201, rest.exchange("/api/experiments/" + expId, HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":2}",
                        headers("coord-1", "COORDINATOR", prefix + "-create")), String.class)
                .getStatusCode().value());
        assertEquals(201, rest.exchange(
                "/api/experiments/" + expId + "/participants/PA/allocations",
                HttpMethod.POST, new HttpEntity<>(null,
                        headers("coord-1", "COORDINATOR", prefix + "-alloc")), String.class)
                .getStatusCode().value());
        ResponseEntity<String> apply = rest.exchange(
                "/api/experiments/" + expId + "/participants/PA/unblind-requests",
                HttpMethod.POST, new HttpEntity<>("{\"reason\":\"紧急\"}",
                        headers("coord-1", "COORDINATOR", prefix + "-apply")), String.class);
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, rest.exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, new HttpEntity<>(null,
                        headers("rev-2", "REVIEWER", prefix + "-approve")), String.class)
                .getStatusCode().value());
    }

    @Test
    void concurrentDisclosures_distinctEdges_serializedNoLostEdgesVersionsSequential()
            throws Exception {
        approvedRoot("CC-1", "cc1");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return rest.exchange(
                            "/api/experiments/CC-1/participants/PA/disclosures",
                            HttpMethod.POST, new HttpEntity<>(
                                    "{\"exposureKey\":\"CC1-EX-" + idx + "\","
                                            + "\"targetActorIds\":[\"op-" + idx + "\"]}",
                                    headers("coord-1", "COORDINATOR", "cc1-disc-" + idx)),
                            String.class).getStatusCode().value();
                }));
            }
            Set<Integer> versions = new HashSet<>();
            for (Future<Integer> future : futures) {
                assertEquals(201, future.get(30, TimeUnit.SECONDS),
                        "不同边并发登记都应成功，串行化后不得死锁或丢失");
            }
            // 响应中的版本号在提交后不易回收，这里以最终数据断言。
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(threads, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_edge WHERE experiment_id = 'CC-1'", Integer.class),
                "不得丢边");
        assertEquals(threads, jdbc.queryForObject(
                "SELECT COUNT(*) FROM closure_version WHERE experiment_id = 'CC-1'", Integer.class),
                "每次新增边提交都生成一个版本");
        List<Integer> versionNos = jdbc.queryForList(
                "SELECT version_no FROM closure_version WHERE experiment_id = 'CC-1' "
                        + "ORDER BY version_no", Integer.class);
        for (int i = 1; i <= threads; i++) {
            assertTrue(versionNos.contains(i), "版本号必须连续包含 " + i);
        }
        JsonNode closure = json(rest.exchange(
                "/api/experiments/CC-1/participants/PA/contamination/closure",
                HttpMethod.GET, new HttpEntity<>(null,
                        headers("rev-2", "REVIEWER", null)), String.class));
        assertEquals(threads, closure.path("currentVersion").asInt());
        assertEquals(threads, closure.path("edgeCount").asInt());
    }

    @Test
    void concurrentDisclosures_sameEdgeDifferentKeys_oneEdgeOneVersion() throws Exception {
        approvedRoot("CC-2", "cc2");
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return rest.exchange(
                            "/api/experiments/CC-2/participants/PA/disclosures",
                            HttpMethod.POST, new HttpEntity<>(
                                    "{\"exposureKey\":\"CC2-EX-" + idx + "\","
                                            + "\"targetActorIds\":[\"op-same\"]}",
                                    headers("coord-1", "COORDINATOR", "cc2-disc-" + idx)),
                            String.class).getStatusCode().value();
                }));
            }
            for (Future<Integer> future : futures) {
                assertEquals(201, future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        // 唯一索引兜底：同边并发只有一条边；只有真正新增边的提交生成版本。
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_edge WHERE experiment_id = 'CC-2'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM closure_version WHERE experiment_id = 'CC-2'", Integer.class));
        assertEquals(threads, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_event WHERE experiment_id = 'CC-2'", Integer.class),
                "每次登记事件都保留审计记录");
    }

    @Test
    void concurrentDisclosures_sameRequestId_allReplayExactlyOneEdge() throws Exception {
        approvedRoot("CC-3", "cc3");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return rest.exchange(
                            "/api/experiments/CC-3/participants/PA/disclosures",
                            HttpMethod.POST, new HttpEntity<>(
                                    "{\"exposureKey\":\"CC3-EX\","
                                            + "\"targetActorIds\":[\"op-a\",\"op-b\"]}",
                                    headers("coord-1", "COORDINATOR", "cc3-same-key")),
                            String.class);
                }));
            }
            Set<String> bodies = new HashSet<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
                assertEquals(201, response.getStatusCode().value(), "同键同参并发均回放成功");
                bodies.add(response.getBody());
            }
            assertEquals(1, bodies.size(), "回放响应体必须一致");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_edge WHERE experiment_id = 'CC-3'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_event WHERE experiment_id = 'CC-3'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM closure_version WHERE experiment_id = 'CC-3'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'cc3-same-key'",
                Integer.class));
    }

    @Test
    void approvalGate_racesWithDisclosure_onlyConsistentOutcomes() throws Exception {
        approvedRoot("CC-4", "cc4");
        // 第二个申请人对 PA 提出待审申请。
        ResponseEntity<String> apply2 = rest.exchange(
                "/api/experiments/CC-4/participants/PA/unblind-requests",
                HttpMethod.POST, new HttpEntity<>("{\"reason\":\"第二申请\"}",
                        headers("coord-2", "COORDINATOR", "cc4-apply2")), String.class);
        assertEquals(201, apply2.getStatusCode().value());
        String ubId2 = json(apply2).path("requestId").asText();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            // 线程 A：根节点 coord-1 把代码披露给拟审核人 rev-dirty。
            Future<Integer> disclosure = pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                return rest.exchange(
                        "/api/experiments/CC-4/participants/PA/disclosures",
                        HttpMethod.POST, new HttpEntity<>(
                                "{\"exposureKey\":\"CC4-EX\",\"targetActorIds\":[\"rev-dirty\"]}",
                                headers("coord-1", "COORDINATOR", "cc4-disc")),
                        String.class).getStatusCode().value();
            });
            // 线程 B：rev-dirty 同时尝试批准第二申请。
            Future<ResponseEntity<String>> approval = pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                return rest.exchange("/api/unblind-requests/" + ubId2 + "/approval",
                        HttpMethod.POST, new HttpEntity<>(null,
                                headers("rev-dirty", "REVIEWER", "cc4-approve-2")), String.class);
            });

            assertEquals(201, disclosure.get(30, TimeUnit.SECONDS));
            ResponseEntity<String> approvalResp = approval.get(30, TimeUnit.SECONDS);
            int approvalStatus = approvalResp.getStatusCode().value();
            assertTrue(approvalStatus == 200 || approvalStatus == 403,
                    "门禁竞争只能先通过(200)或被污染拦截(403)，实际: " + approvalStatus
                            + " body=" + approvalResp.getBody());

            // 终态必须与批准结果一致。
            String finalStatus = jdbc.queryForObject(
                    "SELECT status FROM unblind_request WHERE id = ?", String.class, ubId2);
            if (approvalStatus == 200) {
                assertEquals("APPROVED", finalStatus);
            } else {
                assertEquals("PENDING", finalStatus, "被门禁拒绝不得改动申请状态");
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void quarantineConfirm_racesWithDisclosure_confirmerCannotUseStaleClosure() throws Exception {
        approvedRoot("CC-5", "cc5");
        assertEquals(201, rest.exchange(
                "/api/experiments/CC-5/participants/PA/disclosures",
                HttpMethod.POST, new HttpEntity<>(
                        "{\"exposureKey\":\"CC5-EX-1\",\"targetActorIds\":[\"op-a\"]}",
                        headers("coord-1", "COORDINATOR", "cc5-disc1")), String.class)
                .getStatusCode().value());
        ResponseEntity<String> init = rest.exchange(
                "/api/experiments/CC-5/participants/PA/quarantine-orders",
                HttpMethod.POST, new HttpEntity<>(
                        "{\"versionNo\":1,\"actors\":[\"coord-1\",\"op-a\"]}",
                        headers("comp-1", "COMPLIANCE", "cc5-init")), String.class);
        assertEquals(201, init.getStatusCode().value());
        String orderId = json(init).path("orderId").asText();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            // 线程 A：op-a 把代码披露给拟确认人 comp-2。
            Future<Integer> disclosure = pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                return rest.exchange(
                        "/api/experiments/CC-5/participants/PA/disclosures",
                        HttpMethod.POST, new HttpEntity<>(
                                "{\"exposureKey\":\"CC5-EX-2\",\"targetActorIds\":[\"comp-2\"]}",
                                headers("op-a", "REVIEWER", "cc5-disc2")),
                        String.class).getStatusCode().value();
            });
            // 线程 B：comp-2 同时确认隔离单。
            Future<Integer> confirm = pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                return rest.exchange(
                        "/api/quarantine-orders/" + orderId + "/confirmation",
                        HttpMethod.POST, new HttpEntity<>(null,
                                headers("comp-2", "COMPLIANCE", "cc5-confirm")), String.class)
                        .getStatusCode().value();
            });

            assertEquals(201, disclosure.get(30, TimeUnit.SECONDS));
            int confirmStatus = confirm.get(30, TimeUnit.SECONDS);
            assertTrue(confirmStatus == 200 || confirmStatus == 403,
                    "确认竞争只能先通过(200)或闭包拦截(403)，实际: " + confirmStatus);

            String orderStatus = jdbc.queryForObject(
                    "SELECT status FROM quarantine_order WHERE id = ?", String.class, orderId);
            String v1Status = jdbc.queryForObject(
                    "SELECT status FROM closure_version WHERE experiment_id = 'CC-5' "
                            + "AND version_no = 1", String.class);
            if (confirmStatus == 200) {
                assertEquals("CONFIRMED", orderStatus);
                assertEquals("CLOSED", v1Status, "确认提交时 comp-2 尚不在闭包，快照应冻结");
            } else {
                assertEquals("OPEN", orderStatus);
                assertEquals("OPEN", v1Status, "被拦截不得冻结版本");
            }
            // 无论哪种结局，边都保留，且新披露生成 v2 重新 OPEN。
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM disclosure_edge WHERE experiment_id = 'CC-5'",
                    Integer.class));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM closure_version WHERE experiment_id = 'CC-5'",
                    Integer.class));
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
