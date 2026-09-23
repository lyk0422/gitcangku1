package com.example.starter.blind;

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

/**
 * 轮换失败分支与整体回滚：
 * 非 ACTIVE 实验、版本不符、每类至少一人、采集/保管同人、揭盲知情冲突、
 * rotationKey 冲突、缺 requestId；失败时旧授权继续有效且绝不撤一半、不占键。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RotationFailureTest extends AbstractBlindIntegrationTest {

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

    private static String roster(String collectors, String custodians, String reviewers) {
        return "{\"dataCollectors\":[" + collectors + "],\"randomizationCustodians\":["
                + custodians + "],\"safetyReviewers\":[" + reviewers + "]}";
    }

    private String activateBody(long version, String rosterJson) {
        return "{\"expectedExperimentVersion\":" + version + ",\"effectiveAt\":1700000000000,"
                + "\"roster\":" + rosterJson + "}";
    }

    private void createExperiment(String expId) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("lead", "COORDINATOR", expId + "-create"),
                "{\"blockCount\":2}").getStatusCode().value());
    }

    private void register(String expId, String pid, String req) {
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST, headers("lead", "COORDINATOR", req), null)
                .getStatusCode().value());
    }

    private void rotate(String expId, String key, long version, String rosterJson, String req,
                        int expectedStatus) {
        assertEquals(expectedStatus, exchange(
                "/api/experiments/" + expId + "/rotations/" + key + "/activate", HttpMethod.POST,
                headers("lead", "COORDINATOR", req), activateBody(version, rosterJson))
                .getStatusCode().value());
    }

    private String approveUnblind(String expId, String pid, String applicant, String prefix) {
        try {
            ResponseEntity<String> apply = exchange(
                    "/api/experiments/" + expId + "/participants/" + pid + "/unblind-requests",
                    HttpMethod.POST, headers(applicant, "COORDINATOR", prefix + "-apply"),
                    "{\"reason\":\"核对\"}");
            String ubId = mapper.readTree(apply.getBody()).path("requestId").asText();
            assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                    HttpMethod.POST, headers("rev", "REVIEWER", prefix + "-appr"), null)
                    .getStatusCode().value());
            return ubId;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void rotate_onMissingOrClosedExperiment_fails() {
        // 实验不存在
        rotate("NOPE", "RK-X", 0, roster("\"d\"", "\"c\"", "\"s\""), "f-nope", 404);

        createExperiment("F-CLOSED");
        assertEquals(200, exchange("/api/experiments/F-CLOSED/close", HttpMethod.POST,
                headers("lead", "COORDINATOR", "f-close"), null).getStatusCode().value());
        // CLOSED 实验不可轮换
        rotate("F-CLOSED", "RK-C", 0, roster("\"d\"", "\"c\"", "\"s\""), "f-closed", 409);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_generation WHERE experiment_id = 'F-CLOSED'",
                Integer.class));
    }

    @Test
    void versionMismatch_conflicts_andWritesNothing() {
        createExperiment("F-VER");
        register("F-VER", "PA", "f-ver-alloc");
        // 当前版本0，提交期望版本1：409
        rotate("F-VER", "RK-V", 1, roster("\"d\"", "\"c\"", "\"s\""), "f-ver", 409);
        // 版本仍为0，无代次产生
        assertEquals(0, jdbc.queryForObject(
                "SELECT role_version FROM experiment WHERE id = 'F-VER'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_generation WHERE experiment_id = 'F-VER'",
                Integer.class));
        // 失败不占 requestId 键：同键用正确参数可成功
        rotate("F-VER", "RK-V", 0, roster("\"d\"", "\"c\"", "\"s\""), "f-ver", 200);
    }

    @Test
    void eachRoleRequiresAtLeastOne_isBadRequest() {
        createExperiment("F-ROLE");
        register("F-ROLE", "PA", "f-role-alloc");
        // 采集者为空：400
        rotate("F-ROLE", "RK-R1", 0, roster("", "\"c\"", "\"s\""), "f-r1", 400);
        // 保管者为空：400
        rotate("F-ROLE", "RK-R2", 0, roster("\"d\"", "", "\"s\""), "f-r2", 400);
        // 审阅者为空：400
        rotate("F-ROLE", "RK-R3", 0, roster("\"d\"", "\"c\"", ""), "f-r3", 400);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_generation WHERE experiment_id = 'F-ROLE'",
                Integer.class));
    }

    @Test
    void samePersonCollectorAndCustodian_isUnprocessable_andRollsBack() {
        createExperiment("F-DUP");
        register("F-DUP", "PA", "f-dup-alloc");
        // x 同时是采集者与保管者：422
        rotate("F-DUP", "RK-D", 0, roster("\"x\"", "\"x\"", "\"s\""), "f-dup", 422);
        // 整单回滚：无代次、无名册、无轮换单、实验指针仍空
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_generation WHERE experiment_id = 'F-DUP'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_assignment WHERE experiment_id = 'F-DUP'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rotation_order WHERE experiment_id = 'F-DUP'",
                Integer.class));
        assertEquals(null, jdbc.queryForObject(
                "SELECT active_generation_id FROM experiment WHERE id = 'F-DUP'", Long.class));
        // 失败不占 rotationKey：同键换合法名册成功
        rotate("F-DUP", "RK-D", 0, roster("\"d\"", "\"c\"", "\"s\""), "f-dup", 200);
    }

    @Test
    void collectorWhoUnblindedActiveParticipant_isUnprocessable_butWithdrawnIsAllowed() {
        createExperiment("F-UB");
        register("F-UB", "PA", "f-ub-a");
        register("F-UB", "PB", "f-ub-b");
        approveUnblind("F-UB", "PA", "knower", "f-ub");

        // knower 作为采集者、PA 仍在组：422
        rotate("F-UB", "RK-UB", 0,
                roster("\"knower\",\"d2\"", "\"c\"", "\"s\""), "f-ub-rot", 422);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_generation WHERE experiment_id = 'F-UB'",
                Integer.class));

        // PA 退组（已结束）后，knower 不再被限制：成功
        assertEquals(200, exchange("/api/experiments/F-UB/participants/PA/withdrawal",
                HttpMethod.POST, headers("lead", "COORDINATOR", "f-ub-wd"), null)
                .getStatusCode().value());
        rotate("F-UB", "RK-UB", 0,
                roster("\"knower\",\"d2\"", "\"c\"", "\"s\""), "f-ub-rot", 200);

        // 采集范围只含未结束的 PB，不含已退组 PA，也不含被知情的 PA
        List<String> scopes = jdbc.queryForList(
                "SELECT cs.participant_id FROM collector_scope cs "
                        + "JOIN access_generation g ON g.id = cs.generation_id "
                        + "WHERE g.experiment_id = 'F-UB' AND cs.actor_id = 'knower'",
                String.class);
        assertEquals(1, scopes.size());
        assertEquals("PB", scopes.get(0));
    }

    @Test
    void duplicateRotationKey_conflicts_andOldAuthorizationStaysIntact() {
        createExperiment("F-KEY");
        register("F-KEY", "PA", "f-key-alloc");
        rotate("F-KEY", "RK-SAME", 0, roster("\"d1\"", "\"c1\"", "\"s1\""), "f-key-1", 200);
        long firstGen = jdbc.queryForObject(
                "SELECT id FROM access_generation WHERE experiment_id = 'F-KEY' AND status = 'ACTIVE'",
                Long.class);

        // 复用相同 rotationKey、不同 requestId、版本1：409（键已占）
        rotate("F-KEY", "RK-SAME", 1, roster("\"d2\"", "\"c2\"", "\"s2\""), "f-key-2", 409);

        // 旧代次仍 ACTIVE，版本未变，无第二代
        assertEquals(firstGen, jdbc.queryForObject(
                "SELECT active_generation_id FROM experiment WHERE id = 'F-KEY'", Long.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_generation WHERE experiment_id = 'F-KEY' "
                        + "AND status = 'ACTIVE'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT role_version FROM experiment WHERE id = 'F-KEY'", Integer.class));
    }

    @Test
    void missingRequestId_isBadRequest_andPrecededByRoleCheck() {
        createExperiment("F-AUTH");
        register("F-AUTH", "PA", "f-auth-alloc");
        // 缺 X-Request-Id：400
        assertEquals(400, exchange(
                "/api/experiments/F-AUTH/rotations/RK-A/activate", HttpMethod.POST,
                headers("lead", "COORDINATOR", null),
                activateBody(0, roster("\"d\"", "\"c\"", "\"s\""))).getStatusCode().value());
        // REVIEWER 不能轮换：403
        assertEquals(403, exchange(
                "/api/experiments/F-AUTH/rotations/RK-A/activate", HttpMethod.POST,
                headers("r", "REVIEWER", "f-auth-r"),
                activateBody(0, roster("\"d\"", "\"c\"", "\"s\""))).getStatusCode().value());
        // 职责角色也不能轮换
        assertEquals(403, exchange(
                "/api/experiments/F-AUTH/rotations/RK-A/activate", HttpMethod.POST,
                headers("d", "DATA_COLLECTOR", "f-auth-d"),
                activateBody(0, roster("\"d\"", "\"c\"", "\"s\""))).getStatusCode().value());
    }

    @Test
    void futureEffectiveAt_isUnprocessable() {
        createExperiment("F-FUT");
        register("F-FUT", "PA", "f-fut-alloc");
        long future = 1_700_000_000_000L + 60_000L;
        assertEquals(422, exchange(
                "/api/experiments/F-FUT/rotations/RK-F/activate", HttpMethod.POST,
                headers("lead", "COORDINATOR", "f-fut"),
                "{\"expectedExperimentVersion\":0,\"effectiveAt\":" + future
                        + ",\"roster\":" + roster("\"d\"", "\"c\"", "\"s\"") + "}")
                .getStatusCode().value());
    }

    @Test
    void concurrentStateChange_afterPreview_makesActivationFail() {
        createExperiment("F-CONC-CHG");
        register("F-CONC-CHG", "PA", "f-cc-alloc");
        // 先成功轮换到版本1
        rotate("F-CONC-CHG", "RK-CC1", 0, roster("\"d1\"", "\"c1\"", "\"s1\""), "f-cc-1", 200);
        // 客户端仍持版本0提交第二单：409（状态已变，基于旧预览不能激活）
        rotate("F-CONC-CHG", "RK-CC2", 0, roster("\"d2\"", "\"c2\"", "\"s2\""), "f-cc-2", 409);
        // 旧代次仍活动，版本停留在1，第二单整体回滚
        assertEquals(1, jdbc.queryForObject(
                "SELECT role_version FROM experiment WHERE id = 'F-CONC-CHG'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rotation_order WHERE experiment_id = 'F-CONC-CHG'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_generation WHERE experiment_id = 'F-CONC-CHG' "
                        + "AND status = 'ACTIVE'", Integer.class));
    }
}
