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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 田块入渗余额 API 测试：合成场景、首次关联、同小时字典序、历史插入重算。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FieldInfiltrationApiTests {

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
        return objectMapper.readTree(
                mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse()
                        .getContentAsString());
    }

    private long createWindow(String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", key("ch"));
        body.put("startUtc", "2026-10-01T00:00:00Z");
        body.put("endUtc", "2026-10-10T00:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private Map<String, Object> fieldBody(String requestId, String allocationKey, long windowId,
                                          String userId, String amount, String fieldKey, String irrigation,
                                          Long capacity, Long recovery, String enabled,
                                          Long expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        body.put("fieldKey", fieldKey);
        body.put("irrigationUtc", irrigation);
        body.put("capacity", capacity);
        body.put("recoveryPerHour", recovery);
        body.put("enabledUtc", enabled);
        body.put("expectedFieldVersion", expectedVersion);
        return body;
    }

    /** 提交田块绑定申请并断言 200，返回 allocationKey。 */
    private String submitField(long windowId, String userId, String amount, String fieldKey,
                               String irrigation, long capacity, long recovery, String enabled,
                               String actor) throws Exception {
        String allocationKey = key("fa");
        JsonNode node = postOk("/api/field-allocations",
                fieldBody(key("rid"), allocationKey, windowId, userId, amount, fieldKey, irrigation,
                        capacity, recovery, enabled, null), actor);
        assertEquals("REQUESTED", node.get("status").asText());
        assertEquals(fieldKey, node.get("fieldKey").asText());
        return allocationKey;
    }

    private JsonNode approveNode(String allocationKey, int expectedStatus) throws Exception {
        String json = postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus).getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private JsonNode balance(String fieldKey) throws Exception {
        return getOk("/api/fields/" + fieldKey + "/balance");
    }

    private JsonNode allocation(long windowId, String allocationKey) throws Exception {
        for (JsonNode node : getOk("/api/windows/" + windowId + "/history").get("allocations")) {
            if (allocationKey.equals(node.get("allocationKey").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("allocation not found: " + allocationKey);
    }

    // ------------------------------------------------------------------
    // 题干合成场景
    // ------------------------------------------------------------------

    @Test
    void syntheticScenarioCapacity10Recovery2() throws Exception {
        long windowId = createWindow("100");
        String field = key("fld");
        String enabled = "2026-10-01T00:00:00Z";
        String a = submitField(windowId, "u1", "8", field, "2026-10-01T10:00:00Z", 10, 2, enabled, "alice");
        String b = submitField(windowId, "u1", "4", field, "2026-10-01T11:00:00Z", 10, 2, enabled, "alice");
        approveNode(a, 200);
        approveNode(b, 200);

        JsonNode bal = balance(field);
        assertEquals(2, bal.get("version").asLong());
        assertEquals("10", bal.get("capacity").asText());
        assertEquals("2", bal.get("recoveryPerHour").asText());
        JsonNode current = bal.get("current");
        assertEquals(2, current.size());
        assertEquals(a, current.get(0).get("allocationKey").asText());
        assertEquals("8", current.get(0).get("amount").asText());
        assertEquals("2", current.get(0).get("balanceAfter").asText());
        assertEquals(b, current.get(1).get("allocationKey").asText());
        assertEquals("0", current.get(1).get("balanceAfter").asText());
        // 批准时冻结的快照与现况一致
        assertEquals(current, bal.get("frozen"));

        // 补批 10 点另一单 1：10 点扣 8+1 后余额 1，11 点恢复到 3，缺口 1，整次拒绝
        String c = submitField(windowId, "u1", "1", field, "2026-10-01T10:00:00Z", 10, 2, enabled, "alice");
        JsonNode error = approveNode(c, 422);
        assertEquals("INFILTRATION_INSUFFICIENT", error.get("code").asText());
        assertTrue(error.get("message").asText().contains(b), error.toString());
        assertTrue(error.get("message").asText().contains("缺口 1"), error.toString());
        // 原额度与所有申请不变：c 仍 REQUESTED，a/b 仍 APPROVED，版本与快照不变
        assertEquals("REQUESTED", allocation(windowId, c).get("status").asText());
        assertEquals("APPROVED", allocation(windowId, a).get("status").asText());
        assertEquals("APPROVED", allocation(windowId, b).get("status").asText());
        JsonNode after = balance(field);
        assertEquals(2, after.get("version").asLong());
        assertEquals(bal.get("frozen"), after.get("frozen"));
        assertEquals(2, after.get("current").size());
    }

    // ------------------------------------------------------------------
    // 首次关联与参数校验
    // ------------------------------------------------------------------

    @Test
    void firstAssociationSynthesizesFieldAndValidatesParams() throws Exception {
        long windowId = createWindow("100");
        String field = key("fld");
        String enabled = "2026-10-01T00:00:00Z";
        String a = submitField(windowId, "u1", "1", field, "2026-10-01T10:00:00Z", 10, 2, enabled, "alice");

        JsonNode bal = balance(field);
        assertEquals("alice", bal.get("ownerUserId").asText());
        assertEquals(0, bal.get("version").asLong());
        assertEquals(0, bal.get("current").size());
        assertTrue(bal.get("frozen").isNull());

        // 田块归属不匹配 -> 403
        JsonNode denied = objectMapper.readTree(postJson("/api/field-allocations",
                fieldBody(key("rid"), key("fa"), windowId, "u2", "1", field, "2026-10-01T12:00:00Z",
                        10L, 2L, enabled, null), "bob", 403).getResponse().getContentAsString());
        assertEquals("FIELD_OWNERSHIP_MISMATCH", denied.get("code").asText());
        // 配置不可变：C/R/启用时刻不一致 -> 409
        postJson("/api/field-allocations", fieldBody(key("rid"), key("fa"), windowId, "u1", "1", field,
                "2026-10-01T12:00:00Z", 11L, 2L, enabled, null), "alice", 409);
        postJson("/api/field-allocations", fieldBody(key("rid"), key("fa"), windowId, "u1", "1", field,
                "2026-10-01T12:00:00Z", 10L, 3L, enabled, null), "alice", 409);
        postJson("/api/field-allocations", fieldBody(key("rid"), key("fa"), windowId, "u1", "1", field,
                "2026-10-01T12:00:00Z", 10L, 2L, "2026-10-01T01:00:00Z", null), "alice", 409);
        // 启用前灌溉 -> 400
        postJson("/api/field-allocations", fieldBody(key("rid"), key("fa"), windowId, "u1", "1",
                key("fld"), "2026-09-30T23:00:00Z", 10L, 2L, enabled, null), "alice", 400);
        // 非整小时 -> 400
        postJson("/api/field-allocations", fieldBody(key("rid"), key("fa"), windowId, "u1", "1",
                key("fld"), "2026-10-01T10:30:00Z", 10L, 2L, enabled, null), "alice", 400);
        // C/R 非正整数 -> 400
        postJson("/api/field-allocations", fieldBody(key("rid"), key("fa"), windowId, "u1", "1",
                key("fld"), "2026-10-01T10:00:00Z", 0L, 2L, enabled, null), "alice", 400);
        postJson("/api/field-allocations", fieldBody(key("rid"), key("fa"), windowId, "u1", "1",
                key("fld"), "2026-10-01T10:00:00Z", 10L, -1L, enabled, null), "alice", 400);
        // 田块不存在 -> 404
        mvc.perform(get("/api/fields/no-such-" + run + "/balance")).andExpect(status().isNotFound());
        // 首次申请仍 REQUESTED，不计入序列
        assertEquals("REQUESTED", allocation(windowId, a).get("status").asText());
    }

    // ------------------------------------------------------------------
    // 同小时字典序与历史插入
    // ------------------------------------------------------------------

    @Test
    void sameHourDeductsInAllocationKeyOrder() throws Exception {
        long windowId = createWindow("100");
        String field = key("fld");
        String enabled = "2026-10-01T00:00:00Z";
        String ka = "ka-" + run;
        String kb = "kb-" + run;
        postOk("/api/field-allocations", fieldBody(key("rid"), ka, windowId, "u1", "3", field,
                "2026-10-01T10:00:00Z", 5L, 1L, enabled, null), "alice");
        postOk("/api/field-allocations", fieldBody(key("rid"), kb, windowId, "u1", "3", field,
                "2026-10-01T10:00:00Z", 5L, 1L, enabled, null), "alice");
        // 字典序 ka 先扣：5-3=2；kb 再扣 3 缺口 1
        approveNode(ka, 200);
        JsonNode error = approveNode(kb, 422);
        assertEquals("INFILTRATION_INSUFFICIENT", error.get("code").asText());
        assertTrue(error.get("message").asText().contains(kb), error.toString());
        assertTrue(error.get("message").asText().contains("缺口 1"), error.toString());
        // 取消 ka 后按最终序列重算，kb 可批准
        postOk("/api/allocations/" + ka + "/cancel", Map.of("commandKey", key("cc")), "alice");
        approveNode(kb, 200);
        JsonNode current = balance(field).get("current");
        assertEquals(1, current.size());
        assertEquals(kb, current.get(0).get("allocationKey").asText());
        assertEquals("2", current.get(0).get("balanceAfter").asText());
    }

    @Test
    void historicalInsertionRechecksLaterEvents() throws Exception {
        long windowId = createWindow("100");
        String field = key("fld");
        String enabled = "2026-10-01T00:00:00Z";
        String a = submitField(windowId, "u1", "8", field, "2026-10-01T10:00:00Z", 10, 2, enabled, "alice");
        String b = submitField(windowId, "u1", "4", field, "2026-10-01T12:00:00Z", 10, 2, enabled, "alice");
        approveNode(a, 200);
        approveNode(b, 200);
        // 历史插入 11 点的 3：10 点余额 2，11 点恢复到 4 扣 3 余 1，12 点恢复到 3 扣 4 缺口 1
        // 缺口出现在既有更晚申请 b 上，而非新增申请 c
        String c = submitField(windowId, "u1", "3", field, "2026-10-01T11:00:00Z", 10, 2, enabled, "alice");
        JsonNode error = approveNode(c, 422);
        assertEquals("INFILTRATION_INSUFFICIENT", error.get("code").asText());
        assertTrue(error.get("message").asText().contains(b), error.toString());
        assertEquals("REQUESTED", allocation(windowId, c).get("status").asText());
        assertEquals(2, balance(field).get("version").asLong());
        // 取消 12 点的 b 后，c 可批准
        postOk("/api/allocations/" + b + "/cancel", Map.of("commandKey", key("cc")), "alice");
        approveNode(c, 200);
        JsonNode current = balance(field).get("current");
        assertEquals(2, current.size());
        assertEquals(a, current.get(0).get("allocationKey").asText());
        assertEquals("2", current.get(0).get("balanceAfter").asText());
        assertEquals(c, current.get(1).get("allocationKey").asText());
        assertEquals("1", current.get(1).get("balanceAfter").asText());
    }

    // ------------------------------------------------------------------
    // 窗口总额度不变，仍独立生效
    // ------------------------------------------------------------------

    @Test
    void windowQuotaStillAppliesToFieldBoundAllocations() throws Exception {
        long windowId = createWindow("5");
        String field = key("fld");
        String a = submitField(windowId, "u1", "8", field, "2026-10-01T10:00:00Z", 10, 1,
                "2026-10-01T00:00:00Z", "alice");
        // 田块余额够（10），窗口计划水量 5 不够 -> 422 窗口配额
        JsonNode error = approveNode(a, 422);
        assertEquals("QUOTA_EXCEEDED", error.get("code").asText());
        assertEquals("0", getOk("/api/windows/" + windowId + "/capacity").get("approvedTotal").asText());
    }

    // ------------------------------------------------------------------
    // requestId 幂等
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdReplaysAndDifferentParamsConflict() throws Exception {
        long windowId = createWindow("100");
        String field = key("fld");
        String enabled = "2026-10-01T00:00:00Z";
        String requestId = key("rid");
        String allocationKey = key("fa");
        Map<String, Object> body = fieldBody(requestId, allocationKey, windowId, "u1", "2", field,
                "2026-10-01T10:00:00Z", 10L, 2L, enabled, null);
        JsonNode first = postOk("/api/field-allocations", body, "alice");
        JsonNode replay = postOk("/api/field-allocations", body, "alice");
        assertEquals(first, replay);
        // 只创建了一条申请
        assertEquals(1, getOk("/api/windows/" + windowId + "/history").get("allocations").size());
        // 同键改参（水量）-> 409
        postJson("/api/field-allocations", fieldBody(requestId, allocationKey, windowId, "u1", "3", field,
                "2026-10-01T10:00:00Z", 10L, 2L, enabled, null), "alice", 409);
        // 同键改操作者 -> 409
        postJson("/api/field-allocations", body, "bob", 409);
        // 同键改期望版本 -> 409
        postJson("/api/field-allocations", fieldBody(requestId, allocationKey, windowId, "u1", "2", field,
                "2026-10-01T10:00:00Z", 10L, 2L, enabled, 0L), "alice", 409);
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() throws Exception {
        long windowId = createWindow("100");
        String field = key("fld");
        String enabled = "2026-10-01T00:00:00Z";
        submitField(windowId, "u1", "1", field, "2026-10-01T10:00:00Z", 10, 2, enabled, "alice");
        // 403 失败不占键：归属不符后，归属人可用同 requestId 成功写入
        String requestId = key("rid");
        String allocationKey = key("fa");
        Map<String, Object> body = fieldBody(requestId, allocationKey, windowId, "u1", "1", field,
                "2026-10-01T11:00:00Z", 10L, 2L, enabled, null);
        postJson("/api/field-allocations", body, "bob", 403);
        JsonNode ok = postOk("/api/field-allocations", body, "alice");
        assertEquals(allocationKey, ok.get("allocationKey").asText());
        // 404 失败不占键：窗口不存在后，修正窗口可用同 requestId 成功
        String requestId2 = key("rid");
        String allocationKey2 = key("fa");
        postJson("/api/field-allocations", fieldBody(requestId2, allocationKey2, 999999999L, "u1", "1",
                field, "2026-10-01T12:00:00Z", 10L, 2L, enabled, null), "alice", 404);
        JsonNode ok2 = postOk("/api/field-allocations", fieldBody(requestId2, allocationKey2, windowId,
                "u1", "1", field, "2026-10-01T12:00:00Z", 10L, 2L, enabled, null), "alice");
        assertEquals(allocationKey2, ok2.get("allocationKey").asText());
    }

    // ------------------------------------------------------------------
    // 期望版本
    // ------------------------------------------------------------------

    @Test
    void expectedFieldVersionGuardsAgainstStaleWrites() throws Exception {
        long windowId = createWindow("100");
        String field = key("fld");
        String enabled = "2026-10-01T00:00:00Z";
        String a = submitField(windowId, "u1", "1", field, "2026-10-01T10:00:00Z", 10, 2, enabled, "alice");
        approveNode(a, 200);
        assertEquals(1, balance(field).get("version").asLong());
        // 期望版本过期 -> 409
        postJson("/api/field-allocations", fieldBody(key("rid"), key("fa"), windowId, "u1", "1", field,
                "2026-10-01T11:00:00Z", 10L, 2L, enabled, 0L), "alice", 409);
        // 期望版本匹配 -> 200
        postOk("/api/field-allocations", fieldBody(key("rid"), key("fa"), windowId, "u1", "1", field,
                "2026-10-01T11:00:00Z", 10L, 2L, enabled, 1L), "alice");
        // 新田块期望版本非 0 -> 409
        postJson("/api/field-allocations", fieldBody(key("rid"), key("fa"), windowId, "u1", "1",
                key("fld"), "2026-10-01T10:00:00Z", 10L, 2L, enabled, 2L), "alice", 409);
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentApprovesKeepOneLegalPostState() throws Exception {
        long windowId = createWindow("100");
        String field = key("fld");
        String enabled = "2026-10-01T00:00:00Z";
        String a = submitField(windowId, "u1", "6", field, "2026-10-01T10:00:00Z", 10, 1, enabled, "alice");
        String b = submitField(windowId, "u1", "6", field, "2026-10-01T10:00:00Z", 10, 1, enabled, "alice");

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        String approveCommandA = key("ap");
        String approveCommandB = key("ap");
        var f1 = pool.submit(() -> {
            gate.await();
            try {
                waterService.approveAllocation(approveCommandA, a);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        var f2 = pool.submit(() -> {
            gate.await();
            try {
                waterService.approveAllocation(approveCommandB, b);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int s1 = f1.get(30, java.util.concurrent.TimeUnit.SECONDS);
        int s2 = f2.get(30, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();

        // 田块容量 10 只装得下一单 6：恰好一个成功，另一个 422
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "approve: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 422 ? 1 : 0) + (s2 == 422 ? 1 : 0));
        JsonNode bal = balance(field);
        assertEquals(1, bal.get("version").asLong());
        assertEquals(1, bal.get("current").size());
        assertEquals("4", bal.get("current").get(0).get("balanceAfter").asText());
    }

    @Test
    void concurrentSameRequestIdSettlesWithoutLeakingUniqueKey() throws Exception {
        long windowId = createWindow("100");
        String field = key("fld");
        String enabled = "2026-10-01T00:00:00Z";
        String requestId = key("rid");
        String allocationKey = key("fa");
        Map<String, Object> body = fieldBody(requestId, allocationKey, windowId, "u1", "2", field,
                "2026-10-01T10:00:00Z", 10L, 2L, enabled, null);
        String bodyJson = objectMapper.writeValueAsString(body);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        var f1 = pool.submit(() -> {
            gate.await();
            return mvc.perform(post("/api/field-allocations").contentType(MediaType.APPLICATION_JSON)
                            .content(bodyJson).header("X-Actor-Id", "alice"))
                    .andReturn().getResponse().getStatus();
        });
        var f2 = pool.submit(() -> {
            gate.await();
            return mvc.perform(post("/api/field-allocations").contentType(MediaType.APPLICATION_JSON)
                            .content(bodyJson).header("X-Actor-Id", "alice"))
                    .andReturn().getResponse().getStatus();
        });
        gate.countDown();
        int s1 = f1.get(30, java.util.concurrent.TimeUnit.SECONDS);
        int s2 = f2.get(30, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();

        // 不泄露唯一键异常（无 500）：同参并发同键最终一个成功一个重放或提示重试
        assertTrue(s1 == 200 || s1 == 409, "s1=" + s1);
        assertTrue(s2 == 200 || s2 == 409, "s2=" + s2);
        assertTrue(s1 == 200 || s2 == 200);
        // 恰好一条申请
        assertEquals(1, getOk("/api/windows/" + windowId + "/history").get("allocations").size());
    }

    // ------------------------------------------------------------------
    // 转让重算与冻结快照
    // ------------------------------------------------------------------

    @Test
    void transferRecomputesBothFields() throws Exception {
        long windowId = createWindow("100");
        String f1 = key("fld");
        String f2 = key("fld");
        String enabled = "2026-10-01T00:00:00Z";
        String source = submitField(windowId, "u-src", "6", f1, "2026-10-01T10:00:00Z", 10, 1, enabled,
                "alice");
        approveNode(source, 200);
        // 目标田块 C=3，转入 4 不足 -> 422，双方田块与额度不变
        String target = submitField(windowId, "u-dst", "4", f2, "2026-10-01T11:00:00Z", 3, 1, enabled,
                "alice");
        Map<String, Object> transferBody = new HashMap<>();
        transferBody.put("commandKey", key("tc"));
        transferBody.put("transferKey", key("tk"));
        transferBody.put("sourceAllocationKey", source);
        transferBody.put("targetAllocationKey", target);
        JsonNode error = objectMapper.readTree(postJson("/api/transfers", transferBody, "alice", 422)
                .getResponse().getContentAsString());
        assertEquals("INFILTRATION_INSUFFICIENT", error.get("code").asText());
        assertTrue(error.get("message").asText().contains(target), error.toString());
        assertEquals("6", allocation(windowId, source).get("heldAmount").asText());
        assertEquals("REQUESTED", allocation(windowId, target).get("status").asText());
        assertEquals(0, getOk("/api/windows/" + windowId + "/transfers").get("transfers").size());
        assertEquals(0, balance(f2).get("version").asLong());

        // 转入 3 可行：源田块持有扣减、目标田块新增批准，双方版本推进
        String target2 = submitField(windowId, "u-dst2", "3", f2, "2026-10-01T11:00:00Z", 3, 1, enabled,
                "alice");
        transferBody.put("commandKey", key("tc"));
        transferBody.put("transferKey", key("tk"));
        transferBody.put("targetAllocationKey", target2);
        postOk("/api/transfers", transferBody, "alice");
        JsonNode bal1 = balance(f1);
        assertEquals(2, bal1.get("version").asLong());
        assertEquals("3", bal1.get("current").get(0).get("amount").asText());
        assertEquals("7", bal1.get("current").get(0).get("balanceAfter").asText());
        JsonNode bal2 = balance(f2);
        assertEquals(1, bal2.get("version").asLong());
        assertEquals("0", bal2.get("current").get(0).get("balanceAfter").asText());
    }

    @Test
    void approveFreezesSnapshotAndQueryDoesNotRewriteHistory() throws Exception {
        long windowId = createWindow("100");
        String field = key("fld");
        String enabled = "2026-10-01T00:00:00Z";
        String a = submitField(windowId, "u1", "8", field, "2026-10-01T10:00:00Z", 10, 2, enabled, "alice");
        approveNode(a, 200);
        JsonNode frozen1 = balance(field).get("frozen");
        assertEquals(1, frozen1.size());
        assertEquals("2", frozen1.get(0).get("balanceAfter").asText());

        String b = submitField(windowId, "u1", "4", field, "2026-10-01T11:00:00Z", 10, 2, enabled, "alice");
        approveNode(b, 200);
        JsonNode frozen2 = balance(field).get("frozen");
        assertEquals(2, frozen2.size());
        assertEquals("0", frozen2.get(1).get("balanceAfter").asText());

        // 取消 b：现况重算只剩 a，但批准时冻结的历史快照不被改写
        postOk("/api/allocations/" + b + "/cancel", Map.of("commandKey", key("cc")), "alice");
        JsonNode bal = balance(field);
        assertEquals(1, bal.get("current").size());
        assertEquals(a, bal.get("current").get(0).get("allocationKey").asText());
        assertEquals(frozen2, bal.get("frozen"));
        assertEquals(3, bal.get("version").asLong());
        // 再次查询仍不改写历史
        assertEquals(frozen2, balance(field).get("frozen"));
    }
}
