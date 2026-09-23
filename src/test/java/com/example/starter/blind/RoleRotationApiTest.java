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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 职责轮换主流程与失败分支：
 * 激活成功原子换代次并签发最小授权；预览只读；名册违规、知情冲突、版本过期、
 * 非负责人、实验关闭、rotationKey 重复分别 422/409/403，且失败整单回滚、不占幂等键。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoleRotationApiTest extends AbstractBlindIntegrationTest {

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

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }

    private void createExperiment(String expId, String owner, String requestId) {
        ResponseEntity<String> resp = exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers(owner, "COORDINATOR", requestId), "{\"blockCount\":2}");
        assertEquals(201, resp.getStatusCode().value());
    }

    private void register(String expId, String participantId, String requestId) {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/participants/" + participantId + "/allocations",
                HttpMethod.POST, headers("pi1", "COORDINATOR", requestId), null);
        assertEquals(201, resp.getStatusCode().value());
    }

    /** 已批准揭盲：申请人 applicant（COORDINATOR），批准人 reviewer（REVIEWER）。 */
    private void approveUnblind(String expId, String participantId, String applicant,
                                String reviewer, String requestIdPrefix) throws Exception {
        ResponseEntity<String> apply = exchange(
                "/api/experiments/" + expId + "/participants/" + participantId + "/unblind-requests",
                HttpMethod.POST, headers(applicant, "COORDINATOR", requestIdPrefix + "-apply"),
                "{\"reason\":\"安全性核查\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        ResponseEntity<String> approve = exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers(reviewer, "REVIEWER", requestIdPrefix + "-approve"), null);
        assertEquals(200, approve.getStatusCode().value());
    }

    private String rotationBody(String rotationKey, long expectedVersion, long effectiveAt,
                                List<String> collectors, List<String> custodians,
                                List<String> reviewers) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rotationKey", rotationKey);
        body.put("expectedExperimentVersion", expectedVersion);
        body.put("effectiveAt", effectiveAt);
        body.put("dataCollectors", collectors);
        body.put("randomizationCustodians", custodians);
        body.put("safetyReviewers", reviewers);
        return mapper.writeValueAsString(body);
    }

    private ResponseEntity<String> activate(String expId, String actor, String requestId,
                                            String body) {
        return exchange("/api/experiments/" + expId + "/role-rotations", HttpMethod.POST,
                headers(actor, "COORDINATOR", requestId), body);
    }

    private int activeGenerationCount(String expId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM access_generation "
                + "WHERE experiment_id = '" + expId + "' AND status = 'ACTIVE'", Integer.class);
    }

    @Test
    void activate_success_endsOldGenerationAndIssuesMinimalGrants() throws Exception {
        createExperiment("EXP-R1", "pi1", "r1-create");
        register("EXP-R1", "P1", "r1-reg-1");
        register("EXP-R1", "P2", "r1-reg-2");
        // 创建 v1 + 两次登记 => 当前版本 3
        String body = rotationBody("RK-1", 3, T0,
                List.of("dc2", "dc1"), List.of("rc1"), List.of("sr1"));
        ResponseEntity<String> resp = activate("EXP-R1", "pi1", "r1-rot", body);
        assertEquals(201, resp.getStatusCode().value());
        JsonNode view = json(resp);
        assertEquals("RK-1", view.path("rotationKey").asText());
        assertEquals("EXP-R1", view.path("experimentId").asText());
        assertEquals("pi1", view.path("actorId").asText());
        assertEquals(1, view.path("beforeGenerationNo").asInt());
        assertEquals(2, view.path("afterGenerationNo").asInt());
        // 名册已排序（换序同参），初始代次前名册为空
        assertEquals(List.of("dc1", "dc2"),
                mapper.convertValue(view.path("afterRoster").path("dataCollectors"),
                        mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals(0, view.path("beforeRoster").path("dataCollectors").size());
        assertEquals(0, view.path("knowledgeBasis").size());

        // 旧代次原子结束，新代次唯一活动
        assertEquals(1, activeGenerationCount("EXP-R1"));
        assertEquals("ENDED", jdbc.queryForObject("SELECT status FROM access_generation "
                + "WHERE experiment_id = 'EXP-R1' AND generation_no = 1", String.class));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM access_generation "
                + "WHERE experiment_id = 'EXP-R1' AND generation_no = 2", String.class));
        // 最小字段授权：采集不见盲底，保管可见处理映射
        assertEquals("participantId,blindCode,status", jdbc.queryForObject(
                "SELECT visible_fields FROM access_grant WHERE experiment_id = 'EXP-R1' "
                        + "AND actor_id = 'dc1' AND role_type = 'DATA_COLLECTOR'", String.class));
        assertEquals("participantId,blindCode,blockNo,seatNo,treatment", jdbc.queryForObject(
                "SELECT visible_fields FROM access_grant WHERE experiment_id = 'EXP-R1' "
                        + "AND actor_id = 'rc1' AND role_type = 'RANDOMIZATION_CUSTODIAN'",
                String.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM access_grant "
                + "WHERE experiment_id = 'EXP-R1'", Integer.class));
        // 激活递增实验版本
        assertEquals(4, jdbc.queryForObject("SELECT version FROM experiment WHERE id = 'EXP-R1'",
                Integer.class));

        // 查询轮换单：前后名册、代次、知情依据，只读
        ResponseEntity<String> query = exchange("/api/experiments/EXP-R1/role-rotations/RK-1",
                HttpMethod.GET, headers("sr1", "REVIEWER", null), null);
        assertEquals(200, query.getStatusCode().value());
        JsonNode queried = json(query);
        assertEquals(2, queried.path("afterGenerationNo").asInt());
        assertEquals("rc1", queried.path("afterRoster").path("randomizationCustodians")
                .get(0).asText());
    }

    @Test
    void preview_computesVisibilityAndConflicts_withoutAnyWrite() throws Exception {
        createExperiment("EXP-R2", "pi1", "r2-create");
        register("EXP-R2", "P1", "r2-reg-1");
        register("EXP-R2", "P2", "r2-reg-2");
        // dc1 通过批准揭盲知悉 P1 分组（申请人与批准人均知情）
        approveUnblind("EXP-R2", "P1", "dc1", "rev1", "r2-ub");
        // 版本：创建1 + 登记2 + 揭盲批准1 = 4
        String body = rotationBody("RK-2", 4, T0,
                List.of("dc1", "dc2"), List.of("rc1"), List.of("sr1"));
        ResponseEntity<String> resp = exchange("/api/experiments/EXP-R2/role-rotations/preview",
                HttpMethod.POST, headers("pi1", "COORDINATOR", null), body);
        assertEquals(200, resp.getStatusCode().value());
        JsonNode view = json(resp);
        assertEquals(4, view.path("currentVersion").asInt());
        assertTrue(view.path("versionMatch").asBoolean());
        assertFalse(view.path("activatable").asBoolean());
        // 知情冲突：dc1 不得采集 P1；dc2 无冲突
        assertEquals(1, view.path("conflicts").size());
        assertEquals("dc1", view.path("conflicts").get(0).path("actorId").asText());
        assertEquals("P1", view.path("conflicts").get(0).path("participantId").asText());
        // 可见范围：每名采集人对全部未结束受试者
        JsonNode visibility = view.path("visibility");
        assertEquals(4, visibility.size());
        for (JsonNode entry : visibility) {
            assertEquals(2, entry.path("participantIds").size());
            if ("DATA_COLLECTOR".equals(entry.path("roleType").asText())) {
                String fields = entry.path("visibleFields").toString();
                assertFalse(fields.contains("treatment"), "采集人不得见处理代码");
                assertFalse(fields.contains("seatNo"), "采集人不得见席位号");
            }
        }
        // 预览不写数据：无轮换单、无授权、代次不变
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM role_rotation", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM access_grant", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM access_generation "
                + "WHERE experiment_id = 'EXP-R2'", Integer.class));
    }

    @Test
    void activate_withKnowledgeConflict_422_oldGenerationIntactAndKeyNotConsumed() throws Exception {
        createExperiment("EXP-R3", "pi1", "r3-create");
        register("EXP-R3", "P1", "r3-reg-1");
        approveUnblind("EXP-R3", "P1", "dc1", "rev1", "r3-ub");
        // 版本：1 + 1 + 1 = 3
        String body = rotationBody("RK-3", 3, T0,
                List.of("dc1"), List.of("rc1"), List.of("sr1"));
        ResponseEntity<String> resp = activate("EXP-R3", "pi1", "r3-rot", body);
        assertEquals(422, resp.getStatusCode().value());
        // 整单回滚：旧代次仍活动，无新代次、无授权、无轮换单
        assertEquals(1, activeGenerationCount("EXP-R3"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM access_generation "
                + "WHERE experiment_id = 'EXP-R3'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM access_grant", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM role_rotation", Integer.class));
        // 失败不占幂等键
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_request "
                + "WHERE request_id = 'r3-rot'", Integer.class));
        // 同一 requestId 修正名册后成功
        String fixed = rotationBody("RK-3", 3, T0,
                List.of("dc2"), List.of("rc1"), List.of("sr1"));
        ResponseEntity<String> retry = activate("EXP-R3", "pi1", "r3-rot", fixed);
        assertEquals(201, retry.getStatusCode().value());
        assertEquals(2, json(retry).path("afterGenerationNo").asInt());
    }

    @Test
    void activate_rosterViolations_422() throws Exception {
        createExperiment("EXP-R4", "pi1", "r4-create");
        // 某类名册为空
        ResponseEntity<String> empty = activate("EXP-R4", "pi1", "r4-rot-1",
                rotationBody("RK-4a", 1, T0, List.of("dc1"), List.of("rc1"), List.of()));
        assertEquals(422, empty.getStatusCode().value());
        // 同一人员同时承担采集与保管
        ResponseEntity<String> overlap = activate("EXP-R4", "pi1", "r4-rot-2",
                rotationBody("RK-4b", 1, T0, List.of("dc1", "x1"), List.of("x1"), List.of("sr1")));
        assertEquals(422, overlap.getStatusCode().value());
        assertEquals(1, activeGenerationCount("EXP-R4"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM role_rotation", Integer.class));
    }

    @Test
    void activate_staleExpectedVersion_409_oldGenerationIntact() throws Exception {
        createExperiment("EXP-R5", "pi1", "r5-create");
        register("EXP-R5", "P1", "r5-reg-1");
        // 当前版本 2，携带过期版本 1
        ResponseEntity<String> resp = activate("EXP-R5", "pi1", "r5-rot",
                rotationBody("RK-5", 1, T0, List.of("dc1"), List.of("rc1"), List.of("sr1")));
        assertEquals(409, resp.getStatusCode().value());
        assertEquals(1, activeGenerationCount("EXP-R5"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM role_rotation", Integer.class));
    }

    @Test
    void experimentVersion_incrementsOnSubjectAndUnblindEvents() {
        createExperiment("EXP-R6", "pi1", "r6-create");
        register("EXP-R6", "P1", "r6-reg-1");
        register("EXP-R6", "P2", "r6-reg-2");
        assertEquals(3, jdbc.queryForObject("SELECT version FROM experiment WHERE id = 'EXP-R6'",
                Integer.class));
        ResponseEntity<String> withdraw = exchange(
                "/api/experiments/EXP-R6/participants/P2/withdrawal", HttpMethod.POST,
                headers("pi1", "COORDINATOR", "r6-wd"), null);
        assertEquals(200, withdraw.getStatusCode().value());
        assertEquals(4, jdbc.queryForObject("SELECT version FROM experiment WHERE id = 'EXP-R6'",
                Integer.class));
    }

    @Test
    void activate_nonOwnerOrReviewer_forbidden() throws Exception {
        createExperiment("EXP-R7", "pi1", "r7-create");
        String body = rotationBody("RK-7", 1, T0, List.of("dc1"), List.of("rc1"), List.of("sr1"));
        // 非负责人 COORDINATOR
        ResponseEntity<String> other = activate("EXP-R7", "c9", "r7-rot-1", body);
        assertEquals(403, other.getStatusCode().value());
        // REVIEWER 角色
        ResponseEntity<String> reviewer = exchange("/api/experiments/EXP-R7/role-rotations",
                HttpMethod.POST, headers("pi1", "REVIEWER", "r7-rot-2"), body);
        assertEquals(403, reviewer.getStatusCode().value());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM role_rotation", Integer.class));
    }

    @Test
    void activate_closedExperiment_409() throws Exception {
        createExperiment("EXP-R8", "pi1", "r8-create");
        ResponseEntity<String> close = exchange("/api/experiments/EXP-R8/close", HttpMethod.POST,
                headers("pi1", "COORDINATOR", "r8-close"), null);
        assertEquals(200, close.getStatusCode().value());
        // 关闭后版本为 2
        ResponseEntity<String> resp = activate("EXP-R8", "pi1", "r8-rot",
                rotationBody("RK-8", 2, T0, List.of("dc1"), List.of("rc1"), List.of("sr1")));
        assertEquals(409, resp.getStatusCode().value());
        assertEquals(1, activeGenerationCount("EXP-R8"));
    }

    @Test
    void activate_idempotentReplay_rosterReorderedSameParams_returnsFirstSnapshot() throws Exception {
        createExperiment("EXP-R9", "pi1", "r9-create");
        String body = rotationBody("RK-9", 1, T0,
                List.of("dc1", "dc2"), List.of("rc1"), List.of("sr1"));
        ResponseEntity<String> first = activate("EXP-R9", "pi1", "r9-rot", body);
        assertEquals(201, first.getStatusCode().value());
        // 名册换序视为同参：回放首次快照
        String reordered = rotationBody("RK-9", 1, T0,
                List.of("dc2", "dc1"), List.of("rc1"), List.of("sr1"));
        ResponseEntity<String> replay = activate("EXP-R9", "pi1", "r9-rot", reordered);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM role_rotation", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM access_generation "
                + "WHERE experiment_id = 'EXP-R9'", Integer.class));
        // 同键异参 409
        String different = rotationBody("RK-9", 1, T0,
                List.of("dc1", "dc3"), List.of("rc1"), List.of("sr1"));
        ResponseEntity<String> conflict = activate("EXP-R9", "pi1", "r9-rot", different);
        assertEquals(409, conflict.getStatusCode().value());
    }

    @Test
    void activate_duplicateRotationKey_409_fullRollbackKeepsOldGeneration() throws Exception {
        createExperiment("EXP-R10", "pi1", "r10-create");
        String body = rotationBody("RK-10", 1, T0, List.of("dc1"), List.of("rc1"), List.of("sr1"));
        assertEquals(201, activate("EXP-R10", "pi1", "r10-rot-1", body).getStatusCode().value());
        // 相同 rotationKey、不同 requestId、版本正确：唯一约束兜底，整单回滚
        String duplicate = rotationBody("RK-10", 2, T0, List.of("dc2"), List.of("rc2"),
                List.of("sr2"));
        ResponseEntity<String> resp = activate("EXP-R10", "pi1", "r10-rot-2", duplicate);
        assertEquals(409, resp.getStatusCode().value());
        // 旧代次仍是唯一活动代次，未产生新代次与新授权
        assertEquals(1, activeGenerationCount("EXP-R10"));
        assertEquals(2, jdbc.queryForObject("SELECT generation_no FROM access_generation "
                + "WHERE experiment_id = 'EXP-R10' AND status = 'ACTIVE'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM access_generation "
                + "WHERE experiment_id = 'EXP-R10'", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM access_grant "
                + "WHERE experiment_id = 'EXP-R10'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM role_rotation", Integer.class));
    }

    @Test
    void getRotation_unknown_404() {
        createExperiment("EXP-R11", "pi1", "r11-create");
        ResponseEntity<String> resp = exchange("/api/experiments/EXP-R11/role-rotations/RK-X",
                HttpMethod.GET, headers("pi1", "COORDINATOR", null), null);
        assertEquals(404, resp.getStatusCode().value());
    }
}
