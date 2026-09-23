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
 * 职责轮换与最小知情授权代次主流程：
 * 首次/再次轮换、前后名册、版本推进、最小字段、采集范围、只读预览与查询。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RotationFlowTest extends AbstractBlindIntegrationTest {

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

    private HttpHeaders tokenHeaders(String actor, String role, String token) {
        HttpHeaders h = headers(actor, role, null);
        if (token != null) {
            h.set("X-Access-Token", token);
        }
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

    private String activateBody(long version, long effectiveAt, String rosterJson) {
        return "{\"expectedExperimentVersion\":" + version + ",\"effectiveAt\":" + effectiveAt
                + ",\"roster\":" + rosterJson + "}";
    }

    private void createExperiment(String expId, int blocks, String requestId) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("lead", "COORDINATOR", requestId),
                "{\"blockCount\":" + blocks + "}").getStatusCode().value());
    }

    private void register(String expId, String pid, String requestId) {
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST, headers("lead", "COORDINATOR", requestId), null)
                .getStatusCode().value());
    }

    @Test
    void firstRotation_createsGeneration_setsVersion_beforeRosterEmpty_andMinimumFields() throws Exception {
        createExperiment("ROT-1", 2, "rot1-create");
        register("ROT-1", "PA", "rot1-alloc-a");
        register("ROT-1", "PB", "rot1-alloc-b");

        String rosterJson = roster("\"d1\"", "\"rc1\"", "\"sr1\"");
        ResponseEntity<String> resp = exchange(
                "/api/experiments/ROT-1/rotations/RK-1/activate", HttpMethod.POST,
                headers("lead", "COORDINATOR", "rot1-activate"),
                activateBody(0, 1_700_000_000_000L, rosterJson));
        assertEquals(200, resp.getStatusCode().value());
        JsonNode body = json(resp);
        assertEquals("RK-1", body.path("rotationKey").asText());
        assertEquals("ACTIVATED", body.path("status").asText());
        assertEquals(0, body.path("expectedVersion").asInt());
        assertEquals(1, body.path("newVersion").asInt());

        // 首次轮换前名册三类均为空
        JsonNode before = body.path("beforeRoster");
        assertTrue(before.path("dataCollectors").isEmpty());
        assertTrue(before.path("randomizationCustodians").isEmpty());
        assertTrue(before.path("safetyReviewers").isEmpty());
        // 目标名册
        JsonNode target = body.path("targetRoster");
        assertEquals(List.of("d1"), toStringList(target, "dataCollectors"));
        assertEquals(List.of("rc1"), toStringList(target, "randomizationCustodians"));
        assertEquals(List.of("sr1"), toStringList(target, "safetyReviewers"));

        // 新代次
        JsonNode gen = body.path("generation");
        assertEquals(1, gen.path("generationNo").asInt());
        assertEquals("ACTIVE", gen.path("status").asText());
        long generationId = gen.path("generationId").asLong();

        // 最小字段：三类角色各自仅得到完成职责所需字段
        JsonNode fields = gen.path("grantedFields");
        assertEquals(List.of("BLIND_CODE", "META"),
                toStringList(fields, "DATA_COLLECTOR"));
        assertEquals(List.of("BLOCK_NO", "SEAT_NO"),
                toStringList(fields, "RANDOMIZATION_CUSTODIAN"));
        assertEquals(List.of("SAFETY", "TREATMENT"),
                toStringList(fields, "SAFETY_REVIEWER"));

        // 实验版本与活动代次指针推进
        JsonNode exp = json(exchange("/api/experiments/ROT-1", HttpMethod.GET,
                headers("lead", "COORDINATOR", null), null));
        assertEquals(1, exp.path("roleVersion").asInt());

        // 采集范围：d1 覆盖两个未结束受试者
        List<String> scope = jdbc.queryForList(
                "SELECT participant_id FROM collector_scope WHERE generation_id = ? "
                        + "AND actor_id = 'd1' ORDER BY participant_id",
                String.class, generationId);
        assertEquals(List.of("PA", "PB"), scope);
        // 库内仅一个 ACTIVE 代次
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_generation WHERE experiment_id = 'ROT-1' "
                        + "AND status = 'ACTIVE'", Integer.class));
    }

    @Test
    void secondRotation_supersedesOldGeneration_recordsBeforeRoster_andBumpsVersion() throws Exception {
        createExperiment("ROT-2", 2, "rot2-create");
        register("ROT-2", "PA", "rot2-alloc-a");
        firstRotation("ROT-2", "RK-2A", 0, "\"d1\"", "\"rc1\"", "\"sr1\"", "rot2-act-a");

        String rosterJson = roster("\"d2\"", "\"rc2\"", "\"sr2\"");
        ResponseEntity<String> resp = exchange(
                "/api/experiments/ROT-2/rotations/RK-2B/activate", HttpMethod.POST,
                headers("lead", "COORDINATOR", "rot2-act-b"),
                activateBody(1, 1_700_000_000_000L, rosterJson));
        assertEquals(200, resp.getStatusCode().value());
        JsonNode body = json(resp);
        assertEquals(1, body.path("expectedVersion").asInt());
        assertEquals(2, body.path("newVersion").asInt());
        // 前名册为第一代名册
        JsonNode before = body.path("beforeRoster");
        assertEquals(List.of("d1"), toStringList(before, "dataCollectors"));
        assertEquals(List.of("rc1"), toStringList(before, "randomizationCustodians"));
        assertEquals(List.of("sr1"), toStringList(before, "safetyReviewers"));
        // 新代次序号为 2
        assertEquals(2, body.path("generation").path("generationNo").asInt());

        // 第一代已 SUPERSEDED，第二代 ACTIVE：不存在两代同时有效
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_generation WHERE experiment_id = 'ROT-2' "
                        + "AND status = 'ACTIVE'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_generation WHERE experiment_id = 'ROT-2' "
                        + "AND status = 'SUPERSEDED'", Integer.class));
        Long activeId = jdbc.queryForObject(
                "SELECT id FROM access_generation WHERE experiment_id = 'ROT-2' "
                        + "AND status = 'ACTIVE'", Long.class);
        Long pointer = jdbc.queryForObject(
                "SELECT active_generation_id FROM experiment WHERE id = 'ROT-2'", Long.class);
        assertEquals(activeId, pointer);
        // 旧名册作为审计证据保留
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_assignment WHERE generation_id = "
                        + "(SELECT id FROM access_generation WHERE experiment_id = 'ROT-2' "
                        + "AND generation_no = 1)", Integer.class));
    }

    @Test
    void preview_isReadOnly_andReportsScopesAndConflicts() throws Exception {
        createExperiment("ROT-3", 2, "rot3-create");
        register("ROT-3", "PA", "rot3-alloc-a");
        register("ROT-3", "PB", "rot3-alloc-b");
        // lead 申请并经 rev 批准揭盲 PA：lead 已知 PA 分组
        approveUnblindFor("ROT-3", "PA", "lead", "rot3-ub");

        String rosterJson = roster("\"lead\",\"d1\"", "\"rc1\"", "\"sr1\"");
        String bodyJson = "{\"effectiveAt\":1700000000000,\"roster\":" + rosterJson + "}";
        ResponseEntity<String> resp = exchange(
                "/api/experiments/ROT-3/rotation-preview", HttpMethod.POST,
                headers("lead", "COORDINATOR", null), bodyJson);
        assertEquals(200, resp.getStatusCode().value());
        JsonNode preview = json(resp);
        assertEquals(0, preview.path("currentVersion").asInt());
        assertFalse(preview.path("valid").asBoolean());

        // lead 对 PA 有知情冲突，依据含揭盲单号；d1 无冲突
        JsonNode conflicts = preview.path("conflicts");
        assertEquals(1, conflicts.size());
        assertEquals("lead", conflicts.get(0).path("actorId").asText());
        assertEquals("PA", conflicts.get(0).path("participantId").asText());
        assertTrue(conflicts.get(0).path("unblindRequestId").asText().startsWith("UB-"));

        JsonNode scopes = preview.path("collectorScopes");
        for (JsonNode scope : scopes) {
            if ("lead".equals(scope.path("actorId").asText())) {
                assertEquals(List.of("PB"), toStringList(scope, "visibleParticipants"));
                assertEquals(1, scope.path("blocked").size());
            } else {
                assertEquals("d1", scope.path("actorId").asText());
                assertEquals(List.of("PA", "PB"), toStringList(scope, "visibleParticipants"));
            }
        }

        // 预览不写任何数据
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_generation WHERE experiment_id = 'ROT-3'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rotation_order WHERE experiment_id = 'ROT-3'",
                Integer.class));
    }

    @Test
    void getOrderAndCurrentGeneration_returnSnapshots() throws Exception {
        createExperiment("ROT-4", 2, "rot4-create");
        register("ROT-4", "PA", "rot4-alloc-a");
        JsonNode activated = json(firstRotation("ROT-4", "RK-4", 0,
                "\"d1\"", "\"rc1\"", "\"sr1\"", "rot4-act"));
        long generationId = activated.path("generation").path("generationId").asLong();

        ResponseEntity<String> orderResp = exchange(
                "/api/experiments/ROT-4/rotations/RK-4", HttpMethod.GET,
                headers("lead", "COORDINATOR", null), null);
        assertEquals(200, orderResp.getStatusCode().value());
        JsonNode order = json(orderResp);
        assertEquals(generationId, order.path("generation").path("generationId").asLong());
        assertNotNull(order.path("conflictEvidence"));

        ResponseEntity<String> genResp = exchange(
                "/api/experiments/ROT-4/generations/current", HttpMethod.GET,
                headers("sr1", "SAFETY_REVIEWER", null), null);
        assertEquals(200, genResp.getStatusCode().value());
        assertEquals(generationId, json(genResp).path("generationId").asLong());

        // 查询不存在的轮换单 404
        assertEquals(404, exchange("/api/experiments/ROT-4/rotations/NOPE", HttpMethod.GET,
                headers("lead", "COORDINATOR", null), null).getStatusCode().value());
    }

    private ResponseEntity<String> firstRotation(String expId, String key, long version,
                                                 String collectors, String custodians,
                                                 String reviewers, String requestId) {
        return exchange("/api/experiments/" + expId + "/rotations/" + key + "/activate",
                HttpMethod.POST, headers("lead", "COORDINATOR", requestId),
                activateBody(version, 1_700_000_000_000L, roster(collectors, custodians, reviewers)));
    }

    private String approveUnblindFor(String expId, String pid, String applicant, String prefix) {
        ResponseEntity<String> apply = exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/unblind-requests",
                HttpMethod.POST, headers(applicant, "COORDINATOR", prefix + "-apply"),
                "{\"reason\":\"合成安全核对\"}");
        try {
            String ubId = json(apply).path("requestId").asText();
            assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                    HttpMethod.POST, headers("rev", "REVIEWER", prefix + "-approve"), null)
                    .getStatusCode().value());
            return ubId;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> toStringList(JsonNode parent, String field) {
        return java.util.stream.StreamSupport.stream(parent.path(field).spliterator(), false)
                .map(JsonNode::asText)
                .sorted()
                .toList();
    }
}
