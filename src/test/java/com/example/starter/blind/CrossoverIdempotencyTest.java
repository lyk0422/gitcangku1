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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 交叉阶段幂等与期望版本：
 * 同键同参重放返回最初阶段与时刻（不延长洗脱、不重新启动）；异参 409；失败不占键；
 * 权限校验先于重放；期望版本不匹配 409。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrossoverIdempotencyTest extends AbstractBlindIntegrationTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long MINUTE = 60_000L;

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

    private void createCrossoverAndRegister(String expId, String pid, String keyPrefix) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("c1", "COORDINATOR", keyPrefix + "-create"),
                "{\"blockCount\":2,\"washoutMinutes\":5}").getStatusCode().value());
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", keyPrefix + "-alloc"), null)
                .getStatusCode().value());
    }

    private ResponseEntity<String> phase(String expId, String pid, int phase, String action,
                                         String actor, String role, String requestId, String body) {
        return exchange("/api/experiments/" + expId + "/participants/" + pid
                        + "/phases/" + phase + "/" + action,
                HttpMethod.POST, headers(actor, role, requestId), body);
    }

    @Test
    void sameKeySameParams_replaysOriginalPhaseAndMoment() throws Exception {
        createCrossoverAndRegister("EXP-I1", "P1", "req-i1");

        ResponseEntity<String> first = phase("EXP-I1", "P1", 1, "start",
                "c1", "COORDINATOR", "req-i1-s1", null);
        assertEquals(200, first.getStatusCode().value());
        JsonNode firstBody = mapper.readTree(first.getBody());
        assertEquals(T0, firstBody.path("firstStartedAt").asLong());

        // 时钟前进后重放：仍返回最初阶段与时刻，不重新启动
        clock.advance(10 * MINUTE);
        ResponseEntity<String> replay = phase("EXP-I1", "P1", 1, "start",
                "c1", "COORDINATOR", "req-i1-s1", null);
        assertEquals(200, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody(), "重放必须原样返回首次完整响应");
        assertEquals(T0, mapper.readTree(replay.getBody()).path("firstStartedAt").asLong());
        // 业务未被重复执行：版本仍为 1
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM crossover_phase WHERE experiment_id = 'EXP-I1'",
                Integer.class));

        // 同键异参（不同阶段）409；同键不同操作者 409
        assertEquals(409, phase("EXP-I1", "P1", 2, "start",
                "c1", "COORDINATOR", "req-i1-s1", null).getStatusCode().value());
        assertEquals(409, phase("EXP-I1", "P1", 1, "start",
                "c2", "COORDINATOR", "req-i1-s1", null).getStatusCode().value());
        // 同键不同操作（start vs end）409
        assertEquals(409, phase("EXP-I1", "P1", 1, "end",
                "c1", "COORDINATOR", "req-i1-s1", null).getStatusCode().value());
    }

    @Test
    void permissionCheckedBeforeReplay_andFailureDoesNotHoldKey() throws Exception {
        createCrossoverAndRegister("EXP-I2", "P1", "req-i2");

        // REVIEWER 无阶段写权限：403，先于任何幂等回放
        assertEquals(403, phase("EXP-I2", "P1", 1, "start",
                "r1", "REVIEWER", "req-i2-denied", null).getStatusCode().value());
        // 该键未被占用：协调员可正常使用同一键
        assertEquals(200, phase("EXP-I2", "P1", 1, "start",
                "c1", "COORDINATOR", "req-i2-denied", null).getStatusCode().value());
        // REVIEWER 重放协调员已成功的键：仍 403（权限先于回放）
        assertEquals(403, phase("EXP-I2", "P1", 1, "start",
                "r1", "REVIEWER", "req-i2-denied", null).getStatusCode().value());

        // 失败不占键：洗脱不足 422 后，同键同参在洗脱满后可成功
        assertEquals(200, phase("EXP-I2", "P1", 1, "end",
                "c1", "COORDINATOR", "req-i2-e1", null).getStatusCode().value());
        assertEquals(422, phase("EXP-I2", "P1", 2, "start",
                "c1", "COORDINATOR", "req-i2-s2", null).getStatusCode().value());
        clock.advance(5 * MINUTE);
        ResponseEntity<String> afterWashout = phase("EXP-I2", "P1", 2, "start",
                "c1", "COORDINATOR", "req-i2-s2", null);
        assertEquals(200, afterWashout.getStatusCode().value());
        assertEquals(T0 + 5 * MINUTE,
                mapper.readTree(afterWashout.getBody()).path("secondStartedAt").asLong());
    }

    @Test
    void expectedVersion_mismatchConflicts_matchSucceeds() throws Exception {
        createCrossoverAndRegister("EXP-I3", "P1", "req-i3");

        // 期望版本与当前版本（0）不一致 → 409
        assertEquals(409, phase("EXP-I3", "P1", 1, "start",
                "c1", "COORDINATOR", "req-i3-s1-bad", "{\"expectedVersion\":3}")
                .getStatusCode().value());
        // 期望版本匹配 → 200，版本推进到 1
        assertEquals(200, phase("EXP-I3", "P1", 1, "start",
                "c1", "COORDINATOR", "req-i3-s1", "{\"expectedVersion\":0}")
                .getStatusCode().value());
        // 旧期望版本不再匹配 → 409
        assertEquals(409, phase("EXP-I3", "P1", 1, "end",
                "c1", "COORDINATOR", "req-i3-e1-bad", "{\"expectedVersion\":0}")
                .getStatusCode().value());
        assertEquals(200, phase("EXP-I3", "P1", 1, "end",
                "c1", "COORDINATOR", "req-i3-e1", "{\"expectedVersion\":1}")
                .getStatusCode().value());
        // 同键同参（含期望版本）重放：返回首次响应，版本不重复推进
        assertEquals(200, phase("EXP-I3", "P1", 1, "end",
                "c1", "COORDINATOR", "req-i3-e1", "{\"expectedVersion\":1}")
                .getStatusCode().value());
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM crossover_phase WHERE experiment_id = 'EXP-I3'",
                Integer.class));
    }

    @Test
    void expectedVersion_isPartOfIdempotencyIdentity() throws Exception {
        createCrossoverAndRegister("EXP-I4", "P1", "req-i4");
        ResponseEntity<String> first = phase("EXP-I4", "P1", 1, "start",
                "c1", "COORDINATOR", "req-i4-s1", "{\"expectedVersion\":0}");
        assertEquals(200, first.getStatusCode().value());
        // 同键同参（含期望版本）重放成功
        ResponseEntity<String> replay = phase("EXP-I4", "P1", 1, "start",
                "c1", "COORDINATOR", "req-i4-s1", "{\"expectedVersion\":0}");
        assertEquals(200, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        // 同键但期望版本不同 → 异参 409
        assertEquals(409, phase("EXP-I4", "P1", 1, "start",
                "c1", "COORDINATOR", "req-i4-s1", "{\"expectedVersion\":1}")
                .getStatusCode().value());
        // 同键但缺少期望版本 → 异参 409
        assertEquals(409, phase("EXP-I4", "P1", 1, "start",
                "c1", "COORDINATOR", "req-i4-s1", null).getStatusCode().value());
        assertNotEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'req-i4-s1'",
                Integer.class));
    }
}
