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

import java.math.BigDecimal;
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
 * 光照剂量账本 API 测试：上限登记、主流程累计、达到上限联合裁决、明细权限与显示舍入。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DoseApiTest {

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
        body.put("category", "PHOTO");
        body.put("sealNo", "SEAL-1");
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult register(String actor, String evidenceKey, String requestId,
                               String limit, long expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("limitLuxMinutes", limit == null ? null : new BigDecimal(limit));
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/evidence/{key}/dose-limit", evidenceKey)
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
        body.put("purpose", "光照实验");
        body.put("dueAt", dueAt.toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private Map<String, Object> segment(Instant start, Instant end, String lux) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("startAt", utc(start).toString());
        segment.put("endAt", utc(end).toString());
        segment.put("illuminanceLux", new BigDecimal(lux));
        return segment;
    }

    private MvcResult returnLoan(String actor, String evidenceKey, String commandKey,
                                 String loanKey, boolean sealIntact, Long expectedVersion,
                                 List<Map<String, Object>> segments) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("loanKey", loanKey);
        body.put("sealIntact", sealIntact);
        body.put("note", "归还说明");
        body.put("expectedVersion", expectedVersion);
        body.put("segments", segments);
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

    private MvcResult accept(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        return mockMvc.perform(post("/api/evidence/{key}/transfers/accept", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode doseDetail(String actor, String evidenceKey, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/dose", evidenceKey)
                        .header(ACTOR_HEADER, actor))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void registerLimitCreatesImmutableLedger() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        MvcResult created = register("alice", evidenceKey, uniqueKey("REQ"), "100", 0);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode ledger = objectMapper.readTree(created.getResponse().getContentAsString());
        assertThat(ledger.get("evidenceKey").asText()).isEqualTo(evidenceKey);
        assertThat(ledger.get("limitLuxMinutes").decimalValue())
                .isEqualByComparingTo(new BigDecimal("100"));
        assertThat(ledger.get("cumulativeLuxMin").decimalValue())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ledger.get("limitDisplay").asText()).isEqualTo("100.0000");
        assertThat(ledger.get("cumulativeDisplay").asText()).isEqualTo("0.0000");
        assertThat(ledger.get("doseBlocked").asBoolean()).isFalse();
        assertThat(ledger.get("registeredBy").asText()).isEqualTo("alice");
        assertThat(ledger.get("version").asInt()).isEqualTo(1);
        assertThat(ledger.get("returns")).isEmpty();

        // 上限不可变：再次登记 409
        assertThat(register("alice", evidenceKey, uniqueKey("REQ"), "200", 0)
                .getResponse().getStatus()).isEqualTo(409);
        JsonNode detail = doseDetail("alice", evidenceKey, 200);
        assertThat(detail.get("limitLuxMinutes").decimalValue())
                .isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    void registerLimitRejectsInvalidValues() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        assertThat(register("alice", evidenceKey, uniqueKey("REQ"), "0", 0)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(register("alice", evidenceKey, uniqueKey("REQ"), "-5", 0)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(register("alice", evidenceKey, uniqueKey("REQ"), "1.0001", 0)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(register("alice", evidenceKey, uniqueKey("REQ"), null, 0)
                .getResponse().getStatus()).isEqualTo(400);
        // 期望版本必须为 0（账本尚不存在）
        assertThat(register("alice", evidenceKey, uniqueKey("REQ"), "100", 1)
                .getResponse().getStatus()).isEqualTo(409);
        // 失败不占键：同 requestId 修正参数后可成功
        String requestId = uniqueKey("REQ");
        assertThat(register("alice", evidenceKey, requestId, "-1", 0)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(register("alice", evidenceKey, requestId, "100", 0)
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void registerLimitRequiresCustodianAndSealedEvidence() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        // 非保管人 409；证物不存在 404
        assertThat(register("mallory", evidenceKey, uniqueKey("REQ"), "100", 0)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(register("alice", uniqueKey("MISSING"), uniqueKey("REQ"), "100", 0)
                .getResponse().getStatus()).isEqualTo(404);

        // 借出期间（非 SEALED）不可登记
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob",
                utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(200);
        assertThat(register("alice", evidenceKey, uniqueKey("REQ"), "100", 0)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void registerLimitReplayAndChangedParams() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String requestId = uniqueKey("REQ");
        intake("alice", evidenceKey);

        MvcResult first = register("alice", evidenceKey, requestId, "100", 0);
        MvcResult replay = register("alice", evidenceKey, requestId, "100", 0);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 同键改参 409
        assertThat(register("alice", evidenceKey, requestId, "200", 0)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void registerLimitPermissionPrecedesReplay() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String requestId = uniqueKey("REQ");
        intake("alice", evidenceKey);
        assertThat(register("alice", evidenceKey, requestId, "100", 0)
                .getResponse().getStatus()).isEqualTo(201);

        // 非保管人使用同一 requestId 同参重放：权限先于重放，返回 409 且不泄露首次响应
        MvcResult denied = register("mallory", evidenceKey, requestId, "100", 0);
        assertThat(denied.getResponse().getStatus()).isEqualTo(409);
        assertThat(denied.getResponse().getContentAsString()).doesNotContain("limitLuxMinutes");
    }

    @Test
    void syntheticScenarioAccumulatesToLimitAndBlocks() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        assertThat(register("alice", evidenceKey, uniqueKey("REQ"), "100", 0)
                .getResponse().getStatus()).isEqualTo(201);

        // 第一次借出：10 分钟照度 6 → 累计 60
        String loan1 = uniqueKey("LOAN");
        assertThat(borrow("alice", evidenceKey, loan1, "bob", utc(BASE.plusSeconds(3600)))
                .getResponse().getStatus()).isEqualTo(200);
        fixClock(BASE.plusSeconds(600));
        MvcResult firstReturn = returnLoan("alice", evidenceKey, uniqueKey("CMD"), loan1,
                true, 1L, List.of(segment(BASE, BASE.plusSeconds(600), "6")));
        assertThat(firstReturn.getResponse().getStatus()).isEqualTo(200);
        JsonNode afterFirst = doseDetail("alice", evidenceKey, 200);
        assertThat(afterFirst.get("cumulativeLuxMin").decimalValue())
                .isEqualByComparingTo(new BigDecimal("60"));
        assertThat(afterFirst.get("version").asInt()).isEqualTo(2);
        assertThat(afterFirst.get("doseBlocked").asBoolean()).isFalse();
        assertThat(afterFirst.get("returns")).hasSize(1);
        assertThat(afterFirst.get("returns").get(0).get("doseThisReturn").decimalValue())
                .isEqualByComparingTo(new BigDecimal("60"));
        assertThat(afterFirst.get("returns").get(0).get("cumulativeAfter").decimalValue())
                .isEqualByComparingTo(new BigDecimal("60"));

        // 第二次借出：10 分钟照度 4 → 累计 100，达到上限（含等于）即 DOSE_BLOCKED
        String loan2 = uniqueKey("LOAN");
        assertThat(borrow("alice", evidenceKey, loan2, "bob", utc(BASE.plusSeconds(3600)))
                .getResponse().getStatus()).isEqualTo(200);
        fixClock(BASE.plusSeconds(1200));
        MvcResult secondReturn = returnLoan("alice", evidenceKey, uniqueKey("CMD"), loan2,
                true, 2L, List.of(segment(BASE.plusSeconds(600), BASE.plusSeconds(1200), "4")));
        assertThat(secondReturn.getResponse().getStatus()).isEqualTo(200);
        JsonNode afterSecond = doseDetail("alice", evidenceKey, 200);
        assertThat(afterSecond.get("cumulativeLuxMin").decimalValue())
                .isEqualByComparingTo(new BigDecimal("100"));
        assertThat(afterSecond.get("cumulativeDisplay").asText()).isEqualTo("100.0000");
        assertThat(afterSecond.get("doseBlocked").asBoolean()).isTrue();
        assertThat(afterSecond.get("version").asInt()).isEqualTo(3);

        // 证物回 SEALED 但新增借出 422
        MvcResult chain = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        JsonNode view = objectMapper.readTree(chain.getResponse().getContentAsString());
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob",
                utc(BASE.plusSeconds(7200))).getResponse().getStatus()).isEqualTo(422);

        // 交接仍按原规则可行；保管权变化不清账，新保管人接手后仍 DOSE_BLOCKED
        assertThat(initiate("alice", evidenceKey, "carol").getResponse().getStatus()).isEqualTo(200);
        assertThat(accept("carol", evidenceKey).getResponse().getStatus()).isEqualTo(200);
        JsonNode afterTransfer = doseDetail("carol", evidenceKey, 200);
        assertThat(afterTransfer.get("cumulativeLuxMin").decimalValue())
                .isEqualByComparingTo(new BigDecimal("100"));
        assertThat(afterTransfer.get("doseBlocked").asBoolean()).isTrue();
        assertThat(afterTransfer.get("returns")).hasSize(2);
        assertThat(borrow("carol", evidenceKey, uniqueKey("LOAN"), "bob",
                utc(BASE.plusSeconds(7200))).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void overLimitReturnSucceedsAndSavesActualDose() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        assertThat(register("alice", evidenceKey, uniqueKey("REQ"), "50", 0)
                .getResponse().getStatus()).isEqualTo(201);
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));

        // 超限归还必须成功保存实际剂量（60 > 50），不能拒收
        fixClock(BASE.plusSeconds(600));
        MvcResult returned = returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey,
                true, 1L, List.of(segment(BASE, BASE.plusSeconds(600), "6")));
        assertThat(returned.getResponse().getStatus()).isEqualTo(200);
        JsonNode detail = doseDetail("alice", evidenceKey, 200);
        assertThat(detail.get("cumulativeLuxMin").decimalValue())
                .isEqualByComparingTo(new BigDecimal("60"));
        assertThat(detail.get("doseBlocked").asBoolean()).isTrue();
        // 不能拒收后继续外借
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob",
                utc(BASE.plusSeconds(7200))).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void doseDetailOnlyForCurrentCustodian() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        // 未登记上限：明细 404
        doseDetail("alice", evidenceKey, 404);
        register("alice", evidenceKey, uniqueKey("REQ"), "100", 0);

        // 非保管人 409
        doseDetail("mallory", evidenceKey, 409);

        // 交接后保管权变化：旧保管人 409，新保管人可查，账本不清零
        assertThat(initiate("alice", evidenceKey, "carol").getResponse().getStatus()).isEqualTo(200);
        assertThat(accept("carol", evidenceKey).getResponse().getStatus()).isEqualTo(200);
        doseDetail("alice", evidenceKey, 409);
        JsonNode detail = doseDetail("carol", evidenceKey, 200);
        assertThat(detail.get("limitLuxMinutes").decimalValue())
                .isEqualByComparingTo(new BigDecimal("100"));
        assertThat(detail.get("registeredBy").asText()).isEqualTo("alice");
    }

    @Test
    void displayRoundsHalfUpButDoesNotAffectJudgment() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        assertThat(register("alice", evidenceKey, uniqueKey("REQ"), "0.017", 0)
                .getResponse().getStatus()).isEqualTo(201);
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));

        // 1 秒照度 1 → 精确剂量 1/60 ≈ 0.016666...，显示 HALF_UP 四位于 0.0167，但判定用精确值
        fixClock(BASE.plusSeconds(1));
        MvcResult returned = returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey,
                true, 1L, List.of(segment(BASE, BASE.plusSeconds(1), "1")));
        assertThat(returned.getResponse().getStatus()).isEqualTo(200);
        JsonNode detail = doseDetail("alice", evidenceKey, 200);
        assertThat(detail.get("cumulativeLuxMin").decimalValue())
                .isEqualByComparingTo(new BigDecimal("0.016666667"));
        assertThat(detail.get("cumulativeDisplay").asText()).isEqualTo("0.0167");
        // 精确值 0.016666... < 0.017：未达上限，不受显示值 0.0167 影响
        assertThat(detail.get("doseBlocked").asBoolean()).isFalse();
        assertThat(detail.get("returns").get(0).get("segments").get(0).get("doseDisplay").asText())
                .isEqualTo("0.0167");
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob",
                utc(BASE.plusSeconds(7200))).getResponse().getStatus()).isEqualTo(200);
    }
}
