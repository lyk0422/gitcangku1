package com.example.starter.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 逾期追缴与借出人冻结 API 测试：逾期实时判定、追缴原子流转、幂等边界、
 * 冻结/解冻与查询端点（固定时钟驱动，真实 H2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReclaimApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    /** 测试基准时刻（UTC）。 */
    private static final Instant BASE = Instant.parse("2026-09-23T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceClock evidenceClock;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 各场景共用同一内存库，逐场景清理，避免顺序依赖。
     */
    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM reclaim_record");
        jdbc.update("DELETE FROM unfreeze_record");
        jdbc.update("DELETE FROM seal_inspection");
        jdbc.update("DELETE FROM transfer_record");
        jdbc.update("DELETE FROM loan_record");
        jdbc.update("DELETE FROM evidence");
    }

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
        body.put("dueAt", dueAt == null ? null : dueAt.toString());
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

    private MvcResult reclaim(String actor, String evidenceKey, String reclaimKey,
                              String loanKey, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reclaimKey", reclaimKey);
        body.put("loanKey", loanKey);
        body.put("note", note);
        return mockMvc.perform(post("/api/evidence/{key}/loans/reclaim", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult inspect(String actor, String evidenceKey, boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", passed);
        body.put("note", "核验");
        return mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
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

    private JsonNode reclaims(String borrower) throws Exception {
        String url = borrower == null ? "/api/evidence/reclaims"
                : "/api/evidence/reclaims?borrowerId=" + borrower;
        MvcResult result = mockMvc.perform(get(url)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode freezeStatus(String borrower) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/evidence/borrowers/{id}/freeze-status", borrower))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * 在 BASE 时刻借出 1 小时，然后把时钟拨到逾期 30 分钟。
     */
    private String[] overdueLoan(String borrower) throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        fixClock(BASE);
        assertThat(borrow("alice", evidenceKey, loanKey, borrower, utc(BASE.plusSeconds(3600)))
                .getResponse().getStatus()).isEqualTo(200);
        fixClock(BASE.plusSeconds(3600 + 1800));
        return new String[]{evidenceKey, loanKey};
    }

    @Test
    void reclaimTransitionsLoanAndEvidenceAtomicallyAndWritesImmutableRecord() throws Exception {
        String borrower = uniqueKey("bob");
        String[] keys = overdueLoan(borrower);
        String evidenceKey = keys[0];
        String loanKey = keys[1];

        MvcResult result = reclaim("alice", evidenceKey, uniqueKey("REC"), loanKey, "逾期未还，依法追缴");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode reclaim = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(reclaim.get("loanKey").asText()).isEqualTo(loanKey);
        assertThat(reclaim.get("evidenceKey").asText()).isEqualTo(evidenceKey);
        assertThat(reclaim.get("borrowerId").asText()).isEqualTo(borrower);
        assertThat(reclaim.get("custodianId").asText()).isEqualTo("alice");
        assertThat(reclaim.get("dueAt").asText()).isEqualTo("2026-09-23T09:00:00");
        assertThat(reclaim.get("overdueMinutes").asLong()).isEqualTo(30);
        assertThat(reclaim.get("note").asText()).isEqualTo("逾期未还，依法追缴");

        // 同一事务终态：借出 RECLAIMED，证物在库待核验
        JsonNode view = chain(evidenceKey);
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("PENDING_INSPECTION");
        assertThat(view.get("loans").get(0).get("status").asText()).isEqualTo("RECLAIMED");
        assertThat(view.get("loans").get(0).get("effectiveStatus").asText()).isEqualTo("RECLAIMED");

        // 追缴记录不可变且可查询
        JsonNode records = reclaims(borrower);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("loanKey").asText()).isEqualTo(loanKey);
        assertThat(records.get(0).get("overdueMinutes").asLong()).isEqualTo(30);
    }

    @Test
    void reclaimNotOverdueLoanReturns422WithDueTime() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        fixClock(BASE);
        borrow("alice", evidenceKey, loanKey, uniqueKey("bob"), utc(BASE.plusSeconds(3600)));

        // 未到期：422 且错误信息给出到期时刻
        fixClock(BASE.plusSeconds(1800));
        MvcResult result = reclaim("alice", evidenceKey, uniqueKey("REC"), loanKey, "提前追缴");
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("2026-09-23T09:00");

        // 状态不变
        JsonNode view = chain(evidenceKey);
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("BORROWED");
        assertThat(view.get("loans").get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(reclaims(null)).isEmpty();
    }

    @Test
    void reclaimReturnedLoanReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        fixClock(BASE);
        borrow("alice", evidenceKey, loanKey, uniqueKey("bob"), utc(BASE.plusSeconds(3600)));
        assertThat(returnLoan("alice", evidenceKey, loanKey, true, "按期归还")
                .getResponse().getStatus()).isEqualTo(200);

        fixClock(BASE.plusSeconds(7200));
        assertThat(reclaim("alice", evidenceKey, uniqueKey("REC"), loanKey, "追缴")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void reclaimSameLoanTwiceWithDifferentKeysReturns409() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        assertThat(reclaim("alice", keys[0], uniqueKey("REC"), keys[1], "第一次追缴")
                .getResponse().getStatus()).isEqualTo(200);
        // 同一借出记录只能追缴一次
        assertThat(reclaim("alice", keys[0], uniqueKey("REC"), keys[1], "再次追缴")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void reclaimIdempotentReplayByReclaimKey() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        String reclaimKey = uniqueKey("REC");

        MvcResult first = reclaim("alice", keys[0], reclaimKey, keys[1], "追缴说明");
        MvcResult replay = reclaim("alice", keys[0], reclaimKey, keys[1], "追缴说明");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(reclaims(null)).hasSize(1);
    }

    @Test
    void reclaimSameKeyWithChangedParamsReturns409() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        String reclaimKey = uniqueKey("REC");
        assertThat(reclaim("alice", keys[0], reclaimKey, keys[1], "追缴说明")
                .getResponse().getStatus()).isEqualTo(200);
        // 同键改参
        assertThat(reclaim("alice", keys[0], reclaimKey, keys[1], "改参说明")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failedReclaimDoesNotOccupyReclaimKey() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        String reclaimKey = uniqueKey("REC");
        intake("alice", evidenceKey);
        fixClock(BASE);
        borrow("alice", evidenceKey, loanKey, uniqueKey("bob"), utc(BASE.plusSeconds(3600)));

        // 未逾期失败，不占键
        assertThat(reclaim("alice", evidenceKey, reclaimKey, loanKey, "提前追缴")
                .getResponse().getStatus()).isEqualTo(422);
        // 逾期后同键成功
        fixClock(BASE.plusSeconds(3600));
        assertThat(reclaim("alice", evidenceKey, reclaimKey, loanKey, "到期追缴")
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void reclaimByNonCustodianOrBorrowerReturns409() throws Exception {
        String borrower = uniqueKey("bob");
        String[] keys = overdueLoan(borrower);
        assertThat(reclaim("mallory", keys[0], uniqueKey("REC"), keys[1], "无关人员")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(reclaim(borrower, keys[0], uniqueKey("REC"), keys[1], "借出人")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(keys[0]).get("loans").get(0).get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void reclaimWithWrongLoanKeyOrEvidenceReturns404() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        assertThat(reclaim("alice", keys[0], uniqueKey("REC"), uniqueKey("OTHER"), "x")
                .getResponse().getStatus()).isEqualTo(404);
        String otherEvidence = uniqueKey("EV");
        intake("alice", otherEvidence);
        assertThat(reclaim("alice", otherEvidence, uniqueKey("REC"), keys[1], "x")
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void reclaimedEvidenceRequiresSealInspectionBeforeBorrowAgain() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        String evidenceKey = keys[0];
        reclaim("alice", evidenceKey, uniqueKey("REC"), keys[1], "追缴");

        // 待核验期间禁止借出与交接
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), uniqueKey("carol"),
                utc(BASE.plusSeconds(7200))).getResponse().getStatus()).isEqualTo(409);

        // 核验通过回到 SEALED 后可再次借出
        assertThat(inspect("alice", evidenceKey, true).getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), uniqueKey("carol"),
                utc(BASE.plusSeconds(7200))).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void reclaimedEvidenceFailedInspectionEntersSealBroken() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        String evidenceKey = keys[0];
        reclaim("alice", evidenceKey, uniqueKey("REC"), keys[1], "追缴");

        assertThat(inspect("alice", evidenceKey, false).getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("SEAL_BROKEN");
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), uniqueKey("carol"),
                utc(BASE.plusSeconds(7200))).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void returnAfterReclaimReturns409() throws Exception {
        String[] keys = overdueLoan(uniqueKey("bob"));
        reclaim("alice", keys[0], uniqueKey("REC"), keys[1], "追缴");
        // 追缴先提交：归还对已追缴记录返回 409
        assertThat(returnLoan("alice", keys[0], keys[1], true, "迟到的归还")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void overdueListReflectsClockInRealTime() throws Exception {
        String borrower = uniqueKey("bob");
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        fixClock(BASE);
        borrow("alice", evidenceKey, loanKey, borrower, utc(BASE.plusSeconds(3600)));

        fixClock(BASE.plusSeconds(3599));
        assertThat(overdueList()).isEmpty();

        // 恰在到期时刻即逾期，实时判定
        fixClock(BASE.plusSeconds(3600));
        JsonNode list = overdueList();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("loanKey").asText()).isEqualTo(loanKey);
        assertThat(list.get(0).get("effectiveStatus").asText()).isEqualTo("OVERDUE");
        assertThat(list.get(0).get("overdue").asBoolean()).isTrue();

        // 追缴后不再出现在逾期清单
        reclaim("alice", evidenceKey, uniqueKey("REC"), loanKey, "追缴");
        assertThat(overdueList()).isEmpty();
    }

    @Test
    void borrowerFrozenAfterTwoReclaimsAndBorrowReturns403() throws Exception {
        String borrower = uniqueKey("bob");
        // 第一次追缴：未冻结
        String[] keys1 = overdueLoan(borrower);
        reclaim("alice", keys1[0], uniqueKey("REC"), keys1[1], "第一次追缴");
        JsonNode status1 = freezeStatus(borrower);
        assertThat(status1.get("frozen").asBoolean()).isFalse();
        assertThat(status1.get("reclaimCount").asInt()).isEqualTo(1);

        // 第二次追缴：达到阈值自动冻结
        String[] keys2 = overdueLoan(borrower);
        reclaim("alice", keys2[0], uniqueKey("REC"), keys2[1], "第二次追缴");
        JsonNode status2 = freezeStatus(borrower);
        assertThat(status2.get("frozen").asBoolean()).isTrue();
        assertThat(status2.get("reclaimCount").asInt()).isEqualTo(2);
        assertThat(status2.get("totalReclaimCount").asInt()).isEqualTo(2);
        assertThat(status2.get("freezeThreshold").asInt()).isEqualTo(2);

        // 冻结期间新借出申请 403，给出冻结原因与追缴次数
        String ev3 = uniqueKey("EV");
        intake("alice", ev3);
        MvcResult blocked = borrow("alice", ev3, uniqueKey("LOAN"), borrower,
                utc(BASE.plusSeconds(3600 + 1800 + 3600)));
        assertThat(blocked.getResponse().getStatus()).isEqualTo(403);
        assertThat(blocked.getResponse().getContentAsString()).contains("冻结").contains("2");
    }

    @Test
    void unfreezeResetsCountAndKeepsHistory() throws Exception {
        String borrower = uniqueKey("bob");
        String[] keys1 = overdueLoan(borrower);
        reclaim("alice", keys1[0], uniqueKey("REC"), keys1[1], "第一次追缴");
        String[] keys2 = overdueLoan(borrower);
        reclaim("alice", keys2[0], uniqueKey("REC"), keys2[1], "第二次追缴");
        assertThat(freezeStatus(borrower).get("frozen").asBoolean()).isTrue();

        // 另一名保管人解冻
        MvcResult result = unfreeze("carol", borrower, uniqueKey("CMD"), "核实后解冻");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode status = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(status.get("frozen").asBoolean()).isFalse();
        assertThat(status.get("reclaimCount").asInt()).isEqualTo(0);
        assertThat(status.get("totalReclaimCount").asInt()).isEqualTo(2);
        assertThat(status.get("lastUnfrozenBy").asText()).isEqualTo("carol");

        // 历史追缴记录保留
        assertThat(reclaims(borrower)).hasSize(2);

        // 解冻后可再次借出
        String ev3 = uniqueKey("EV");
        intake("alice", ev3);
        fixClock(BASE.plusSeconds(7200));
        assertThat(borrow("alice", ev3, uniqueKey("LOAN"), borrower,
                utc(BASE.plusSeconds(7200 + 3600))).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void unfreezeByBorrowerThemselvesOrWhenNotFrozenReturns409() throws Exception {
        String borrower = uniqueKey("bob");
        // 未冻结不能解冻
        assertThat(unfreeze("carol", borrower, uniqueKey("CMD"), "提前解冻")
                .getResponse().getStatus()).isEqualTo(409);

        String[] keys1 = overdueLoan(borrower);
        reclaim("alice", keys1[0], uniqueKey("REC"), keys1[1], "第一次追缴");
        String[] keys2 = overdueLoan(borrower);
        reclaim("alice", keys2[0], uniqueKey("REC"), keys2[1], "第二次追缴");

        // 借出人不能自行解冻
        assertThat(unfreeze(borrower, borrower, uniqueKey("CMD"), "自行解冻")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(freezeStatus(borrower).get("frozen").asBoolean()).isTrue();
    }

    @Test
    void refreezeAfterUnfreezeRequiresTwoNewReclaims() throws Exception {
        String borrower = uniqueKey("bob");
        String[] keys1 = overdueLoan(borrower);
        reclaim("alice", keys1[0], uniqueKey("REC"), keys1[1], "第一次追缴");
        String[] keys2 = overdueLoan(borrower);
        reclaim("alice", keys2[0], uniqueKey("REC"), keys2[1], "第二次追缴");
        unfreeze("carol", borrower, uniqueKey("CMD"), "解冻");

        // 解冻后一次追缴不再冻结（计数从零重新累计）
        String[] keys3 = overdueLoan(borrower);
        reclaim("alice", keys3[0], uniqueKey("REC"), keys3[1], "解冻后第一次追缴");
        JsonNode status = freezeStatus(borrower);
        assertThat(status.get("frozen").asBoolean()).isFalse();
        assertThat(status.get("reclaimCount").asInt()).isEqualTo(1);
        assertThat(status.get("totalReclaimCount").asInt()).isEqualTo(3);

        // 解冻后第二次追缴再次冻结
        String[] keys4 = overdueLoan(borrower);
        reclaim("alice", keys4[0], uniqueKey("REC"), keys4[1], "解冻后第二次追缴");
        assertThat(freezeStatus(borrower).get("frozen").asBoolean()).isTrue();
    }

    @Test
    void unfreezeIdempotentReplayReturnsFirstResult() throws Exception {
        String borrower = uniqueKey("bob");
        String[] keys1 = overdueLoan(borrower);
        reclaim("alice", keys1[0], uniqueKey("REC"), keys1[1], "第一次追缴");
        String[] keys2 = overdueLoan(borrower);
        reclaim("alice", keys2[0], uniqueKey("REC"), keys2[1], "第二次追缴");

        String commandKey = uniqueKey("CMD");
        MvcResult first = unfreeze("carol", borrower, commandKey, "核实后解冻");
        MvcResult replay = unfreeze("carol", borrower, commandKey, "核实后解冻");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 同键改参 409
        assertThat(unfreeze("carol", borrower, commandKey, "改参解冻")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void reclaimsQueryFiltersByBorrower() throws Exception {
        String borrower1 = uniqueKey("bob");
        String borrower2 = uniqueKey("carol");
        String[] keys1 = overdueLoan(borrower1);
        reclaim("alice", keys1[0], uniqueKey("REC"), keys1[1], "追缴一");
        String[] keys2 = overdueLoan(borrower2);
        reclaim("alice", keys2[0], uniqueKey("REC"), keys2[1], "追缴二");

        JsonNode all = reclaims(null);
        assertThat(all).hasSize(2);
        JsonNode only1 = reclaims(borrower1);
        assertThat(only1).hasSize(1);
        assertThat(only1.get(0).get("borrowerId").asText()).isEqualTo(borrower1);
    }
}
