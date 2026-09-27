package com.example.starter.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 证物跨案移交 API 测试：主流程、失败分支（400/403/404/409/422）、
 * 整批原子性、幂等重放与撤销边界。数据访问经由真实 H2（MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaseTransferApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    private static final Instant BASE = Instant.parse("2026-09-27T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceClock evidenceClock;

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private void fixClock(Instant instant) {
        evidenceClock.setClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    /**
     * 测试夹具：唯一的来源/目标案件、双方保管人与已登记移交令。
     */
    private record Fixture(String sourceCase, String targetCase,
                           String srcCustodian, String tgtCustodian, String orderVersion) {
    }

    private Fixture newFixture() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Fixture fixture = new Fixture("CASE-S-" + suffix, "CASE-T-" + suffix,
                "src-" + suffix, "tgt-" + suffix, "ORD-" + suffix);
        // 目标案件保管人权限：通过目标案件内一次真实入库获得授权
        assertThat(intake(fixture.tgtCustodian(), uniqueKey("EV-SEED"), fixture.targetCase(), "SEAL-0")
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(registerOrder("registrar", fixture.orderVersion(),
                fixture.sourceCase(), fixture.targetCase()).getResponse().getStatus()).isEqualTo(201);
        return fixture;
    }

    private MvcResult intake(String actor, String evidenceKey, String caseKey, String sealNo) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", caseKey);
        body.put("category", "DOCUMENT");
        body.put("sealNo", sealNo);
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private String intakeSealed(String actor, String caseKey, String sealNo) throws Exception {
        String evidenceKey = uniqueKey("EV");
        assertThat(intake(actor, evidenceKey, caseKey, sealNo).getResponse().getStatus()).isEqualTo(201);
        return evidenceKey;
    }

    private MvcResult registerOrder(String actor, String orderVersion,
                                    String sourceCase, String targetCase) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("orderVersion", orderVersion);
        body.put("sourceCaseKey", sourceCase);
        body.put("targetCaseKey", targetCase);
        return mockMvc.perform(post("/api/case-transfers/orders")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult revokeOrder(String actor, String orderVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        return mockMvc.perform(post("/api/case-transfers/orders/{ver}/revoke", orderVersion)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult transfer(String actor, String requestId, Fixture fixture,
                               List<String> evidenceKeys) throws Exception {
        return transfer(actor, requestId, fixture.sourceCase(), fixture.targetCase(),
                fixture.orderVersion(), fixture.srcCustodian(), fixture.tgtCustodian(), evidenceKeys);
    }

    private MvcResult transfer(String actor, String requestId, String sourceCase, String targetCase,
                               String orderVersion, String srcCustodian, String tgtCustodian,
                               List<String> evidenceKeys) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("sourceCaseKey", sourceCase);
        body.put("targetCaseKey", targetCase);
        body.put("orderVersion", orderVersion);
        body.put("sourceCustodianId", srcCustodian);
        body.put("targetCustodianId", tgtCustodian);
        body.put("evidenceKeys", evidenceKeys);
        return mockMvc.perform(post("/api/case-transfers")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult revokeTransfer(String actor, String transferId, String requestId,
                                     String sourceConfirmBy, String targetConfirmBy) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("sourceConfirmBy", sourceConfirmBy);
        body.put("targetConfirmBy", targetConfirmBy);
        return mockMvc.perform(post("/api/case-transfers/{id}/revoke", transferId)
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

    private MvcResult initiate(String actor, String evidenceKey, String toCustodian) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", toCustodian);
        return mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult borrow(String actor, String evidenceKey, LocalDateTime dueAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", uniqueKey("brw"));
        body.put("purpose", "鉴定用");
        body.put("dueAt", dueAt.toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult returnLoan(String actor, String evidenceKey, String loanKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", loanKey);
        body.put("sealIntact", true);
        body.put("note", "完好归还");
        return mockMvc.perform(post("/api/evidence/{key}/loans/return", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode evidenceOf(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("evidence");
    }

    private JsonNode detail(String transferId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/case-transfers/{id}", transferId)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode diagnostics(String transferId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/case-transfers/{id}/diagnostics", transferId))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode byEvidence(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/case-transfers/by-evidence/{key}", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode byCase(String caseKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/case-transfers/by-case/{key}", caseKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ---------- 移交令 ----------

    @Test
    void registerOrderHappyAndDuplicateAndSameCase() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String order = "ORD-" + suffix;
        MvcResult created = registerOrder("registrar", order, "CASE-A-" + suffix, "CASE-B-" + suffix);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("VALID");
        assertThat(body.get("revokedAt").isNull()).isTrue();

        // 令版本全局唯一
        assertThat(registerOrder("registrar", order, "CASE-C-" + suffix, "CASE-D-" + suffix)
                .getResponse().getStatus()).isEqualTo(409);
        // 来源与目标案件不得相同
        assertThat(registerOrder("registrar", uniqueKey("ORD"), "CASE-X-" + suffix, "CASE-X-" + suffix)
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void revokeOrderInvalidatesVersion() throws Exception {
        Fixture fixture = newFixture();
        assertThat(revokeOrder("registrar", fixture.orderVersion()).getResponse().getStatus())
                .isEqualTo(200);
        // 撤销后令版本失效
        String ev = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-1");
        assertThat(transfer("op", uniqueKey("REQ"), fixture, List.of(ev)).getResponse().getStatus())
                .isEqualTo(409);
        // 重复撤销 409；未知令 404
        assertThat(revokeOrder("registrar", fixture.orderVersion()).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(revokeOrder("registrar", uniqueKey("ORD")).getResponse().getStatus())
                .isEqualTo(404);
    }

    // ---------- 跨案移交主流程 ----------

    @Test
    void transferHappyPathWritesDualChainAndSnapshotAtomically() throws Exception {
        Fixture fixture = newFixture();
        String ev1 = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-A");
        String ev2 = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-B");

        // 乱序提交，服务端规范排序
        MvcResult result = transfer("operator", uniqueKey("REQ"), fixture, List.of(ev2, ev1));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = objectMapper.readTree(result.getResponse().getContentAsString());
        String transferId = view.get("transferId").asText();
        assertThat(transferId).startsWith("CT-");
        assertThat(view.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(view.get("orderVersion").asText()).isEqualTo(fixture.orderVersion());
        assertThat(view.get("sourceCaseKey").asText()).isEqualTo(fixture.sourceCase());
        assertThat(view.get("targetCaseKey").asText()).isEqualTo(fixture.targetCase());
        // 快照按证物键升序固化双方案件、原位置、封签版本
        assertThat(view.get("items")).hasSize(2);
        assertThat(view.get("items").get(0).get("evidenceKey").asText()).isEqualTo(ev1);
        assertThat(view.get("items").get(1).get("evidenceKey").asText()).isEqualTo(ev2);
        assertThat(view.get("items").get(0).get("originLocation").asText())
                .isEqualTo(fixture.sourceCase() + "#" + fixture.srcCustodian());
        assertThat(view.get("items").get(0).get("sealNo").asText()).isEqualTo("SEAL-A");
        assertThat(view.get("items").get(0).get("sourceCustodianId").asText())
                .isEqualTo(fixture.srcCustodian());
        assertThat(view.get("items").get(0).get("targetCustodianId").asText())
                .isEqualTo(fixture.tgtCustodian());
        // 同一 transferId 下每条证物各有移出/移入链
        assertThat(view.get("chain")).hasSize(4);
        assertThat(view.get("chain").findValuesAsText("transferId"))
                .containsOnly(transferId);
        assertThat(view.get("chain").findValuesAsText("direction"))
                .containsExactlyInAnyOrder("OUT", "IN", "OUT", "IN");
        assertThat(view.get("revokedAt").isNull()).isTrue();

        // 证物案件归属与保管人原子切换，状态保持 SEALED
        JsonNode evidence = evidenceOf(ev1);
        assertThat(evidence.get("caseKey").asText()).isEqualTo(fixture.targetCase());
        assertThat(evidence.get("custodianId").asText()).isEqualTo(fixture.tgtCustodian());
        assertThat(evidence.get("status").asText()).isEqualTo("SEALED");

        // 明细 / 历史 / 诊断查询（只读）
        JsonNode detail = detail(transferId);
        assertThat(detail.get("items")).hasSize(2);
        assertThat(byEvidence(ev1)).hasSize(1);
        assertThat(byCase(fixture.sourceCase())).hasSize(1);
        assertThat(byCase(fixture.targetCase())).hasSize(1);
        JsonNode diag = diagnostics(transferId);
        assertThat(diag.get("snapshotCount").asInt()).isEqualTo(2);
        assertThat(diag.get("outCount").asInt()).isEqualTo(2);
        assertThat(diag.get("inCount").asInt()).isEqualTo(2);
        assertThat(diag.get("revoked").asBoolean()).isFalse();
        assertThat(diag.get("balanced").asBoolean()).isTrue();
        assertThat(diag.get("consistent").asBoolean()).isTrue();

        // 目标案件承接后续借出
        fixClock(BASE);
        assertThat(borrow(fixture.tgtCustodian(), ev1,
                LocalDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC))
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void transferSameSourceAndTargetCaseReturns400() throws Exception {
        Fixture fixture = newFixture();
        String ev = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-1");
        assertThat(transfer("op", uniqueKey("REQ"), fixture.sourceCase(), fixture.sourceCase(),
                fixture.orderVersion(), fixture.srcCustodian(), fixture.tgtCustodian(), List.of(ev))
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void transferSameCustodianOnBothSidesReturns409() throws Exception {
        Fixture fixture = newFixture();
        String ev = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-1");
        assertThat(transfer("op", uniqueKey("REQ"), fixture.sourceCase(), fixture.targetCase(),
                fixture.orderVersion(), fixture.srcCustodian(), fixture.srcCustodian(), List.of(ev))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void transferWithUnknownOrMismatchedOrderVersionReturns409() throws Exception {
        Fixture fixture = newFixture();
        String ev = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-1");
        // 未知令版本
        assertThat(transfer("op", uniqueKey("REQ"), fixture.sourceCase(), fixture.targetCase(),
                uniqueKey("ORD"), fixture.srcCustodian(), fixture.tgtCustodian(), List.of(ev))
                .getResponse().getStatus()).isEqualTo(409);
        // 令版本案件对不匹配
        String otherCase = uniqueKey("CASE-O");
        String otherOrder = uniqueKey("ORD");
        assertThat(registerOrder("registrar", otherOrder, fixture.sourceCase(), otherCase)
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(transfer("op", uniqueKey("REQ"), fixture.sourceCase(), fixture.targetCase(),
                otherOrder, fixture.srcCustodian(), fixture.tgtCustodian(), List.of(ev))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void transferCustodianWithoutCasePermissionReturns403() throws Exception {
        Fixture fixture = newFixture();
        String ev = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-1");
        // 来源保管人不具备来源案件权限
        assertThat(transfer("op", uniqueKey("REQ"), fixture.sourceCase(), fixture.targetCase(),
                fixture.orderVersion(), "stranger", fixture.tgtCustodian(), List.of(ev))
                .getResponse().getStatus()).isEqualTo(403);
        // 目标保管人不具备目标案件权限
        assertThat(transfer("op", uniqueKey("REQ"), fixture.sourceCase(), fixture.targetCase(),
                fixture.orderVersion(), fixture.srcCustodian(), "stranger", List.of(ev))
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void transferEvidenceNotInSourceCaseReturns422() throws Exception {
        Fixture fixture = newFixture();
        // 来源保管人先在来源案件获得权限，再校验“证物不属于来源案件”分支
        intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-0");
        String otherCase = uniqueKey("CASE-O");
        String ev = intakeSealed(fixture.srcCustodian(), otherCase, "SEAL-1");
        assertThat(transfer("op", uniqueKey("REQ"), fixture, List.of(ev)).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void transferBorrowedEvidenceReturns422() throws Exception {
        Fixture fixture = newFixture();
        String ev = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-1");
        fixClock(BASE);
        assertThat(borrow(fixture.srcCustodian(), ev,
                LocalDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(transfer("op", uniqueKey("REQ"), fixture, List.of(ev)).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void transferSealBrokenEvidenceReturns422() throws Exception {
        Fixture fixture = newFixture();
        String ev = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-1");
        assertThat(inspect(fixture.srcCustodian(), ev, false).getResponse().getStatus()).isEqualTo(200);
        assertThat(transfer("op", uniqueKey("REQ"), fixture, List.of(ev)).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void transferEvidenceInOtherHandoverReturns422() throws Exception {
        Fixture fixture = newFixture();
        String ev = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-1");
        assertThat(initiate(fixture.srcCustodian(), ev, "someone").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(transfer("op", uniqueKey("REQ"), fixture, List.of(ev)).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void transferMissingEvidenceReturns404() throws Exception {
        Fixture fixture = newFixture();
        assertThat(transfer("op", uniqueKey("REQ"), fixture, List.of(uniqueKey("EV")))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void transferDuplicateEvidenceKeysReturns400() throws Exception {
        Fixture fixture = newFixture();
        String ev = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-1");
        assertThat(transfer("op", uniqueKey("REQ"), fixture, List.of(ev, ev))
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void transferBatchFailureWritesNothing() throws Exception {
        Fixture fixture = newFixture();
        String good = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-G");
        String bad = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-B");
        assertThat(inspect(fixture.srcCustodian(), bad, false).getResponse().getStatus()).isEqualTo(200);

        assertThat(transfer("op", uniqueKey("REQ"), fixture, List.of(good, bad))
                .getResponse().getStatus()).isEqualTo(422);

        // 整批回滚：好证物仍在来源案件且状态不变，查询不到任何半成品链
        JsonNode evidence = evidenceOf(good);
        assertThat(evidence.get("caseKey").asText()).isEqualTo(fixture.sourceCase());
        assertThat(evidence.get("custodianId").asText()).isEqualTo(fixture.srcCustodian());
        assertThat(evidence.get("status").asText()).isEqualTo("SEALED");
        assertThat(byEvidence(good)).isEmpty();
        assertThat(byCase(fixture.sourceCase())).isEmpty();
        assertThat(byCase(fixture.targetCase())).isEmpty();
    }

    @Test
    void transferIdempotentReplayAndConflict() throws Exception {
        Fixture fixture = newFixture();
        String ev = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-1");
        String requestId = uniqueKey("REQ");

        MvcResult first = transfer("op", requestId, fixture, List.of(ev));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        // 同参重放（证物顺序差异不影响规范化指纹）返回首次快照
        MvcResult replay = transfer("op", requestId, fixture, List.of(ev));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        String transferId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("transferId").asText();
        assertThat(detail(transferId).get("chain")).hasSize(2);

        // 同键改参 409
        String ev2 = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-2");
        assertThat(transfer("op", requestId, fixture, List.of(ev2)).getResponse().getStatus())
                .isEqualTo(409);
    }

    // ---------- 撤销 ----------

    @Test
    void revokeTransferAppendsReverseChainAndRestoresSourceCase() throws Exception {
        Fixture fixture = newFixture();
        String ev1 = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-A");
        String ev2 = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-B");
        MvcResult transferred = transfer("op", uniqueKey("REQ"), fixture, List.of(ev1, ev2));
        String transferId = objectMapper.readTree(transferred.getResponse().getContentAsString())
                .get("transferId").asText();

        MvcResult revoked = revokeTransfer("op", transferId, uniqueKey("REQ"),
                fixture.srcCustodian(), fixture.tgtCustodian());
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = objectMapper.readTree(revoked.getResponse().getContentAsString());
        assertThat(view.get("status").asText()).isEqualTo("REVOKED");
        assertThat(view.get("revokedAt").isNull()).isFalse();
        // 原移交链保留，仅追加反向链
        assertThat(view.get("chain")).hasSize(8);
        assertThat(view.get("chain").findValuesAsText("direction"))
                .containsExactlyInAnyOrder("OUT", "IN", "OUT", "IN",
                        "REVOKE_OUT", "REVOKE_IN", "REVOKE_OUT", "REVOKE_IN");

        // 证物退回来源案件与来源保管人
        JsonNode evidence = evidenceOf(ev1);
        assertThat(evidence.get("caseKey").asText()).isEqualTo(fixture.sourceCase());
        assertThat(evidence.get("custodianId").asText()).isEqualTo(fixture.srcCustodian());
        assertThat(evidence.get("status").asText()).isEqualTo("SEALED");

        JsonNode diag = diagnostics(transferId);
        assertThat(diag.get("revoked").asBoolean()).isTrue();
        assertThat(diag.get("revokeOutCount").asInt()).isEqualTo(2);
        assertThat(diag.get("revokeInCount").asInt()).isEqualTo(2);
        assertThat(diag.get("consistent").asBoolean()).isTrue();
    }

    @Test
    void revokeTransferWithSameConfirmerReturns409() throws Exception {
        Fixture fixture = newFixture();
        String ev = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-1");
        String transferId = objectMapper.readTree(transfer("op", uniqueKey("REQ"), fixture, List.of(ev))
                .getResponse().getContentAsString()).get("transferId").asText();
        assertThat(revokeTransfer("op", transferId, uniqueKey("REQ"),
                fixture.srcCustodian(), fixture.srcCustodian()).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void revokeTransferConfirmerWithoutPermissionReturns403() throws Exception {
        Fixture fixture = newFixture();
        String ev = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-1");
        String transferId = objectMapper.readTree(transfer("op", uniqueKey("REQ"), fixture, List.of(ev))
                .getResponse().getContentAsString()).get("transferId").asText();
        assertThat(revokeTransfer("op", transferId, uniqueKey("REQ"),
                "stranger", fixture.tgtCustodian()).getResponse().getStatus()).isEqualTo(403);
        assertThat(revokeTransfer("op", transferId, uniqueKey("REQ"),
                fixture.srcCustodian(), "stranger").getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void revokeUnknownTransferReturns404() throws Exception {
        assertThat(revokeTransfer("op", "CT-nonexistent", uniqueKey("REQ"), "a", "b")
                .getResponse().getStatus()).isEqualTo(404);
        MvcResult detail = mockMvc.perform(get("/api/case-transfers/{id}", "CT-nonexistent"))
                .andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(404);
        MvcResult diag = mockMvc.perform(get("/api/case-transfers/{id}/diagnostics", "CT-nonexistent"))
                .andReturn();
        assertThat(diag.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void revokeTransferTwiceReturns409AndKeepsSingleReverseChain() throws Exception {
        Fixture fixture = newFixture();
        String ev = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-1");
        String transferId = objectMapper.readTree(transfer("op", uniqueKey("REQ"), fixture, List.of(ev))
                .getResponse().getContentAsString()).get("transferId").asText();
        assertThat(revokeTransfer("op", transferId, uniqueKey("REQ"),
                fixture.srcCustodian(), fixture.tgtCustodian()).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(revokeTransfer("op", transferId, uniqueKey("REQ"),
                fixture.srcCustodian(), fixture.tgtCustodian()).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(detail(transferId).get("chain")).hasSize(4);
    }

    @Test
    void revokeTransferAfterFollowUpLoanReturns409() throws Exception {
        Fixture fixture = newFixture();
        String ev = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-1");
        fixClock(BASE);
        String transferId = objectMapper.readTree(transfer("op", uniqueKey("REQ"), fixture, List.of(ev))
                .getResponse().getContentAsString()).get("transferId").asText();

        // 目标案件发生后续借出并归还（时钟推进保证时序）
        fixClock(BASE.plusSeconds(60));
        String loanKey = uniqueKey("LOAN");
        Map<String, Object> borrowBody = new LinkedHashMap<>();
        borrowBody.put("commandKey", uniqueKey("CMD"));
        borrowBody.put("loanKey", loanKey);
        borrowBody.put("borrowerId", uniqueKey("brw"));
        borrowBody.put("purpose", "鉴定用");
        borrowBody.put("dueAt", LocalDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC).toString());
        assertThat(mockMvc.perform(post("/api/evidence/{key}/loans", ev)
                        .header(ACTOR_HEADER, fixture.tgtCustodian())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(borrowBody)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        fixClock(BASE.plusSeconds(120));
        assertThat(returnLoan(fixture.tgtCustodian(), ev, loanKey).getResponse().getStatus())
                .isEqualTo(200);

        // 即使证物已归还且状态恢复，后续借出历史仍禁止撤销
        fixClock(BASE.plusSeconds(180));
        assertThat(revokeTransfer("op", transferId, uniqueKey("REQ"),
                fixture.srcCustodian(), fixture.tgtCustodian()).getResponse().getStatus())
                .isEqualTo(409);
        // 原移交未被撤销
        assertThat(detail(transferId).get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void revokeTransferAfterFollowUpHandoverReturns409() throws Exception {
        Fixture fixture = newFixture();
        String ev = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-1");
        String transferId = objectMapper.readTree(transfer("op", uniqueKey("REQ"), fixture, List.of(ev))
                .getResponse().getContentAsString()).get("transferId").asText();

        // 目标案件保管人发起后续交接并被接受
        assertThat(initiate(fixture.tgtCustodian(), ev, "carol").getResponse().getStatus())
                .isEqualTo(200);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        assertThat(mockMvc.perform(post("/api/evidence/{key}/transfers/accept", ev)
                        .header(ACTOR_HEADER, "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(revokeTransfer("op", transferId, uniqueKey("REQ"),
                fixture.srcCustodian(), fixture.tgtCustodian()).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void revokeTransferIdempotentReplay() throws Exception {
        Fixture fixture = newFixture();
        String ev = intakeSealed(fixture.srcCustodian(), fixture.sourceCase(), "SEAL-1");
        String transferId = objectMapper.readTree(transfer("op", uniqueKey("REQ"), fixture, List.of(ev))
                .getResponse().getContentAsString()).get("transferId").asText();
        String requestId = uniqueKey("REQ");
        MvcResult first = revokeTransfer("op", transferId, requestId,
                fixture.srcCustodian(), fixture.tgtCustodian());
        MvcResult replay = revokeTransfer("op", transferId, requestId,
                fixture.srcCustodian(), fixture.tgtCustodian());
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放不重复追加反向链
        assertThat(detail(transferId).get("chain")).hasSize(4);
    }
}
