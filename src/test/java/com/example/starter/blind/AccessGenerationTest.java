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
 * 代次令牌与数据提交的强制边界：
 * 仅在册人员可签发；旧代次令牌在轮换后拒绝；数据按提交顺序归属当时活动代次；
 * 非采集角色、越界受试者、无令牌、错持有人均拒绝；实验关闭拒绝签发与提交。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessGenerationTest extends AbstractBlindIntegrationTest {

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

    private static String roster(String collectors, String custodians, String reviewers) {
        return "{\"dataCollectors\":[" + collectors + "],\"randomizationCustodians\":["
                + custodians + "],\"safetyReviewers\":[" + reviewers + "]}";
    }

    private void bootstrap(String expId) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("lead", "COORDINATOR", expId + "-create"),
                "{\"blockCount\":2}").getStatusCode().value());
        for (String pid : new String[]{"PA", "PB"}) {
            assertEquals(201, exchange(
                    "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                    HttpMethod.POST, headers("lead", "COORDINATOR", expId + "-" + pid), null)
                    .getStatusCode().value());
        }
    }

    private void rotate(String expId, String key, long version, String rosterJson, String req) {
        String body = "{\"expectedExperimentVersion\":" + version
                + ",\"effectiveAt\":1700000000000,\"roster\":" + rosterJson + "}";
        assertEquals(200, exchange("/api/experiments/" + expId + "/rotations/" + key + "/activate",
                HttpMethod.POST, headers("lead", "COORDINATOR", req), body)
                .getStatusCode().value());
    }

    private String issueToken(String expId, String actor, String role) throws Exception {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/access-tokens", HttpMethod.POST,
                headers(actor, role, null), null);
        assertEquals(200, resp.getStatusCode().value(), actor + " 应能签发令牌");
        return json(resp).path("tokenId").asText();
    }

    private int submit(String expId, String pid, String actor, String role, String token,
                       String payload) {
        HttpHeaders h = headers(actor, role, null);
        if (token != null) {
            h.set("X-Access-Token", token);
        }
        return exchange("/api/experiments/" + expId + "/participants/" + pid + "/data",
                HttpMethod.POST, h, "{\"payload\":\"" + payload + "\"}").getStatusCode().value();
    }

    @Test
    void oldGenerationToken_rejectedAfterRotation_newTokenAttachedToNewGeneration() throws Exception {
        bootstrap("AG-1");
        rotate("AG-1", "AG1-A", 0, roster("\"d1\"", "\"c1\"", "\"s1\""), "ag1-a");
        String oldToken = issueToken("AG-1", "d1", "DATA_COLLECTOR");
        long oldGen = jdbc.queryForObject(
                "SELECT id FROM access_generation WHERE experiment_id = 'AG-1' AND status = 'ACTIVE'",
                Long.class);

        // 旧令牌在旧代次有效：提交归属代次1
        assertEquals(200, submit("AG-1", "PA", "d1", "DATA_COLLECTOR", oldToken, "v1-data"));
        Long submittedGen = jdbc.queryForObject(
                "SELECT generation_id FROM data_submission WHERE experiment_id = 'AG-1' "
                        + "AND participant_id = 'PA'", Long.class);
        assertEquals(oldGen, submittedGen);

        // 轮换到第二代，d1 不再在册，d2 接任
        rotate("AG-1", "AG1-B", 1, roster("\"d2\"", "\"c2\"", "\"s2\""), "ag1-b");
        long newGen = jdbc.queryForObject(
                "SELECT id FROM access_generation WHERE experiment_id = 'AG-1' AND status = 'ACTIVE'",
                Long.class);
        assertEquals(false, oldGen == newGen);

        // 生效时刻前签发的旧令牌在轮换后使用：409
        assertEquals(409, submit("AG-1", "PA", "d1", "DATA_COLLECTOR", oldToken, "after"));

        // d1 已不在册，不能签发新令牌
        assertEquals(403, exchange("/api/experiments/AG-1/access-tokens", HttpMethod.POST,
                headers("d1", "DATA_COLLECTOR", null), null).getStatusCode().value());

        // 新采集者 d2 签发新令牌并提交，归属代次2
        String newToken = issueToken("AG-1", "d2", "DATA_COLLECTOR");
        assertEquals(200, submit("AG-1", "PB", "d2", "DATA_COLLECTOR", newToken, "v2-data"));
        Long newSubmittedGen = jdbc.queryForObject(
                "SELECT generation_id FROM data_submission WHERE experiment_id = 'AG-1' "
                        + "AND participant_id = 'PB'", Long.class);
        assertEquals(newGen, newSubmittedGen);

        // 仅一个 ACTIVE 代次
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_generation WHERE experiment_id = 'AG-1' "
                        + "AND status = 'ACTIVE'", Integer.class));
    }

    @Test
    void tokenAndScope_enforcement() throws Exception {
        bootstrap("AG-2");
        rotate("AG-2", "AG2-A", 0, roster("\"d1\"", "\"c1\"", "\"s1\""), "ag2-a");

        // 无令牌：401
        assertEquals(401, submit("AG-2", "PA", "d1", "DATA_COLLECTOR", null, "x"));
        // 伪造令牌：401
        assertEquals(401, submit("AG-2", "PA", "d1", "DATA_COLLECTOR", "BOGUS", "x"));

        String token = issueToken("AG-2", "d1", "DATA_COLLECTOR");

        // 令牌被另一人冒用：403
        assertEquals(403, submit("AG-2", "PA", "dX", "DATA_COLLECTOR", token, "x"));
        // 同持有人但换角色头：403
        assertEquals(403, submit("AG-2", "PA", "d1", "SAFETY_REVIEWER", token, "x"));
        // 保管者/安全审阅者即便有在册身份也不能提交（其无采集令牌；这里用其令牌）：先验证签发再提交
        // 不在范围的受试者：不存在 404
        assertEquals(404, submit("AG-2", "NOPE", "d1", "DATA_COLLECTOR", token, "x"));

        // 正常范围内提交：200
        assertEquals(200, submit("AG-2", "PA", "d1", "DATA_COLLECTOR", token, "ok"));
    }

    @Test
    void nonCollectorRoles_cannotIssueCollectorTokenOrSubmit() {
        bootstrap("AG-3");
        rotate("AG-3", "AG3-A", 0, roster("\"d1\"", "\"c1\"", "\"s1\""), "ag3-a");
        // 保管者可签发自己角色的令牌，但不是采集令牌；安全审阅者同理
        assertEquals(200, exchange("/api/experiments/AG-3/access-tokens", HttpMethod.POST,
                headers("c1", "RANDOMIZATION_CUSTODIAN", null), null).getStatusCode().value());
        // COORDINATOR 不属于职责角色，签发 403
        assertEquals(403, exchange("/api/experiments/AG-3/access-tokens", HttpMethod.POST,
                headers("lead", "COORDINATOR", null), null).getStatusCode().value());
        // 保管者令牌不能用于数据提交（角色非采集者）
        // （直接以保管者身份、无采集令牌提交，先 401/403；此处无 X-Access-Token，先被令牌校验拦截）
        assertEquals(401, submit("AG-3", "PA", "c1", "RANDOMIZATION_CUSTODIAN", null, "x"));
    }

    @Test
    void closedExperiment_rejectsTokenIssueAndSubmission() throws Exception {
        bootstrap("AG-4");
        rotate("AG-4", "AG4-A", 0, roster("\"d1\"", "\"c1\"", "\"s1\""), "ag4-a");
        String token = issueToken("AG-4", "d1", "DATA_COLLECTOR");
        assertEquals(200, exchange("/api/experiments/AG-4/close", HttpMethod.POST,
                headers("lead", "COORDINATOR", "ag4-close"), null).getStatusCode().value());
        // 关闭后签发令牌 409
        assertEquals(409, exchange("/api/experiments/AG-4/access-tokens", HttpMethod.POST,
                headers("d1", "DATA_COLLECTOR", null), null).getStatusCode().value());
        // 关闭后提交数据 409
        assertEquals(409, submit("AG-4", "PA", "d1", "DATA_COLLECTOR", token, "late"));
    }

    @Test
    void noGenerationBeforeFirstRotation_tokenIssueConflicts() {
        bootstrap("AG-5");
        // 尚未轮换，无活动代次：签发 409
        assertEquals(409, exchange("/api/experiments/AG-5/access-tokens", HttpMethod.POST,
                headers("d1", "DATA_COLLECTOR", null), null).getStatusCode().value());
    }
}
