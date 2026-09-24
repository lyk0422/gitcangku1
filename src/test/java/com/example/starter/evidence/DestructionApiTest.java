package com.example.starter.evidence;

import com.example.starter.evidence.dto.LoanCreateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 销毁令 API 主流程、入列校验、冻结拦截、双人审批、执行重查与幂等边界测试（真实 H2 库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DestructionApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult intake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult createOrder(String actor, String commandKey, String destructionKey,
                                  List<String> evidenceKeys, boolean forceBroken) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("destructionKey", destructionKey);
        body.put("evidenceKeys", evidenceKeys);
        body.put("legalBasis", "LAW-ART-9");
        body.put("destructionMethod", "INCINERATION");
        body.put("forceIncludeBroken", forceBroken);
        return mockMvc.perform(post("/api/destruction-orders")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult approve(String approver, String destructionKey, String commandKey) throws Exception {
        return decide(approver, destructionKey, commandKey, "approvals", null);
    }

    private MvcResult reject(String approver, String destructionKey, String commandKey,
                              String reason) throws Exception {
        return decide(approver, destructionKey, commandKey, "rejections", reason);
    }

    private MvcResult decide(String approver, String destructionKey, String commandKey,
                            String action, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        if (reason != null) {
            body.put("reason", reason);
        }
        return mockMvc.perform(post("/api/destruction-orders/{key}/{action}", destructionKey, action)
                        .header(ACTOR_HEADER, approver)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult execute(String actor, String destructionKey, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(post("/api/destruction-orders/{key}/execution", destructionKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode orderDetail(String destructionKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/destruction-orders/{key}", destructionKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode freeze(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/destruction-orders/evidence/{key}/freeze", evidenceKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult initiateTransfer(String actor, String evidenceKey, String toCustodian)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", toCustodian);
        return mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult borrow(String actor, String evidenceKey) throws Exception {
        LoanCreateRequest request = new LoanCreateRequest(uniqueKey("CMD"), uniqueKey("LOAN"),
                "borrower-x", "鉴定用", LocalDateTime.now().plusHours(2));
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();
    }

    private MvcResult inspect(String actor, String evidenceKey, boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", passed);
        body.put("note", "note");
        return mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/evidence/{key}/custody-chain", evidenceKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String approveTwiceAndExecute(String... evidenceKeys) throws Exception {
        String destructionKey = uniqueKey("DST");
        assertThat(createOrder("alice", uniqueKey("CMD"), destructionKey,
                List.of(evidenceKeys), false).getResponse().getStatus()).isEqualTo(201);
        assertThat(approve("bob", destructionKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(200);
        JsonNode afterFirst = orderDetail(destructionKey);
        assertThat(afterFirst.get("status").asText()).isEqualTo("PENDING");
        assertThat(approve("carol", destructionKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(orderDetail(destructionKey).get("status").asText()).isEqualTo("APPROVED");
        assertThat(execute("alice", destructionKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(200);
        return destructionKey;
    }

    @Test
    void fullFlowCreatesApprovesAndDestroysEvidence() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);

        String destructionKey = approveTwiceAndExecute(ev1, ev2);

        JsonNode order = orderDetail(destructionKey);
        assertThat(order.get("status").asText()).isEqualTo("DESTROYED");
        assertThat(order.get("executedAt").isNull()).isFalse();
        assertThat(order.get("decidedAt").isNull()).isFalse();
        assertThat(order.get("approvals")).hasSize(2);
        assertThat(order.get("approvals").findValuesAsText("approverId"))
                .containsExactlyInAnyOrder("bob", "carol");
        assertThat(order.get("items")).hasSize(2);
        assertThat(order.get("items").findValuesAsText("currentStatus"))
                .containsOnly("DESTROYED");

        // 原保管链仍可查询，证物状态为 DESTROYED
        assertThat(chain(ev1).get("evidence").get("status").asText()).isEqualTo("DESTROYED");
        JsonNode frozen = freeze(ev1);
        assertThat(frozen.get("frozen").asBoolean()).isFalse();
        assertThat(frozen.get("destructionKey").isNull()).isTrue();
    }

    @Test
    void createWithInvalidItemsReturns422WithPerItemReasonsAndNoOrder() throws Exception {
        String ok = uniqueKey("EV");
        String borrowed = uniqueKey("EV");
        String pending = uniqueKey("EV");
        String broken = uniqueKey("EV");
        String other = uniqueKey("EV");
        String missing = uniqueKey("EV");
        intake("alice", ok);
        intake("alice", borrowed);
        intake("alice", pending);
        intake("alice", broken);
        intake("bob", other);
        assertThat(borrow("alice", borrowed).getResponse().getStatus()).isEqualTo(200);
        assertThat(initiateTransfer("alice", pending, "bob").getResponse().getStatus()).isEqualTo(200);
        assertThat(inspect("alice", broken, false).getResponse().getStatus()).isEqualTo(200);

        String destructionKey = uniqueKey("DST");
        MvcResult result = createOrder("alice", uniqueKey("CMD"), destructionKey,
                List.of(ok, borrowed, pending, broken, other, missing), false);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(error.get("status").asInt()).isEqualTo(422);
        Map<String, String> reasons = new LinkedHashMap<>();
        for (JsonNode item : error.get("items")) {
            reasons.put(item.get("evidenceKey").asText(), item.get("reason").asText());
        }
        assertThat(reasons.get(borrowed)).isEqualTo("ACTIVE_LOAN");
        assertThat(reasons.get(pending)).isEqualTo("PENDING_TRANSFER");
        assertThat(reasons.get(broken)).isEqualTo("SEAL_BROKEN");
        assertThat(reasons.get(other)).isEqualTo("NOT_CUSTODIAN");
        assertThat(reasons.get(missing)).isEqualTo("EVIDENCE_NOT_FOUND");
        assertThat(reasons).doesNotContainKey(ok);

        // 整单失败：销毁令未创建
        MvcResult detail = mockMvc.perform(get("/api/destruction-orders/{key}", destructionKey))
                .andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(404);
        // 证物未被冻结
        assertThat(freeze(ok).get("frozen").asBoolean()).isFalse();
    }

    @Test
    void brokenSealRequiresForceFlagButCanBeIncludedWhenForced() throws Exception {
        String broken = uniqueKey("EV");
        intake("alice", broken);
        inspect("alice", broken, false);

        String key1 = uniqueKey("DST");
        assertThat(createOrder("alice", uniqueKey("CMD"), key1, List.of(broken), false)
                .getResponse().getStatus()).isEqualTo(422);

        String key2 = uniqueKey("DST");
        MvcResult forced = createOrder("alice", uniqueKey("CMD"), key2,
                List.of(broken), true);
        assertThat(forced.getResponse().getStatus()).isEqualTo(201);
        JsonNode order = objectMapper.readTree(forced.getResponse().getContentAsString());
        assertThat(order.get("status").asText()).isEqualTo("PENDING");
        assertThat(order.get("items").get(0).get("includedStatus").asText()).isEqualTo("SEAL_BROKEN");
        assertThat(order.get("items").get(0).get("forcedBroken").asBoolean()).isTrue();
    }

    @Test
    void pendingAndApprovedEvidenceIsFrozenAgainstTransfersLoansInspectionsAndRelisting()
            throws Exception {
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        String destructionKey = uniqueKey("DST");
        assertThat(createOrder("alice", uniqueKey("CMD"), destructionKey, List.of(ev1), false)
                .getResponse().getStatus()).isEqualTo(201);

        // PENDING 冻结：交接/借出/核验全部 409 且返回冻结销毁令键
        for (MvcResult blocked : List.of(
                initiateTransfer("alice", ev1, "bob"),
                borrow("alice", ev1),
                inspect("alice", ev1, true))) {
            assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
            assertThat(blocked.getResponse().getContentAsString()).contains(destructionKey);
        }
        // 再次入列其他销毁令：409 且返回冻结销毁令键
        MvcResult relist = createOrder("alice", uniqueKey("CMD"), uniqueKey("DST"),
                List.of(ev1), false);
        assertThat(relist.getResponse().getStatus()).isEqualTo(409);
        assertThat(relist.getResponse().getContentAsString()).contains(destructionKey);

        // APPROVED 后冻结仍在
        approve("bob", destructionKey, uniqueKey("CMD"));
        approve("carol", destructionKey, uniqueKey("CMD"));
        assertThat(initiateTransfer("alice", ev1, "bob").getResponse().getStatus()).isEqualTo(409);
        assertThat(freeze(ev1).get("destructionKey").asText()).isEqualTo(destructionKey);
    }

    @Test
    void rejectMovesToTerminalStateReleasesEvidenceAndReasonIsImmutable() throws Exception {
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        String destructionKey = uniqueKey("DST");
        String rejectKey = uniqueKey("CMD");
        createOrder("alice", uniqueKey("CMD"), destructionKey, List.of(ev1), false);

        MvcResult rejected = reject("bob", destructionKey, rejectKey, "依据不充分");
        assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
        JsonNode order = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertThat(order.get("status").asText()).isEqualTo("REJECTED");
        assertThat(order.get("rejectReason").asText()).isEqualTo("依据不充分");
        assertThat(order.get("decidedAt").isNull()).isFalse();

        // 拒绝后证物恢复可用
        assertThat(initiateTransfer("alice", ev1, "bob").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(freeze(ev1).get("frozen").asBoolean()).isFalse();

        // 终态：再同意/再拒绝 409
        assertThat(approve("carol", destructionKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(reject("carol", destructionKey, uniqueKey("CMD"), "另一原因")
                .getResponse().getStatus()).isEqualTo(409);
        // 拒绝原因不可改写
        assertThat(orderDetail(destructionKey).get("rejectReason").asText())
                .isEqualTo("依据不充分");
        // 同键同参重放返回首次结果
        MvcResult replay = reject("bob", destructionKey, rejectKey, "依据不充分");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(rejected.getResponse().getContentAsString());
    }

    @Test
    void approvalRequiresTwoDistinctApproversDifferentFromSubmitter() throws Exception {
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        String destructionKey = uniqueKey("DST");
        createOrder("alice", uniqueKey("CMD"), destructionKey, List.of(ev1), false);

        // 提交人不能审批
        assertThat(approve("alice", destructionKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        // 同一审批人不能同意两次
        assertThat(approve("bob", destructionKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(approve("bob", destructionKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        // 仍 PENDING
        assertThat(orderDetail(destructionKey).get("status").asText()).isEqualTo("PENDING");
        // 第二名互异审批人同意后 APPROVED
        assertThat(approve("carol", destructionKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(orderDetail(destructionKey).get("status").asText()).isEqualTo("APPROVED");
        // APPROVED 后继续同意 409
        assertThat(approve("dave", destructionKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void executeOnlyBySubmitterAfterApprovalAndSucceedsOnce() throws Exception {
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        String destructionKey = uniqueKey("DST");
        createOrder("alice", uniqueKey("CMD"), destructionKey, List.of(ev1), false);

        // PENDING 直接执行 409
        assertThat(execute("alice", destructionKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        approve("bob", destructionKey, uniqueKey("CMD"));
        // 仅一名审批人时执行仍 409
        assertThat(execute("alice", destructionKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        approve("carol", destructionKey, uniqueKey("CMD"));
        // 非提交人不能执行
        assertThat(execute("bob", destructionKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);

        String execKey = uniqueKey("CMD");
        MvcResult first = execute("alice", destructionKey, execKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        // 同键同参重放：返回首次结果，不重复执行
        MvcResult replay = execute("alice", destructionKey, execKey);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 新的 commandKey 再执行：销毁令已是终态 409
        assertThat(execute("alice", destructionKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void destroyedEvidenceRejectsAllWritesButChainStaysReadable() throws Exception {
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        String destructionKey = approveTwiceAndExecute(ev1);

        assertThat(initiateTransfer("alice", ev1, "bob").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(borrow("alice", ev1).getResponse().getStatus()).isEqualTo(409);
        assertThat(inspect("alice", ev1, true).getResponse().getStatus()).isEqualTo(409);
        assertThat(createOrder("alice", uniqueKey("CMD"), uniqueKey("DST"),
                List.of(ev1), true).getResponse().getStatus()).isEqualTo(422);

        // 历史保管链与销毁令明细原样保留可查
        JsonNode chainNode = chain(ev1);
        assertThat(chainNode.get("evidence").get("status").asText()).isEqualTo("DESTROYED");
        JsonNode order = orderDetail(destructionKey);
        assertThat(order.get("status").asText()).isEqualTo("DESTROYED");
        assertThat(order.get("items")).hasSize(1);
    }

    @Test
    void createIdempotencyReplayReorderAndDifferentParamsAndFailureNotOccupyingKey()
            throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);

        String commandKey = uniqueKey("CMD");
        String destructionKey = uniqueKey("DST");
        MvcResult first = createOrder("alice", commandKey, destructionKey, List.of(ev1, ev2), false);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        // 同键同参重放
        MvcResult replay = createOrder("alice", commandKey, destructionKey,
                List.of(ev1, ev2), false);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 证物集合换序视为同参
        MvcResult reordered = createOrder("alice", commandKey, destructionKey,
                List.of(ev2, ev1), false);
        assertThat(reordered.getResponse().getStatus()).isEqualTo(201);
        assertThat(reordered.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 同键异参 409
        assertThat(createOrder("alice", commandKey, destructionKey, List.of(ev1), false)
                .getResponse().getStatus()).isEqualTo(409);
        // 销毁令业务键重复 409
        assertThat(createOrder("alice", uniqueKey("CMD"), destructionKey,
                List.of(ev1, ev2), false).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failedCreateDoesNotOccupyCommandKey() throws Exception {
        String broken = uniqueKey("EV");
        intake("alice", broken);
        inspect("alice", broken, false);

        String commandKey = uniqueKey("CMD");
        String failedKey = uniqueKey("DST");
        assertThat(createOrder("alice", commandKey, failedKey, List.of(broken), false)
                .getResponse().getStatus()).isEqualTo(422);
        // 失败不占键：同一 commandKey 换为合格参数（显式强制）成功
        String successKey = uniqueKey("DST");
        MvcResult success = createOrder("alice", commandKey, successKey,
                List.of(broken), true);
        assertThat(success.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void pendingListShowsOnlyPendingOrdersAndFreezeEndpointReports404ForUnknownEvidence()
            throws Exception {
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        String destructionKey = uniqueKey("DST");
        createOrder("alice", uniqueKey("CMD"), destructionKey, List.of(ev1), false);

        MvcResult pendingResult = mockMvc.perform(get("/api/destruction-orders/pending"))
                .andReturn();
        assertThat(pendingResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode pendingList = objectMapper.readTree(pendingResult.getResponse().getContentAsString());
        assertThat(pendingList.findValuesAsText("destructionKey")).contains(destructionKey);

        approve("bob", destructionKey, uniqueKey("CMD"));
        approve("carol", destructionKey, uniqueKey("CMD"));
        execute("alice", destructionKey, uniqueKey("CMD"));

        JsonNode afterExecution = objectMapper.readTree(
                mockMvc.perform(get("/api/destruction-orders/pending")).andReturn()
                        .getResponse().getContentAsString());
        assertThat(afterExecution.findValuesAsText("destructionKey")).doesNotContain(destructionKey);

        MvcResult freezeMissing = mockMvc.perform(
                get("/api/destruction-orders/evidence/{key}/freeze", uniqueKey("EV"))).andReturn();
        assertThat(freezeMissing.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void createWithEmptyEvidenceListReturns400() throws Exception {
        MvcResult result = createOrder("alice", uniqueKey("CMD"), uniqueKey("DST"),
                List.of(), false);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }
}
