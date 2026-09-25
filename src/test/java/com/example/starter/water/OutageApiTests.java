package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 输水渠停运窗口与配额核销联动测试：UTC 窗口、渠道版本、核销门禁、批量回滚、
 * 供应风险与并发幂等。全部使用真实 H2（MODE=MySQL）事务与行锁，时钟可控。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutageApiTests {

    private static final Instant BASE = Instant.parse("2026-09-26T08:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

    @MockitoBean
    private Clock clock;

    private final AtomicReference<Instant> now = new AtomicReference<>(BASE);

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private int seq;

    @BeforeEach
    void stubClock() {
        Mockito.lenient().when(clock.instant()).thenAnswer(inv -> now.get());
    }

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

    private JsonNode postErr(String url, Map<String, Object> body, String actor, int expectedStatus)
            throws Exception {
        return objectMapper.readTree(
                postJson(url, body, actor, expectedStatus).getResponse().getContentAsString());
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

    private String submitAllocation(long windowId, String userId, String amount, String actor)
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

    private void approve(String allocationKey, int expectedStatus) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus);
    }

    private JsonNode createOutage(String channel, String outageKey, long expectedVersion, String start,
                                  String end, List<String> affected, int expectedStatus) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("oc"));
        body.put("outageKey", outageKey);
        body.put("expectedVersion", expectedVersion);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("allocationKeys", affected);
        return objectMapper.readTree(postJson("/api/channels/" + channel + "/outages", body, null,
                expectedStatus).getResponse().getContentAsString());
    }

    private JsonNode deleteOutage(String channel, String outageKey, long expectedVersion, int expectedStatus)
            throws Exception {
        return objectMapper.readTree(postJson(
                "/api/channels/" + channel + "/outages/" + outageKey + "/delete",
                Map.of("commandKey", key("od"), "expectedVersion", expectedVersion), null, expectedStatus)
                .getResponse().getContentAsString());
    }

    private JsonNode recoverOutage(String channel, String outageKey, long expectedVersion, String recoveredUtc,
                                   int expectedStatus) throws Exception {
        return objectMapper.readTree(postJson(
                "/api/channels/" + channel + "/outages/" + outageKey + "/recover",
                Map.of("commandKey", key("or"), "expectedVersion", expectedVersion,
                        "recoveredUtc", recoveredUtc), null, expectedStatus)
                .getResponse().getContentAsString());
    }

    private JsonNode settle(String allocationKey, String settleKey, String amount, int expectedStatus)
            throws Exception {
        return objectMapper.readTree(postJson("/api/allocations/" + allocationKey + "/settlements",
                Map.of("commandKey", key("sc"), "settlementKey", settleKey, "amount", amount), null,
                expectedStatus).getResponse().getContentAsString());
    }

    private JsonNode settleBatch(String commandKey, String batchKey, List<Map<String, String>> items,
                                 int expectedStatus) throws Exception {
        return objectMapper.readTree(postJson("/api/settlements/batch",
                Map.of("commandKey", commandKey, "batchKey", batchKey, "items", items), null, expectedStatus)
                .getResponse().getContentAsString());
    }

    private Map<String, String> item(String allocationKey, String amount) {
        return Map.of("allocationKey", allocationKey, "amount", amount);
    }

    private JsonNode transfer(String sourceKey, String targetKey, String actor, int expectedStatus)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("tc"));
        body.put("transferKey", key("tk"));
        body.put("sourceAllocationKey", sourceKey);
        body.put("targetAllocationKey", targetKey);
        return objectMapper.readTree(postJson("/api/transfers", body, actor, expectedStatus)
                .getResponse().getContentAsString());
    }

    private String heldAmount(long windowId, String allocationKey) throws Exception {
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        for (JsonNode allocation : history.get("allocations")) {
            if (allocation.get("allocationKey").asText().equals(allocationKey)) {
                return allocation.get("heldAmount").asText();
            }
        }
        throw new AssertionError("allocation not found: " + allocationKey);
    }

    // ------------------------------------------------------------------
    // 停运窗口：UTC 窗口、重叠与渠道版本
    // ------------------------------------------------------------------

    @Test
    void outageWindowOverlapVersionAndAffectedSet() throws Exception {
        String channel = "ch-ow-" + run;
        long windowId = createWindow(channel, "2026-10-01T00:00:00Z", "2026-10-01T04:00:00Z", "10");
        // 渠道版本不符 -> 409
        JsonNode versionErr = createOutage(channel, key("ok"), 3,
                "2026-10-01T01:00:00Z", "2026-10-01T02:00:00Z", List.of(), 409);
        assertEquals("CHANNEL_VERSION_CONFLICT", versionErr.get("code").asText());
        // 版本 0 -> 200，变更后版本 1
        String outageKey = key("ok");
        JsonNode outage = createOutage(channel, outageKey, 0,
                "2026-10-01T01:00:00Z", "2026-10-01T02:00:00Z", List.of(), 200);
        assertEquals(1, outage.get("channelVersion").asLong());
        assertEquals("SCHEDULED", outage.get("status").asText());
        assertTrue(outage.get("recoveredUtc").isNull());
        // 重叠 -> 409；端点相接 -> 200
        JsonNode overlap = createOutage(channel, key("ok"), 1,
                "2026-10-01T01:30:00Z", "2026-10-01T03:00:00Z", List.of(), 409);
        assertEquals("OUTAGE_OVERLAP", overlap.get("code").asText());
        JsonNode adjacent = createOutage(channel, key("ok"), 1,
                "2026-10-01T02:00:00Z", "2026-10-01T03:00:00Z", List.of(), 200);
        assertEquals(2, adjacent.get("channelVersion").asLong());
        // outageKey 复用 -> 409
        JsonNode reused = createOutage(channel, outageKey, 2,
                "2026-10-01T03:00:00Z", "2026-10-01T04:00:00Z", List.of(), 409);
        assertEquals("OUTAGE_KEY_REUSED", reused.get("code").asText());
        // 起止倒置 -> 400
        createOutage(channel, key("ok"), 2, "2026-10-01T03:00:00Z", "2026-10-01T03:00:00Z", List.of(), 400);
        // 不同渠道同时段 -> 200（渠道随停运创建，版本从 0 开始）
        JsonNode other = createOutage("ch-ow2-" + run, key("ok"), 0,
                "2026-10-01T01:00:00Z", "2026-10-01T02:00:00Z", List.of(), 200);
        assertEquals(1, other.get("channelVersion").asLong());
        // 受影响申请校验：不存在 -> 404；跨渠道 -> 409；去重排序后写入
        String a1 = submitAllocation(windowId, "user-1", "1", "alice");
        JsonNode missing = createOutage(channel, key("ok"), 2,
                "2026-10-01T03:00:00Z", "2026-10-01T03:30:00Z", List.of("no-such-" + run), 404);
        assertEquals("ALLOCATION_NOT_FOUND", missing.get("code").asText());
        long otherWindow = createWindow("ch-ow3-" + run,
                "2026-10-01T00:00:00Z", "2026-10-01T04:00:00Z", "10");
        String aOther = submitAllocation(otherWindow, "user-2", "1", "bob");
        JsonNode mismatch = createOutage(channel, key("ok"), 2,
                "2026-10-01T03:00:00Z", "2026-10-01T03:30:00Z", List.of(aOther), 409);
        assertEquals("ALLOCATION_CHANNEL_MISMATCH", mismatch.get("code").asText());
        JsonNode withAffected = createOutage(channel, key("ok"), 2,
                "2026-10-01T03:00:00Z", "2026-10-01T03:30:00Z", List.of(a1, a1), 200);
        assertEquals(1, withAffected.get("allocationKeys").size());
        assertEquals(a1, withAffected.get("allocationKeys").get(0).asText());
    }

    @Test
    void startedOutageCannotBeDeletedAndRecoveryRules() throws Exception {
        String channel = "ch-del-" + run;
        createWindow(channel, "2026-09-20T00:00:00Z", "2026-09-21T00:00:00Z", "10");
        // 未开始停运可删除
        String futureKey = key("ok");
        createOutage(channel, futureKey, 0, "2026-09-27T00:00:00Z", "2026-09-28T00:00:00Z", List.of(), 200);
        JsonNode deleted = deleteOutage(channel, futureKey, 1, 200);
        assertEquals("DELETED", deleted.get("status").asText());
        assertEquals(2, deleted.get("channelVersion").asLong());
        JsonNode deletedAgain = deleteOutage(channel, futureKey, 2, 409);
        assertEquals("OUTAGE_DELETED", deletedAgain.get("code").asText());
        // 已开始停运不可删除
        String startedKey = key("ok");
        createOutage(channel, startedKey, 2, "2026-09-25T00:00:00Z", "2026-09-27T00:00:00Z", List.of(), 200);
        JsonNode startedDelete = deleteOutage(channel, startedKey, 3, 409);
        assertEquals("OUTAGE_ALREADY_STARTED", startedDelete.get("code").asText());
        // 恢复时刻早于当前 -> 400；不早于结束时刻 -> 400
        recoverOutage(channel, startedKey, 3, "2026-09-26T07:00:00Z", 400);
        recoverOutage(channel, startedKey, 3, "2026-09-28T00:00:00Z", 400);
        // 正常恢复：版本递增
        JsonNode recovered = recoverOutage(channel, startedKey, 3, "2026-09-26T12:00:00Z", 200);
        assertEquals("2026-09-26T12:00:00Z", recovered.get("recoveredUtc").asText());
        assertEquals(4, recovered.get("channelVersion").asLong());
        // 重复恢复 -> 409；版本不符 -> 409
        JsonNode twice = recoverOutage(channel, startedKey, 4, "2026-09-26T13:00:00Z", 409);
        assertEquals("OUTAGE_ALREADY_RECOVERED", twice.get("code").asText());
        JsonNode staleVersion = recoverOutage(channel, startedKey, 99, "2026-09-26T14:00:00Z", 409);
        assertEquals("CHANNEL_VERSION_CONFLICT", staleVersion.get("code").asText());
        // 未开始停运只能删除，不能恢复
        String future2 = key("ok");
        createOutage(channel, future2, 4, "2026-09-27T00:00:00Z", "2026-09-28T00:00:00Z", List.of(), 200);
        JsonNode notStarted = recoverOutage(channel, future2, 5, "2026-09-27T12:00:00Z", 409);
        assertEquals("OUTAGE_NOT_STARTED", notStarted.get("code").asText());
        // 停运不存在 -> 404
        deleteOutage(channel, "no-such-" + run, 5, 404);
        recoverOutage(channel, "no-such-" + run, 5, "2026-09-26T12:00:00Z", 404);
    }

    // ------------------------------------------------------------------
    // 核销与停运联动
    // ------------------------------------------------------------------

    @Test
    void settlementBlockedByOutageUntilRecovery() throws Exception {
        String channel = "ch-sb-" + run;
        long windowId = createWindow(channel, "2026-09-20T00:00:00Z", "2026-09-22T00:00:00Z", "10");
        String a1 = submitAllocation(windowId, "user-1", "5", "alice");
        approve(a1, 200);
        String outageKey = key("ok");
        createOutage(channel, outageKey, 0, "2026-09-20T12:00:00Z", "2026-09-30T00:00:00Z",
                List.of(a1), 200);
        // 生效停运相交 -> 422，可区分原因
        JsonNode blocked = settle(a1, key("sk"), "1", 422);
        assertEquals("OUTAGE_INTERSECT", blocked.get("code").asText());
        JsonNode check = getOk("/api/allocations/" + a1 + "/settlement-check?amount=1");
        assertFalse(check.get("settleable").asBoolean());
        assertEquals("OUTAGE_INTERSECT", check.get("rejectCode").asText());
        // 记录未来恢复时刻：恢复仅影响之后的核销，当前仍拒绝
        recoverOutage(channel, outageKey, 1, "2026-09-26T12:00:00Z", 200);
        assertEquals("OUTAGE_INTERSECT", settle(a1, key("sk"), "1", 422).get("code").asText());
        // 时钟拨过恢复时刻 -> 核销成功，余额扣减
        now.set(Instant.parse("2026-09-26T13:00:00Z"));
        JsonNode settled = settle(a1, key("sk"), "1", 200);
        assertEquals("1", settled.get("amount").asText());
        assertEquals("4", heldAmount(windowId, a1));
        JsonNode checkAfter = getOk("/api/allocations/" + a1 + "/settlement-check?amount=1");
        assertTrue(checkAfter.get("settleable").asBoolean());
    }

    @Test
    void settleBalanceStateAndKeyRules() throws Exception {
        String channel = "ch-st-" + run;
        long windowId = createWindow(channel, "2026-09-20T00:00:00Z", "2026-09-22T00:00:00Z", "10");
        String a1 = submitAllocation(windowId, "user-1", "5", "alice");
        String a2 = submitAllocation(windowId, "user-2", "3", "bob");
        approve(a1, 200);
        // 未批准 -> 409
        JsonNode notApproved = settle(a2, key("sk"), "1", 409);
        assertEquals("ALLOCATION_NOT_APPROVED", notApproved.get("code").asText());
        // 余额不足 -> 422
        JsonNode insufficient = settle(a1, key("sk"), "6", 422);
        assertEquals("INSUFFICIENT_BALANCE", insufficient.get("code").asText());
        // 正常核销至余额为 0
        String settleKey = key("sk");
        settle(a1, settleKey, "2", 200);
        assertEquals("3", heldAmount(windowId, a1));
        settle(a1, key("sk"), "3", 200);
        assertEquals("0", heldAmount(windowId, a1));
        assertEquals("INSUFFICIENT_BALANCE", settle(a1, key("sk"), "0.001", 422).get("code").asText());
        // settlementKey 复用（换 commandKey）-> 409
        assertEquals("SETTLEMENT_KEY_REUSED", settle(a2, settleKey, "1", 409).get("code").asText());
        // 核销可行性查询：余额不足与可核销
        approve(a2, 200);
        JsonNode check = getOk("/api/allocations/" + a2 + "/settlement-check?amount=4");
        assertFalse(check.get("settleable").asBoolean());
        assertEquals("INSUFFICIENT_BALANCE", check.get("rejectCode").asText());
        assertTrue(getOk("/api/allocations/" + a2 + "/settlement-check").get("settleable").asBoolean());
        // 不存在 -> 404
        settle("no-such-" + run, key("sk"), "1", 404);
        mvc.perform(get("/api/allocations/no-such-" + run + "/settlement-check"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/allocations/no-such-" + run + "/risks"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/channels/" + channel + "/outages/no-such-" + run + "/impact"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 批量核销：预校验与整体回滚
    // ------------------------------------------------------------------

    @Test
    void batchSettleAllOrNothingAndKeyReuse() throws Exception {
        String channel = "ch-bt-" + run;
        long windowId = createWindow(channel, "2026-09-20T00:00:00Z", "2026-09-22T00:00:00Z", "10");
        String a1 = submitAllocation(windowId, "user-1", "5", "alice");
        String a2 = submitAllocation(windowId, "user-2", "3", "bob");
        approve(a1, 200);
        approve(a2, 200);
        // 全部通过 -> 同事务扣减并写流水
        JsonNode ok = settleBatch(key("bc"), key("bk"), List.of(item(a1, "2"), item(a2, "1")), 200);
        assertEquals(2, ok.get("settlements").size());
        assertEquals("3", heldAmount(windowId, a1));
        assertEquals("2", heldAmount(windowId, a2));
        // 任一失败全部回滚：a2 余额不足 -> 422，a1 不扣减、无流水
        String failCommand = key("bc");
        String failBatch = key("bk");
        JsonNode failed = settleBatch(failCommand, failBatch, List.of(item(a1, "1"), item(a2, "5")), 422);
        assertEquals("INSUFFICIENT_BALANCE", failed.get("code").asText());
        assertEquals("3", heldAmount(windowId, a1));
        assertEquals("2", heldAmount(windowId, a2));
        // 失败不占键：同 commandKey/batchKey 修正参数后成功
        JsonNode retried = settleBatch(failCommand, failBatch, List.of(item(a1, "1"), item(a2, "2")), 200);
        assertEquals(2, retried.get("settlements").size());
        assertEquals("2", heldAmount(windowId, a1));
        assertEquals("0", heldAmount(windowId, a2));
        // batchKey 复用 -> 409；同批重复申请 -> 400；不存在申请 -> 404
        assertEquals("BATCH_KEY_REUSED",
                settleBatch(key("bc"), failBatch, List.of(item(a1, "0.5")), 409).get("code").asText());
        settleBatch(key("bc"), key("bk"), List.of(item(a1, "0.5"), item(a1, "0.5")), 400);
        settleBatch(key("bc"), key("bk"), List.of(item(a1, "0.5"), item("no-such-" + run, "0.5")), 404);
        assertEquals("2", heldAmount(windowId, a1));
    }

    @Test
    void batchSettleRespectsFinalChannelCapacity() throws Exception {
        String channel = "ch-cap-" + run;
        long windowId = createWindow(channel, "2026-09-20T00:00:00Z", "2026-09-22T00:00:00Z", "10");
        String a1 = submitAllocation(windowId, "user-1", "10", "alice");
        approve(a1, 200);
        settle(a1, key("sk"), "4", 200);
        // 限供后最终可用总量降为 6，累计已核销 4
        postOk("/api/windows/" + windowId + "/curtailment",
                Map.of("commandKey", key("cu"), "volume", "6"), null);
        // 4 + 3 > 6 -> 422 且余额不变；4 + 2 == 6 -> 200
        JsonNode exceeded = settleBatch(key("bc"), key("bk"), List.of(item(a1, "3")), 422);
        assertEquals("CHANNEL_CAPACITY_EXCEEDED", exceeded.get("code").asText());
        assertEquals("6", heldAmount(windowId, a1));
        settleBatch(key("bc"), key("bk"), List.of(item(a1, "2")), 200);
        assertEquals("4", heldAmount(windowId, a1));
    }

    // ------------------------------------------------------------------
    // 供应风险与转让门禁
    // ------------------------------------------------------------------

    @Test
    void transferGatedByOutageAndImmutableRisk() throws Exception {
        String channel = "ch-tg-" + run;
        long windowId = createWindow(channel, "2026-09-20T00:00:00Z", "2026-09-30T00:00:00Z", "10");
        String source = submitAllocation(windowId, "user-1", "5", "alice");
        String target = submitAllocation(windowId, "user-2", "2", "bob");
        approve(source, 200);
        // 下达未来停运（与窗口相交），已批准未结算的 source 写入供应风险
        String outageKey = key("ok");
        createOutage(channel, outageKey, 0, "2026-09-27T00:00:00Z", "2026-09-28T00:00:00Z",
                List.of(source), 200);
        // 生效停运相交 -> 转让结算 422
        JsonNode blocked = transfer(source, target, "alice", 422);
        assertEquals("OUTAGE_INTERSECT", blocked.get("code").asText());
        // 删除未开始停运后停运不再生效，但风险不可变：风险申请不能再次转让
        deleteOutage(channel, outageKey, 1, 200);
        JsonNode risky = transfer(source, target, "alice", 409);
        assertEquals("SUPPLY_RISK", risky.get("code").asText());
        // 风险查询
        JsonNode risks = getOk("/api/allocations/" + source + "/risks");
        assertEquals(1, risks.get("risks").size());
        assertEquals(outageKey, risks.get("risks").get(0).get("outageKey").asText());
        // 停运影响查询：窗口 + 受影响申请 + 已写入风险
        JsonNode impact = getOk("/api/channels/" + channel + "/outages/" + outageKey + "/impact");
        assertEquals("DELETED", impact.get("outage").get("status").asText());
        assertEquals(1, impact.get("affectedAllocations").size());
        assertEquals(source, impact.get("affectedAllocations").get(0).get("allocationKey").asText());
        assertEquals(1, impact.get("risks").size());
        // 无风险申请转让不受影响
        String source2 = submitAllocation(windowId, "user-3", "3", "carol");
        String target2 = submitAllocation(windowId, "user-4", "1", "dave");
        approve(source2, 200);
        transfer(source2, target2, "carol", 200);
    }

    // ------------------------------------------------------------------
    // 幂等：同键重放，失败不占键
    // ------------------------------------------------------------------

    @Test
    void outageIdempotencyAndFailureDoesNotOccupyKey() throws Exception {
        String channel = "ch-id-" + run;
        createWindow(channel, "2026-10-01T00:00:00Z", "2026-10-01T04:00:00Z", "10");
        String outageKey = key("ok");
        Map<String, Object> body = new HashMap<>();
        String commandKey = key("oc");
        body.put("commandKey", commandKey);
        body.put("outageKey", outageKey);
        body.put("expectedVersion", 0);
        body.put("startUtc", "2026-10-01T01:00:00Z");
        body.put("endUtc", "2026-10-01T02:00:00Z");
        body.put("allocationKeys", List.of());
        JsonNode first = postOk("/api/channels/" + channel + "/outages", body, null);
        JsonNode replay = postOk("/api/channels/" + channel + "/outages", body, null);
        assertEquals(first, replay);
        // 同键改参 -> 409
        body.put("endUtc", "2026-10-01T03:00:00Z");
        postJson("/api/channels/" + channel + "/outages", body, null, 409);
        // 失败命令不占键：重叠失败后用同 commandKey 下达合法窗口
        String failCommand = key("oc");
        Map<String, Object> failBody = new HashMap<>();
        failBody.put("commandKey", failCommand);
        failBody.put("outageKey", key("ok"));
        failBody.put("expectedVersion", 1);
        failBody.put("startUtc", "2026-10-01T01:30:00Z");
        failBody.put("endUtc", "2026-10-01T03:00:00Z");
        failBody.put("allocationKeys", List.of());
        postJson("/api/channels/" + channel + "/outages", failBody, null, 409);
        failBody.put("outageKey", key("ok"));
        failBody.put("startUtc", "2026-10-01T02:00:00Z");
        postOk("/api/channels/" + channel + "/outages", failBody, null);
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentSettlementsNeverOverdrawBalance() throws Exception {
        String channel = "ch-cs-" + run;
        long windowId = createWindow(channel, "2026-09-20T00:00:00Z", "2026-09-22T00:00:00Z", "10");
        String a1 = submitAllocation(windowId, "user-1", "1.000", "alice");
        approve(a1, 200);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String commandKey = key("sc");
            String settlementKey = key("sk");
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    waterService.settle(commandKey, settlementKey, a1, "0.2");
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        int ok = 0;
        int insufficient = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 422) {
                insufficient++;
            }
        }
        pool.shutdown();
        assertEquals(5, ok, "恰好 5 个 0.2 可以核销 1.000");
        assertEquals(5, insufficient);
        assertEquals("0", heldAmount(windowId, a1));
    }

    @Test
    void concurrentOutageAndSettleAdjudicatedByCommitOrder() throws Exception {
        String channel = "ch-co-" + run;
        long windowId = createWindow(channel, "2026-09-20T00:00:00Z", "2026-09-22T00:00:00Z", "10");
        String a1 = submitAllocation(windowId, "user-1", "5", "alice");
        approve(a1, 200);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        String outageKey = key("ok");
        Future<Integer> settleFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.settle(key("sc"), key("sk"), a1, "1");
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        Future<Integer> outageFuture = pool.submit(() -> {
            gate.await();
            try {
                waterService.createOutage(key("oc"), outageKey, channel, 0L,
                        "2026-09-20T12:00:00Z", "2026-09-30T00:00:00Z", List.of());
                return 200;
            } catch (ApiException e) {
                return e.status().value();
            }
        });
        gate.countDown();
        int settleStatus = settleFuture.get(30, TimeUnit.SECONDS);
        int outageStatus = outageFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        // 停运下达始终成功；核销按提交顺序裁决：先于停运提交则 200，否则 422
        assertEquals(200, outageStatus);
        assertTrue(settleStatus == 200 || settleStatus == 422, "settle=" + settleStatus);
        assertEquals(settleStatus == 200 ? "4" : "5", heldAmount(windowId, a1));
        // 停运生效后新的核销一律 422
        assertEquals("OUTAGE_INTERSECT", settle(a1, key("sk"), "1", 422).get("code").asText());
    }
}
