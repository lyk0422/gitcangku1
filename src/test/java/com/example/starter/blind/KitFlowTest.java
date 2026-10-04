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
 * 处理包发放主流程与失败分支：
 * SUPPLY 登记、字典序发放、只回包装码与领取ID、无匹配包 422 不占包、
 * 退组/关闭后禁领、同次分配只能领一次、供应查询只回发放状态。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KitFlowTest extends AbstractBlindIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpHeaders headers(String actor, String role, String requestId) {
        HttpHeaders h = new HttpHeaders();
        if (actor != null) {
            h.set("X-Actor-Id", actor);
        }
        if (role != null) {
            h.set("X-Role", role);
        }
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
        assertNotNull(response.getBody());
        return mapper.readTree(response.getBody());
    }

    private void createExperiment(String exp, int blocks, String reqId) {
        assertEquals(201, post("/api/experiments/" + exp,
                headers("c1", "COORDINATOR", reqId), "{\"blockCount\":" + blocks + "}")
                .getStatusCode().value());
    }

    private void register(String exp, String pid, String reqId) {
        assertEquals(201, post("/api/experiments/" + exp + "/participants/" + pid + "/allocations",
                headers("c1", "COORDINATOR", reqId), null).getStatusCode().value());
    }

    private ResponseEntity<String> registerKit(String exp, String kitNo,
                                               String treatment, String reqId) {
        return post("/api/experiments/" + exp + "/kits",
                headers("s1", "SUPPLY", reqId),
                "{\"kitNo\":\"" + kitNo + "\",\"treatment\":\"" + treatment + "\"}");
    }

    private ResponseEntity<String> pickup(String exp, String pid, String actor, String reqId) {
        return post("/api/experiments/" + exp + "/participants/" + pid + "/kit-pickup",
                headers(actor, "COORDINATOR", reqId), null);
    }

    private String kitPackageCode(String exp, String kitNo) {
        return jdbc.queryForObject(
                "SELECT package_code FROM kit WHERE experiment_id = ? AND kit_no = ?",
                String.class, exp, kitNo);
    }

    @Test
    void pickup_lexicographicFirstMatchingKit_returnsOnlyPackageCodeAndPickupId() throws Exception {
        createExperiment("EXP-K1", 2, "kf-create-1");
        // 区组1：P1=A、P2=A、P3=B（席位顺序固定两A两B）
        register("EXP-K1", "P1", "kf-alloc-1");
        register("EXP-K1", "P2", "kf-alloc-2");
        register("EXP-K1", "P3", "kf-alloc-3");
        // 故意乱序登记，验证按包编号字典序而非登记顺序发放
        assertEquals(201, registerKit("EXP-K1", "KIT-A2", "A", "kf-kit-1").getStatusCode().value());
        assertEquals(201, registerKit("EXP-K1", "KIT-A1", "A", "kf-kit-2").getStatusCode().value());
        assertEquals(201, registerKit("EXP-K1", "KIT-B1", "B", "kf-kit-3").getStatusCode().value());

        ResponseEntity<String> kitView = registerKit("EXP-K1", "KIT-A3", "A", "kf-kit-4");
        JsonNode kitNode = json(kitView);
        assertEquals("STOCK", kitNode.path("status").asText());
        assertTrue(kitNode.path("packageCode").asText().matches("[A-Z2-9]{12}"));

        // P1（A）领到字典序第一个未发包 KIT-A1
        ResponseEntity<String> p1 = pickup("EXP-K1", "P1", "c1", "kf-pick-1");
        assertEquals(201, p1.getStatusCode().value());
        JsonNode n1 = json(p1);
        assertEquals(kitPackageCode("EXP-K1", "KIT-A1"), n1.path("packageCode").asText());
        assertTrue(n1.path("pickupId").asText().startsWith("KP-"));
        assertEquals("P1", n1.path("participantId").asText());
        // 普通响应不得含处理代码、包编号或任何可解码编号
        assertFalse(n1.has("treatment"));
        assertFalse(n1.has("kitNo"));
        assertFalse(n1.has("kitId"));
        assertFalse(n1.has("allocationId"));
        assertFalse(n1.has("blockNo"));
        assertFalse(n1.has("seatNo"));

        // P2（A）领 KIT-A2；P3（B）领 KIT-B1
        ResponseEntity<String> p2 = pickup("EXP-K1", "P2", "c1", "kf-pick-2");
        assertEquals(kitPackageCode("EXP-K1", "KIT-A2"), json(p2).path("packageCode").asText());
        ResponseEntity<String> p3 = pickup("EXP-K1", "P3", "c1", "kf-pick-3");
        assertEquals(kitPackageCode("EXP-K1", "KIT-B1"), json(p3).path("packageCode").asText());

        // 供应查询只回发放状态：无处理代码、包装码、参与者
        ResponseEntity<String> status = get("/api/experiments/EXP-K1/kits/KIT-A1",
                headers("s1", "SUPPLY", null));
        assertEquals(200, status.getStatusCode().value());
        JsonNode st = json(status);
        assertEquals("ISSUED", st.path("status").asText());
        assertTrue(st.path("issuedAt").isNumber());
        assertFalse(st.has("treatment"));
        assertFalse(st.has("packageCode"));
        assertFalse(st.has("participantId"));
        ResponseEntity<String> stock = get("/api/experiments/EXP-K1/kits/KIT-A3",
                headers("s1", "SUPPLY", null));
        assertEquals("STOCK", json(stock).path("status").asText());
        assertTrue(json(stock).path("issuedAt").isNull());

        // 最终数据：3 条领取，每包一次、每次分配一次
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'EXP-K1'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT kit_id FROM kit_pickup "
                        + "GROUP BY kit_id HAVING COUNT(*) > 1) d", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT allocation_id FROM kit_pickup "
                        + "GROUP BY allocation_id HAVING COUNT(*) > 1) d", Integer.class));
    }
    @Test
    void pickup_noMatchingKit_422_kitNotConsumed_andFailureKeyReleased() throws Exception {
        createExperiment("EXP-K2", 2, "kf-create-2");
        register("EXP-K2", "P1", "kf-alloc-21");

        // 无任何包：422 且不占包、不占幂等键
        ResponseEntity<String> noKit = pickup("EXP-K2", "P1", "c1", "kf-pick-21");
        assertEquals(422, noKit.getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'EXP-K2'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'kf-pick-21'",
                Integer.class));

        // 只有不匹配代码的包：仍 422，该包保持 STOCK
        assertEquals(201, registerKit("EXP-K2", "KIT-B", "B", "kf-kit-21").getStatusCode().value());
        assertEquals(422, pickup("EXP-K2", "P1", "c1", "kf-pick-22").getStatusCode().value());
        assertEquals("STOCK", json(get("/api/experiments/EXP-K2/kits/KIT-B",
                headers("s1", "SUPPLY", null))).path("status").asText());

        // 失败不占键：补上匹配包后同一 requestId 可成功
        assertEquals(201, registerKit("EXP-K2", "KIT-A", "A", "kf-kit-22").getStatusCode().value());
        ResponseEntity<String> ok = pickup("EXP-K2", "P1", "c1", "kf-pick-21");
        assertEquals(201, ok.getStatusCode().value());
        assertEquals(kitPackageCode("EXP-K2", "KIT-A"), json(ok).path("packageCode").asText());
    }

    @Test
    void pickup_duplicate_withdrawn_closed_allRejected() throws Exception {
        createExperiment("EXP-K3", 2, "kf-create-3");
        register("EXP-K3", "P1", "kf-alloc-31");
        register("EXP-K3", "P2", "kf-alloc-32");
        register("EXP-K3", "P3", "kf-alloc-33");
        assertEquals(201, registerKit("EXP-K3", "KIT-A", "A", "kf-kit-31").getStatusCode().value());
        assertEquals(201, registerKit("EXP-K3", "KIT-B", "B", "kf-kit-32").getStatusCode().value());

        assertEquals(201, pickup("EXP-K3", "P1", "c1", "kf-pick-31").getStatusCode().value());
        // 同参与者同次分配只能领取一次
        assertEquals(409, pickup("EXP-K3", "P1", "c1", "kf-pick-32").getStatusCode().value());

        // 退组后不能新领包
        assertEquals(200, post("/api/experiments/EXP-K3/participants/P2/withdrawal",
                headers("c1", "COORDINATOR", "kf-wd-31"), null).getStatusCode().value());
        assertEquals(409, pickup("EXP-K3", "P2", "c1", "kf-pick-33").getStatusCode().value());

        // 实验关闭后不能新领包
        assertEquals(200, post("/api/experiments/EXP-K3/close",
                headers("c1", "COORDINATOR", "kf-close-31"), null).getStatusCode().value());
        assertEquals(409, pickup("EXP-K3", "P3", "c1", "kf-pick-34").getStatusCode().value());

        // 被拒的包均未发出
        assertEquals("STOCK", json(get("/api/experiments/EXP-K3/kits/KIT-B",
                headers("s1", "SUPPLY", null))).path("status").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'EXP-K3'", Integer.class));
    }

    @Test
    void kitEndpoints_authorization_supplyCannotReadMapping_authBeforeReplay() throws Exception {
        createExperiment("EXP-K4", 2, "kf-create-4");
        register("EXP-K4", "P1", "kf-alloc-41");

        // 非 SUPPLY 不能登记包、不能查供应状态
        assertEquals(403, post("/api/experiments/EXP-K4/kits",
                headers("c1", "COORDINATOR", "kf-kit-41"),
                "{\"kitNo\":\"K1\",\"treatment\":\"A\"}").getStatusCode().value());
        assertEquals(403, post("/api/experiments/EXP-K4/kits",
                headers("r1", "REVIEWER", "kf-kit-42"),
                "{\"kitNo\":\"K1\",\"treatment\":\"A\"}").getStatusCode().value());
        assertEquals(201, registerKit("EXP-K4", "K1", "A", "kf-kit-43").getStatusCode().value());
        assertEquals(403, get("/api/experiments/EXP-K4/kits/K1",
                headers("c1", "COORDINATOR", null)).getStatusCode().value());
        assertEquals(403, get("/api/experiments/EXP-K4/kits/K1",
                headers("r1", "REVIEWER", null)).getStatusCode().value());

        // SUPPLY 不能登记参与者、不能读普通分配映射、不能领取
        assertEquals(403, post("/api/experiments/EXP-K4/participants/P9/allocations",
                headers("s1", "SUPPLY", "kf-alloc-42"), null).getStatusCode().value());
        assertEquals(403, get("/api/experiments/EXP-K4/participants/P1",
                headers("s1", "SUPPLY", null)).getStatusCode().value());
        assertEquals(403, post("/api/experiments/EXP-K4/participants/P1/kit-pickup",
                headers("s1", "SUPPLY", "kf-pick-41"), null).getStatusCode().value());
        assertEquals(403, post("/api/experiments/EXP-K4/participants/P1/kit-pickup",
                headers("r1", "REVIEWER", "kf-pick-42"), null).getStatusCode().value());

        // SUPPLY 不能读揭盲信息（即使冒用申请人 actorId）
        assertEquals(201, post("/api/experiments/EXP-K4/participants/P1/unblind-requests",
                headers("c1", "COORDINATOR", "kf-ub-41"), "{\"reason\":\"合成测试\"}")
                .getStatusCode().value());
        String ubId = jdbc.queryForObject(
                "SELECT id FROM unblind_request WHERE experiment_id = 'EXP-K4'", String.class);
        assertEquals(403, get("/api/unblind-requests/" + ubId,
                headers("c1", "SUPPLY", null)).getStatusCode().value());
        assertEquals(403, get("/api/unblind-requests/" + ubId + "/result",
                headers("c1", "SUPPLY", null)).getStatusCode().value());

        // 鉴权先于重放：已成功的 requestId 被无权限角色使用时返回 403 而非回放
        assertEquals(403, post("/api/experiments/EXP-K4/kits",
                headers("c1", "COORDINATOR", "kf-kit-43"),
                "{\"kitNo\":\"K1\",\"treatment\":\"A\"}").getStatusCode().value());
        // 缺头 401、非法角色 401
        assertEquals(401, post("/api/experiments/EXP-K4/kits",
                headers(null, null, "kf-kit-44"),
                "{\"kitNo\":\"K2\",\"treatment\":\"A\"}").getStatusCode().value());
        assertEquals(401, post("/api/experiments/EXP-K4/kits",
                headers("x1", "ADMIN", "kf-kit-45"),
                "{\"kitNo\":\"K2\",\"treatment\":\"A\"}").getStatusCode().value());
    }
    @Test
    void kitRegister_idempotency_replaySameResult_differentParams409_failureKeyReleased()
            throws Exception {
        createExperiment("EXP-K5", 2, "kf-create-5");

        ResponseEntity<String> first = registerKit("EXP-K5", "KIT-1", "A", "kf-kit-51");
        assertEquals(201, first.getStatusCode().value());
        // 同键同参重放：原样返回首个完整响应，不产生第二个包
        ResponseEntity<String> replay = registerKit("EXP-K5", "KIT-1", "A", "kf-kit-51");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit WHERE experiment_id = 'EXP-K5'", Integer.class));

        // 同键异参（不同处理代码）409；同键异操作者 409
        assertEquals(409, registerKit("EXP-K5", "KIT-1", "B", "kf-kit-51").getStatusCode().value());
        assertEquals(409, post("/api/experiments/EXP-K5/kits",
                headers("s2", "SUPPLY", "kf-kit-51"),
                "{\"kitNo\":\"KIT-1\",\"treatment\":\"A\"}").getStatusCode().value());
        // 同包编号换键：业务冲突 409
        assertEquals(409, registerKit("EXP-K5", "KIT-1", "A", "kf-kit-52").getStatusCode().value());

        // 失败不占键：实验不存在 404 后，同键可成功
        assertEquals(404, registerKit("EXP-K5-MISSING", "KIT-9", "A", "kf-kit-53")
                .getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'kf-kit-53'",
                Integer.class));
        createExperiment("EXP-K5-MISSING", 2, "kf-create-55");
        assertEquals(201, registerKit("EXP-K5-MISSING", "KIT-9", "A", "kf-kit-53")
                .getStatusCode().value());
    }

    @Test
    void pickup_idempotentReplay_samePackageCode_noTreatmentLeak() throws Exception {
        createExperiment("EXP-K6", 2, "kf-create-6");
        register("EXP-K6", "P1", "kf-alloc-61");
        assertEquals(201, registerKit("EXP-K6", "KIT-1", "A", "kf-kit-61").getStatusCode().value());

        ResponseEntity<String> first = pickup("EXP-K6", "P1", "c1", "kf-pick-61");
        assertEquals(201, first.getStatusCode().value());
        ResponseEntity<String> replay = pickup("EXP-K6", "P1", "c1", "kf-pick-61");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        JsonNode node = json(replay);
        assertTrue(node.path("packageCode").asText().matches("[A-Z2-9]{12}"));
        assertFalse(node.has("treatment"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM kit_pickup WHERE experiment_id = 'EXP-K6'", Integer.class));

        // 同键异参（换参与者）409；同键异角色 409
        register("EXP-K6", "P2", "kf-alloc-62");
        assertEquals(409, pickup("EXP-K6", "P2", "c1", "kf-pick-61").getStatusCode().value());
        assertEquals(403, post("/api/experiments/EXP-K6/participants/P1/kit-pickup",
                headers("s1", "SUPPLY", "kf-pick-61"), null).getStatusCode().value());
    }

    @Test
    void unblindAndPickup_independent_noExtraLeak() throws Exception {
        createExperiment("EXP-K7", 2, "kf-create-7");
        register("EXP-K7", "P1", "kf-alloc-71");
        register("EXP-K7", "P2", "kf-alloc-72");
        assertEquals(201, registerKit("EXP-K7", "KIT-1", "A", "kf-kit-71").getStatusCode().value());
        assertEquals(201, registerKit("EXP-K7", "KIT-2", "A", "kf-kit-72").getStatusCode().value());

        // 先领取再揭盲：互不影响
        ResponseEntity<String> picked = pickup("EXP-K7", "P1", "c1", "kf-pick-71");
        assertEquals(201, picked.getStatusCode().value());
        assertFalse(json(picked).has("treatment"));

        assertEquals(201, post("/api/experiments/EXP-K7/participants/P1/unblind-requests",
                headers("c1", "COORDINATOR", "kf-ub-71"), "{\"reason\":\"合成测试\"}")
                .getStatusCode().value());
        String ubId = jdbc.queryForObject(
                "SELECT id FROM unblind_request WHERE experiment_id = 'EXP-K7'", String.class);
        assertEquals(200, post("/api/unblind-requests/" + ubId + "/approval",
                headers("r1", "REVIEWER", "kf-ub-72"), null).getStatusCode().value());
        ResponseEntity<String> result = get("/api/unblind-requests/" + ubId + "/result",
                headers("c1", "COORDINATOR", null));
        assertEquals(200, result.getStatusCode().value());
        assertEquals("A", json(result).path("treatment").asText());

        // 已批准揭盲后另一参与者仍可正常领取，反之无依赖
        assertEquals(201, pickup("EXP-K7", "P2", "c1", "kf-pick-72").getStatusCode().value());
    }
}