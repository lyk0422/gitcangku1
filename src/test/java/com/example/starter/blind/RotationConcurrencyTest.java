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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轮换幂等与并发边界（真实多线程，协调并发而非睡眠）：
 * 同参（名册换序）重放首次快照；异参 409；失败不占键；
 * 两轮换并发恰好一次成功，不存在两代同时有效；轮换与数据提交并发时旧/新代次按提交顺序归属。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RotationConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private static String roster(String collectors, String custodians, String reviewers) {
        return "{\"dataCollectors\":[" + collectors + "],\"randomizationCustodians\":["
                + custodians + "],\"safetyReviewers\":[" + reviewers + "]}";
    }

    private String body(long version, String rosterJson) {
        return "{\"expectedExperimentVersion\":" + version
                + ",\"effectiveAt\":1700000000000,\"roster\":" + rosterJson + "}";
    }

    private int activate(String expId, String key, String reqId, long version, String rosterJson) {
        return rest.exchange(
                "/api/experiments/" + expId + "/rotations/" + key + "/activate", HttpMethod.POST,
                new HttpEntity<>(body(version, rosterJson),
                        headers("lead", "COORDINATOR", reqId)), String.class)
                .getStatusCode().value();
    }

    private void bootstrap(String expId) {
        assertEquals(201, rest.exchange("/api/experiments/" + expId, HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":2}",
                        headers("lead", "COORDINATOR", expId + "-create")), String.class)
                .getStatusCode().value());
        assertEquals(201, rest.exchange(
                "/api/experiments/" + expId + "/participants/PA/allocations", HttpMethod.POST,
                new HttpEntity<>(null, headers("lead", "COORDINATOR", expId + "-pa")),
                String.class).getStatusCode().value());
    }

    @Test
    void replaySameRequestId_rosterReordered_returnsFirstSnapshot() {
        bootstrap("RC-1");
        String ordered = roster("\"d1\",\"d2\"", "\"c1\"", "\"s1\"");
        String reordered = roster("\"d2\",\"d1\"", "\"c1\"", "\"s1\"");

        ResponseEntity<String> first = rest.exchange(
                "/api/experiments/RC-1/rotations/RK1/activate", HttpMethod.POST,
                new HttpEntity<>(body(0, ordered), headers("lead", "COORDINATOR", "idem-rc1")),
                String.class);
        assertEquals(200, first.getStatusCode().value());

        // 同 requestId、名册换序：同参，回放首次快照
        ResponseEntity<String> replay = rest.exchange(
                "/api/experiments/RC-1/rotations/RK1/activate", HttpMethod.POST,
                new HttpEntity<>(body(0, reordered), headers("lead", "COORDINATOR", "idem-rc1")),
                String.class);
        assertEquals(200, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());

        // 仅一个代次、一张轮换单、版本1
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_generation WHERE experiment_id = 'RC-1'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rotation_order WHERE experiment_id = 'RC-1'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT role_version FROM experiment WHERE id = 'RC-1'", Integer.class));
        // 名册仍含两人
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_assignment ra JOIN access_generation g ON g.id = ra.generation_id "
                        + "WHERE g.experiment_id = 'RC-1' AND ra.role_name = 'DATA_COLLECTOR'",
                Integer.class));
    }

    @Test
    void sameRequestId_differentParams_conflicts() {
        bootstrap("RC-2");
        assertEquals(200, activate("RC-2", "RK2", "idem-rc2", 0, roster("\"d1\"", "\"c1\"", "\"s1\"")));
        // 同 requestId、不同目标名册：异参 409
        assertEquals(409, activate("RC-2", "RK2", "idem-rc2", 1, roster("\"d9\"", "\"c9\"", "\"s9\"")));
        // 同 requestId、不同 expectedVersion（名册相同）：异参 409
        assertEquals(409, activate("RC-2", "RK2", "idem-rc2", 1, roster("\"d1\"", "\"c1\"", "\"s1\"")));
        // 同 requestId、不同 rotationKey（路径）：operation 指纹含 key，异参 409
        assertEquals(409, rest.exchange(
                "/api/experiments/RC-2/rotations/OTHER/activate", HttpMethod.POST,
                new HttpEntity<>(body(1, roster("\"d1\"", "\"c1\"", "\"s1\"")),
                        headers("lead", "COORDINATOR", "idem-rc2")), String.class)
                .getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rotation_order WHERE experiment_id = 'RC-2'",
                Integer.class));
    }

    @Test
    void failedActivation_doesNotConsumeRequestId() {
        bootstrap("RC-3");
        // 版本错误失败 409，不占键
        assertEquals(409, activate("RC-3", "RK3", "idem-rc3", 1, roster("\"d1\"", "\"c1\"", "\"s1\"")));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'idem-rc3'",
                Integer.class));
        // 同 requestId 改正确参数成功，证明失败未占键
        assertEquals(200, activate("RC-3", "RK3", "idem-rc3", 0, roster("\"d1\"", "\"c1\"", "\"s1\"")));
    }

    @Test
    void concurrentRotations_onlyOneSucceeds_noTwoActiveGenerations() throws Exception {
        bootstrap("RC-4");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(threads);
        AtomicInteger counter = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    ready.await(5, TimeUnit.SECONDS);
                    String actor = "d" + idx;
                    return activate("RC-4", "RK4-" + idx, "conc-rc4-" + idx, 0,
                            roster("\"" + actor + "\"", "\"c\"", "\"s\""));
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            long ok = statuses.stream().filter(s -> s == 200).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, ok, "并发轮换仅一次成功");
            assertEquals(threads - 1L, conflict, "其余因版本已变 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终仅一个 ACTIVE 代次、版本1、无 SUPERSEDED（败者全部回滚）
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_generation WHERE experiment_id = 'RC-4' "
                        + "AND status = 'ACTIVE'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_generation WHERE experiment_id = 'RC-4' "
                        + "AND status = 'SUPERSEDED'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT role_version FROM experiment WHERE id = 'RC-4'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rotation_order WHERE experiment_id = 'RC-4'",
                Integer.class));
        // 败者不得残留名册/范围
        Integer generations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_generation WHERE experiment_id = 'RC-4'",
                Integer.class);
        assertEquals(1, generations);
    }

    @Test
    void rotationVsDataSubmission_concurrent_attributionIsConsistent() throws Exception {
        bootstrap("RC-5");
        // 第一代：d1 采集
        assertEquals(200, activate("RC-5", "RK5-A", "rc5-a", 0,
                roster("\"d1\"", "\"c1\"", "\"s1\"")));
        long gen1 = jdbc.queryForObject(
                "SELECT id FROM access_generation WHERE experiment_id = 'RC-5' AND status = 'ACTIVE'",
                Long.class);

        // d1 签发第一代令牌
        ResponseEntity<String> tokenResp = rest.exchange(
                "/api/experiments/RC-5/access-tokens", HttpMethod.POST,
                new HttpEntity<>(null, headers("d1", "DATA_COLLECTOR", null)), String.class);
        String oldToken = tokenResp.getBody() == null ? null
                : new com.fasterxml.jackson.databind.ObjectMapper().readTree(tokenResp.getBody())
                .path("tokenId").asText();

        // 第二代：d2 采集
        assertEquals(200, activate("RC-5", "RK5-B", "rc5-b", 1,
                roster("\"d2\"", "\"c1\"", "\"s1\"")));
        long gen2 = jdbc.queryForObject(
                "SELECT id FROM access_generation WHERE experiment_id = 'RC-5' AND status = 'ACTIVE'",
                Long.class);

        // 旧令牌在新代次下并发提交：必须全部 409，不得写入归属代次1 的新数据
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    HttpHeaders h = headers("d1", "DATA_COLLECTOR", null);
                    h.set("X-Access-Token", oldToken);
                    return rest.exchange(
                            "/api/experiments/RC-5/participants/PA/data", HttpMethod.POST,
                            new HttpEntity<>("{\"payload\":\"late\"}", h), String.class)
                            .getStatusCode().value();
                }));
            }
            Set<Integer> statusSet = new HashSet<>();
            for (Future<Integer> f : futures) {
                statusSet.add(f.get(30, TimeUnit.SECONDS));
            }
            assertEquals(Set.of(409), statusSet, "旧代次令牌在轮换后并发提交必须全部拒绝");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 不存在两代同时有效；gen1 已 SUPERSEDED、gen2 ACTIVE
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT status FROM access_generation WHERE id = ?", String.class, gen1));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM access_generation WHERE id = ?", String.class, gen2));
        // 无旧代次延迟写入
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM data_submission WHERE generation_id = ?",
                Integer.class, gen1));
    }
}
