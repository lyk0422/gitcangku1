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
 * 证物跨案移交 API 主流程、失败分支、幂等与撤销测试。
 * 默认场景：alice 在 caseA 入库证物（自动获得 caseA 保管权限），
 * bob 自助登记为空案件 caseB 的首名保管人，alice 签发 caseA->caseB 移交令。
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

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private MvcResult postJson(String url, String actor, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(url)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult intake(String actor, String evidenceKey, String caseKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", caseKey);
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        return postJson("/api/evidence", actor, body);
    }

    private MvcResult grant(String actor, String caseKey, String custodianId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("caseKey", caseKey);
        body.put("custodianId", custodianId);
        return postJson("/api/case-custodians", actor, body);
    }

    private MvcResult createOrder(String actor, String orderVersion, String sourceCase,
                                  String targetCase, LocalDateTime validFrom,
                                  LocalDateTime validUntil) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("orderVersion", orderVersion);
        body.put("sourceCaseKey", sourceCase);
        body.put("targetCaseKey", targetCase);
        body.put("validFrom", validFrom.toString());
        body.put("validUntil", validUntil.toString());
        return postJson("/api/transfer-orders", actor, body);
    }

    private MvcResult revokeOrder(String actor, String orderVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        return postJson("/api/transfer-orders/" + orderVersion + "/revoke", actor, body);
    }

    private MvcResult transfer(String actor, String commandKey, String sourceCase, String targetCase,
                               String orderVersion, String sourceCustodian, String targetCustodian,
                               List<String> evidenceKeys) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("sourceCaseKey", sourceCase);
        body.put("targetCaseKey", targetCase);
        body.put("orderVersion", orderVersion);
        body.put("sourceCustodianId", sourceCustodian);
        body.put("targetCustodianId", targetCustodian);
        body.put("evidenceKeys", evidenceKeys);
        return postJson("/api/case-transfers", actor, body);
    }

    private MvcResult revokeTransfer(String actor, String transferKey, String sourceConfirmer,
                                     String targetConfirmer) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("sourceConfirmerId", sourceConfirmer);
        body.put("targetConfirmerId", targetConfirmer);
        return postJson("/api/case-transfers/" + transferKey + "/revoke", actor, body);
    }

    private MvcResult borrow(String actor, String evidenceKey, String borrower) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", borrower);
        body.put("purpose", "examination");
        body.put("dueAt", utcNow().plusHours(2).toString());
        return postJson("/api/evidence/" + evidenceKey + "/loans", actor, body);
    }

    private MvcResult inspect(String actor, String evidenceKey, boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", passed);
        body.put("note", "routine");
        return postJson("/api/evidence/" + evidenceKey + "/seal-inspections", actor, body);
    }

    private MvcResult initiate(String actor, String evidenceKey, String toCustodian) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", toCustodian);
        return postJson("/api/evidence/" + evidenceKey + "/transfers", actor, body);
    }

    private MvcResult decide(String actor, String evidenceKey, String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        return postJson("/api/evidence/" + evidenceKey + "/transfers/" + action, actor, body);
    }

    /**
     * 标准场景：caseA（alice 保管，含 evidenceKeys 证物）、caseB（bob 保管）、有效移交令。
     */
    private record Scenario(String caseA, String caseB, String orderVersion,
                            List<String> evidenceKeys) {
    }

    private Scenario newScenario(int evidenceCount) throws Exception {
        String caseA = uniqueKey("CASE-A");
        String caseB = uniqueKey("CASE-B");
        String orderVersion = uniqueKey("ORD");
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < evidenceCount; i++) {
            String key = uniqueKey("EV");
            assertThat(intake("alice", key, caseA).getResponse().getStatus()).isEqualTo(201);
            keys.add(key);
        }
        assertThat(grant("bob", caseB, "bob").getResponse().getStatus()).isEqualTo(201);
        assertThat(createOrder("alice", orderVersion, caseA, caseB,
                utcNow().minusHours(1), utcNow().plusHours(1)).getResponse().getStatus())
                .isEqualTo(201);
        return new Scenario(caseA, caseB, orderVersion, keys);
    }

    private MvcResult transfer(Scenario s, String commandKey, List<String> keys) throws Exception {
        return transfer("alice", commandKey, s.caseA(), s.caseB(), s.orderVersion(),
                "alice", "bob", keys);
    }

    private String transferKeyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("transfer").get("transferKey").asText();
    }

    @Test
    void transferCompletesBatchAtomicallyWithDualCaseChainAndSnapshot() throws Exception {
        Scenario s = newScenario(2);
        String ev1 = s.evidenceKeys().get(0);
        String ev2 = s.evidenceKeys().get(1);

        // 故意乱序提交，服务端应按规范化排序落库
        MvcResult result = transfer(s, uniqueKey("CMD"), List.of(ev2, ev1));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode transfer = body.get("transfer");
        assertThat(transfer.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(transfer.get("evidenceCount").asInt()).isEqualTo(2);
        assertThat(transfer.get("sourceCaseKey").asText()).isEqualTo(s.caseA());
        assertThat(transfer.get("targetCaseKey").asText()).isEqualTo(s.caseB());
        assertThat(transfer.get("orderVersion").asText()).isEqualTo(s.orderVersion());
        assertThat(transfer.get("revokedAt").isNull()).isTrue();

        List<String> sorted = s.evidenceKeys().stream().sorted().toList();
        JsonNode items = body.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("evidenceKey").asText()).isEqualTo(sorted.get(0));
        assertThat(items.get(1).get("evidenceKey").asText()).isEqualTo(sorted.get(1));
        assertThat(items.get(0).get("sealVersion").asInt()).isEqualTo(1);
        assertThat(items.get(0).get("sourceCustodianId").asText()).isEqualTo("alice");
        assertThat(items.get(0).get("targetCustodianId").asText()).isEqualTo("bob");
        assertThat(items.get(0).get("orderVersion").asText()).isEqualTo(s.orderVersion());

        // 同一 transferId 下双案链：每证物一条移出 + 一条移入
        String transferKey = transfer.get("transferKey").asText();
        JsonNode chain = body.get("chain");
        assertThat(chain).hasSize(4);
        for (JsonNode entry : chain) {
            assertThat(entry.get("transferKey").asText()).isEqualTo(transferKey);
            assertThat(entry.get("entryKind").asText()).isEqualTo("TRANSFER");
        }
        assertThat(chain.findValuesAsText("direction"))
                .containsExactlyInAnyOrder("OUT", "IN", "OUT", "IN");

        // 目标案件承接归属与保管人；来源案件保留只读链证据
        JsonNode custody = getJson("/api/evidence/" + ev1 + "/custody-chain");
        assertThat(custody.get("evidence").get("caseKey").asText()).isEqualTo(s.caseB());
        assertThat(custody.get("evidence").get("custodianId").asText()).isEqualTo("bob");
        JsonNode evChain = getJson("/api/evidence/" + ev1 + "/case-transfer-chain");
        assertThat(evChain).hasSize(2);
        assertThat(evChain.get(0).get("caseKey").asText()).isEqualTo(s.caseA());
        assertThat(evChain.get(0).get("direction").asText()).isEqualTo("OUT");
        assertThat(evChain.get(1).get("caseKey").asText()).isEqualTo(s.caseB());
        assertThat(evChain.get(1).get("direction").asText()).isEqualTo("IN");
    }

    @Test
    void transferFreezesSealVersionFromInspectionHistory() throws Exception {
        Scenario s = newScenario(1);
        String ev = s.evidenceKeys().get(0);
        assertThat(inspect("alice", ev, true).getResponse().getStatus()).isEqualTo(200);
        assertThat(inspect("alice", ev, true).getResponse().getStatus()).isEqualTo(200);

        MvcResult result = transfer(s, uniqueKey("CMD"), List.of(ev));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("items");
        // 封签版本 = 历史核验次数 + 1
        assertThat(items.get(0).get("sealVersion").asInt()).isEqualTo(3);
    }

    @Test
    void transferWithEvidenceOutsideSourceCaseReturns422AndWritesNothing() throws Exception {
        Scenario s = newScenario(1);
        String evInA = s.evidenceKeys().get(0);
        String caseC = uniqueKey("CASE-C");
        String evInC = uniqueKey("EV");
        assertThat(intake("alice", evInC, caseC).getResponse().getStatus()).isEqualTo(201);

        MvcResult result = transfer(s, uniqueKey("CMD"), List.of(evInA, evInC));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);

        // 整批不写入：evInA 仍属来源案件，无任何跨案移交记录
        JsonNode custody = getJson("/api/evidence/" + evInA + "/custody-chain");
        assertThat(custody.get("evidence").get("caseKey").asText()).isEqualTo(s.caseA());
        assertThat(custody.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        assertThat(getJson("/api/case-transfers?caseKey=" + s.caseA())).isEmpty();
        assertThat(getJson("/api/evidence/" + evInA + "/case-transfer-chain")).isEmpty();
    }

    @Test
    void transferBorrowedEvidenceReturns422() throws Exception {
        Scenario s = newScenario(1);
        String ev = s.evidenceKeys().get(0);
        assertThat(borrow("alice", ev, "carol").getResponse().getStatus()).isEqualTo(200);
        assertThat(transfer(s, uniqueKey("CMD"), List.of(ev)).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void transferSealBrokenEvidenceReturns422() throws Exception {
        Scenario s = newScenario(1);
        String ev = s.evidenceKeys().get(0);
        assertThat(inspect("alice", ev, false).getResponse().getStatus()).isEqualTo(200);
        assertThat(transfer(s, uniqueKey("CMD"), List.of(ev)).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void transferEvidenceInPendingCustodyHandoverReturns422() throws Exception {
        Scenario s = newScenario(1);
        String ev = s.evidenceKeys().get(0);
        assertThat(initiate("alice", ev, "carol").getResponse().getStatus()).isEqualTo(200);
        assertThat(transfer(s, uniqueKey("CMD"), List.of(ev)).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void transferWithSameSourceAndTargetCaseReturns400() throws Exception {
        Scenario s = newScenario(1);
        MvcResult result = transfer("alice", uniqueKey("CMD"), s.caseA(), s.caseA(),
                s.orderVersion(), "alice", "bob", s.evidenceKeys());
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void transferWithDuplicateEvidenceKeysReturns400() throws Exception {
        Scenario s = newScenario(1);
        String ev = s.evidenceKeys().get(0);
        assertThat(transfer(s, uniqueKey("CMD"), List.of(ev, ev)).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void transferWithSameCustodianOnBothSidesReturns409() throws Exception {
        Scenario s = newScenario(1);
        MvcResult result = transfer("alice", uniqueKey("CMD"), s.caseA(), s.caseB(),
                s.orderVersion(), "alice", "alice", s.evidenceKeys());
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void transferByActorOtherThanSourceCustodianReturns403() throws Exception {
        Scenario s = newScenario(1);
        MvcResult result = transfer("mallory", uniqueKey("CMD"), s.caseA(), s.caseB(),
                s.orderVersion(), "alice", "bob", s.evidenceKeys());
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void transferWithTargetCustodianLackingPermissionReturns403() throws Exception {
        Scenario s = newScenario(1);
        MvcResult result = transfer("alice", uniqueKey("CMD"), s.caseA(), s.caseB(),
                s.orderVersion(), "alice", "mallory", s.evidenceKeys());
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void transferWithUnknownOrderReturns403() throws Exception {
        Scenario s = newScenario(1);
        MvcResult result = transfer("alice", uniqueKey("CMD"), s.caseA(), s.caseB(),
                uniqueKey("ORD-MISSING"), "alice", "bob", s.evidenceKeys());
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void transferWithRevokedOrderReturns403() throws Exception {
        Scenario s = newScenario(1);
        assertThat(revokeOrder("alice", s.orderVersion()).getResponse().getStatus()).isEqualTo(200);
        assertThat(transfer(s, uniqueKey("CMD"), s.evidenceKeys()).getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void transferWithExpiredOrNotYetValidOrderReturns403() throws Exception {
        Scenario s = newScenario(2);
        String ev1 = s.evidenceKeys().get(0);
        String ev2 = s.evidenceKeys().get(1);

        String expired = uniqueKey("ORD");
        assertThat(createOrder("alice", expired, s.caseA(), s.caseB(),
                utcNow().minusHours(2), utcNow().minusHours(1)).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(transfer("alice", uniqueKey("CMD"), s.caseA(), s.caseB(), expired,
                "alice", "bob", List.of(ev1)).getResponse().getStatus()).isEqualTo(403);

        String future = uniqueKey("ORD");
        assertThat(createOrder("alice", future, s.caseA(), s.caseB(),
                utcNow().plusHours(1), utcNow().plusHours(2)).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(transfer("alice", uniqueKey("CMD"), s.caseA(), s.caseB(), future,
                "alice", "bob", List.of(ev2)).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void transferWithOrderForDifferentCasePairReturns403() throws Exception {
        Scenario s = newScenario(1);
        String caseC = uniqueKey("CASE-C");
        String otherOrder = uniqueKey("ORD");
        assertThat(createOrder("alice", otherOrder, s.caseA(), caseC,
                utcNow().minusHours(1), utcNow().plusHours(1)).getResponse().getStatus())
                .isEqualTo(201);
        MvcResult result = transfer("alice", uniqueKey("CMD"), s.caseA(), s.caseB(),
                otherOrder, "alice", "bob", s.evidenceKeys());
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void transferIdempotentReplayReturnsFirstSnapshot() throws Exception {
        Scenario s = newScenario(1);
        String commandKey = uniqueKey("CMD");
        MvcResult first = transfer(s, commandKey, s.evidenceKeys());
        MvcResult replay = transfer(s, commandKey, s.evidenceKeys());

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放不产生新链
        assertThat(getJson("/api/evidence/" + s.evidenceKeys().get(0) + "/case-transfer-chain"))
                .hasSize(2);
    }

    @Test
    void transferReplayWithUnsortedListMatchesFirstSnapshot() throws Exception {
        Scenario s = newScenario(2);
        String commandKey = uniqueKey("CMD");
        List<String> sorted = s.evidenceKeys().stream().sorted().toList();
        List<String> reversed = sorted.reversed();
        MvcResult first = transfer(s, commandKey, sorted);
        MvcResult replay = transfer(s, commandKey, reversed);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void transferSameCommandKeyWithDifferentParamsReturns409() throws Exception {
        Scenario s = newScenario(2);
        String commandKey = uniqueKey("CMD");
        assertThat(transfer(s, commandKey, List.of(s.evidenceKeys().get(0)))
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(transfer(s, commandKey, List.of(s.evidenceKeys().get(1)))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failedTransferDoesNotOccupyCommandKey() throws Exception {
        Scenario s = newScenario(1);
        String ev = s.evidenceKeys().get(0);
        String commandKey = uniqueKey("CMD");
        String loanKey = uniqueKey("LOAN");

        Map<String, Object> borrowBody = new LinkedHashMap<>();
        borrowBody.put("commandKey", uniqueKey("CMD"));
        borrowBody.put("loanKey", loanKey);
        borrowBody.put("borrowerId", "carol");
        borrowBody.put("purpose", "examination");
        borrowBody.put("dueAt", utcNow().plusHours(2).toString());
        assertThat(postJson("/api/evidence/" + ev + "/loans", "alice", borrowBody)
                .getResponse().getStatus()).isEqualTo(200);

        // 借出未归还：422，失败不占键
        assertThat(transfer(s, commandKey, List.of(ev)).getResponse().getStatus()).isEqualTo(422);

        Map<String, Object> returnBody = new LinkedHashMap<>();
        returnBody.put("commandKey", uniqueKey("CMD"));
        returnBody.put("loanKey", loanKey);
        returnBody.put("sealIntact", true);
        returnBody.put("note", "returned");
        assertThat(postJson("/api/evidence/" + ev + "/loans/return", "alice", returnBody)
                .getResponse().getStatus()).isEqualTo(200);

        // 同一 commandKey 重试成功
        assertThat(transfer(s, commandKey, List.of(ev)).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void revokeAppendsReverseChainAndRestoresSourceOwnership() throws Exception {
        Scenario s = newScenario(2);
        String ev1 = s.evidenceKeys().get(0);
        MvcResult transferred = transfer(s, uniqueKey("CMD"), s.evidenceKeys());
        assertThat(transferred.getResponse().getStatus()).isEqualTo(201);
        String transferKey = transferKeyOf(transferred);

        MvcResult revoked = revokeTransfer("bob", transferKey, "alice", "bob");
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(revoked.getResponse().getContentAsString());
        JsonNode transfer = body.get("transfer");
        assertThat(transfer.get("status").asText()).isEqualTo("REVOKED");
        assertThat(transfer.get("revokedAt").isNull()).isFalse();
        assertThat(transfer.get("revokeSourceConfirmer").asText()).isEqualTo("alice");
        assertThat(transfer.get("revokeTargetConfirmer").asText()).isEqualTo("bob");

        // 原移交链保留，追加等长反向链
        JsonNode chain = body.get("chain");
        assertThat(chain).hasSize(8);
        long revokeEntries = chain.findValuesAsText("entryKind").stream()
                .filter("REVOKE"::equals).count();
        assertThat(revokeEntries).isEqualTo(4);

        // 证物回到来源案件与来源保管人
        JsonNode custody = getJson("/api/evidence/" + ev1 + "/custody-chain");
        assertThat(custody.get("evidence").get("caseKey").asText()).isEqualTo(s.caseA());
        assertThat(custody.get("evidence").get("custodianId").asText()).isEqualTo("alice");
    }

    @Test
    void revokeAfterSubsequentLoanInTargetCaseReturns409() throws Exception {
        Scenario s = newScenario(1);
        String ev = s.evidenceKeys().get(0);
        String transferKey = transferKeyOf(transfer(s, uniqueKey("CMD"), List.of(ev)));
        assertThat(borrow("bob", ev, "carol").getResponse().getStatus()).isEqualTo(200);
        assertThat(revokeTransfer("alice", transferKey, "alice", "bob")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void revokeAfterSubsequentCustodyHandoverReturns409() throws Exception {
        Scenario s = newScenario(1);
        String ev = s.evidenceKeys().get(0);
        String transferKey = transferKeyOf(transfer(s, uniqueKey("CMD"), List.of(ev)));
        assertThat(initiate("bob", ev, "carol").getResponse().getStatus()).isEqualTo(200);
        assertThat(decide("carol", ev, "accept").getResponse().getStatus()).isEqualTo(200);
        assertThat(revokeTransfer("alice", transferKey, "alice", "bob")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void revokeAfterSubsequentCaseTransferReturns409() throws Exception {
        Scenario s = newScenario(1);
        String ev = s.evidenceKeys().get(0);
        String firstKey = transferKeyOf(transfer(s, uniqueKey("CMD"), List.of(ev)));

        // 目标案件继续跨案移交到 caseC
        String caseC = uniqueKey("CASE-C");
        assertThat(grant("carol", caseC, "carol").getResponse().getStatus()).isEqualTo(201);
        String order2 = uniqueKey("ORD");
        assertThat(createOrder("bob", order2, s.caseB(), caseC,
                utcNow().minusHours(1), utcNow().plusHours(1)).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(transfer("bob", uniqueKey("CMD"), s.caseB(), caseC, order2,
                "bob", "carol", List.of(ev)).getResponse().getStatus()).isEqualTo(201);

        assertThat(revokeTransfer("alice", firstKey, "alice", "bob")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void revokeTwiceReturns409() throws Exception {
        Scenario s = newScenario(1);
        String transferKey = transferKeyOf(transfer(s, uniqueKey("CMD"), s.evidenceKeys()));
        assertThat(revokeTransfer("alice", transferKey, "alice", "bob")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(revokeTransfer("alice", transferKey, "alice", "bob")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void revokeWithSameConfirmerOnBothSidesReturns409() throws Exception {
        Scenario s = newScenario(1);
        String transferKey = transferKeyOf(transfer(s, uniqueKey("CMD"), s.evidenceKeys()));
        assertThat(revokeTransfer("alice", transferKey, "alice", "alice")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void revokeByActorWhoIsNotConfirmerReturns403() throws Exception {
        Scenario s = newScenario(1);
        String transferKey = transferKeyOf(transfer(s, uniqueKey("CMD"), s.evidenceKeys()));
        assertThat(revokeTransfer("mallory", transferKey, "alice", "bob")
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void revokeWithConfirmerLackingCasePermissionReturns403() throws Exception {
        Scenario s = newScenario(1);
        String transferKey = transferKeyOf(transfer(s, uniqueKey("CMD"), s.evidenceKeys()));
        assertThat(revokeTransfer("bob", transferKey, "mallory", "bob")
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void revokeMissingTransferReturns404() throws Exception {
        assertThat(revokeTransfer("alice", uniqueKey("CT-MISSING"), "alice", "bob")
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void orderCreateAndRevokeValidation() throws Exception {
        Scenario s = newScenario(1);
        // 来源与目标相同
        assertThat(createOrder("alice", uniqueKey("ORD"), s.caseA(), s.caseA(),
                utcNow().minusHours(1), utcNow().plusHours(1)).getResponse().getStatus())
                .isEqualTo(400);
        // 有效期非法（含实际值提示）
        MvcResult badWindow = createOrder("alice", uniqueKey("ORD"), s.caseA(), s.caseB(),
                utcNow().plusHours(1), utcNow().minusHours(1));
        assertThat(badWindow.getResponse().getStatus()).isEqualTo(400);
        // 非案件保管人签发
        assertThat(createOrder("mallory", uniqueKey("ORD"), s.caseA(), s.caseB(),
                utcNow().minusHours(1), utcNow().plusHours(1)).getResponse().getStatus())
                .isEqualTo(403);
        // 重复令版本
        assertThat(createOrder("alice", s.orderVersion(), s.caseA(), s.caseB(),
                utcNow().minusHours(1), utcNow().plusHours(1)).getResponse().getStatus())
                .isEqualTo(409);
        // 撤销：不存在 404；非保管人 403；成功 200；重复撤销 409
        assertThat(revokeOrder("alice", uniqueKey("ORD-MISSING")).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(revokeOrder("mallory", s.orderVersion()).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(revokeOrder("alice", s.orderVersion()).getResponse().getStatus()).isEqualTo(200);
        assertThat(revokeOrder("alice", s.orderVersion()).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void custodianGrantRules() throws Exception {
        String caseKey = uniqueKey("CASE-G");
        // 新案件首名保管人自助登记
        assertThat(grant("dave", caseKey, "dave").getResponse().getStatus()).isEqualTo(201);
        // 非保管人不能授权他人
        assertThat(grant("mallory", caseKey, "erin").getResponse().getStatus()).isEqualTo(403);
        // 已有保管人可追加授权
        assertThat(grant("dave", caseKey, "erin").getResponse().getStatus()).isEqualTo(201);
        // 重复授权冲突
        assertThat(grant("dave", caseKey, "erin").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void detailHistoryAndDiagnosticsQueriesAreReadOnly() throws Exception {
        Scenario s = newScenario(2);
        String transferKey = transferKeyOf(transfer(s, uniqueKey("CMD"), s.evidenceKeys()));

        JsonNode detail = getJson("/api/case-transfers/" + transferKey);
        assertThat(detail.get("transfer").get("transferKey").asText()).isEqualTo(transferKey);
        assertThat(detail.get("items")).hasSize(2);
        assertThat(detail.get("chain")).hasSize(4);

        JsonNode history = getJson("/api/case-transfers?caseKey=" + s.caseA());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("transferKey").asText()).isEqualTo(transferKey);

        JsonNode diagnostics = getJson("/api/case-transfers/diagnostics?caseKey=" + s.caseB());
        assertThat(diagnostics.get("totalCount").asLong()).isEqualTo(1);
        assertThat(diagnostics.get("completedCount").asLong()).isEqualTo(1);
        assertThat(diagnostics.get("revokedCount").asLong()).isEqualTo(0);
        // 案件维度链：每证物一条移入条目
        assertThat(diagnostics.get("chainEntryCount").asLong()).isEqualTo(2);
        List<String> custodians = new ArrayList<>();
        diagnostics.get("custodians").forEach(node -> custodians.add(node.asText()));
        assertThat(custodians).contains("bob");

        // 读取不改变状态：重复读取结果一致
        JsonNode again = getJson("/api/case-transfers/diagnostics?caseKey=" + s.caseB());
        assertThat(again).isEqualTo(diagnostics);

        // 撤销后诊断计数变化
        assertThat(revokeTransfer("alice", transferKey, "alice", "bob")
                .getResponse().getStatus()).isEqualTo(200);
        JsonNode afterRevoke = getJson("/api/case-transfers/diagnostics?caseKey=" + s.caseB());
        assertThat(afterRevoke.get("completedCount").asLong()).isEqualTo(0);
        assertThat(afterRevoke.get("revokedCount").asLong()).isEqualTo(1);
        // 撤销后每证物再追加一条移出反向条目
        assertThat(afterRevoke.get("chainEntryCount").asLong()).isEqualTo(4);

        // 案件维度链包含撤销反向条目
        JsonNode caseChain = getJson("/api/case-transfers/chain?caseKey=" + s.caseA());
        assertThat(caseChain).hasSize(4);
        assertThat(caseChain.findValuesAsText("entryKind"))
                .containsExactlyInAnyOrder("TRANSFER", "TRANSFER", "REVOKE", "REVOKE");

        // 明细 404 与证物链 404
        MvcResult missing = mockMvc.perform(get("/api/case-transfers/" + uniqueKey("CT"))).andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        MvcResult missingEv = mockMvc.perform(get("/api/evidence/" + uniqueKey("EV")
                + "/case-transfer-chain")).andReturn();
        assertThat(missingEv.getResponse().getStatus()).isEqualTo(404);
    }
}
