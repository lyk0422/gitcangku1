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
 * 泄露传播、审核门禁与隔离确认的并发边界（真实多线程 + H2 行锁，不用睡眠代替断言）：
 * 并发披露按提交顺序串行、边去重不覆盖；同 requestId 并发恰好一次业务执行；
 * 披露与隔离确认并发时冻结快照不可变、新版本必重新 OPEN；隔离发起唯一占位。
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

    private int post(String path, String actor, String role, String requestId, String body)
            throws Exception {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(actor, role, requestId)),
                String.class).getStatusCode().value();
    }

    private JsonNode postEntity(String path, String actor, String role, String requestId, String body)
            throws Exception {
        ResponseEntity<String> resp = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers(actor, role, requestId)), String.class);
        return mapper.readTree(resp.getBody());
    }

    private JsonNode getEntity(String path, String actor, String role) throws Exception {
        ResponseEntity<String> resp = rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers(actor, role, null)), String.class);
        return mapper.readTree(resp.getBody());
    }

    /** 建实验、登记参与者、批准揭盲，返回 exposureKey。 */
    private String setupApproved(String expId, String applicant, String reviewer, String prefix)
            throws Exception {
        assertEquals(201, post("/api/experiments/" + expId, applicant, "COORDINATOR",
                prefix + "-create", "{\"blockCount\":2}"));
        assertEquals(201, post("/api/experiments/" + expId + "/participants/PA/allocations",
                applicant, "COORDINATOR", prefix + "-alloc", null));
        JsonNode apply = postEntity(
                "/api/experiments/" + expId + "/participants/PA/unblind-requests",
                applicant, "COORDINATOR", prefix + "-apply", "{\"reason\":\"并发测试\"}");
        String ubId = apply.path("requestId").asText();
        assertEquals(200, post("/api/unblind-requests/" + ubId + "/approval",
                reviewer, "REVIEWER", prefix + "-approve", null));
        JsonNode result = getEntity("/api/unblind-requests/" + ubId + "/result",
                applicant, "COORDINATOR");
        return result.path("exposureKey").asText();
    }

    private long subjectId(String expId) {
        return jdbc.queryForObject(
                "SELECT id FROM contamination_subject WHERE experiment_id = ? AND participant_id = 'PA'",
                Long.class, expId);
    }

    @Test
    void concurrentDirectDisclosures_distinctRecipients_serializedWithDedupedEdges()
            throws Exception {
        String key = setupApproved("CC-1", "coord-1", "rev-2", "cc1");
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final String recipient = "op" + i;
                final String body = "{\"exposureKey\":\"" + key + "\",\"recipients\":[\"" + recipient + "\"]}";
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return rest.exchange("/api/exposures", HttpMethod.POST,
                            new HttpEntity<>(body, headers("coord-1", "COORDINATOR",
                                    "cc1-expose-" + recipient)), String.class)
                            .getStatusCode().value();
                }));
            }
            for (Future<Integer> future : futures) {
                assertEquals(201, future.get(30, TimeUnit.SECONDS),
                        "主体行锁串行化后每条新披露都应成功");
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 1 条种子边 + 8 条互不相同的直接披露边，全部保留不覆盖。
        assertEquals(9, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_edge WHERE subject_id = ?", Integer.class,
                subjectId("CC-1")));
        List<String> closure = jdbc.queryForList(
                "SELECT DISTINCT target_actor FROM exposure_edge WHERE subject_id = ? ORDER BY 1",
                String.class, subjectId("CC-1"));
        assertEquals(9, closure.size());
        // 并发后仍只有一个开放版本，闭包完整。
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM contamination_version WHERE subject_id = ? AND status = 'OPEN'",
                Integer.class, subjectId("CC-1")));
    }

    @Test
    void sameRequestIdConcurrent_sameExposureParams_exactlyOneEdgeAllReplaySameResult()
            throws Exception {
        String key = setupApproved("CC-2", "coord-1", "rev-2", "cc2");
        int threads = 6;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String body = "{\"exposureKey\":\"" + key + "\",\"recipients\":[\"op1\",\"op2\"]}";
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<String> responses = new HashSet<>();
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    ResponseEntity<String> resp = rest.exchange("/api/exposures", HttpMethod.POST,
                            new HttpEntity<>(body, headers("coord-1", "COORDINATOR", "cc2-same-key")),
                            String.class);
                    synchronized (responses) {
                        responses.add(resp.getBody());
                    }
                    return resp.getStatusCode().value();
                }));
            }
            for (Future<Integer> future : futures) {
                assertEquals(201, future.get(30, TimeUnit.SECONDS), "同键同参并发均应回放成功");
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, responses.size(), "所有回放响应体必须一致");
        // 仅 1 条种子边 + 2 条直接披露边，幂等并发不得重复加边。
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_edge WHERE subject_id = ?", Integer.class,
                subjectId("CC-2")));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'cc2-same-key'",
                Integer.class));
    }

    @Test
    void disclosureAndQuarantineConfirm_concurrent_snapshotImmutableAndNewVersionReopened()
            throws Exception {
        String key = setupApproved("CC-3", "coord-1", "rev-2", "cc3");
        // 先让 op1 获知，闭包 [coord-1, op1]。
        assertEquals(201, post("/api/exposures", "coord-1", "COORDINATOR", "cc3-to-op1",
                "{\"exposureKey\":\"" + key + "\",\"recipients\":[\"op1\"]}"));
        // c1 发起隔离，待确认快照锁定 v1=[coord-1,op1]。
        JsonNode qo = postEntity("/api/experiments/CC-3/participants/PA/quarantine-orders",
                "c1", "COMPLIANCE", "cc3-qo", null);
        String orderId = qo.path("orderId").asText();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // T1：op1 向下游 op2 披露；T2：c2 确认隔离。两种提交顺序都必须安全。
            Future<Integer> disclosure = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return rest.exchange("/api/experiments/CC-3/participants/PA/exposures",
                        HttpMethod.POST, new HttpEntity<>("{\"recipients\":[\"op2\"]}",
                                headers("op1", "COORDINATOR", "cc3-down")), String.class)
                        .getStatusCode().value();
            });
            Future<Integer> confirm = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return rest.exchange("/api/quarantine-orders/" + orderId + "/confirmation",
                        HttpMethod.POST, new HttpEntity<>(null,
                                headers("c2", "COMPLIANCE", "cc3-confirm")), String.class)
                        .getStatusCode().value();
            });
            assertEquals(201, disclosure.get(30, TimeUnit.SECONDS));
            assertEquals(200, confirm.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        long sid = subjectId("CC-3");
        // 无论谁先提交：隔离单已确认；v1 冻结快照恒为发起时闭包，不可被新边覆盖。
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT status FROM quarantine_order WHERE id = ?", String.class, orderId));
        String frozenClosure = jdbc.queryForObject(
                "SELECT closure FROM contamination_version WHERE subject_id = ? AND version_no = 1",
                String.class, sid);
        assertEquals(List.of("coord-1", "op1"), mapper.readValue(frozenClosure, List.class));
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM contamination_version WHERE subject_id = ? AND version_no = 1",
                String.class, sid));
        // 新披露必然落入一个重新 OPEN 的新版本（v1 已被待确认/已确认隔离占为快照）。
        assertEquals(2, jdbc.queryForObject(
                "SELECT current_version FROM contamination_subject WHERE id = ?", Integer.class, sid));
        JsonNode openVersion = getEntity(
                "/api/experiments/CC-3/participants/PA/contamination",
                "c1", "COMPLIANCE");
        assertEquals(2, openVersion.path("version").asInt());
        assertEquals("OPEN", openVersion.path("versionStatus").asText());
        assertEquals(List.of("coord-1", "op1", "op2"),
                mapper.convertValue(openVersion.path("closure"),
                        mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        // 边不删除：种子 + op1 + op2 共 3 条。
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_edge WHERE subject_id = ?", Integer.class, sid));
    }

    @Test
    void concurrentQuarantineInitiation_onlyOneOpenOrder() throws Exception {
        setupApproved("CC-4", "coord-1", "rev-2", "cc4");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (String initiator : List.of("c1", "c2")) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return rest.exchange(
                            "/api/experiments/CC-4/participants/PA/quarantine-orders",
                            HttpMethod.POST, new HttpEntity<>(null,
                                    headers(initiator, "COMPLIANCE", "cc4-qo-" + initiator)),
                            String.class).getStatusCode().value();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long created = statuses.stream().filter(s -> s == 201).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, created, "同一主体至多一个待确认隔离单");
            assertEquals(1, conflict, "并发发起的另一方必须 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM quarantine_order WHERE subject_id = ? AND status = 'OPEN'",
                Integer.class, subjectId("CC-4")));
    }

    @Test
    void concurrentDisclosureAndContaminatedApproval_serializedNoCorruption() throws Exception {
        String key = setupApproved("CC-5", "coord-1", "rev-2", "cc5");
        // 第二次揭盲申请（同一参与者首次批准后允许再申请）。
        JsonNode secondApply = postEntity(
                "/api/experiments/CC-5/participants/PA/unblind-requests",
                "coord-1", "COORDINATOR", "cc5-apply2", "{\"reason\":\"二次揭盲\"}");
        String ub2 = secondApply.path("requestId").asText();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // T1：让审核人 rev-dirty 先进入闭包；T2：rev-dirty 尝试批准。
            // 两种提交顺序都合法：披露先提交则批准必须 403；批准先提交则成功。
            Future<Integer> dirtyDisclosure = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return rest.exchange("/api/exposures", HttpMethod.POST,
                        new HttpEntity<>("{\"exposureKey\":\"" + key + "\",\"recipients\":[\"rev-dirty\"]}",
                                headers("coord-1", "COORDINATOR", "cc5-dirty")), String.class)
                        .getStatusCode().value();
            });
            Future<Integer> approval = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return rest.exchange("/api/unblind-requests/" + ub2 + "/approval",
                        HttpMethod.POST, new HttpEntity<>(null,
                                headers("rev-dirty", "REVIEWER", "cc5-approve-dirty")),
                        String.class).getStatusCode().value();
            });
            assertEquals(201, dirtyDisclosure.get(30, TimeUnit.SECONDS));
            int approvalStatus = approval.get(30, TimeUnit.SECONDS);
            assertTrue(approvalStatus == 200 || approvalStatus == 403,
                    "按提交顺序，污染审核人批准只能成功或被门禁拒绝，实际: " + approvalStatus);

            long sid = subjectId("CC-5");
            // 不变量：rev-dirty 披露边恰有一条；申请状态与批准结果自洽，不允许 403 却写成 APPROVED。
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM exposure_edge WHERE subject_id = ? AND target_actor = 'rev-dirty'",
                    Integer.class, sid));
            String dbStatus = jdbc.queryForObject(
                    "SELECT status FROM unblind_request WHERE id = ?", String.class, ub2);
            if (approvalStatus == 403) {
                assertEquals("PENDING", dbStatus, "门禁拒绝不得把申请改成 APPROVED");
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM unblind_request WHERE id = ? AND reviewer_actor = 'rev-dirty'",
                        Integer.class, ub2));
            } else {
                assertEquals("APPROVED", dbStatus);
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
