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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中心门禁与容量：暂停后拒绝新分配但既有盲态/容量/揭盲权限不变；
 * 累计分配达上限 422 且退组不回收容量；关闭须无待处理揭盲申请，关闭不可逆。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteGateAndCapacityTest extends AbstractBlindIntegrationTest {

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

    private void createExperiment(String id, int blockCount, String requestId) {
        assertEquals(201, exchange("/api/experiments/" + id, HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId),
                "{\"blockCount\":" + blockCount + "}").getStatusCode().value());
    }

    private void activateSite(String exp, String site, int cap, String tag) {
        assertEquals(201, exchange("/api/experiments/" + exp + "/sites/" + site,
                HttpMethod.POST, headers("c1", "COORDINATOR", tag + "-site"),
                "{\"targetCap\":" + cap + "}").getStatusCode().value());
        assertEquals(200, exchange(
                "/api/experiments/" + exp + "/sites/" + site + "/activation-confirmations",
                HttpMethod.POST, headers("m1", "COORDINATOR", tag + "-c1"),
                "{\"activationKey\":\"KEY-" + tag + "\"}").getStatusCode().value());
        assertEquals(200, exchange(
                "/api/experiments/" + exp + "/sites/" + site + "/activation-confirmations",
                HttpMethod.POST, headers("m2", "COORDINATOR", tag + "-c2"),
                "{\"activationKey\":\"KEY-" + tag + "\"}").getStatusCode().value());
    }

    private ResponseEntity<String> allocate(String exp, String site, String pid,
                                            String key, String requestId) {
        return exchange(
                "/api/experiments/" + exp + "/sites/" + site + "/participants/" + pid
                        + "/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", requestId),
                "{\"assignmentKey\":\"" + key + "\"}");
    }

    @Test
    void capReached_returns422_andWithdrawalDoesNotRecycleCapacity() throws Exception {
        createExperiment("EXP-G1", 2, "req-g1-exp");
        activateSite("EXP-G1", "SITE-1", 2, "g1");

        assertEquals(201, allocate("EXP-G1", "SITE-1", "P1", "ASG-1", "req-g1-a1")
                .getStatusCode().value());
        assertEquals(201, allocate("EXP-G1", "SITE-1", "P2", "ASG-2", "req-g1-a2")
                .getStatusCode().value());

        // 累计分配达到上限：新分配 422
        ResponseEntity<String> third = allocate("EXP-G1", "SITE-1", "P3", "ASG-3", "req-g1-a3");
        assertEquals(422, third.getStatusCode().value());

        // 查询：门禁原因为容量达上限
        JsonNode view = json(exchange("/api/experiments/EXP-G1/sites/SITE-1",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null));
        assertEquals(2, view.path("allocatedCount").asInt());
        assertEquals("SITE_CAP_REACHED", view.path("gateReason").asText());

        // 退组不回收容量：退出一人后新分配仍 422，累计分配数不变
        assertEquals(200, exchange("/api/experiments/EXP-G1/participants/P1/withdrawal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-g1-w1"), null)
                .getStatusCode().value());
        ResponseEntity<String> afterWithdraw = allocate("EXP-G1", "SITE-1", "P4", "ASG-4",
                "req-g1-a4");
        assertEquals(422, afterWithdraw.getStatusCode().value());
        JsonNode viewAfter = json(exchange("/api/experiments/EXP-G1/sites/SITE-1",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null));
        assertEquals(2, viewAfter.path("allocatedCount").asInt());
        assertEquals("SITE_CAP_REACHED", viewAfter.path("gateReason").asText());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation "
                        + "WHERE experiment_id = 'EXP-G1' AND site_code = 'SITE-1'",
                Integer.class));
    }

    @Test
    void suspend_blocksNewAllocation_keepsBlindStateAndUnblind_thenResumeNewGeneration()
            throws Exception {
        createExperiment("EXP-G2", 2, "req-g2-exp");
        activateSite("EXP-G2", "SITE-1", 5, "g2");
        ResponseEntity<String> alloc = allocate("EXP-G2", "SITE-1", "P1", "ASG-1", "req-g2-a1");
        assertEquals(201, alloc.getStatusCode().value());
        String blindCode = json(alloc).path("blindCode").asText();

        // 暂停：仅 ACTIVE 可暂停
        assertEquals(200, exchange("/api/experiments/EXP-G2/sites/SITE-1/suspension",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-g2-sus"), null)
                .getStatusCode().value());
        assertEquals(409, exchange("/api/experiments/EXP-G2/sites/SITE-1/suspension",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-g2-sus2"), null)
                .getStatusCode().value());

        // 暂停后不接受新分配
        assertEquals(409, allocate("EXP-G2", "SITE-1", "P2", "ASG-2", "req-g2-a2")
                .getStatusCode().value());
        JsonNode suspended = json(exchange("/api/experiments/EXP-G2/sites/SITE-1",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null));
        assertEquals("SUSPENDED", suspended.path("status").asText());
        assertEquals("SITE_SUSPENDED", suspended.path("gateReason").asText());

        // 既有受试者盲态不变：普通查询仍返回原盲码与区组
        JsonNode query = json(exchange("/api/experiments/EXP-G2/participants/P1",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null));
        assertEquals(blindCode, query.path("blindCode").asText());
        assertEquals("ASSIGNED", query.path("status").asText());

        // 揭盲权限不变：暂停期间可申请并批准揭盲
        ResponseEntity<String> apply = exchange(
                "/api/experiments/EXP-G2/participants/P1/unblind-requests",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-g2-ub1"),
                "{\"reason\":\"疑似不良事件需核对处理\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("r1", "REVIEWER", "req-g2-ub1-ok"), null)
                .getStatusCode().value());
        JsonNode result = json(exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null));
        assertTrue(result.path("treatment").asText().matches("[AB]"));

        // 恢复须再次双人确认并产生新激活代次
        assertEquals(200, exchange(
                "/api/experiments/EXP-G2/sites/SITE-1/activation-confirmations",
                HttpMethod.POST, headers("m3", "COORDINATOR", "req-g2-r1"),
                "{\"activationKey\":\"KEY-RESUME\"}").getStatusCode().value());
        // 单人确认不足以恢复
        assertEquals("SUSPENDED", jdbc.queryForObject(
                "SELECT status FROM site "
                        + "WHERE experiment_id = 'EXP-G2' AND site_code = 'SITE-1'",
                String.class));
        assertEquals(200, exchange(
                "/api/experiments/EXP-G2/sites/SITE-1/activation-confirmations",
                HttpMethod.POST, headers("m4", "COORDINATOR", "req-g2-r2"),
                "{\"activationKey\":\"KEY-RESUME\"}").getStatusCode().value());

        JsonNode resumed = json(exchange("/api/experiments/EXP-G2/sites/SITE-1",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null));
        assertEquals("ACTIVE", resumed.path("status").asText());
        assertEquals(2, resumed.path("generation").asInt());
        assertEquals(2, resumed.path("activations").size());
        assertEquals("KEY-RESUME", resumed.path("activations").get(1)
                .path("activationKey").asText());
        assertEquals("m3", resumed.path("activations").get(1).path("firstActor").asText());
        assertEquals("m4", resumed.path("activations").get(1).path("secondActor").asText());

        // 恢复后允许新分配，且区组席位顺序不受暂停影响（P2 领到第 2 席）
        ResponseEntity<String> afterResume = allocate("EXP-G2", "SITE-1", "P2", "ASG-2",
                "req-g2-a3");
        assertEquals(201, afterResume.getStatusCode().value());
        assertEquals(1, json(afterResume).path("blockNo").asInt());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation "
                        + "WHERE experiment_id = 'EXP-G2' AND site_code = 'SITE-1'",
                Integer.class));
    }

    @Test
    void close_requiresNoPendingUnblind_andIsIrreversible() throws Exception {
        createExperiment("EXP-G3", 2, "req-g3-exp");
        activateSite("EXP-G3", "SITE-1", 5, "g3");
        assertEquals(201, allocate("EXP-G3", "SITE-1", "P1", "ASG-1", "req-g3-a1")
                .getStatusCode().value());

        // 存在待处理揭盲申请：关闭 422
        ResponseEntity<String> apply = exchange(
                "/api/experiments/EXP-G3/participants/P1/unblind-requests",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-g3-ub1"),
                "{\"reason\":\"关闭前核查处理分配\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        ResponseEntity<String> closeWithPending =
                exchange("/api/experiments/EXP-G3/sites/SITE-1/closure",
                        HttpMethod.POST, headers("c1", "COORDINATOR", "req-g3-close1"), null);
        assertEquals(422, closeWithPending.getStatusCode().value());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM site "
                        + "WHERE experiment_id = 'EXP-G3' AND site_code = 'SITE-1'",
                String.class));

        // 批准后无待审申请：关闭成功
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("r1", "REVIEWER", "req-g3-ub1-ok"), null)
                .getStatusCode().value());
        ResponseEntity<String> closed = exchange("/api/experiments/EXP-G3/sites/SITE-1/closure",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-g3-close2"), null);
        assertEquals(200, closed.getStatusCode().value());
        JsonNode closedBody = json(closed);
        assertEquals("CLOSED", closedBody.path("status").asText());
        assertEquals("SITE_CLOSED", closedBody.path("gateReason").asText());

        // 关闭后不可恢复：确认、暂停、再关闭、分配均 409
        assertEquals(409, exchange(
                "/api/experiments/EXP-G3/sites/SITE-1/activation-confirmations",
                HttpMethod.POST, headers("m1", "COORDINATOR", "req-g3-rc1"),
                "{\"activationKey\":\"KEY-NEW\"}").getStatusCode().value());
        assertEquals(409, exchange("/api/experiments/EXP-G3/sites/SITE-1/suspension",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-g3-sus"), null)
                .getStatusCode().value());
        assertEquals(409, exchange("/api/experiments/EXP-G3/sites/SITE-1/closure",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-g3-close3"), null)
                .getStatusCode().value());
        assertEquals(409, allocate("EXP-G3", "SITE-1", "P2", "ASG-2", "req-g3-a2")
                .getStatusCode().value());
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM site "
                        + "WHERE experiment_id = 'EXP-G3' AND site_code = 'SITE-1'",
                String.class));
    }

    @Test
    void assignmentKey_replaysSameResult_andRejectsDifferentParams() throws Exception {
        createExperiment("EXP-G4", 2, "req-g4-exp");
        activateSite("EXP-G4", "SITE-1", 5, "g4");

        ResponseEntity<String> first = allocate("EXP-G4", "SITE-1", "P1", "ASG-1", "req-g4-a1");
        assertEquals(201, first.getStatusCode().value());

        // 同 assignmentKey、同受试者、同操作者（新 requestId）：业务键重放首次响应
        ResponseEntity<String> replay = allocate("EXP-G4", "SITE-1", "P1", "ASG-1", "req-g4-a2");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(json(first).path("blindCode").asText(),
                json(replay).path("blindCode").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation "
                        + "WHERE experiment_id = 'EXP-G4' AND site_code = 'SITE-1'",
                Integer.class));

        // 同 assignmentKey 绑定其他受试者：409
        assertEquals(409, allocate("EXP-G4", "SITE-1", "P2", "ASG-1", "req-g4-a3")
                .getStatusCode().value());
        // 同 assignmentKey 绑定其他操作者：409
        ResponseEntity<String> otherActor = exchange(
                "/api/experiments/EXP-G4/sites/SITE-1/participants/P1/allocations",
                HttpMethod.POST, headers("c2", "COORDINATOR", "req-g4-a4"),
                "{\"assignmentKey\":\"ASG-1\"}");
        assertEquals(409, otherActor.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation "
                        + "WHERE experiment_id = 'EXP-G4' AND site_code = 'SITE-1'",
                Integer.class));

        // 失败不占键：中心暂停期间分配失败，恢复后同一 assignmentKey 可成功
        assertEquals(200, exchange("/api/experiments/EXP-G4/sites/SITE-1/suspension",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-g4-sus"), null)
                .getStatusCode().value());
        assertEquals(409, allocate("EXP-G4", "SITE-1", "P2", "ASG-2", "req-g4-a5")
                .getStatusCode().value());
        assertEquals(200, exchange(
                "/api/experiments/EXP-G4/sites/SITE-1/activation-confirmations",
                HttpMethod.POST, headers("m1", "COORDINATOR", "req-g4-r1"),
                "{\"activationKey\":\"KEY-R\"}").getStatusCode().value());
        assertEquals(200, exchange(
                "/api/experiments/EXP-G4/sites/SITE-1/activation-confirmations",
                HttpMethod.POST, headers("m2", "COORDINATOR", "req-g4-r2"),
                "{\"activationKey\":\"KEY-R\"}").getStatusCode().value());
        assertEquals(201, allocate("EXP-G4", "SITE-1", "P2", "ASG-2", "req-g4-a6")
                .getStatusCode().value());
        // 分配记录绑定中心代次（恢复后为第 2 代）
        assertEquals(2, jdbc.queryForObject(
                "SELECT site_generation FROM allocation "
                        + "WHERE experiment_id = 'EXP-G4' AND participant_id = 'P2'",
                Integer.class));
        assertFalse(json(exchange("/api/experiments/EXP-G4/sites/SITE-1",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null))
                .path("activations").isEmpty());
    }
}
