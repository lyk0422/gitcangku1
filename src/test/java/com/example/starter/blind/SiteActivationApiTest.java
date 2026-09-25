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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 中心激活门禁主流程与失败分支：
 * 双人激活、未激活门禁、暂停持续门禁、容量不回收、关闭条件与终态、查询视图。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteActivationApiTest extends AbstractBlindIntegrationTest {

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
        assertNotNull(response.getBody());
        return mapper.readTree(response.getBody());
    }

    private void createExperiment(String expId, int blockCount, String requestId) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId),
                "{\"blockCount\":" + blockCount + "}").getStatusCode().value());
    }

    private void createSite(String expId, String siteCode, int limit, String requestId) {
        assertEquals(201, exchange("/api/experiments/" + expId + "/sites/" + siteCode,
                HttpMethod.POST, headers("m1", "UNBLINDED_MANAGER", requestId),
                "{\"targetEnrollmentLimit\":" + limit + "}").getStatusCode().value());
    }

    private void activateSite(String expId, String siteCode, String key, String requestIdPrefix)
            throws Exception {
        ResponseEntity<String> first = exchange(
                "/api/experiments/" + expId + "/sites/" + siteCode + "/activation-confirmations",
                HttpMethod.POST, headers("m1", "UNBLINDED_MANAGER", requestIdPrefix + "-c1"),
                "{\"activationKey\":\"" + key + "\"}");
        assertEquals(202, first.getStatusCode().value());
        ResponseEntity<String> second = exchange(
                "/api/experiments/" + expId + "/sites/" + siteCode + "/activation-confirmations",
                HttpMethod.POST, headers("m2", "UNBLINDED_MANAGER", requestIdPrefix + "-c2"),
                "{\"activationKey\":\"" + key + "\"}");
        assertEquals(200, second.getStatusCode().value());
        assertEquals("m1", json(second).path("firstConfirmerActor").asText());
        assertEquals("m2", json(second).path("secondConfirmerActor").asText());
    }

    private ResponseEntity<String> allocate(String expId, String siteCode, String pid,
                                            String requestId) {
        return exchange("/api/experiments/" + expId + "/sites/" + siteCode
                        + "/participants/" + pid + "/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", requestId), null);
    }

    // ---------------- 创建与查询 ----------------

    @Test
    void createSite_initialStateAndGateReason_andValidationFailures() throws Exception {
        createExperiment("SE-1", 2, "se1-create");

        ResponseEntity<String> created = exchange("/api/experiments/SE-1/sites/S1",
                HttpMethod.POST, headers("m1", "UNBLINDED_MANAGER", "se1-site"),
                "{\"targetEnrollmentLimit\":5}");
        assertEquals(201, created.getStatusCode().value());
        JsonNode body = json(created);
        assertEquals("S1", body.path("siteCode").asText());
        assertEquals("INACTIVE", body.path("status").asText());
        assertEquals(0, body.path("generation").asInt());
        assertEquals(5, body.path("targetEnrollmentLimit").asInt());
        assertEquals(0, body.path("cumulativeAssignments").asInt());
        assertEquals(5, body.path("remainingCapacity").asInt());
        assertEquals("SITE_NOT_ACTIVE", body.path("gateReason").asText());
        assertFalse(body.has("activationKey"), "视图不得回显激活密钥");

        // 查询视图一致
        ResponseEntity<String> view = exchange("/api/experiments/SE-1/sites/S1",
                HttpMethod.GET, headers("r1", "REVIEWER", null), null);
        assertEquals(200, view.getStatusCode().value());
        assertEquals("INACTIVE", json(view).path("status").asText());

        // 重复创建 409
        assertEquals(409, exchange("/api/experiments/SE-1/sites/S1", HttpMethod.POST,
                headers("m1", "UNBLINDED_MANAGER", "se1-site-dup"),
                "{\"targetEnrollmentLimit\":3}").getStatusCode().value());
        // 负数上限 400；缺实验 404；越权角色 403
        assertEquals(400, exchange("/api/experiments/SE-1/sites/S2", HttpMethod.POST,
                headers("m1", "UNBLINDED_MANAGER", "se1-site-neg"),
                "{\"targetEnrollmentLimit\":-1}").getStatusCode().value());
        assertEquals(404, exchange("/api/experiments/NOPE/sites/S1", HttpMethod.POST,
                headers("m1", "UNBLINDED_MANAGER", "se1-site-noexp"),
                "{\"targetEnrollmentLimit\":1}").getStatusCode().value());
        assertEquals(403, exchange("/api/experiments/SE-1/sites/S3", HttpMethod.POST,
                headers("c1", "COORDINATOR", "se1-site-coord"),
                "{\"targetEnrollmentLimit\":1}").getStatusCode().value());
        assertEquals(403, exchange("/api/experiments/SE-1/sites/S3", HttpMethod.POST,
                headers("r1", "REVIEWER", "se1-site-rev"),
                "{\"targetEnrollmentLimit\":1}").getStatusCode().value());
        // 查询不存在的中心 404
        assertEquals(404, exchange("/api/experiments/SE-1/sites/NOPE", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null).getStatusCode().value());
    }

    // ---------------- 双人激活 ----------------

    @Test
    void dualConfirmationActivation_fullFlowAndFailureBranches() throws Exception {
        createExperiment("SE-2", 2, "se2-create");
        createSite("SE-2", "S1", 3, "se2-site");

        // 未激活中心登记：409，且不产生盲码/分配
        ResponseEntity<String> blocked = allocate("SE-2", "S1", "P1", "se2-alloc-blocked");
        assertEquals(409, blocked.getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'SE-2'", Integer.class));

        // 协调员/审阅员不能确认激活
        assertEquals(403, exchange(
                "/api/experiments/SE-2/sites/S1/activation-confirmations", HttpMethod.POST,
                headers("c1", "COORDINATOR", "se2-conf-coord"),
                "{\"activationKey\":\"K1\"}").getStatusCode().value());

        // 首确认 202 暂存
        ResponseEntity<String> first = exchange(
                "/api/experiments/SE-2/sites/S1/activation-confirmations", HttpMethod.POST,
                headers("m1", "UNBLINDED_MANAGER", "se2-conf-1"),
                "{\"activationKey\":\"K1\"}");
        assertEquals(202, first.getStatusCode().value());
        JsonNode pending = json(first);
        assertEquals("AWAITING_SECOND_CONFIRMATION", pending.path("state").asText());
        assertEquals("m1", pending.path("firstConfirmerActor").asText());
        assertEquals(0, pending.path("generation").asInt());
        assertFalse(pending.has("activationKey"));

        // 中心仍未激活
        assertEquals("INACTIVE", json(exchange("/api/experiments/SE-2/sites/S1",
                HttpMethod.GET, headers("m1", "UNBLINDED_MANAGER", null), null))
                .path("status").asText());

        // 同人同键重提：幂等返回暂存视图（不同 requestId）
        ResponseEntity<String> sameAgain = exchange(
                "/api/experiments/SE-2/sites/S1/activation-confirmations", HttpMethod.POST,
                headers("m1", "UNBLINDED_MANAGER", "se2-conf-1b"),
                "{\"activationKey\":\"K1\"}");
        assertEquals(202, sameAgain.getStatusCode().value());
        assertEquals(pending.path("createdAt").asLong(), json(sameAgain).path("createdAt").asLong());

        // 同人异键：409
        assertEquals(409, exchange(
                "/api/experiments/SE-2/sites/S1/activation-confirmations", HttpMethod.POST,
                headers("m1", "UNBLINDED_MANAGER", "se2-conf-1c"),
                "{\"activationKey\":\"K-OTHER\"}").getStatusCode().value());

        // 第二人异键：409
        assertEquals(409, exchange(
                "/api/experiments/SE-2/sites/S1/activation-confirmations", HttpMethod.POST,
                headers("m2", "UNBLINDED_MANAGER", "se2-conf-2bad"),
                "{\"activationKey\":\"K-OTHER\"}").getStatusCode().value());

        // 第二人同键：200，中心 ACTIVE，代次 1
        clock.advance(3_000L);
        ResponseEntity<String> second = exchange(
                "/api/experiments/SE-2/sites/S1/activation-confirmations", HttpMethod.POST,
                headers("m2", "UNBLINDED_MANAGER", "se2-conf-2"),
                "{\"activationKey\":\"K1\"}");
        assertEquals(200, second.getStatusCode().value());
        JsonNode record = json(second);
        assertEquals(1, record.path("generation").asInt());
        assertEquals("m1", record.path("firstConfirmerActor").asText());
        assertEquals("m2", record.path("secondConfirmerActor").asText());
        assertEquals(3, record.path("targetEnrollmentLimit").asInt());
        assertEquals(1_700_000_003_000L, record.path("activatedAt").asLong());
        assertFalse(record.has("activationKey"));

        // 暂存已清除，库内恰一条不可变记录
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_pending WHERE experiment_id = 'SE-2'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_record WHERE experiment_id = 'SE-2' "
                        + "AND site_code = 'S1' AND generation = 1", Integer.class));

        // 中心视图：ACTIVE、代次 1、门禁放行
        JsonNode siteView = json(exchange("/api/experiments/SE-2/sites/S1",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null));
        assertEquals("ACTIVE", siteView.path("status").asText());
        assertEquals(1, siteView.path("generation").asInt());
        assertEquals("ALLOWED", siteView.path("gateReason").asText());

        // 激活记录查询
        ResponseEntity<String> records = exchange(
                "/api/experiments/SE-2/sites/S1/activation-records", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        assertEquals(200, records.getStatusCode().value());
        JsonNode list = json(records);
        assertEquals(1, list.size());
        assertEquals("m1", list.get(0).path("firstConfirmerActor").asText());
        assertEquals("m2", list.get(0).path("secondConfirmerActor").asText());
        assertFalse(list.get(0).has("activationKey"));

        // 已激活中心再次确认：409
        assertEquals(409, exchange(
                "/api/experiments/SE-2/sites/S1/activation-confirmations", HttpMethod.POST,
                headers("m3", "UNBLINDED_MANAGER", "se2-conf-3"),
                "{\"activationKey\":\"K1\"}").getStatusCode().value());

        // 激活后可以登记
        assertEquals(201, allocate("SE-2", "S1", "P1", "se2-alloc-1").getStatusCode().value());
    }

    @Test
    void activationRequiresPositiveLimit_secondConfirm422_andKeyNotConsumed() throws Exception {
        createExperiment("SE-3", 2, "se3-create");
        createSite("SE-3", "S1", 0, "se3-site");

        assertEquals(202, exchange(
                "/api/experiments/SE-3/sites/S1/activation-confirmations", HttpMethod.POST,
                headers("m1", "UNBLINDED_MANAGER", "se3-conf-1"),
                "{\"activationKey\":\"K0\"}").getStatusCode().value());
        // 第二人确认时上限为 0：422，中心保持未激活
        assertEquals(422, exchange(
                "/api/experiments/SE-3/sites/S1/activation-confirmations", HttpMethod.POST,
                headers("m2", "UNBLINDED_MANAGER", "se3-conf-2"),
                "{\"activationKey\":\"K0\"}").getStatusCode().value());
        assertEquals("INACTIVE", json(exchange("/api/experiments/SE-3/sites/S1",
                HttpMethod.GET, headers("m1", "UNBLINDED_MANAGER", null), null))
                .path("status").asText());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_record WHERE experiment_id = 'SE-3'",
                Integer.class));
        // 失败不占传输键：同 requestId 可重用于合法请求
        assertEquals(201, exchange("/api/experiments/SE-3/sites/S9", HttpMethod.POST,
                headers("m1", "UNBLINDED_MANAGER", "se3-conf-2"),
                "{\"targetEnrollmentLimit\":1}").getStatusCode().value());
    }

    // ---------------- 暂停 / 恢复 ----------------

    @Test
    void suspendKeepsBlindStateAndUnblind_resumeCreatesNewGeneration() throws Exception {
        createExperiment("SE-4", 2, "se4-create");
        createSite("SE-4", "S1", 5, "se4-site");
        activateSite("SE-4", "S1", "K1", "se4-act");

        assertEquals(201, allocate("SE-4", "S1", "P1", "se4-alloc-1").getStatusCode().value());
        String blindCode = json(exchange(
                "/api/experiments/SE-4/participants/P1", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null)).path("blindCode").asText();

        // 暂停：仅 ACTIVE 可暂停
        ResponseEntity<String> suspended = exchange("/api/experiments/SE-4/sites/S1/suspension",
                HttpMethod.POST, headers("m1", "UNBLINDED_MANAGER", "se4-suspend"), null);
        assertEquals(200, suspended.getStatusCode().value());
        assertEquals("SUSPENDED", json(suspended).path("status").asText());
        assertEquals(1, json(suspended).path("generation").asInt());
        assertEquals("SITE_SUSPENDED", json(suspended).path("gateReason").asText());

        // 暂停后拒绝新分配 409；既有受试者盲态不变
        assertEquals(409, allocate("SE-4", "S1", "P2", "se4-alloc-2").getStatusCode().value());
        JsonNode p1 = json(exchange("/api/experiments/SE-4/participants/P1", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null));
        assertEquals(blindCode, p1.path("blindCode").asText());
        assertEquals("ASSIGNED", p1.path("status").asText());

        // 暂停期间揭盲权限不变：可申请、可批准、可查结果
        ResponseEntity<String> apply = exchange(
                "/api/experiments/SE-4/participants/P1/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "se4-ub-apply"), "{\"reason\":\"暂停期间核对\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("r2", "REVIEWER", "se4-ub-approve"), null)
                .getStatusCode().value());
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null)
                .getStatusCode().value());

        // 重复暂停 409
        assertEquals(409, exchange("/api/experiments/SE-4/sites/S1/suspension",
                HttpMethod.POST, headers("m1", "UNBLINDED_MANAGER", "se4-suspend-2"), null)
                .getStatusCode().value());

        // 恢复：再次双人确认，产生新代次 2
        assertEquals(202, exchange(
                "/api/experiments/SE-4/sites/S1/activation-confirmations", HttpMethod.POST,
                headers("m3", "UNBLINDED_MANAGER", "se4-react-1"),
                "{\"activationKey\":\"K2\"}").getStatusCode().value());
        ResponseEntity<String> resumed = exchange(
                "/api/experiments/SE-4/sites/S1/activation-confirmations", HttpMethod.POST,
                headers("m1", "UNBLINDED_MANAGER", "se4-react-2"),
                "{\"activationKey\":\"K2\"}");
        assertEquals(200, resumed.getStatusCode().value());
        assertEquals(2, json(resumed).path("generation").asInt());
        assertEquals("m3", json(resumed).path("firstConfirmerActor").asText());
        assertEquals("m1", json(resumed).path("secondConfirmerActor").asText());

        // 两代激活记录均可查
        JsonNode records = json(exchange(
                "/api/experiments/SE-4/sites/S1/activation-records", HttpMethod.GET,
                headers("m1", "UNBLINDED_MANAGER", null), null));
        assertEquals(2, records.size());
        assertEquals(1, records.get(0).path("generation").asInt());
        assertEquals(2, records.get(1).path("generation").asInt());

        // 恢复后接受新分配；既有受试者盲态仍不变
        assertEquals(201, allocate("SE-4", "S1", "P2", "se4-alloc-3").getStatusCode().value());
        assertEquals(blindCode, json(exchange("/api/experiments/SE-4/participants/P1",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null))
                .path("blindCode").asText());
    }

    // ---------------- 容量不回收 ----------------

    @Test
    void capacityLimitReached_422_andWithdrawalDoesNotReclaim() throws Exception {
        createExperiment("SE-5", 2, "se5-create");
        createSite("SE-5", "S1", 2, "se5-site");
        activateSite("SE-5", "S1", "K1", "se5-act");

        assertEquals(201, allocate("SE-5", "S1", "P1", "se5-alloc-1").getStatusCode().value());
        assertEquals(201, allocate("SE-5", "S1", "P2", "se5-alloc-2").getStatusCode().value());

        // 达到上限：422
        assertEquals(422, allocate("SE-5", "S1", "P3", "se5-alloc-3").getStatusCode().value());
        JsonNode view = json(exchange("/api/experiments/SE-5/sites/S1", HttpMethod.GET,
                headers("m1", "UNBLINDED_MANAGER", null), null));
        assertEquals(2, view.path("cumulativeAssignments").asInt());
        assertEquals(0, view.path("remainingCapacity").asInt());
        assertEquals("ENROLLMENT_LIMIT_REACHED", view.path("gateReason").asText());

        // 退组不回收容量：退出一人后新分配仍 422
        assertEquals(200, exchange("/api/experiments/SE-5/participants/P1/withdrawal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "se5-withdraw"), null)
                .getStatusCode().value());
        assertEquals(422, allocate("SE-5", "S1", "P3", "se5-alloc-4").getStatusCode().value());
        JsonNode after = json(exchange("/api/experiments/SE-5/sites/S1", HttpMethod.GET,
                headers("m1", "UNBLINDED_MANAGER", null), null));
        assertEquals(2, after.path("cumulativeAssignments").asInt());
        assertEquals(0, after.path("remainingCapacity").asInt());
    }

    // ---------------- 关闭 ----------------

    @Test
    void closeRequiresNoPendingUnblind_andClosedIsTerminal() throws Exception {
        createExperiment("SE-6", 2, "se6-create");
        createSite("SE-6", "S1", 5, "se6-site");
        activateSite("SE-6", "S1", "K1", "se6-act");
        assertEquals(201, allocate("SE-6", "S1", "P1", "se6-alloc-1").getStatusCode().value());

        // 存在待审揭盲申请：关闭 422
        ResponseEntity<String> apply = exchange(
                "/api/experiments/SE-6/participants/P1/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "se6-ub-apply"), "{\"reason\":\"关闭前核对\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        assertEquals(422, exchange("/api/experiments/SE-6/sites/S1/closure",
                HttpMethod.POST, headers("m1", "UNBLINDED_MANAGER", "se6-close-1"), null)
                .getStatusCode().value());
        // 中心仍 ACTIVE
        assertEquals("ACTIVE", json(exchange("/api/experiments/SE-6/sites/S1",
                HttpMethod.GET, headers("m1", "UNBLINDED_MANAGER", null), null))
                .path("status").asText());

        // 批准后关闭成功
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("r2", "REVIEWER", "se6-ub-approve"), null)
                .getStatusCode().value());
        ResponseEntity<String> closed = exchange("/api/experiments/SE-6/sites/S1/closure",
                HttpMethod.POST, headers("m1", "UNBLINDED_MANAGER", "se6-close-2"), null);
        assertEquals(200, closed.getStatusCode().value());
        assertEquals("CLOSED", json(closed).path("status").asText());
        assertEquals("SITE_CLOSED", json(closed).path("gateReason").asText());

        // 关闭后终态：分配 409、确认 409、暂停 409、重复关闭 409
        assertEquals(409, allocate("SE-6", "S1", "P2", "se6-alloc-2").getStatusCode().value());
        assertEquals(409, exchange(
                "/api/experiments/SE-6/sites/S1/activation-confirmations", HttpMethod.POST,
                headers("m1", "UNBLINDED_MANAGER", "se6-conf-after"),
                "{\"activationKey\":\"K9\"}").getStatusCode().value());
        assertEquals(409, exchange("/api/experiments/SE-6/sites/S1/suspension",
                HttpMethod.POST, headers("m1", "UNBLINDED_MANAGER", "se6-suspend-after"), null)
                .getStatusCode().value());
        assertEquals(409, exchange("/api/experiments/SE-6/sites/S1/closure",
                HttpMethod.POST, headers("m1", "UNBLINDED_MANAGER", "se6-close-3"), null)
                .getStatusCode().value());

        // 已批准揭盲结果不受关闭影响；激活记录仍可查
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null)
                .getStatusCode().value());
        assertEquals(1, json(exchange("/api/experiments/SE-6/sites/S1/activation-records",
                HttpMethod.GET, headers("m1", "UNBLINDED_MANAGER", null), null)).size());
    }

    // ---------------- 幂等 ----------------

    @Test
    void siteWrites_areIdempotent_replayFirstResponse_andFailureDoesNotConsumeKey()
            throws Exception {
        createExperiment("SE-7", 2, "se7-create");
        createSite("SE-7", "S1", 2, "se7-site");

        // 失败不占键：未激活中心登记 409 后，同 requestId 在激活后可成功
        assertEquals(409, allocate("SE-7", "S1", "P1", "se7-alloc-key").getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'se7-alloc-key'",
                Integer.class));

        // 首确认同传输键重放
        ResponseEntity<String> first1 = exchange(
                "/api/experiments/SE-7/sites/S1/activation-confirmations", HttpMethod.POST,
                headers("m1", "UNBLINDED_MANAGER", "se7-conf-key"),
                "{\"activationKey\":\"K1\"}");
        assertEquals(202, first1.getStatusCode().value());
        ResponseEntity<String> firstReplay = exchange(
                "/api/experiments/SE-7/sites/S1/activation-confirmations", HttpMethod.POST,
                headers("m1", "UNBLINDED_MANAGER", "se7-conf-key"),
                "{\"activationKey\":\"K1\"}");
        assertEquals(202, firstReplay.getStatusCode().value());
        assertEquals(first1.getBody(), firstReplay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_pending WHERE experiment_id = 'SE-7'",
                Integer.class));

        // 第二人确认同传输键重放：不产生第二条激活记录
        ResponseEntity<String> second1 = exchange(
                "/api/experiments/SE-7/sites/S1/activation-confirmations", HttpMethod.POST,
                headers("m2", "UNBLINDED_MANAGER", "se7-conf-key-2"),
                "{\"activationKey\":\"K1\"}");
        assertEquals(200, second1.getStatusCode().value());
        ResponseEntity<String> secondReplay = exchange(
                "/api/experiments/SE-7/sites/S1/activation-confirmations", HttpMethod.POST,
                headers("m2", "UNBLINDED_MANAGER", "se7-conf-key-2"),
                "{\"activationKey\":\"K1\"}");
        assertEquals(200, secondReplay.getStatusCode().value());
        assertEquals(second1.getBody(), secondReplay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_record WHERE experiment_id = 'SE-7'",
                Integer.class));

        // 登记同传输键重放：同键成功重放首次响应
        ResponseEntity<String> alloc1 = allocate("SE-7", "S1", "P1", "se7-alloc-key");
        assertEquals(201, alloc1.getStatusCode().value());
        ResponseEntity<String> allocReplay = allocate("SE-7", "S1", "P1", "se7-alloc-key");
        assertEquals(201, allocReplay.getStatusCode().value());
        assertEquals(alloc1.getBody(), allocReplay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'SE-7' "
                        + "AND site_code = 'S1'", Integer.class));

        // 暂停同传输键重放
        ResponseEntity<String> suspend1 = exchange("/api/experiments/SE-7/sites/S1/suspension",
                HttpMethod.POST, headers("m1", "UNBLINDED_MANAGER", "se7-suspend-key"), null);
        assertEquals(200, suspend1.getStatusCode().value());
        ResponseEntity<String> suspendReplay = exchange(
                "/api/experiments/SE-7/sites/S1/suspension", HttpMethod.POST,
                headers("m1", "UNBLINDED_MANAGER", "se7-suspend-key"), null);
        assertEquals(200, suspendReplay.getStatusCode().value());
        assertEquals(suspend1.getBody(), suspendReplay.getBody());
        assertEquals("SUSPENDED", jdbc.queryForObject(
                "SELECT status FROM site WHERE experiment_id = 'SE-7' AND site_code = 'S1'",
                String.class));
    }
}
