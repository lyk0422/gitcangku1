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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轮换并发边界（真实多线程与真实 H2 行锁，不用睡眠代替断言）：
 * 并发轮换仅一单激活；轮换与数据提交、实验关闭、受控揭盲并发时
 * 任意时刻至多一个活动代次，数据写入按事务提交顺序归属旧或新代次。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RotationConcurrencyTest extends AbstractBlindIntegrationTest {

    private static final long T0 = 1_700_000_000_000L;

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

    private ResponseEntity<String> exchange(String path, HttpMethod method,
                                            HttpHeaders headers, String body) {
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private void createExperiment(String expId, String requestId) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("pi1", "COORDINATOR", requestId), "{\"blockCount\":2}")
                .getStatusCode().value());
    }

    private void register(String expId, String participantId, String requestId) {
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/" + participantId + "/allocations",
                HttpMethod.POST, headers("pi1", "COORDINATOR", requestId), null)
                .getStatusCode().value());
    }

    private String rotationBody(String rotationKey, long expectedVersion,
                                List<String> collectors) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rotationKey", rotationKey);
        body.put("expectedExperimentVersion", expectedVersion);
        body.put("effectiveAt", T0);
        body.put("dataCollectors", collectors);
        body.put("randomizationCustodians", List.of("rc1"));
        body.put("safetyReviewers", List.of("sr1"));
        return mapper.writeValueAsString(body);
    }

    private int activeGenerationCount(String expId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM access_generation "
                + "WHERE experiment_id = '" + expId + "' AND status = 'ACTIVE'", Integer.class);
    }

    @Test
    void concurrentRotations_exactlyOneActivates_singleActiveGeneration() throws Exception {
        createExperiment("EXP-C1", "c1-create");
        register("EXP-C1", "P1", "c1-reg-1");
        // 当前版本 2；6 个并发轮换单携带相同期望版本
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int n = i;
                futures.add(pool.submit(() -> exchange("/api/experiments/EXP-C1/role-rotations",
                        HttpMethod.POST, headers("pi1", "COORDINATOR", "c1-rot-" + n),
                        rotationBody("RK-C1-" + n, 2, List.of("dc" + n)))
                        .getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long created = statuses.stream().filter(s -> s == 201).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, created, "并发轮换仅一单激活");
            assertEquals(threads - 1L, conflict, "其余因版本变化整单 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        // 任意时刻至多一个活动代次；恰好一单落库
        assertEquals(1, activeGenerationCount("EXP-C1"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM role_rotation "
                + "WHERE experiment_id = 'EXP-C1'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM access_generation "
                + "WHERE experiment_id = 'EXP-C1'", Integer.class));
    }

    @Test
    void concurrentRotationAndDataSubmission_attributionByCommitOrder_noOverlapGeneration()
            throws Exception {
        createExperiment("EXP-C2", "c2-create");
        register("EXP-C2", "P1", "c2-reg-1");
        // 先激活代次 2，dc1 为采集人，立即生效
        assertEquals(201, exchange("/api/experiments/EXP-C2/role-rotations", HttpMethod.POST,
                headers("pi1", "COORDINATOR", "c2-rot-0"),
                rotationBody("RK-C2-0", 2, List.of("dc1"))).getStatusCode().value());
        // 当前版本 3；并发：一次轮换（dc2 接替）+ 八个 dc1 持代次 2 令牌提交
        String submitBody = mapper.writeValueAsString(
                Map.of("accessGeneration", 2, "payload", "obs"));
        ExecutorService pool = Executors.newFixedThreadPool(9);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(() -> exchange("/api/experiments/EXP-C2/role-rotations",
                    HttpMethod.POST, headers("pi1", "COORDINATOR", "c2-rot-1"),
                    rotationBody("RK-C2-1", 3, List.of("dc2")))));
            for (int i = 0; i < 8; i++) {
                final int n = i;
                futures.add(pool.submit(() -> exchange(
                        "/api/experiments/EXP-C2/participants/P1/data", HttpMethod.POST,
                        headers("dc1", "REVIEWER", "c2-sub-" + n), submitBody)));
            }
            int rotationStatus = futures.get(0).get(30, TimeUnit.SECONDS).getStatusCode().value();
            assertEquals(201, rotationStatus, "轮换应激活成功");
            int attributedOld = 0;
            int rejected = 0;
            for (int i = 1; i < futures.size(); i++) {
                ResponseEntity<String> resp = futures.get(i).get(30, TimeUnit.SECONDS);
                if (resp.getStatusCode().value() == 201) {
                    JsonNode view = mapper.readTree(resp.getBody());
                    assertEquals(2, view.path("generationNo").asInt(),
                            "提交只可能归属提交时活动的旧代次 2");
                    attributedOld++;
                } else {
                    assertEquals(409, resp.getStatusCode().value(),
                            "轮换提交后旧代次令牌必须拒绝");
                    rejected++;
                }
            }
            assertEquals(8, attributedOld + rejected, "每个提交要么归属旧代次要么被拒");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        // 终态：恰好一个活动代次（新代次 3），数据行归属与响应一致
        assertEquals(1, activeGenerationCount("EXP-C2"));
        assertEquals(3, jdbc.queryForObject("SELECT generation_no FROM access_generation "
                + "WHERE experiment_id = 'EXP-C2' AND status = 'ACTIVE'", Integer.class));
        int rows = jdbc.queryForObject("SELECT COUNT(*) FROM subject_data "
                + "WHERE experiment_id = 'EXP-C2'", Integer.class);
        assertEquals(rows, jdbc.queryForObject("SELECT COUNT(*) FROM subject_data "
                + "WHERE experiment_id = 'EXP-C2' AND generation_no = 2", Integer.class));
    }

    @Test
    void concurrentRotationAndClose_neverTwoActiveGenerations() throws Exception {
        createExperiment("EXP-C3", "c3-create");
        register("EXP-C3", "P1", "c3-reg-1");
        // 并发：轮换（期望版本 2）与关闭
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(() -> exchange("/api/experiments/EXP-C3/role-rotations",
                    HttpMethod.POST, headers("pi1", "COORDINATOR", "c3-rot"),
                    rotationBody("RK-C3", 2, List.of("dc1"))).getStatusCode().value()));
            futures.add(pool.submit(() -> exchange("/api/experiments/EXP-C3/close",
                    HttpMethod.POST, headers("pi1", "COORDINATOR", "c3-close"), null)
                    .getStatusCode().value()));
            int rotationStatus = futures.get(0).get(30, TimeUnit.SECONDS);
            int closeStatus = futures.get(1).get(30, TimeUnit.SECONDS);
            assertEquals(200, closeStatus, "关闭最终成功（先或后于轮换）");
            assertTrue(rotationStatus == 201 || rotationStatus == 409,
                    "轮换要么先于关闭激活，要么因实验已关闭 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        // 无论谁先提交：实验已关闭，且至多一个活动代次
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM experiment WHERE id = 'EXP-C3'", String.class));
        assertEquals(1, activeGenerationCount("EXP-C3"));
    }

    @Test
    void concurrentRotationAndUnblindApprove_serializedByExperimentLock() throws Exception {
        createExperiment("EXP-C4", "c4-create");
        register("EXP-C4", "P1", "c4-reg-1");
        // 先提出揭盲申请（申请人 c1）
        ResponseEntity<String> apply = exchange(
                "/api/experiments/EXP-C4/participants/P1/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "c4-ub-apply"), "{\"reason\":\"安全性核查\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = mapper.readTree(apply.getBody()).path("requestId").asText();
        // 并发：批准揭盲（版本 +1）与轮换（期望版本 2）
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();
        int approveStatus;
        int rotationStatus;
        try {
            futures.add(pool.submit(() -> exchange(
                    "/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                    headers("rev1", "REVIEWER", "c4-ub-approve"), null)
                    .getStatusCode().value()));
            futures.add(pool.submit(() -> exchange("/api/experiments/EXP-C4/role-rotations",
                    HttpMethod.POST, headers("pi1", "COORDINATOR", "c4-rot"),
                    rotationBody("RK-C4", 2, List.of("dc1"))).getStatusCode().value()));
            approveStatus = futures.get(0).get(30, TimeUnit.SECONDS);
            rotationStatus = futures.get(1).get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(200, approveStatus, "揭盲批准最终成功");
        assertTrue(rotationStatus == 201 || rotationStatus == 409,
                "轮换要么先于批准激活，要么因版本变化 409");
        // 终态一致：恰好一个活动代次；若轮换成功则版本为 4（批准+轮换各+1）
        assertEquals(1, activeGenerationCount("EXP-C4"));
        int expectedVersion = rotationStatus == 201 ? 4 : 3;
        assertEquals(expectedVersion, jdbc.queryForObject(
                "SELECT version FROM experiment WHERE id = 'EXP-C4'", Integer.class));
    }
}
