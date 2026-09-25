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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 逾期追缴与借出人冻结 API 测试：逾期实时判定、追缴原子流转、reclaimKey/commandKey 幂等、
 * 冻结与解冻、逾期清单与冻结状态查询（固定时钟驱动，真实 H2 库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReclaimApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    /** 测试基准时刻（UTC）。 */
    private static final Instant BASE = Instant.parse("2026-09-22T08:00:00Z");

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

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

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

    private MvcResult borrow(String actor, String evidenceKey, String loanKey,
                             String borrower, LocalDateTime dueAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", loanKey);
        body.put("borrowerId", borrower);
        body.put("purpose", "鉴定用");
        body.put("dueAt", dueAt.toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult returnLoan(String actor, String evidenceKey, String loanKey,
                                 boolean sealIntact, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", loanKey);
        body.put("sealIntact", sealIntact);
        body.put("note", note);
        return mockMvc.perform(post("/api/evidence/{key}/loans/return", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult reclaim(String actor, String evidenceKey, String commandKey,
                              String reclaimKey, String loanKey, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("reclaimKey", reclaimKey);
        body.put("loanKey", loanKey);
        body.put("note", note);
        return mockMvc.perform(post("/api/evidence/{key}/loans/reclaim", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult reclaim(String actor, String evidenceKey, String reclaimKey,
                              String loanKey) throws Exception {
        return reclaim(actor, evidenceKey, uniqueKey("CMD"), reclaimKey, loanKey, "逾期未还，发起追缴");
    }

    private MvcResult unfreeze(String actor, String borrower, String commandKey,
                               String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("note", note);
        return mockMvc.perform(post("/api/evidence/borrowers/{id}/unfreeze", borrower)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult inspect(String actor, String evidenceKey, boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", passed);
        body.put("note", "回库核验");
        return mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
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

    private JsonNode overdueList() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/loans/overdue")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode reclaimsOf(String borrower) throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/evidence/reclaims/by-borrower/{id}", borrower)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode freezeOf(String borrower) throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/evidence/borrowers/{id}/freeze", borrower)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * 在 BASE 时刻入库并借出，应还时刻 BASE+1h；随后把时钟推到 BASE+2h 形成逾期 60 分钟。
     */
    private String[] overdueLoan(String borrower) throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        fixClock(BASE);
        assertThat(borrow("alice", evidenceKey, loanKey, borrower, utc(BASE.plusSeconds(3600)))
                .getResponse().getStatus()).isEqualTo(200);
        fixClock(BASE.plusSeconds(7200));
        return new String[]{evidenceKey, loanKey};
    }

    @Test
    void reclaimOverdueLoanTransitionsAtomicallyAndWritesImmutableRecord() throws Exception {
        String borrower = uniqueKey("bob");
        String[] keys = overdueLoan(borrower);
        String evidenceKey = keys[0];
        String loanKey = keys[1];
        String reclaimKey = uniqueKey("RCL");

        MvcResult result = reclaim("alice", evidenceKey, reclaimKey, loanKey);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode reclaim = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(reclaim.get("reclaimKey").asText()).isEqualTo(reclaimKey);
        assertThat(reclaim.get("loanKey").asText()).isEqualTo(loanKey);
        assertThat(reclaim.get("evidenceKey").asText()).isEqualTo(evidenceKey);
        assertThat(reclaim.get("custodianId").asText()).isEqualTo("alice");
        assertThat(reclaim.get("borrowerId").asText()).isEqualTo(borrower);
        // 固化原到期时刻与逾期分钟数（应还 BASE+1h，追缴 BASE+2h）
        assertThat(reclaim.get("dueAt").asText()).isEqualTo("2026-09-22T09:00:00");
        assertThat(reclaim.get("overdueMinutes").asLong()).isEqualTo(60);
        assertThat(reclaim.get("note").asText()).isEqualTo("逾期未还，发起追缴");
        assertThat(reclaim.get("reclaimedAt").asText()).isEqualTo("2026-09-22T10:00:00");

        // 同一事务内：借出转 RECLAIMED 终态、证物转在库待核验
        JsonNode view = chain(evidenceKey);
        assertThat(view.get("loans").get(0).get("status").asText()).isEqualTo("RECLAIMED");
        assertThat(view.get("loans").get(0).get("overdue").asBoolean()).isFalse();
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("PENDING_INSPECTION");
        assertThat(view.get("evidence").get("custodianId").asText()).isEqualTo("alice");

        // 追缴记录可按借出人查询；冻结计数为 1，未冻结
        JsonNode reclaims = reclaimsOf(borrower);
        assertThat(reclaims).hasSize(1);
        assertThat(reclaims.get(0).get("reclaimKey").asText()).isEqualTo(reclaimKey);
        JsonNode freeze = freezeOf(borrower);
        assertThat(freeze.get("frozen").asBoolean()).isFalse();
        assertThat(freeze.get("reclaimCount").asInt()).isEqualTo(1);

        // 逾期清单不再包含已追缴借出
        assertThat(overdueList().findValuesAsText("loanKey")).doesNotContain(loanKey);
    }

    @Test
    void reclaimNonOverdueLoanReturns422WithDueAt() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        fixClock(BASE);
        borrow("alice", evidenceKey, loanKey, uniqueKey("bob"), utc(BASE.plusSeconds(3600)));

        // 恰在应还时刻之前 1 秒：未逾期
        fixClock(BASE.plusSeconds(3599));
        MvcResult result = reclaim("alice", evidenceKey, uniqueKey("RCL"), loanKey);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("2026-09-22T09:00");

        // 失败不改变任何状态
        JsonNode view = chain(evidenceKey);
        assertThat(view.get("loans").get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("BORROWED");
    }

    @Test
    void reclaimAtExactlyDueTimeAllowed() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        fixClock(BASE);
        borrow("alice", evidenceKey, loanKey, uniqueKey("bob"), utc(BASE.plusSeconds(3600)));

        // 恰在应还时刻即逾期（now >= dueAt），可追缴，逾期 0 分钟
        fixClock(BASE.plusSeconds(3600));
        MvcResult result = reclaim("alice", evidenceKey, uniqueKey("RCL"), loanKey);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode reclaim = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(reclaim.get("overdueMinutes").asLong()).isEqualTo(0);
    }

    @Test
    void reclaimReturnedLoanReturns409() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        String evidenceKey = keys[0];
        String loanKey = keys[1];
        assertThat(returnLoan("alice", evidenceKey, loanKey, true, "逾期后归还")
                .getResponse().getStatus()).isEqualTo(200);

        assertThat(reclaim("alice", evidenceKey, uniqueKey("RCL"), loanKey)
                .getResponse().getStatus()).isEqualTo(409);
        // 状态保持归还结果
        JsonNode view = chain(evidenceKey);
        assertThat(view.get("loans").get(0).get("status").asText()).isEqualTo("RETURNED");
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void reclaimByNonCustodianReturns409() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        assertThat(reclaim("mallory", keys[0], uniqueKey("RCL"), keys[1])
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(keys[0]).get("loans").get(0).get("status").asText()).isEqualTo("OVERDUE");
    }

    @Test
    void reclaimWithWrongLoanKeyOrEvidenceReturns404() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        String evidenceKey = keys[0];
        String loanKey = keys[1];

        assertThat(reclaim("alice", evidenceKey, uniqueKey("RCL"), uniqueKey("OTHER"))
                .getResponse().getStatus()).isEqualTo(404);
        String otherEvidence = uniqueKey("EV");
        intake("alice", otherEvidence);
        assertThat(reclaim("alice", otherEvidence, uniqueKey("RCL"), loanKey)
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(reclaim("alice", uniqueKey("MISSING"), uniqueKey("RCL"), loanKey)
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void secondReclaimWithDifferentReclaimKeyReturns409ButSameReclaimKeyReplays() throws Exception {
        String borrower = uniqueKey("bob");
        String[] keys = overdueLoan(borrower);
        String evidenceKey = keys[0];
        String loanKey = keys[1];
        String reclaimKey = uniqueKey("RCL");

        MvcResult first = reclaim("alice", evidenceKey, reclaimKey, loanKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        // 同一 reclaimKey 重复提交（换 commandKey）：幂等返回首次结果
        MvcResult replay = reclaim("alice", evidenceKey, reclaimKey, loanKey);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 换 reclaimKey 再次追缴同一借出：409，且只有一条追缴记录
        assertThat(reclaim("alice", evidenceKey, uniqueKey("RCL"), loanKey)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(reclaimsOf(borrower)).hasSize(1);
    }

    @Test
    void reclaimKeyUsedForOtherLoanReturns409() throws Exception {
        String borrower = uniqueKey("bob");
        String[] first = overdueLoan(borrower);
        String reclaimKey = uniqueKey("RCL");
        assertThat(reclaim("alice", first[0], reclaimKey, first[1])
                .getResponse().getStatus()).isEqualTo(200);

        String[] second = overdueLoan(borrower);
        assertThat(reclaim("alice", second[0], reclaimKey, second[1])
                .getResponse().getStatus()).isEqualTo(409);
        // 第二笔借出未被追缴
        assertThat(chain(second[0]).get("loans").get(0).get("status").asText())
                .isEqualTo("OVERDUE");
    }

    @Test
    void reclaimCommandKeyIdempotencyAndFailureDoesNotOccupyKey() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        String evidenceKey = keys[0];
        String loanKey = keys[1];

        // 未逾期时追缴失败（422），不占 commandKey
        fixClock(BASE.plusSeconds(1800));
        String commandKey = uniqueKey("CMD");
        String reclaimKey = uniqueKey("RCL");
        assertThat(reclaim("alice", evidenceKey, commandKey, reclaimKey, loanKey, "提前追缴")
                .getResponse().getStatus()).isEqualTo(422);

        // 逾期后同 commandKey 重试成功
        fixClock(BASE.plusSeconds(7200));
        MvcResult ok = reclaim("alice", evidenceKey, commandKey, reclaimKey, loanKey, "提前追缴");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);

        // 同键同参重放返回首次结果；同键改参 409
        MvcResult replay = reclaim("alice", evidenceKey, commandKey, reclaimKey, loanKey, "提前追缴");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(ok.getResponse().getContentAsString());
        assertThat(reclaim("alice", evidenceKey, commandKey, reclaimKey, loanKey, "改参说明")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void reclaimedEvidenceMustPassInspectionBeforeBorrowAgain() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        String evidenceKey = keys[0];
        assertThat(reclaim("alice", evidenceKey, uniqueKey("RCL"), keys[1])
                .getResponse().getStatus()).isEqualTo(200);

        // 待核验期间禁止借出与交接
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), uniqueKey("carol"),
                utc(BASE.plusSeconds(10800))).getResponse().getStatus()).isEqualTo(409);

        // 既有封条核验流程确认完好：回到 SEALED 后可再次借出
        assertThat(inspect("alice", evidenceKey, true).getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), uniqueKey("carol"),
                utc(BASE.plusSeconds(10800))).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void reclaimedEvidenceFailingInspectionEntersSealBroken() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        String evidenceKey = keys[0];
        reclaim("alice", evidenceKey, uniqueKey("RCL"), keys[1]);

        assertThat(inspect("alice", evidenceKey, false).getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("SEAL_BROKEN");
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), uniqueKey("carol"),
                utc(BASE.plusSeconds(10800))).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void returnOnReclaimedLoanReturns409() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        String evidenceKey = keys[0];
        String loanKey = keys[1];
        reclaim("alice", evidenceKey, uniqueKey("RCL"), loanKey);

        assertThat(returnLoan("alice", evidenceKey, loanKey, true, "追缴后尝试归还")
                .getResponse().getStatus()).isEqualTo(409);
        JsonNode view = chain(evidenceKey);
        assertThat(view.get("loans").get(0).get("status").asText()).isEqualTo("RECLAIMED");
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("PENDING_INSPECTION");
    }

    @Test
    void borrowerFrozenAfterTwoReclaimsAndUnfreezeRestartsCount() throws Exception {
        String borrower = uniqueKey("bob");

        // 第一次追缴：未冻结
        String[] first = overdueLoan(borrower);
        reclaim("alice", first[0], uniqueKey("RCL"), first[1]);
        JsonNode notFrozen = freezeOf(borrower);
        assertThat(notFrozen.get("frozen").asBoolean()).isFalse();
        assertThat(notFrozen.get("reclaimCount").asInt()).isEqualTo(1);

        // 第二次追缴：自动冻结
        String[] second = overdueLoan(borrower);
        reclaim("alice", second[0], uniqueKey("RCL"), second[1]);
        JsonNode frozen = freezeOf(borrower);
        assertThat(frozen.get("frozen").asBoolean()).isTrue();
        assertThat(frozen.get("reclaimCount").asInt()).isEqualTo(2);
        assertThat(frozen.get("frozenBy").asText()).isEqualTo("alice");
        assertThat(frozen.get("reason").asText()).contains("2");

        // 冻结期间新借出申请 403，给出冻结原因与追缴次数
        String ev3 = uniqueKey("EV");
        intake("alice", ev3);
        fixClock(BASE.plusSeconds(7200));
        MvcResult blocked = borrow("alice", ev3, uniqueKey("LOAN"), borrower,
                utc(BASE.plusSeconds(10800)));
        assertThat(blocked.getResponse().getStatus()).isEqualTo(403);
        assertThat(blocked.getResponse().getContentAsString())
                .contains("冻结").contains("2");

        // 触发冻结的保管人不能自行解冻；借出人本人也不能解冻
        assertThat(unfreeze("alice", borrower, uniqueKey("CMD"), "自行解冻")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(unfreeze(borrower, borrower, uniqueKey("CMD"), "借出人解冻")
                .getResponse().getStatus()).isEqualTo(409);

        // 另一名保管人提交说明解冻
        MvcResult unfrozen = unfreeze("carol", borrower, uniqueKey("CMD"), "核实后解冻");
        assertThat(unfrozen.getResponse().getStatus()).isEqualTo(200);
        JsonNode unfrozenBody = objectMapper.readTree(unfrozen.getResponse().getContentAsString());
        assertThat(unfrozenBody.get("borrowerId").asText()).isEqualTo(borrower);
        assertThat(unfrozenBody.get("unfrozenBy").asText()).isEqualTo("carol");

        // 解冻后计数从零重新累计，历史追缴记录保留
        JsonNode after = freezeOf(borrower);
        assertThat(after.get("frozen").asBoolean()).isFalse();
        assertThat(after.get("reclaimCount").asInt()).isEqualTo(0);
        assertThat(reclaimsOf(borrower)).hasSize(2);

        // 解冻后可正常借出
        assertThat(borrow("alice", ev3, uniqueKey("LOAN"), borrower,
                utc(BASE.plusSeconds(10800))).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void unfreezeWhenNotFrozenReturns409() throws Exception {
        assertThat(unfreeze("carol", uniqueKey("nobody"), uniqueKey("CMD"), "无冻结")
                .getResponse().getStatus()).isEqualTo(409);

        // 仅一次追缴未冻结
        String borrower = uniqueKey("bob");
        String[] keys = overdueLoan(borrower);
        reclaim("alice", keys[0], uniqueKey("RCL"), keys[1]);
        assertThat(unfreeze("carol", borrower, uniqueKey("CMD"), "未冻结")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void unfreezeCommandKeyIdempotency() throws Exception {
        String borrower = uniqueKey("bob");
        String[] first = overdueLoan(borrower);
        reclaim("alice", first[0], uniqueKey("RCL"), first[1]);
        String[] second = overdueLoan(borrower);
        reclaim("alice", second[0], uniqueKey("RCL"), second[1]);
        assertThat(freezeOf(borrower).get("frozen").asBoolean()).isTrue();

        String commandKey = uniqueKey("CMD");
        MvcResult first_unfreeze = unfreeze("carol", borrower, commandKey, "核实后解冻");
        assertThat(first_unfreeze.getResponse().getStatus()).isEqualTo(200);
        // 同键同参重放首次结果；同键改参 409
        MvcResult replay = unfreeze("carol", borrower, commandKey, "核实后解冻");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first_unfreeze.getResponse().getContentAsString());
        assertThat(unfreeze("carol", borrower, commandKey, "改参说明")
                .getResponse().getStatus()).isEqualTo(409);
        // 已解冻后再次解冻（新 commandKey）409
        assertThat(unfreeze("carol", borrower, uniqueKey("CMD"), "再次解冻")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void overdueListContainsOnlyCurrentlyOverdueLoans() throws Exception {
        String borrower = uniqueKey("bob");
        // 借出 1：应还 BASE+1h；借出 2：应还 BASE+2h；借出 3：应还 BASE+1h 但已归还
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        String loan1 = uniqueKey("LOAN");
        String loan2 = uniqueKey("LOAN");
        String loan3 = uniqueKey("LOAN");
        intake("alice", ev1);
        intake("alice", ev2);
        intake("alice", ev3);
        fixClock(BASE);
        borrow("alice", ev1, loan1, borrower, utc(BASE.plusSeconds(3600)));
        borrow("alice", ev2, loan2, borrower, utc(BASE.plusSeconds(7200)));
        borrow("alice", ev3, loan3, borrower, utc(BASE.plusSeconds(3600)));
        returnLoan("alice", ev3, loan3, true, "按时归还");

        fixClock(BASE.plusSeconds(5400));
        JsonNode list = overdueList();
        assertThat(list.findValuesAsText("loanKey")).contains(loan1);
        assertThat(list.findValuesAsText("loanKey")).doesNotContain(loan2, loan3);
        for (JsonNode node : list) {
            if (node.get("loanKey").asText().equals(loan1)) {
                assertThat(node.get("status").asText()).isEqualTo("OVERDUE");
                assertThat(node.get("overdue").asBoolean()).isTrue();
            }
        }
    }
}
