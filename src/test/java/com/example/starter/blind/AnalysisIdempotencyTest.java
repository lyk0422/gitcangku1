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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分析幂等与版本冻结：
 * 同键同参（含引用换序）重放首次完整响应，异参 409，权限先于重放；
 * 分析冻结引用版本，后续修订不影响已冻结结果，不同版本独立统计。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalysisIdempotencyTest extends AbstractBlindIntegrationTest {

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

    private void submitResult(String expId, String pid, int value, String requestId) {
        ResponseEntity<String> resp = post(
                "/api/experiments/" + expId + "/participants/" + pid + "/result",
                headers("c1", "COORDINATOR", requestId), "{\"value\":" + value + "}");
        int status = resp.getStatusCode().value();
        assertTrue(status == 201 || status == 200, "结果提交应成功: " + resp.getBody());
    }

    /** 建实验、登记 P1..P4、各提交一次版本1结果。 */
    private void setupOpenExperiment(String expId, int[] values, String keyPrefix) {
        assertEquals(201, post("/api/experiments/" + expId,
                headers("c1", "COORDINATOR", keyPrefix + "-create"), "{\"blockCount\":2}")
                .getStatusCode().value());
        for (int i = 1; i <= 4; i++) {
            assertEquals(201, post(
                    "/api/experiments/" + expId + "/participants/P" + i + "/allocations",
                    headers("c1", "COORDINATOR", keyPrefix + "-alloc-" + i), null)
                    .getStatusCode().value());
            submitResult(expId, "P" + i, values[i - 1], keyPrefix + "-result-" + i);
        }
    }

    private void close(String expId, String requestId) {
        assertEquals(200, post("/api/experiments/" + expId + "/close",
                headers("c1", "COORDINATOR", requestId), null).getStatusCode().value());
    }

    private ResponseEntity<String> analyze(String expId, String actor, String role,
                                           String requestId, String body) {
        return post("/api/experiments/" + expId + "/analysis",
                headers(actor, role, requestId), body);
    }

    @Test
    void analysis_idempotentReplay_reorderIsSameParams_conflictOnDifferent() throws Exception {
        setupOpenExperiment("EXP-I1", new int[] {0, 0, 100, 100}, "i1");
        close("EXP-I1", "i1-close");

        ResponseEntity<String> first = analyze("EXP-I1", "r1", "REVIEWER", "i1-ana",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\"],\"version\":1}");
        assertEquals(201, first.getStatusCode().value());

        // 引用换序同参：重放首次完整响应
        ResponseEntity<String> reordered = analyze("EXP-I1", "r1", "REVIEWER", "i1-ana",
                "{\"participantIds\":[\"P4\",\"P3\",\"P2\",\"P1\"],\"version\":1}");
        assertEquals(201, reordered.getStatusCode().value());
        assertEquals(first.getBody(), reordered.getBody());

        // 同键异参（不同版本）：409
        assertEquals(409, analyze("EXP-I1", "r1", "REVIEWER", "i1-ana",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\"],\"version\":2}")
                .getStatusCode().value());
        // 同键异操作者：409
        assertEquals(409, analyze("EXP-I1", "r2", "REVIEWER", "i1-ana",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\"],\"version\":1}")
                .getStatusCode().value());
        // 权限先于重放：COORDINATOR 持同键直接 403
        assertEquals(403, analyze("EXP-I1", "c1", "COORDINATOR", "i1-ana",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\"],\"version\":1}")
                .getStatusCode().value());

        // 只执行一次业务：一条分析记录、一条幂等记录
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM analysis WHERE experiment_id = 'EXP-I1'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'i1-ana'",
                Integer.class));
    }

    @Test
    void analysis_freezesReferencedVersion_laterRevisionDoesNotChangeIt() throws Exception {
        // 版本1：A={0,0}、B={100,100}；随后全部修订到版本2：A={0,0}、B={0,100}
        setupOpenExperiment("EXP-I2", new int[] {0, 0, 100, 100}, "i2");
        int[] v2 = {0, 0, 0, 100};
        for (int i = 1; i <= 4; i++) {
            submitResult("EXP-I2", "P" + i, v2[i - 1], "i2-revise-" + i);
        }
        close("EXP-I2", "i2-close");

        // 冻结版本1：p = 2/6
        ResponseEntity<String> atV1 = analyze("EXP-I2", "r1", "REVIEWER", "i2-ana-v1",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\"],\"version\":1}");
        assertEquals(201, atV1.getStatusCode().value());
        JsonNode bodyV1 = mapper.readTree(atV1.getBody());
        assertEquals(2, bodyV1.path("k").asLong());
        assertEquals(6, bodyV1.path("n").asLong());
        assertEquals("2/6", bodyV1.path("p").asText());

        // 版本2取值不同：A 均值 0、B 均值 50，全部分组统计量都达到观察值，p = 6/6
        ResponseEntity<String> atV2 = analyze("EXP-I2", "r1", "REVIEWER", "i2-ana-v2",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\"],\"version\":2}");
        assertEquals(201, atV2.getStatusCode().value());
        JsonNode bodyV2 = mapper.readTree(atV2.getBody());
        assertEquals(6, bodyV2.path("k").asLong());
        assertEquals(6, bodyV2.path("n").asLong());
        assertEquals("6/6", bodyV2.path("p").asText());

        // 再次引用版本1（新键）：仍是冻结的 2/6
        ResponseEntity<String> againV1 = analyze("EXP-I2", "r1", "REVIEWER", "i2-ana-v1-b",
                "{\"participantIds\":[\"P1\",\"P2\",\"P3\",\"P4\"],\"version\":1}");
        assertEquals(201, againV1.getStatusCode().value());
        assertEquals("2/6", mapper.readTree(againV1.getBody()).path("p").asText());

        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM analysis WHERE experiment_id = 'EXP-I2'", Integer.class));
    }
}
