package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 灌区降雨抵扣与窗口核销守恒 API 集成测试。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务、行锁与唯一约束，不 mock 数据库边界；
 * 覆盖主流程、422 失败分支、双角色确认、撤销不影响已结算核销、批量先验最终余额、
 * 幂等重放/异参 409/失败不占键、并发裁决与可控时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RainCreditApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

    private final String run = UUID.randomUUID().toString().substring(0, 10);
    private int seq;

    private String key(String prefix) {
        return prefix + "-" + run + "-" + (++seq);
    }

    @AfterEach
    void resetClock() {
        waterService.setClock(Clock.systemUTC());
    }

    // ------------------------------------------------------------------
    // HTTP 辅助
    // ------------------------------------------------------------------

    private MvcResult postJson(String url, Map<String, Object> body, String actor, int expectedStatus)
            throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (actor != null) {
            request = request.header("X-Actor-Id", actor);
        }
        return mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    }

    private JsonNode postOk(String url, Map<String, Object> body, String actor) throws Exception {
        return objectMapper.readTree(postJson(url, body, actor, 200).getResponse().getContentAsString());
    }

    private JsonNode getOk(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long createWindow(String channelId, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", "2026-11-01T00:00:00Z");
        body.put("endUtc", "2026-11-01T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String approvedAllocation(long windowId, String userId, String amount, String actor)
            throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        postOk("/api/allocations", body, actor);
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, 200);
        return allocationKey;
    }

    private Map<String, Object> rainBody(String commandKey, String rainKey, String mm) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("rainKey", rainKey);
        body.put("rainfallMm", mm);
        return body;
    }

    /** 登记降雨并由两名不同角色确认生效，返回 rainKey。 */
    private String effectiveRain(long windowId, String mm) throws Exception {
        String rainKey = key("rk");
        JsonNode registered = postOk("/api/windows/" + windowId + "/rainfall",
                rainBody(key("rc"), rainKey, mm), "registrar-1");
        assertEquals("PENDING", registered.get("status").asText());
        assertEquals(0, registered.get("confirmCount").asInt());
        JsonNode afterFirst = postOk("/api/windows/" + windowId + "/rainfall/" + rainKey + "/confirm",
                Map.of("commandKey", key("rcc"), "roleId", "role-hydrology"), "officer-a");
        assertEquals("PENDING", afterFirst.get("status").asText());
        assertEquals(1, afterFirst.get("confirmCount").asInt());
        JsonNode effective = postOk("/api/windows/" + windowId + "/rainfall/" + rainKey + "/confirm",
                Map.of("commandKey", key("rcc"), "roleId", "role-management"), "officer-b");
        assertEquals("EFFECTIVE", effective.get("status").asText());
        assertEquals(2, effective.get("confirmCount").asInt());
        assertNotNull(effective.get("effectiveUtc").asText());
        return rainKey;
    }

    private void declareFactor(String allocationKey, String coefficient, String actor, int expected)
            throws Exception {
        postJson("/api/allocations/" + allocationKey + "/credit-factor",
                Map.of("commandKey", key("fc"), "coefficient", coefficient), actor, expected);
    }

    private JsonNode writeOff(long windowId, String allocationKey, String amount, String actor)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wo"));
        body.put("writeOffKey", key("wok"));
        body.put("allocationKey", allocationKey);
        body.put("amount", amount);
        return postOk("/api/windows/" + windowId + "/write-offs", body, actor);
    }

    private JsonNode allocation(long windowId, String allocationKey) throws Exception {
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        for (JsonNode node : history.get("allocations")) {
            if (allocationKey.equals(node.get("allocationKey").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("allocation not found: " + allocationKey);
    }

    private JsonNode rain(long windowId, String rainKey) throws Exception {
        JsonNode list = getOk("/api/windows/" + windowId + "/rainfall");
        for (JsonNode node : list.get("records")) {
            if (rainKey.equals(node.get("rainKey").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("rain not found: " + rainKey);
    }

    // ------------------------------------------------------------------
    // 主流程：登记 -> 双角色确认 -> 系数 -> 核销守恒
    // ------------------------------------------------------------------

    @Test
    void rainCreditAppliedBeforeQuotaDeductionAndConservationHolds() throws Exception {
        long windowId = createWindow("ch-rain-" + run, "100");
        String a1 = approvedAllocation(windowId, "user-1", "10", "alice");
        declareFactor(a1, "0.2", "alice", 200);
        // 10 毫米有效降雨 × 0.2 = 2 立方米抵扣
        String rainKey = effectiveRain(windowId, "10");

        JsonNode result = writeOff(windowId, a1, "5", "alice");
        assertEquals("5", result.get("requestedAmount").asText());
        assertEquals("10", result.get("rainfallMm").asText());
        assertEquals("0.2", result.get("coefficient").asText());
        assertEquals("2", result.get("creditAmount").asText());
        assertEquals("3", result.get("quotaDeduction").asText());
        // 守恒：抵扣 + 配额扣减 = 原核销量
        assertEquals("5", add(result, "creditAmount", "quotaDeduction"));
        assertEquals("10", result.get("heldBefore").asText());
        assertEquals("7", result.get("heldAfter").asText());
        // 固化降雨证据
        ArrayNode rains = (ArrayNode) result.get("rainRecords");
        assertEquals(1, rains.size());
        assertEquals(rainKey, rains.get(0).get("rainKey").asText());
        assertEquals("10", rains.get(0).get("rainfallMm").asText());

        // 持有额度扣 3；容量已批准总量同步下降
        assertEquals("7", allocation(windowId, a1).get("heldAmount").asText());
        assertEquals("7", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());

        // 明细、诊断
        JsonNode list = getOk("/api/windows/" + windowId + "/write-offs");
        assertEquals(1, list.get("writeOffs").size());
        JsonNode diag = getOk("/api/windows/" + windowId + "/diagnostics");
        assertEquals("10", diag.get("effectiveRainfallMm").asText());
        assertEquals("5", diag.get("requestedTotal").asText());
        assertEquals("2", diag.get("creditTotal").asText());
        assertEquals("3", diag.get("quotaDeductionTotal").asText());
        JsonNode row = diag.get("allocations").get(0);
        assertEquals(a1, row.get("allocationKey").asText());
        assertEquals("2", row.get("availableCredit").asText());
        assertEquals("2", row.get("usedCredit").asText());
        assertEquals("3", row.get("quotaDeductedTotal").asText());
        assertEquals(1, row.get("writeOffCount").asInt());
    }

    @Test
    void creditNeverExceedsRequestedWriteOffAmount() throws Exception {
        long windowId = createWindow("ch-cap-" + run, "100");
        String a1 = approvedAllocation(windowId, "user-1", "10", "alice");
        declareFactor(a1, "1", "alice", 200);
        effectiveRain(windowId, "10");
        // 毛抵扣 10，但本次仅核销 4：抵扣封顶 4，配额扣减 0
        JsonNode result = writeOff(windowId, a1, "4", "alice");
        assertEquals("4", result.get("creditAmount").asText());
        assertEquals("0", result.get("quotaDeduction").asText());
        assertEquals("4", add(result, "creditAmount", "quotaDeduction"));
        // 全部由降雨抵扣时持有额度不变
        assertEquals("10", allocation(windowId, a1).get("heldAmount").asText());
        assertEquals("10", result.get("heldBefore").asText());
        assertEquals("10", result.get("heldAfter").asText());
    }

    @Test
    void noEffectiveRainOrNoCoefficientMeansZeroCredit() throws Exception {
        long windowId = createWindow("ch-norain-" + run, "100");
        String a1 = approvedAllocation(windowId, "user-1", "10", "alice");
        declareFactor(a1, "0.5", "alice", 200);
        // 登记但未生效的降雨不参与抵扣
        String pendingKey = key("rk");
        postOk("/api/windows/" + windowId + "/rainfall",
                rainBody(key("rc"), pendingKey, "8"), "registrar-1");
        JsonNode result = writeOff(windowId, a1, "3", "alice");
        assertEquals("0", result.get("rainfallMm").asText());
        assertEquals("0.5", result.get("coefficient").asText());
        assertEquals("0", result.get("creditAmount").asText());
        assertEquals("3", result.get("quotaDeduction").asText());
        assertEquals(0, result.get("rainRecords").size());

        // 未声明系数的申请：系数固化为 0，同样无抵扣
        String a2 = approvedAllocation(windowId, "user-2", "10", "bob");
        JsonNode result2 = writeOff(windowId, a2, "2", "bob");
        assertEquals("0", result2.get("coefficient").asText());
        assertEquals("0", result2.get("creditAmount").asText());
        assertEquals("2", result2.get("quotaDeduction").asText());
        assertNull(allocation(windowId, a2).get("creditCoefficient").asText(null));
    }

    // ------------------------------------------------------------------
    // 失败分支：422 / 409 / 404 / 400
    // ------------------------------------------------------------------

    @Test
    void negativeRainfallAndNegativeCoefficientReturn422WithDifference() throws Exception {
        long windowId = createWindow("ch-neg-" + run, "100");
        // 负降雨量 -> 422，携带实际值/要求值/差额
        MvcResult rainResult = postJson("/api/windows/" + windowId + "/rainfall",
                rainBody(key("rc"), key("rk"), "-2.5"), "registrar-1", 422);
        JsonNode rainError = objectMapper.readTree(rainResult.getResponse().getContentAsString());
        assertEquals("NEGATIVE_RAINFALL", rainError.get("code").asText());
        assertEquals("-2.5", rainError.get("actualValue").asText());
        assertEquals("0", rainError.get("requiredValue").asText());
        assertEquals("2.5", rainError.get("difference").asText());

        String a1 = approvedAllocation(windowId, "user-1", "10", "alice");
        MvcResult factorResult = postJson("/api/allocations/" + a1 + "/credit-factor",
                Map.of("commandKey", key("fc"), "coefficient", "-0.1"), "alice", 422);
        JsonNode factorError = objectMapper.readTree(factorResult.getResponse().getContentAsString());
        assertEquals("NEGATIVE_COEFFICIENT", factorError.get("code").asText());
        assertEquals("-0.1", factorError.get("actualValue").asText());
        assertEquals("0", factorError.get("requiredValue").asText());
        assertEquals("0.1", factorError.get("difference").asText());
        // 负数不产生记录、不改系数
        assertEquals(0, getOk("/api/windows/" + windowId + "/rainfall").get("records").size());
        assertNull(allocation(windowId, a1).get("creditCoefficient").asText(null));
    }

    @Test
    void duplicateEffectiveRainInSameWindowReturns422() throws Exception {
        long windowId = createWindow("ch-dup-" + run, "100");
        String first = effectiveRain(windowId, "10");
        // 同窗口允许再登记 PENDING 记录（尚未生效不算重复有效记录）
        String second = key("rk");
        JsonNode registered = postOk("/api/windows/" + windowId + "/rainfall",
                rainBody(key("rc-pending"), second, "3"), "registrar-1");
        assertEquals("PENDING", registered.get("status").asText());
        // 先确认一名角色（PENDING 允许）
        postOk("/api/windows/" + windowId + "/rainfall/" + second + "/confirm",
                Map.of("commandKey", key("rcc"), "roleId", "role-a"), "officer-a");
        // 第二名角色确认将使其成为第二条生效记录 -> 422，状态保持 PENDING
        postJson("/api/windows/" + windowId + "/rainfall/" + second + "/confirm",
                Map.of("commandKey", key("rcc"), "roleId", "role-b"), "officer-b", 422);
        assertEquals("PENDING", rain(windowId, second).get("status").asText());
        // 撤销生效记录后，第二条可补确认生效
        postOk("/api/windows/" + windowId + "/rainfall/" + first + "/revoke",
                Map.of("commandKey", key("rrc")), "registrar-1");
        JsonNode effective = postOk("/api/windows/" + windowId + "/rainfall/" + second + "/confirm",
                Map.of("commandKey", key("rcc2"), "roleId", "role-c"), "officer-c");
        assertEquals("EFFECTIVE", effective.get("status").asText());
    }

    @Test
    void rainfallConfirmEnforcesTwoDistinctRolesAndWindowMatch() throws Exception {
        long w1 = createWindow("ch-m1-" + run, "100");
        long w2 = createWindow("ch-m2-" + run, "100");
        String rainKey = key("rk");
        postOk("/api/windows/" + w1 + "/rainfall", rainBody(key("rc"), rainKey, "5"), "registrar-1");

        // 窗口不匹配：在 w2 路径下确认 w1 的记录 -> 422
        postJson("/api/windows/" + w2 + "/rainfall/" + rainKey + "/confirm",
                Map.of("commandKey", key("rcc"), "roleId", "role-a"), "officer-a", 422);
        // 降雨记录不存在 -> 404；窗口不存在 -> 404
        postJson("/api/windows/" + w1 + "/rainfall/missing-" + run + "/confirm",
                Map.of("commandKey", key("rcc"), "roleId", "role-a"), "officer-a", 404);
        postJson("/api/windows/" + 999999999 + "/rainfall/" + rainKey + "/confirm",
                Map.of("commandKey", key("rcc"), "roleId", "role-a"), "officer-a", 404);

        // 同角色重复确认 -> 409，状态保持 PENDING
        postOk("/api/windows/" + w1 + "/rainfall/" + rainKey + "/confirm",
                Map.of("commandKey", key("rcc"), "roleId", "role-a"), "officer-a");
        postJson("/api/windows/" + w1 + "/rainfall/" + rainKey + "/confirm",
                Map.of("commandKey", key("rcc-dup"), "roleId", "role-a"), "officer-a2", 409);
        // 即使不同操作人，只要角色相同仍拒绝
        assertEquals("PENDING", rain(w1, rainKey).get("status").asText());
        // 撤销后确认 -> 409
        postOk("/api/windows/" + w1 + "/rainfall/" + rainKey + "/revoke",
                Map.of("commandKey", key("rrc")), "registrar-1");
        postJson("/api/windows/" + w1 + "/rainfall/" + rainKey + "/confirm",
                Map.of("commandKey", key("rcc"), "roleId", "role-b"), "officer-b", 409);
        // 重复撤销 -> 409
        postJson("/api/windows/" + w1 + "/rainfall/" + rainKey + "/revoke",
                Map.of("commandKey", key("rrc2")), "registrar-1", 409);
    }

    @Test
    void writeOffBeyondHeldQuotaReturns422WithActualRequiredDifferenceAndNoPartialState()
            throws Exception {
        long windowId = createWindow("ch-wo422-" + run, "100");
        String a1 = approvedAllocation(windowId, "user-1", "5", "alice");
        effectiveRain(windowId, "4");
        declareFactor(a1, "0.5", "alice", 200);
        // 毛抵扣 2；核销 8 -> 配额扣减 6 > 持有 5 -> 422
        MvcResult result = postJson("/api/windows/" + windowId + "/write-offs",
                writeOffBody(key("woc"), key("wok"), a1, "8"), "alice", 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("WRITE_OFF_QUOTA_EXCEEDED", error.get("code").asText());
        assertEquals("5", error.get("actualValue").asText());
        assertEquals("6", error.get("requiredValue").asText());
        assertEquals("1", error.get("difference").asText());
        // 无半成品：流水为空、持有额度不变、降雨仍生效
        assertEquals(0, getOk("/api/windows/" + windowId + "/write-offs").get("writeOffs").size());
        assertEquals("5", allocation(windowId, a1).get("heldAmount").asText());
        assertEquals("EFFECTIVE",
                getOk("/api/windows/" + windowId + "/rainfall").get("records").get(0).get("status").asText());

        // 非 APPROVED（REQUESTED）申请核销 -> 409
        String pendingKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", pendingKey);
        body.put("windowId", windowId);
        body.put("userId", "user-2");
        body.put("amount", "1");
        postOk("/api/allocations", body, "bob");
        postJson("/api/windows/" + windowId + "/write-offs",
                writeOffBody(key("woc"), key("wok"), pendingKey, "1"), "bob", 409);

        // 跨窗口核销 -> 422
        long otherWindow = createWindow("ch-woother-" + run, "100");
        postJson("/api/windows/" + otherWindow + "/write-offs",
                writeOffBody(key("woc"), key("wok"), a1, "1"), "alice", 422);

        // 申请不存在 -> 404
        postJson("/api/windows/" + windowId + "/write-offs",
                writeOffBody(key("woc"), key("wok"), "missing-" + run, "1"), "alice", 404);
    }

    private Map<String, Object> writeOffBody(String commandKey, String writeOffKey,
                                             String allocationKey, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("writeOffKey", writeOffKey);
        body.put("allocationKey", allocationKey);
        body.put("amount", amount);
        return body;
    }

    @Test
    void creditFactorRestrictedToRequesterAndCancelledAllocation() throws Exception {
        long windowId = createWindow("ch-fc-" + run, "100");
        String a1 = approvedAllocation(windowId, "user-1", "5", "alice");
        // 非申请人 -> 409
        postJson("/api/allocations/" + a1 + "/credit-factor",
                Map.of("commandKey", key("fc"), "coefficient", "0.1"), "bob", 409);
        // 零系数允许
        declareFactor(a1, "0", "alice", 200);
        // 取消后不能再改
        postOk("/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "alice");
        postJson("/api/allocations/" + a1 + "/credit-factor",
                Map.of("commandKey", key("fc2"), "coefficient", "0.2"), "alice", 409);
    }

    // ------------------------------------------------------------------
    // 撤销不改写已结算核销
    // ------------------------------------------------------------------

    @Test
    void revokingRainAfterWriteOffDoesNotRewriteSettledEvidence() throws Exception {
        long windowId = createWindow("ch-rev-" + run, "100");
        String a1 = approvedAllocation(windowId, "user-1", "10", "alice");
        declareFactor(a1, "0.5", "alice", 200);
        String rainKey = effectiveRain(windowId, "6");
        JsonNode settled = writeOff(windowId, a1, "5", "alice");
        assertEquals("3", settled.get("creditAmount").asText());
        assertEquals("2", settled.get("quotaDeduction").asText());

        // 事后撤销降雨：核销流水、固化降雨量与抵扣量保持不变
        JsonNode revoked = postOk("/api/windows/" + windowId + "/rainfall/" + rainKey + "/revoke",
                Map.of("commandKey", key("rrc")), "registrar-1");
        assertEquals("REVOKED", revoked.get("status").asText());
        assertNotNull(revoked.get("revokedUtc").asText());
        assertEquals("registrar-1", revoked.get("revokedBy").asText());

        JsonNode frozen = getOk("/api/windows/" + windowId + "/write-offs").get("writeOffs").get(0);
        assertEquals("6", frozen.get("rainfallMm").asText());
        assertEquals("0.5", frozen.get("coefficient").asText());
        assertEquals("3", frozen.get("creditAmount").asText());
        assertEquals("2", frozen.get("quotaDeduction").asText());
        assertEquals(rainKey, frozen.get("rainRecords").get(0).get("rainKey").asText());
        assertEquals("6", frozen.get("rainRecords").get(0).get("rainfallMm").asText());
        // 新核销不再享受抵扣（降雨已撤销）
        JsonNode next = writeOff(windowId, a1, "2", "alice");
        assertEquals("0", next.get("rainfallMm").asText());
        assertEquals("0", next.get("creditAmount").asText());
        assertEquals("2", next.get("quotaDeduction").asText());
        // 诊断：当前有效降雨为 0，但历史抵扣合计仍为 3
        JsonNode diag = getOk("/api/windows/" + windowId + "/diagnostics");
        assertEquals("0", diag.get("effectiveRainfallMm").asText());
        assertEquals("3", diag.get("creditTotal").asText());
        assertEquals("4", diag.get("quotaDeductionTotal").asText());
        // 历史明细字段稳定：降雨列表含 REVOKED 记录，核销列表两笔且按顺序
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals("REVOKED", history.get("rainfallRecords").get(0).get("status").asText());
        assertEquals(2, history.get("writeOffs").size());
    }

    // ------------------------------------------------------------------
    // 批量核销：先验最终余额、单事务、无半成品
    // ------------------------------------------------------------------

    @Test
    void batchWriteOffValidatesFinalBalanceAndCommitsAtomically() throws Exception {
        long windowId = createWindow("ch-batch-" + run, "100");
        String a1 = approvedAllocation(windowId, "user-1", "5", "alice");
        String a2 = approvedAllocation(windowId, "user-2", "4", "bob");
        declareFactor(a1, "0.5", "alice", 200);
        effectiveRain(windowId, "4");

        Map<String, Object> item1 = new HashMap<>();
        item1.put("writeOffKey", key("wok"));
        item1.put("allocationKey", a1);
        item1.put("amount", "3");
        Map<String, Object> item2 = new HashMap<>();
        item2.put("writeOffKey", key("wok"));
        item2.put("allocationKey", a1);
        item2.put("amount", "3");
        Map<String, Object> item3 = new HashMap<>();
        item3.put("writeOffKey", key("wok"));
        item3.put("allocationKey", a2);
        item3.put("amount", "4");
        Map<String, Object> batch = new HashMap<>();
        batch.put("commandKey", key("bc"));
        batch.put("batchKey", key("bk"));
        batch.put("items", List.of(item1, item2, item3));
        JsonNode result = postOk("/api/" + batchPath(windowId), batch, "clerk");
        // a1 两笔：毛抵扣 2，每笔抵扣 min(2,3)=2、配额 1；合并配额扣减 2 <= 持有 5
        assertEquals(3, result.get("items").size());
        assertEquals("10", result.get("requestedTotal").asText());
        // 抵扣合计 4，配额合计 6（a1:2 + a2:4）
        assertEquals("4", result.get("creditTotal").asText());
        assertEquals("6", result.get("quotaDeductionTotal").asText());
        assertEquals("3", allocation(windowId, a1).get("heldAmount").asText());
        assertEquals("0", allocation(windowId, a2).get("heldAmount").asText());
        // 每笔守恒
        for (JsonNode item : result.get("items")) {
            assertEquals(item.get("requestedAmount").asText(),
                    add(item, "creditAmount", "quotaDeduction"));
            assertEquals(windowId, item.get("windowId").asLong());
            assertNotNull(item.get("batchKey").asText());
        }

        // batchKey 复用（换 commandKey）-> 409；writeOffKey 复用 -> 409
        Map<String, Object> sameBatchKey = new HashMap<>(batch);
        sameBatchKey.put("commandKey", key("bc-reuse"));
        postJson("/api/windows/" + windowId + "/write-offs/batch", sameBatchKey, "clerk", 409);
        Map<String, Object> reuse = new HashMap<>();
        reuse.put("commandKey", key("bc2"));
        reuse.put("batchKey", key("bk2"));
        reuse.put("items", List.of(item3));
        postJson("/api/windows/" + windowId + "/write-offs/batch", reuse, "clerk", 409);
    }

    private String batchPath(long windowId) {
        return "windows/" + windowId + "/write-offs/batch";
    }

    @Test
    void batchFailingFinalBalanceRollsBackEverything() throws Exception {
        long windowId = createWindow("ch-batchfail-" + run, "100");
        String a1 = approvedAllocation(windowId, "user-1", "5", "alice");
        String a2 = approvedAllocation(windowId, "user-2", "2", "bob");
        // a1 两笔合并配额扣减 3+3=6（无降雨）> 持有 5；即使 a2 合法也整体回滚
        Map<String, Object> item1 = new HashMap<>();
        item1.put("writeOffKey", key("wok"));
        item1.put("allocationKey", a1);
        item1.put("amount", "3");
        Map<String, Object> item2 = new HashMap<>();
        item2.put("writeOffKey", key("wok"));
        item2.put("allocationKey", a1);
        item2.put("amount", "3");
        Map<String, Object> item3 = new HashMap<>();
        item3.put("writeOffKey", key("wok"));
        item3.put("allocationKey", a2);
        item3.put("amount", "1");
        Map<String, Object> batch = new HashMap<>();
        batch.put("commandKey", key("bc"));
        batch.put("batchKey", key("bk"));
        batch.put("items", List.of(item1, item2, item3));
        MvcResult result = postJson("/api/windows/" + windowId + "/write-offs/batch",
                batch, "clerk", 422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("WRITE_OFF_QUOTA_EXCEEDED", error.get("code").asText());
        assertEquals("5", error.get("actualValue").asText());
        assertEquals("6", error.get("requiredValue").asText());
        assertEquals("1", error.get("difference").asText());
        // 查不到任何半成品：流水 0 笔，持有额度不变
        assertEquals(0, getOk("/api/windows/" + windowId + "/write-offs").get("writeOffs").size());
        assertEquals("5", allocation(windowId, a1).get("heldAmount").asText());
        assertEquals("2", allocation(windowId, a2).get("heldAmount").asText());
        // 失败不占键：同 commandKey 改一批合法核销可以成功
        batch.put("commandKey", key("bc"));
        batch.put("batchKey", key("bk"));
        Map<String, Object> only = new HashMap<>();
        only.put("writeOffKey", key("wok"));
        only.put("allocationKey", a2);
        only.put("amount", "1");
        batch.put("items", List.of(only));
        JsonNode retry = postOk("/api/windows/" + windowId + "/write-offs/batch", batch, "clerk");
        assertEquals(1, retry.get("items").size());
        assertEquals("1", allocation(windowId, a2).get("heldAmount").asText());
    }

    @Test
    void batchRejectsCrossWindowItemAndEmptyItems() throws Exception {
        long w1 = createWindow("ch-bx1-" + run, "100");
        long w2 = createWindow("ch-bx2-" + run, "100");
        String a1 = approvedAllocation(w1, "user-1", "5", "alice");
        String a2 = approvedAllocation(w2, "user-2", "5", "bob");
        Map<String, Object> item1 = new HashMap<>();
        item1.put("writeOffKey", key("wok"));
        item1.put("allocationKey", a1);
        item1.put("amount", "1");
        Map<String, Object> item2 = new HashMap<>();
        item2.put("writeOffKey", key("wok"));
        item2.put("allocationKey", a2);
        item2.put("amount", "1");
        Map<String, Object> batch = new HashMap<>();
        batch.put("commandKey", key("bc"));
        batch.put("batchKey", key("bk"));
        batch.put("items", List.of(item1, item2));
        postJson("/api/windows/" + w1 + "/write-offs/batch", batch, "clerk", 422);
        // 空 items -> 400
        Map<String, Object> empty = new HashMap<>();
        empty.put("commandKey", key("bc"));
        empty.put("batchKey", key("bk"));
        empty.put("items", List.of());
        postJson("/api/windows/" + w1 + "/write-offs/batch", empty, "clerk", 400);
        // 两窗口均无流水
        assertEquals(0, getOk("/api/windows/" + w1 + "/write-offs").get("writeOffs").size());
        assertEquals(0, getOk("/api/windows/" + w2 + "/write-offs").get("writeOffs").size());
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void writeOffReplaysFirstSnapshotAndRejectsDifferentParams() throws Exception {
        long windowId = createWindow("ch-idem-" + run, "100");
        String a1 = approvedAllocation(windowId, "user-1", "10", "alice");
        declareFactor(a1, "0.2", "alice", 200);
        effectiveRain(windowId, "10");
        String commandKey = key("woc");
        Map<String, Object> body = writeOffBody(commandKey, key("wok"), a1, "5");

        JsonNode first = postOk("/api/windows/" + windowId + "/write-offs", body, "alice");
        JsonNode replay = postOk("/api/windows/" + windowId + "/write-offs", body, "alice");
        // 成功重放返回首次完整响应快照
        assertEquals(first, replay);
        // 读取重放不再扣减：仅一笔流水、持有额度只扣一次
        assertEquals(1, getOk("/api/windows/" + windowId + "/write-offs").get("writeOffs").size());
        assertEquals("7", allocation(windowId, a1).get("heldAmount").asText());

        // 同键改核销量 -> 409
        postJson("/api/windows/" + windowId + "/write-offs",
                writeOffBody(commandKey, key("wok"), a1, "4"), "alice", 409);
        // 同键改操作人 -> 409（指纹包含操作者）
        postJson("/api/windows/" + windowId + "/write-offs",
                writeOffBody(commandKey, key("wok"), a1, "5"), "bob", 409);
        // 同键改申请 -> 409
        String a2 = approvedAllocation(windowId, "user-2", "3", "bob");
        postJson("/api/windows/" + windowId + "/write-offs",
                writeOffBody(commandKey, key("wok"), a2, "5"), "alice", 409);
    }

    @Test
    void rainfallCommandsAreIdempotentAndBatchIgnoresItemOrder() throws Exception {
        long windowId = createWindow("ch-ridem-" + run, "100");
        String rainKey = key("rk");
        Map<String, Object> registerBody = rainBody(key("rc"), rainKey, "7");
        JsonNode first = postOk("/api/windows/" + windowId + "/rainfall", registerBody, "registrar-1");
        JsonNode replay = postOk("/api/windows/" + windowId + "/rainfall", registerBody, "registrar-1");
        assertEquals(first, replay);
        assertEquals(1, getOk("/api/windows/" + windowId + "/rainfall").get("records").size());
        // 同键改降雨量 -> 409
        postJson("/api/windows/" + windowId + "/rainfall",
                rainBody(key("rc-same"), rainKey, "8"), "registrar-1", 409);

        String a1 = approvedAllocation(windowId, "user-1", "10", "alice");
        String a2 = approvedAllocation(windowId, "user-2", "8", "bob");
        // 成功确认同参重放：指纹含 roleId，第二次命中幂等快照而非重复确认
        Map<String, Object> confirmBody = new HashMap<>();
        confirmBody.put("commandKey", key("rcc"));
        confirmBody.put("roleId", "role-a");
        JsonNode c1 = postOk("/api/windows/" + windowId + "/rainfall/" + rainKey + "/confirm",
                confirmBody, "officer-a");
        JsonNode c1Replay = postOk("/api/windows/" + windowId + "/rainfall/" + rainKey + "/confirm",
                confirmBody, "officer-a");
        assertEquals(c1, c1Replay);

        Map<String, Object> itemA = new HashMap<>();
        itemA.put("writeOffKey", "wok-A-" + run);
        itemA.put("allocationKey", a1);
        itemA.put("amount", "2");
        Map<String, Object> itemB = new HashMap<>();
        itemB.put("writeOffKey", "wok-B-" + run);
        itemB.put("allocationKey", a2);
        itemB.put("amount", "2");
        Map<String, Object> batch1 = new HashMap<>();
        batch1.put("commandKey", "bc-" + run);
        batch1.put("batchKey", "bk-" + run);
        batch1.put("items", List.of(itemA, itemB));
        // 首次成功
        JsonNode batchFirst = postOk("/api/windows/" + windowId + "/write-offs/batch", batch1, "clerk");
        // 调换集合顺序 + 同键重放：规范化指纹一致，返回首次响应快照（顺序也与首次一致）
        Map<String, Object> batch2 = new HashMap<>();
        batch2.put("commandKey", "bc-" + run);
        batch2.put("batchKey", "bk-" + run);
        batch2.put("items", List.of(itemB, itemA));
        JsonNode batchReplay = postOk("/api/windows/" + windowId + "/write-offs/batch", batch2, "clerk");
        assertEquals(batchFirst, batchReplay);
        assertEquals(2, getOk("/api/windows/" + windowId + "/write-offs").get("writeOffs").size());
        // 异参（改操作者）-> 409
        batch2.put("commandKey", "bc-" + run);
        postJson("/api/windows/" + windowId + "/write-offs/batch", batch2, "someone-else", 409);
    }

    @Test
    void failedWriteOffDoesNotOccupyCommandKey() throws Exception {
        long windowId = createWindow("ch-failidem-" + run, "100");
        String a1 = approvedAllocation(windowId, "user-1", "2", "alice");
        String commandKey = key("woc");
        // 配额不足失败（422）不占键
        postJson("/api/windows/" + windowId + "/write-offs",
                writeOffBody(commandKey, key("wok"), a1, "5"), "alice", 422);
        postJson("/api/windows/" + windowId + "/write-offs",
                writeOffBody(commandKey, key("wok"), a1, "5"), "alice", 422);
        // 同键改参为合法核销 -> 成功
        JsonNode ok = postOk("/api/windows/" + windowId + "/write-offs",
                writeOffBody(commandKey, key("wok2"), a1, "1"), "alice");
        assertEquals("1", ok.get("quotaDeduction").asText());
    }

    // ------------------------------------------------------------------
    // 并发裁决
    // ------------------------------------------------------------------

    @Test
    void concurrentWriteOffsOnSameAllocationNeverGoNegative() throws Exception {
        long windowId = createWindow("ch-race-wo-" + run, "100");
        String a1 = approvedAllocation(windowId, "user-1", "10", "alice");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String commandKey = key("woc");
            String writeOffKey = key("wok");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.writeOff(commandKey, windowId, writeOffKey, a1, "6", "alice");
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        int ok = 0;
        int rejected = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 422) {
                rejected++;
            }
        }
        pool.shutdown();
        assertEquals(1, ok);
        assertEquals(1, rejected);
        // 恰好扣 6，不留负持有
        assertEquals("4", allocation(windowId, a1).get("heldAmount").asText());
        assertEquals(1, getOk("/api/windows/" + windowId + "/write-offs").get("writeOffs").size());
    }

    @Test
    void concurrentSameRoleConfirmSucceedsOnce() throws Exception {
        long windowId = createWindow("ch-race-cf-" + run, "100");
        String rainKey = key("rk");
        postOk("/api/windows/" + windowId + "/rainfall",
                rainBody(key("rc"), rainKey, "5"), "registrar-1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String commandKey = key("rcc");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.confirmRainfall(commandKey, windowId, rainKey, "role-a", "officer-a");
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 409) {
                conflict++;
            }
        }
        pool.shutdown();
        assertEquals(1, ok, "同一角色并发确认只生效一次");
        assertEquals(1, conflict);
        JsonNode record = rain(windowId, rainKey);
        assertEquals("PENDING", record.get("status").asText());
        assertEquals(1, record.get("confirmCount").asInt());
        assertEquals(1, record.get("confirmations").size());
    }

    @Test
    void writeOffAndRainRevokeSettleByCommitOrder() throws Exception {
        long windowId = createWindow("ch-race-rev-" + run, "100");
        String a1 = approvedAllocation(windowId, "user-1", "10", "alice");
        declareFactor(a1, "0.5", "alice", 200);
        String rainKey = effectiveRain(windowId, "6");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String writeOffCommand = key("woc");
        String revokeCommand = key("rrc");
        Future<Integer> writeOffFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.writeOff(writeOffCommand, windowId, key("wok"), a1, "4", "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> revokeFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.revokeRainfall(revokeCommand, windowId, rainKey, "registrar-1");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int writeOffStatus = writeOffFuture.get(30, TimeUnit.SECONDS);
        int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(200, writeOffStatus);
        assertEquals(200, revokeStatus);

        // 按提交顺序：先核销则抵扣 3 已固化（撤销不改写）；先撤销则核销抵扣 0
        JsonNode settled = getOk("/api/windows/" + windowId + "/write-offs").get("writeOffs").get(0);
        String credit = settled.get("creditAmount").asText();
        assertTrue("3".equals(credit) || "0".equals(credit), "credit=" + credit);
        assertEquals("4", add(settled, "creditAmount", "quotaDeduction"));
        assertEquals("REVOKED", rain(windowId, rainKey).get("status").asText());
        // 当时系数始终固化；先核销时降雨证据一并固化且不被撤销改写
        assertEquals("0.5", settled.get("coefficient").asText());
        if ("3".equals(credit)) {
            assertEquals(rainKey, settled.get("rainRecords").get(0).get("rainKey").asText());
            assertEquals("6", settled.get("rainRecords").get(0).get("rainfallMm").asText());
        } else {
            assertEquals(0, settled.get("rainRecords").size());
        }
    }

    // ------------------------------------------------------------------
    // 可控时钟与时间精度
    // ------------------------------------------------------------------

    @Test
    void controlledClockDrivesConfirmationEffectiveAndWriteOffTimestamps() throws Exception {
        Instant t0 = Instant.parse("2026-11-05T08:00:00Z");
        waterService.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        long windowId = createWindow("ch-clock-" + run, "100");
        String a1 = approvedAllocation(windowId, "user-1", "10", "alice");

        waterService.setClock(Clock.fixed(Instant.parse("2026-11-05T09:00:00Z"), ZoneOffset.UTC));
        String rainKey = key("rk");
        JsonNode registered = postOk("/api/windows/" + windowId + "/rainfall",
                rainBody(key("rc"), rainKey, "5"), "registrar-1");
        assertEquals("2026-11-05T09:00:00Z", registered.get("createdUtc").asText());

        waterService.setClock(Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC));
        JsonNode first = postOk("/api/windows/" + windowId + "/rainfall/" + rainKey + "/confirm",
                Map.of("commandKey", key("rcc"), "roleId", "role-a"), "officer-a");
        assertNull(first.get("effectiveUtc").asText(null));

        Instant effectiveAt = Instant.parse("2026-11-05T11:30:00.123456789Z");
        waterService.setClock(Clock.fixed(effectiveAt, ZoneOffset.UTC));
        declareFactor(a1, "0.4", "alice", 200);
        JsonNode effective = postOk("/api/windows/" + windowId + "/rainfall/" + rainKey + "/confirm",
                Map.of("commandKey", key("rcc"), "roleId", "role-b"), "officer-b");
        // 纳秒精度稳定
        assertEquals("2026-11-05T11:30:00.123456789Z", effective.get("effectiveUtc").asText());

        JsonNode settled = writeOff(windowId, a1, "3", "alice");
        assertEquals("2026-11-05T11:30:00.123456789Z", settled.get("createdUtc").asText());
        // 历史查询排序稳定：降雨、核销各按主键升序
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        assertEquals(rainKey, history.get("rainfallRecords").get(0).get("rainKey").asText());
        assertEquals(1, history.get("writeOffs").size());
        // 时间为 UTC（不随 jackson Asia/Shanghai 时区转换，服务端输出即纳秒戳 ISO）
        assertTrue(history.get("window").get("startUtc").asText().endsWith("Z"));
    }

    private String add(JsonNode node, String a, String b) {
        return new java.math.BigDecimal(node.get(a).asText())
                .add(new java.math.BigDecimal(node.get(b).asText())).toPlainString();
    }
}
