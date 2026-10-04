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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 样本链写操作幂等：同键同参重放首次完整响应，异参 409，失败不占键；
 * 权限检查先于幂等回放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SampleChainIdempotencyTest extends AbstractBlindIntegrationTest {

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

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertNotNull(response.getBody());
        return mapper.readTree(response.getBody());
    }

    private void setupWithBothSamples(String expId, String keyPrefix) {
        assertEquals(201, post("/api/experiments/" + expId,
                headers("coord-1", "COORDINATOR", keyPrefix + "-create"), "{\"blockCount\":2}")
                .getStatusCode().value());
        assertEquals(201, post("/api/experiments/" + expId + "/participants/PA/allocations",
                headers("coord-1", "COORDINATOR", keyPrefix + "-alloc"), null)
                .getStatusCode().value());
        assertEquals(201, post("/api/experiments/" + expId + "/participants/PA/samples",
                headers("coord-1", "COORDINATOR", keyPrefix + "-pre"),
                "{\"phase\":\"PRE\",\"sampleCode\":\"" + keyPrefix + "-PRE\","
                        + "\"digest\":\"d-pre\",\"collectedAt\":1700000010000}")
                .getStatusCode().value());
        assertEquals(201, post("/api/experiments/" + expId + "/participants/PA/samples",
                headers("coord-1", "COORDINATOR", keyPrefix + "-post"),
                "{\"phase\":\"POST\",\"sampleCode\":\"" + keyPrefix + "-POST\","
                        + "\"digest\":\"d-post\",\"collectedAt\":1700000020000}")
                .getStatusCode().value());
    }

    @Test
    void registerReplay_sameKeySameParamsReturnsOriginal_andNoDuplicateRow() throws Exception {
        assertEquals(201, post("/api/experiments/EXP-I1",
                headers("coord-1", "COORDINATOR", "i1-create"), "{\"blockCount\":2}")
                .getStatusCode().value());
        assertEquals(201, post("/api/experiments/EXP-I1/participants/PA/allocations",
                headers("coord-1", "COORDINATOR", "i1-alloc"), null).getStatusCode().value());

        String body = "{\"phase\":\"PRE\",\"sampleCode\":\"SCI1-PRE\","
                + "\"digest\":\"d-pre\",\"collectedAt\":1700000010000}";
        ResponseEntity<String> first = post("/api/experiments/EXP-I1/participants/PA/samples",
                headers("coord-1", "COORDINATOR", "i1-reg"), body);
        assertEquals(201, first.getStatusCode().value());

        // 同键同参重放：原 201 与相同响应体，不产生第二条样本记录
        ResponseEntity<String> replay = post("/api/experiments/EXP-I1/participants/PA/samples",
                headers("coord-1", "COORDINATOR", "i1-reg"), body);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE experiment_id = 'EXP-I1'", Integer.class));

        // 同键异参（不同摘要）：409
        String other = "{\"phase\":\"PRE\",\"sampleCode\":\"SCI1-PRE\","
                + "\"digest\":\"d-other\",\"collectedAt\":1700000010000}";
        assertEquals(409, post("/api/experiments/EXP-I1/participants/PA/samples",
                headers("coord-1", "COORDINATOR", "i1-reg"), other).getStatusCode().value());
        // 同键异操作者：409
        assertEquals(409, post("/api/experiments/EXP-I1/participants/PA/samples",
                headers("coord-2", "COORDINATOR", "i1-reg"), body).getStatusCode().value());
    }

    @Test
    void lockReplay_returnsSamePairCode_andFailureDoesNotConsumeKey() throws Exception {
        setupWithBothSamples("EXP-I2", "i2");

        // 失败不占键：期望版本不匹配 409，键可复用
        assertEquals(409, post("/api/experiments/EXP-I2/participants/PA/sample-pair/lock",
                headers("coord-1", "COORDINATOR", "i2-lock"),
                "{\"expectedPreVersion\":9}").getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'i2-lock'",
                Integer.class));

        ResponseEntity<String> locked = post(
                "/api/experiments/EXP-I2/participants/PA/sample-pair/lock",
                headers("coord-1", "COORDINATOR", "i2-lock"), null);
        assertEquals(201, locked.getStatusCode().value());
        String pairCode = json(locked).path("pairCode").asText();

        // 同键重放：返回首次完整响应（同一配对码），不产生第二对
        ResponseEntity<String> replay = post(
                "/api/experiments/EXP-I2/participants/PA/sample-pair/lock",
                headers("coord-1", "COORDINATOR", "i2-lock"), null);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(locked.getBody(), replay.getBody());
        assertEquals(pairCode, json(replay).path("pairCode").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_pair WHERE experiment_id = 'EXP-I2'", Integer.class));
    }

    @Test
    void correctionReplay_andPermissionPrecedesReplay() throws Exception {
        setupWithBothSamples("EXP-I3", "i3");

        String correctBody = "{\"phase\":\"POST\",\"sampleCode\":\"i3-POST-V2\","
                + "\"digest\":\"d-post-2\",\"collectedAt\":1700000030000}";
        ResponseEntity<String> corrected = post(
                "/api/experiments/EXP-I3/participants/PA/samples/corrections",
                headers("coord-1", "COORDINATOR", "i3-correct"), correctBody);
        assertEquals(201, corrected.getStatusCode().value());

        // 同键同参重放首次响应；样本链仍只有两条 ACTIVE 之外的替代记录
        ResponseEntity<String> replay = post(
                "/api/experiments/EXP-I3/participants/PA/samples/corrections",
                headers("coord-1", "COORDINATOR", "i3-correct"), correctBody);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(corrected.getBody(), replay.getBody());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE experiment_id = 'EXP-I3'", Integer.class));

        // 权限先于幂等回放：分析人持同键请求锁定，必须 403 而非回放
        assertEquals(201, post("/api/experiments/EXP-I3/participants/PA/sample-pair/lock",
                headers("coord-1", "COORDINATOR", "i3-lock"), null).getStatusCode().value());
        assertEquals(403, post("/api/experiments/EXP-I3/participants/PA/sample-pair/lock",
                headers("analyst-1", "ANALYST", "i3-lock"), null).getStatusCode().value());
        assertEquals(403, post("/api/experiments/EXP-I3/participants/PA/sample-pair/lock",
                headers("rev-1", "REVIEWER", "i3-lock"), null).getStatusCode().value());
        // 缺身份头：401 而非回放
        HttpHeaders noAuth = new HttpHeaders();
        noAuth.set("X-Request-Id", "i3-lock");
        assertEquals(401, post("/api/experiments/EXP-I3/participants/PA/sample-pair/lock",
                noAuth, null).getStatusCode().value());
    }
}