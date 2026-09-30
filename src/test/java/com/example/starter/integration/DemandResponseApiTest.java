package com.example.starter.integration;

import com.example.starter.testsupport.TestClockConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 H2（MODE=MySQL）端到端测试：邀约→承诺→计量→结算/更正链路、422/409 分支与 requestId 幂等。
 * 每个用例使用独立用户与独立时段邀约，互不污染。时钟固定在 2026-10-01T19:30Z。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfiguration.class)
@Sql(scripts = "/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class DemandResponseApiTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    private MockMvc mvc() {
        if (mockMvc == null) {
            mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        return mockMvc;
    }

    // ---------- 主流程 ----------

    @Test
    void syntheticChainCommit10Baseline30Actual23Rewards7Kwh560Fen() throws Exception {
        long invitationId = createInvitation("2026-09-01T18:00:00Z", "2026-09-01T19:00:00Z");
        createCommitment(invitationId, "u-synth", "10", "req-c-synth");
        submitReading(invitationId, "2026-09-01", "u-synth", "30", "23", null, 201);

        mvc().perform(post("/api/invitations/{id}/settlements", invitationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", "u-synth", "requestId", "req-s-synth"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.readingVersion").value(1))
                .andExpect(jsonPath("$.rewardKwh").value(7.0))
                .andExpect(jsonPath("$.amountFen").value(560))
                .andExpect(jsonPath("$.netAmountFen").value(560))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.entries[0].entryType").value("FIRST"))
                .andExpect(jsonPath("$.entries[0].newVersion").value(1));

        // 同 requestId 重放：返回原结果，账目不增加。
        mvc().perform(post("/api/invitations/{id}/settlements", invitationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", "u-synth", "requestId", "req-s-synth"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.amountFen").value(560));

        JsonNode account = getAccount(invitationId, "u-synth");
        assertEquals(1, account.at("/settlement/readingVersion").asInt());
        assertEquals(560, account.get("netAmountFen").asLong());
        assertEquals(1, account.get("readings").size());
        assertEquals(1, account.at("/settlement/entries").size(), "重试仍只产生一笔首次结算");
    }

    @Test
    void accountViewShowsCommitmentReadingsRewardAndNet() throws Exception {
        long invitationId = createInvitation("2026-09-02T18:00:00Z", "2026-09-02T19:00:00Z");
        createCommitment(invitationId, "u-view", "12.5", "req-c-view");
        // 节省 7.19 → 截取 7.1 → 568 分
        submitReading(invitationId, "2026-09-02", "u-view", "30", "22.81", null, 201);
        settle(invitationId, "u-view", "req-s-view", 201);

        JsonNode account = getAccount(invitationId, "u-view");
        assertEquals(0, new BigDecimal("12.5").compareTo(new BigDecimal(account.at("/commitment/reductionKwh").asText())));
        assertEquals(1, account.get("readings").size());
        assertEquals(7.1, account.at("/settlement/rewardKwh").asDouble(), 0.0001);
        assertEquals(568, account.get("netAmountFen").asLong());
    }

    // ---------- 承诺幂等与上限 ----------

    @Test
    void commitmentSameRequestIdReplaysOriginal() throws Exception {
        long invitationId = createInvitation("2026-09-03T18:00:00Z", "2026-09-03T19:00:00Z");
        MvcResult first = createCommitmentResult(invitationId, "u-cidem", "10", "req-c-same", 201);
        long firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asLong();

        MvcResult replay = createCommitmentResult(invitationId, "u-cidem", "10", "req-c-same", 201);
        assertEquals(firstId, objectMapper.readTree(replay.getResponse().getContentAsString()).get("id").asLong());

        // 同 requestId 异参 → 409
        createCommitmentResult(invitationId, "u-cidem", "11", "req-c-same", 409);
        // 不同 requestId、同用户 → 409
        createCommitmentResult(invitationId, "u-cidem", "10", "req-c-other", 409);
    }

    @Test
    void commitmentOver50KwhRejected() throws Exception {
        long invitationId = createInvitation("2026-09-04T18:00:00Z", "2026-09-04T19:00:00Z");
        createCommitmentResult(invitationId, "u-cap", "50.0001", "req-c-cap", 400);
    }

    // ---------- 计量错误分支 ----------

    @Test
    void missingReadingsReturn422AndNoLedger() throws Exception {
        long invitationId = createInvitation("2026-09-05T18:00:00Z", "2026-09-05T19:00:00Z");
        createCommitment(invitationId, "u-missing", "10", "req-c-missing");
        settle(invitationId, "u-missing", "req-s-missing", 422);

        JsonNode account = getAccount(invitationId, "u-missing");
        assertTrue(account.get("readings").isEmpty());
        assertEquals(0, account.get("netAmountFen").asLong());
    }

    @Test
    void negativeEnergyReturns422AndNoLedger() throws Exception {
        long invitationId = createInvitation("2026-09-06T18:00:00Z", "2026-09-06T19:00:00Z");
        createCommitment(invitationId, "u-neg", "10", "req-c-neg");
        submitReading(invitationId, "2026-09-06", "u-neg", "-0.1", "23", null, 422);
        submitReading(invitationId, "2026-09-06", "u-neg", "30", "-1", null, 422);

        JsonNode account = getAccount(invitationId, "u-neg");
        assertTrue(account.get("readings").isEmpty(), "失败请求不产生读数");
    }

    @Test
    void mismatchedReadingPeriodsReturn422() throws Exception {
        long invitationId = createInvitation("2026-09-07T18:00:00Z", "2026-09-07T19:00:00Z");
        createCommitment(invitationId, "u-period", "10", "req-c-period");

        Map<String, Object> body = readingBody("u-period", "30", "23",
                "2026-09-07T18:00:00Z", "2026-09-07T19:00:00Z",
                "2026-09-07T18:00:00Z", "2026-09-07T19:30:00Z", null);
        mvc().perform(post("/api/invitations/{id}/readings", invitationId)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isUnprocessableEntity());

        JsonNode account = getAccount(invitationId, "u-period");
        assertTrue(account.get("readings").isEmpty());
    }

    @Test
    void readingBeforePeriodEndReturns409() throws Exception {
        // 时钟固定在 2026-10-01T19:30Z；读数时段结束于未来
        long invitationId = createInvitation("2026-10-02T18:00:00Z", "2026-10-02T19:00:00Z");
        createCommitment(invitationId, "u-early", "10", "req-c-early");

        Map<String, Object> body = readingBody("u-early", "30", "23",
                "2026-10-02T18:00:00Z", "2026-10-02T20:00:00Z",
                "2026-10-02T18:00:00Z", "2026-10-02T20:00:00Z", null);
        mvc().perform(post("/api/invitations/{id}/readings", invitationId)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isConflict());

        settle(invitationId, "u-early", "req-s-early", 409);
        JsonNode account = getAccount(invitationId, "u-early");
        assertTrue(account.get("readings").isEmpty());
        assertEquals(0, account.get("netAmountFen").asLong());
    }

    @Test
    void settleBeforeInvitationEndReturns409AndNothingPosted() throws Exception {
        long invitationId = createInvitation("2026-10-03T18:00:00Z", "2026-10-03T19:00:00Z");
        createCommitment(invitationId, "u-soon", "10", "req-c-soon");
        settle(invitationId, "u-soon", "req-s-soon-1", 409);
        // 计量迟到场景：未结束时段重复结算仍是 409，不产生账目
        settle(invitationId, "u-soon", "req-s-soon-2", 409);
        JsonNode account = getAccount(invitationId, "u-soon");
        assertEquals(0, account.get("netAmountFen").asLong());
    }

    // ---------- 读数更正与差额账 ----------

    @Test
    void correctionCreatesNewVersionAndDeltaLedgerIncludingNegative() throws Exception {
        long invitationId = createInvitation("2026-09-08T18:00:00Z", "2026-09-08T19:00:00Z");
        createCommitment(invitationId, "u-corr", "10", "req-c-corr");
        submitReading(invitationId, "2026-09-08", "u-corr", "30", "23", null, 201);
        settle(invitationId, "u-corr", "req-s-corr", 201);

        // 更正：实际 23→25，奖励 7→5 kWh，差额 -2 kWh = -160 分
        submitReading(invitationId, "2026-09-08", "u-corr", "30", "25", "实际表计复核修正", 201);

        JsonNode account = getAccount(invitationId, "u-corr");
        assertEquals(2, account.get("readings").size(), "读数不可变，更正生成新版本");
        assertEquals("实际表计复核修正", account.at("/readings/1/reason").asText());
        assertEquals(1, account.at("/settlement/readingVersion").asInt(), "首次结算版本固化为 1");
        assertEquals(560, account.at("/settlement/amountFen").asLong(), "原结算不被覆盖");

        JsonNode entries = account.at("/settlement/entries");
        assertEquals(2, entries.size());
        assertEquals("FIRST", entries.get(0).get("entryType").asText());
        assertEquals("CORRECTION", entries.get(1).get("entryType").asText());
        assertEquals(1, entries.get(1).get("oldVersion").asInt(), "展示旧版本");
        assertEquals(2, entries.get(1).get("newVersion").asInt(), "展示新版本");
        assertEquals(-160, entries.get(1).get("amountFen").asLong(), "差额可为负");
        assertEquals("实际表计复核修正", entries.get(1).get("reason").asText());
        assertEquals(400, account.get("netAmountFen").asLong(), "净结算额 560-160=400");

        // 再更正：实际 25→21，奖励 5→9，差额相对上一版本 +4 kWh = +320
        submitReading(invitationId, "2026-09-08", "u-corr", "30", "21", "二次复核", 201);
        JsonNode afterSecond = getAccount(invitationId, "u-corr");
        JsonNode secondEntry = afterSecond.at("/settlement/entries/2");
        assertEquals(2, secondEntry.get("oldVersion").asInt());
        assertEquals(3, secondEntry.get("newVersion").asInt());
        assertEquals(320, secondEntry.get("amountFen").asLong());
        assertEquals(720, afterSecond.get("netAmountFen").asLong(), "净结算额 400+320=720");
    }

    @Test
    void correctionWithoutReasonAfterSettleRejected() throws Exception {
        long invitationId = createInvitation("2026-09-09T18:00:00Z", "2026-09-09T19:00:00Z");
        createCommitment(invitationId, "u-reason", "10", "req-c-reason");
        submitReading(invitationId, "2026-09-09", "u-reason", "30", "23", null, 201);
        settle(invitationId, "u-reason", "req-s-reason", 201);
        submitReading(invitationId, "2026-09-09", "u-reason", "30", "22", "  ", 422);
        submitReading(invitationId, "2026-09-09", "u-reason", "30", "22", null, 422);
    }

    @Test
    void settleWithDifferentRequestIdAfterSettleReturns409() throws Exception {
        long invitationId = createInvitation("2026-09-10T18:00:00Z", "2026-09-10T19:00:00Z");
        createCommitment(invitationId, "u-s2", "10", "req-c-s2");
        submitReading(invitationId, "2026-09-10", "u-s2", "30", "23", null, 201);
        settle(invitationId, "u-s2", "req-s-s2-a", 201);
        settle(invitationId, "u-s2", "req-s-s2-b", 409);
    }

    // ---------- helpers ----------

    private long createInvitation(String start, String end) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodStart", start);
        body.put("periodEnd", end);
        MvcResult result = mvc().perform(post("/api/invitations")
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void createCommitment(long invitationId, String userId, String kwh, String requestId) throws Exception {
        createCommitmentResult(invitationId, userId, kwh, requestId, 201);
    }

    private MvcResult createCommitmentResult(long invitationId, String userId, String kwh,
                                             String requestId, int expectedStatus) throws Exception {
        return mvc().perform(post("/api/invitations/{id}/commitments", invitationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", userId, "reductionKwh", new BigDecimal(kwh),
                                "requestId", requestId))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private void submitReading(long invitationId, String day, String userId, String baseline, String actual,
                               String reason, int expectedStatus) throws Exception {
        Map<String, Object> body = readingBody(userId, baseline, actual,
                day + "T18:00:00Z", day + "T19:00:00Z",
                day + "T18:00:00Z", day + "T19:00:00Z", reason);
        mvc().perform(post("/api/invitations/{id}/readings", invitationId)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().is(expectedStatus));
    }

    private Map<String, Object> readingBody(String userId, String baseline, String actual,
                                            String bStart, String bEnd, String aStart, String aEnd,
                                            String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("baselineKwh", new BigDecimal(baseline));
        body.put("actualKwh", new BigDecimal(actual));
        body.put("baselinePeriodStart", bStart);
        body.put("baselinePeriodEnd", bEnd);
        body.put("actualPeriodStart", aStart);
        body.put("actualPeriodEnd", aEnd);
        if (reason != null) {
            body.put("reason", reason);
        }
        return body;
    }

    private void settle(long invitationId, String userId, String requestId, int expectedStatus) throws Exception {
        mvc().perform(post("/api/invitations/{id}/settlements", invitationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", userId, "requestId", requestId))))
                .andExpect(status().is(expectedStatus));
    }

    private JsonNode getAccount(long invitationId, String userId) throws Exception {
        MvcResult result = mvc().perform(get("/api/invitations/{id}/accounts/{userId}", invitationId, userId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String json(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }
}
