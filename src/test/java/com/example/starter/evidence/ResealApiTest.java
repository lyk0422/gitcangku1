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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 双人重新封存 API 测试：主流程、失败回滚、幂等边界与历史封条约束，全部基于真实 H2(MySQL 模式)。
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
    private EvidenceClock evidenceClock;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult intake(String actor, String commandKey, String evidenceKey, String sealNo)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", sealNo);
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult intake(String actor, String evidenceKey) throws Exception {
        return intake(actor, uniqueKey("CMD"), evidenceKey, "SEAL-1");
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

    private MvcResult applyReseal(String actor, String evidenceKey, String commandKey,
                                  String resealKey, String witness, String reason,
                                  String newSealNo) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("resealKey", resealKey);
        body.put("witnessId", witness);
        body.put("reason", reason);
        body.put("newSealNo", newSealNo);
        return mockMvc.perform(post("/api/evidence/{key}/reseals", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult applyReseal(String actor, String evidenceKey, String resealKey,
                                  String witness) throws Exception {
        return applyReseal(actor, evidenceKey, uniqueKey("CMD"), resealKey, witness,
                "tampered seal needs renewal", "SEAL-2");
    }

    private MvcResult decideReseal(String actor, String evidenceKey, String resealKey,
                                   String commandKey, String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(post("/api/evidence/{key}/reseals/{rk}/{action}",
                                evidenceKey, resealKey, action)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult borrow(String actor, String evidenceKey, String borrower) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", borrower);
        body.put("purpose", "lab inspection");
        body.put("dueAt", evidenceClock.nowUtc().plusHours(24));
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult returnLoan(String actor, String evidenceKey, String loanKey,
                                 boolean sealIntact) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", loanKey);
        body.put("sealIntact", sealIntact);
        body.put("note", sealIntact ? "intact" : "seal cracked on return");
        return mockMvc.perform(post("/api/evidence/{key}/loans/return", evidenceKey)
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

    private String breakSealAndApply(String evidenceKey, String resealKey, String witness)
            throws Exception {
        intake("alice", evidenceKey);
        assertThat(inspect("alice", evidenceKey, false).getResponse().getStatus()).isEqualTo(200);
        MvcResult applied = applyReseal("alice", evidenceKey, resealKey, witness);
        assertThat(applied.getResponse().getStatus()).isEqualTo(200);
        return applied.getResponse().getContentAsString();
    }

    @Test
    void applyOnBrokenSealCreatesPendingWithoutChangingEvidence() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String resealKey = uniqueKey("RS");
        String body = breakSealAndApply(evidenceKey, resealKey, "carol");

        JsonNode application = objectMapper.readTree(body);
        assertThat(application.get("resealKey").asText()).isEqualTo(resealKey);
        assertThat(application.get("applicantId").asText()).isEqualTo("alice");
        assertThat(application.get("witnessId").asText()).isEqualTo("carol");
        assertThat(application.get("newSealNo").asText()).isEqualTo("SEAL-2");
        assertThat(application.get("status").asText()).isEqualTo("PENDING");
        assertThat(application.get("oldSealNo").isNull()).isTrue();
        assertThat(application.get("confirmedSealNo").isNull()).isTrue();
        assertThat(application.get("decidedAt").isNull()).isTrue();

        // 申请不改变当前封条与异常状态
        JsonNode chainNode = chain(evidenceKey);
        assertThat(chainNode.get("evidence").get("status").asText()).isEqualTo("SEAL_BROKEN");
        assertThat(chainNode.get("evidence").get("sealNo").asText()).isEqualTo("SEAL-1");
        assertThat(chainNode.get("reseals")).hasSize(1);
    }

    @Test
    void witnessConfirmRestoresSealedAndSwapsSealAtomicallyWithSnapshot() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String resealKey = uniqueKey("RS");
        breakSealAndApply(evidenceKey, resealKey, "carol");

        String confirmKey = uniqueKey("CMD");
        MvcResult confirmed = decideReseal("carol", evidenceKey, resealKey, confirmKey, "confirm");
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        JsonNode application = objectMapper.readTree(confirmed.getResponse().getContentAsString());
        assertThat(application.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(application.get("oldSealNo").asText()).isEqualTo("SEAL-1");
        assertThat(application.get("confirmedSealNo").asText()).isEqualTo("SEAL-2");
        assertThat(application.get("decidedAt").isNull()).isFalse();

        JsonNode chainNode = chain(evidenceKey);
        assertThat(chainNode.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chainNode.get("evidence").get("sealNo").asText()).isEqualTo("SEAL-2");
        assertThat(chainNode.get("reseals")).hasSize(1);
        // 历史借出异常结果、旧核验记录保留，不被覆盖
        assertThat(chainNode.get("inspections")).hasSize(1);
        assertThat(chainNode.get("inspections").get(0).get("passed").asBoolean()).isFalse();
    }

    @Test
    void applicantCancelLeavesBrokenSealUntouched() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String resealKey = uniqueKey("RS");
        breakSealAndApply(evidenceKey, resealKey, "carol");

        MvcResult cancelled = decideReseal("alice", evidenceKey, resealKey, uniqueKey("CMD"),
                "cancel");
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        JsonNode application = objectMapper.readTree(cancelled.getResponse().getContentAsString());
        assertThat(application.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(application.get("oldSealNo").isNull()).isTrue();
        assertThat(application.get("confirmedSealNo").isNull()).isTrue();

        JsonNode chainNode = chain(evidenceKey);
        assertThat(chainNode.get("evidence").get("status").asText()).isEqualTo("SEAL_BROKEN");
        assertThat(chainNode.get("evidence").get("sealNo").asText()).isEqualTo("SEAL-1");
    }

    @Test
    void applyOnIntactSealReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("RS"), "carol")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(evidenceKey).get("reseals")).isEmpty();
    }

    @Test
    void applyByNonCustodianReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        inspect("alice", evidenceKey, false);
        assertThat(applyReseal("mallory", evidenceKey, uniqueKey("RS"), "carol")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void applyWithWitnessEqualToSelfReturns400() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        inspect("alice", evidenceKey, false);
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("RS"), "alice")
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void applyWithBlankReasonOrSealReturns400() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        inspect("alice", evidenceKey, false);
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("RS"), "carol",
                "  ", "SEAL-2").getResponse().getStatus()).isEqualTo(400);
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("RS"), "carol",
                "reason", " ").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void applyWithNewSealEqualToHistorySealReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        inspect("alice", evidenceKey, false);
        // 与入库初始封条相同
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("RS"), "carol", "reason", "SEAL-1")
                .getResponse().getStatus()).isEqualTo(409);
        // 失败不留下申请占位
        assertThat(chain(evidenceKey).get("reseals")).isEmpty();

        // 换用合法新封条并确认后，新封条也成为历史，再次异常时不能复用
        String rs1 = uniqueKey("RS");
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"), rs1, "carol",
                "reason", "SEAL-2").getResponse().getStatus()).isEqualTo(200);
        decideReseal("carol", evidenceKey, rs1, uniqueKey("CMD"), "confirm");
        inspect("alice", evidenceKey, false);
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("RS"), "carol", "reason", "SEAL-2")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("RS"), "carol", "reason", "SEAL-1")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void secondPendingApplyReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        breakSealAndApply(evidenceKey, uniqueKey("RS"), "carol");
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("RS"), "dave")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void confirmByNonWitnessReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String resealKey = uniqueKey("RS");
        breakSealAndApply(evidenceKey, resealKey, "carol");
        // 申请人不是见证人，不能自行确认
        assertThat(decideReseal("alice", evidenceKey, resealKey, uniqueKey("CMD"), "confirm")
                .getResponse().getStatus()).isEqualTo(409);
        // 无关第三人不能确认
        assertThat(decideReseal("dave", evidenceKey, resealKey, uniqueKey("CMD"), "confirm")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("SEAL_BROKEN");
    }

    @Test
    void cancelByNonApplicantReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String resealKey = uniqueKey("RS");
        breakSealAndApply(evidenceKey, resealKey, "carol");
        assertThat(decideReseal("carol", evidenceKey, resealKey, uniqueKey("CMD"), "cancel")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(decideReseal("dave", evidenceKey, resealKey, uniqueKey("CMD"), "cancel")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void confirmUnknownResealKeyReturns404AndMissingEvidenceReturns404() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        inspect("alice", evidenceKey, false);
        assertThat(decideReseal("carol", evidenceKey, uniqueKey("RS"), uniqueKey("CMD"), "confirm")
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(decideReseal("carol", uniqueKey("EV"), uniqueKey("RS"), uniqueKey("CMD"),
                "confirm").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void decidedApplicationCannotBeDecidedAgain() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String resealKey = uniqueKey("RS");
        breakSealAndApply(evidenceKey, resealKey, "carol");

        assertThat(decideReseal("carol", evidenceKey, resealKey, uniqueKey("CMD"), "confirm")
                .getResponse().getStatus()).isEqualTo(200);
        // 终态：再次确认或撤销均 409
        assertThat(decideReseal("carol", evidenceKey, resealKey, uniqueKey("CMD"), "confirm")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(decideReseal("alice", evidenceKey, resealKey, uniqueKey("CMD"), "cancel")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void borrowBlockedWhilePendingButAllowedAfterConfirm() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String resealKey = uniqueKey("RS");
        breakSealAndApply(evidenceKey, resealKey, "carol");

        // 确认提交前禁止借出（PENDING 重新封存期间统一 409）
        assertThat(borrow("alice", evidenceKey, "bob").getResponse().getStatus()).isEqualTo(409);

        decideReseal("carol", evidenceKey, resealKey, uniqueKey("CMD"), "confirm");
        // 确认先提交后可按原规则借出
        assertThat(borrow("alice", evidenceKey, "bob").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void fullCycleReSealThenBorrowBreakAndReSealAgain() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        // 借出归还时封条异常 → SEAL_BROKEN（历史借出异常结果保留）
        MvcResult borrowed = borrow("alice", evidenceKey, "bob");
        assertThat(borrowed.getResponse().getStatus()).isEqualTo(200);
        JsonNode loan = objectMapper.readTree(borrowed.getResponse().getContentAsString());
        String loanKey = loan.get("loanKey").asText();
        assertThat(returnLoan("alice", evidenceKey, loanKey, false).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("SEAL_BROKEN");

        String rs1 = uniqueKey("RS");
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"), rs1, "carol",
                "renew", "SEAL-2").getResponse().getStatus()).isEqualTo(200);
        decideReseal("carol", evidenceKey, rs1, uniqueKey("CMD"), "confirm");

        JsonNode afterFirst = chain(evidenceKey);
        assertThat(afterFirst.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(afterFirst.get("evidence").get("sealNo").asText()).isEqualTo("SEAL-2");
        assertThat(afterFirst.get("loans")).hasSize(1);
        assertThat(afterFirst.get("loans").get(0).get("status").asText()).isEqualTo("RETURNED");

        // 再次异常并第二次重新封存，保管人仍为 alice
        inspect("alice", evidenceKey, false);
        String rs2 = uniqueKey("RS");
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"), rs2, "carol",
                "renew again", "SEAL-3").getResponse().getStatus()).isEqualTo(200);
        decideReseal("carol", evidenceKey, rs2, uniqueKey("CMD"), "confirm");

        JsonNode afterSecond = chain(evidenceKey);
        assertThat(afterSecond.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(afterSecond.get("evidence").get("sealNo").asText()).isEqualTo("SEAL-3");
        assertThat(afterSecond.get("reseals")).hasSize(2);
        assertThat(afterSecond.get("reseals").get(0).get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(afterSecond.get("reseals").get(1).get("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void applyIdempotentReplayReturnsFirstResultWithoutDuplicate() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        inspect("alice", evidenceKey, false);
        String commandKey = uniqueKey("CMD");
        String resealKey = uniqueKey("RS");

        MvcResult first = applyReseal("alice", evidenceKey, commandKey, resealKey, "carol",
                "renew", "SEAL-2");
        MvcResult replay = applyReseal("alice", evidenceKey, commandKey, resealKey, "carol",
                "renew", "SEAL-2");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(chain(evidenceKey).get("reseals")).hasSize(1);
    }

    @Test
    void applySameCommandKeyChangedParamsReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        inspect("alice", evidenceKey, false);
        String commandKey = uniqueKey("CMD");
        assertThat(applyReseal("alice", evidenceKey, commandKey, uniqueKey("RS"), "carol",
                "renew", "SEAL-2").getResponse().getStatus()).isEqualTo(200);
        assertThat(applyReseal("alice", evidenceKey, commandKey, uniqueKey("RS"), "dave",
                "renew", "SEAL-2").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failedApplyDoesNotOccupyCommandKey() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        inspect("alice", evidenceKey, false);
        String commandKey = uniqueKey("CMD");
        // 见证人非法 → 400，命令键不被占用
        assertThat(applyReseal("alice", evidenceKey, commandKey, uniqueKey("RS"), "alice",
                "renew", "SEAL-2").getResponse().getStatus()).isEqualTo(400);
        // 同一命令键以合法参数重试成功
        assertThat(applyReseal("alice", evidenceKey, commandKey, uniqueKey("RS"), "carol",
                "renew", "SEAL-2").getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("reseals")).hasSize(1);
    }

    @Test
    void reuseResealKeyWithDifferentCommandKeyReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String resealKey = uniqueKey("RS");
        breakSealAndApply(evidenceKey, resealKey, "carol");
        decideReseal("carol", evidenceKey, resealKey, uniqueKey("CMD"), "confirm");
        inspect("alice", evidenceKey, false);
        // 换命令键但复用同一 resealKey → 409
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"), resealKey, "carol",
                "renew", "SEAL-3").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void confirmReplayDoesNotAffectLaterBorrowOrReBreak() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String resealKey = uniqueKey("RS");
        breakSealAndApply(evidenceKey, resealKey, "carol");
        String confirmKey = uniqueKey("CMD");

        MvcResult first = decideReseal("carol", evidenceKey, resealKey, confirmKey, "confirm");
        MvcResult replay = decideReseal("carol", evidenceKey, resealKey, confirmKey, "confirm");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 重放确认不产生额外终态/半条保管链，且后续借出、归还再次异常、再次封存均正常
        assertThat(chain(evidenceKey).get("reseals")).hasSize(1);
        MvcResult borrowed = borrow("alice", evidenceKey, "bob");
        assertThat(borrowed.getResponse().getStatus()).isEqualTo(200);
        String loanKey = objectMapper.readTree(borrowed.getResponse().getContentAsString())
                .get("loanKey").asText();
        // 借出期间独立核验仍被禁止
        assertThat(inspect("alice", evidenceKey, false).getResponse().getStatus()).isEqualTo(409);
        // 归还时封条异常，证物再次 SEAL_BROKEN
        assertThat(returnLoan("alice", evidenceKey, loanKey, false).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("SEAL_BROKEN");
        // 可再次申请并确认重新封存（使用又一个新封条），历史不受重放影响
        String rs2 = uniqueKey("RS");
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"), rs2, "carol",
                "renew again", "SEAL-3").getResponse().getStatus()).isEqualTo(200);
        assertThat(decideReseal("carol", evidenceKey, rs2, uniqueKey("CMD"), "confirm")
                .getResponse().getStatus()).isEqualTo(200);
        JsonNode finalChain = chain(evidenceKey);
        assertThat(finalChain.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(finalChain.get("evidence").get("sealNo").asText()).isEqualTo("SEAL-3");
        assertThat(finalChain.get("reseals")).hasSize(2);
    }

    @Test
    void resealsHistoryEndpointReturnsApplicationsAndCurrentEvidence() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String resealKey = uniqueKey("RS");
        breakSealAndApply(evidenceKey, resealKey, "carol");

        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/reseals", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("evidence").get("evidenceKey").asText()).isEqualTo(evidenceKey);
        assertThat(body.get("reseals")).hasSize(1);
        assertThat(body.get("reseals").get(0).get("resealKey").asText()).isEqualTo(resealKey);

        MvcResult missing = mockMvc.perform(get("/api/evidence/{key}/reseals", uniqueKey("EV")))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void failedApplyLeavesNoHalfChainOrSealHistory() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        inspect("alice", evidenceKey, false);
        // 新封条与历史重复导致失败
        assertThat(applyReseal("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("RS"), "carol",
                "renew", "SEAL-1").getResponse().getStatus()).isEqualTo(409);
        JsonNode chainNode = chain(evidenceKey);
        assertThat(chainNode.get("reseals")).isEmpty();
        // 证物仍异常、封条不变
        assertThat(chainNode.get("evidence").get("status").asText()).isEqualTo("SEAL_BROKEN");
        assertThat(chainNode.get("evidence").get("sealNo").asText()).isEqualTo("SEAL-1");
    }
}
