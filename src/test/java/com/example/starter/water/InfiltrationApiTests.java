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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 田块入渗 API 集成测试（真实 H2，MODE=MySQL）：田块创建幂等、提交校验、
 * 题干合成场景、历史插入重算、取消重算、转让回滚、并发与现况查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InfiltrationApiTests {

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
        String json = postJson(url, body, actor, 200).getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private JsonNode getOk(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long createWindow(String channelId, String start, String end, String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private Map<String, Object> fieldBody(String requestId, String fieldKey, Object capacity,
                                          Object recovery, String enabledAt, Object expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("fieldKey", fieldKey);
        body.put("capacity", capacity);
        body.put("recoveryPerHour", recovery);
        body.put("enabledAtUtc", enabledAt);
        if (expectedVersion != null) {
            body.put("expectedVersion", expectedVersion);
        }
        return body;
    }

    /** 创建田块并返回响应；farmer 由系统合成。 */
    private JsonNode createField(long capacity, long recovery, String enabledAt) throws Exception {
        return postOk("/api/fields",
                fieldBody(key("rq"), key("fk"), capacity, recovery, enabledAt, null), "ops-" + run);
    }

    /** 提交关联田块的申请并返回申请键；expectedStatus 非 200 时返回 null。 */
    private String submit(long windowId, String userId, String amount, String fieldKey,
                          String irrigationUtc, int expectedStatus) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        if (fieldKey != null) {
            body.put("fieldKey", fieldKey);
        }
        if (irrigationUtc != null) {
            body.put("irrigationUtc", irrigationUtc);
        }
        postJson("/api/allocations", body, "alice", expectedStatus);
        return expectedStatus == 200 ? allocationKey : null;
    }

    private MvcResult approve(String allocationKey, int expectedStatus) throws Exception {
        return postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus);
    }

    private JsonNode approveOk(String allocationKey) throws Exception {
        return objectMapper.readTree(approve(allocationKey, 200).getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------
    // 田块创建与 requestId 幂等
    // ------------------------------------------------------------------

    @Test
    void fieldCreateReplayConflictAndFailureNotConsumingKey() throws Exception {
        String fieldKey = key("fk");
        String requestId = key("rq");
        Map<String, Object> body = fieldBody(requestId, fieldKey, 10, 2, "2026-10-20T09:00:00Z", 0);
        JsonNode first = postOk("/api/fields", body, "ops-" + run);
        assertEquals(fieldKey, first.get("fieldKey").asText());
        assertTrue(first.get("farmer").asText().startsWith("farmer-"));
        assertEquals(10, first.get("capacity").asLong());
        assertEquals(2, first.get("recoveryPerHour").asLong());
        assertEquals(0, first.get("version").asLong());
        // 同键同参重放首次完整响应
        JsonNode replay = postOk("/api/fields", body, "ops-" + run);
        assertEquals(first, replay);
        // 异参 409：改容量、改操作者
        Map<String, Object> changed = fieldBody(requestId, fieldKey, 11, 2, "2026-10-20T09:00:00Z", 0);
        postJson("/api/fields", changed, "ops-" + run, 409);
        postJson("/api/fields", body, "other-ops", 409);
        // 期望版本非 0 -> 409
        postJson("/api/fields", fieldBody(key("rq"), key("fk"), 5, 1, "2026-10-20T09:00:00Z", 1),
                "ops-" + run, 409);
        // 参数校验失败（400）不占键：同 requestId 改正参数后成功
        String rid = key("rq");
        String fk = key("fk");
        postJson("/api/fields", fieldBody(rid, fk, 0, 1, "2026-10-20T09:00:00Z", null), "ops-" + run, 400);
        JsonNode fixed = postOk("/api/fields", fieldBody(rid, fk, 5, 1, "2026-10-20T09:00:00Z", null),
                "ops-" + run);
        assertEquals(fk, fixed.get("fieldKey").asText());
        // 业务冲突（409 FIELD_EXISTS，事务内回滚）不占键：同 requestId 换新田块键后成功
        String rid2 = key("rq");
        postJson("/api/fields", fieldBody(rid2, fk, 5, 1, "2026-10-20T09:00:00Z", null), "ops-" + run, 409);
        JsonNode created = postOk("/api/fields",
                fieldBody(rid2, key("fk"), 5, 1, "2026-10-20T09:00:00Z", null), "ops-" + run);
        assertEquals(5, created.get("capacity").asLong());
    }

    // ------------------------------------------------------------------
    // 提交校验：整小时、启用前 400、归属 403、田块不存在 404
    // ------------------------------------------------------------------

    @Test
    void submitWithFieldValidation() throws Exception {
        JsonNode field = createField(10, 2, "2026-10-20T09:00:00Z");
        String fieldKey = field.get("fieldKey").asText();
        String farmer = field.get("farmer").asText();
        long windowId = createWindow("ch-inf-" + run, "2026-10-20T00:00:00Z", "2026-10-21T00:00:00Z", "100");
        // 启用前灌溉 -> 400
        submit(windowId, farmer, "1", fieldKey, "2026-10-20T08:00:00Z", 400);
        // 非整小时 -> 400
        submit(windowId, farmer, "1", fieldKey, "2026-10-20T10:30:00Z", 400);
        // 田块归属不匹配 -> 403
        submit(windowId, "someone-else", "1", fieldKey, "2026-10-20T10:00:00Z", 403);
        // 田块不存在 -> 404
        submit(windowId, farmer, "1", "no-such-" + run, "2026-10-20T10:00:00Z", 404);
        // 合法提交
        String ok = submit(windowId, farmer, "1", fieldKey, "2026-10-20T10:00:00Z", 200);
        JsonNode node = approveOk(ok);
        assertEquals(fieldKey, node.get("fieldKey").asText());
        assertEquals("2026-10-20T10:00:00Z", node.get("irrigationUtc").asText());
        // 有 irrigationUtc 无 fieldKey -> 400
        String bareKey = key("ak");
        Map<String, Object> bare = new HashMap<>();
        bare.put("commandKey", key("ac"));
        bare.put("allocationKey", bareKey);
        bare.put("windowId", windowId);
        bare.put("userId", "u1");
        bare.put("amount", "1");
        bare.put("irrigationUtc", "2026-10-20T10:00:00Z");
        postJson("/api/allocations", bare, "alice", 400);
        // 入渗现况查询：田块不存在 -> 404
        mvc.perform(get("/api/fields/no-such-" + run + "/infiltration")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 题干合成场景：C=10、R=2，10 点批 8、11 点批 4 均可；补批 10 点 1 整次拒绝
    // ------------------------------------------------------------------

    @Test
    void syntheticScenarioRejectsThirdApprovalAtomically() throws Exception {
        JsonNode field = createField(10, 2, "2026-10-20T09:00:00Z");
        String fieldKey = field.get("fieldKey").asText();
        String farmer = field.get("farmer").asText();
        long windowId = createWindow("ch-syn-" + run, "2026-10-20T00:00:00Z", "2026-10-21T00:00:00Z", "100");

        String a1 = submit(windowId, farmer, "8", fieldKey, "2026-10-20T10:00:00Z", 200);
        JsonNode approved1 = approveOk(a1);
        assertEquals("2", approved1.get("infiltrationBalance").asText());
        String a2 = submit(windowId, farmer, "4", fieldKey, "2026-10-20T11:00:00Z", 200);
        JsonNode approved2 = approveOk(a2);
        assertEquals("0", approved2.get("infiltrationBalance").asText());

        // 补批 10 点另一单 1：10 点合计 9 后余额 1，11 点恢复 2 仅 3 < 4 -> 422，报出申请与缺口
        String a3 = submit(windowId, farmer, "1", fieldKey, "2026-10-20T10:00:00Z", 200);
        MvcResult rejected = approve(a3, 422);
        JsonNode error = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals("INFILTRATION_INSUFFICIENT", error.get("code").asText());
        assertTrue(error.get("message").asText().contains(a2), "缺口应定位到 11 点申请: " + error);
        assertTrue(error.get("message").asText().contains("缺口 1"), "缺口应为 1: " + error);

        // 整次拒绝：a3 仍 REQUESTED，已批准总量与冻结余额不变
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        JsonNode allocations = history.get("allocations");
        assertEquals("APPROVED", allocations.get(0).get("status").asText());
        assertEquals("APPROVED", allocations.get(1).get("status").asText());
        assertEquals("REQUESTED", allocations.get(2).get("status").asText());
        assertEquals("2", allocations.get(0).get("infiltrationBalance").asText());
        assertEquals("0", allocations.get(1).get("infiltrationBalance").asText());
        assertEquals("12", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    // ------------------------------------------------------------------
    // 历史插入：不能仅核验新增时点，后续时刻缺口同样拒绝
    // ------------------------------------------------------------------

    @Test
    void historicalInsertRecomputesWholeSequence() throws Exception {
        JsonNode field = createField(10, 2, "2026-10-20T09:00:00Z");
        String fieldKey = field.get("fieldKey").asText();
        String farmer = field.get("farmer").asText();
        long windowId = createWindow("ch-his2-" + run, "2026-10-20T00:00:00Z", "2026-10-21T00:00:00Z", "100");

        // 先批 11 点 4（独占时序列合法：10-4=6）
        String late = submit(windowId, farmer, "4", fieldKey, "2026-10-20T11:00:00Z", 200);
        assertEquals("6", approveOk(late).get("infiltrationBalance").asText());
        // 历史插入 10 点 8：重算后 10 点余 2、11 点恢复 2 恰为 4 -> 合法
        String early = submit(windowId, farmer, "8", fieldKey, "2026-10-20T10:00:00Z", 200);
        assertEquals("2", approveOk(early).get("infiltrationBalance").asText());
        // 再插 10 点 1：新增时点本身合法（10-8-1=1），但使 11 点不足 -> 422
        String extra = submit(windowId, farmer, "1", fieldKey, "2026-10-20T10:00:00Z", 200);
        MvcResult rejected = approve(extra, 422);
        JsonNode error = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals("INFILTRATION_INSUFFICIENT", error.get("code").asText());
        assertTrue(error.get("message").asText().contains(late));
        // 全部申请与额度不变
        JsonNode allocations = getOk("/api/windows/" + windowId + "/history").get("allocations");
        assertEquals("REQUESTED", allocations.get(2).get("status").asText());
        assertEquals("12", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    // ------------------------------------------------------------------
    // 取消后按最终序列重算
    // ------------------------------------------------------------------

    @Test
    void cancelRecomputesFinalSequence() throws Exception {
        JsonNode field = createField(10, 2, "2026-10-20T09:00:00Z");
        String fieldKey = field.get("fieldKey").asText();
        String farmer = field.get("farmer").asText();
        long windowId = createWindow("ch-can-" + run, "2026-10-20T00:00:00Z", "2026-10-21T00:00:00Z", "100");

        String a1 = submit(windowId, farmer, "8", fieldKey, "2026-10-20T10:00:00Z", 200);
        approveOk(a1);
        String a2 = submit(windowId, farmer, "4", fieldKey, "2026-10-20T11:00:00Z", 200);
        approveOk(a2);
        // 取消 10 点 8 -> 释放入渗需求
        postOk("/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "alice");
        // 10 点 9 仍使 11 点不足（9 -> 余 1，恢复 2 仅 3 < 4）-> 422
        String tooBig = submit(windowId, farmer, "9", fieldKey, "2026-10-20T10:00:00Z", 200);
        approve(tooBig, 422);
        // 10 点 8 与取消前等效 -> 合法
        String fits = submit(windowId, farmer, "8", fieldKey, "2026-10-20T10:00:00Z", 200);
        assertEquals("2", approveOk(fits).get("infiltrationBalance").asText());
        assertEquals("12", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    // ------------------------------------------------------------------
    // 转让：目标入渗不足则 422 整体回滚；合法转让冻结目标余额
    // ------------------------------------------------------------------

    @Test
    void transferRecomputesAndRollsBackOnDeficit() throws Exception {
        JsonNode field = createField(10, 2, "2026-10-20T09:00:00Z");
        String fieldKey = field.get("fieldKey").asText();
        String farmer = field.get("farmer").asText();
        long windowId = createWindow("ch-tr-" + run, "2026-10-20T00:00:00Z", "2026-10-21T00:00:00Z", "100");

        String a1 = submit(windowId, farmer, "8", fieldKey, "2026-10-20T10:00:00Z", 200);
        approveOk(a1);
        String s1 = submit(windowId, "other-user", "6", null, null, 200);
        approveOk(s1);
        // 目标 6 在 10 点：8+6=14 > 10 -> 422 整体回滚
        String t1 = submit(windowId, farmer, "6", fieldKey, "2026-10-20T10:00:00Z", 200);
        Map<String, Object> transferBody = new HashMap<>();
        transferBody.put("commandKey", key("tc"));
        transferBody.put("transferKey", key("tk"));
        transferBody.put("sourceAllocationKey", s1);
        transferBody.put("targetAllocationKey", t1);
        MvcResult rejected = postJson("/api/transfers", transferBody, "alice", 422);
        JsonNode error = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertEquals("INFILTRATION_INSUFFICIENT", error.get("code").asText());
        // 同小时按申请键字典序扣量，8+6=14 超出容量 10，缺口 4
        assertTrue(error.get("message").asText().contains("缺口 4"),
                "应报出缺口 4: " + error.get("message").asText());
        // 回滚：源持有不变、目标仍 REQUESTED、无转让流水、已批准总量不变
        JsonNode allocations = getOk("/api/windows/" + windowId + "/history").get("allocations");
        assertEquals("6", allocations.get(1).get("heldAmount").asText());
        assertEquals("REQUESTED", allocations.get(2).get("status").asText());
        assertEquals(0, getOk("/api/windows/" + windowId + "/transfers").get("transfers").size());
        assertEquals("14", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
        // 合法转让：目标 2 在 10 点（8+2=10）-> 200，冻结目标余额 0，源持有 4
        String t2 = submit(windowId, farmer, "2", fieldKey, "2026-10-20T10:00:00Z", 200);
        Map<String, Object> okBody = new HashMap<>();
        okBody.put("commandKey", key("tc"));
        okBody.put("transferKey", key("tk"));
        okBody.put("sourceAllocationKey", s1);
        okBody.put("targetAllocationKey", t2);
        JsonNode transfer = postOk("/api/transfers", okBody, "alice");
        assertEquals("2", transfer.get("amount").asText());
        JsonNode after = getOk("/api/windows/" + windowId + "/history").get("allocations");
        assertEquals("4", after.get(1).get("heldAmount").asText());
        assertEquals("APPROVED", after.get(3).get("status").asText());
        // 同小时按申请键字典序扣量：t2 键字典序在 a1 前，先扣 2 余 8，冻结余额为 8
        assertEquals("8", after.get(3).get("infiltrationBalance").asText());
        // 全序列终态：t2(2) 后 a1(8)，最终余额 0
        assertEquals("0", getOk("/api/fields/" + fieldKey + "/infiltration").get("finalBalance").asText());
    }

    // ------------------------------------------------------------------
    // 并发批准：同一田块两单仅一单合法，保持一个合法后态
    // ------------------------------------------------------------------

    @Test
    void concurrentApprovesKeepOneLegalState() throws Exception {
        JsonNode field = createField(10, 2, "2026-10-20T09:00:00Z");
        String fieldKey = field.get("fieldKey").asText();
        String farmer = field.get("farmer").asText();
        long windowId = createWindow("ch-cc-" + run, "2026-10-20T00:00:00Z", "2026-10-21T00:00:00Z", "100");
        String a1 = submit(windowId, farmer, "8", fieldKey, "2026-10-20T10:00:00Z", 200);
        String a2 = submit(windowId, farmer, "8", fieldKey, "2026-10-20T10:00:00Z", 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String allocationKey : List.of(a1, a2)) {
            String commandKey = key("ap");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.approveAllocation(commandKey, allocationKey);
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        // 两单各 8，同小时合计 16 > 10：恰好一单成功一单 422
        assertTrue(statuses.contains(200) && statuses.contains(422), "statuses=" + statuses);
        assertEquals("8", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
        JsonNode infiltration = getOk("/api/fields/" + fieldKey + "/infiltration");
        assertEquals(1, infiltration.get("events").size());
        assertEquals("2", infiltration.get("finalBalance").asText());
    }

    // ------------------------------------------------------------------
    // 现况查询：重算最新序列但不改历史冻结余额
    // ------------------------------------------------------------------

    @Test
    void infiltrationQueryRecomputesWithoutChangingHistory() throws Exception {
        JsonNode field = createField(10, 2, "2026-10-20T09:00:00Z");
        String fieldKey = field.get("fieldKey").asText();
        String farmer = field.get("farmer").asText();
        long windowId = createWindow("ch-qry-" + run, "2026-10-20T00:00:00Z", "2026-10-21T00:00:00Z", "100");
        String a1 = submit(windowId, farmer, "8", fieldKey, "2026-10-20T10:00:00Z", 200);
        approveOk(a1);
        String a2 = submit(windowId, farmer, "4", fieldKey, "2026-10-20T11:00:00Z", 200);
        approveOk(a2);

        JsonNode current = getOk("/api/fields/" + fieldKey + "/infiltration");
        assertEquals(10, current.get("capacity").asLong());
        assertEquals(2, current.get("recoveryPerHour").asLong());
        assertEquals(2, current.get("events").size());
        assertEquals(a1, current.get("events").get(0).get("allocationKey").asText());
        assertEquals("2", current.get("events").get(0).get("balanceAfter").asText());
        assertEquals(a2, current.get("events").get(1).get("allocationKey").asText());
        assertEquals("0", current.get("events").get(1).get("balanceAfter").asText());
        assertEquals("0", current.get("finalBalance").asText());

        // 取消 a1 后现况重算：a2 成为首个事件，11 点恢复 2 小时后扣 4 余 6
        postOk("/api/allocations/" + a1 + "/cancel", Map.of("commandKey", key("cc")), "alice");
        JsonNode recomputed = getOk("/api/fields/" + fieldKey + "/infiltration");
        assertEquals(1, recomputed.get("events").size());
        assertEquals("6", recomputed.get("events").get(0).get("balanceAfter").asText());
        assertEquals("6", recomputed.get("finalBalance").asText());
        // 历史冻结余额不改写：已取消的 a1 仍保留批准时的 2
        JsonNode allocations = getOk("/api/windows/" + windowId + "/history").get("allocations");
        assertEquals("CANCELLED", allocations.get(0).get("status").asText());
        assertEquals("2", allocations.get(0).get("infiltrationBalance").asText());
    }
}
