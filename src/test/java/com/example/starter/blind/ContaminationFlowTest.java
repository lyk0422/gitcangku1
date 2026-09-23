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
 * 揭盲泄露传播、污染闭包版本与隔离门禁主流程、失败分支、整体回滚与幂等边界。
 * 全部走真实 HTTP 入口与 H2（MySQL 兼容模式），不 mock 数据库边界。
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

    private int status(String path, HttpMethod method, HttpHeaders headers, String body) {
        return exchange(path, method, headers, body).getStatusCode().value();
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertNotNull(response.getBody());
        return mapper.readTree(response.getBody());
    }

    /** 建实验、登记参与者、申请并批准揭盲，返回申请人结果中的 exposureKey。 */
    private String approveUnblinding(String expId, String pid, String applicant, String reviewer,
                                    String prefix) throws Exception {
        assertEquals(201, status("/api/experiments/" + expId, HttpMethod.POST,
                headers(applicant, "COORDINATOR", prefix + "-create"),
                "{\"blockCount\":2}"));
        assertEquals(201, status("/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST, headers(applicant, "COORDINATOR", prefix + "-alloc"), null));
        ResponseEntity<String> apply = exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/unblind-requests",
                HttpMethod.POST, headers(applicant, "COORDINATOR", prefix + "-apply"),
                "{\"reason\":\"合成测试需要核对处理代码\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, status("/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers(reviewer, "REVIEWER", prefix + "-approve"), null));
        ResponseEntity<String> result = exchange(
                "/api/unblind-requests/" + ubId + "/result", HttpMethod.GET,
                headers(applicant, "COORDINATOR", null), null);
        assertEquals(200, result.getStatusCode().value());
        JsonNode body = json(result);
        String key = body.path("exposureKey").asText();
        assertFalse(key.isBlank(), "批准结果必须向申请人颁发 exposureKey");
        // 污染查询不得返回处理代码。
        assertFalse(body.has("seatNo"));
        return key;
    }

    @Test
    void seed_directAndDownstreamDisclosure_buildsClosure_andDedupsEdges() throws Exception {
        String key = approveUnblinding("CT-1", "PA", "coord-1", "rev-2", "ct1");

        // 批准即种子：闭包仅含申请人，版本1。
        ResponseEntity<String> contamination = exchange(
                "/api/experiments/CT-1/participants/PA/contamination", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, contamination.getStatusCode().value());
        JsonNode c0 = json(contamination);
        assertEquals(1, c0.path("version").asInt());
        assertEquals("OPEN", c0.path("versionStatus").asText());
        assertEquals(1, c0.path("edgeCount").asInt());
        assertEquals(List.of("coord-1"), toStringList(c0.path("closure")));
        assertFalse(c0.has("treatment"), "污染视图不得返回处理代码");

        // 申请人持 key 登记自己向 op1/op2 的直接披露。
        ResponseEntity<String> recorded = exchange("/api/exposures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ct1-expose-1"),
                "{\"exposureKey\":\"" + key + "\",\"recipients\":[\"op1\",\"op2\"]}");
        assertEquals(201, recorded.getStatusCode().value());
        JsonNode recBody = json(recorded);
        assertEquals(2, recBody.path("addedEdges").asInt());
        assertEquals(3, recBody.path("edgeCount").asInt());
        assertEquals(List.of("coord-1", "op1", "op2"), toStringList(recBody.path("closure")));
        assertFalse(recBody.has("treatment"));

        // 重复登记同一接收人：边重复不新增。
        ResponseEntity<String> repeat = exchange("/api/exposures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ct1-expose-repeat"),
                "{\"exposureKey\":\"" + key + "\",\"recipients\":[\"op2\",\"op1\"]}");
        assertEquals(201, repeat.getStatusCode().value());
        assertEquals(0, json(repeat).path("addedEdges").asInt());
        assertEquals(3, json(repeat).path("edgeCount").asInt());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_edge WHERE subject_id = "
                        + "(SELECT id FROM contamination_subject WHERE experiment_id='CT-1' "
                        + "AND participant_id='PA')", Integer.class), "边必须去重");

        // 接收人 op1 继续向下游 op3 登记；披露源为 op1。
        ResponseEntity<String> downstream = exchange(
                "/api/experiments/CT-1/participants/PA/exposures", HttpMethod.POST,
                headers("op1", "COORDINATOR", "ct1-down-1"),
                "{\"recipients\":[\"op3\"]}");
        assertEquals(201, downstream.getStatusCode().value());
        JsonNode dBody = json(downstream);
        assertEquals("op1", dBody.path("sourceActor").asText());
        assertEquals(4, dBody.path("edgeCount").asInt());
        assertEquals(List.of("coord-1", "op1", "op2", "op3"),
                toStringList(dBody.path("closure")));

        // 未获知者 opX 不能登记该参与者（不得登记未获知的参与者）。
        assertEquals(403, status("/api/experiments/CT-1/participants/PA/exposures", HttpMethod.POST,
                headers("opX", "COORDINATOR", "ct1-down-uninformed"),
                "{\"recipients\":[\"op9\"]}"));
    }

    @Test
    void exposureKey_isUnique_andProtectsAgainstForgeryAndReuse() throws Exception {
        String key = approveUnblinding("CT-2", "PA", "coord-1", "rev-2", "ct2");

        // 无效 key：403，不暴露资源是否存在。
        assertEquals(403, status("/api/exposures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ct2-bad-key"),
                "{\"exposureKey\":\"EK-not-a-real-key\",\"recipients\":[\"op1\"]}"));

        // 他人冒用申请人 key：披露源不能伪造，403。
        assertEquals(403, status("/api/exposures", HttpMethod.POST,
                headers("intruder", "COORDINATOR", "ct2-forge"),
                "{\"exposureKey\":\"" + key + "\",\"recipients\":[\"op1\"]}"));

        // exposureKey 全局唯一：两次批准颁发不同 key。
        String secondKey = secondApprovalNewKey("CT-2", "PA", "coord-1", "rev-3", "ct2b");
        assertFalse(key.equals(secondKey), "不同批准颁发的 exposureKey 必须唯一");
    }

    @Test
    void recipientCount_validated_andFailureRollsBackWholeBatch() throws Exception {
        approveUnblinding("CT-3", "PA", "coord-1", "rev-2", "ct3");
        // 先让 op1 获知。
        assertEquals(201, status("/api/exposures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ct3-to-op1"),
                "{\"exposureKey\":\"__KEY__\",\"recipients\":[\"op1\"]}"
                        .replace("__KEY__", keyOf("CT-3", "PA"))));

        // 空接收人 400。
        assertEquals(400, status("/api/exposures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ct3-empty"),
                "{\"exposureKey\":\"" + keyOf("CT-3", "PA") + "\",\"recipients\":[]}"));
        // 超过 20 人 400。
        String tooMany = mapper.writeValueAsString(java.util.Map.of(
                "exposureKey", keyOf("CT-3", "PA"),
                "recipients", java.util.stream.IntStream.rangeClosed(1, 21)
                        .mapToObj(i -> "m" + i).toList()));
        assertEquals(400, status("/api/exposures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ct3-too-many"), tooMany));

        // 整批回滚：op1 已获知，登记 [op-good, op1(自己)]，自检在 op-good 之后抛错，
        // 事务整体回滚，op-good 边不得残留。
        assertEquals(400, status("/api/experiments/CT-3/participants/PA/exposures", HttpMethod.POST,
                headers("op1", "COORDINATOR", "ct3-self-rollback"),
                "{\"recipients\":[\"op-good\",\"op1\"]}"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_edge WHERE target_actor = 'op-good'", Integer.class),
                "失败批次必须整体回滚，不允许部分边落库");
    }

    @Test
    void exposureWrites_idempotent_reorderReplaysDifferentMembersConflict_andFailureKeepsKey()
            throws Exception {
        String key = approveUnblinding("CT-4", "PA", "coord-1", "rev-2", "ct4");

        String body1 = "{\"exposureKey\":\"" + key + "\",\"recipients\":[\"op1\",\"op2\"]}";
        String bodyReordered = "{\"exposureKey\":\"" + key + "\",\"recipients\":[\"op2\",\"op1\"]}";
        ResponseEntity<String> first = exchange("/api/exposures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ct4-key"), body1);
        assertEquals(201, first.getStatusCode().value());
        // 同参集合换序重放：原成功结果回放。
        ResponseEntity<String> replay = exchange("/api/exposures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ct4-key"), bodyReordered);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_edge WHERE subject_id = "
                        + "(SELECT id FROM contamination_subject WHERE experiment_id='CT-4')",
                Integer.class), "换序重放不得新增边");

        // 异参（成员不同）：409。
        assertEquals(409, status("/api/exposures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ct4-key"),
                "{\"exposureKey\":\"" + key + "\",\"recipients\":[\"op1\",\"op3\"]}"));

        // 失败不占键：先用 ct4-reuse 键发一个非法（向自己）请求，失败后该键仍可成功使用。
        assertEquals(400, status("/api/exposures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ct4-reuse"),
                "{\"exposureKey\":\"" + key + "\",\"recipients\":[\"coord-1\"]}"));
        assertEquals(201, status("/api/exposures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ct4-reuse"),
                "{\"exposureKey\":\"" + key + "\",\"recipients\":[\"op7\"]}"));
    }

    @Test
    void reviewerInClosure_cannotApproveNewUnblinding_butPriorApprovalStaysReadable() throws Exception {
        String key = approveUnblinding("CT-5", "PA", "coord-1", "rev-2", "ct5");
        // coord-1 把处理代码直接披露给 rev-dirty，使其进入 PA 的污染闭包。
        assertEquals(201, status("/api/exposures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ct5-spread"),
                "{\"exposureKey\":\"" + key + "\",\"recipients\":[\"rev-dirty\"]}"));

        // 同一参与者首次批准后可再次申请；新审核人 rev-dirty 已在闭包，门禁拒绝。
        ResponseEntity<String> secondApply = exchange(
                "/api/experiments/CT-5/participants/PA/unblind-requests", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ct5-apply-2"), "{\"reason\":\"再次核对\"}");
        assertEquals(201, secondApply.getStatusCode().value());
        String ub2 = json(secondApply).path("requestId").asText();
        assertEquals(403, status("/api/unblind-requests/" + ub2 + "/approval", HttpMethod.POST,
                headers("rev-dirty", "REVIEWER", "ct5-approve-dirty"), null));
        // 门禁拒绝不得改变申请状态。
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM unblind_request WHERE id = ?", String.class, ub2));

        // 不在闭包的新审核人可以批准。
        assertEquals(200, status("/api/unblind-requests/" + ub2 + "/approval", HttpMethod.POST,
                headers("rev-clean", "REVIEWER", "ct5-approve-clean"), null));

        // 已有批准结果仍可由原申请人读取；污染记录不扩大查询权限。
        assertEquals(200, status("/api/unblind-requests/" + ub2 + "/result", HttpMethod.GET,
                headers("coord-1", "COORDINATOR", null), null));
        assertEquals(403, status("/api/unblind-requests/" + ub2 + "/result", HttpMethod.GET,
                headers("rev-clean", "REVIEWER", null), null));
        assertEquals(403, status("/api/unblind-requests/" + ub2 + "/result", HttpMethod.GET,
                headers("rev-dirty", "REVIEWER", null), null));
    }

    @Test
    void quarantine_initiateConfirmFreezeVersion_thenNewDisclosureOpensNewVersion() throws Exception {
        String key = approveUnblinding("CT-6", "PA", "coord-1", "rev-2", "ct6");
        assertEquals(201, status("/api/exposures", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "ct6-spread"),
                "{\"exposureKey\":\"" + key + "\",\"recipients\":[\"op1\"]}"));

        // 非 COMPLIANCE 角色不能发起。
        assertEquals(403, status(
                "/api/experiments/CT-6/participants/PA/quarantine-orders", HttpMethod.POST,
                headers("coord-x", "COORDINATOR", "ct6-qo-role"), null));

        // 题干只要求确认人不在闭包，闭包内合规负责人仍可发起：由 coord-1 发起。
        ResponseEntity<String> initiated = exchange(
                "/api/experiments/CT-6/participants/PA/quarantine-orders", HttpMethod.POST,
                headers("coord-1", "COMPLIANCE", "ct6-qo-init"), null);
        assertEquals(201, initiated.getStatusCode().value());
        JsonNode qo = json(initiated);
        String orderId = qo.path("orderId").asText();
        assertEquals(1, qo.path("version").asInt());
        assertEquals("OPEN", qo.path("status").asText());
        assertEquals("coord-1", qo.path("initiatorActor").asText());
        assertEquals(List.of("coord-1", "op1"), toStringList(qo.path("closure")));
        assertFalse(qo.has("treatment"));

        // 待确认期间不能重复发起。
        assertEquals(409, status(
                "/api/experiments/CT-6/participants/PA/quarantine-orders", HttpMethod.POST,
                headers("c1", "COMPLIANCE", "ct6-qo-dup"), null));
        // 发起人自己不能确认；闭包内负责人不能确认。
        assertEquals(403, status("/api/quarantine-orders/" + orderId + "/confirmation",
                HttpMethod.POST, headers("coord-1", "COMPLIANCE", "ct6-qo-self"), null));
        assertEquals(403, status("/api/quarantine-orders/" + orderId + "/confirmation",
                HttpMethod.POST, headers("op1", "COMPLIANCE", "ct6-qo-inside"), null));

        // 另一名不在闭包的负责人 c2 确认：版本1冻结。
        ResponseEntity<String> confirmed = exchange(
                "/api/quarantine-orders/" + orderId + "/confirmation", HttpMethod.POST,
                headers("c2", "COMPLIANCE", "ct6-qo-confirm"), null);
        assertEquals(200, confirmed.getStatusCode().value());
        assertEquals("CONFIRMED", json(confirmed).path("status").asText());
        assertEquals("c2", json(confirmed).path("confirmerActor").asText());
        // 重复确认 409。
        assertEquals(409, status("/api/quarantine-orders/" + orderId + "/confirmation",
                HttpMethod.POST, headers("c3", "COMPLIANCE", "ct6-qo-confirm-2"), null));

        // 冻结只保留审计快照，不删除边（1 条种子边 + 1 条直接披露边）。
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_edge WHERE subject_id = "
                        + "(SELECT id FROM contamination_subject WHERE experiment_id='CT-6')",
                Integer.class), "隔离关闭不得删除边");

        // 后续新增披露生成新版本并重新 OPEN：op1 向 op2 披露。
        ResponseEntity<String> afterClose = exchange(
                "/api/experiments/CT-6/participants/PA/exposures", HttpMethod.POST,
                headers("op1", "COORDINATOR", "ct6-after-close"),
                "{\"recipients\":[\"op2\"]}");
        assertEquals(201, afterClose.getStatusCode().value());
        assertEquals(2, json(afterClose).path("version").asInt());

        // 版本历史：v1 CLOSED 冻结快照（2条边时的闭包），v2 OPEN 最新闭包。
        ResponseEntity<String> versions = exchange(
                "/api/experiments/CT-6/participants/PA/contamination/versions", HttpMethod.GET,
                headers("c1", "COMPLIANCE", null), null);
        assertEquals(200, versions.getStatusCode().value());
        JsonNode vArray = json(versions);
        assertEquals(2, vArray.size());
        assertEquals("CLOSED", vArray.get(0).path("status").asText());
        assertEquals(List.of("coord-1", "op1"), toStringList(vArray.get(0).path("closure")));
        assertEquals(orderId, vArray.get(0).path("quarantineId").asText());
        assertEquals("OPEN", vArray.get(1).path("status").asText());
        assertEquals(List.of("coord-1", "op1", "op2"), toStringList(vArray.get(1).path("closure")));

        // 当前污染视图指向 v2 OPEN。
        JsonNode current = json(exchange(
                "/api/experiments/CT-6/participants/PA/contamination", HttpMethod.GET,
                headers("c1", "COMPLIANCE", null), null));
        assertEquals(2, current.path("version").asInt());
        assertEquals("OPEN", current.path("versionStatus").asText());

        // 隔离历史可查，不含处理代码。
        JsonNode history = json(exchange(
                "/api/experiments/CT-6/participants/PA/quarantine-orders", HttpMethod.GET,
                headers("c1", "COMPLIANCE", null), null));
        assertEquals(1, history.size());
        assertEquals(orderId, history.get(0).path("orderId").asText());
        assertFalse(history.get(0).has("treatment"));
    }

    @Test
    void queries_forUnknownSubject_404_andNoTreatmentLeak() throws Exception {
        approveUnblinding("CT-7", "PA", "coord-1", "rev-2", "ct7");
        assertEquals(404, status("/api/experiments/CT-7/participants/NOBODY/contamination",
                HttpMethod.GET, headers("c1", "COMPLIANCE", null), null));
        assertEquals(404, status(
                "/api/experiments/CT-7/participants/NOBODY/quarantine-orders", HttpMethod.POST,
                headers("c1", "COMPLIANCE", "ct7-qo-nobody"), null));
        String rawBody = exchange("/api/experiments/CT-7/participants/PA/contamination/versions",
                HttpMethod.GET, headers("c1", "COMPLIANCE", null), null).getBody();
        assertNotNull(rawBody);
        assertFalse(rawBody.contains("A\"") && rawBody.contains("seatNo"), "版本历史不得泄露盲底");
        assertTrue(rawBody.contains("coord-1"));
    }

    // ---------------- 辅助 ----------------

    /** 再次申请并批准同一参与者，返回新颁发的 exposureKey（用于校验 key 唯一）。 */
    private String secondApprovalNewKey(String expId, String pid, String applicant,
                                        String reviewer, String prefix) throws Exception {
        ResponseEntity<String> apply = exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/unblind-requests",
                HttpMethod.POST, headers(applicant, "COORDINATOR", prefix + "-apply2"),
                "{\"reason\":\"二次揭盲\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, status("/api/unblind-requests/" + ubId + "/approval", HttpMethod.POST,
                headers(reviewer, "REVIEWER", prefix + "-approve2"), null));
        return json(exchange("/api/unblind-requests/" + ubId + "/result", HttpMethod.GET,
                headers(applicant, "COORDINATOR", null), null)).path("exposureKey").asText();
    }

    private String keyOf(String expId, String pid) {
        return jdbc.queryForObject(
                "SELECT exposure_key FROM unblind_request "
                        + "WHERE experiment_id = ? AND participant_id = ? AND status = 'APPROVED' "
                        + "ORDER BY reviewed_at LIMIT 1",
                String.class, expId, pid);
    }

    private List<String> toStringList(JsonNode array) {
        return mapper.convertValue(array,
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }
}
