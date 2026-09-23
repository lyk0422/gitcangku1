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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 泄露传播、污染闭包版本与隔离门禁主流程及失败分支（真实 H2 数据库）：
 * 批准揭盲种子闭包、直接/下游披露传播、重复边不新增、未获知拒绝、
 * 审核人闭包门禁、隔离单发起/确认/冻结与新版本重开；所有查询不返回处理代码。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContaminationFlowTest extends AbstractBlindIntegrationTest {

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

    /** 创建实验并登记参与者；participants 可变参数，按顺序占席。 */
    private void setupExperiment(String expId, String... participants) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("coord-1", "COORDINATOR", expId.toLowerCase() + "-create"),
                "{\"blockCount\":4}").getStatusCode().value());
        int i = 1;
        for (String pid : participants) {
            assertEquals(201, exchange(
                    "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                    HttpMethod.POST,
                    headers("coord-1", "COORDINATOR", expId.toLowerCase() + "-alloc-" + i),
                    null).getStatusCode().value());
            i++;
        }
    }

    /** coord-1 申请并由 rev-2 批准对 participant 的揭盲，返回揭盲申请编号。 */
    private String approveUnblind(String expId, String participant, String prefix) throws Exception {
        ResponseEntity<String> apply = exchange(
                "/api/experiments/" + expId + "/participants/" + participant + "/unblind-requests",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", prefix + "-apply"),
                "{\"reason\":\"合成测试核对处理代码\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-2", "REVIEWER", prefix + "-approve"), null)
                .getStatusCode().value());
        return ubId;
    }

    private JsonNode getClosure(String expId, String participant) throws Exception {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/participants/" + participant + "/contamination",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, resp.getStatusCode().value());
        return json(resp);
    }

    @Test
    void approvalSeedsClosure_andDisclosurePropagatesDownstream() throws Exception {
        setupExperiment("C-1", "PA", "PB");
        String ubId = approveUnblind("C-1", "PA", "c1");

        // 批准即种子闭包：仅申请人 coord-1，版本 1 OPEN，不含处理代码
        JsonNode seed = getClosure("C-1", "PA");
        assertEquals(1, seed.path("version").asInt());
        assertEquals("OPEN", seed.path("status").asText());
        assertEquals("[\"coord-1\"]", seed.path("actors").toString());
        assertFalse(seed.has("treatment"), "闭包视图不得包含处理代码");

        // coord-1 直接披露给 op-3
        ResponseEntity<String> direct = exchange(
                "/api/experiments/C-1/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "c1-disc-1"),
                "{\"exposureKey\":\"EK-1\",\"receiverActors\":[\"op-3\"],"
                        + "\"participantIds\":[\"PA\"]}");
        assertEquals(201, direct.getStatusCode().value());
        JsonNode directBody = json(direct);
        assertEquals(1, directBody.path("newEdges").asInt());
        assertFalse(directBody.has("treatment"));

        JsonNode v2 = getClosure("C-1", "PA");
        assertEquals(2, v2.path("version").asInt());
        assertEquals("[\"coord-1\",\"op-3\"]", v2.path("actors").toString());

        // op-3 向下游 op-4、op-5 披露（接收人可继续登记）
        ResponseEntity<String> downstream = exchange(
                "/api/experiments/C-1/disclosures", HttpMethod.POST,
                headers("op-3", "COORDINATOR", "c1-disc-2"),
                "{\"exposureKey\":\"EK-2\",\"receiverActors\":[\"op-5\",\"op-4\"],"
                        + "\"participantIds\":[\"PA\"]}");
        assertEquals(201, downstream.getStatusCode().value());
        assertEquals(2, json(downstream).path("newEdges").asInt());

        JsonNode v3 = getClosure("C-1", "PA");
        assertEquals(3, v3.path("version").asInt());
        assertEquals("[\"coord-1\",\"op-3\",\"op-4\",\"op-5\"]", v3.path("actors").toString());

        // 重复边不新增：op-3 再次向 op-4（已在闭包）披露，newEdges=0、版本不变
        ResponseEntity<String> dup = exchange(
                "/api/experiments/C-1/disclosures", HttpMethod.POST,
                headers("op-3", "COORDINATOR", "c1-disc-3"),
                "{\"exposureKey\":\"EK-3\",\"receiverActors\":[\"op-4\",\"op-4\"],"
                        + "\"participantIds\":[\"PA\"]}");
        assertEquals(201, dup.getStatusCode().value());
        assertEquals(0, json(dup).path("newEdges").asInt());
        assertEquals(3, getClosure("C-1", "PA").path("version").asInt());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM contamination_edge WHERE experiment_id='C-1' "
                        + "AND participant_id='PA' AND actor_id='op-4'", Integer.class),
                "重复边不得新增第二条");

        // 版本历史：3 个版本
        ResponseEntity<String> versions = exchange(
                "/api/experiments/C-1/participants/PA/contamination/versions",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, versions.getStatusCode().value());
        assertEquals(3, json(versions).size());
        assertFalse(versions.getBody().contains("treatment"));

        // 未获知 PB 的 op-3 登记 [PB, PA]：403；该请求先为 PA 插入的边/版本必须整体回滚
        ResponseEntity<String> unknown = exchange(
                "/api/experiments/C-1/disclosures", HttpMethod.POST,
                headers("op-3", "COORDINATOR", "c1-disc-x"),
                "{\"exposureKey\":\"EK-X\",\"receiverActors\":[\"op-9\"],"
                        + "\"participantIds\":[\"PB\",\"PA\"]}");
        assertEquals(403, unknown.getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM contamination_edge WHERE exposure_key='EK-X'",
                Integer.class), "失败请求不得残留边");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_event WHERE exposure_key='EK-X'",
                Integer.class), "失败请求不得残留事件");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM contamination_edge WHERE actor_id='op-9'",
                Integer.class), "失败请求不得把接收人写入闭包");
        assertEquals(3, getClosure("C-1", "PA").path("version").asInt(),
                "整体回滚后闭包版本号不得前移");
        // 回滚不占键：同一 requestId/exposureKey 换合法参数可成功
        assertEquals(201, exchange(
                "/api/experiments/C-1/disclosures", HttpMethod.POST,
                headers("op-3", "COORDINATOR", "c1-disc-x"),
                "{\"exposureKey\":\"EK-X\",\"receiverActors\":[\"op-9\"],"
                        + "\"participantIds\":[\"PA\"]}").getStatusCode().value());

        // 从未揭盲、不在闭包的 coord-2 登记披露：403
        ResponseEntity<String> neverKnew = exchange(
                "/api/experiments/C-1/disclosures", HttpMethod.POST,
                headers("coord-2", "COORDINATOR", "c1-disc-y"),
                "{\"exposureKey\":\"EK-Y\",\"receiverActors\":[\"op-9\"],"
                        + "\"participantIds\":[\"PA\"]}");
        assertEquals(403, neverKnew.getStatusCode().value());

        // 污染记录不扩大处理代码查询权限：接收人 op-3 查申请人的揭盲结果仍 403
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("op-3", "COORDINATOR", null), null)
                .getStatusCode().value());
    }

    @Test
    void disclosureValidation_failures() {
        setupExperiment("C-2", "PA");

        // 缺少 X-Request-Id：400
        assertEquals(400, exchange("/api/experiments/C-2/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", null),
                "{\"exposureKey\":\"EK\",\"receiverActors\":[\"op-3\"],"
                        + "\"participantIds\":[\"PA\"]}").getStatusCode().value());

        // 接收人 0 个：400
        assertEquals(400, exchange("/api/experiments/C-2/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "c2-bad-1"),
                "{\"exposureKey\":\"EK\",\"receiverActors\":[],"
                        + "\"participantIds\":[\"PA\"]}").getStatusCode().value());

        // 接收人超过 20 名：400
        StringBuilder tooMany = new StringBuilder();
        for (int i = 1; i <= 21; i++) {
            if (i > 1) {
                tooMany.append(',');
            }
            tooMany.append("\"op-").append(i).append('"');
        }
        assertEquals(400, exchange("/api/experiments/C-2/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "c2-bad-2"),
                "{\"exposureKey\":\"EK\",\"receiverActors\":[" + tooMany + "],"
                        + "\"participantIds\":[\"PA\"]}").getStatusCode().value());

        // 向本人登记：400（无需已揭盲也在事务前拦截）
        assertEquals(400, exchange("/api/experiments/C-2/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "c2-bad-3"),
                "{\"exposureKey\":\"EK\",\"receiverActors\":[\"coord-1\"],"
                        + "\"participantIds\":[\"PA\"]}").getStatusCode().value());

        // 不存在的参与者：404
        assertEquals(404, exchange("/api/experiments/C-2/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "c2-bad-4"),
                "{\"exposureKey\":\"EK\",\"receiverActors\":[\"op-3\"],"
                        + "\"participantIds\":[\"NOPE\"]}").getStatusCode().value());

        // 非 COMPLIANCE 不能发起隔离（权限先于业务）
        assertEquals(403, exchange(
                "/api/experiments/C-2/participants/PA/quarantine-orders",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "c2-bad-5"),
                "{\"version\":1,\"actors\":[\"coord-1\"]}").getStatusCode().value());
    }

    @Test
    void reviewerInClosure_cannotApproveNewRequest_butOriginalResultStaysReadable() throws Exception {
        setupExperiment("C-3", "PA");
        String ub1 = approveUnblind("C-3", "PA", "c3");

        // coord-1 把处理代码披露给 rev-2（第一个申请的批准人）
        assertEquals(201, exchange("/api/experiments/C-3/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "c3-disc"),
                "{\"exposureKey\":\"EK-3\",\"receiverActors\":[\"rev-2\"],"
                        + "\"participantIds\":[\"PA\"]}").getStatusCode().value());

        // 原申请人仍可读取已批准结果（污染记录不扩大也不收回查询权限）
        ResponseEntity<String> result = exchange(
                "/api/unblind-requests/" + ub1 + "/result", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, result.getStatusCode().value());
        assertTrue(result.getBody().contains("APPROVED"));

        // 同一分配可再次申请揭盲（前一申请已批准释放待审占位）
        ResponseEntity<String> apply2 = exchange(
                "/api/experiments/C-3/participants/PA/unblind-requests",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "c3-apply-2"),
                "{\"reason\":\"二次申请\"}");
        assertEquals(201, apply2.getStatusCode().value());
        String ub2 = json(apply2).path("requestId").asText();

        // rev-2 已在闭包内：不得作为新审核人，403；申请仍 PENDING
        ResponseEntity<String> gated = exchange(
                "/api/unblind-requests/" + ub2 + "/approval", HttpMethod.POST,
                headers("rev-2", "REVIEWER", "c3-approve-gated"), null);
        assertEquals(403, gated.getStatusCode().value());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, ub2));

        // 不在闭包内的另一名 REVIEWER 可以批准
        assertEquals(200, exchange("/api/unblind-requests/" + ub2 + "/approval",
                HttpMethod.POST, headers("rev-9", "REVIEWER", "c3-approve-ok"), null)
                .getStatusCode().value());

        // 批准后申请人种子边已存在（幂等不新增版本），闭包版本数保持为 2（种子 + rev-2 披露）
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM contamination_version WHERE experiment_id='C-3' "
                        + "AND participant_id='PA'", Integer.class));
    }

    @Test
    void quarantineLifecycle_freezeSnapshot_thenReopenOnNewDisclosure() throws Exception {
        setupExperiment("C-4", "PA");
        approveUnblind("C-4", "PA", "c4");
        assertEquals(201, exchange("/api/experiments/C-4/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "c4-disc"),
                "{\"exposureKey\":\"EK-4\",\"receiverActors\":[\"op-3\"],"
                        + "\"participantIds\":[\"PA\"]}").getStatusCode().value());

        JsonNode closure = getClosure("C-4", "PA");
        int version = closure.path("version").asInt();
        assertEquals(2, version);

        // 用旧版本号发起：409
        assertEquals(409, exchange(
                "/api/experiments/C-4/participants/PA/quarantine-orders",
                HttpMethod.POST, headers("comp-1", "COMPLIANCE", "c4-qo-stale-v"),
                "{\"version\":1,\"actors\":[\"coord-1\"]}").getStatusCode().value());

        // 用旧闭包内容（缺少 op-3）发起：409
        assertEquals(409, exchange(
                "/api/experiments/C-4/participants/PA/quarantine-orders",
                HttpMethod.POST, headers("comp-1", "COMPLIANCE", "c4-qo-stale-a"),
                "{\"version\":2,\"actors\":[\"coord-1\"]}").getStatusCode().value());

        // 正确提交当前完整闭包与版本：201 OPEN
        ResponseEntity<String> created = exchange(
                "/api/experiments/C-4/participants/PA/quarantine-orders",
                HttpMethod.POST, headers("comp-1", "COMPLIANCE", "c4-qo-create"),
                "{\"version\":2,\"actors\":[\"coord-1\",\"op-3\"]}");
        assertEquals(201, created.getStatusCode().value());
        JsonNode orderBody = json(created);
        String orderId = orderBody.path("orderId").asText();
        assertEquals("OPEN", orderBody.path("status").asText());
        assertEquals("[\"coord-1\",\"op-3\"]", orderBody.path("closureActors").toString());
        assertFalse(orderBody.has("treatment"));

        // 同参与者第二个待确认隔离单：409
        assertEquals(409, exchange(
                "/api/experiments/C-4/participants/PA/quarantine-orders",
                HttpMethod.POST, headers("comp-2", "COMPLIANCE", "c4-qo-dup"),
                "{\"version\":2,\"actors\":[\"coord-1\",\"op-3\"]}")
                .getStatusCode().value());

        // 发起人本人确认：403
        assertEquals(403, exchange("/api/quarantine-orders/" + orderId + "/confirmation",
                HttpMethod.POST, headers("comp-1", "COMPLIANCE", "c4-qo-self"), null)
                .getStatusCode().value());

        // 在闭包内的负责人确认：403
        assertEquals(403, exchange("/api/quarantine-orders/" + orderId + "/confirmation",
                HttpMethod.POST, headers("op-3", "COMPLIANCE", "c4-qo-inside"), null)
                .getStatusCode().value());

        // 另一名不在闭包内的负责人确认：200 CLOSED
        ResponseEntity<String> confirmed = exchange(
                "/api/quarantine-orders/" + orderId + "/confirmation",
                HttpMethod.POST, headers("comp-2", "COMPLIANCE", "c4-qo-confirm"), null);
        assertEquals(200, confirmed.getStatusCode().value());
        assertEquals("CLOSED", json(confirmed).path("status").asText());
        assertEquals("comp-2", json(confirmed).path("confirmerActor").asText());

        // 版本 2 被冻结；边未删除
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM contamination_version WHERE experiment_id='C-4' "
                        + "AND participant_id='PA' AND version=2", String.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM contamination_edge WHERE experiment_id='C-4' "
                        + "AND participant_id='PA'", Integer.class),
                "关闭隔离不得删除边");

        // 重复确认：409
        assertEquals(409, exchange("/api/quarantine-orders/" + orderId + "/confirmation",
                HttpMethod.POST, headers("comp-3", "COMPLIANCE", "c4-qo-again"), null)
                .getStatusCode().value());

        // 关闭后新增披露：生成新版本 3 并重新 OPEN
        assertEquals(201, exchange("/api/experiments/C-4/disclosures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "c4-disc-2"),
                "{\"exposureKey\":\"EK-4B\",\"receiverActors\":[\"op-7\"],"
                        + "\"participantIds\":[\"PA\"]}").getStatusCode().value());
        JsonNode reopened = getClosure("C-4", "PA");
        assertEquals(3, reopened.path("version").asInt());
        assertEquals("OPEN", reopened.path("status").asText());
        assertEquals("[\"coord-1\",\"op-3\",\"op-7\"]", reopened.path("actors").toString());

        // 隔离历史含 1 条已关闭记录，不含处理代码
        ResponseEntity<String> history = exchange(
                "/api/experiments/C-4/participants/PA/quarantine-orders",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, history.getStatusCode().value());
        JsonNode historyBody = json(history);
        assertEquals(1, historyBody.size());
        assertEquals("CLOSED", historyBody.get(0).path("status").asText());
        assertEquals(2, historyBody.get(0).path("version").asInt());
        assertFalse(history.getBody().contains("treatment"));

        // 新版本可再次发起并确认隔离
        ResponseEntity<String> created2 = exchange(
                "/api/experiments/C-4/participants/PA/quarantine-orders",
                HttpMethod.POST, headers("comp-1", "COMPLIANCE", "c4-qo-create-2"),
                "{\"version\":3,\"actors\":[\"coord-1\",\"op-3\",\"op-7\"]}");
        assertEquals(201, created2.getStatusCode().value());
        assertEquals(200, exchange(
                "/api/quarantine-orders/" + json(created2).path("orderId").asText()
                        + "/confirmation",
                HttpMethod.POST, headers("comp-9", "COMPLIANCE", "c4-qo-confirm-2"), null)
                .getStatusCode().value());
    }

    @Test
    void queries_forUnknownParticipantOrEmptyClosure_are404() {
        setupExperiment("C-5", "PA");
        // 参与者存在但从未揭盲/披露：无闭包
        assertEquals(404, exchange(
                "/api/experiments/C-5/participants/PA/contamination",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null)
                .getStatusCode().value());
        // 参与者不存在
        assertEquals(404, exchange(
                "/api/experiments/C-5/participants/NOPE/contamination",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null)
                .getStatusCode().value());
        // 隔离单不存在
        assertEquals(404, exchange("/api/quarantine-orders/QO-NOPE/confirmation",
                HttpMethod.POST, headers("comp-1", "COMPLIANCE", "c5-qo-missing"), null)
                .getStatusCode().value());
    }
}
