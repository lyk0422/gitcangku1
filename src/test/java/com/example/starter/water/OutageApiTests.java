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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 输水渠停运窗口与配额核销联动 API 测试。
 *
 * <p>覆盖：UTC 左闭右开窗口边界（重叠/相邻/恢复截断）、expectedVersion 渠道版本裁决、
 * 单笔/批量核销账目与最终渠道容量、批量预校验全量回滚、停运后态拦截核销与转让、
 * 不可变供应风险门禁、停运/恢复/删除生命周期、幂等重放与失败不占键、并发提交顺序裁决。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务与行锁，不 mock 数据库边界。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutageApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CanalService canalService;

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

    private JsonNode getJson(String url, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Object> windowBody(String channelId, String start, String end, String planned) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", channelId);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("plannedVolume", planned);
        return body;
    }

    private long createWindow(String channelId, String start, String end, String planned) throws Exception {
        return postOk("/api/windows", windowBody(channelId, start, end, planned), null)
                .get("id").asLong();
    }

    private String submit(long windowId, String userId, String amount, String actor) throws Exception {
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

    private void approve(String allocationKey) throws Exception {
        postOk("/api/allocations/" + allocationKey + "/approve", Map.of("commandKey", key("ap")), null);
    }

    private Map<String, Object> outageBody(String outageKey, String start, String end,
                                           List<String> affected, int expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("oc"));
        body.put("outageKey", outageKey);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("affectedAllocationKeys", affected);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private JsonNode createOutage(String channelId, String outageKey, String start, String end,
                                  List<String> affected, int expectedVersion) throws Exception {
        return postOk("/api/channels/" + channelId + "/outages",
                outageBody(outageKey, start, end, affected, expectedVersion), null);
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

    private Map<String, Object> settleBody(String settlementKey, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("sc"));
        body.put("settlementKey", settlementKey);
        body.put("amount", amount);
        return body;
    }

    private JsonNode settleOk(String allocationKey, String settlementKey, String amount, String actor)
            throws Exception {
        return postOk("/api/allocations/" + allocationKey + "/settle",
                settleBody(settlementKey, amount), actor);
    }

    private JsonNode settleStatus(String allocationKey, String settlementKey, String amount, String actor,
                                  int expectedStatus) throws Exception {
        String raw = postJson("/api/allocations/" + allocationKey + "/settle",
                settleBody(settlementKey, amount), actor, expectedStatus).getResponse().getContentAsString();
        return objectMapper.readTree(raw);
    }

    private int settleStatusDirect(String allocationKey, String amount, String actor) {
        try {
            canalService.settle(key("sc"), key("sk"), allocationKey, amount, actor);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private JsonNode changeCapacity(String channelId, String capacity, int expectedVersion) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("cc"));
        body.put("capacity", capacity);
        body.put("expectedVersion", expectedVersion);
        return postOk("/api/channels/" + channelId + "/change", body, null);
    }

    private Map<String, Object> batchBody(String commandKey, String a1, String v1, String a2, String v2) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("items", List.of(Map.of("allocationKey", a1, "amount", v1),
                Map.of("allocationKey", a2, "amount", v2)));
        return body;
    }

    private JsonNode batchOk(Map<String, Object> body) throws Exception {
        return postOk("/api/settlements/batch", body, "dispatcher");
    }

    private JsonNode batchStatus(Map<String, Object> body, int expectedStatus) throws Exception {
        return objectMapper.readTree(postJson("/api/settlements/batch", body, "dispatcher",
                expectedStatus).getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------
    // UTC 窗口边界与渠道版本
    // ------------------------------------------------------------------

    @Test
    void outageOverlapRejectedButAdjacentAllowedAndVersionBumps() throws Exception {
        String channel = "ch-out-" + run;
        createWindow(channel, "2026-10-10T00:00:00Z", "2026-10-11T00:00:00Z", "20");
        // 窗口创建自动登记渠道，版本 1
        assertEquals(1, getOk("/api/channels/" + channel).get("version").asInt());

        JsonNode first = createOutage(channel, key("ok"), "2026-10-10T06:00:00Z",
                "2026-10-10T18:00:00Z", List.of(), 1);
        assertEquals("SCHEDULED", first.get("status").asText());
        assertEquals(2, getOk("/api/channels/" + channel).get("version").asInt());

        // 重叠（当前时刻之前重叠、之后重叠、包含）-> 409 OUTAGE_OVERLAP
        postJson("/api/channels/" + channel + "/outages",
                outageBody(key("ok"), "2026-10-10T12:00:00Z", "2026-10-10T20:00:00Z", List.of(), 2),
                null, 409);
        postJson("/api/channels/" + channel + "/outages",
                outageBody(key("ok"), "2026-10-10T00:00:00Z", "2026-10-10T06:00:01Z", List.of(), 2),
                null, 409);
        postJson("/api/channels/" + channel + "/outages",
                outageBody(key("ok"), "2026-10-10T05:00:00Z", "2026-10-10T19:00:00Z", List.of(), 2),
                null, 409);
        // 过期版本 -> 409 CHANNEL_VERSION_MISMATCH
        postJson("/api/channels/" + channel + "/outages",
                outageBody(key("ok"), "2026-10-10T18:00:00Z", "2026-10-10T20:00:00Z", List.of(), 1),
                null, 409);
        // 端点相接合法，版本再 +1
        JsonNode adjacent = createOutage(channel, key("ok"), "2026-10-10T18:00:00Z",
                "2026-10-10T20:00:00Z", List.of(), 2);
        assertEquals("SCHEDULED", adjacent.get("status").asText());
        assertEquals(3, getOk("/api/channels/" + channel).get("version").asInt());

        // 起止倒置/非法时刻/缺版本 -> 400
        postJson("/api/channels/" + channel + "/outages",
                outageBody(key("ok"), "2026-10-10T20:00:00Z", "2026-10-10T18:00:00Z", List.of(), 3),
                null, 400);
        Map<String, Object> bad = outageBody(key("ok"), "2026-10-10T20:00:00Z",
                "2026-10-10T22:00:00Z", List.of(), 3);
        bad.remove("expectedVersion");
        postJson("/api/channels/" + channel + "/outages", bad, null, 400);
    }

    @Test
    void affectedAllocationsAreNormalizedIntoFingerprint() throws Exception {
        String channel = "ch-fp-" + run;
        long windowId = createWindow(channel, "2026-10-12T00:00:00Z", "2026-10-12T12:00:00Z", "20");
        String a = submit(windowId, "u1", "1", "alice");
        String b = submit(windowId, "u2", "1", "bob");
        String outageKey = key("ok");
        String commandKey = key("oc");
        // 乱序 + 重复，响应中规范化为字典序去重
        JsonNode first = postOk("/api/channels/" + channel + "/outages",
                outageBodyWithCommand(commandKey, outageKey, "2026-10-12T01:00:00Z",
                        "2026-10-12T02:00:00Z", List.of(b, a, a), 1), null);
        assertEquals(List.of(a, b), toStringList(first.get("affectedAllocationKeys")));
        // 同 commandKey 不同书写顺序（规范化后同指纹）-> 重放首次结果
        JsonNode replay = postOk("/api/channels/" + channel + "/outages",
                outageBodyWithCommand(commandKey, outageKey, "2026-10-12T01:00:00Z",
                        "2026-10-12T02:00:00Z", List.of(a, b), 1), null);
        assertEquals(first, replay);
        // 同 commandKey 改时段 -> 409
        postJson("/api/channels/" + channel + "/outages",
                outageBodyWithCommand(commandKey, outageKey, "2026-10-12T03:00:00Z",
                        "2026-10-12T04:00:00Z", List.of(a, b), 1), null, 409);
        // 受影响申请不存在 -> 404，失败不占键
        String otherCommand = key("oc");
        postJson("/api/channels/" + channel + "/outages",
                outageBodyWithCommand(otherCommand, key("ok"), "2026-10-12T05:00:00Z",
                        "2026-10-12T06:00:00Z", List.of("missing-" + run), 2), null, 404);
        // 同键随后可成功执行另一合法停运
        createOutage(channel, key("ok"), "2026-10-12T05:00:00Z", "2026-10-12T06:00:00Z",
                List.of(a), 2);
    }

    private Map<String, Object> outageBodyWithCommand(String commandKey, String outageKey, String start,
                                                      String end, List<String> affected, int version) {
        Map<String, Object> body = outageBody(outageKey, start, end, affected, version);
        body.put("commandKey", commandKey);
        return body;
    }

    // ------------------------------------------------------------------
    // 停运删除与提前恢复生命周期
    // ------------------------------------------------------------------

    @Test
    void futureOutageCanBeDeletedAndReleasesAllocations() throws Exception {
        String channel = "ch-del-" + run;
        long windowId = createWindow(channel, "2026-10-20T00:00:00Z", "2026-10-21T00:00:00Z", "20");
        String a = submit(windowId, "u1", "4", "alice");
        approve(a);
        JsonNode outage = createOutage(channel, key("ok"), "2026-10-20T06:00:00Z",
                "2026-10-20T18:00:00Z", List.of(a), 1);
        // 删除前核销被拦截
        settleStatus(a, key("sk"), "1", "alice", 422);

        Map<String, Object> deleteBody = new HashMap<>();
        deleteBody.put("commandKey", key("dl"));
        deleteBody.put("expectedVersion", 2);
        JsonNode deleted = postOk("/api/outages/" + outage.get("outageKey").asText() + "/delete",
                deleteBody, null);
        assertEquals("DELETED", deleted.get("status").asText());
        // 重复删除 -> 409
        deleteBody.put("commandKey", key("dl"));
        postJson("/api/outages/" + outage.get("outageKey").asText() + "/delete",
                deleteBody, null, 409);
        // 删除后受影响集合释放，核销成功
        settleOk(a, key("sk"), "1", "alice");
        // 删除后同窗口时段可再次下达（重叠判定排除已删除）
        JsonNode second = createOutage(channel, key("ok"), "2026-10-20T06:00:00Z",
                "2026-10-20T18:00:00Z", List.of(), 3);
        assertEquals("SCHEDULED", second.get("status").asText());
        // 停运不存在 -> 404；渠道不存在 -> 404
        postJson("/api/outages/missing-" + run + "/delete", deleteBody, null, 404);
        mvcPerformNotFound("/api/channels/missing-" + run + "/outages");
    }

    @Test
    void startedOutageCannotBeDeletedButRecoveryTruncatesEffectiveWindow() throws Exception {
        String channel = "ch-rec-" + run;
        // 当前日期 2026-09-26：长停运 09-20 ~ 12-31 正在进行中
        long octWindow = createWindow(channel, "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", "20");
        long novWindow = createWindow(channel, "2026-11-01T00:00:00Z", "2026-11-02T00:00:00Z", "20");
        String oct = submit(octWindow, "u-oct", "2", "alice");
        String nov = submit(novWindow, "u-nov", "2", "bob");
        approve(oct);
        approve(nov);
        String outageKey = key("ok");
        JsonNode outage = createOutage(channel, outageKey, "2026-09-20T00:00:00Z",
                "2026-12-31T00:00:00Z", List.of(oct, nov), 1);
        assertEquals("ACTIVE", outage.get("status").asText());

        // 已开始不可删除 -> 409
        Map<String, Object> deleteBody = new HashMap<>();
        deleteBody.put("commandKey", key("dl"));
        deleteBody.put("expectedVersion", 2);
        postJson("/api/outages/" + outageKey + "/delete", deleteBody, null, 409);
        // 恢复时刻早于当前时刻 -> 400；晚于停运结束 -> 400
        recoverStatus(outageKey, 2, "2026-09-01T00:00:00Z", 400);
        recoverStatus(outageKey, 2, "2027-01-01T00:00:00Z", 400);
        // 提前恢复到 10-15（未来但早于结束）-> 成功，渠道版本 +1
        JsonNode recovered = recoverOk(outageKey, 2, "2026-10-15T00:00:00Z");
        assertEquals("RECOVERED", recovered.get("status").asText());
        assertEquals("2026-10-15T00:00:00Z", recovered.get("recoveredUtc").asText());
        // 重复恢复 -> 409
        recoverStatus(outageKey, 3, "2026-10-16T00:00:00Z", 409);
        // 恢复仅影响之后的核销：生效区间截断为 [09-20, 10-15)
        // 10 月窗口仍与截断区间相交 -> 422
        settleStatus(oct, key("sk"), "1", "alice", 422);
        // 11 月窗口在恢复时刻之后 -> 放行，核销成功
        settleOk(nov, key("sk"), "1", "bob");
    }

    @Test
    void recoveryGuardsForNotStartedAndEndedOutages() throws Exception {
        String channel = "ch-recg-" + run;
        createWindow(channel, "2026-10-20T00:00:00Z", "2026-10-21T00:00:00Z", "20");
        JsonNode future = createOutage(channel, key("ok"), "2026-10-20T06:00:00Z",
                "2026-10-20T18:00:00Z", List.of(), 1);
        // 未开始只能删除 -> 409 OUTAGE_NOT_STARTED
        recoverStatus(future.get("outageKey").asText(), 2, "2026-10-20T12:00:00Z", 409);
        // 已结束窗口 -> 409 OUTAGE_ALREADY_ENDED
        JsonNode ended = createOutage(channel, key("ok"), "2026-09-01T00:00:00Z",
                "2026-09-02T00:00:00Z", List.of(), 2);
        recoverStatus(ended.get("outageKey").asText(), 3, "2026-09-26T00:00:00Z", 409);
        // 停运不存在 -> 404
        recoverStatus("missing-" + run, 3, "2026-10-01T00:00:00Z", 404);
    }

    private JsonNode recoverOk(String outageKey, int expectedVersion, String recoveredAt) throws Exception {
        return postOk("/api/outages/" + outageKey + "/recover",
                recoverBody(expectedVersion, recoveredAt), null);
    }

    private JsonNode recoverStatus(String outageKey, int expectedVersion, String recoveredAt,
                                   int expectedStatus) throws Exception {
        String raw = postJson("/api/outages/" + outageKey + "/recover",
                recoverBody(expectedVersion, recoveredAt), null, expectedStatus)
                .getResponse().getContentAsString();
        return objectMapper.readTree(raw);
    }

    private Map<String, Object> recoverBody(int expectedVersion, String recoveredAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("rc"));
        body.put("expectedVersion", expectedVersion);
        body.put("recoveredAtUtc", recoveredAt);
        return body;
    }

    // ------------------------------------------------------------------
    // 单笔核销账目
    // ------------------------------------------------------------------

    @Test
    void settlementDebitsHeldAmountAndWritesImmutableLedger() throws Exception {
        String channel = "ch-set-" + run;
        long windowId = createWindow(channel, "2026-11-10T00:00:00Z", "2026-11-10T12:00:00Z", "20");
        String a = submit(windowId, "u1", "3.500", "alice");
        approve(a);
        JsonNode s1 = settleOk(a, key("sk"), "1.500", "alice");
        assertEquals("1.5", s1.get("amount").asText());
        assertEquals("alice", s1.get("actor").asText());
        assertTrue(s1.get("batchKey").isNull());
        // 持有额度扣减为 2.000，仍 APPROVED
        assertEquals("2", allocation(windowId, a).get("heldAmount").asText());
        // 再核销 2 -> 持有 0 仍 APPROVED；继续核销 -> 422 INSUFFICIENT_BALANCE，账目不变
        settleOk(a, key("sk"), "2", "alice");
        JsonNode err = settleStatus(a, key("sk"), "0.001", "alice", 422);
        assertEquals("INSUFFICIENT_BALANCE", err.get("code").asText());
        assertEquals("0", allocation(windowId, a).get("heldAmount").asText());
        // 流水不可变：两条
        JsonNode list = getOk("/api/allocations/" + a + "/settlements");
        assertEquals(2, list.get("settlements").size());
        // 非申请人 -> 409；非 APPROVED 状态 -> 409
        settleStatus(a, key("sk"), "1", "bob", 409);
        String pending = submit(windowId, "u2", "1", "bob");
        settleStatus(pending, key("sk"), "1", "bob", 409);
        // settlementKey 复用（换 commandKey）-> 409，扣减回滚无半成品
        long window2 = createWindow("ch-set2-" + run, "2026-11-11T00:00:00Z",
                "2026-11-11T12:00:00Z", "20");
        String c = submit(window2, "u3", "2", "carol");
        approve(c);
        String reusedKey = key("sk");
        settleOk(c, reusedKey, "1", "carol");
        settleStatus(c, reusedKey, "1", "carol", 409);
        assertEquals("1", allocation(window2, c).get("heldAmount").asText());
    }

    // ------------------------------------------------------------------
    // 停运后态拦截核销与转让
    // ------------------------------------------------------------------

    @Test
    void intersectingOutageBlocksSettlementAndTransferWithDistinctReasons() throws Exception {
        String channel = "ch-block-" + run;
        long blockedWindow = createWindow(channel, "2026-10-10T00:00:00Z",
                "2026-10-10T12:00:00Z", "30");
        long adjacentWindow = createWindow(channel, "2026-10-10T18:00:00Z",
                "2026-10-10T23:00:00Z", "30");
        long otherDayWindow = createWindow(channel, "2026-10-11T00:00:00Z",
                "2026-10-11T12:00:00Z", "30");
        String blocked = submit(blockedWindow, "u-blocked", "4", "alice");
        String notListed = submit(blockedWindow, "u-unlisted", "4", "bob");
        String adjacent = submit(adjacentWindow, "u-adj", "2", "carol");
        String otherDay = submit(otherDayWindow, "u-other", "2", "dave");
        // 停运先于批准下达：这些批准不产生供应风险（风险只快照下达时已批准未结算申请），
        // 从而精确验证相交停运门禁（422）而非风险门禁（409）
        createOutage(channel, key("ok"), "2026-10-10T06:00:00Z", "2026-10-10T18:00:00Z",
                List.of(blocked, otherDay), 1);
        for (String k : List.of(blocked, notListed, adjacent, otherDay)) {
            approve(k);
        }

        // 受影响且时段相交 -> 422 OUTAGE_WINDOW_CONFLICT，持有额度与流水不变
        JsonNode err = settleStatus(blocked, key("sk"), "1", "alice", 422);
        assertEquals("OUTAGE_WINDOW_CONFLICT", err.get("code").asText());
        assertEquals("4", allocation(blockedWindow, blocked).get("heldAmount").asText());
        // 同窗口但不在受影响集合 -> 核销成功
        settleOk(notListed, key("sk"), "1", "bob");
        // 供水窗口与停运端点相接（18:00）-> 不相交，核销成功
        settleOk(adjacent, key("sk"), "1", "carol");
        // 申请在受影响集合但其窗口在停运次日 -> 不相交，核销成功
        settleOk(otherDay, key("sk"), "1", "dave");

        // 转让结算同样被相交停运拦截：blocked 转出 -> 422
        String target = submit(blockedWindow, "u-target", "2", "zoe");
        Map<String, Object> transfer = new HashMap<>();
        transfer.put("commandKey", key("tkc"));
        transfer.put("transferKey", key("tk"));
        transfer.put("sourceAllocationKey", blocked);
        transfer.put("targetAllocationKey", target);
        postJson("/api/transfers", transfer, "alice", 422);
        // 转让未落任何半成品
        assertEquals("4", allocation(blockedWindow, blocked).get("heldAmount").asText());
        assertEquals("REQUESTED", allocation(blockedWindow, target).get("status").asText());
    }

    // ------------------------------------------------------------------
    // 不可变供应风险门禁
    // ------------------------------------------------------------------

    @Test
    void supplyRiskIsImmutableBlocksFurtherTransferButNotSettlement() throws Exception {
        String channel = "ch-risk-" + run;
        long windowId = createWindow(channel, "2026-11-20T00:00:00Z", "2026-11-20T12:00:00Z", "30");
        String source = submit(windowId, "u-src", "6", "alice");
        String firstTarget = submit(windowId, "u-t1", "2", "bob");
        approve(source);
        // 先完成一笔已批准未结算转让
        Map<String, Object> transfer1 = new HashMap<>();
        transfer1.put("commandKey", key("tkc"));
        transfer1.put("transferKey", key("tk"));
        transfer1.put("sourceAllocationKey", source);
        transfer1.put("targetAllocationKey", firstTarget);
        postOk("/api/transfers", transfer1, "alice");
        assertEquals("4", allocation(windowId, source).get("heldAmount").asText());

        // 下达进行中停运（受影响集合为空，风险仍覆盖渠道内全部已批准未结算申请）
        String outageKey = key("ok");
        createOutage(channel, outageKey, "2026-09-20T00:00:00Z", "2026-12-31T00:00:00Z",
                List.of(), 1);

        // 风险查询：源与首个转让目标（均 APPROVED 且 held>0）各一条不可变风险
        JsonNode sourceRisks = getOk("/api/allocations/" + source + "/risks");
        assertEquals(1, sourceRisks.get("risks").size());
        assertEquals(outageKey, sourceRisks.get("risks").get(0).get("outageKey").asText());
        assertEquals(1, getOk("/api/allocations/" + firstTarget + "/risks").get("risks").size());
        // 风险申请不存在 -> 404
        mvcPerformNotFound("/api/allocations/missing-" + run + "/risks");

        // 已存在转让不撤销：流水仍在
        JsonNode transfers = getOk("/api/windows/" + windowId + "/transfers");
        assertEquals(1, transfers.get("transfers").size());

        // 风险申请不能作为转出方再次转让 -> 409 SUPPLY_RISK_BLOCKED
        String secondTarget = submit(windowId, "u-t2", "2", "carol");
        Map<String, Object> transfer2 = new HashMap<>();
        transfer2.put("commandKey", key("tkc"));
        transfer2.put("transferKey", key("tk"));
        transfer2.put("sourceAllocationKey", source);
        transfer2.put("targetAllocationKey", secondTarget);
        JsonNode err = objectMapper.readTree(postJson("/api/transfers", transfer2, "alice", 409)
                .getResponse().getContentAsString());
        assertEquals("SUPPLY_RISK_BLOCKED", err.get("code").asText());
        // 额度与目标状态无变化
        assertEquals("4", allocation(windowId, source).get("heldAmount").asText());
        assertEquals("REQUESTED", allocation(windowId, secondTarget).get("status").asText());

        // 风险不阻止核销（该申请不在任何停运受影响集合内），核销后风险记录仍保留
        settleOk(source, key("sk"), "1", "alice");
        assertEquals("3", allocation(windowId, source).get("heldAmount").asText());
        assertEquals(1, getOk("/api/allocations/" + source + "/risks").get("risks").size());
    }

    // ------------------------------------------------------------------
    // 最终渠道容量与批量核销回滚
    // ------------------------------------------------------------------

    @Test
    void batchSettlementValidatesFinalCapacityAndRollsBackEverything() throws Exception {
        String channel = "ch-cap-" + run;
        long w1 = createWindow(channel, "2026-11-21T00:00:00Z", "2026-11-21T06:00:00Z", "40");
        long w2 = createWindow(channel, "2026-11-21T06:00:00Z", "2026-11-21T12:00:00Z", "40");
        String a = submit(w1, "u-a", "6", "alice");
        String b = submit(w2, "u-b", "6", "bob");
        approve(a);
        approve(b);

        // 渠道容量 10（窗口登记后版本 1）
        JsonNode channelResp = changeCapacity(channel, "10", 1);
        assertEquals("10", channelResp.get("capacity").asText());
        assertEquals(2, channelResp.get("version").asInt());
        // 版本不符 -> 409
        postJson("/api/channels/" + channel + "/change",
                Map.of("commandKey", key("cc"), "capacity", "9", "expectedVersion", 1), null, 409);
        // 渠道不存在 -> 404
        postJson("/api/channels/missing-" + run + "/change",
                Map.of("commandKey", key("cc"), "capacity", "9", "expectedVersion", 1), null, 404);

        // 批量 [a 2, b 2] 成功：持有各扣 2，两条不可变流水，渠道累计已核销 4
        String batchKey1 = key("bc");
        JsonNode ok = batchOk(batchBody(batchKey1, a, "2", b, "2"));
        assertEquals(2, ok.get("settlements").size());
        assertEquals(batchKey1, ok.get("settlements").get(0).get("batchKey").asText());
        assertEquals("4", allocation(w1, a).get("heldAmount").asText());
        assertEquals("4", allocation(w2, b).get("heldAmount").asText());

        // 容量收紧到 5（当前版本 2）-> 版本 3
        changeCapacity(channel, "5", 2);
        // 批量 [a 1, b 1] 将使累计 6 > 5 -> 422 CHANNEL_CAPACITY_EXCEEDED，全部回滚
        JsonNode err = batchStatus(batchBody(key("bc"), a, "1", b, "1"), 422);
        assertEquals("CHANNEL_CAPACITY_EXCEEDED", err.get("code").asText());
        assertEquals("4", allocation(w1, a).get("heldAmount").asText());
        assertEquals("4", allocation(w2, b).get("heldAmount").asText());
        // 失败批量不产生任何流水（a 仍只有第一批那一条）
        assertEquals(1, getOk("/api/allocations/" + a + "/settlements").get("settlements").size());

        // 容量放到 6 -> 批量成功（恰好达到容量上限）
        changeCapacity(channel, "6", 3);
        batchOk(batchBody(key("bc"), a, "1", b, "1"));
        assertEquals("3", allocation(w1, a).get("heldAmount").asText());
        // 单笔再核销 1 -> 7 > 6 -> 422
        settleStatus(a, key("sk"), "1", "alice", 422);
        // 容量改回不限（null）-> 核销放行
        changeCapacity(channel, null, 4);
        settleOk(a, key("sk"), "1", "alice");

        // 非法批量：不同渠道 400、重复申请 400、空列表 400、申请不存在 404
        long other = createWindow("ch-cap-other-" + run, "2026-11-22T00:00:00Z",
                "2026-11-22T06:00:00Z", "40");
        String c = submit(other, "u-c", "1", "carol");
        approve(c);
        batchStatus(batchBody(key("bc"), a, "1", c, "1"), 400);
        Map<String, Object> dup = new HashMap<>();
        dup.put("commandKey", key("bc"));
        dup.put("items", List.of(Map.of("allocationKey", a, "amount", "1"),
                Map.of("allocationKey", a, "amount", "1")));
        batchStatus(dup, 400);
        batchStatus(Map.of("commandKey", key("bc"), "items", List.of()), 400);
        batchStatus(batchBody(key("bc"), "missing-" + run, "1", b, "1"), 404);
    }

    @Test
    void batchSettlementRollsBackOnOutageConflictAndInsufficientBalance() throws Exception {
        String channel = "ch-brb-" + run;
        long w1 = createWindow(channel, "2026-10-10T00:00:00Z", "2026-10-10T12:00:00Z", "40");
        long w2 = createWindow(channel, "2026-10-10T12:00:00Z", "2026-10-11T00:00:00Z", "40");
        String blocked = submit(w1, "u-blocked", "3", "alice");
        String fine = submit(w2, "u-fine", "3", "bob");
        String poor = submit(w2, "u-poor", "1", "carol");
        approve(blocked);
        approve(fine);
        approve(poor);
        createOutage(channel, key("ok"), "2026-10-10T06:00:00Z", "2026-10-10T18:00:00Z",
                List.of(blocked), 1);

        // 停运冲突：含一笔正常申请也必须整体回滚
        JsonNode err1 = batchStatus(batchBody(key("bc"), blocked, "1", fine, "1"), 422);
        assertEquals("OUTAGE_WINDOW_CONFLICT", err1.get("code").asText());
        assertEquals("3", allocation(w1, blocked).get("heldAmount").asText());
        assertEquals("3", allocation(w2, fine).get("heldAmount").asText());
        assertEquals(0, getOk("/api/allocations/" + fine + "/settlements").get("settlements").size());

        // 余额不足：poor 仅持有 1，核销 2 -> 422，同批 fine 也回滚
        JsonNode err2 = batchStatus(batchBody(key("bc"), poor, "2", fine, "1"), 422);
        assertEquals("INSUFFICIENT_BALANCE", err2.get("code").asText());
        assertEquals("1", allocation(w2, poor).get("heldAmount").asText());
        assertEquals("3", allocation(w2, fine).get("heldAmount").asText());

        // 失败不占 commandKey：同键随后执行合法批量成功
        String reused = key("bc");
        batchStatus(batchBody(reused, fine, "1", poor, "1"), 200);
        assertEquals("2", allocation(w2, fine).get("heldAmount").asText());
        assertEquals("0", allocation(w2, poor).get("heldAmount").asText());
    }

    // ------------------------------------------------------------------
    // 拒绝原因查询与停运影响查询
    // ------------------------------------------------------------------

    @Test
    void rejectionPreviewAndImpactQueryReportDistinctReasons() throws Exception {
        String channel = "ch-q-" + run;
        long w = createWindow(channel, "2026-10-10T00:00:00Z", "2026-10-10T12:00:00Z", "10");
        String a = submit(w, "u-a", "3", "alice");
        String b = submit(w, "u-b", "3", "bob");
        approve(a);
        approve(b);
        changeCapacity(channel, "10", 1);
        String outageKey = key("ok");
        createOutage(channel, outageKey, "2026-10-10T06:00:00Z", "2026-10-10T18:00:00Z",
                List.of(a), 2);

        // 受停运影响的申请 -> 可区分拒绝原因
        JsonNode rejected = getOk("/api/allocations/" + a
                + "/settlement-rejection?amount=1");
        assertFalse(rejected.get("allowed").asBoolean());
        assertEquals("OUTAGE_WINDOW_CONFLICT", rejected.get("code").asText());
        // 未受影响申请：容量充足 -> 允许
        JsonNode allowed = getOk("/api/allocations/" + b + "/settlement-rejection?amount=1");
        assertTrue(allowed.get("allowed").asBoolean());
        assertTrue(allowed.get("code").isNull());
        // 超额：容量收紧后累计口径拒绝
        settleOk(b, key("sk"), "3", "bob");
        changeCapacity(channel, "2", 3);
        JsonNode capErr = getOk("/api/allocations/" + b + "/settlement-rejection?amount=0.001");
        // b 已核销持有 0：先命中余额不足
        assertFalse(capErr.get("allowed").asBoolean());
        assertEquals("INSUFFICIENT_BALANCE", capErr.get("code").asText());
        // 预览不落任何状态
        assertEquals("0", allocation(w, b).get("heldAmount").asText());

        // 停运影响查询
        JsonNode impact = getOk("/api/outages/" + outageKey + "/impact");
        assertEquals(channel, impact.get("channelId").asText());
        assertEquals(1, impact.get("allocations").size());
        JsonNode impacted = impact.get("allocations").get(0);
        assertEquals(a, impacted.get("allocationKey").asText());
        assertTrue(impacted.get("intersects").asBoolean());
        assertEquals("OUTAGE_NOT_FOUND",
                getJson("/api/outages/missing-" + run + "/impact", 404).get("code").asText());
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameCommandKeyReplaysOutageAndSettlement() throws Exception {
        String channel = "ch-idem-" + run;
        long w = createWindow(channel, "2026-11-25T00:00:00Z", "2026-11-25T12:00:00Z", "20");
        String a = submit(w, "u-a", "4", "alice");
        approve(a);
        // 停运命令重放
        String outageKey = key("ok");
        String outageCommand = key("oc");
        Map<String, Object> outage = outageBodyWithCommand(outageCommand, outageKey,
                "2026-11-25T01:00:00Z", "2026-11-25T02:00:00Z", List.of(a), 1);
        JsonNode first = postOk("/api/channels/" + channel + "/outages", outage, null);
        JsonNode replay = postOk("/api/channels/" + channel + "/outages", outage, null);
        assertEquals(first, replay);
        assertEquals(1, getOk("/api/channels/" + channel + "/outages").get("outages").size());
        // 删除该停运后做核销重放
        postOk("/api/outages/" + outageKey + "/delete",
                Map.of("commandKey", key("dl"), "expectedVersion", 2), null);
        String settleCommand = key("sc");
        String settlementKey = key("sk");
        Map<String, Object> settle = settleBody(settlementKey, "1.5");
        settle.put("commandKey", settleCommand);
        JsonNode s1 = postOk("/api/allocations/" + a + "/settle", settle, "alice");
        JsonNode s2 = postOk("/api/allocations/" + a + "/settle", settle, "alice");
        assertEquals(s1, s2);
        // 只扣一次、只有一条流水
        assertEquals("2.5", allocation(w, a).get("heldAmount").asText());
        assertEquals(1, getOk("/api/allocations/" + a + "/settlements").get("settlements").size());
    }

    @Test
    void sameOutageKeyReplaysAcrossCommandKeysAndConflictsOnDifferentFingerprint() throws Exception {
        String channel = "ch-ok-" + run;
        long w = createWindow(channel, "2026-11-27T00:00:00Z", "2026-11-27T12:00:00Z", "20");
        String a = submit(w, "u-a", "1", "alice");
        String outageKey = key("ok");
        JsonNode first = createOutage(channel, outageKey, "2026-11-27T01:00:00Z",
                "2026-11-27T02:00:00Z", List.of(a), 1);
        // 换 commandKey，同 outageKey 同指纹（渠道、时段、规范化受影响集合）-> 重放首次结果
        JsonNode replay = createOutage(channel, outageKey, "2026-11-27T01:00:00Z",
                "2026-11-27T02:00:00Z", List.of(a), 2);
        assertEquals(first, replay);
        // 重放不产生新变更：渠道版本不变、仍只有一个停运窗口
        assertEquals(2, getOk("/api/channels/" + channel).get("version").asInt());
        assertEquals(1, getOk("/api/channels/" + channel + "/outages").get("outages").size());
        // 同 outageKey 不同指纹（改时段/改受影响集合）-> 409
        postJson("/api/channels/" + channel + "/outages",
                outageBody(outageKey, "2026-11-27T03:00:00Z", "2026-11-27T04:00:00Z", List.of(a), 2),
                null, 409);
        postJson("/api/channels/" + channel + "/outages",
                outageBody(outageKey, "2026-11-27T01:00:00Z", "2026-11-27T02:00:00Z", List.of(), 2),
                null, 409);
        assertEquals(1, getOk("/api/channels/" + channel + "/outages").get("outages").size());
    }

    // ------------------------------------------------------------------
    // 并发裁决
    // ------------------------------------------------------------------

    @Test
    void concurrentOverlappingOutagesSettleByCommitOrder() throws Exception {
        String channel = "ch-race-out-" + run;
        createWindow(channel, "2026-10-10T00:00:00Z", "2026-10-11T00:00:00Z", "20");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return createOutageStatus(channel, key("ok"), "2026-10-10T06:00:00Z",
                    "2026-10-10T18:00:00Z", 1);
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return createOutageStatus(channel, key("ok"), "2026-10-10T12:00:00Z",
                    "2026-10-10T20:00:00Z", 1);
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0),
                "重叠停运并发下达恰好一个成功: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0));
        // 最终渠道版本只 +1，且只有一条未删除停运
        assertEquals(2, getOk("/api/channels/" + channel).get("version").asInt());
        JsonNode outages = getOk("/api/channels/" + channel + "/outages");
        assertEquals(1, outages.get("outages").size());
        assertFalse("DELETED".equals(outages.get("outages").get(0).get("status").asText()));
    }

    @Test
    void settlementAndOutageOrderSettleByCommitOrderWithoutHalfDoneState() throws Exception {
        String channel = "ch-race-set-" + run;
        long w = createWindow(channel, "2026-10-10T00:00:00Z", "2026-10-11T00:00:00Z", "20");
        String a = submit(w, "u-a", "4", "alice");
        approve(a);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> settleFuture = pool.submit(() -> {
            gate.await();
            return settleStatusDirect(a, "2", "alice");
        });
        Future<Integer> outageFuture = pool.submit(() -> {
            gate.await();
            try {
                canalService.createOutage(key("oc"), key("ok"), channel, "2026-10-10T06:00:00Z",
                        "2026-10-10T18:00:00Z", List.of(a), 1);
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int settleStatus = settleFuture.get(30, TimeUnit.SECONDS);
        int outageStatus = outageFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(200, outageStatus);

        JsonNode held = allocation(w, a).get("heldAmount");
        JsonNode risks = getOk("/api/allocations/" + a + "/risks");
        JsonNode settlements = getOk("/api/allocations/" + a + "/settlements");
        if (settleStatus == 200) {
            // 核销先提交：持有扣减到 2、流水 1 条；停运随后写入风险（held>0）
            assertEquals("2", held.asText());
            assertEquals(1, settlements.get("settlements").size());
            assertEquals(1, risks.get("risks").size());
        } else {
            // 停运先提交：核销 422 回滚，无半成品；停运受影响集合含该申请
            assertEquals(422, settleStatus);
            assertEquals("4", held.asText());
            assertEquals(0, settlements.get("settlements").size());
            JsonNode impact = getOk("/api/outages/"
                    + getOk("/api/channels/" + channel + "/outages").get("outages").get(0)
                    .get("outageKey").asText() + "/impact");
            assertTrue(impact.get("allocations").get(0).get("intersects").asBoolean());
        }
    }

    @Test
    void concurrentSameCommandKeySettlesExactlyOnce() throws Exception {
        String channel = "ch-race-key-" + run;
        long w = createWindow(channel, "2026-11-26T00:00:00Z", "2026-11-26T12:00:00Z", "20");
        String a = submit(w, "u-a", "4", "alice");
        approve(a);
        String commandKey = key("sc");
        String settlementKey = key("sk");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return settleStatusDirectWithKeys(commandKey, settlementKey, a, "1");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return settleStatusDirectWithKeys(commandKey, settlementKey, a, "1");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(200, s1);
        assertEquals(200, s2);
        // 同键重放：持有只扣一次，流水只有一条
        assertEquals("3", allocation(w, a).get("heldAmount").asText());
        assertEquals(1, getOk("/api/allocations/" + a + "/settlements").get("settlements").size());
    }

    private int settleStatusDirectWithKeys(String commandKey, String settlementKey, String allocationKey,
                                           String amount) {
        try {
            canalService.settle(commandKey, settlementKey, allocationKey, amount, "alice");
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private int createOutageStatus(String channelId, String outageKey, String start, String end,
                                   int expectedVersion) {
        try {
            canalService.createOutage(key("oc"), outageKey, channelId, start, end, List.of(),
                    expectedVersion);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private void mvcPerformNotFound(String url) throws Exception {
        mvc.perform(get(url)).andExpect(status().isNotFound());
    }

    private static List<String> toStringList(JsonNode arrayNode) {
        List<String> result = new ArrayList<>();
        arrayNode.elements().forEachRemaining(node -> result.add(node.asText()));
        return result;
    }
}
