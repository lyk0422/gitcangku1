package com.example.starter.evidence;

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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 证物跨案移交 API 主流程与失败分支测试。
 * 覆盖：移交令登记与有效期（UTC 左闭右开）、批量移交双案链与快照、
 * 422/403/409 失败分支、撤销与反向链、幂等重放与失败不占键、只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaseTransferApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult intake(String actor, String evidenceKey, String caseKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", caseKey);
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-" + evidenceKey.substring(evidenceKey.length() - 4));
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private Map<String, Object> orderBody(String commandKey, String orderVersion,
                                          String sourceCase, String targetCase,
                                          LocalDateTime validFrom, LocalDateTime validTo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("orderVersion", orderVersion);
        body.put("sourceCaseKey", sourceCase);
        body.put("targetCaseKey", targetCase);
        body.put("validFrom", validFrom.toString());
        body.put("validTo", validTo.toString());
        return body;
    }

    private MvcResult registerOrder(String actor, String orderVersion, String sourceCase,
                                    String targetCase, LocalDateTime validFrom,
                                    LocalDateTime validTo) throws Exception {
        return mockMvc.perform(post("/api/case-transfers/orders")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderBody(uniqueKey("CMD"),
                                orderVersion, sourceCase, targetCase, validFrom, validTo))))
                .andReturn();
    }

    /**
     * 登记一个当前有效的移交令（UTC 前后各 1 小时）。
     */
    private String registerValidOrder(String actor, String sourceCase, String targetCase)
            throws Exception {
        String orderVersion = uniqueKey("ORD");
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        MvcResult result = registerOrder(actor, orderVersion, sourceCase, targetCase,
                nowUtc.minusHours(1), nowUtc.plusHours(1));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return orderVersion;
    }

    private MvcResult transfer(String actor, String commandKey, String sourceCase, String targetCase,
                               String orderVersion, String sourceCustodian, String targetCustodian,
                               List<String> evidenceKeys) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("sourceCaseKey", sourceCase);
        body.put("targetCaseKey", targetCase);
        body.put("orderVersion", orderVersion);
        body.put("sourceCustodian", sourceCustodian);
        body.put("targetCustodian", targetCustodian);
        body.put("evidenceKeys", evidenceKeys);
        return mockMvc.perform(post("/api/case-transfers")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult revoke(String actor, String commandKey, String transferId,
                             String sourceConfirmer, String targetConfirmer) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("sourceConfirmer", sourceConfirmer);
        body.put("targetConfirmer", targetConfirmer);
        return mockMvc.perform(post("/api/case-transfers/{id}/revoke", transferId)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode detail(String transferId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/case-transfers/{id}", transferId)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode chainByCase(String caseKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/case-transfers/by-case/{caseKey}", caseKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode historyByEvidence(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/case-transfers/by-evidence/{key}", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode evidenceState(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("evidence");
    }

    private MvcResult borrow(String actor, String evidenceKey, String borrower) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", borrower);
        body.put("purpose", "forensic-review");
        body.put("dueAt", LocalDateTime.now(ZoneOffset.UTC).plusHours(2).toString());
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

    private MvcResult initiateHandover(String actor, String evidenceKey, String toCustodian)
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

    @Test
    void registerOrderCreatesOrderWithUtcWindow() throws Exception {
        String orderVersion = uniqueKey("ORD");
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        MvcResult result = registerOrder("admin", orderVersion, uniqueKey("CASE"),
                uniqueKey("CASE"), nowUtc.minusHours(1), nowUtc.plusHours(1));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("orderVersion").asText()).isEqualTo(orderVersion);
        assertThat(body.get("createdBy").asText()).isEqualTo("admin");
    }

    @Test
    void registerOrderRejectsInvalidWindowAndSameCase() throws Exception {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        String caseA = uniqueKey("CASE");
        // 终点不晚于起点
        assertThat(registerOrder("admin", uniqueKey("ORD"), caseA, uniqueKey("CASE"),
                nowUtc, nowUtc).getResponse().getStatus()).isEqualTo(400);
        // 来源与目标案件相同
        assertThat(registerOrder("admin", uniqueKey("ORD"), caseA, caseA,
                nowUtc.minusHours(1), nowUtc.plusHours(1)).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void transferMovesBatchWritesDualChainAndSnapshot() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, caseA);
        intake("alice", ev2, caseA);
        String orderVersion = registerValidOrder("admin", caseA, caseB);

        // 故意乱序提交，服务端按规范排序处理
        MvcResult result = transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev2, ev1));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        JsonNode transfer = body.get("transfer");
        String transferId = transfer.get("transferId").asText();
        assertThat(transferId).startsWith("CT-");
        assertThat(transfer.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(transfer.get("evidenceCount").asInt()).isEqualTo(2);
        assertThat(transfer.get("sourceCaseKey").asText()).isEqualTo(caseA);
        assertThat(transfer.get("targetCaseKey").asText()).isEqualTo(caseB);
        assertThat(transfer.get("orderVersion").asText()).isEqualTo(orderVersion);
        assertThat(transfer.get("revokedAt").isNull()).isTrue();

        // 快照按规范排序固化双方案件、原位置、封签版本与令版本
        List<String> sortedKeys = List.of(ev1, ev2).stream().sorted().toList();
        JsonNode items = body.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("evidenceKey").asText()).isEqualTo(sortedKeys.get(0));
        assertThat(items.get(0).get("seqNo").asInt()).isEqualTo(1);
        assertThat(items.get(1).get("evidenceKey").asText()).isEqualTo(sortedKeys.get(1));
        assertThat(items.get(1).get("seqNo").asInt()).isEqualTo(2);
        for (JsonNode item : items) {
            assertThat(item.get("transferId").asText()).isEqualTo(transferId);
            assertThat(item.get("sourceCaseKey").asText()).isEqualTo(caseA);
            assertThat(item.get("targetCaseKey").asText()).isEqualTo(caseB);
            assertThat(item.get("sourceCustodian").asText()).isEqualTo("alice");
            assertThat(item.get("targetCustodian").asText()).isEqualTo("bob");
            assertThat(item.get("sealVersion").asInt()).isEqualTo(1);
            assertThat(item.get("orderVersion").asText()).isEqualTo(orderVersion);
            assertThat(item.get("status").asText()).isEqualTo("TRANSFERRED");
        }

        // 双案链：每证物来源 MOVE_OUT + 目标 MOVE_IN，共用同一 transferId
        JsonNode chain = body.get("chain");
        assertThat(chain).hasSize(4);
        JsonNode caseAChain = chainByCase(caseA);
        JsonNode caseBChain = chainByCase(caseB);
        assertThat(caseAChain).hasSize(2);
        assertThat(caseBChain).hasSize(2);
        assertThat(caseAChain.findValuesAsText("entryType")).containsOnly("MOVE_OUT");
        assertThat(caseBChain.findValuesAsText("entryType")).containsOnly("MOVE_IN");
        assertThat(caseAChain.findValuesAsText("transferId")).containsOnly(transferId);

        // 证物当前归属切换到目标案件与目标保管人
        JsonNode ev1State = evidenceState(ev1);
        assertThat(ev1State.get("caseKey").asText()).isEqualTo(caseB);
        assertThat(ev1State.get("custodianId").asText()).isEqualTo("bob");
        assertThat(ev1State.get("status").asText()).isEqualTo("SEALED");

        // 证物跨案历史可查
        assertThat(historyByEvidence(ev1)).hasSize(1);
    }

    @Test
    void transferToSameCaseReturns400() throws Exception {
        String caseA = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseA, uniqueKey("ORD"),
                "alice", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void transferWithSameCustodiansReturns409() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "alice", List.of(ev)).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void transferWithUnknownOrderReturns409() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseB, uniqueKey("ORD"),
                "alice", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void transferWithExpiredOrNotYetValidOrderReturns409() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);

        String expired = uniqueKey("ORD");
        registerOrder("admin", expired, caseA, caseB, nowUtc.minusHours(2), nowUtc.minusHours(1));
        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseB, expired,
                "alice", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(409);

        String notYet = uniqueKey("ORD");
        registerOrder("admin", notYet, caseA, caseB, nowUtc.plusHours(1), nowUtc.plusHours(2));
        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseB, notYet,
                "alice", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(409);

        // 失败不写链：证物仍在来源案件
        assertThat(evidenceState(ev).get("caseKey").asText()).isEqualTo(caseA);
    }

    @Test
    void transferWithMismatchedOrderCasePairReturns409() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String caseC = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        // 令限定 CASE-A -> CASE-C，不能用于 CASE-A -> CASE-B
        String orderVersion = registerValidOrder("admin", caseA, caseC);
        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void transferWithEvidenceNotInSourceCaseReturns422AndWritesNothing() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String caseC = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseC);
        String orderVersion = registerValidOrder("admin", caseA, caseB);

        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(422);
        // 整批不写入任何保管链
        assertThat(chainByCase(caseA)).isEmpty();
        assertThat(chainByCase(caseB)).isEmpty();
        assertThat(historyByEvidence(ev)).isEmpty();
        assertThat(evidenceState(ev).get("caseKey").asText()).isEqualTo(caseC);
    }

    @Test
    void transferWithBorrowedEvidenceReturns422AndWholeBatchRollback() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, caseA);
        intake("alice", ev2, caseA);
        assertThat(borrow("alice", ev2, "borrower-1").getResponse().getStatus()).isEqualTo(200);
        String orderVersion = registerValidOrder("admin", caseA, caseB);

        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev1, ev2)).getResponse().getStatus()).isEqualTo(422);
        // 整批回滚：ev1 也未移交
        assertThat(evidenceState(ev1).get("caseKey").asText()).isEqualTo(caseA);
        assertThat(chainByCase(caseB)).isEmpty();
    }

    @Test
    void transferWithBrokenSealReturns422() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        assertThat(inspect("alice", ev, false).getResponse().getStatus()).isEqualTo(200);
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void transferWithPendingHandoverReturns422() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        assertThat(initiateHandover("alice", ev, "carol").getResponse().getStatus()).isEqualTo(200);
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void transferWithMissingEvidenceReturns404() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "bob", List.of(uniqueKey("EV"))).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void transferWithDuplicateEvidenceInBatchReturns400() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev, ev)).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void transferByNonCustodianReturns403() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        // 来源保管人不是证物当前保管人
        assertThat(transfer("mallory", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "mallory", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void transferToCaseWhereTargetCustodianLacksPermissionReturns403() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        // carol 在目标案件已有登记保管权限（通过入库），bob 没有
        intake("carol", uniqueKey("EV"), caseB);
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(403);
        // carol 作为目标保管人则可以
        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "carol", List.of(ev)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void transferIdempotentReplayReturnsFirstSnapshotRegardlessOfOrder() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, caseA);
        intake("alice", ev2, caseA);
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        String commandKey = uniqueKey("CMD");

        MvcResult first = transfer("alice", commandKey, caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev2, ev1));
        // 同键同参（证物顺序不同，规范化后指纹相同）重放首次快照
        MvcResult replay = transfer("alice", commandKey, caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev1, ev2));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 只写入一次
        assertThat(chainByCase(caseB)).hasSize(2);
    }

    @Test
    void transferSameCommandKeyWithDifferentParamsReturns409() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String caseC = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        String order1 = registerValidOrder("admin", caseA, caseB);
        String order2 = registerValidOrder("admin", caseA, caseC);
        String commandKey = uniqueKey("CMD");
        assertThat(transfer("alice", commandKey, caseA, caseB, order1,
                "alice", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(200);
        assertThat(transfer("alice", commandKey, caseA, caseC, order2,
                "alice", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void transferFailureDoesNotConsumeCommandKey() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        String commandKey = uniqueKey("CMD");

        // 先以相同双方保管人失败（409），失败不占键
        assertThat(transfer("alice", commandKey, caseA, caseB, orderVersion,
                "alice", "alice", List.of(ev)).getResponse().getStatus()).isEqualTo(409);
        // 修正参数后同键可成功
        assertThat(transfer("alice", commandKey, caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void revokeAppendsReverseChainAndRestoresSourceCase() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        MvcResult transferred = transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev));
        String transferId = objectMapper.readTree(transferred.getResponse().getContentAsString())
                .get("transfer").get("transferId").asText();

        MvcResult revoked = revoke("alice", uniqueKey("CMD"), transferId, "alice", "bob");
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(revoked.getResponse().getContentAsString());
        assertThat(body.get("transfer").get("status").asText()).isEqualTo("REVOKED");
        assertThat(body.get("transfer").get("revokedAt").isNull()).isFalse();
        assertThat(body.get("transfer").get("revokeSourceConfirmer").asText()).isEqualTo("alice");
        assertThat(body.get("transfer").get("revokeTargetConfirmer").asText()).isEqualTo("bob");
        assertThat(body.get("items").get(0).get("status").asText()).isEqualTo("REVOKED");

        // 原移交链保留，追加反向链
        JsonNode caseAChain = chainByCase(caseA);
        JsonNode caseBChain = chainByCase(caseB);
        assertThat(caseAChain.findValuesAsText("entryType"))
                .containsExactly("MOVE_OUT", "REVOKE_IN");
        assertThat(caseBChain.findValuesAsText("entryType"))
                .containsExactly("MOVE_IN", "REVOKE_OUT");

        // 证物归属切回来源案件
        JsonNode state = evidenceState(ev);
        assertThat(state.get("caseKey").asText()).isEqualTo(caseA);
        assertThat(state.get("custodianId").asText()).isEqualTo("alice");
        assertThat(state.get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void revokeWithSameConfirmerReturns409() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        MvcResult transferred = transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev));
        String transferId = objectMapper.readTree(transferred.getResponse().getContentAsString())
                .get("transfer").get("transferId").asText();
        assertThat(revoke("alice", uniqueKey("CMD"), transferId, "alice", "alice")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void revokeByWrongConfirmerReturns403() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        MvcResult transferred = transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev));
        String transferId = objectMapper.readTree(transferred.getResponse().getContentAsString())
                .get("transfer").get("transferId").asText();
        assertThat(revoke("mallory", uniqueKey("CMD"), transferId, "mallory", "bob")
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(revoke("mallory", uniqueKey("CMD"), transferId, "alice", "mallory")
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void revokeAfterSubsequentLoanReturns409() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        MvcResult transferred = transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev));
        String transferId = objectMapper.readTree(transferred.getResponse().getContentAsString())
                .get("transfer").get("transferId").asText();

        // 目标案件承接后续借出
        assertThat(borrow("bob", ev, "borrower-2").getResponse().getStatus()).isEqualTo(200);
        assertThat(revoke("alice", uniqueKey("CMD"), transferId, "alice", "bob")
                .getResponse().getStatus()).isEqualTo(409);
        // 原移交保持 COMPLETED
        assertThat(detail(transferId).get("transfer").get("status").asText())
                .isEqualTo("COMPLETED");
    }

    @Test
    void revokeTwiceReturns409() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        MvcResult transferred = transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev));
        String transferId = objectMapper.readTree(transferred.getResponse().getContentAsString())
                .get("transfer").get("transferId").asText();
        assertThat(revoke("alice", uniqueKey("CMD"), transferId, "alice", "bob")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(revoke("alice", uniqueKey("CMD"), transferId, "alice", "bob")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void revokeMissingTransferReturns404() throws Exception {
        assertThat(revoke("alice", uniqueKey("CMD"), uniqueKey("CT"), "alice", "bob")
                .getResponse().getStatus()).isEqualTo(404);
        MvcResult result = mockMvc.perform(get("/api/case-transfers/{id}", uniqueKey("CT")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void snapshotSealVersionReflectsInspectionCount() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        inspect("alice", ev, true);
        inspect("alice", ev, true);
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        MvcResult transferred = transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev));
        JsonNode item = objectMapper.readTree(transferred.getResponse().getContentAsString())
                .get("items").get(0);
        assertThat(item.get("sealVersion").asInt()).isEqualTo(3);
    }

    @Test
    void targetCaseTakesOverSubsequentLoanAndReturn() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion, "alice", "bob", List.of(ev));

        // 移交后来源保管人不能再借出，目标保管人可以
        assertThat(borrow("alice", ev, "borrower-3").getResponse().getStatus()).isEqualTo(409);
        assertThat(borrow("bob", ev, "borrower-3").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void queriesAreReadOnlyAndStable() throws Exception {
        String caseA = uniqueKey("CASE");
        String caseB = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA);
        String orderVersion = registerValidOrder("admin", caseA, caseB);
        MvcResult transferred = transfer("alice", uniqueKey("CMD"), caseA, caseB, orderVersion,
                "alice", "bob", List.of(ev));
        String transferId = objectMapper.readTree(transferred.getResponse().getContentAsString())
                .get("transfer").get("transferId").asText();

        JsonNode first = detail(transferId);
        JsonNode second = detail(transferId);
        assertThat(second).isEqualTo(first);
        // 读取不改变证物状态
        assertThat(evidenceState(ev).get("caseKey").asText()).isEqualTo(caseB);
        assertThat(detail(transferId)).isEqualTo(first);
    }
}
