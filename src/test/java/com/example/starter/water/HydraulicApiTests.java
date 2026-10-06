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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 简化水力模型 API 测试：渠段版本化、路径申请校验、批准重算、阻力修改、转让继承与幂等。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HydraulicApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    private long createWindow(String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", "ch-h-" + run + "-" + seq);
        body.put("startUtc", "2027-01-01T00:00:00Z");
        body.put("endUtc", "2027-01-02T00:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private void createSegment(String segmentKey, long resistance, int expectedStatus) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", key("sc"));
        body.put("segmentKey", segmentKey);
        body.put("resistance", resistance);
        postJson("/api/segments", body, "operator", expectedStatus);
    }

    private Map<String, Object> pathBody(String requestId, String allocationKey, long windowId,
                                         String userId, long flow, String start, String end, long head,
                                         List<String> segments) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("flow", flow);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("availableHead", head);
        body.put("segments", segments);
        return body;
    }

    private String submitPath(long windowId, String userId, long flow, String start, String end,
                              long head, List<String> segments, String actor) throws Exception {
        String allocationKey = key("pa");
        postOk("/api/path-allocations",
                pathBody(key("pac"), allocationKey, windowId, userId, flow, start, end, head, segments),
                actor);
        return allocationKey;
    }

    private void approve(String allocationKey, int expectedStatus) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus);
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

    // ------------------------------------------------------------------
    // 渠段
    // ------------------------------------------------------------------

    @Test
    void segmentLifecycleAndVersionConflict() throws Exception {
        String segment = key("seg");
        createSegment(segment, 1, 200);
        JsonNode created = getOk("/api/segments/" + segment);
        assertEquals(1, created.get("resistance").asLong());
        assertEquals(1, created.get("version").asInt());

        // 重复创建 -> 409
        Map<String, Object> dup = new HashMap<>();
        dup.put("requestId", key("sc"));
        dup.put("segmentKey", segment);
        dup.put("resistance", 5);
        postJson("/api/segments", dup, "operator", 409);

        // 版本匹配 -> 200，版本 +1
        Map<String, Object> update = new HashMap<>();
        update.put("requestId", key("su"));
        update.put("resistance", 7);
        update.put("expectedVersion", 1);
        JsonNode updated = postOk("/api/segments/" + segment + "/resistance", update, "operator");
        assertEquals(7, updated.get("resistance").asLong());
        assertEquals(2, updated.get("version").asInt());

        // 版本过期（新 requestId，expectedVersion 仍为 1）-> 409，阻力与版本不变
        Map<String, Object> stale = new HashMap<>();
        stale.put("requestId", key("su"));
        stale.put("resistance", 7);
        stale.put("expectedVersion", 1);
        postJson("/api/segments/" + segment + "/resistance", stale, "operator", 409);
        JsonNode after = getOk("/api/segments/" + segment);
        assertEquals(7, after.get("resistance").asLong());
        assertEquals(2, after.get("version").asInt());

        // 不存在 -> 404
        Map<String, Object> missing = new HashMap<>();
        missing.put("requestId", key("su"));
        missing.put("resistance", 1);
        missing.put("expectedVersion", 1);
        postJson("/api/segments/" + key("missing") + "/resistance", missing, "operator", 404);
        mvc.perform(get("/api/segments/" + key("missing"))).andExpect(status().isNotFound());
    }

    @Test
    void segmentAndPathValidationFailures() throws Exception {
        String segment = key("seg");
        // 阻力非法 -> 400
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", key("sc"));
        body.put("segmentKey", segment);
        body.put("resistance", 0);
        postJson("/api/segments", body, "operator", 400);
        body.put("resistance", -3);
        postJson("/api/segments", body, "operator", 400);
        body.put("resistance", null);
        postJson("/api/segments", body, "operator", 400);
        // 缺操作人 -> 400
        body.put("resistance", 1);
        postJson("/api/segments", body, null, 400);
        createSegment(segment, 1, 200);
        // expectedVersion 非法 -> 400
        Map<String, Object> update = new HashMap<>();
        update.put("requestId", key("su"));
        update.put("resistance", 2);
        update.put("expectedVersion", 0);
        postJson("/api/segments/" + segment + "/resistance", update, "operator", 400);

        long windowId = createWindow("100000");
        // 段数超过 5 / 重复 / 空 -> 400
        postJson("/api/path-allocations", pathBody(key("pac"), key("pa"), windowId, "u1", 1,
                "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z", 100,
                List.of("s1", "s2", "s3", "s4", "s5", "s6")), "alice", 400);
        postJson("/api/path-allocations", pathBody(key("pac"), key("pa"), windowId, "u1", 1,
                "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z", 100,
                List.of(segment, segment)), "alice", 400);
        postJson("/api/path-allocations", pathBody(key("pac"), key("pa"), windowId, "u1", 1,
                "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z", 100, List.of()), "alice", 400);
        // 非整分钟 / 起止倒置 -> 400
        postJson("/api/path-allocations", pathBody(key("pac"), key("pa"), windowId, "u1", 1,
                "2027-01-01T00:00:30Z", "2027-01-01T01:00:00Z", 100, List.of(segment)), "alice", 400);
        postJson("/api/path-allocations", pathBody(key("pac"), key("pa"), windowId, "u1", 1,
                "2027-01-01T01:00:00Z", "2027-01-01T00:00:00Z", 100, List.of(segment)), "alice", 400);
        // 流量 / 水头非正 -> 400
        postJson("/api/path-allocations", pathBody(key("pac"), key("pa"), windowId, "u1", 0,
                "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z", 100, List.of(segment)), "alice", 400);
        postJson("/api/path-allocations", pathBody(key("pac"), key("pa"), windowId, "u1", 1,
                "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z", 0, List.of(segment)), "alice", 400);
        // 渠段不存在 -> 404；窗口不存在 -> 404；缺操作人 -> 400
        postJson("/api/path-allocations", pathBody(key("pac"), key("pa"), windowId, "u1", 1,
                "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z", 100, List.of(key("missing"))),
                "alice", 404);
        postJson("/api/path-allocations", pathBody(key("pac"), key("pa"), 999999999L, "u1", 1,
                "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z", 100, List.of(segment)), "alice", 404);
        postJson("/api/path-allocations", pathBody(key("pac"), key("pa"), windowId, "u1", 1,
                "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z", 100, List.of(segment)), null, 400);
    }

    // ------------------------------------------------------------------
    // 批准水力重算
    // ------------------------------------------------------------------

    @Test
    void syntheticSingleSegmentHeadScenario() throws Exception {
        // 合成场景：单段 k=1、水头 100，两单同时段各流量 6；单独损失 36 可批，合计 144 拒绝第二单
        String segment = key("seg");
        createSegment(segment, 1, 200);
        long windowId = createWindow("100000");
        String a = submitPath(windowId, "u1", 6, "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z",
                100, List.of(segment), "alice");
        String b = submitPath(windowId, "u2", 6, "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z",
                100, List.of(segment), "bob");
        // 流量 × 分钟数 = 原申请水量：6 × 60 = 360
        assertEquals("360", allocation(windowId, a).get("amount").asText());

        approve(a, 200);
        approve(b, 422);
        // 第二单整次拒绝：仍为 REQUESTED，所有额度不变
        assertEquals("REQUESTED", allocation(windowId, b).get("status").asText());
        JsonNode cap = getOk("/api/windows/" + windowId + "/capacity");
        assertEquals("360", cap.get("approvedTotal").asText());
        // 冻结快照：唯一时片总流量 6
        JsonNode path = getOk("/api/allocations/" + a + "/path");
        assertTrue(path.get("frozen").asBoolean());
        assertEquals(1, path.get("frozenSlices").size());
        assertEquals(6, path.get("frozenSlices").get(0).get("totalFlow").asLong());
    }

    @Test
    void approvalFailingOldAllocationHeadReturns422() throws Exception {
        String segment = key("seg");
        createSegment(segment, 1, 200);
        long windowId = createWindow("100000");
        String a = submitPath(windowId, "u1", 6, "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z",
                100, List.of(segment), "alice");
        approve(a, 200);
        // 本单水头 1000 单独合法（36），但合计 144 使旧单（水头 100）越限 -> 422
        String b = submitPath(windowId, "u2", 6, "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z",
                1000, List.of(segment), "bob");
        approve(b, 422);
        assertEquals("REQUESTED", allocation(windowId, b).get("status").asText());
        assertEquals("360", getOk("/api/windows/" + windowId + "/capacity")
                .get("approvedTotal").asText());
    }

    @Test
    void nonOverlappingPeriodsDoNotInteract() throws Exception {
        String segment = key("seg");
        createSegment(segment, 1, 200);
        long windowId = createWindow("100000");
        String a = submitPath(windowId, "u1", 6, "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z",
                100, List.of(segment), "alice");
        String b = submitPath(windowId, "u2", 6, "2027-01-01T01:00:00Z", "2027-01-01T02:00:00Z",
                100, List.of(segment), "bob");
        approve(a, 200);
        approve(b, 200);
        // 无路径的旧类型申请不参与模型
        String plain = key("ak");
        Map<String, Object> plainBody = new HashMap<>();
        plainBody.put("commandKey", key("ac"));
        plainBody.put("allocationKey", plain);
        plainBody.put("windowId", windowId);
        plainBody.put("userId", "u3");
        plainBody.put("amount", "10");
        postOk("/api/allocations", plainBody, "carol");
        approve(plain, 200);
        mvc.perform(get("/api/allocations/" + plain + "/path")).andExpect(status().isNotFound());
    }

    @Test
    void cancelReleasesHydraulicCapacity() throws Exception {
        String segment = key("seg");
        createSegment(segment, 1, 200);
        long windowId = createWindow("100000");
        String a = submitPath(windowId, "u1", 6, "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z",
                100, List.of(segment), "alice");
        String b = submitPath(windowId, "u2", 6, "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z",
                100, List.of(segment), "bob");
        approve(a, 200);
        approve(b, 422);
        postOk("/api/allocations/" + a + "/cancel", Map.of("commandKey", key("cc")), "alice");
        approve(b, 200);
        assertEquals("360", getOk("/api/windows/" + windowId + "/capacity")
                .get("approvedTotal").asText());
    }

    @Test
    void resistanceUpdateBreakingExistingReturns409() throws Exception {
        String segment = key("seg");
        createSegment(segment, 1, 200);
        long windowId = createWindow("100000");
        String a = submitPath(windowId, "u1", 6, "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z",
                100, List.of(segment), "alice");
        approve(a, 200);
        // k 1 -> 3：损失 108 > 100，越限 -> 409，阻力与版本不变
        Map<String, Object> bad = new HashMap<>();
        bad.put("requestId", key("su"));
        bad.put("resistance", 3);
        bad.put("expectedVersion", 1);
        postJson("/api/segments/" + segment + "/resistance", bad, "operator", 409);
        JsonNode after = getOk("/api/segments/" + segment);
        assertEquals(1, after.get("resistance").asLong());
        assertEquals(1, after.get("version").asInt());
        // k 1 -> 2：损失 72 <= 100 -> 200，版本 2
        Map<String, Object> ok = new HashMap<>();
        ok.put("requestId", key("su"));
        ok.put("resistance", 2);
        ok.put("expectedVersion", 1);
        JsonNode updated = postOk("/api/segments/" + segment + "/resistance", ok, "operator");
        assertEquals(2, updated.get("resistance").asLong());
        assertEquals(2, updated.get("version").asInt());
        // 取消申请释放模型容量后，k -> 3 可改
        postOk("/api/allocations/" + a + "/cancel", Map.of("commandKey", key("cc")), "alice");
        Map<String, Object> ok2 = new HashMap<>();
        ok2.put("requestId", key("su"));
        ok2.put("resistance", 3);
        ok2.put("expectedVersion", 2);
        postOk("/api/segments/" + segment + "/resistance", ok2, "operator");
    }

    // ------------------------------------------------------------------
    // 转让继承路径
    // ------------------------------------------------------------------

    private String submitPlain(long windowId, String userId, String amount, String actor)
            throws Exception {
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

    private Map<String, Object> transferBody(String transferKey, String source, String target) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("tkc"));
        body.put("transferKey", transferKey);
        body.put("sourceAllocationKey", source);
        body.put("targetAllocationKey", target);
        return body;
    }

    @Test
    void transferInheritsPathAndRechecks() throws Exception {
        String segment = key("seg");
        createSegment(segment, 1, 200);
        long windowId = createWindow("100000");
        String source = submitPath(windowId, "u1", 6, "2027-01-01T00:00:00Z",
                "2027-01-01T01:00:00Z", 100, List.of(segment), "alice");
        approve(source, 200);
        // 目标为无路径旧类型申请，水量 360 = 6 × 60 与继承路径一致
        String target = submitPlain(windowId, "u2", "360", "bob");
        postOk("/api/transfers", transferBody(key("tk"), source, target), "alice");
        assertEquals("0", allocation(windowId, source).get("heldAmount").asText());
        assertEquals("APPROVED", allocation(windowId, target).get("status").asText());
        // 目标继承原路径并已冻结
        JsonNode path = getOk("/api/allocations/" + target + "/path");
        assertTrue(path.get("frozen").asBoolean());
        assertEquals(segment, path.get("segments").get(0).asText());
        // 继承路径参与模型：再批同段同时流量 6 -> 合计 144 > 100 -> 422
        String c = submitPath(windowId, "u3", 6, "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z",
                100, List.of(segment), "carol");
        approve(c, 422);
    }

    @Test
    void transferFlowVolumeMismatchReturns422() throws Exception {
        String segment = key("seg");
        createSegment(segment, 1, 200);
        long windowId = createWindow("100000");
        String source = submitPath(windowId, "u1", 6, "2027-01-01T00:00:00Z",
                "2027-01-01T01:00:00Z", 100, List.of(segment), "alice");
        approve(source, 200);
        // 目标水量 300 ≠ 6 × 60 = 360，继承路径流量与水量不一致 -> 422，额度不变
        String target = submitPlain(windowId, "u2", "300", "bob");
        postJson("/api/transfers", transferBody(key("tk"), source, target), "alice", 422);
        assertEquals("360", allocation(windowId, source).get("heldAmount").asText());
        assertEquals("REQUESTED", allocation(windowId, target).get("status").asText());
    }

    @Test
    void transferSharedSegmentsAreCounted() throws Exception {
        String segment = key("seg");
        createSegment(segment, 1, 200);
        long windowId = createWindow("100000");
        String a = submitPath(windowId, "u1", 6, "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z",
                200, List.of(segment), "alice");
        String c = submitPath(windowId, "u2", 6, "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z",
                200, List.of(segment), "bob");
        approve(a, 200);
        approve(c, 200);
        // 转让 a -> t：a 持有归零退出模型，t 继承路径进入；最终集合 {c, t} 共用该段
        String t = submitPlain(windowId, "u3", "360", "carol");
        postOk("/api/transfers", transferBody(key("tk"), a, t), "alice");
        // k 1 -> 2：c 与 t 共用段合计损失 2 × 12² = 288 > 200 -> 409；漏算任一方则为 72 可通过
        Map<String, Object> update = new HashMap<>();
        update.put("requestId", key("su"));
        update.put("resistance", 2);
        update.put("expectedVersion", 1);
        postJson("/api/segments/" + segment + "/resistance", update, "operator", 409);
        assertEquals(1, getOk("/api/segments/" + segment).get("resistance").asLong());
    }

    // ------------------------------------------------------------------
    // 幂等与冻结快照
    // ------------------------------------------------------------------

    @Test
    void idempotentReplayAndConflict() throws Exception {
        String segment = key("seg");
        createSegment(segment, 1, 200);
        long windowId = createWindow("100000");
        String requestId = key("pac");
        String allocationKey = key("pa");
        Map<String, Object> body = pathBody(requestId, allocationKey, windowId, "u1", 6,
                "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z", 100, List.of(segment));
        JsonNode first = postOk("/api/path-allocations", body, "alice");
        JsonNode replay = postOk("/api/path-allocations", body, "alice");
        assertEquals(first, replay);
        // 同键改参 -> 409（流量、操作人均属结构化身份）
        postJson("/api/path-allocations", pathBody(requestId, allocationKey, windowId, "u1", 7,
                "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z", 100, List.of(segment)), "alice", 409);
        postJson("/api/path-allocations", body, "bob", 409);
        // 只创建一条申请
        assertEquals(1, getOk("/api/windows/" + windowId + "/history").get("allocations").size());
        // 渠段创建幂等重放
        String segRequest = key("sc");
        Map<String, Object> segBody = new HashMap<>();
        segBody.put("requestId", segRequest);
        segBody.put("segmentKey", key("seg"));
        segBody.put("resistance", 4);
        JsonNode seg1 = postOk("/api/segments", segBody, "operator");
        JsonNode seg2 = postOk("/api/segments", segBody, "operator");
        assertEquals(seg1, seg2);
        segBody.put("resistance", 5);
        postJson("/api/segments", segBody, "operator", 409);
    }

    @Test
    void failedCommandDoesNotOccupyRequestId() throws Exception {
        String segment = key("seg");
        createSegment(segment, 1, 200);
        long windowId = createWindow("100000");
        String requestId = key("pac");
        // 首次失败（渠段不存在 404）不占键
        postJson("/api/path-allocations", pathBody(requestId, key("pa"), windowId, "u1", 6,
                "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z", 100, List.of(key("missing"))),
                "alice", 404);
        JsonNode ok = postOk("/api/path-allocations", pathBody(requestId, key("pa"), windowId, "u1",
                6, "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z", 100, List.of(segment)), "alice");
        assertEquals("REQUESTED", ok.get("status").asText());
    }

    @Test
    void freezeSnapshotRecordsSlicesAndVersions() throws Exception {
        String segment = key("seg");
        createSegment(segment, 1, 200);
        long windowId = createWindow("100000");
        String a = submitPath(windowId, "u1", 6, "2027-01-01T00:00:00Z", "2027-01-01T01:00:00Z",
                200, List.of(segment), "alice");
        approve(a, 200);
        String b = submitPath(windowId, "u2", 6, "2027-01-01T00:30:00Z", "2027-01-01T01:30:00Z",
                200, List.of(segment), "bob");
        approve(b, 200);
        // a 的冻结在其批准时刻生成（b 尚未批准）：单时片 [00:00,01:00)=6；段参数版本 1
        JsonNode pathA = getOk("/api/allocations/" + a + "/path");
        assertTrue(pathA.get("frozen").asBoolean());
        assertEquals(1, pathA.get("frozenSegments").get(0).get("version").asInt());
        assertEquals(1, pathA.get("frozenSegments").get(0).get("resistance").asLong());
        assertEquals(1, pathA.get("frozenSlices").size());
        assertEquals(6, pathA.get("frozenSlices").get(0).get("totalFlow").asLong());
        // b 的冻结按已批曲线起止切片：[00:30,01:00)=12、[01:00,01:30)=6
        JsonNode pathB = getOk("/api/allocations/" + b + "/path");
        assertEquals(2, pathB.get("frozenSlices").size());
        assertEquals(12, pathB.get("frozenSlices").get(0).get("totalFlow").asLong());
        assertEquals("2027-01-01T00:30:00Z",
                pathB.get("frozenSlices").get(0).get("startUtc").asText());
        assertEquals(6, pathB.get("frozenSlices").get(1).get("totalFlow").asLong());
    }
}
