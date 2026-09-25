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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议版本主流程：实验创建自带 V1（50:50 生效）；修订创建、比例规范化、生效时刻校验、
 * 批量只允许一条待生效版本、撤销保留记录、生效后旧版本归 SUPERSEDED、版本查询。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProtocolVersionTest extends AbstractBlindIntegrationTest {

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

    private JsonNode json(ResponseEntity<String> resp) throws Exception {
        assertNotNull(resp.getBody());
        return mapper.readTree(resp.getBody());
    }

    private void createExperiment(String id, String requestId) {
        assertEquals(201, exchange("/api/experiments/" + id, HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId), "{\"blockCount\":2}")
                .getStatusCode().value());
    }

    @Test
    void experimentCreation_seedsInitialVersionV1_fiftyFifty_effective() throws Exception {
        createExperiment("PV-1", "pv1-create");

        ResponseEntity<String> resp = exchange("/api/experiments/PV-1/protocol-versions",
                HttpMethod.GET, headers("r1", "REVIEWER", null), null);
        assertEquals(200, resp.getStatusCode().value());
        JsonNode versions = json(resp);
        assertTrue(versions.isArray());
        assertEquals(1, versions.size());
        JsonNode v1 = versions.get(0);
        assertEquals(1, v1.path("version").asInt());
        assertEquals(50, v1.path("ratioA").asInt());
        assertEquals(50, v1.path("ratioB").asInt());
        assertEquals("EFFECTIVE", v1.path("status").asText());
        assertEquals("SYSTEM", v1.path("createdBy").asText());
        assertFalse(v1.has("treatment"));

        assertEquals("EFFECTIVE", jdbc.queryForObject(
                "SELECT status FROM protocol_version WHERE experiment_id = 'PV-1' AND version = 1",
                String.class));
    }

    @Test
    void createAmendment_validatesRatioAndEffectiveTime_andBecomesPending() throws Exception {
        createExperiment("PV-2", "pv2-create");

        // 比例含非正整数：400
        assertEquals(400, exchange("/api/experiments/PV-2/protocol-amendments", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv2-bad-zero"),
                "{\"ratioA\":0,\"ratioB\":100,\"effectiveAt\":1700000001000}")
                .getStatusCode().value());
        // 比例之和不为 100：400
        assertEquals(400, exchange("/api/experiments/PV-2/protocol-amendments", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv2-bad-sum"),
                "{\"ratioA\":30,\"ratioB\":60,\"effectiveAt\":1700000001000}")
                .getStatusCode().value());
        // 生效时刻早于当前时刻：422（可区分原因）
        ResponseEntity<String> tooEarly = exchange(
                "/api/experiments/PV-2/protocol-amendments", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv2-bad-time"),
                "{\"ratioA\":30,\"ratioB\":70,\"effectiveAt\":1699999999999}");
        assertEquals(422, tooEarly.getStatusCode().value());
        assertTrue(json(tooEarly).path("message").asText().contains("生效时刻"));
        // 失败不占键、不留版本
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_version WHERE experiment_id = 'PV-2'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'pv2-bad-time'",
                Integer.class));

        // 合法修订：PENDING
        ResponseEntity<String> ok = exchange(
                "/api/experiments/PV-2/protocol-amendments", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv2-amd"),
                "{\"ratioA\":30,\"ratioB\":70,\"effectiveAt\":1700000010000}");
        assertEquals(201, ok.getStatusCode().value());
        JsonNode body = json(ok);
        assertEquals(2, body.path("version").asInt());
        assertEquals(30, body.path("ratioA").asInt());
        assertEquals(70, body.path("ratioB").asInt());
        assertEquals(1700000010000L, body.path("effectiveAt").asLong());
        assertEquals("PENDING", body.path("status").asText());
        assertEquals("c1", body.path("createdBy").asText());

        // 第二条待生效修订：422（只允许一条最终待生效版本）
        ResponseEntity<String> second = exchange(
                "/api/experiments/PV-2/protocol-amendments", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv2-amd-2"),
                "{\"ratioA\":80,\"ratioB\":20,\"effectiveAt\":1700000020000}");
        assertEquals(422, second.getStatusCode().value());
        assertTrue(json(second).path("message").asText().contains("待生效"));
    }

    @Test
    void batchCreate_allowsAtMostOnePending_andSingleSucceeds() throws Exception {
        createExperiment("PV-3", "pv3-create");

        // 批量两条：422，整批不留任何修订
        String two = "{\"amendments\":["
                + "{\"ratioA\":30,\"ratioB\":70,\"effectiveAt\":1700000010000},"
                + "{\"ratioA\":80,\"ratioB\":20,\"effectiveAt\":1700000020000}]}";
        ResponseEntity<String> rejected = exchange(
                "/api/experiments/PV-3/protocol-amendments/batch", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv3-batch-two"), two);
        assertEquals(422, rejected.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_version WHERE experiment_id = 'PV-3'",
                Integer.class));

        // 批量一条：成功
        String one = "{\"amendments\":["
                + "{\"ratioA\":30,\"ratioB\":70,\"effectiveAt\":1700000010000}]}";
        ResponseEntity<String> ok = exchange(
                "/api/experiments/PV-3/protocol-amendments/batch", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv3-batch-one"), one);
        assertEquals(201, ok.getStatusCode().value());
        JsonNode arr = json(ok);
        assertTrue(arr.isArray());
        assertEquals(1, arr.size());
        assertEquals("PENDING", arr.get(0).path("status").asText());

        // 已有待生效版本时再批量一条：422
        ResponseEntity<String> again = exchange(
                "/api/experiments/PV-3/protocol-amendments/batch", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv3-batch-again"), one);
        assertEquals(422, again.getStatusCode().value());

        // 批量内含非法比例：400
        String bad = "{\"amendments\":["
                + "{\"ratioA\":30,\"ratioB\":60,\"effectiveAt\":1700000010000}]}";
        assertEquals(400, exchange(
                "/api/experiments/PV-3/protocol-amendments/batch", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv3-batch-bad"), bad).getStatusCode().value());
    }

    @Test
    void revokePending_keepsRecord_andFreesSlot_effectiveCannotRevoke() throws Exception {
        createExperiment("PV-4", "pv4-create");
        assertEquals(201, exchange("/api/experiments/PV-4/protocol-amendments", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv4-amd"),
                "{\"ratioA\":30,\"ratioB\":70,\"effectiveAt\":1700000010000}")
                .getStatusCode().value());

        // 撤销未生效修订
        clock.advance(1000L);
        ResponseEntity<String> revoked = exchange(
                "/api/experiments/PV-4/protocol-amendments/2/revocation", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv4-revoke"), null);
        assertEquals(200, revoked.getStatusCode().value());
        JsonNode body = json(revoked);
        assertEquals("REVOKED", body.path("status").asText());
        assertEquals(1_700_000_001_000L, body.path("revokedAt").asLong());
        assertEquals("c1", body.path("revokedBy").asText());

        // 记录保留
        assertEquals("REVOKED", jdbc.queryForObject(
                "SELECT status FROM protocol_version WHERE experiment_id = 'PV-4' AND version = 2",
                String.class));

        // 撤销后释放待生效名额：可再创建一条（版本号 3）
        ResponseEntity<String> another = exchange(
                "/api/experiments/PV-4/protocol-amendments", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv4-amd-3"),
                "{\"ratioA\":80,\"ratioB\":20,\"effectiveAt\":1700000020000}");
        assertEquals(201, another.getStatusCode().value());
        assertEquals(3, json(another).path("version").asInt());

        // 已撤销的版本再次撤销：409
        assertEquals(409, exchange(
                "/api/experiments/PV-4/protocol-amendments/2/revocation", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv4-revoke-again"), null)
                .getStatusCode().value());
    }

    @Test
    void effect_beforeDueTime_conflicts_andManualEffectSupersedesOldVersion() throws Exception {
        createExperiment("PV-5", "pv5-create");
        assertEquals(201, exchange("/api/experiments/PV-5/protocol-amendments", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv5-amd"),
                "{\"ratioA\":30,\"ratioB\":70,\"effectiveAt\":1700000010000}")
                .getStatusCode().value());

        // 未到生效时刻手动生效：422
        ResponseEntity<String> early = exchange(
                "/api/experiments/PV-5/protocol-amendments/2/effect", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv5-effect-early"), null);
        assertEquals(422, early.getStatusCode().value());
        assertTrue(json(early).path("message").asText().contains("生效时刻"));

        // 到点手动生效
        clock.setTime(1_700_000_010_000L);
        ResponseEntity<String> effected = exchange(
                "/api/experiments/PV-5/protocol-amendments/2/effect", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv5-effect"), null);
        assertEquals(200, effected.getStatusCode().value());
        assertEquals("EFFECTIVE", json(effected).path("status").asText());

        // 旧版本 V1 归 SUPERSEDED
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM protocol_version WHERE experiment_id = 'PV-5' ORDER BY version",
                String.class);
        assertEquals(List.of("SUPERSEDED", "EFFECTIVE"), statuses);

        // 已生效修订不可撤销：409
        assertEquals(409, exchange(
                "/api/experiments/PV-5/protocol-amendments/2/revocation", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv5-revoke-effective"), null)
                .getStatusCode().value());
        // 重复生效：409
        assertEquals(409, exchange(
                "/api/experiments/PV-5/protocol-amendments/2/effect", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv5-effect-again"), null)
                .getStatusCode().value());
        // 不存在的版本：404
        assertEquals(404, exchange(
                "/api/experiments/PV-5/protocol-amendments/99/effect", HttpMethod.POST,
                headers("c1", "COORDINATOR", "pv5-effect-missing"), null)
                .getStatusCode().value());
    }
}
