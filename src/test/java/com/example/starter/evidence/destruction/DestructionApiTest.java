package com.example.starter.evidence.destruction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 销毁令 API 主流程、入列校验、冻结拦截、双人审批、执行重查与幂等边界测试（真实 H2 数据库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DestructionApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
                                  List<String> evidenceKeys, String legalBasis, String method,
                                  boolean forceBroken) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("destructionKey", destructionKey);
        body.put("evidenceKeys", evidenceKeys);
        body.put("legalBasis", legalBasis);
        body.put("destructionMethod", method);
        body.put("forceIncludeBroken", forceBroken);
        return mockMvc.perform(post("/api/destruction-orders")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult createOrder(String actor, String commandKey, String destructionKey,
                                  List<String> evidenceKeys, boolean forceBroken) throws Exception {
        return createOrder(actor, commandKey, destructionKey, evidenceKeys,
                "LAW-2026-01", "INCINERATION", forceBroken);
    }

    private MvcResult createOrder(String actor, String destructionKey, List<String> evidenceKeys,
                                  boolean forceBroken) throws Exception {
        return createOrder(actor, uniqueKey("CMD"), destructionKey, evidenceKeys,
                "LAW-2026-01", "INCINERATION", forceBroken);
    }

    private MvcResult decide(String actor, String destructionKey, String action,
                             String commandKey, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        if (reason != null) {
            body.put("reason", reason);
        }
        return mockMvc.perform(post("/api/destruction-orders/{key}/{action}", destructionKey, action)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult agree(String actor, String destructionKey) throws Exception {
        return decide(actor, destructionKey, "agree", uniqueKey("CMD"), null);
    }

    private MvcResult reject(String actor, String destructionKey, String reason) throws Exception {
        return decide(actor, destructionKey, "reject", uniqueKey("CMD"), reason);
    }

    private MvcResult execute(String actor, String destructionKey, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(post("/api/destruction-orders/{key}/execute", destructionKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", "borrower-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("purpose", "lab test");
        body.put("dueAt", LocalDateTime.now().plusHours(24).toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult inspect(String actor, String evidenceKey, boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", passed);
        body.put("note", "routine");
        return mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
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

    @Test
    void createOrderSucceedsAndFreezesEvidence() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String orderKey = uniqueKey("DO");

        MvcResult result = createOrder("alice", orderKey, List.of(ev2, ev1), false);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("submitterId").asText()).isEqualTo("alice");
        assertThat(body.get("legalBasis").asText()).isEqualTo("LAW-2026-01");
        assertThat(body.get("destructionMethod").asText()).isEqualTo("INCINERATION");
        // 入列证物按提交原序返回
        assertThat(body.get("evidenceKeys").get(0).asText()).isEqualTo(ev2);
        assertThat(body.get("evidenceKeys").get(1).asText()).isEqualTo(ev1);
        assertThat(body.get("approvals")).isEmpty();

        assertThat(freeze(ev1).get("frozen").asBoolean()).isTrue();
        assertThat(freeze(ev1).get("destructionKey").asText()).isEqualTo(orderKey);
        assertThat(freeze(ev2).get("frozen").asBoolean()).isTrue();
    }

    @Test
    void createWithInvalidItemsReturns422WithPerItemReasonsAndNoOrder() throws Exception {
        String ok = uniqueKey("EV");
        String borrowed = uniqueKey("EV");
        String others = uniqueKey("EV");
        String broken = uniqueKey("EV");
        String missing = uniqueKey("EV");
        intake("alice", ok);
        intake("alice", borrowed);
        intake("bob", others);
        intake("alice", broken);
        borrow("alice", borrowed);
        inspect("alice", broken, false);

        MvcResult result = createOrder("alice", uniqueKey("DO"),
                List.of(ok, borrowed, others, broken, missing), false);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(422);
        List<String> failedKeys = body.get("items").findValuesAsText("evidenceKey");
        // 合格件不在逐件原因中；不合格件按提交原序返回
        assertThat(failedKeys).containsExactly(borrowed, others, broken, missing);
        assertThat(body.get("items").get(0).get("reason").asText()).contains("借出");
        assertThat(body.get("items").get(1).get("reason").asText()).contains("保管人");
        assertThat(body.get("items").get(2).get("reason").asText()).contains("封条");
        assertThat(body.get("items").get(3).get("reason").asText()).contains("不存在");

        // 未创建销毁令：证物均未冻结
        assertThat(freeze(ok).get("frozen").asBoolean()).isFalse();
        MvcResult pending = mockMvc.perform(get("/api/destruction-orders/pending")).andReturn();
        assertThat(pending.getResponse().getStatus()).isEqualTo(200);
        assertThat(pending.getResponse().getContentAsString()).doesNotContain(missing);
    }

    @Test
    void brokenSealRequiresForceFlagAndCanBeIncludedWhenForced() throws Exception {
        String broken = uniqueKey("EV");
        intake("alice", broken);
        inspect("alice", broken, false);

        assertThat(createOrder("alice", uniqueKey("DO"), List.of(broken), false)
                .getResponse().getStatus()).isEqualTo(422);

        MvcResult forced = createOrder("alice", uniqueKey("DO"), List.of(broken), true);
        assertThat(forced.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(forced.getResponse().getContentAsString());
        assertThat(body.get("forceIncludeBroken").asBoolean()).isTrue();
    }

    @Test
    void pendingTransferEvidenceCannotBeListed() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        initiateTransfer("alice", ev, "bob");

        MvcResult result = createOrder("alice", uniqueKey("DO"), List.of(ev), false);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("items")).hasSize(1);
        assertThat(body.get("items").get(0).get("reason").asText()).contains("交接");
    }

    @Test
    void duplicateEvidenceInListReturns400AndEmptyListReturns400() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);

        assertThat(createOrder("alice", uniqueKey("DO"), List.of(ev, ev), false)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(createOrder("alice", uniqueKey("DO"), List.of(), false)
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void twoDistinctApproversApproveOnSecondAgree() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String orderKey = uniqueKey("DO");
        createOrder("alice", orderKey, List.of(ev), false);

        MvcResult first = agree("bob", orderKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(first.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("PENDING");

        // 同一审批人不能重复同意
        assertThat(agree("bob", orderKey).getResponse().getStatus()).isEqualTo(409);
        // 提交人不能审批
        assertThat(agree("alice", orderKey).getResponse().getStatus()).isEqualTo(400);

        MvcResult second = agree("carol", orderKey);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("APPROVED");
        assertThat(body.get("approvals")).hasSize(2);
        assertThat(body.get("approvedAt").isNull()).isFalse();
        // 批准后证物仍冻结
        assertThat(freeze(ev).get("frozen").asBoolean()).isTrue();
    }

    @Test
    void rejectIsTerminalUnfreezesEvidenceAndReasonIsImmutable() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String orderKey = uniqueKey("DO");
        createOrder("alice", orderKey, List.of(ev), false);

        MvcResult rejected = reject("bob", orderKey, "材料存疑");
        assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("REJECTED");
        assertThat(body.get("rejectReason").asText()).isEqualTo("材料存疑");
        assertThat(body.get("rejectedBy").asText()).isEqualTo("bob");

        // 终态：再同意/再拒绝均 409
        assertThat(agree("carol", orderKey).getResponse().getStatus()).isEqualTo(409);
        MvcResult secondReject = reject("carol", orderKey, "另一个原因");
        assertThat(secondReject.getResponse().getStatus()).isEqualTo(409);

        JsonNode detail = orderDetail(orderKey);
        assertThat(detail.get("rejectReason").asText()).isEqualTo("材料存疑");
        assertThat(detail.get("rejectedBy").asText()).isEqualTo("bob");

        // 证物恢复可用：冻结解除且可发起交接
        assertThat(freeze(ev).get("frozen").asBoolean()).isFalse();
        assertThat(initiateTransfer("alice", ev, "bob").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void frozenEvidenceRejectsTransfersLoansInspectionsAndReInclusionWith409() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String orderKey = uniqueKey("DO");
        createOrder("alice", orderKey, List.of(ev1), false);

        MvcResult transfer = initiateTransfer("alice", ev1, "bob");
        assertThat(transfer.getResponse().getStatus()).isEqualTo(409);
        assertThat(transfer.getResponse().getContentAsString()).contains(orderKey);

        MvcResult loan = borrow("alice", ev1);
        assertThat(loan.getResponse().getStatus()).isEqualTo(409);
        assertThat(loan.getResponse().getContentAsString()).contains(orderKey);

        MvcResult inspection = inspect("alice", ev1, true);
        assertThat(inspection.getResponse().getStatus()).isEqualTo(409);
        assertThat(inspection.getResponse().getContentAsString()).contains(orderKey);

        // 再次入列（含与其他证物合并提交）一律 409 并返回冻结它的销毁令
        MvcResult reInclude = createOrder("alice", uniqueKey("DO"), List.of(ev2, ev1), false);
        assertThat(reInclude.getResponse().getStatus()).isEqualTo(409);
        assertThat(reInclude.getResponse().getContentAsString()).contains(orderKey);
        // 整单未创建：ev2 未被冻结
        assertThat(freeze(ev2).get("frozen").asBoolean()).isFalse();
    }

    @Test
    void freezeAlsoHoldsAfterApprovalAndExecuteDestroysEvidenceAndSealsChain() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String orderKey = uniqueKey("DO");
        createOrder("alice", orderKey, List.of(ev), false);
        agree("bob", orderKey);
        agree("carol", orderKey);

        // APPROVED 期间仍冻结
        assertThat(initiateTransfer("alice", ev, "bob").getResponse().getStatus()).isEqualTo(409);

        // 未批准时执行被拒绝
        String pendingEv = uniqueKey("EV");
        intake("alice", pendingEv);
        String pendingOrder = uniqueKey("DO");
        createOrder("alice", pendingOrder, List.of(pendingEv), false);
        assertThat(execute("alice", pendingOrder, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(409);

        // 非提交保管人不能执行
        assertThat(execute("bob", orderKey, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(409);

        MvcResult executed = execute("alice", orderKey, uniqueKey("CMD"));
        assertThat(executed.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(executed.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("DESTROYED");
        assertThat(body.get("destroyedAt").isNull()).isFalse();

        // 证物进入 DESTROYED 终态
        JsonNode frozen = freeze(ev);
        assertThat(frozen.get("frozen").asBoolean()).isFalse();
        assertThat(frozen.get("evidenceStatus").asText()).isEqualTo("DESTROYED");

        // DESTROYED 后禁止任何写操作
        assertThat(initiateTransfer("alice", ev, "bob").getResponse().getStatus()).isEqualTo(409);
        assertThat(borrow("alice", ev).getResponse().getStatus()).isEqualTo(409);
        assertThat(inspect("alice", ev, true).getResponse().getStatus()).isEqualTo(409);

        // 重复执行被拒绝
        assertThat(execute("alice", orderKey, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(409);

        // 原保管链与审批记录原样保留可查
        JsonNode chain = objectMapper.readTree(mockMvc.perform(
                get("/api/evidence/{key}/custody-chain", ev)).andReturn()
                .getResponse().getContentAsString());
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("DESTROYED");
        JsonNode detail = orderDetail(orderKey);
        assertThat(detail.get("approvals")).hasSize(2);
        assertThat(detail.get("evidenceKeys")).hasSize(1);
    }

    @Test
    void executeRecheckRollsBackWhenEvidenceTamperedAfterApproval() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String orderKey = uniqueKey("DO");
        createOrder("alice", orderKey, List.of(ev), false);
        agree("bob", orderKey);
        agree("carol", orderKey);

        // 模拟 APPROVED 之后、执行之前证物状态被外部改动：执行事务重查必须整单 409 回滚。
        jdbcTemplate.update("UPDATE evidence SET status = 'BORROWED' WHERE evidence_key = ?", ev);
        MvcResult failed = execute("alice", orderKey, uniqueKey("CMD"));
        assertThat(failed.getResponse().getStatus()).isEqualTo(409);
        assertThat(failed.getResponse().getContentAsString()).contains(ev);

        // 销毁令与证物状态保持原样
        assertThat(orderDetail(orderKey).get("status").asText()).isEqualTo("APPROVED");
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM evidence WHERE evidence_key = ?", String.class, ev);
        assertThat(status).isEqualTo("BORROWED");

        // 冻结关系丢失同样导致整单 409 回滚
        jdbcTemplate.update("UPDATE evidence SET status = 'SEALED' WHERE evidence_key = ?", ev);
        jdbcTemplate.update("DELETE FROM destruction_order_item WHERE destruction_key = ?", orderKey);
        MvcResult missingFreeze = execute("alice", orderKey, uniqueKey("CMD"));
        assertThat(missingFreeze.getResponse().getStatus()).isEqualTo(409);
        assertThat(orderDetail(orderKey).get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void commandKeyIdempotencyReplaysCreateAgreeAndExecute() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String orderKey = uniqueKey("DO");
        String createCommand = uniqueKey("CMD");

        MvcResult first = createOrder("alice", createCommand, orderKey, List.of(ev1, ev2),
                "LAW-2026-01", "INCINERATION", false);
        // 同键同参、证物集合换序：重放首次结果
        MvcResult replay = createOrder("alice", createCommand, orderKey, List.of(ev2, ev1),
                "LAW-2026-01", "INCINERATION", false);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放响应保留首次提交原序
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString())
                .get("evidenceKeys").get(0).asText()).isEqualTo(ev1);

        // 同键异参 409
        assertThat(createOrder("alice", createCommand, orderKey, List.of(ev1),
                "LAW-2026-01", "INCINERATION", false).getResponse().getStatus()).isEqualTo(409);

        // 失败不占键：首次 422 的 commandKey 在修正入列证物后仍可成功
        String reusedCommand = uniqueKey("CMD");
        String broken = uniqueKey("EV");
        intake("alice", broken);
        inspect("alice", broken, false);
        assertThat(createOrder("alice", reusedCommand, uniqueKey("DO"), List.of(broken), false)
                .getResponse().getStatus()).isEqualTo(422);
        MvcResult retry = createOrder("alice", reusedCommand, uniqueKey("DO"), List.of(broken), true);
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);

        // 同意重放（同键同参）
        String agreeCommand = uniqueKey("CMD");
        MvcResult agreeFirst = decide("bob", orderKey, "agree", agreeCommand, "ok");
        MvcResult agreeReplay = decide("bob", orderKey, "agree", agreeCommand, "ok");
        assertThat(agreeReplay.getResponse().getStatus()).isEqualTo(200);
        assertThat(agreeReplay.getResponse().getContentAsString())
                .isEqualTo(agreeFirst.getResponse().getContentAsString());
        // 同键异参（备注不同）409
        assertThat(decide("bob", orderKey, "agree", agreeCommand, "changed note")
                .getResponse().getStatus()).isEqualTo(409);
        agree("carol", orderKey);

        // 执行重放：最多成功一次，重放返回首次结果
        String executeCommand = uniqueKey("CMD");
        MvcResult execFirst = execute("alice", orderKey, executeCommand);
        MvcResult execReplay = execute("alice", orderKey, executeCommand);
        assertThat(execFirst.getResponse().getStatus()).isEqualTo(200);
        assertThat(execReplay.getResponse().getStatus()).isEqualTo(200);
        assertThat(execReplay.getResponse().getContentAsString())
                .isEqualTo(execFirst.getResponse().getContentAsString());
        Integer destroyedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evidence WHERE status = 'DESTROYED' AND evidence_key IN (?, ?)",
                Integer.class, ev1, ev2);
        assertThat(destroyedCount).isEqualTo(2);
    }

    @Test
    void pendingListFreezeQueryAndMissingOrderBehaveCorrectly() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String orderKey = uniqueKey("DO");
        createOrder("alice", orderKey, List.of(ev), false);

        MvcResult pending = mockMvc.perform(get("/api/destruction-orders/pending")).andReturn();
        assertThat(pending.getResponse().getStatus()).isEqualTo(200);
        assertThat(pending.getResponse().getContentAsString()).contains(orderKey);

        agree("bob", orderKey);
        agree("carol", orderKey);
        MvcResult pendingAgain = mockMvc.perform(get("/api/destruction-orders/pending")).andReturn();
        assertThat(pendingAgain.getResponse().getContentAsString()).doesNotContain(orderKey);

        assertThat(mockMvc.perform(get("/api/destruction-orders/{key}", uniqueKey("DO")))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(
                get("/api/destruction-orders/evidence/{key}/freeze", uniqueKey("EV")))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }
}
