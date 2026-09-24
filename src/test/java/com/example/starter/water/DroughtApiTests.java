package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 旱情分级比例削减 API 集成测试：比例削减取整、总量守恒、等级升降重算、回补越界、
 * 削减期间批准/转让校验、curtailmentKey 与 commandKey 幂等、并发提交顺序裁决。
 * 全部走 H2 内存库（MODE=MySQL）真实事务与行锁，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DroughtApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private int seq;

    private String key(String prefix) {
        return prefix + "-" + run + "-" + (++seq);
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
        body.put("startUtc", "2026-12-01T00:00:00Z");
        body.put("endUtc", "2026-12-01T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private String submit(long windowId, String userId, String amount, String priority, String actor)
            throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        if (priority != null) {
            body.put("priority", priority);
        }
        postOk("/api/allocations", body, actor);
        return allocationKey;
    }

    private void approve(String allocationKey, int expectedStatus) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus);
    }

    private Map<String, Object> droughtBody(String commandKey, String curtailmentKey, String level,
                                            int e, int n, int d, long expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("curtailmentKey", curtailmentKey);
        body.put("level", level);
        body.put("essentialPct", e);
        body.put("normalPct", n);
        body.put("deferrablePct", d);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private JsonNode declare(long windowId, String level, int e, int n, int d, long expectedVersion)
            throws Exception {
        return postOk("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), key("dk"), level, e, n, d, expectedVersion), null);
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

    private JsonNode detail(JsonNode declaration, String allocationKey) {
        for (JsonNode detail : declaration.get("details")) {
            if (allocationKey.equals(detail.get("allocationKey").asText())) {
                return detail;
            }
        }
        throw new IllegalStateException("detail not found: " + allocationKey);
    }

    private JsonNode capacity(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/capacity");
    }

    // ------------------------------------------------------------------
    // 主流程：分级比例削减、取整守恒、查询
    // ------------------------------------------------------------------

    @Test
    void droughtCutsByPriorityWithConservationRounding() throws Exception {
        long windowId = createWindow("ch-d-" + run, "100");
        String e1 = submit(windowId, "u-e", "10", "ESSENTIAL", "alice");
        approve(e1, 200);
        // 3 笔 NORMAL 各 1.001，用于制造同级取整差额
        String n1 = submit(windowId, "u-n1", "1.001", "NORMAL", "bob");
        String n2 = submit(windowId, "u-n2", "1.001", "NORMAL", "carol");
        String n3 = submit(windowId, "u-n3", "1.001", "NORMAL", "dave");
        approve(n1, 200);
        approve(n2, 200);
        approve(n3, 200);
        String d1 = submit(windowId, "u-d", "4", "DEFERRABLE", "erin");
        approve(d1, 200);

        // LEVEL2：ESSENTIAL 0% / NORMAL 35% / DEFERRABLE 100%
        JsonNode declaration = declare(windowId, "LEVEL2", 0, 35, 100, 0);
        assertEquals("LEVEL2", declaration.get("level").asText());
        assertEquals(0, declaration.get("essentialPct").asInt());
        assertEquals(35, declaration.get("normalPct").asInt());
        assertEquals(100, declaration.get("deferrablePct").asInt());
        assertEquals(0, declaration.get("expectedVersion").asLong());
        assertEquals(1, declaration.get("version").asLong());
        assertEquals("ACTIVE", declaration.get("status").asText());
        assertEquals(5, declaration.get("details").size());

        // ESSENTIAL 不削减；DEFERRABLE 全额削减为 0 但仍 APPROVED
        assertEquals("10", detail(declaration, e1).get("targetHeld").asText());
        assertEquals("0", detail(declaration, e1).get("reducedAmount").asText());
        assertEquals("0", detail(declaration, d1).get("targetHeld").asText());
        assertEquals("4", detail(declaration, d1).get("reducedAmount").asText());
        // NORMAL：1.001*0.65=0.65065 HALF_UP=0.651，三笔和 1.953；
        // 级总量 3.003*0.65=1.95195 HALF_UP=1.952，-0.001 差额由申请标识升序首笔承担
        List<String> normalKeys = List.of(n1, n2, n3).stream().sorted().toList();
        assertEquals("0.65", detail(declaration, normalKeys.get(0)).get("targetHeld").asText(),
                "申请标识升序首笔承担取整差额（接口字符串去尾零，0.650 显示为 0.65）");
        assertEquals("0.651", detail(declaration, normalKeys.get(1)).get("targetHeld").asText());
        assertEquals("0.651", detail(declaration, normalKeys.get(2)).get("targetHeld").asText());
        assertEquals("1.001", detail(declaration, n2).get("originalHeld").asText());
        assertEquals("NORMAL", detail(declaration, n3).get("priority").asText());
        // BigDecimal 汇总（不依赖字符串尾零）：目标和 1.952，削减和 = 3.003 - 1.952 = 1.051
        assertEquals(0, new java.math.BigDecimal(sumDetails(declaration, "NORMAL", "targetHeld"))
                .compareTo(new java.math.BigDecimal("1.952")));
        assertEquals(0, new java.math.BigDecimal(sumDetails(declaration, "NORMAL", "reducedAmount"))
                .compareTo(new java.math.BigDecimal("1.051")));

        // 原申请水量不可改写；持有额度按明细变化
        assertEquals("1.001", allocation(windowId, normalKeys.get(0)).get("amount").asText());
        assertEquals("0.65", allocation(windowId, normalKeys.get(0)).get("heldAmount").asText());
        assertEquals("APPROVED", allocation(windowId, d1).get("status").asText());
        assertEquals("0", allocation(windowId, d1).get("heldAmount").asText());

        // 已批准总量下降：10 + 1.952 + 0 = 11.952；旱情不改变供水总量，余量同步释放
        JsonNode cap = capacity(windowId);
        assertEquals("100", cap.get("availableTotal").asText());
        assertEquals("11.952", cap.get("approvedTotal").asText());
        assertEquals("88.048", cap.get("remaining").asText());
        assertTrue(cap.get("activeCurtailmentVolume").isNull());

        // 当前等级查询
        JsonNode status = getOk("/api/windows/" + windowId + "/drought");
        assertEquals("LEVEL2", status.get("level").asText());
        assertEquals(declaration.get("curtailmentKey").asText(),
                status.get("active").get("curtailmentKey").asText());
        assertEquals("11.952", status.get("approvedTotal").asText());

        // 历史查询：一条声明
        JsonNode history = getOk("/api/windows/" + windowId + "/drought/history");
        assertEquals(1, history.get("declarations").size());
        assertEquals(5, history.get("declarations").get(0).get("details").size());
    }

    @Test
    void upgradeAndDowngradeAlwaysRecalculateFromBaseline() throws Exception {
        long windowId = createWindow("ch-d-up-" + run, "100");
        String a = submit(windowId, "u1", "6", "NORMAL", "alice");
        approve(a, 200);

        // LEVEL2 50%：6 -> 3
        JsonNode level2 = declare(windowId, "LEVEL2", 0, 50, 50, 0);
        assertEquals("3", detail(level2, a).get("targetHeld").asText());
        assertEquals("3", allocation(windowId, a).get("heldAmount").asText());

        // 升级 LEVEL3 80%：必须在原始持有额度 6 上重算 -> 1.2，而不是 3*0.2
        JsonNode level3 = declare(windowId, "LEVEL3", 0, 80, 80, 1);
        assertEquals(2, level3.get("version").asLong());
        assertEquals("1.2", detail(level3, a).get("targetHeld").asText());
        assertEquals("4.8", detail(level3, a).get("reducedAmount").asText());
        assertEquals("1.2", allocation(windowId, a).get("heldAmount").asText());

        // 降级 LEVEL1 20%：仍按基准 6 重算 -> 4.8
        JsonNode level1 = declare(windowId, "LEVEL1", 0, 20, 20, 2);
        assertEquals("4.8", detail(level1, a).get("targetHeld").asText());
        assertEquals("1.2", detail(level1, a).get("reducedAmount").asText());
        assertEquals("4.8", allocation(windowId, a).get("heldAmount").asText());

        // 恢复 NONE：回补原始持有额度 6
        JsonNode restored = declare(windowId, "NONE", 0, 0, 0, 3);
        assertEquals("6", detail(restored, a).get("targetHeld").asText());
        assertEquals("0", detail(restored, a).get("reducedAmount").asText());
        assertEquals("6", allocation(windowId, a).get("heldAmount").asText());
        // 旧声明全部 SUPERSEDED，最新 ACTIVE
        JsonNode history = getOk("/api/windows/" + windowId + "/drought/history");
        assertEquals(4, history.get("declarations").size());
        for (int i = 0; i < 3; i++) {
            assertEquals("SUPERSEDED", history.get("declarations").get(i).get("status").asText());
        }
        assertEquals("ACTIVE", history.get("declarations").get(3).get("status").asText());
        assertEquals("NONE", history.get("declarations").get(3).get("level").asText());
        JsonNode status = getOk("/api/windows/" + windowId + "/drought");
        assertEquals("NONE", status.get("level").asText());
        assertEquals("6", capacity(windowId).get("approvedTotal").asText());
    }

    @Test
    void noDeclarationStatusIsNoneAndUnknownWindow404() throws Exception {
        long windowId = createWindow("ch-d-none-" + run, "10");
        JsonNode status = getOk("/api/windows/" + windowId + "/drought");
        assertEquals("NONE", status.get("level").asText());
        assertTrue(status.get("active").isNull());
        assertEquals(0, getOk("/api/windows/" + windowId + "/drought/history").get("declarations").size());

        postJson("/api/windows/999999999/drought",
                droughtBody(key("dc"), key("dk"), "LEVEL1", 0, 10, 10, 0), null, 404);
        mvc.perform(get("/api/windows/999999999/drought")).andExpect(status().isNotFound());
        mvc.perform(get("/api/windows/999999999/drought/history")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void invalidPercentagesAndLevelsRejected() throws Exception {
        long windowId = createWindow("ch-d-bad-" + run, "10");
        // ESSENTIAL > NORMAL -> 422
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), key("dk"), "LEVEL2", 50, 10, 10, 0), null, 422);
        // NORMAL > DEFERRABLE -> 422
        MvcResult orderResult = postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), key("dk"), "LEVEL2", 10, 50, 10, 0), null, 422);
        assertEquals("INVALID_CURTAILMENT_PERCENTAGES",
                objectMapper.readTree(orderResult.getResponse().getContentAsString()).get("code").asText());
        // 百分比越界 -> 400
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), key("dk"), "LEVEL2", 0, 101, 101, 0), null, 400);
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), key("dk"), "LEVEL2", -1, 0, 0, 0), null, 400);
        // NONE 带非零百分比 -> 422
        MvcResult noneResult = postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), key("dk"), "NONE", 0, 1, 1, 0), null, 422);
        assertEquals("INVALID_DROUGHT_LEVEL",
                objectMapper.readTree(noneResult.getResponse().getContentAsString()).get("code").asText());
        // 非法等级 -> 400
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), key("dk"), "LEVEL9", 0, 0, 0, 0), null, 400);
        // expectedVersion 缺失/为负 -> 400
        Map<String, Object> noVersion = droughtBody(key("dc"), key("dk"), "LEVEL1", 0, 0, 0, 0);
        noVersion.remove("expectedVersion");
        postJson("/api/windows/" + windowId + "/drought", noVersion, null, 400);
        // 非法键 -> 400
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody("", key("dk"), "LEVEL1", 0, 0, 0, 0), null, 400);
        // 全部失败后窗口无任何声明
        assertEquals("NONE", getOk("/api/windows/" + windowId + "/drought").get("level").asText());
        assertEquals(0, getOk("/api/windows/" + windowId + "/drought/history").get("declarations").size());
    }

    @Test
    void staleExpectedVersionReturns409() throws Exception {
        long windowId = createWindow("ch-d-ver-" + run, "10");
        String a = submit(windowId, "u1", "4", "NORMAL", "alice");
        approve(a, 200);
        declare(windowId, "LEVEL1", 0, 10, 10, 0);
        // 版本已到 1，仍用 0 -> 409，额度不变
        MvcResult result = postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), key("dk"), "LEVEL2", 0, 50, 50, 0), null, 409);
        assertEquals("VERSION_CONFLICT",
                objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText());
        assertEquals("3.6", allocation(windowId, a).get("heldAmount").asText());
        // 用最新版本 1 -> 成功
        declare(windowId, "LEVEL2", 0, 50, 50, 1);
        assertEquals("2", allocation(windowId, a).get("heldAmount").asText());
    }

    @Test
    void restoreBeyondWindowAvailableReturns422AndChangesNothing() throws Exception {
        long windowId = createWindow("ch-d-restore-" + run, "10");
        String a = submit(windowId, "u1", "6", "NORMAL", "alice");
        approve(a, 200);
        // 旱情 50%：A 持有 6 -> 3，释放 3 余量
        declare(windowId, "LEVEL2", 0, 50, 50, 0);
        assertEquals("3", allocation(windowId, a).get("heldAmount").asText());
        // 削减期间新批准 7：削减后已批准总量 3 + 7 = 10，恰好放满
        String b = submit(windowId, "u2", "7", "NORMAL", "bob");
        approve(b, 200);
        assertEquals("7", allocation(windowId, b).get("heldAmount").asText());

        // 恢复 NONE：A 回补 6 -> 总量 13 > 可用 10，整次 422 且额度不变
        MvcResult result = postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), key("dk"), "NONE", 0, 0, 0, 1), null, 422);
        assertEquals("DROUGHT_RESTORE_EXCEEDED",
                objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText());
        assertEquals("3", allocation(windowId, a).get("heldAmount").asText());
        assertEquals("7", allocation(windowId, b).get("heldAmount").asText());
        assertEquals("LEVEL2", getOk("/api/windows/" + windowId + "/drought").get("level").asText());
        assertEquals("10", capacity(windowId).get("approvedTotal").asText());
        // 失败不产生声明历史，版本不变（仍为 1，下一次仍以 expectedVersion=1 提交）
        assertEquals(1, getOk("/api/windows/" + windowId + "/drought/history").get("declarations").size());

        // 取消 B 释放 7 后恢复：A 回补 6 <= 10，成功
        postOk("/api/allocations/" + b + "/cancel", Map.of("commandKey", key("cc")), "bob");
        declare(windowId, "NONE", 0, 0, 0, 1);
        assertEquals("6", allocation(windowId, a).get("heldAmount").asText());
        assertEquals("6", capacity(windowId).get("approvedTotal").asText());
    }

    // ------------------------------------------------------------------
    // 削减期间的批准、转让与取消
    // ------------------------------------------------------------------

    @Test
    void approvalsDuringDroughtValidateAgainstReducedMargin() throws Exception {
        long windowId = createWindow("ch-d-margin-" + run, "10");
        String a = submit(windowId, "u1", "6", "NORMAL", "alice");
        approve(a, 200);
        declare(windowId, "LEVEL2", 0, 50, 50, 0);
        // 削减后余量 7
        assertEquals("7", capacity(windowId).get("remaining").asText());
        String tooBig = submit(windowId, "u2", "7.001", "NORMAL", "bob");
        approve(tooBig, 422);
        String fits = submit(windowId, "u3", "7", "NORMAL", "carol");
        approve(fits, 200);
        assertEquals("10", capacity(windowId).get("approvedTotal").asText());
        assertEquals("0", capacity(windowId).get("remaining").asText());
    }

    @Test
    void droughtRecalculatesFromBaselineAfterTransferAndKeepsTransferHistory() throws Exception {
        long windowId = createWindow("ch-d-tx-" + run, "10");
        String source = submit(windowId, "u-src", "6", "NORMAL", "alice");
        String target = submit(windowId, "u-dst", "2.5", "NORMAL", "bob");
        approve(source, 200);
        postOk("/api/transfers", Map.of(
                "commandKey", key("tkc"), "transferKey", key("tk"),
                "sourceAllocationKey", source, "targetAllocationKey", target), "alice");
        // 转让后源持有与旱情基准均为 3.5（水权随转让转移），目标基准 2.5，基准总量守恒为 6
        declare(windowId, "LEVEL2", 0, 50, 50, 0);
        assertEquals("1.75", allocation(windowId, source).get("heldAmount").asText(),
                "按转让后基准 3.5 的 50% 重算");
        assertEquals("1.25", allocation(windowId, target).get("heldAmount").asText());
        // 原申请水量与转让流水均不改写
        assertEquals("6", allocation(windowId, source).get("amount").asText());
        JsonNode transfers = getOk("/api/windows/" + windowId + "/transfers");
        assertEquals(1, transfers.get("transfers").size());
        assertEquals("2.5", transfers.get("transfers").get(0).get("amount").asText());
        assertEquals("3", capacity(windowId).get("approvedTotal").asText());

        // 旱情期间转让按削减后持有额度校验：源仅持有 1.75，转出 3.5 -> 422
        String target2 = submit(windowId, "u-dst2", "3.5", "NORMAL", "carol");
        postJson("/api/transfers", Map.of(
                "commandKey", key("tkc"), "transferKey", key("tk"),
                "sourceAllocationKey", source, "targetAllocationKey", target2), "alice", 422);
        // 转出 1.75 -> 成功（总量不变 3.0）
        String target3 = submit(windowId, "u-dst3", "1.75", "NORMAL", "dave");
        postJson("/api/transfers", Map.of(
                "commandKey", key("tkc"), "transferKey", key("tk"),
                "sourceAllocationKey", source, "targetAllocationKey", target3), "alice", 200);
        assertEquals("0", allocation(windowId, source).get("heldAmount").asText());
        assertEquals("3", capacity(windowId).get("approvedTotal").asText());
        // 恢复 NONE：基准随转让守恒（源保留 6-2.5-1.75=1.75、target 2.5、target3 1.75，合计 6）
        declare(windowId, "NONE", 0, 0, 0, 1);
        assertEquals("1.75", allocation(windowId, source).get("heldAmount").asText());
        assertEquals("2.5", allocation(windowId, target).get("heldAmount").asText());
        assertEquals("1.75", allocation(windowId, target3).get("heldAmount").asText());
        assertEquals("6", capacity(windowId).get("approvedTotal").asText());
    }

    @Test
    void cancelledAllocationStaysZeroThroughUpgradeAndRestore() throws Exception {
        long windowId = createWindow("ch-d-cancel-" + run, "10");
        String a = submit(windowId, "u1", "6", "NORMAL", "alice");
        approve(a, 200);
        declare(windowId, "LEVEL2", 0, 50, 50, 0);
        postOk("/api/allocations/" + a + "/cancel", Map.of("commandKey", key("cc")), "alice");
        // 升级与恢复都不复活已取消申请
        declare(windowId, "LEVEL3", 0, 80, 80, 1);
        assertEquals("0", allocation(windowId, a).get("heldAmount").asText());
        declare(windowId, "NONE", 0, 0, 0, 2);
        assertEquals("CANCELLED", allocation(windowId, a).get("status").asText());
        assertEquals("0", allocation(windowId, a).get("heldAmount").asText());
        assertEquals("0", capacity(windowId).get("approvedTotal").asText());
    }

    // ------------------------------------------------------------------
    // 幂等与键唯一
    // ------------------------------------------------------------------

    @Test
    void commandKeyReplaysDeclarationAndCurtailmentKeyIsUnique() throws Exception {
        long windowId = createWindow("ch-d-idem-" + run, "10");
        String a = submit(windowId, "u1", "6", "NORMAL", "alice");
        approve(a, 200);
        String commandKey = key("dc");
        String curtailmentKey = key("dk");
        Map<String, Object> body = droughtBody(commandKey, curtailmentKey, "LEVEL2", 0, 50, 50, 0);
        JsonNode first = postOk("/api/windows/" + windowId + "/drought", body, null);
        JsonNode replay = postOk("/api/windows/" + windowId + "/drought", body, null);
        assertEquals(first, replay);
        // 重放不产生第二条声明、不重复削减、版本不变
        assertEquals(1, getOk("/api/windows/" + windowId + "/drought/history").get("declarations").size());
        assertEquals("3", allocation(windowId, a).get("heldAmount").asText());
        assertEquals(1, getOk("/api/windows/" + windowId + "/drought").get("active").get("version").asLong());

        // 同 commandKey 改参（百分比） -> 409
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(commandKey, key("dk"), "LEVEL3", 0, 80, 80, 1), null, 409);

        // curtailmentKey 全局唯一：换新 commandKey 复用同一 curtailmentKey -> 409
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(key("dc"), curtailmentKey, "LEVEL3", 0, 80, 80, 1), null, 409);
        // 额度未被失败请求改动
        assertEquals("3", allocation(windowId, a).get("heldAmount").asText());
    }

    @Test
    void failedDeclarationDoesNotOccupyKeys() throws Exception {
        long windowId = createWindow("ch-d-failidem-" + run, "10");
        String a = submit(windowId, "u1", "6", "NORMAL", "alice");
        approve(a, 200);
        String commandKey = key("dc");
        String curtailmentKey = key("dk");
        // 首次失败：百分比顺序非法 422
        postJson("/api/windows/" + windowId + "/drought",
                droughtBody(commandKey, curtailmentKey, "LEVEL2", 50, 10, 10, 0), null, 422);
        // 同 commandKey + 同 curtailmentKey 改为合法参数 -> 成功，证明失败不占键
        JsonNode ok = postOk("/api/windows/" + windowId + "/drought",
                droughtBody(commandKey, curtailmentKey, "LEVEL2", 0, 50, 50, 0), null);
        assertEquals("ACTIVE", ok.get("status").asText());
        assertEquals("3", allocation(windowId, a).get("heldAmount").asText());
    }

    @Test
    void allocationPriorityAcceptedDefaultNormalAndRejectedInvalid() throws Exception {
        long windowId = createWindow("ch-d-prio-" + run, "10");
        String essential = submit(windowId, "u1", "2", "ESSENTIAL", "alice");
        assertEquals("ESSENTIAL", allocation(windowId, essential).get("priority").asText());
        String deferred = submit(windowId, "u2", "2", "DEFERRABLE", "bob");
        assertEquals("DEFERRABLE", allocation(windowId, deferred).get("priority").asText());
        String normal = submit(windowId, "u3", "2", null, "carol");
        assertEquals("NORMAL", allocation(windowId, normal).get("priority").asText());
        postJson("/api/allocations", Map.of(
                "commandKey", key("ac"), "allocationKey", key("ak"),
                "windowId", windowId, "userId", "u4", "amount", "2",
                "priority", "URGENT"), "dave", 400);
    }

    // ------------------------------------------------------------------
    // 并发裁决
    // ------------------------------------------------------------------

    @Test
    void concurrentDeclarationsSameVersionExactlyOneWins() throws Exception {
        long windowId = createWindow("ch-d-racever-" + run, "10");
        String a = submit(windowId, "u1", "6", "NORMAL", "alice");
        approve(a, 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String key50 = key("dk");
        String key80 = key("dk");
        String cmd50 = key("dc");
        String cmd80 = key("dc");
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return declareStatus(cmd50, key50, "LEVEL2", 0, 50, 50, 0, windowId);
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return declareStatus(cmd80, key80, "LEVEL3", 0, 80, 80, 0, windowId);
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0),
                "同 expectedVersion 并发声明恰好一个成功: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0),
                "负方必须是版本冲突 409: " + s1 + "/" + s2);
        // 最终只有一条 ACTIVE 声明，版本为 1，持有额度与胜方一致
        JsonNode history = getOk("/api/windows/" + windowId + "/drought/history");
        assertEquals(1, history.get("declarations").size());
        String held = allocation(windowId, a).get("heldAmount").asText();
        if (s1 == 200) {
            assertEquals("3", held);
        } else {
            assertEquals("1.2", held);
        }
        assertEquals(1, getOk("/api/windows/" + windowId + "/drought").get("active").get("version").asLong());
    }

    @Test
    void concurrentRestoreAndApproveSettleByCommitOrder() throws Exception {
        long windowId = createWindow("ch-d-race-" + run, "10");
        String a = submit(windowId, "u1", "6", "NORMAL", "alice");
        approve(a, 200);
        declare(windowId, "LEVEL2", 0, 50, 50, 0);
        // 削减后 A 持有 3，余量 7；B 申请 7 恰好放满
        String b = submit(windowId, "u2", "7", "NORMAL", "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String approveCommand = key("ap");
        String restoreCommand = key("dc");
        String restoreKey = key("dk");
        Future<Integer> approveFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.approveAllocation(approveCommand, b);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> restoreFuture = pool.submit(() -> {
            gate.await();
            return declareStatus(restoreCommand, restoreKey, "NONE", 0, 0, 0, 1, windowId);
        });
        gate.countDown();
        int approveStatus = approveFuture.get(30, TimeUnit.SECONDS);
        int restoreStatus = restoreFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 批准先提交则恢复后总量 13>10 -> 恢复 422；恢复先提交则余量仅 4 -> 批准 422
        // 两种提交顺序下都恰好一个成功
        assertEquals(1, (approveStatus == 200 ? 1 : 0) + (restoreStatus == 200 ? 1 : 0),
                "approve=" + approveStatus + " restore=" + restoreStatus);
        assertEquals(1, (approveStatus == 422 ? 1 : 0) + (restoreStatus == 422 ? 1 : 0),
                "负方必须为 422: approve=" + approveStatus + " restore=" + restoreStatus);
        assertNotEquals(approveStatus, restoreStatus);
        // 不变式：最终已批准总量永不超过可用总量
        JsonNode cap = capacity(windowId);
        assertTrue(Double.parseDouble(cap.get("approvedTotal").asText())
                <= Double.parseDouble(cap.get("availableTotal").asText()));
        if (restoreStatus == 200) {
            assertEquals("6", cap.get("approvedTotal").asText());
            assertEquals("NONE", getOk("/api/windows/" + windowId + "/drought").get("level").asText());
        } else {
            assertEquals("10", cap.get("approvedTotal").asText());
            assertEquals("LEVEL2", getOk("/api/windows/" + windowId + "/drought").get("level").asText());
            // 恢复失败额度不变：A 仍为 3
            assertEquals("3", allocation(windowId, a).get("heldAmount").asText());
        }
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private int declareStatus(String commandKey, String curtailmentKey, String level,
                              int e, int n, int d, long expectedVersion, long windowId) {
        try {
            waterService.declareDrought(commandKey, curtailmentKey, windowId, level, e, n, d,
                    expectedVersion);
            return 200;
        } catch (ApiException ex) {
            return ex.status().value();
        }
    }

    /** 汇总某次声明中指定优先级明细的数值字段，返回去尾零字符串。 */
    private String sumDetails(JsonNode declaration, String priority, String field) {
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        for (JsonNode detail : declaration.get("details")) {
            if (priority.equals(detail.get("priority").asText())) {
                sum = sum.add(new java.math.BigDecimal(detail.get(field).asText()));
            }
        }
        return sum.stripTrailingZeros().toPlainString();
    }
}
