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
 * 处理包主流程：SUPPLY 登记与供应查询、协调员领取、无包 422、退组/关闭拒领、
 * 发过的包不回库、同一分配只能领取一次、响应不含代码与可解码编号。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KitSupplyFlowTest extends AbstractBlindIntegrationTest {

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

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }

    private void createExperiment(String experimentId, int blockCount, String requestId) {
        ResponseEntity<String> resp = post("/api/experiments/" + experimentId,
                headers("c1", "COORDINATOR", requestId), "{\"blockCount\":" + blockCount + "}");
        assertEquals(201, resp.getStatusCode().value());
    }

    private void register(String experimentId, String participantId, String requestId) {
        ResponseEntity<String> resp = post(
                "/api/experiments/" + experimentId + "/participants/" + participantId + "/allocations",
                headers("c1", "COORDINATOR", requestId), null);
        assertEquals(201, resp.getStatusCode().value());
    }

    private ResponseEntity<String> registerKit(String experimentId, String packageNo,
                                               String treatment, String requestId) {
        return post("/api/experiments/" + experimentId + "/kits",
                headers("s1", "SUPPLY", requestId),
                "{\"packageNo\":\"" + packageNo + "\",\"treatment\":\"" + treatment + "\"}");
    }

    private ResponseEntity<String> pickup(String experimentId, String participantId,
                                          String actor, String requestId) {
        return post("/api/experiments/" + experimentId + "/participants/" + participantId
                        + "/kit-pickups",
                headers(actor, "COORDINATOR", requestId), null);
    }

    /** 从库内读取参与者分配的处理代码（盲底，仅测试核对用）。 */
    private String treatmentOf(String experimentId, String participantId) {
        return jdbc.queryForObject(
                "SELECT s.treatment FROM seat s JOIN allocation a "
                        + "ON a.experiment_id = s.experiment_id AND a.block_no = s.block_no "
                        + "AND a.seat_no = s.seat_no "
                        + "WHERE a.experiment_id = ? AND a.participant_id = ?",
                String.class, experimentId, participantId);
    }

    @Test
    void registerKit_supplyOnly_viewHasNoTreatmentAndQueryShowsStatusOnly() throws Exception {
        createExperiment("KEXP-1", 2, "k1-create");

        // 非 SUPPLY 登记：403，且权限先于幂等
        assertEquals(403, post("/api/experiments/KEXP-1/kits",
                headers("c1", "COORDINATOR", "k1-kit-denied"),
                "{\"packageNo\":\"K-01\",\"treatment\":\"A\"}").getStatusCode().value());
        assertEquals(403, post("/api/experiments/KEXP-1/kits",
                headers("r1", "REVIEWER", "k1-kit-denied2"),
                "{\"packageNo\":\"K-01\",\"treatment\":\"A\"}").getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM treatment_kit WHERE experiment_id = 'KEXP-1'", Integer.class));

        // SUPPLY 登记成功；响应不含处理代码
        ResponseEntity<String> resp = registerKit("KEXP-1", "K-01", "A", "k1-kit-1");
        assertEquals(201, resp.getStatusCode().value());
        JsonNode body = json(resp);
        assertEquals("K-01", body.path("packageNo").asText());
        assertEquals("STOCK", body.path("status").asText());
        assertEquals(1_700_000_000_000L, body.path("registeredAt").asLong());
        assertFalse(body.has("treatment"), "登记响应不得回显处理代码");
        assertFalse(body.has("allocationId"));

        // 库内落盲底；供应查询只返回发放状态
        assertEquals("A", jdbc.queryForObject(
                "SELECT treatment FROM treatment_kit WHERE experiment_id = 'KEXP-1' "
                        + "AND package_no = 'K-01'", String.class));
        ResponseEntity<String> query = get("/api/experiments/KEXP-1/kits/K-01",
                headers("s1", "SUPPLY", null));
        assertEquals(200, query.getStatusCode().value());
        JsonNode view = json(query);
        assertEquals("STOCK", view.path("status").asText());
        assertFalse(view.has("treatment"), "供应查询不得含处理代码");
        assertFalse(view.has("allocationId"), "供应查询不得含分配信息");

        // 非 SUPPLY 不可做供应查询；编号重复 409；非法代码 400
        assertEquals(403, get("/api/experiments/KEXP-1/kits/K-01",
                headers("c1", "COORDINATOR", null)).getStatusCode().value());
        assertEquals(409, registerKit("KEXP-1", "K-01", "B", "k1-kit-dup").getStatusCode().value());
        assertEquals(400, registerKit("KEXP-1", "K-02", "C", "k1-kit-bad").getStatusCode().value());
        // 实验不存在 404
        assertEquals(404, registerKit("NOPE", "K-01", "A", "k1-kit-noexp").getStatusCode().value());
    }
    @Test
    void supplyCannotReadParticipantAllocation_coordinatorStillCan() {
        createExperiment("KEXP-2", 2, "k2-create");
        register("KEXP-2", "P1", "k2-alloc-1");

        assertEquals(403, get("/api/experiments/KEXP-2/participants/P1",
                headers("s1", "SUPPLY", null)).getStatusCode().value());
        assertEquals(200, get("/api/experiments/KEXP-2/participants/P1",
                headers("c1", "COORDINATOR", null)).getStatusCode().value());
        assertEquals(200, get("/api/experiments/KEXP-2/participants/P1",
                headers("r1", "REVIEWER", null)).getStatusCode().value());
    }

    @Test
    void pickup_happyPath_returnsWrapperAndPickupIdOnly_kitIssuedInDictionaryOrder()
            throws Exception {
        createExperiment("KEXP-3", 2, "k3-create");
        register("KEXP-3", "P1", "k3-alloc-1");
        String treatment = treatmentOf("KEXP-3", "P1");
        // 字典序 "K-10" < "K-2"：应先发 K-10
        assertEquals(201, registerKit("KEXP-3", "K-2", treatment, "k3-kit-2").getStatusCode().value());
        assertEquals(201, registerKit("KEXP-3", "K-10", treatment, "k3-kit-10").getStatusCode().value());

        ResponseEntity<String> resp = pickup("KEXP-3", "P1", "c1", "k3-pickup-1");
        assertEquals(201, resp.getStatusCode().value());
        JsonNode body = json(resp);
        String pickupId = body.path("pickupId").asText();
        String wrapperCode = body.path("wrapperCode").asText();
        assertTrue(pickupId.startsWith("KP-"), "领取编号应以 KP- 开头");
        assertTrue(wrapperCode.matches("[A-Z2-9]{12}"), "包装码应为12位无含义随机码");
        // 响应不得含处理代码、包编号、匹配数量或可解码编号
        assertFalse(body.has("treatment"));
        assertFalse(body.has("packageNo"));
        assertFalse(body.has("kitId"));
        assertFalse(body.has("allocationId"));
        assertFalse(body.has("matchedCount"));
        assertFalse(resp.getBody().contains("\"" + treatment + "\""), "响应不得出现处理代码值");

        // 库内：按字典序发出 K-10，绑定该分配；K-2 仍在库
        assertEquals("ISSUED", jdbc.queryForObject(
                "SELECT status FROM treatment_kit WHERE experiment_id = 'KEXP-3' "
                        + "AND package_no = 'K-10'", String.class));
        assertEquals("STOCK", jdbc.queryForObject(
                "SELECT status FROM treatment_kit WHERE experiment_id = 'KEXP-3' "
                        + "AND package_no = 'K-2'", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'KEXP-3'", Integer.class));
        // 发出的包处理代码与参与者席位一致（库内核对）
        String issuedTreatment = jdbc.queryForObject(
                "SELECT k.treatment FROM treatment_kit k JOIN kit_pickup p ON p.kit_id = k.id "
                        + "WHERE p.experiment_id = 'KEXP-3' AND p.participant_id = 'P1'",
                String.class);
        assertEquals(treatment, issuedTreatment);

        // 供应查询显示已发放，仍不含代码
        JsonNode view = json(get("/api/experiments/KEXP-3/kits/K-10",
                headers("s1", "SUPPLY", null)));
        assertEquals("ISSUED", view.path("status").asText());
        assertFalse(view.has("treatment"));
    }

    @Test
    void pickup_noMatchingKit_422_kitNotConsumed_andKeyNotOccupied() throws Exception {
        createExperiment("KEXP-4", 2, "k4-create");
        register("KEXP-4", "P1", "k4-alloc-1");
        String treatment = treatmentOf("KEXP-4", "P1");
        String other = "A".equals(treatment) ? "B" : "A";
        // 只有不匹配的包
        assertEquals(201, registerKit("KEXP-4", "K-1", other, "k4-kit-1").getStatusCode().value());

        ResponseEntity<String> resp = pickup("KEXP-4", "P1", "c1", "k4-pickup-1");
        assertEquals(422, resp.getStatusCode().value());
        assertFalse(resp.getBody().contains("\"" + treatment + "\""), "错误响应不得含处理代码值");
        // 不占包、不产生领取记录
        assertEquals("STOCK", jdbc.queryForObject(
                "SELECT status FROM treatment_kit WHERE experiment_id = 'KEXP-4' "
                        + "AND package_no = 'K-1'", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'KEXP-4'", Integer.class));
        // 失败不占幂等键
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'k4-pickup-1'",
                Integer.class));

        // 补上匹配包后，同一 requestId 可成功
        assertEquals(201, registerKit("KEXP-4", "K-2", treatment, "k4-kit-2").getStatusCode().value());
        assertEquals(201, pickup("KEXP-4", "P1", "c1", "k4-pickup-1").getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'KEXP-4'", Integer.class));
    }
    @Test
    void pickup_withdrawnOrClosedParticipant_rejected() {
        createExperiment("KEXP-5", 2, "k5-create");
        register("KEXP-5", "P1", "k5-alloc-1");
        register("KEXP-5", "P2", "k5-alloc-2");
        String t1 = treatmentOf("KEXP-5", "P1");
        String t2 = treatmentOf("KEXP-5", "P2");
        assertEquals(201, registerKit("KEXP-5", "K-1", t1, "k5-kit-1").getStatusCode().value());
        assertEquals(201, registerKit("KEXP-5", "K-2", t2, "k5-kit-2").getStatusCode().value());

        // 退组后不能新领包
        assertEquals(200, post("/api/experiments/KEXP-5/participants/P1/withdrawal",
                headers("c1", "COORDINATOR", "k5-withdraw-1"), null).getStatusCode().value());
        assertEquals(409, pickup("KEXP-5", "P1", "c1", "k5-pickup-wd").getStatusCode().value());
        assertEquals("STOCK", jdbc.queryForObject(
                "SELECT status FROM treatment_kit WHERE experiment_id = 'KEXP-5' "
                        + "AND package_no = 'K-1'", String.class));

        // 实验关闭后不能新领包
        assertEquals(200, post("/api/experiments/KEXP-5/close",
                headers("c1", "COORDINATOR", "k5-close"), null).getStatusCode().value());
        assertEquals(409, pickup("KEXP-5", "P2", "c1", "k5-pickup-closed").getStatusCode().value());
        assertEquals("STOCK", jdbc.queryForObject(
                "SELECT status FROM treatment_kit WHERE experiment_id = 'KEXP-5' "
                        + "AND package_no = 'K-2'", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'KEXP-5'", Integer.class));
    }

    @Test
    void pickup_sameAllocationOnlyOnce_issuedKitNeverReturnsToStock() throws Exception {
        createExperiment("KEXP-6", 2, "k6-create");
        // 前两个参与者同代码（每区组前两席同码）
        register("KEXP-6", "P1", "k6-alloc-1");
        register("KEXP-6", "P2", "k6-alloc-2");
        String treatment = treatmentOf("KEXP-6", "P1");
        assertEquals(treatment, treatmentOf("KEXP-6", "P2"));
        assertEquals(201, registerKit("KEXP-6", "K-1", treatment, "k6-kit-1").getStatusCode().value());

        assertEquals(201, pickup("KEXP-6", "P1", "c1", "k6-pickup-1").getStatusCode().value());

        // 同一分配只能领取一次（新 requestId 也 409）
        assertEquals(409, pickup("KEXP-6", "P1", "c1", "k6-pickup-1b").getStatusCode().value());
        // 发过的包不回库、不转给别人：同代码第二人无包可领
        assertEquals(422, pickup("KEXP-6", "P2", "c1", "k6-pickup-2").getStatusCode().value());
        assertEquals("ISSUED", jdbc.queryForObject(
                "SELECT status FROM treatment_kit WHERE experiment_id = 'KEXP-6' "
                        + "AND package_no = 'K-1'", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'KEXP-6'", Integer.class));
        // 包仍绑定第一个分配
        Long allocationId = jdbc.queryForObject(
                "SELECT allocation_id FROM treatment_kit WHERE experiment_id = 'KEXP-6' "
                        + "AND package_no = 'K-1'", Long.class);
        Long p1Allocation = jdbc.queryForObject(
                "SELECT id FROM allocation WHERE experiment_id = 'KEXP-6' AND participant_id = 'P1'",
                Long.class);
        assertEquals(p1Allocation, allocationId);
    }

    @Test
    void pickup_unblindIndependent_issuedKitMatchesBlindTreatment() throws Exception {
        createExperiment("KEXP-7", 2, "k7-create");
        register("KEXP-7", "P1", "k7-alloc-1");
        String treatment = treatmentOf("KEXP-7", "P1");
        assertEquals(201, registerKit("KEXP-7", "K-1", treatment, "k7-kit-1").getStatusCode().value());

        // 未揭盲也可领取；领取不泄露代码
        ResponseEntity<String> picked = pickup("KEXP-7", "P1", "c1", "k7-pickup-1");
        assertEquals(201, picked.getStatusCode().value());
        assertFalse(picked.getBody().contains("\"" + treatment + "\""), "领取响应不得含处理代码值");

        // 揭盲流程与发包无依赖：批准后结果与发出的包代码一致
        ResponseEntity<String> applied = post(
                "/api/experiments/KEXP-7/participants/P1/unblind-requests",
                headers("c1", "COORDINATOR", "k7-ub-apply"), "{\"reason\":\"合成核对\"}");
        assertEquals(201, applied.getStatusCode().value());
        String unblindId = json(applied).path("requestId").asText();
        assertEquals(200, post("/api/unblind-requests/" + unblindId + "/approval",
                headers("r1", "REVIEWER", "k7-ub-approve"), null).getStatusCode().value());
        ResponseEntity<String> result = get("/api/unblind-requests/" + unblindId + "/result",
                headers("c1", "COORDINATOR", null));
        assertEquals(200, result.getStatusCode().value());
        assertEquals(treatment, json(result).path("treatment").asText());
        // SUPPLY 不能借任何接口读到该映射
        assertEquals(403, get("/api/unblind-requests/" + unblindId + "/result",
                headers("s1", "SUPPLY", null)).getStatusCode().value());
    }
    @Test
    void kitWrites_idempotentReplay_sameKeySameParamsOriginalResponse() throws Exception {
        createExperiment("KEXP-8", 2, "k8-create");
        register("KEXP-8", "P1", "k8-alloc-1");
        String treatment = treatmentOf("KEXP-8", "P1");

        // 登记包：同键同参重放首次完整响应，不产生第二行
        ResponseEntity<String> kit1 = registerKit("KEXP-8", "K-1", treatment, "k8-kit-req");
        assertEquals(201, kit1.getStatusCode().value());
        ResponseEntity<String> kitReplay = registerKit("KEXP-8", "K-1", treatment, "k8-kit-req");
        assertEquals(201, kitReplay.getStatusCode().value());
        assertEquals(kit1.getBody(), kitReplay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM treatment_kit WHERE experiment_id = 'KEXP-8'",
                Integer.class));
        // 同键异参 409
        String other = "A".equals(treatment) ? "B" : "A";
        assertEquals(409, registerKit("KEXP-8", "K-1", other, "k8-kit-req").getStatusCode().value());
        assertEquals(409, registerKit("KEXP-8", "K-9", treatment, "k8-kit-req").getStatusCode().value());

        // 领取：同键同参重放同一包装码与领取编号
        ResponseEntity<String> pickup1 = pickup("KEXP-8", "P1", "c1", "k8-pickup-req");
        assertEquals(201, pickup1.getStatusCode().value());
        ResponseEntity<String> pickupReplay = pickup("KEXP-8", "P1", "c1", "k8-pickup-req");
        assertEquals(201, pickupReplay.getStatusCode().value());
        assertEquals(pickup1.getBody(), pickupReplay.getBody());
        assertEquals(json(pickup1).path("wrapperCode").asText(),
                json(pickupReplay).path("wrapperCode").asText());
        assertFalse(pickupReplay.getBody().contains("\"" + treatment + "\""), "重放响应同样不得含代码值");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'KEXP-8'", Integer.class));
        // 同键异参（换参与者）409
        register("KEXP-8", "P2", "k8-alloc-2");
        assertEquals(409, pickup("KEXP-8", "P2", "c1", "k8-pickup-req").getStatusCode().value());
        // 同键换人（另一协调员）409：身份含本地操作者
        assertEquals(409, pickup("KEXP-8", "P1", "c2", "k8-pickup-req").getStatusCode().value());
    }

    @Test
    void kitWrites_authCheckedBeforeReplay() {
        createExperiment("KEXP-9", 2, "k9-create");
        register("KEXP-9", "P1", "k9-alloc-1");
        String treatment = treatmentOf("KEXP-9", "P1");
        assertEquals(201, registerKit("KEXP-9", "K-1", treatment, "k9-kit-req").getStatusCode().value());
        assertEquals(201, pickup("KEXP-9", "P1", "c1", "k9-pickup-req").getStatusCode().value());

        // 权限先于重放：SUPPLY 用已成功的领取键 -> 403 而非回放；协调员用登记键 -> 403
        assertEquals(403, post("/api/experiments/KEXP-9/participants/P1/kit-pickups",
                headers("s1", "SUPPLY", "k9-pickup-req"), null).getStatusCode().value());
        assertEquals(403, post("/api/experiments/KEXP-9/participants/P1/kit-pickups",
                headers("r1", "REVIEWER", "k9-pickup-req"), null).getStatusCode().value());
        assertEquals(403, post("/api/experiments/KEXP-9/kits",
                headers("c1", "COORDINATOR", "k9-kit-req"),
                "{\"packageNo\":\"K-1\",\"treatment\":\"" + treatment + "\"}")
                .getStatusCode().value());
        // 缺身份头 401，不触达幂等
        assertEquals(401, rest.exchange("/api/experiments/KEXP-9/kits", HttpMethod.POST,
                new HttpEntity<>("{\"packageNo\":\"K-2\",\"treatment\":\"A\"}",
                        noAuthHeaders("k9-noauth")), String.class).getStatusCode().value());
        // 非法角色 401
        assertEquals(401, post("/api/experiments/KEXP-9/kits",
                headers("s1", "ADMIN", "k9-badrole"),
                "{\"packageNo\":\"K-2\",\"treatment\":\"A\"}").getStatusCode().value());
    }

    private HttpHeaders noAuthHeaders(String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Request-Id", requestId);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}