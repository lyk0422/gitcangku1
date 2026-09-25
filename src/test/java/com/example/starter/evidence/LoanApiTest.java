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
 * 限时借出与归还核验 API 测试：主流程、失败回滚、幂等边界与逾期标识（固定时钟驱动）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoanApiTest {

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

    private MvcResult borrow(String actor, String evidenceKey, String commandKey, String loanKey,
                             String borrower, String purpose, LocalDateTime dueAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("loanKey", loanKey);
        body.put("borrowerId", borrower);
        body.put("purpose", purpose);
        body.put("dueAt", dueAt == null ? null : dueAt.toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult borrow(String actor, String evidenceKey, String loanKey,
                             String borrower, LocalDateTime dueAt) throws Exception {
        return borrow(actor, evidenceKey, uniqueKey("CMD"), loanKey, borrower, "鉴定用", dueAt);
    }

    private MvcResult returnLoan(String actor, String evidenceKey, String commandKey,
                                 String loanKey, boolean sealIntact, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("loanKey", loanKey);
        body.put("sealIntact", sealIntact);
        body.put("note", note);
        return mockMvc.perform(post("/api/evidence/{key}/loans/return", evidenceKey)
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

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode activeLoans(String borrower) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/loans/by-borrower/{borrowerId}", borrower))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void borrowEntersBorrowedStateAndKeepsCustodian() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);

        MvcResult result = borrow("alice", evidenceKey, loanKey, "bob",
                utc(BASE.plusSeconds(3600)));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode loan = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(loan.get("loanKey").asText()).isEqualTo(loanKey);
        assertThat(loan.get("evidenceKey").asText()).isEqualTo(evidenceKey);
        assertThat(loan.get("custodianId").asText()).isEqualTo("alice");
        assertThat(loan.get("borrowerId").asText()).isEqualTo("bob");
        assertThat(loan.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(loan.get("overdue").asBoolean()).isFalse();
        assertThat(loan.get("returnedAt").isNull()).isTrue();
        assertThat(loan.get("loanAt").asText()).isEqualTo("2026-09-22T08:00:00");

        JsonNode view = chain(evidenceKey);
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("BORROWED");
        assertThat(view.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        assertThat(view.get("loans")).hasSize(1);
    }

    @Test
    void borrowDueExactlyNowOrInPastReturns400() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob", utc(BASE))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob",
                utc(BASE.minusSeconds(60))).getResponse().getStatus()).isEqualTo(400);

        // 失败不改变状态
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void borrowDueBeyond72HoursReturns400ButExactly72HoursAllowed() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob",
                utc(BASE.plusSeconds(72 * 3600 + 1))).getResponse().getStatus()).isEqualTo(400);

        MvcResult ok = borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob",
                utc(BASE.plusSeconds(72 * 3600)));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void borrowByBorrowerSameAsCustodianReturns400() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "alice",
                utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void borrowByNonCustodianReturns409() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(borrow("mallory", evidenceKey, uniqueKey("LOAN"), "bob",
                utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void borrowDuringPendingTransferReturns409() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(initiate("alice", evidenceKey, "carol").getResponse().getStatus()).isEqualTo(200);
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob",
                utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("TRANSFER_PENDING");
    }

    @Test
    void borrowSealBrokenEvidenceReturns422() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(inspect("alice", evidenceKey, false).getResponse().getStatus()).isEqualTo(200);
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob",
                utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void secondActiveLoanOnSameEvidenceReturns409() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob", utc(BASE.plusSeconds(3600)));
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "carol",
                utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void loanBlocksTransferInspectionAndAnotherBorrow() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob", utc(BASE.plusSeconds(3600)));

        assertThat(initiate("alice", evidenceKey, "carol").getResponse().getStatus()).isEqualTo(409);
        assertThat(inspect("alice", evidenceKey, true).getResponse().getStatus()).isEqualTo(409);
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "carol",
                utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void overdueOnlyAffectsQueryFlagAndReturnStillWorks() throws Exception {
        String borrower = "bob-" + UUID.randomUUID().toString().substring(0, 8);
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        fixClock(BASE);
        borrow("alice", evidenceKey, loanKey, borrower, utc(BASE.plusSeconds(3600)));

        // 未到应还时刻
        fixClock(BASE.plusSeconds(1800));
        JsonNode beforeDue = activeLoans(borrower);
        assertThat(beforeDue).hasSize(1);
        assertThat(beforeDue.get(0).get("overdue").asBoolean()).isFalse();

        // 超过应还时刻：实时判定为派生状态 OVERDUE，不自动归还、不换保管人、不解除限制
        fixClock(BASE.plusSeconds(7200));
        JsonNode overdue = activeLoans(borrower);
        assertThat(overdue).hasSize(1);
        assertThat(overdue.get(0).get("overdue").asBoolean()).isTrue();
        assertThat(overdue.get(0).get("status").asText()).isEqualTo("OVERDUE");
        JsonNode view = chain(evidenceKey);
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("BORROWED");
        assertThat(view.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        assertThat(view.get("loans").get(0).get("status").asText()).isEqualTo("OVERDUE");
        assertThat(initiate("alice", evidenceKey, "carol").getResponse().getStatus()).isEqualTo(409);

        // 逾期后仍可正常归还
        MvcResult returned = returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey,
                true, "逾期归还，封条完好");
        assertThat(returned.getResponse().getStatus()).isEqualTo(200);
        JsonNode loan = objectMapper.readTree(returned.getResponse().getContentAsString());
        assertThat(loan.get("status").asText()).isEqualTo("RETURNED");
        assertThat(loan.get("overdue").asBoolean()).isFalse();
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(activeLoans(borrower)).isEmpty();
    }

    @Test
    void returnIntactRestoresSealedAndRecordsInspectionInSameHistory() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));

        MvcResult result = returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey,
                true, "归还核验完好");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode loan = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(loan.get("status").asText()).isEqualTo("RETURNED");
        assertThat(loan.get("sealIntact").asBoolean()).isTrue();
        assertThat(loan.get("returnNote").asText()).isEqualTo("归还核验完好");
        assertThat(loan.get("returnedAt").isNull()).isFalse();

        JsonNode view = chain(evidenceKey);
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(view.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        assertThat(view.get("loans").get(0).get("status").asText()).isEqualTo("RETURNED");
        assertThat(view.get("inspections")).hasSize(1);
        assertThat(view.get("inspections").get(0).get("passed").asBoolean()).isTrue();
        assertThat(view.get("inspections").get(0).get("inspectorId").asText()).isEqualTo("alice");
        assertThat(view.get("inspections").get(0).get("note").asText()).isEqualTo("归还核验完好");

        // 归还后可再次借出与交接
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "carol",
                utc(BASE.plusSeconds(7200))).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void returnWithBrokenSealEntersSealBrokenTerminalState() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));

        MvcResult result = returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey,
                false, "封条破损");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode loan = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(loan.get("sealIntact").asBoolean()).isFalse();

        JsonNode view = chain(evidenceKey);
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("SEAL_BROKEN");
        assertThat(view.get("inspections")).hasSize(1);
        assertThat(view.get("inspections").get(0).get("passed").asBoolean()).isFalse();

        // 终态：不能再次借出（422）或交接（422）
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "carol",
                utc(BASE.plusSeconds(7200))).getResponse().getStatus()).isEqualTo(422);
        assertThat(initiate("alice", evidenceKey, "carol").getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void borrowerCannotConfirmReturn() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));

        assertThat(returnLoan("bob", evidenceKey, uniqueKey("CMD"), loanKey, true, "代确认")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("BORROWED");
    }

    @Test
    void unrelatedNonCustodianCannotConfirmReturn() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));

        assertThat(returnLoan("mallory", evidenceKey, uniqueKey("CMD"), loanKey, true, "无关人员")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void returnWithWrongLoanKeyOrEvidenceReturns404() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));

        // 不存在的 loanKey
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("OTHER"),
                true, "x").getResponse().getStatus()).isEqualTo(404);
        // loanKey 属于其他证物
        String otherEvidence = uniqueKey("EV");
        intake("alice", otherEvidence);
        assertThat(returnLoan("alice", otherEvidence, uniqueKey("CMD"), loanKey,
                true, "x").getResponse().getStatus()).isEqualTo(404);
        // 证物不存在
        assertThat(returnLoan("alice", uniqueKey("MISSING"), uniqueKey("CMD"), loanKey,
                true, "x").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void returnWithBlankNoteReturns400() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));

        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, "  ")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("BORROWED");
    }

    @Test
    void oldLoanKeyCannotCloseOrStartAnotherLoanAfterReturn() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String oldLoanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        borrow("alice", evidenceKey, oldLoanKey, "bob", utc(BASE.plusSeconds(3600)));
        returnLoan("alice", evidenceKey, uniqueKey("CMD"), oldLoanKey, true, "完好");

        // 旧 loanKey 不能结束新一轮借出
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), oldLoanKey, true, "再还一次")
                .getResponse().getStatus()).isEqualTo(409);
        // 旧 loanKey 不能用于新一轮借出（全局唯一）
        assertThat(borrow("alice", evidenceKey, oldLoanKey, "carol",
                utc(BASE.plusSeconds(7200))).getResponse().getStatus()).isEqualTo(409);

        // 新借出必须使用新 loanKey
        String newLoanKey = uniqueKey("LOAN");
        assertThat(borrow("alice", evidenceKey, newLoanKey, "carol",
                utc(BASE.plusSeconds(7200))).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void borrowerQueryShowsOnlyActiveLoansWithOverdueFlag() throws Exception {
        String borrower = "bob-" + UUID.randomUUID().toString().substring(0, 8);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        intake("alice", ev3);
        fixClock(BASE);
        String loan1 = uniqueKey("LOAN");
        String loan2 = uniqueKey("LOAN");
        String loan3 = uniqueKey("LOAN");
        borrow("alice", ev1, loan1, borrower, utc(BASE.plusSeconds(3600)));
        borrow("alice", ev2, loan2, borrower, utc(BASE.plusSeconds(7200)));
        borrow("alice", ev3, loan3, borrower, utc(BASE.plusSeconds(3600)));
        // ev3 已归还，不再出现在未归还查询中
        returnLoan("alice", ev3, uniqueKey("CMD"), loan3, true, "完好");

        fixClock(BASE.plusSeconds(5400));
        JsonNode list = activeLoans(borrower);
        assertThat(list).hasSize(2);
        assertThat(list.findValuesAsText("loanKey")).containsExactlyInAnyOrder(loan1, loan2);
        Map<String, Boolean> overdueByKey = new LinkedHashMap<>();
        list.forEach(node -> overdueByKey.put(node.get("loanKey").asText(),
                node.get("overdue").asBoolean()));
        assertThat(overdueByKey.get(loan1)).isTrue();
        assertThat(overdueByKey.get(loan2)).isFalse();
    }

    @Test
    void custodyChainContainsLoansInOrder() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String loan1 = uniqueKey("LOAN");
        String loan2 = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        borrow("alice", evidenceKey, loan1, "bob", utc(BASE.plusSeconds(3600)));
        returnLoan("alice", evidenceKey, uniqueKey("CMD"), loan1, true, "第一次归还");
        borrow("alice", evidenceKey, loan2, "carol", utc(BASE.plusSeconds(7200)));
        returnLoan("alice", evidenceKey, uniqueKey("CMD"), loan2, false, "第二次封条异常");

        JsonNode view = chain(evidenceKey);
        assertThat(view.get("loans")).hasSize(2);
        assertThat(view.get("loans").get(0).get("loanKey").asText()).isEqualTo(loan1);
        assertThat(view.get("loans").get(0).get("status").asText()).isEqualTo("RETURNED");
        assertThat(view.get("loans").get(1).get("loanKey").asText()).isEqualTo(loan2);
        assertThat(view.get("inspections")).hasSize(2);
        assertThat(view.get("inspections").get(0).get("passed").asBoolean()).isTrue();
        assertThat(view.get("inspections").get(1).get("passed").asBoolean()).isFalse();
        // 历史不可覆盖
        assertThat(view.get("loans").get(0).get("sealIntact").asBoolean()).isTrue();
        assertThat(view.get("loans").get(1).get("sealIntact").asBoolean()).isFalse();
    }

    @Test
    void loanIsOverdueAtExactlyDueTime() throws Exception {
        String borrower = "bob-" + UUID.randomUUID().toString().substring(0, 8);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        borrow("alice", evidenceKey, uniqueKey("LOAN"), borrower, utc(BASE.plusSeconds(3600)));

        // 恰在应还时刻即逾期（now >= dueAt）
        fixClock(BASE.plusSeconds(3600));
        JsonNode atDue = activeLoans(borrower);
        assertThat(atDue).hasSize(1);
        assertThat(atDue.get(0).get("overdue").asBoolean()).isTrue();
    }

    @Test
    void borrowIdempotentReplayReturnsFirstResult() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String commandKey = uniqueKey("CMD");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);

        MvcResult first = borrow("alice", evidenceKey, commandKey, loanKey, "bob",
                "鉴定用", utc(BASE.plusSeconds(3600)));
        MvcResult replay = borrow("alice", evidenceKey, commandKey, loanKey, "bob",
                "鉴定用", utc(BASE.plusSeconds(3600)));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(chain(evidenceKey).get("loans")).hasSize(1);
    }

    @Test
    void sameCommandKeyWithChangedParamsReturns409() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String commandKey = uniqueKey("CMD");
        intake("alice", evidenceKey);

        assertThat(borrow("alice", evidenceKey, commandKey, uniqueKey("LOAN"), "bob",
                "鉴定用", utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(200);
        // 同键改参（不同借用人）
        assertThat(borrow("alice", evidenceKey, commandKey, uniqueKey("LOAN"), "carol",
                "鉴定用", utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failedBorrowDoesNotOccupyCommandKey() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String commandKey = uniqueKey("CMD");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);

        // 首次参数非法（应还时刻已过）失败，不占用 commandKey
        assertThat(borrow("alice", evidenceKey, commandKey, loanKey, "bob",
                "鉴定用", utc(BASE.minusSeconds(60))).getResponse().getStatus()).isEqualTo(400);
        // 同键同合法参数重试成功
        MvcResult retry = borrow("alice", evidenceKey, commandKey, loanKey, "bob",
                "鉴定用", utc(BASE.plusSeconds(3600)));
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void replayOldReturnDoesNotAffectNewLoan() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String firstLoanKey = uniqueKey("LOAN");
        String returnCommandKey = uniqueKey("CMD");
        intake("alice", evidenceKey);
        borrow("alice", evidenceKey, firstLoanKey, "bob", utc(BASE.plusSeconds(3600)));

        MvcResult firstReturn = returnLoan("alice", evidenceKey, returnCommandKey, firstLoanKey,
                true, "第一次归还");
        assertThat(firstReturn.getResponse().getStatus()).isEqualTo(200);

        // 新一轮借出（新 loanKey）
        String secondLoanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, secondLoanKey, "carol", utc(BASE.plusSeconds(7200)));

        // 重放旧归还命令：返回首次结果，不影响新一轮借出
        MvcResult replay = returnLoan("alice", evidenceKey, returnCommandKey, firstLoanKey,
                true, "第一次归还");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(firstReturn.getResponse().getContentAsString());

        JsonNode view = chain(evidenceKey);
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("BORROWED");
        assertThat(view.get("loans")).hasSize(2);
        assertThat(view.get("loans").get(0).get("status").asText()).isEqualTo("RETURNED");
        assertThat(view.get("loans").get(1).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(view.get("loans").get(1).get("loanKey").asText()).isEqualTo(secondLoanKey);
        assertThat(view.get("inspections")).hasSize(1);
    }

    @Test
    void borrowOnMissingEvidenceReturns404() throws Exception {
        fixClock(BASE);
        assertThat(borrow("alice", uniqueKey("MISSING"), uniqueKey("LOAN"), "bob",
                utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void borrowWithMissingFieldsReturns400() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        // 缺少 borrowerId/purpose/dueAt
        MvcResult result = mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }
}
