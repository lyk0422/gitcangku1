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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 泄露传播、审核门禁与隔离确认的并发边界（真实多线程 + 真实 H2，行级锁串行化）：
 * 并发披露边唯一且版本连续；同 requestId 并发恰好一次业务执行；
 * 被污染审核人与干净审核人并发批准只有干净者成功；隔离确认与新披露并发后状态一致。
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

    private int postStatus(String path, String actor, String role, String requestId, String body) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers(actor, role, requestId)), String.class)
                .getStatusCode().value();
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertNotNull(response.getBody());
        return mapper.readTree(response.getBody());
    }

    private void seedApproved(String expId, String pid, int blockCount, String... moreParticipants) {
        assertEquals(201, rest.exchange("/api/experiments/" + expId, HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":" + blockCount + "}",
                        headers("coord-1", "COORDINATOR", expId.toLowerCase() + "-create")),
                String.class).getStatusCode().value());
        assertEquals(201, rest.exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST, new HttpEntity<>(null,
                        headers("coord-1", "COORDINATOR", expId.toLowerCase() + "-alloc")),
                String.class).getStatusCode().value());
        for (String extra : moreParticipants) {
            assertEquals(201, rest.exchange(
                    "/api/experiments/" + expId + "/participants/" + extra + "/allocations",
                    HttpMethod.POST, new HttpEntity<>(null,
                            headers("coord-1", "COORDINATOR", expId.toLowerCase() + "-alloc-" + extra)),
                    String.class).getStatusCode().value());
        }
        ResponseEntity<String> apply = rest.exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/unblind-requests",
                HttpMethod.POST, new HttpEntity<>("{\"reason\":\"x\"}",
                        headers("coord-1", "COORDINATOR", expId.toLowerCase() + "-apply")),
                String.class);
        assertEquals(201, apply.getStatusCode().value());
        String ubId;
        try {
            ubId = json(apply).path("requestId").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals(200, rest.exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, new HttpEntity<>(null,
                        headers("rev-2", "REVIEWER", expId.toLowerCase() + "-approve")),
                String.class).getStatusCode().value());
    }

    @Test
    void concurrentDisclosures_overlappingReceivers_edgesUniqueAndVersionsContiguous()
            throws Exception {
        seedApproved("CC-1", "PA", 4);
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    // 每名接收人 op-i 唯一，另所有人都披露共享接收人 op-shared（仅一条边能新增）
                    return postStatus("/api/experiments/CC-1/disclosures",
                            "coord-1", "COORDINATOR", "cc1-disc-" + idx,
                            "{\"exposureKey\":\"EK-CC1-" + idx + "\","
                                    + "\"receiverActors\":[\"op-" + idx + "\",\"op-shared\"],"
                                    + "\"participantIds\":[\"PA\"]}");
                }));
            }
            Set<Integer> statuses = new HashSet<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(Set.of(201), statuses, "全部披露应成功");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 种子 1 条 + 8 个 op-i + 1 个 op-shared = 10 条边，无重复
        assertEquals(10, jdbc.queryForObject(
                "SELECT COUNT(*) FROM contamination_edge WHERE experiment_id='CC-1' "
                        + "AND participant_id='PA'", Integer.class));
        assertEquals(10, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT actor_id) FROM contamination_edge "
                        + "WHERE experiment_id='CC-1' AND participant_id='PA'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM contamination_edge WHERE experiment_id='CC-1' "
                        + "AND participant_id='PA' AND actor_id='op-shared'", Integer.class));

        // 版本：种子 v1 + 8 笔披露各追加一个版本 = 9，版本号 1..9 连续无缺号、全部 OPEN
        List<Integer> versions = jdbc.queryForList(
                "SELECT version FROM contamination_version WHERE experiment_id='CC-1' "
                        + "AND participant_id='PA' ORDER BY version", Integer.class);
        assertEquals(9, versions.size());
        for (int v = 1; v <= 9; v++) {
            assertEquals(v, versions.get(v - 1), "版本号必须连续");
        }
        Integer closed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contamination_version WHERE experiment_id='CC-1' "
                        + "AND participant_id='PA' AND status='CLOSED'", Integer.class);
        assertEquals(0, closed);

        // 当前闭包为全集
        ResponseEntity<String> closure = rest.exchange(
                "/api/experiments/CC-1/participants/PA/contamination",
                HttpMethod.GET, new HttpEntity<>(null, headers("coord-1", "COORDINATOR", null)),
                String.class);
        assertEquals(200, closure.getStatusCode().value());
        JsonNode actors = json(closure).path("actors");
        assertEquals(10, actors.size());
    }

    @Test
    void sameRequestIdConcurrentDisclosure_exactlyOneExecutionAllReplaySameBody()
            throws Exception {
        seedApproved("CC-2", "PA", 2);
        int threads = 6;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return rest.exchange("/api/experiments/CC-2/disclosures", HttpMethod.POST,
                            new HttpEntity<>(
                                    "{\"exposureKey\":\"EK-CC2\",\"receiverActors\":[\"op-3\"],"
                                            + "\"participantIds\":[\"PA\"]}",
                                    headers("coord-1", "COORDINATOR", "cc2-shared-key")),
                            String.class);
                }));
            }
            Set<String> bodies = new HashSet<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
                assertEquals(201, response.getStatusCode().value(), "同键同参并发均应回放成功");
                bodies.add(response.getBody());
            }
            assertEquals(1, bodies.size(), "回放响应体必须一致");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_event WHERE exposure_key='EK-CC2'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM contamination_edge WHERE experiment_id='CC-2' "
                        + "AND participant_id='PA' AND actor_id='op-3'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id='cc2-shared-key'",
                Integer.class));
    }

    @Test
    void concurrentApproval_contaminatedAndCleanReviewer_onlyCleanSucceeds() throws Exception {
        seedApproved("CC-3", "PA", 2);
        // coord-1 披露给 rev-2，使其进入闭包
        assertEquals(201, postStatus("/api/experiments/CC-3/disclosures",
                "coord-1", "COORDINATOR", "cc3-disc",
                "{\"exposureKey\":\"EK-CC3\",\"receiverActors\":[\"rev-2\"],"
                        + "\"participantIds\":[\"PA\"]}"));
        // 第二次揭盲申请（同一参与者），保持 PENDING
        ResponseEntity<String> apply = rest.exchange(
                "/api/experiments/CC-3/participants/PA/unblind-requests",
                HttpMethod.POST, new HttpEntity<>("{\"reason\":\"二次\"}",
                        headers("coord-1", "COORDINATOR", "cc3-apply-2")), String.class);
        assertEquals(201, apply.getStatusCode().value());
        String ub2 = json(apply).path("requestId").asText();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                // 已污染审核人：必不成功（门禁 403，或干净者先批准后的 409）
                return postStatus("/api/unblind-requests/" + ub2 + "/approval",
                        "rev-2", "REVIEWER", "cc3-approval-contaminated", null);
            }));
            futures.add(pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                // 干净审核人
                return postStatus("/api/unblind-requests/" + ub2 + "/approval",
                        "rev-9", "REVIEWER", "cc3-approval-clean", null);
            }));
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long success = statuses.stream().filter(s -> s == 200).count();
            assertEquals(1, success, "恰好一名审核人批准成功");
            assertTrue(statuses.contains(200));
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 终态确定：APPROVED 且批准人必须是干净的 rev-9，污染者 rev-2 绝不可能落库
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id=?", String.class, ub2));
        assertEquals("rev-9", jdbc.queryForObject(
                "SELECT reviewer_actor FROM unblind_request WHERE id=?", String.class, ub2));
    }

    @Test
    void concurrentQuarantineConfirmAndNewDisclosure_finalStateConsistent() throws Exception {
        seedApproved("CC-4", "PA", 2);
        assertEquals(201, postStatus("/api/experiments/CC-4/disclosures",
                "coord-1", "COORDINATOR", "cc4-disc-1",
                "{\"exposureKey\":\"EK-CC4-1\",\"receiverActors\":[\"op-3\"],"
                        + "\"participantIds\":[\"PA\"]}"));

        // 发起隔离单，冻结闭包 v2
        ResponseEntity<String> created = rest.exchange(
                "/api/experiments/CC-4/participants/PA/quarantine-orders",
                HttpMethod.POST, new HttpEntity<>(
                        "{\"version\":2,\"actors\":[\"coord-1\",\"op-3\"]}",
                        headers("comp-1", "COMPLIANCE", "cc4-qo-create")), String.class);
        assertEquals(201, created.getStatusCode().value());
        String orderId = json(created).path("orderId").asText();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                return postStatus("/api/quarantine-orders/" + orderId + "/confirmation",
                        "comp-2", "COMPLIANCE", "cc4-qo-confirm", null);
            }));
            futures.add(pool.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                return postStatus("/api/experiments/CC-4/disclosures",
                        "coord-1", "COORDINATOR", "cc4-disc-2",
                        "{\"exposureKey\":\"EK-CC4-2\",\"receiverActors\":[\"op-7\"],"
                                + "\"participantIds\":[\"PA\"]}");
            }));
            for (Future<Integer> future : futures) {
                // 无论谁先提交，确认 200、披露 201 都应成立
                Integer status = future.get(30, TimeUnit.SECONDS);
                assertTrue(status == 200 || status == 201, "并发两笔均应成功，实际: " + status);
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 终态与提交顺序无关：隔离单 CLOSED，v2 CLOSED，新增边生成重新 OPEN 的最新版本
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM quarantine_order WHERE id=?", String.class, orderId));
        assertEquals("comp-2", jdbc.queryForObject(
                "SELECT confirmer_actor FROM quarantine_order WHERE id=?", String.class, orderId));
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM contamination_version WHERE experiment_id='CC-4' "
                        + "AND participant_id='PA' AND version=2", String.class));
        List<Integer> openLatest = jdbc.queryForList(
                "SELECT version FROM contamination_version WHERE experiment_id='CC-4' "
                        + "AND participant_id='PA' ORDER BY version DESC LIMIT 1", Integer.class);
        assertEquals(List.of(3), openLatest);
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT status FROM contamination_version WHERE experiment_id='CC-4' "
                        + "AND participant_id='PA' AND version=3", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM contamination_edge WHERE experiment_id='CC-4' "
                        + "AND participant_id='PA' AND actor_id='op-7'", Integer.class));
        // 冻结快照不被新披露篡改
        assertEquals("[\"coord-1\",\"op-3\"]", jdbc.queryForObject(
                "SELECT actors FROM contamination_version WHERE experiment_id='CC-4' "
                        + "AND participant_id='PA' AND version=2", String.class));
    }
}
