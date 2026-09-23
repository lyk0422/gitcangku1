package com.example.starter.evidence;

import com.example.starter.evidence.dto.CustodyChainView;
import com.example.starter.evidence.dto.ResealRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 异常证物双人重新封存 API：主流程、失败分支、回滚与幂等边界测试（真实 H2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResealApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceService evidenceService;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private Map<String, Object> intakeBody(String commandKey, String evidenceKey, String sealNo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", sealNo);
        return body;
    }

    private MvcResult intake(String actor, String evidenceKey, String sealNo) throws Exception {
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                intakeBody(uniqueKey("CMD"), evidenceKey, sealNo))))
                .andReturn();
    }

    private MvcResult breakSeal(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", false);
        body.put("note", "封条破损");
        return mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult applyReseal(String actor, String evidenceKey, String commandKey,
                                  String resealKey, String newSeal, String witness) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("resealKey", resealKey);
        body.put("newSealNo", newSeal);
        body.put("witnessId", witness);
        body.put("reason", "封条破损后双人重新封存");
        return mockMvc.perform(post("/api/evidence/{key}/reseals", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult decideReseal(String actor, String evidenceKey, String action,
                                   String commandKey, String resealKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("resealKey", resealKey);
        return mockMvc.perform(post("/api/evidence/{key}/reseals/{action}", evidenceKey, action)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String brokenEvidence(String custodian) throws Exception {
        String evidenceKey = uniqueKey("EV");
        assertThat(intake(custodian, evidenceKey, "SEAL-1").getResponse().getStatus()).isEqualTo(201);
        assertThat(breakSeal(custodian, evidenceKey).getResponse().getStatus()).isEqualTo(200);
        return evidenceKey;
    }

    @Test
    void applyConfirmFlowRestoresSealedAndSwitchesSealAtomically() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        String resealKey = uniqueKey("RS");

        MvcResult applied = applyReseal("alice", evidenceKey, uniqueKey("CMD"),
                resealKey, "SEAL-2", "bob");
        assertThat(applied.getResponse().getStatus()).isEqualTo(200);
        JsonNode application = objectMapper.readTree(applied.getResponse().getContentAsString());
        assertThat(application.get("status").asText()).isEqualTo("PENDING");
        assertThat(application.get("applicantId").asText()).isEqualTo("alice");
        assertThat(application.get("witnessId").asText()).isEqualTo("bob");
        assertThat(application.get("previousSealNo").asText()).isEqualTo("SEAL-1");
        assertThat(application.get("newSealNo").asText()).isEqualTo("SEAL-2");
        assertThat(application.get("decidedAt").isNull()).isTrue();

        // 待确认期间证物仍异常、封条不变
        JsonNode pendingChain = chain(evidenceKey);
        assertThat(pendingChain.get("evidence").get("status").asText()).isEqualTo("SEAL_BROKEN");
        assertThat(pendingChain.get("evidence").get("sealNo").asText()).isEqualTo("SEAL-1");

        MvcResult confirmed = decideReseal("bob", evidenceKey, "confirm", uniqueKey("CMD"), resealKey);
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        JsonNode decided = objectMapper.readTree(confirmed.getResponse().getContentAsString());
        assertThat(decided.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(decided.get("decidedAt").isNull()).isFalse();
        assertThat(decided.get("createdAt").isNull()).isFalse();

        JsonNode after = chain(evidenceKey);
        assertThat(after.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(after.get("evidence").get("sealNo").asText()).isEqualTo("SEAL-2");
        assertThat(after.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        assertThat(after.get("reseals")).hasSize(1);
        assertThat(after.get("reseals").get(0).get("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void applyOnIntactSealReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, "SEAL-1");
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"),
                uniqueKey("RS"), "SEAL-2", "bob").getResponse().getStatus()).isEqualTo(409);
        // 失败不留下申请占位
        assertThat(chain(evidenceKey).get("reseals")).hasSize(0);
    }

    @Test
    void applyByNonCustodianReturns409() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        assertThat(applyReseal("mallory", evidenceKey, uniqueKey("CMD"),
                uniqueKey("RS"), "SEAL-2", "bob").getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(evidenceKey).get("reseals")).hasSize(0);
    }

    @Test
    void applyWitnessSameAsApplicantReturns400() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"),
                uniqueKey("RS"), "SEAL-2", "alice").getResponse().getStatus()).isEqualTo(400);
        assertThat(chain(evidenceKey).get("reseals")).hasSize(0);
    }

    @Test
    void applyWithBlankReasonReturns400() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("resealKey", uniqueKey("RS"));
        body.put("newSealNo", "SEAL-2");
        body.put("witnessId", "bob");
        body.put("reason", "  ");
        MvcResult result = mockMvc.perform(post("/api/evidence/{key}/reseals", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(chain(evidenceKey).get("reseals")).hasSize(0);
    }

    @Test
    void applyReusingHistoricalSealReturns409() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        // 与当前（破损）封条相同
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"),
                uniqueKey("RS"), "SEAL-1", "bob").getResponse().getStatus()).isEqualTo(409);

        String firstKey = uniqueKey("RS");
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"),
                firstKey, "SEAL-2", "bob").getResponse().getStatus()).isEqualTo(200);
        assertThat(decideReseal("bob", evidenceKey, "confirm", uniqueKey("CMD"), firstKey)
                .getResponse().getStatus()).isEqualTo(200);
        // 再次异常后，SEAL-1 与 SEAL-2 都属历史封条
        breakSeal("alice", evidenceKey);
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"),
                uniqueKey("RS"), "SEAL-1", "carol").getResponse().getStatus()).isEqualTo(409);
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"),
                uniqueKey("RS"), "SEAL-2", "carol").getResponse().getStatus()).isEqualTo(409);
        MvcResult ok = applyReseal("alice", evidenceKey, uniqueKey("CMD"),
                uniqueKey("RS"), "SEAL-3", "carol");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void secondPendingApplicationReturns409() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"),
                uniqueKey("RS"), "SEAL-2", "bob").getResponse().getStatus()).isEqualTo(200);
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"),
                uniqueKey("RS"), "SEAL-3", "carol").getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(evidenceKey).get("reseals")).hasSize(1);
    }

    @Test
    void reuseResealKeyWithDifferentCommandKeyReturns409() throws Exception {
        String ev1 = brokenEvidence("alice");
        String ev2 = brokenEvidence("alice");
        String resealKey = uniqueKey("RS");
        assertThat(applyReseal("alice", ev1, uniqueKey("CMD"), resealKey, "SEAL-2", "bob")
                .getResponse().getStatus()).isEqualTo(200);
        // 换命令键复用 resealKey → 409
        assertThat(applyReseal("alice", ev2, uniqueKey("CMD"), resealKey, "SEAL-9", "carol")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void confirmByNonWitnessReturns409AndCancelByNonApplicantReturns409() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        String resealKey = uniqueKey("RS");
        applyReseal("alice", evidenceKey, uniqueKey("CMD"), resealKey, "SEAL-2", "bob");

        assertThat(decideReseal("carol", evidenceKey, "confirm", uniqueKey("CMD"), resealKey)
                .getResponse().getStatus()).isEqualTo(409);
        // 申请人不能自行确认
        assertThat(decideReseal("alice", evidenceKey, "confirm", uniqueKey("CMD"), resealKey)
                .getResponse().getStatus()).isEqualTo(409);
        // 见证人不能撤销
        assertThat(decideReseal("bob", evidenceKey, "cancel", uniqueKey("CMD"), resealKey)
                .getResponse().getStatus()).isEqualTo(409);

        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("SEAL_BROKEN");
    }

    @Test
    void cancelByApplicantIsTerminalAndKeepsBrokenSeal() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        String resealKey = uniqueKey("RS");
        applyReseal("alice", evidenceKey, uniqueKey("CMD"), resealKey, "SEAL-2", "bob");

        MvcResult cancelled = decideReseal("alice", evidenceKey, "cancel",
                uniqueKey("CMD"), resealKey);
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(cancelled.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("CANCELLED");

        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("SEAL_BROKEN");
        assertThat(chain.get("evidence").get("sealNo").asText()).isEqualTo("SEAL-1");

        // 终态：见证人确认、申请人再撤销均失败
        assertThat(decideReseal("bob", evidenceKey, "confirm", uniqueKey("CMD"), resealKey)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(decideReseal("alice", evidenceKey, "cancel", uniqueKey("CMD"), resealKey)
                .getResponse().getStatus()).isEqualTo(409);

        // 撤销后可重新申请（含曾提议但未启用的封条号）
        MvcResult reapply = applyReseal("alice", evidenceKey, uniqueKey("CMD"),
                uniqueKey("RS"), "SEAL-2", "bob");
        assertThat(reapply.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void decideUnknownResealKeyReturns404() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        assertThat(decideReseal("bob", evidenceKey, "confirm", uniqueKey("CMD"), uniqueKey("RS"))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void applyOnMissingEvidenceReturns404() throws Exception {
        assertThat(applyReseal("alice", uniqueKey("EV"), uniqueKey("CMD"),
                uniqueKey("RS"), "SEAL-2", "bob").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void applyIdempotentReplayReturnsFirstResultAndDoesNotDuplicate() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        String commandKey = uniqueKey("CMD");
        String resealKey = uniqueKey("RS");
        MvcResult first = applyReseal("alice", evidenceKey, commandKey, resealKey, "SEAL-2", "bob");
        MvcResult replay = applyReseal("alice", evidenceKey, commandKey, resealKey, "SEAL-2", "bob");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(chain(evidenceKey).get("reseals")).hasSize(1);
    }

    @Test
    void applySameCommandKeyChangedParamsReturns409() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        String commandKey = uniqueKey("CMD");
        assertThat(applyReseal("alice", evidenceKey, commandKey, uniqueKey("RS"), "SEAL-2", "bob")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(applyReseal("alice", evidenceKey, commandKey, uniqueKey("RS"), "SEAL-3", "bob")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void confirmIdempotentReplayDoesNotReapplySeal() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        String resealKey = uniqueKey("RS");
        applyReseal("alice", evidenceKey, uniqueKey("CMD"), resealKey, "SEAL-2", "bob");
        String commandKey = uniqueKey("CMD");
        MvcResult first = decideReseal("bob", evidenceKey, "confirm", commandKey, resealKey);
        MvcResult replay = decideReseal("bob", evidenceKey, "confirm", commandKey, resealKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(chain(evidenceKey).get("reseals")).hasSize(1);
    }

    @Test
    void afterConfirmCanBorrowByOriginalRulesAndBreakAgain() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        String resealKey = uniqueKey("RS");
        applyReseal("alice", evidenceKey, uniqueKey("CMD"), resealKey, "SEAL-2", "bob");
        String confirmCommandKey = uniqueKey("CMD");
        assertThat(decideReseal("bob", evidenceKey, "confirm", confirmCommandKey, resealKey)
                .getResponse().getStatus()).isEqualTo(200);

        // 确认命令同键重放不影响后续借出或再次异常
        assertThat(decideReseal("bob", evidenceKey, "confirm", confirmCommandKey, resealKey)
                .getResponse().getStatus()).isEqualTo(200);

        // 确认后可按原规则借出
        Map<String, Object> loanBody = new LinkedHashMap<>();
        loanBody.put("commandKey", uniqueKey("CMD"));
        loanBody.put("loanKey", uniqueKey("LOAN"));
        loanBody.put("borrowerId", "carol");
        loanBody.put("purpose", "鉴定用");
        loanBody.put("dueAt", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(2));
        MvcResult borrowed = mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanBody)))
                .andReturn();
        assertThat(borrowed.getResponse().getStatus()).isEqualTo(200);

        // 归还时封条再次异常，之后仍可发起新一轮重新封存
        Map<String, Object> returnBody = new LinkedHashMap<>();
        returnBody.put("commandKey", uniqueKey("CMD"));
        returnBody.put("loanKey", loanBody.get("loanKey"));
        returnBody.put("sealIntact", false);
        returnBody.put("note", "归还时封条破损");
        MvcResult returned = mockMvc.perform(post("/api/evidence/{key}/loans/return", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(returnBody)))
                .andReturn();
        assertThat(returned.getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("SEAL_BROKEN");

        MvcResult secondApply = applyReseal("alice", evidenceKey, uniqueKey("CMD"),
                uniqueKey("RS"), "SEAL-4", "bob");
        assertThat(secondApply.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void borrowBlockedWhileResealPending() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        applyReseal("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("RS"), "SEAL-2", "bob");
        Map<String, Object> loanBody = new LinkedHashMap<>();
        loanBody.put("commandKey", uniqueKey("CMD"));
        loanBody.put("loanKey", uniqueKey("LOAN"));
        loanBody.put("borrowerId", "carol");
        loanBody.put("purpose", "鉴定用");
        loanBody.put("dueAt", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(2));
        MvcResult borrowed = mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanBody)))
                .andReturn();
        assertThat(borrowed.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void fullCycleCanRepeatOnlyThroughResealFlow() throws Exception {
        String evidenceKey = brokenEvidence("alice");
        String firstKey = uniqueKey("RS");
        applyReseal("alice", evidenceKey, uniqueKey("CMD"), firstKey, "SEAL-2", "bob");
        assertThat(decideReseal("bob", evidenceKey, "confirm", uniqueKey("CMD"), firstKey)
                .getResponse().getStatus()).isEqualTo(200);

        // 普通核验通过不能自行解除异常：先再次弄坏，再核验通过，状态仍异常
        breakSeal("alice", evidenceKey);
        Map<String, Object> passBody = new LinkedHashMap<>();
        passBody.put("commandKey", uniqueKey("CMD"));
        passBody.put("passed", true);
        passBody.put("note", "复核");
        mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(passBody)))
                .andReturn();
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("SEAL_BROKEN");

        // 第二轮重新封存：历史封条 SEAL-1/SEAL-2 均不可再用
        String secondKey = uniqueKey("RS");
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"), secondKey, "SEAL-3", "carol")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(decideReseal("carol", evidenceKey, "confirm", uniqueKey("CMD"), secondKey)
                .getResponse().getStatus()).isEqualTo(200);

        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chain.get("evidence").get("sealNo").asText()).isEqualTo("SEAL-3");
        assertThat(chain.get("reseals")).hasSize(2);
        assertThat(chain.get("reseals").get(0).get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(chain.get("reseals").get(1).get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(chain.get("reseals").get(1).get("previousSealNo").asText()).isEqualTo("SEAL-2");
        assertThat(chain.get("reseals").get(1).get("newSealNo").asText()).isEqualTo("SEAL-3");
    }

    @Test
    void serviceViewExposesResealHistory() {
        String evidenceKey = uniqueKey("EV");
        evidenceService.intake("alice", new com.example.starter.evidence.dto.IntakeRequest(
                uniqueKey("CMD"), evidenceKey, "CASE-1", "DOCUMENT", "SEAL-1"), "h");
        evidenceService.inspectSeal("alice", evidenceKey,
                new com.example.starter.evidence.dto.SealInspectionRequest(
                        uniqueKey("CMD"), false, "破损"), "h");
        evidenceService.applyReseal("alice", evidenceKey,
                new ResealRequest(uniqueKey("CMD"), uniqueKey("RS"), "SEAL-2", "bob", "原因"), "h");
        CustodyChainView chainView = evidenceService.custodyChain(evidenceKey);
        assertThat(chainView.reseals()).hasSize(1);
        assertThat(chainView.reseals().get(0).newSealNo()).isEqualTo("SEAL-2");
        assertThat(chainView.reseals().get(0).status()).isEqualTo(ResealStatus.PENDING);
    }
}
