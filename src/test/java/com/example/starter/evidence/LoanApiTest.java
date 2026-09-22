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
 * 限时借出与归还核验 API 测试：主流程、失败分支、幂等与注入时钟下的逾期标识。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoanApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    /** 固定基准时刻：2026-10-01T00:00:00Z，避免依赖墙钟。 */
    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void fixClock(Instant instant) {
        clock.setClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private MvcResult intake(String actor, String commandKey, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
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

    private MvcResult initiate(String actor, String evidenceKey, String commandKey,
                               String toCustodian) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("toCustodian", toCustodian);
        return mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult createLoan(String actor, String evidenceKey, String commandKey, String loanKey,
                                 String borrower, String purpose, LocalDateTime dueAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("loanKey", loanKey);
        body.put("borrowerId", borrower);
        body.put("purpose", purpose);
        body.put("dueAt", dueAt);
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult createLoan(String actor, String evidenceKey, String commandKey,
                                 String loanKey) throws Exception {
        return createLoan(actor, evidenceKey, commandKey, loanKey, "bob",
                "lab analysis", LocalDateTime.ofInstant(T0.plusSeconds(3600), ZoneOffset.UTC));
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

    private MvcResult inspect(String actor, String evidenceKey, String commandKey,
                              boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
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
        MvcResult result = mockMvc.perform(get("/api/evidence/loans")
                        .param("borrowerId", borrower))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void createLoanMovesToBorrowedKeepingCustodian() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        String loanKey = uniqueKey("LOAN");

        MvcResult result = createLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode loan = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(loan.get("loanKey").asText()).isEqualTo(loanKey);
        assertThat(loan.get("evidenceKey").asText()).isEqualTo(evidenceKey);
        assertThat(loan.get("custodianId").asText()).isEqualTo("alice");
        assertThat(loan.get("borrowerId").asText()).isEqualTo("bob");
        assertThat(loan.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(loan.get("overdue").asBoolean()).isFalse();
        assertThat(loan.get("returnedAt").isNull()).isTrue();

        JsonNode chainNode = chain(evidenceKey);
        assertThat(chainNode.get("evidence").get("status").asText()).isEqualTo("BORROWED");
        assertThat(chainNode.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        assertThat(chainNode.get("loans")).hasSize(1);
    }

    @Test
    void createLoanWithPastDueAtReturns400AndDoesNotOccupyCommandKey() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        String commandKey = uniqueKey("CMD");

        MvcResult pastDue = createLoan("alice", evidenceKey, commandKey, uniqueKey("LOAN"),
                "bob", "lab", LocalDateTime.ofInstant(T0.minusSeconds(60), ZoneOffset.UTC));
        assertThat(pastDue.getResponse().getStatus()).isEqualTo(400);

        // 失败不占键：同 commandKey 改合法参数后成功
        MvcResult retry = createLoan("alice", evidenceKey, commandKey, uniqueKey("LOAN"));
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void createLoanWithDueBeyond72HoursReturns400() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);

        MvcResult tooLong = createLoan("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("LOAN"),
                "bob", "lab",
                LocalDateTime.ofInstant(T0.plus(DurationHolder.H72).plusSeconds(1), ZoneOffset.UTC));
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);

        // 恰好 72 小时允许
        MvcResult edge = createLoan("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("LOAN"),
                "bob", "lab",
                LocalDateTime.ofInstant(T0.plus(DurationHolder.H72), ZoneOffset.UTC));
        assertThat(edge.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void createLoanWithDueAtExactlyNowReturns400AndKeyNotOccupied() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        String commandKey = uniqueKey("CMD");

        // 应还时刻等于当前时刻不满足"晚于当前时刻"
        assertThat(createLoan("alice", evidenceKey, commandKey, uniqueKey("LOAN"),
                "bob", "lab", LocalDateTime.ofInstant(T0, ZoneOffset.UTC))
                .getResponse().getStatus()).isEqualTo(400);
        // 失败不占键
        assertThat(createLoan("alice", evidenceKey, commandKey, uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void createLoanWithBorrowerEqualToCustodianReturns400() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        assertThat(createLoan("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("LOAN"),
                "alice", "self", LocalDateTime.ofInstant(T0.plusSeconds(3600), ZoneOffset.UTC))
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void createLoanWithBlankFieldsReturns400() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        // loanKey/borrowerId/purpose/dueAt 全部缺失
        MvcResult result = mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void createLoanByNonCustodianReturns409() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        assertThat(createLoan("mallory", evidenceKey, uniqueKey("CMD"), uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void createLoanOnMissingEvidenceReturns404() throws Exception {
        fixClock(T0);
        assertThat(createLoan("alice", uniqueKey("EV"), uniqueKey("CMD"), uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void createLoanWhileTransferPendingReturns409() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        assertThat(initiate("alice", evidenceKey, uniqueKey("CMD"), "bob")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(createLoan("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void createLoanOnSealBrokenReturns422() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        assertThat(inspect("alice", evidenceKey, uniqueKey("CMD"), false)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(createLoan("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void operationsRejectedWhileBorrowed() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        String loanKey = uniqueKey("LOAN");
        assertThat(createLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey)
                .getResponse().getStatus()).isEqualTo(200);

        // 再次借出拒绝
        assertThat(createLoan("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(409);
        // 发起交接拒绝
        assertThat(initiate("alice", evidenceKey, uniqueKey("CMD"), "carol")
                .getResponse().getStatus()).isEqualTo(409);
        // 独立封条核验拒绝
        assertThat(inspect("alice", evidenceKey, uniqueKey("CMD"), true)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void duplicateLoanKeyReturns409() throws Exception {
        fixClock(T0);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), ev1);
        intake("alice", uniqueKey("CMD"), ev2);
        String loanKey = uniqueKey("LOAN");
        assertThat(createLoan("alice", ev1, uniqueKey("CMD"), loanKey)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(createLoan("alice", ev2, uniqueKey("CMD"), loanKey)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void returnIntactRestoresSealedAndAppendsInspectionInSameHistory() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        String loanKey = uniqueKey("LOAN");
        createLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey);

        MvcResult returned = returnLoan("alice", evidenceKey, uniqueKey("CMD"),
                loanKey, true, "seal verified intact");
        assertThat(returned.getResponse().getStatus()).isEqualTo(200);
        JsonNode loan = objectMapper.readTree(returned.getResponse().getContentAsString());
        assertThat(loan.get("status").asText()).isEqualTo("RETURNED");
        assertThat(loan.get("sealIntact").asBoolean()).isTrue();
        assertThat(loan.get("returnNote").asText()).isEqualTo("seal verified intact");
        assertThat(loan.get("returnedAt").isNull()).isFalse();

        JsonNode chainNode = chain(evidenceKey);
        assertThat(chainNode.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chainNode.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        assertThat(chainNode.get("loans").get(0).get("status").asText()).isEqualTo("RETURNED");
        // 归还封条核验记录与状态同事务落库
        assertThat(chainNode.get("inspections")).hasSize(1);
        assertThat(chainNode.get("inspections").get(0).get("passed").asBoolean()).isTrue();
        assertThat(chainNode.get("inspections").get(0).get("inspectorId").asText())
                .isEqualTo("alice");

        // 归还后可再次借出
        assertThat(createLoan("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void returnBrokenSealEntersSealBrokenTerminalState() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        String loanKey = uniqueKey("LOAN");
        createLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey);

        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"),
                loanKey, false, "tampered seal").getResponse().getStatus()).isEqualTo(200);

        JsonNode chainNode = chain(evidenceKey);
        assertThat(chainNode.get("evidence").get("status").asText()).isEqualTo("SEAL_BROKEN");
        assertThat(chainNode.get("inspections")).hasSize(1);
        assertThat(chainNode.get("inspections").get(0).get("passed").asBoolean()).isFalse();
        // 终态：再次借出被封条异常阻止（422）
        assertThat(createLoan("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void borrowerCannotConfirmReturn() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        String loanKey = uniqueKey("LOAN");
        createLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey);

        // 借用人不是保管人，不能代为确认
        assertThat(returnLoan("bob", evidenceKey, uniqueKey("CMD"),
                loanKey, true, "i return it").getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("BORROWED");
    }

    @Test
    void returnWithBlankNoteReturns400() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        String loanKey = uniqueKey("LOAN");
        createLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey);

        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"),
                loanKey, true, "  ").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void returnUnknownLoanKeyReturns404() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        createLoan("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("LOAN"));
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"),
                uniqueKey("LOAN"), true, "note").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void oldLoanKeyCannotCloseNewLoanAndReplayOfOldReturnDoesNotAffectNewLoan() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        String loanKey1 = uniqueKey("LOAN");
        String returnCommand = uniqueKey("CMD");
        createLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey1);
        assertThat(returnLoan("alice", evidenceKey, returnCommand,
                loanKey1, true, "first return").getResponse().getStatus()).isEqualTo(200);

        // 新一轮借出
        String loanKey2 = uniqueKey("LOAN");
        createLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey2);

        // 旧 loanKey 已归还，不能结束新一轮借出：历史不可覆盖，返回 409
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"),
                loanKey1, true, "stale key").getResponse().getStatus()).isEqualTo(409);

        // 重放旧归还命令：原样返回首次结果，新借出保持 ACTIVE
        MvcResult replay = returnLoan("alice", evidenceKey, returnCommand,
                loanKey1, true, "first return");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayed = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayed.get("loanKey").asText()).isEqualTo(loanKey1);
        assertThat(replayed.get("status").asText()).isEqualTo("RETURNED");

        JsonNode chainNode = chain(evidenceKey);
        assertThat(chainNode.get("evidence").get("status").asText()).isEqualTo("BORROWED");
        assertThat(chainNode.get("loans")).hasSize(2);
        assertThat(chainNode.get("loans").get(0).get("status").asText()).isEqualTo("RETURNED");
        assertThat(chainNode.get("loans").get(1).get("loanKey").asText()).isEqualTo(loanKey2);
        assertThat(chainNode.get("loans").get(1).get("status").asText()).isEqualTo("ACTIVE");
        // 旧归还重放不新增封条核验记录
        assertThat(chainNode.get("inspections")).hasSize(1);
    }

    @Test
    void overdueOnlyChangesQueryFlagWithoutAutoReturn() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        String borrower = "user-" + UUID.randomUUID().toString().substring(0, 8);
        intake("alice", uniqueKey("CMD"), evidenceKey);
        String loanKey = uniqueKey("LOAN");
        createLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, borrower, "court",
                LocalDateTime.ofInstant(T0.plusSeconds(3600), ZoneOffset.UTC));

        // 未逾期
        JsonNode before = activeLoans(borrower);
        assertThat(before).hasSize(1);
        assertThat(before.get(0).get("overdue").asBoolean()).isFalse();

        // 时钟拨到恰为应还时刻：达到即逾期
        fixClock(T0.plusSeconds(3600));
        assertThat(activeLoans(borrower).get(0).get("overdue").asBoolean()).isTrue();

        // 时钟拨过应还时刻：逾期仅影响标识
        fixClock(T0.plusSeconds(7200));
        JsonNode overdue = activeLoans(borrower);
        assertThat(overdue).hasSize(1);
        assertThat(overdue.get(0).get("overdue").asBoolean()).isTrue();

        JsonNode chainNode = chain(evidenceKey);
        assertThat(chainNode.get("evidence").get("status").asText()).isEqualTo("BORROWED");
        assertThat(chainNode.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        // 逾期不解除借出限制
        assertThat(initiate("alice", evidenceKey, uniqueKey("CMD"), "carol")
                .getResponse().getStatus()).isEqualTo(409);

        // 逾期后仍可由保管人正常归还
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"),
                loanKey, true, "late but intact").getResponse().getStatus()).isEqualTo(200);
        assertThat(activeLoans(borrower)).isEmpty();
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void activeLoansListedByBorrowerOnly() throws Exception {
        fixClock(T0);
        String borrower = "user-" + UUID.randomUUID().toString().substring(0, 8);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), ev1);
        intake("alice", uniqueKey("CMD"), ev2);
        intake("alice", uniqueKey("CMD"), ev3);
        String loan1 = uniqueKey("LOAN");
        String loan2 = uniqueKey("LOAN");
        createLoan("alice", ev1, uniqueKey("CMD"), loan1, borrower, "p1",
                LocalDateTime.ofInstant(T0.plusSeconds(3600), ZoneOffset.UTC));
        createLoan("alice", ev2, uniqueKey("CMD"), loan2, borrower, "p2",
                LocalDateTime.ofInstant(T0.plusSeconds(7200), ZoneOffset.UTC));
        createLoan("alice", ev3, uniqueKey("CMD"), uniqueKey("LOAN"), "other-borrower", "p3",
                LocalDateTime.ofInstant(T0.plusSeconds(3600), ZoneOffset.UTC));
        returnLoan("alice", ev1, uniqueKey("CMD"), loan1, true, "done");

        JsonNode list = activeLoans(borrower);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("loanKey").asText()).isEqualTo(loan2);
    }

    @Test
    void activeLoansWithoutBorrowerParamReturns400() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/loans"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void createLoanIdempotentReplayReturnsFirstResult() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        String commandKey = uniqueKey("CMD");
        String loanKey = uniqueKey("LOAN");

        MvcResult first = createLoan("alice", evidenceKey, commandKey, loanKey);
        MvcResult replay = createLoan("alice", evidenceKey, commandKey, loanKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(chain(evidenceKey).get("loans")).hasSize(1);
    }

    @Test
    void createLoanSameCommandKeyDifferentParamsReturns409() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        String commandKey = uniqueKey("CMD");
        assertThat(createLoan("alice", evidenceKey, commandKey, uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(createLoan("alice", evidenceKey, commandKey, uniqueKey("LOAN"))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void returnLoanIdempotentReplayDoesNotAppendSecondInspection() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        String loanKey = uniqueKey("LOAN");
        String commandKey = uniqueKey("CMD");
        createLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey);

        MvcResult first = returnLoan("alice", evidenceKey, commandKey,
                loanKey, true, "ok");
        MvcResult replay = returnLoan("alice", evidenceKey, commandKey,
                loanKey, true, "ok");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(chain(evidenceKey).get("inspections")).hasSize(1);
    }

    @Test
    void returnLoanSameCommandKeyDifferentParamsReturns409() throws Exception {
        fixClock(T0);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey);
        String loanKey = uniqueKey("LOAN");
        String commandKey = uniqueKey("CMD");
        createLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey);

        assertThat(returnLoan("alice", evidenceKey, commandKey,
                loanKey, true, "ok").getResponse().getStatus()).isEqualTo(200);
        // 同键改参（核验结果不同）返回 409，历史结果不变
        assertThat(returnLoan("alice", evidenceKey, commandKey,
                loanKey, false, "changed").getResponse().getStatus()).isEqualTo(409);
        JsonNode chainNode = chain(evidenceKey);
        assertThat(chainNode.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chainNode.get("inspections")).hasSize(1);
    }

    /** 仅用于以常量形式引用 72 小时，避免测试体内魔法值重复。 */
    private static final class DurationHolder {
        static final java.time.Duration H72 = java.time.Duration.ofHours(72);
    }
}
