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
import java.util.LinkedHashMap;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 同窗口批量净额清算 API 测试：净额主流程（多指令/环/零净额版本）、整体回滚、
 * 幂等（requestId 同参重放、异参 409、失败不占键、settlementKey 全局唯一）与并发裁决。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务与行锁，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettlementApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final String run = UUID.randomUUID().toString().substring(8);
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

    private JsonNode postOk(String url, Map<String, Object> body) throws Exception {
        return objectMapper.readTree(postJson(url, body, null, 200).getResponse().getContentAsString());
    }

    private JsonNode postOk(String url, Map<String, Object> body, String actor) throws Exception {
        return objectMapper.readTree(postJson(url, body, actor, 200).getResponse().getContentAsString());
    }

    /** 不断言状态，返回真实 HTTP 状态码（供并发调用使用）。 */
    private int httpStatus(String url, Map<String, Object> body, String actor) {
        try {
            var request = post(url).contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body));
            if (actor != null) {
                request = request.header("X-Actor-Id", actor);
            }
            return mvc.perform(request).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
        return postOk("/api/windows", body).get("id").asLong();
    }

    /** 提交并批准一个主体，申请人与用水户同名，便于后续以本人身份转让。 */
    private String approvedSubject(long windowId, String userId, String amount) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        postOk("/api/allocations", body, userId);
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, 200);
        return allocationKey;
    }

    /** 仅提交（REQUESTED）一个主体，作为单笔转让目标。 */
    private String requestedSubject(long windowId, String userId, String amount, String actor) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        postOk("/api/allocations", body, actor);
        return allocationKey;
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

    private long versionOf(long windowId, String allocationKey) throws Exception {
        return allocation(windowId, allocationKey).get("quotaVersion").asLong();
    }

    private String heldOf(long windowId, String allocationKey) throws Exception {
        return allocation(windowId, allocationKey).get("heldAmount").asText();
    }

    private Map<String, Object> instruction(String instructionKey, String from, String to, String volume) {
        Map<String, Object> ins = new HashMap<>();
        ins.put("instructionKey", instructionKey);
        ins.put("from", from);
        ins.put("to", to);
        ins.put("volume", volume);
        return ins;
    }

    private Map<String, Object> versionEntry(String allocationKey, long version) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("allocationKey", allocationKey);
        entry.put("version", version);
        return entry;
    }

    private Map<String, Object> settlementBody(String requestId, String settlementKey, long windowId,
                                               List<Map<String, Object>> instructions,
                                               Map<String, Long> versions) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("settlementKey", settlementKey);
        body.put("windowId", windowId);
        body.put("instructions", instructions);
        List<Map<String, Object>> versionList = new ArrayList<>();
        versions.forEach((k, v) -> versionList.add(versionEntry(k, v)));
        body.put("versions", versionList);
        return body;
    }

    private Map<String, Long> currentVersions(long windowId, List<String> subjects) throws Exception {
        Map<String, Long> versions = new LinkedHashMap<>();
        for (String subject : subjects) {
            versions.put(subject, versionOf(windowId, subject));
        }
        return versions;
    }

    private JsonNode leg(JsonNode settlement, String allocationKey) {
        for (JsonNode leg : settlement.get("legs")) {
            if (allocationKey.equals(leg.get("allocationKey").asText())) {
                return leg;
            }
        }
        throw new IllegalStateException("leg not found: " + allocationKey);
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void batchNettingAppliesNetChangeConservesTotalAndSnapshotsEverything() throws Exception {
        long windowId = createWindow("ch-net-" + run, "1000");
        String a = approvedSubject(windowId, "user-a", "10");
        String b = approvedSubject(windowId, "user-b", "10");
        String c = approvedSubject(windowId, "user-c", "10");

        // 指令：A->B 3, A->C 2, B->C 1
        List<Map<String, Object>> instructions = List.of(
                instruction(key("ik"), a, b, "3"),
                instruction(key("ik"), a, c, "2"),
                instruction(key("ik"), b, c, "1"));
        List<String> subjects = List.of(a, b, c);
        Map<String, Object> body = settlementBody(key("req"), key("sk"), windowId, instructions,
                currentVersions(windowId, subjects));

        JsonNode result = postOk("/api/settlements", body);
        assertEquals(windowId, result.get("windowId").asLong());
        assertEquals(3, result.get("instructionCount").asInt());
        assertEquals("6", result.get("totalVolume").asText());
        assertNotNull(result.get("createdUtc").asText());

        // 输入顺序快照保留
        JsonNode saved = result.get("instructions");
        assertEquals(3, saved.size());
        assertEquals(0, saved.get(0).get("index").asInt());
        assertEquals(a, saved.get(0).get("from").asText());
        assertEquals("3", saved.get(0).get("volume").asText());
        assertEquals(2, saved.get(2).get("index").asInt());
        assertEquals(c, saved.get(2).get("to").asText());

        // 净额：A -5、B +2、C +3；前后余额与版本快照
        JsonNode legA = leg(result, a);
        assertEquals("-5", legA.get("netChange").asText());
        assertEquals("10", legA.get("beforeHeld").asText());
        assertEquals("5", legA.get("afterHeld").asText());
        assertEquals(2, legA.get("beforeVersion").asLong());
        assertEquals(3, legA.get("afterVersion").asLong());
        assertEquals("2", leg(result, b).get("netChange").asText());
        assertEquals("12", leg(result, b).get("afterHeld").asText());
        assertEquals("3", leg(result, c).get("netChange").asText());
        assertEquals("13", leg(result, c).get("afterHeld").asText());

        // 实际余额与版本
        assertEquals("5", heldOf(windowId, a));
        assertEquals("12", heldOf(windowId, b));
        assertEquals("13", heldOf(windowId, c));
        assertEquals(3, versionOf(windowId, a));
        // 总额度严格守恒：5 + 12 + 13 = 30
        assertEquals("30", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());

        // 详情只读查询返回相同不可变快照
        String settlementKey = result.get("settlementKey").asText();
        JsonNode detail = getOk("/api/settlements/" + settlementKey);
        assertEquals(result, detail);

        // 按主体历史查询
        JsonNode historyA = getOk("/api/allocations/" + a + "/settlements");
        assertEquals(a, historyA.get("allocationKey").asText());
        assertEquals(1, historyA.get("settlements").size());
        assertEquals(settlementKey, historyA.get("settlements").get(0).get("settlementKey").asText());
    }

    @Test
    void cycleIsLegalAndZeroNetSubjectsStillParticipateInVersioning() throws Exception {
        long windowId = createWindow("ch-cycle-" + run, "1000");
        String a = approvedSubject(windowId, "user-a", "5");
        String b = approvedSubject(windowId, "user-b", "5");
        String c = approvedSubject(windowId, "user-c", "5");

        // 环 A->B 2, B->C 2, C->A 2：三方净额均为 0
        List<Map<String, Object>> instructions = List.of(
                instruction(key("ik"), a, b, "2"),
                instruction(key("ik"), b, c, "2"),
                instruction(key("ik"), c, a, "2"));
        List<String> subjects = List.of(a, b, c);
        JsonNode result = postOk("/api/settlements", settlementBody(key("req"), key("sk"), windowId,
                instructions, currentVersions(windowId, subjects)));

        for (String subject : subjects) {
            JsonNode leg = leg(result, subject);
            assertEquals("0", leg.get("netChange").asText());
            assertEquals("5", leg.get("beforeHeld").asText());
            assertEquals("5", leg.get("afterHeld").asText());
            // 净额为 0 仍参与版本校验且版本加一（批准后为 2，清算后为 3）
            assertEquals(2, leg.get("beforeVersion").asLong());
            assertEquals(3, leg.get("afterVersion").asLong());
            assertEquals(3, versionOf(windowId, subject));
            assertEquals("5", heldOf(windowId, subject));
        }
        assertEquals("15", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    @Test
    void multipleInstructionsBetweenSamePairAreAllNetted() throws Exception {
        long windowId = createWindow("ch-pair-" + run, "1000");
        String a = approvedSubject(windowId, "user-a", "10");
        String b = approvedSubject(windowId, "user-b", "10");

        // 同一主体对 A->B 两条：3 与 4，净额 A -7、B +7；历史保留两条原指令
        String ik1 = key("ik");
        String ik2 = key("ik");
        List<Map<String, Object>> instructions = List.of(
                instruction(ik1, a, b, "3"),
                instruction(ik2, a, b, "4"));
        List<String> subjects = List.of(a, b);
        JsonNode result = postOk("/api/settlements", settlementBody(key("req"), key("sk"), windowId,
                instructions, currentVersions(windowId, subjects)));

        assertEquals("7", result.get("totalVolume").asText());
        assertEquals(2, result.get("instructions").size());
        assertEquals(ik1, result.get("instructions").get(0).get("instructionKey").asText());
        assertEquals(ik2, result.get("instructions").get(1).get("instructionKey").asText());
        assertEquals("-7", leg(result, a).get("netChange").asText());
        assertEquals("3", heldOf(windowId, a));
        assertEquals("17", heldOf(windowId, b));
    }

    // ------------------------------------------------------------------
    // 整体回滚 / 失败分支
    // ------------------------------------------------------------------

    @Test
    void insufficientSenderRollsBackWholeBatchWithNoRecords() throws Exception {
        long windowId = createWindow("ch-rollback-" + run, "1000");
        String a = approvedSubject(windowId, "user-a", "4");
        String b = approvedSubject(windowId, "user-b", "10");
        String c = approvedSubject(windowId, "user-c", "10");

        // A 只有 4，第一条 A->B 3 可承受但第二条 A->C 2 使净额 -5 -> 整批 422
        String ik1 = key("ik");
        String ik2 = key("ik");
        List<Map<String, Object>> instructions = List.of(
                instruction(ik1, a, b, "3"),
                instruction(ik2, a, c, "2"));
        List<String> subjects = List.of(a, b, c);
        String settlementKey = key("sk");
        postJson("/api/settlements", settlementBody(key("req"), settlementKey, windowId, instructions,
                currentVersions(windowId, subjects)), null, 422);

        // 无任何余额/版本变化
        assertEquals("4", heldOf(windowId, a));
        assertEquals("10", heldOf(windowId, b));
        assertEquals("10", heldOf(windowId, c));
        assertEquals(2, versionOf(windowId, a));
        // 无批次记录，详情 404；指令键未被占用
        mvc.perform(get("/api/settlements/" + settlementKey)).andExpect(status().isNotFound());
        JsonNode historyA = getOk("/api/allocations/" + a + "/settlements");
        assertEquals(0, historyA.get("settlements").size());

        // 调整为 A 承受得起的指令后，复用刚才“失败未占用”的 instructionKey 可以成功
        List<Map<String, Object>> retry = List.of(instruction(ik1, a, b, "3"));
        JsonNode ok = postOk("/api/settlements", settlementBody(key("req"), key("sk"), windowId, retry,
                currentVersions(windowId, List.of(a, b))));
        assertEquals("1", heldOf(windowId, a));
        assertEquals(ik1, ok.get("instructions").get(0).get("instructionKey").asText());
    }

    @Test
    void invalidBatchesReturn409Or404Or400AndChangeNothing() throws Exception {
        long w1 = createWindow("ch-bad1-" + run, "1000");
        long w2 = createWindow("ch-bad2-" + run, "1000");
        String a = approvedSubject(w1, "user-a", "10");
        String b = approvedSubject(w1, "user-b", "10");
        String otherWindow = approvedSubject(w2, "user-x", "10");

        // 自转 -> 409
        postJson("/api/settlements", settlementBody(key("req"), key("sk"), w1,
                List.of(instruction(key("ik"), a, a, "1")),
                currentVersions(w1, List.of(a))), null, 409);

        // 版本遗漏（缺少 b）-> 409
        postJson("/api/settlements", settlementBody(key("req"), key("sk"), w1,
                List.of(instruction(key("ik"), a, b, "1")),
                currentVersions(w1, List.of(a))), null, 409);
        // 版本多余（多带 otherWindow）-> 409
        Map<String, Long> extra = currentVersions(w1, List.of(a, b));
        extra.put(otherWindow, versionOf(w2, otherWindow));
        postJson("/api/settlements", settlementBody(key("req"), key("sk"), w1,
                List.of(instruction(key("ik"), a, b, "1")), extra), null, 409);

        // 版本过期：先做一笔单笔转让改变 d 的版本（d 批准后 2，转让后 3）
        String d = approvedSubject(w1, "user-d", "5");
        String f = requestedSubject(w1, "user-f", "5", "user-f");
        postOk("/api/transfers", Map.of(
                "commandKey", key("tc"), "transferKey", key("tk"),
                "sourceAllocationKey", d, "targetAllocationKey", f), "user-d");
        assertEquals(3, versionOf(w1, d));
        Map<String, Long> stale = new LinkedHashMap<>();
        stale.put(b, 2L);
        stale.put(d, 2L); // 已过期
        postJson("/api/settlements", settlementBody(key("req"), key("sk"), w1,
                List.of(instruction(key("ik"), b, d, "1")), stale), null, 409);

        // 跨窗口 -> 409
        postJson("/api/settlements", settlementBody(key("req"), key("sk"), w1,
                List.of(instruction(key("ik"), a, otherWindow, "1")),
                Map.of(a, versionOf(w1, a), otherWindow, versionOf(w2, otherWindow))), null, 409);
        // 主体不存在 -> 404；窗口不存在 -> 404
        Map<String, Long> missingVersions = new LinkedHashMap<>();
        missingVersions.put(a, versionOf(w1, a));
        missingVersions.put("missing-" + run, 1L);
        postJson("/api/settlements", settlementBody(key("req"), key("sk"), w1,
                List.of(instruction(key("ik"), a, "missing-" + run, "1")),
                missingVersions), null, 404);
        postJson("/api/settlements", settlementBody(key("req"), key("sk"), 999999999L,
                List.of(instruction(key("ik"), a, b, "1")),
                Map.of(a, 1L, b, 1L)), null, 404);
        // 批次内 instructionKey 重复 -> 409
        String dupKey = key("ik");
        postJson("/api/settlements", settlementBody(key("req"), key("sk"), w1,
                List.of(instruction(dupKey, a, b, "1"), instruction(dupKey, b, a, "1")),
                currentVersions(w1, List.of(a, b))), null, 409);
        // 已取消主体（主体限供）-> 409
        String cancelled = approvedSubject(w1, "user-cancel", "3");
        postOk("/api/allocations/" + cancelled + "/cancel",
                Map.of("commandKey", key("cc")), "user-cancel");
        Map<String, Long> cancelVersions = new LinkedHashMap<>();
        cancelVersions.put(a, versionOf(w1, a));
        cancelVersions.put(cancelled, versionOf(w1, cancelled));
        postJson("/api/settlements", settlementBody(key("req"), key("sk"), w1,
                List.of(instruction(key("ik"), a, cancelled, "1")),
                cancelVersions), null, 409);
        // 未批准（REQUESTED，无可用额度）主体不能参与清算 -> 409
        String requested = requestedSubject(w1, "user-req", "3", "user-req");
        Map<String, Long> requestedVersions = new LinkedHashMap<>();
        requestedVersions.put(a, versionOf(w1, a));
        requestedVersions.put(requested, 1L);
        postJson("/api/settlements", settlementBody(key("req"), key("sk"), w1,
                List.of(instruction(key("ik"), a, requested, "1")),
                requestedVersions), null, 409);
        // 空指令 / 超过 100 条 -> 400
        postJson("/api/settlements", settlementBody(key("req"), key("sk"), w1,
                List.of(), Map.of()), null, 400);
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(instruction(key("ik"), a, b, "1"));
        }
        postJson("/api/settlements", settlementBody(key("req"), key("sk"), w1,
                tooMany, currentVersions(w1, List.of(a, b))), null, 400);
        // 非正整数体积 -> 400
        postJson("/api/settlements", settlementBody(key("req"), key("sk"), w1,
                List.of(instruction(key("ik"), a, b, "1.5")),
                currentVersions(w1, List.of(a, b))), null, 400);
        postJson("/api/settlements", settlementBody(key("req"), key("sk"), w1,
                List.of(instruction(key("ik"), a, b, "0")),
                currentVersions(w1, List.of(a, b))), null, 400);

        // 全部失败后 a/b 余额版本不变，无批次记录
        assertEquals("10", heldOf(w1, a));
        assertEquals("10", heldOf(w1, b));
        mvc.perform(get("/api/allocations/missing-" + run + "/settlements"))
                .andExpect(status().isNotFound());
    }

    @Test
    void instructionKeyReusedByAnotherBatchAndSettlementKeyReuseBoth409() throws Exception {
        long windowId = createWindow("ch-keyreuse-" + run, "1000");
        String a = approvedSubject(windowId, "user-a", "10");
        String b = approvedSubject(windowId, "user-b", "10");
        String c = approvedSubject(windowId, "user-c", "10");

        String reusedInstructionKey = key("ik");
        String firstSettlementKey = key("sk");
        postOk("/api/settlements", settlementBody(key("req"), firstSettlementKey, windowId,
                List.of(instruction(reusedInstructionKey, a, b, "1")),
                currentVersions(windowId, List.of(a, b))));

        // instructionKey 已被其他批次使用 -> 409，整批无记录（a 净额已变，需带当前版本）
        String secondSettlementKey = key("sk");
        Map<String, Long> secondVersions = new LinkedHashMap<>();
        secondVersions.put(a, versionOf(windowId, a));
        secondVersions.put(b, versionOf(windowId, b));
        secondVersions.put(c, versionOf(windowId, c));
        postJson("/api/settlements", settlementBody(key("req"), secondSettlementKey, windowId,
                List.of(instruction(reusedInstructionKey, b, c, "1")),
                secondVersions), null, 409);
        mvc.perform(get("/api/settlements/" + secondSettlementKey)).andExpect(status().isNotFound());

        // settlementKey 全局唯一：换 requestId 复用 -> 409
        Map<String, Long> bcVersions = new LinkedHashMap<>();
        bcVersions.put(b, versionOf(windowId, b));
        bcVersions.put(c, versionOf(windowId, c));
        postJson("/api/settlements", settlementBody(key("req"), firstSettlementKey, windowId,
                List.of(instruction(key("ik"), b, c, "1")),
                bcVersions), null, 409);

        // c 未受失败批次影响
        assertEquals("10", heldOf(windowId, c));
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdReplaysOnlyWhenParamsIdenticalIncludingOrder() throws Exception {
        long windowId = createWindow("ch-idem-" + run, "1000");
        String a = approvedSubject(windowId, "user-a", "10");
        String b = approvedSubject(windowId, "user-b", "10");

        String requestId = key("req");
        String ik1 = key("ik");
        String ik2 = key("ik");
        String settlementKey = key("sk");
        Map<String, Object> body = settlementBody(requestId, settlementKey, windowId,
                List.of(instruction(ik1, a, b, "2"), instruction(ik2, b, a, "1")),
                currentVersions(windowId, List.of(a, b)));

        JsonNode first = postOk("/api/settlements", body);
        JsonNode replay = postOk("/api/settlements", body);
        assertEquals(first, replay);
        // 重放不再次变更：A 净 -1 = 9，只发生一次
        assertEquals("9", heldOf(windowId, a));
        assertEquals("11", heldOf(windowId, b));
        assertEquals(3, versionOf(windowId, a));

        // 同 requestId 调换指令顺序（顺序有业务意义）-> 异参 409
        Map<String, Object> reordered = settlementBody(requestId, settlementKey, windowId,
                List.of(instruction(ik2, b, a, "1"), instruction(ik1, a, b, "2")),
                Map.of(a, 3L, b, 3L));
        postJson("/api/settlements", reordered, null, 409);
        // 同 requestId 改 settlementKey -> 409
        postJson("/api/settlements", settlementBody(requestId, key("sk"), windowId,
                List.of(instruction(ik1, a, b, "2"), instruction(ik2, b, a, "1")),
                Map.of(a, 3L, b, 3L)), null, 409);
        // 余额仍只被清算一次
        assertEquals("9", heldOf(windowId, a));
        assertEquals(1, getOk("/api/allocations/" + a + "/settlements").get("settlements").size());
    }

    @Test
    void failedSettlementDoesNotOccupyRequestId() throws Exception {
        long windowId = createWindow("ch-failidem-" + run, "1000");
        String a = approvedSubject(windowId, "user-a", "3");
        String b = approvedSubject(windowId, "user-b", "10");

        String requestId = key("req");
        // 首次 422（A 不足），不占 requestId
        postJson("/api/settlements", settlementBody(requestId, key("sk"), windowId,
                List.of(instruction(key("ik"), a, b, "5")),
                currentVersions(windowId, List.of(a, b))), null, 422);
        // 同 requestId 改成合法指令 -> 成功，证明失败不占键
        JsonNode ok = postOk("/api/settlements", settlementBody(requestId, key("sk"), windowId,
                List.of(instruction(key("ik"), a, b, "2")),
                currentVersions(windowId, List.of(a, b))));
        assertEquals("1", heldOf(windowId, a));
        assertEquals(1, getOk("/api/allocations/" + a + "/settlements").get("settlements").size());
        assertNotNull(ok.get("settlementKey"));
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void twoSettlementsRacingOneSharedSubjectSucceedAtMostOnce() throws Exception {
        long windowId = createWindow("ch-race-batch-" + run, "1000");
        String a = approvedSubject(windowId, "user-a", "5");
        String b = approvedSubject(windowId, "user-b", "10");
        String c = approvedSubject(windowId, "user-c", "10");

        // 两批都基于 A 的同一版本（批准后 2）各转出 3；A 只有 5，串行后第二批版本过期 -> 409
        Map<String, Object> batch1 = settlementBody(key("req"), key("sk"), windowId,
                List.of(instruction(key("ik"), a, b, "3")),
                new LinkedHashMap<>(Map.of(a, 2L, b, 2L)));
        Map<String, Object> batch2 = settlementBody(key("req"), key("sk"), windowId,
                List.of(instruction(key("ik"), a, c, "3")),
                new LinkedHashMap<>(Map.of(a, 2L, c, 2L)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return httpStatus("/api/settlements", batch1, null);
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return httpStatus("/api/settlements", batch2, null);
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0),
                "共享主体的两批清算最多一批成功: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0),
                "另一批必须因版本过期 409: " + s1 + "/" + s2);
        // 胜方扣 3：A=2；败方目标不变
        assertEquals("2", heldOf(windowId, a));
        assertEquals(3, versionOf(windowId, a));
        assertEquals("25", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    @Test
    void disjointSettlementsInSameWindowBothCommit() throws Exception {
        long windowId = createWindow("ch-race-disjoint-" + run, "1000");
        String a = approvedSubject(windowId, "user-a", "5");
        String b = approvedSubject(windowId, "user-b", "5");
        String c = approvedSubject(windowId, "user-c", "5");
        String d = approvedSubject(windowId, "user-d", "5");

        Map<String, Object> batch1 = settlementBody(key("req"), key("sk"), windowId,
                List.of(instruction(key("ik"), a, b, "2")),
                new LinkedHashMap<>(Map.of(a, 2L, b, 2L)));
        Map<String, Object> batch2 = settlementBody(key("req"), key("sk"), windowId,
                List.of(instruction(key("ik"), c, d, "2")),
                new LinkedHashMap<>(Map.of(c, 2L, d, 2L)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return httpStatus("/api/settlements", batch1, null);
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return httpStatus("/api/settlements", batch2, null);
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(200, s1);
        assertEquals(200, s2);
        assertEquals("3", heldOf(windowId, a));
        assertEquals("7", heldOf(windowId, b));
        assertEquals("3", heldOf(windowId, c));
        assertEquals("7", heldOf(windowId, d));
        assertEquals("20", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    @Test
    void settlementAndSingleTransferSettleByCommitOrderWithoutOverdraft() throws Exception {
        long windowId = createWindow("ch-race-mix-" + run, "1000");
        String a = approvedSubject(windowId, "user-a", "6");
        String b = approvedSubject(windowId, "user-b", "6");
        // 单笔转让目标必须为 REQUESTED
        String targetKey = requestedSubject(windowId, "user-t", "6", "user-a");

        // 清算：A->B 4（A 版本取批准后 2）
        Map<String, Object> batch = settlementBody(key("req"), key("sk"), windowId,
                List.of(instruction(key("ik"), a, b, "4")),
                new LinkedHashMap<>(Map.of(a, 2L, b, 2L)));
        Map<String, Object> transfer = new HashMap<>();
        transfer.put("commandKey", key("tc"));
        transfer.put("transferKey", key("tk"));
        transfer.put("sourceAllocationKey", a);
        transfer.put("targetAllocationKey", targetKey);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> settlementFuture = pool.submit(() -> {
            gate.await();
            return httpStatus("/api/settlements", batch, null);
        });
        Future<Integer> transferFuture = pool.submit(() -> {
            gate.await();
            return httpStatus("/api/transfers", transfer, "user-a");
        });
        gate.countDown();
        int settlementStatus = settlementFuture.get(30, TimeUnit.SECONDS);
        int transferStatus = transferFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 两操作都扣 A（清算 4、转让 6，合计 10 > 6），按提交顺序后者必失败：
        // 转让先提交则 A=0，清算因版本过期 409；清算先提交则 A=2，转让持有不足 422
        assertTrue((settlementStatus == 200) != (transferStatus == 200),
                "清算与转让争抢同一源恰好一个成功: settlement=" + settlementStatus
                        + " transfer=" + transferStatus);
        assertTrue(transferStatus == 200 || transferStatus == 422, "transfer=" + transferStatus);
        assertTrue(settlementStatus == 200 || settlementStatus == 409, "settlement=" + settlementStatus);
        double heldA = Double.parseDouble(heldOf(windowId, a));
        assertTrue(heldA >= 0, "A 不得超额: " + heldA);
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertTrue(Double.parseDouble(cap.get("approvedTotal").asText())
                <= Double.parseDouble(cap.get("availableTotal").asText()));
    }
}
