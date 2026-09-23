package com.example.starter.water;

import com.example.starter.water.dto.Dtos.SettlementInstruction;
import com.example.starter.water.dto.Dtos.SubjectVersion;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 同窗口批量净额清算 API 测试：净额主流程、环与零净额版本、整体回滚、requestId 幂等、
 * 版本集合/限供/跨窗口/指令键冲突失败分支，以及与单笔转让、限供、另一清算的真实并发裁决。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务与行锁，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettlementApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

    private final String run = UUID.randomUUID().toString().substring(8);
    private int seq;

    private String key(String prefix) {
        return prefix + "-" + run + "-" + (++seq);
    }

    // ------------------------------------------------------------------
    // HTTP 辅助
    // ------------------------------------------------------------------

    private MvcResult postJson(String url, Map<String, Object> body, int expectedStatus) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "alice")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private JsonNode postOk(String url, Map<String, Object> body) throws Exception {
        return objectMapper.readTree(postJson(url, body, 200).getResponse().getContentAsString());
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
        body.put("startUtc", "2026-10-01T00:00:00Z");
        body.put("endUtc", "2026-10-01T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body).get("id").asLong();
    }

    /** 提交申请并普通批准，返回申请业务键；清算主体必须为 APPROVED。 */
    private String approvedSubject(long windowId, String userId, String amount) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> submit = new HashMap<>();
        submit.put("commandKey", key("ac"));
        submit.put("allocationKey", allocationKey);
        submit.put("windowId", windowId);
        submit.put("userId", userId);
        submit.put("amount", amount);
        postOk("/api/allocations", submit);
        postJson("/api/allocations/" + allocationKey + "/approve", Map.of("commandKey", key("ap")), 200);
        return allocationKey;
    }

    /** 提交但不批准的申请（用于非 APPROVED 主体失败分支）。 */
    private String requestedSubject(long windowId, String userId, String amount) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> submit = new HashMap<>();
        submit.put("commandKey", key("ac"));
        submit.put("allocationKey", allocationKey);
        submit.put("windowId", windowId);
        submit.put("userId", userId);
        submit.put("amount", amount);
        postOk("/api/allocations", submit);
        return allocationKey;
    }

    private record Instr(String instructionKey, String from, String to, String volume) {
    }

    private Map<String, Object> settlementBody(String commandKey, String requestId, String settlementKey,
                                               long windowId, List<Instr> instructions,
                                               Map<String, Long> versions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("requestId", requestId);
        body.put("settlementKey", settlementKey);
        body.put("windowId", windowId);
        List<Map<String, Object>> instrNodes = new ArrayList<>();
        for (Instr instr : instructions) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("instructionKey", instr.instructionKey());
            node.put("from", instr.from());
            node.put("to", instr.to());
            node.put("volume", instr.volume());
            instrNodes.add(node);
        }
        body.put("instructions", instrNodes);
        List<Map<String, Object>> subjectNodes = new ArrayList<>();
        for (Map.Entry<String, Long> entry : versions.entrySet()) {
            subjectNodes.add(Map.of("allocationKey", entry.getKey(), "version", entry.getValue()));
        }
        body.put("subjects", subjectNodes);
        return body;
    }

    private List<Instr> instrs(Object... raw) {
        List<Instr> list = new ArrayList<>();
        for (int i = 0; i < raw.length; i += 3) {
            list.add(new Instr(key("ik"), (String) raw[i], (String) raw[i + 1], (String) raw[i + 2]));
        }
        return list;
    }

    private Map<String, Long> versions(String... keys) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (String k : keys) {
            map.put(k, 0L);
        }
        return map;
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

    private JsonNode snapshot(JsonNode settlement, String allocationKey) {
        for (JsonNode node : settlement.get("subjects")) {
            if (allocationKey.equals(node.get("allocationKey").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("snapshot not found: " + allocationKey);
    }

    private int submitStatus(String requestId, String settlementKey, long windowId,
                             List<SettlementInstruction> instructions, List<SubjectVersion> subjects) {
        try {
            waterService.submitSettlement(key("ck"), requestId, settlementKey, windowId, instructions, subjects);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private List<SettlementInstruction> dtoInstructions(List<Instr> instrs) {
        return instrs.stream()
                .map(i -> new SettlementInstruction(i.instructionKey(), i.from(), i.to(), i.volume()))
                .toList();
    }

    private List<SubjectVersion> dtoSubjects(Map<String, Long> versions) {
        return versions.entrySet().stream()
                .map(e -> new SubjectVersion(e.getKey(), e.getValue())).toList();
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void netSettlementAggregatesInstructionsAndStoresImmutableSnapshot() throws Exception {
        long windowId = createWindow("ch-set-" + run, "30");
        String a = approvedSubject(windowId, "user-a", "10");
        String b = approvedSubject(windowId, "user-b", "2");
        String c = approvedSubject(windowId, "user-c", "3");

        // A->B 4、A->C 1、B->C 2；同主体对只出现一次，净额 A=-5 B=+2 C=+3
        List<Instr> instructions = instrs(a, b, "4", a, c, "1", b, c, "2");
        String requestId = key("rq");
        String settlementKey = key("sk");
        JsonNode result = postOk("/api/settlements", settlementBody(
                key("ck"), requestId, settlementKey, windowId, instructions, versions(a, b, c)));

        assertEquals(settlementKey, result.get("settlementKey").asText());
        assertEquals(requestId, result.get("requestId").asText());
        assertEquals(windowId, result.get("windowId").asLong());
        assertEquals(3, result.get("instructionCount").asInt());
        assertTrue(result.has("createdUtc"));

        // 原指令严格按输入顺序保留
        assertEquals(3, result.get("instructions").size());
        assertEquals(0, result.get("instructions").get(0).get("seqNo").asInt());
        assertEquals(instructions.get(0).instructionKey(),
                result.get("instructions").get(0).get("instructionKey").asText());
        assertEquals(a, result.get("instructions").get(0).get("from").asText());
        assertEquals("4", result.get("instructions").get(0).get("volume").asText());
        assertEquals(instructions.get(2).instructionKey(),
                result.get("instructions").get(2).get("instructionKey").asText());

        // 主体余额与版本
        assertEquals("5", allocation(windowId, a).get("heldAmount").asText());
        assertEquals("4", allocation(windowId, b).get("heldAmount").asText());
        assertEquals("6", allocation(windowId, c).get("heldAmount").asText());
        assertEquals(1, allocation(windowId, a).get("version").asInt());
        assertEquals(1, allocation(windowId, b).get("version").asInt());
        assertEquals(1, allocation(windowId, c).get("version").asInt());

        // 快照净额与前后余额
        JsonNode snapA = snapshot(result, a);
        assertEquals("-5", snapA.get("netChange").asText());
        assertEquals("10", snapA.get("balanceBefore").asText());
        assertEquals("5", snapA.get("balanceAfter").asText());
        assertEquals(0, snapA.get("versionBefore").asInt());
        assertEquals(1, snapA.get("versionAfter").asInt());
        assertEquals("2", snapshot(result, b).get("netChange").asText());
        assertEquals("3", snapshot(result, c).get("netChange").asText());

        // 已批准持有总量严格守恒：10+2+3 = 5+4+6 = 15
        assertEquals("15", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());

        // 详情只读接口返回同一批次
        JsonNode detail = getOk("/api/settlements/" + settlementKey);
        assertEquals(result, detail);

        // 按主体历史查询：A 与 C 各一条快照，按提交顺序
        JsonNode historyA = getOk("/api/allocations/" + a + "/settlements");
        assertEquals(a, historyA.get("allocationKey").asText());
        assertEquals(1, historyA.get("settlements").size());
        assertEquals(a, historyA.get("settlements").get(0).get("allocationKey").asText());
        assertEquals("-5", historyA.get("settlements").get(0).get("netChange").asText());
        assertEquals(1, getOk("/api/allocations/" + c + "/settlements").get("settlements").size());
    }

    @Test
    void cyclesAreLegalAndZeroNetSubjectsStillBumpVersion() throws Exception {
        long windowId = createWindow("ch-cycle-" + run, "20");
        String a = approvedSubject(windowId, "user-a", "5");
        String b = approvedSubject(windowId, "user-b", "5");
        String c = approvedSubject(windowId, "user-c", "5");

        // 等额环 A->B->C->A：净额全 0，环合法
        List<Instr> instructions = instrs(a, b, "3", b, c, "3", c, a, "3");
        JsonNode result = postOk("/api/settlements", settlementBody(
                key("ck"), key("rq"), key("sk"), windowId, instructions, versions(a, b, c)));

        for (String subject : List.of(a, b, c)) {
            assertEquals("5", allocation(windowId, subject).get("heldAmount").asText());
            // 净额为 0 仍参与版本校验，版本加一
            assertEquals(1, allocation(windowId, subject).get("version").asInt());
            JsonNode snap = snapshot(result, subject);
            assertEquals("0", snap.get("netChange").asText());
            assertEquals("5", snap.get("balanceBefore").asText());
            assertEquals("5", snap.get("balanceAfter").asText());
            assertEquals(1, snap.get("versionAfter").asInt());
        }
        assertEquals("15", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    @Test
    void repeatedSubjectPairsAggregateAndHistoryAccumulatesAcrossBatches() throws Exception {
        long windowId = createWindow("ch-pairs-" + run, "20");
        String a = approvedSubject(windowId, "user-a", "10");
        String b = approvedSubject(windowId, "user-b", "4");

        // 相同主体对两条：A->B 2 与 A->B 3，净额 A=-5 B=+5；B 净收入后持有 9 可高于其原申请水量 4
        JsonNode first = postOk("/api/settlements", settlementBody(
                key("ck"), key("rq"), key("sk"), windowId, instrs(a, b, "2", a, b, "3"), versions(a, b)));
        assertEquals(2, first.get("instructionCount").asInt());
        assertEquals("5", allocation(windowId, a).get("heldAmount").asText());
        assertEquals("9", allocation(windowId, b).get("heldAmount").asText());
        assertEquals(1, allocation(windowId, a).get("version").asInt());

        // 第二批 B->A 4，提交时携带新版本 1
        JsonNode second = postOk("/api/settlements", settlementBody(
                key("ck"), key("rq2"), key("sk2"), windowId, instrs(b, a, "4"),
                new LinkedHashMap<>(Map.of(a, 1L, b, 1L))));
        assertEquals("9", allocation(windowId, a).get("heldAmount").asText());
        assertEquals("5", allocation(windowId, b).get("heldAmount").asText());
        assertEquals(2, allocation(windowId, a).get("version").asInt());
        assertEquals(2, allocation(windowId, b).get("version").asInt());

        // 主体历史按提交顺序累计两条，余额前后衔接
        JsonNode history = getOk("/api/allocations/" + a + "/settlements");
        assertEquals(2, history.get("settlements").size());
        assertEquals("-5", history.get("settlements").get(0).get("netChange").asText());
        assertEquals("10", history.get("settlements").get(0).get("balanceBefore").asText());
        assertEquals("5", history.get("settlements").get(0).get("balanceAfter").asText());
        assertEquals("4", history.get("settlements").get(1).get("netChange").asText());
        assertEquals("5", history.get("settlements").get(1).get("balanceBefore").asText());
        assertEquals("9", history.get("settlements").get(1).get("balanceAfter").asText());
    }

    // ------------------------------------------------------------------
    // 整体回滚与失败分支
    // ------------------------------------------------------------------

    @Test
    void insufficientSenderRollsBackWholeBatchWithNoRecords() throws Exception {
        long windowId = createWindow("ch-rollback-" + run, "20");
        String a = approvedSubject(windowId, "user-a", "4");
        String b = approvedSubject(windowId, "user-b", "10");
        String c = approvedSubject(windowId, "user-c", "1");

        // A 收入 1 但转出 6：净 -5，清算后 -1 -> 整批 422
        List<Instr> instructions = instrs(b, a, "1", a, c, "6");
        String settlementKey = key("sk");
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq"), settlementKey, windowId, instructions, versions(a, b, c)), 422);

        // 无任何余额/版本变化
        assertEquals("4", allocation(windowId, a).get("heldAmount").asText());
        assertEquals("10", allocation(windowId, b).get("heldAmount").asText());
        assertEquals("1", allocation(windowId, c).get("heldAmount").asText());
        for (String s : List.of(a, b, c)) {
            assertEquals(0, allocation(windowId, s).get("version").asInt());
        }
        assertEquals("15", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());

        // 失败批次无记录：详情 404
        mvc.perform(get("/api/settlements/" + settlementKey)).andExpect(status().isNotFound());
        // 主体历史为空
        assertEquals(0, getOk("/api/allocations/" + a + "/settlements").get("settlements").size());

        // 失败不占指令键/批次键/requestId：原样修正后提交成功
        List<Instr> fixed = new ArrayList<>();
        fixed.add(new Instr(instructions.get(0).instructionKey(), b, a, "1"));
        fixed.add(new Instr(instructions.get(1).instructionKey(), a, c, "4"));
        JsonNode ok = postOk("/api/settlements", settlementBody(
                key("ck"), key("rq2"), settlementKey, windowId, fixed, versions(a, b, c)));
        assertEquals(2, ok.get("instructions").size());
        assertEquals("1", allocation(windowId, a).get("heldAmount").asText());
    }

    @Test
    void invalidInstructionShapeReturns400() throws Exception {
        long windowId = createWindow("ch-badshape-" + run, "20");
        String a = approvedSubject(windowId, "user-a", "5");
        String b = approvedSubject(windowId, "user-b", "5");

        // 自转
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq"), key("sk"), windowId, instrs(a, a, "1"), versions(a)), 400);
        // 非整数体积 / 零 / 负数
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq"), key("sk"), windowId, instrs(a, b, "2.5"), versions(a, b)), 400);
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq"), key("sk"), windowId, instrs(a, b, "0"), versions(a, b)), 400);
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq"), key("sk"), windowId, instrs(a, b, "-3"), versions(a, b)), 400);
        // 空指令列表
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq"), key("sk"), windowId, List.of(), versions(a, b)), 400);
        // 101 条指令
        List<Instr> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(new Instr(key("ik"), a, b, "1"));
        }
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq"), key("sk"), windowId, tooMany, versions(a, b)), 400);
        // 批内指令键重复
        String dupKey = key("ik");
        List<Instr> dup = List.of(new Instr(dupKey, a, b, "1"), new Instr(dupKey, a, b, "1"));
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq"), key("sk"), windowId, dup, versions(a, b)), 400);
        // 全部失败后无批次记录
        mvc.perform(get("/api/settlements/" + key("sk"))).andExpect(status().isNotFound());
    }

    @Test
    void versionSetMismatchAndStaleVersionReturn409() throws Exception {
        long windowId = createWindow("ch-ver-" + run, "20");
        String a = approvedSubject(windowId, "user-a", "5");
        String b = approvedSubject(windowId, "user-b", "5");

        // 遗漏主体 B
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq"), key("sk"), windowId, instrs(a, b, "1"), versions(a)), 409);
        // 多余主体 C
        String c = approvedSubject(windowId, "user-c", "5");
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq"), key("sk"), windowId, instrs(a, b, "1"),
                versions(a, b, c)), 409);
        // 版本号错误
        Map<String, Long> stale = new LinkedHashMap<>();
        stale.put(a, 0L);
        stale.put(b, 9L);
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq"), key("sk"), windowId, instrs(a, b, "1"), stale), 409);

        // 先成功一批使版本变为 1，再用旧版本 0 提交 -> 409 且无变化
        postOk("/api/settlements", settlementBody(
                key("ck"), key("rq-ok"), key("sk-ok"), windowId, instrs(a, b, "1"), versions(a, b)));
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq2"), key("sk2"), windowId, instrs(a, b, "1"), versions(a, b)), 409);
        assertEquals("4", allocation(windowId, a).get("heldAmount").asText());
        assertEquals(1, allocation(windowId, a).get("version").asInt());
    }

    @Test
    void crossWindowAndNonApprovedSubjectsReturn409() throws Exception {
        long w1 = createWindow("ch-cw1-" + run, "20");
        long w2 = createWindow("ch-cw2-" + run, "20");
        String a = approvedSubject(w1, "user-a", "5");
        String b = approvedSubject(w1, "user-b", "5");
        String other = approvedSubject(w2, "user-x", "5");
        String requested = requestedSubject(w1, "user-r", "5");

        // 跨窗口主体
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq"), key("sk"), w1, instrs(a, other, "1"),
                versions(a, other)), 409);
        // REQUESTED 主体（视为未批准/受限主体）
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq2"), key("sk2"), w1, instrs(a, requested, "1"),
                versions(a, requested)), 409);
        // CANCELLED 主体
        postOk("/api/allocations/" + b + "/cancel", Map.of("commandKey", key("cc")));
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq3"), key("sk3"), w1, instrs(a, b, "1"),
                versions(a, b)), 409);
        // 主体不存在
        Map<String, Long> missingVersion = new LinkedHashMap<>();
        missingVersion.put(a, 0L);
        missingVersion.put("missing-" + run, 0L);
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq4"), key("sk4"), w1, instrs(a, "missing-" + run, "1"),
                missingVersion), 404);
        // 窗口不存在 -> 404（版本集合与指令主体一致，进入事务后先锁窗口）
        Map<String, Long> ghostVersion = new LinkedHashMap<>();
        ghostVersion.put(a, 0L);
        ghostVersion.put("ghost-" + run, 0L);
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq5"), key("sk5"), 999999999L, instrs(a, "ghost-" + run, "1"),
                ghostVersion), 404);
    }

    @Test
    void reusedInstructionKeyAcrossBatchesAndReusedSettlementKeyReturn409() throws Exception {
        long windowId = createWindow("ch-reuse-" + run, "30");
        String a = approvedSubject(windowId, "user-a", "10");
        String b = approvedSubject(windowId, "user-b", "5");
        String c = approvedSubject(windowId, "user-c", "5");

        List<Instr> first = instrs(a, b, "2");
        String usedInstructionKey = first.get(0).instructionKey();
        String sk1 = key("sk1");
        postOk("/api/settlements", settlementBody(
                key("ck"), key("rq1"), sk1, windowId, first, versions(a, b)));

        // instructionKey 已被其他批次使用（即使指令内容不同）-> 409，失败不留记录
        List<Instr> reuse = new ArrayList<>();
        reuse.add(new Instr(usedInstructionKey, a, c, "1"));
        reuse.add(new Instr(key("ik"), a, c, "1"));
        String sk2 = key("sk2");
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq2"), sk2, windowId, reuse,
                new LinkedHashMap<>(Map.of(a, 1L, c, 0L))), 409);
        mvc.perform(get("/api/settlements/" + sk2)).andExpect(status().isNotFound());
        assertEquals("8", allocation(windowId, a).get("heldAmount").asText());
        assertEquals("5", allocation(windowId, c).get("heldAmount").asText());

        // settlementKey 全局唯一：换 requestId 复用已成功批次的 settlementKey -> 409
        postJson("/api/settlements", settlementBody(
                key("ck"), key("rq3"), sk1, windowId, instrs(a, c, "1"),
                new LinkedHashMap<>(Map.of(a, 1L, c, 0L))), 409);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdIdenticalParamsReplaysEvenWithDifferentCommandKey() throws Exception {
        long windowId = createWindow("ch-idem-" + run, "20");
        String a = approvedSubject(windowId, "user-a", "6");
        String b = approvedSubject(windowId, "user-b", "2");
        List<Instr> instructions = instrs(a, b, "3");
        String requestId = key("rq");
        String settlementKey = key("sk");

        Map<String, Object> body1 = settlementBody(
                key("ck"), requestId, settlementKey, windowId, instructions, versions(a, b));
        JsonNode first = postOk("/api/settlements", body1);
        // 完全相同请求（同 commandKey）重放
        assertEquals(first, postOk("/api/settlements", body1));

        // 同 requestId 完全同参（含相同 settlementKey）但换 commandKey：仍重放首次结果，不产生第二批
        JsonNode replay = postOk("/api/settlements", settlementBody(
                key("ck"), requestId, settlementKey, windowId, instructions, versions(a, b)));
        assertEquals(first, replay);
        assertEquals("3", allocation(windowId, a).get("heldAmount").asText());
        assertEquals(1, allocation(windowId, a).get("version").asInt());
        assertEquals(1, getOk("/api/allocations/" + a + "/settlements").get("settlements").size());

        // 同 requestId 换 settlementKey -> 409（参数不同）
        postJson("/api/settlements", settlementBody(
                key("ck"), requestId, key("sk-other"), windowId, instructions, versions(a, b)), 409);
        // 同 requestId 改体积 -> 409
        postJson("/api/settlements", settlementBody(
                key("ck"), requestId, settlementKey, windowId, instrs(a, b, "2"), versions(a, b)), 409);
    }

    @Test
    void instructionOrderIsBusinessSignificantForSameRequestId() throws Exception {
        long windowId = createWindow("ch-order-" + run, "30");
        String a = approvedSubject(windowId, "user-a", "10");
        String b = approvedSubject(windowId, "user-b", "5");
        String c = approvedSubject(windowId, "user-c", "5");
        String requestId = key("rq");

        List<Instr> order1 = instrs(a, b, "2", b, c, "2");
        List<Instr> order2 = new ArrayList<>();
        // 交换两条指令顺序，净额完全相同
        order2.add(new Instr(order1.get(1).instructionKey(), b, c, "2"));
        order2.add(new Instr(order1.get(0).instructionKey(), a, b, "2"));

        JsonNode first = postOk("/api/settlements", settlementBody(
                key("ck"), requestId, key("sk"), windowId, order1, versions(a, b, c)));
        // 同 requestId 仅指令顺序不同 -> 409
        postJson("/api/settlements", settlementBody(
                key("ck2"), requestId, key("sk2"), windowId, order2,
                new LinkedHashMap<>(Map.of(a, 1L, b, 1L, c, 1L))), 409);
        // 原批次详情保持首次输入顺序
        JsonNode detail = getOk("/api/settlements/" + first.get("settlementKey").asText());
        assertEquals(order1.get(0).instructionKey(),
                detail.get("instructions").get(0).get("instructionKey").asText());
    }

    @Test
    void failedRequestDoesNotOccupyKeys() throws Exception {
        long windowId = createWindow("ch-failidem-" + run, "20");
        String a = approvedSubject(windowId, "user-a", "3");
        String b = approvedSubject(windowId, "user-b", "5");
        String requestId = key("rq");
        String settlementKey = key("sk");

        // 首次 422 失败
        List<Instr> tooBig = instrs(a, b, "4");
        postJson("/api/settlements", settlementBody(
                key("ck"), requestId, settlementKey, windowId, tooBig, versions(a, b)), 422);
        // 同 requestId 同参仍失败（业务条件未变）
        postJson("/api/settlements", settlementBody(
                key("ck2"), requestId, key("sk-other"), windowId, tooBig, versions(a, b)), 422);
        // 同 requestId 改小体积后成功，证明失败不占 requestId / settlementKey / instructionKey
        List<Instr> fixed = List.of(new Instr(tooBig.get(0).instructionKey(), a, b, "2"));
        JsonNode ok = postOk("/api/settlements", settlementBody(
                key("ck3"), requestId, settlementKey, windowId, fixed, versions(a, b)));
        assertEquals(settlementKey, ok.get("settlementKey").asText());
        assertEquals("1", allocation(windowId, a).get("heldAmount").asText());
        assertEquals("7", allocation(windowId, b).get("heldAmount").asText());
        assertEquals(1, getOk("/api/allocations/" + a + "/settlements").get("settlements").size());
    }

    // ------------------------------------------------------------------
    // 并发边界（真实线程 + H2 行锁）
    // ------------------------------------------------------------------

    @Test
    void concurrentSettlementsOnSameSubjectSerializeByVersion() throws Exception {
        long windowId = createWindow("ch-race-set-" + run, "30");
        String a = approvedSubject(windowId, "user-a", "10");
        String b = approvedSubject(windowId, "user-b", "10");
        String c = approvedSubject(windowId, "user-c", "10");

        List<SettlementInstruction> batch1 = dtoInstructions(instrs(a, b, "5"));
        List<SettlementInstruction> batch2 = dtoInstructions(instrs(a, c, "5"));
        List<SubjectVersion> v0 = dtoSubjects(versions(a, b));
        List<SubjectVersion> v0b = dtoSubjects(versions(a, c));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return submitStatus(key("rq"), key("sk"), windowId, batch1, v0);
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return submitStatus(key("rq2"), key("sk2"), windowId, batch2, v0b);
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 按提交顺序：先成功者 A 版本变为 1，后到者版本过期 409，绝不丢更新
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0),
                "两个并发清算恰好一个成功: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0));
        assertEquals("5", allocation(windowId, a).get("heldAmount").asText());
        assertEquals(1, allocation(windowId, a).get("version").asInt());
        assertEquals("30", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    @Test
    void settlementRacingSingleTransferNeverOverdraws() throws Exception {
        long windowId = createWindow("ch-race-tx-" + run, "20");
        String a = approvedSubject(windowId, "user-a", "5");
        String b = approvedSubject(windowId, "user-b", "5");
        String target = requestedSubject(windowId, "user-t", "5");

        List<SettlementInstruction> batch = dtoInstructions(instrs(a, b, "5"));
        List<SubjectVersion> v0 = dtoSubjects(versions(a, b));
        String transferCommand = key("tkc");
        String transferKey = key("tk");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> settlementFuture = pool.submit(() -> {
            gate.await();
            return submitStatus(key("rq"), key("sk"), windowId, batch, v0);
        });
        Future<Integer> transferFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.transferAllocation(transferCommand, transferKey, a, target, "alice");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int settlementStatus = settlementFuture.get(30, TimeUnit.SECONDS);
        int transferStatus = transferFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 两者都试图把 A 的全部 5 转走，按提交顺序恰好一个成功，另一个 422
        assertNotEquals(settlementStatus, transferStatus);
        assertEquals(1, (settlementStatus == 200 ? 1 : 0) + (transferStatus == 200 ? 1 : 0));
        assertEquals(1, (settlementStatus == 422 ? 1 : 0) + (transferStatus == 422 ? 1 : 0));
        // A 最终持有 0 且不超扣；总量守恒 10（A 0 + B/target 共 10）
        assertEquals("0", allocation(windowId, a).get("heldAmount").asText());
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("10", cap.get("approvedTotal").asText());
        assertTrue(Double.parseDouble(cap.get("approvedTotal").asText()) >= 0);
    }

    @Test
    void settlementRacingCurtailmentSettlesByCommitOrder() throws Exception {
        long windowId = createWindow("ch-race-cur-" + run, "10");
        String a = approvedSubject(windowId, "user-a", "6");
        String b = approvedSubject(windowId, "user-b", "2");
        String curtailCommand = key("cu");

        List<SettlementInstruction> batch = dtoInstructions(instrs(a, b, "2"));
        List<SubjectVersion> v0 = dtoSubjects(versions(a, b));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> settlementFuture = pool.submit(() -> {
            gate.await();
            return submitStatus(key("rq"), key("sk"), windowId, batch, v0);
        });
        Future<Integer> curtailFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.createCurtailment(curtailCommand, windowId, "8");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int settlementStatus = settlementFuture.get(30, TimeUnit.SECONDS);
        int curtailStatus = curtailFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 清算总额守恒、限供只改可用总量：二者按窗口锁串行，均成功且不超额
        assertEquals(200, settlementStatus);
        assertEquals(200, curtailStatus);
        assertEquals("4", allocation(windowId, a).get("heldAmount").asText());
        assertEquals("4", allocation(windowId, b).get("heldAmount").asText());
        assertEquals(1, allocation(windowId, a).get("version").asInt());
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("8", cap.get("approvedTotal").asText());
        assertEquals("8", cap.get("availableTotal").asText());
        assertEquals("0", cap.get("remaining").asText());
    }
}
