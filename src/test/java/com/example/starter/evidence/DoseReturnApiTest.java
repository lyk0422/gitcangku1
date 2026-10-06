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
 * 剂量归还 API 测试（真实 H2 事务）：合成场景主流程、非法段 400 回滚、
 * 超限归还保存实际剂量、重放不重复累计、交接不清账、封条异常走原流程。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DoseReturnApiTest {

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

    private void intake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "PHOTO");
        body.put("sealNo", "SEAL-1");
        MvcResult result = mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private void registerCap(String actor, String evidenceKey, String cap) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", uniqueKey("REQ"));
        body.put("capLuxMinutes", cap);
        body.put("expectedVersion", 0);
        MvcResult result = mockMvc.perform(post("/api/evidence/{key}/dose-cap", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private MvcResult borrow(String actor, String evidenceKey, String loanKey,
                             String borrower, LocalDateTime dueAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", loanKey);
        body.put("borrowerId", borrower);
        body.put("purpose", "感光鉴定");
        body.put("dueAt", dueAt.toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult returnLoan(String actor, String evidenceKey, String commandKey,
                                 String loanKey, boolean sealIntact,
                                 List<Map<String, Object>> segments) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("loanKey", loanKey);
        body.put("sealIntact", sealIntact);
        body.put("note", "归还说明");
        if (segments != null) {
            body.put("segments", segments);
        }
        return mockMvc.perform(post("/api/evidence/{key}/loans/return", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private Map<String, Object> segment(Instant start, Instant end, String lux) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("startAt", utc(start).toString());
        segment.put("endAt", utc(end).toString());
        segment.put("lux", lux);
        return segment;
    }

    private JsonNode ledger(String actor, String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/dose-ledger", evidenceKey)
                        .header(ACTOR_HEADER, actor))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult ledgerRaw(String actor, String evidenceKey) throws Exception {
        return mockMvc.perform(get("/api/evidence/{key}/dose-ledger", evidenceKey)
                        .header(ACTOR_HEADER, actor))
                .andReturn();
    }

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult initiate(String actor, String evidenceKey, String toCustodian)
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

    private MvcResult accept(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        return mockMvc.perform(post("/api/evidence/{key}/transfers/accept", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    /**
     * 登记上限100的合成场景：第一次10分钟照度6累计60；第二次10分钟照度4累计100；
     * 第三次借出按 DOSE_BLOCKED 拒绝（422），证物回 SEALED，交接仍可行。
     */
    @Test
    void syntheticScenarioReachingCapBlocksNextBorrow() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        registerCap("alice", evidenceKey, "100");

        // 第一次借出 10 分钟，照度 6 → 累计 60
        fixClock(BASE);
        String loan1 = uniqueKey("LOAN");
        assertThat(borrow("alice", evidenceKey, loan1, "bob", utc(BASE.plusSeconds(3600)))
                .getResponse().getStatus()).isEqualTo(200);
        fixClock(BASE.plusSeconds(600));
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loan1, true,
                List.of(segment(BASE, BASE.plusSeconds(600), "6")))
                .getResponse().getStatus()).isEqualTo(200);
        JsonNode ledger1 = ledger("alice", evidenceKey);
        assertThat(ledger1.get("cumulativeLuxMinutes").decimalValue()).isEqualByComparingTo("60");
        assertThat(ledger1.get("cumulativeDisplay").asText()).isEqualTo("60.0000");
        assertThat(ledger1.get("doseBlocked").asBoolean()).isFalse();
        assertThat(ledger1.get("version").asInt()).isEqualTo(2);
        assertThat(ledger1.get("returns")).hasSize(1);
        assertThat(ledger1.get("returns").get(0).get("doseLuxMinutes").decimalValue())
                .isEqualByComparingTo("60");
        assertThat(ledger1.get("returns").get(0).get("segments")).hasSize(1);
        assertThat(ledger1.get("returns").get(0).get("segments").get(0).get("lux").decimalValue())
                .isEqualByComparingTo("6");

        // 第二次借出 10 分钟，照度 4 → 累计 100，达到上限
        fixClock(BASE.plusSeconds(600));
        String loan2 = uniqueKey("LOAN");
        assertThat(borrow("alice", evidenceKey, loan2, "bob", utc(BASE.plusSeconds(4200)))
                .getResponse().getStatus()).isEqualTo(200);
        fixClock(BASE.plusSeconds(1200));
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loan2, true,
                List.of(segment(BASE.plusSeconds(600), BASE.plusSeconds(1200), "4")))
                .getResponse().getStatus()).isEqualTo(200);
        JsonNode ledger2 = ledger("alice", evidenceKey);
        assertThat(ledger2.get("cumulativeLuxMinutes").decimalValue()).isEqualByComparingTo("100");
        assertThat(ledger2.get("doseBlocked").asBoolean()).isTrue();
        assertThat(ledger2.get("version").asInt()).isEqualTo(3);

        // 证物回 SEALED，但新增借出 422（DOSE_BLOCKED）；交接仍按原规则可行
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");
        fixClock(BASE.plusSeconds(1200));
        MvcResult blocked = borrow("alice", evidenceKey, uniqueKey("LOAN"), "carol",
                utc(BASE.plusSeconds(4800)));
        assertThat(blocked.getResponse().getStatus()).isEqualTo(422);
        assertThat(blocked.getResponse().getContentAsString()).contains("DOSE_BLOCKED");
        assertThat(initiate("alice", evidenceKey, "carol").getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void returnWithMissingOrInvalidSegmentsReturns400AndRecordsNothing() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        registerCap("alice", evidenceKey, "100");
        fixClock(BASE);
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(600));

        // 缺段 400
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, null)
                .getResponse().getStatus()).isEqualTo(400);
        // 空段 400
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, List.of())
                .getResponse().getStatus()).isEqualTo(400);
        // 重叠 400
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(segment(BASE, BASE.plusSeconds(400), "1"),
                        segment(BASE.plusSeconds(300), BASE.plusSeconds(600), "1")))
                .getResponse().getStatus()).isEqualTo(400);
        // 缺口 400
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(segment(BASE, BASE.plusSeconds(300), "1"),
                        segment(BASE.plusSeconds(400), BASE.plusSeconds(600), "1")))
                .getResponse().getStatus()).isEqualTo(400);
        // 未覆盖至归还时刻 400
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(segment(BASE, BASE.plusSeconds(300), "1")))
                .getResponse().getStatus()).isEqualTo(400);
        // 负照度 400
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(segment(BASE, BASE.plusSeconds(600), "-1")))
                .getResponse().getStatus()).isEqualTo(400);

        // 不归还、不记剂量：借出仍 ACTIVE，账本累计仍为 0
        JsonNode view = chain(evidenceKey);
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("BORROWED");
        assertThat(view.get("loans").get(0).get("status").asText()).isEqualTo("ACTIVE");
        JsonNode ledger = ledger("alice", evidenceKey);
        assertThat(ledger.get("cumulativeLuxMinutes").decimalValue()).isEqualByComparingTo("0");
        assertThat(ledger.get("returns")).isEmpty();
    }

    @Test
    void overLimitReturnSucceedsAndSavesActualDose() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        registerCap("alice", evidenceKey, "100");
        fixClock(BASE);
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(600));

        // 当次剂量 120 超过上限 100：归还必须成功并保存实际剂量，不得拒收后继续外借
        MvcResult result = returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(segment(BASE, BASE.plusSeconds(600), "12")));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode ledger = ledger("alice", evidenceKey);
        assertThat(ledger.get("cumulativeLuxMinutes").decimalValue()).isEqualByComparingTo("120");
        assertThat(ledger.get("cumulativeDisplay").asText()).isEqualTo("120.0000");
        assertThat(ledger.get("doseBlocked").asBoolean()).isTrue();
        assertThat(ledger.get("returns").get(0).get("doseLuxMinutes").decimalValue())
                .isEqualByComparingTo("120");

        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "carol",
                utc(BASE.plusSeconds(4200))).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void replayedReturnDoesNotAccumulateTwice() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        registerCap("alice", evidenceKey, "100");
        fixClock(BASE);
        String loanKey = uniqueKey("LOAN");
        String commandKey = uniqueKey("CMD");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(600));

        List<Map<String, Object>> segments = List.of(segment(BASE, BASE.plusSeconds(600), "6"));
        MvcResult first = returnLoan("alice", evidenceKey, commandKey, loanKey, true, segments);
        MvcResult replay = returnLoan("alice", evidenceKey, commandKey, loanKey, true, segments);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 重放不重复累计：累计仍 60，仅一条剂量归还、一段剂量段
        JsonNode ledger = ledger("alice", evidenceKey);
        assertThat(ledger.get("cumulativeLuxMinutes").decimalValue()).isEqualByComparingTo("60");
        assertThat(ledger.get("returns")).hasSize(1);
        assertThat(ledger.get("returns").get(0).get("segments")).hasSize(1);
    }

    @Test
    void custodyTransferDoesNotClearLedgerAndDetailFollowsCustodian() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        registerCap("alice", evidenceKey, "100");
        fixClock(BASE);
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(600));
        returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(segment(BASE, BASE.plusSeconds(600), "6")));

        // 保管权交接不清账
        assertThat(initiate("alice", evidenceKey, "carol").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(accept("carol", evidenceKey).getResponse().getStatus()).isEqualTo(200);
        JsonNode ledger = ledger("carol", evidenceKey);
        assertThat(ledger.get("cumulativeLuxMinutes").decimalValue()).isEqualByComparingTo("60");
        assertThat(ledger.get("capLuxMinutes").decimalValue()).isEqualByComparingTo("100");
        assertThat(ledger.get("returns")).hasSize(1);

        // 明细仅当前保管人可查：原保管人 409
        assertThat(ledgerRaw("alice", evidenceKey).getResponse().getStatus()).isEqualTo(409);
        // 新保管人可继续借出（60 < 100），剂量规则随证物走
        fixClock(BASE.plusSeconds(1200));
        assertThat(borrow("carol", evidenceKey, uniqueKey("LOAN"), "dave",
                utc(BASE.plusSeconds(4800))).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void doseLedgerOnlyVisibleToCurrentCustodian() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        registerCap("alice", evidenceKey, "100");
        assertThat(ledgerRaw("mallory", evidenceKey).getResponse().getStatus()).isEqualTo(409);
        assertThat(ledgerRaw("alice", uniqueKey("MISSING")).getResponse().getStatus())
                .isEqualTo(404);
        // 未登记账本的证物：404
        String other = uniqueKey("EV");
        intake("alice", other);
        assertThat(ledgerRaw("alice", other).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void sealBrokenReturnKeepsOriginalFlowWithoutDose() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        registerCap("alice", evidenceKey, "100");
        fixClock(BASE);
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(600));

        // 封条异常：继续走原 SEAL_BROKEN 流程，不要求剂量段、不记剂量
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, false, null)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("SEAL_BROKEN");
        JsonNode ledger = ledger("alice", evidenceKey);
        assertThat(ledger.get("cumulativeLuxMinutes").decimalValue()).isEqualByComparingTo("0");
        assertThat(ledger.get("returns")).isEmpty();
    }

    @Test
    void loanWithoutCapIsUnaffectedByDoseRules() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(600));

        // 未登记上限：归还无需剂量段；即使携带也忽略，不产生账本
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(segment(BASE, BASE.plusSeconds(600), "6")))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(ledgerRaw("alice", evidenceKey).getResponse().getStatus()).isEqualTo(404);
        // 可继续正常借出
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "carol",
                utc(BASE.plusSeconds(4200))).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void displayRoundsHalfUpButDecisionUsesExactCumulative() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        // 上限 0.017：1 秒照度 1.019 的精确剂量为 1.019/60 ≈ 0.01698333，
        // 显示 HALF_UP 四位为 0.0170 与上限一致，但精确累计未达上限
        registerCap("alice", evidenceKey, "0.017");
        fixClock(BASE);
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(1));
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(segment(BASE, BASE.plusSeconds(1), "1.019")))
                .getResponse().getStatus()).isEqualTo(200);

        JsonNode ledger = ledger("alice", evidenceKey);
        assertThat(ledger.get("cumulativeDisplay").asText()).isEqualTo("0.0170");
        // 显示值达到上限不代表判定达到：精确累计 1.019/60 < 0.017，仍可借出
        assertThat(ledger.get("doseBlocked").asBoolean()).isFalse();
        fixClock(BASE.plusSeconds(2));
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "carol",
                utc(BASE.plusSeconds(3602))).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void multiSegmentReturnAccumulatesExactSum() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        registerCap("alice", evidenceKey, "1000");
        fixClock(BASE);
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(1800));

        // 三段：10分钟×6 + 5分钟×0.5 + 15分钟×2 = 60 + 2.5 + 30 = 92.5
        MvcResult result = returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(segment(BASE, BASE.plusSeconds(600), "6"),
                        segment(BASE.plusSeconds(600), BASE.plusSeconds(900), "0.5"),
                        segment(BASE.plusSeconds(900), BASE.plusSeconds(1800), "2")));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode ledger = ledger("alice", evidenceKey);
        assertThat(ledger.get("cumulativeLuxMinutes").decimalValue()).isEqualByComparingTo("92.5");
        JsonNode returns = ledger.get("returns").get(0);
        assertThat(returns.get("doseLuxMinutes").decimalValue()).isEqualByComparingTo("92.5");
        assertThat(returns.get("segments")).hasSize(3);
        assertThat(returns.get("segments").get(0).get("doseLuxMinutes").decimalValue())
                .isEqualByComparingTo("60");
        assertThat(returns.get("segments").get(1).get("doseLuxMinutes").decimalValue())
                .isEqualByComparingTo("2.5");
        assertThat(returns.get("segments").get(2).get("doseLuxMinutes").decimalValue())
                .isEqualByComparingTo("30");
    }
}
