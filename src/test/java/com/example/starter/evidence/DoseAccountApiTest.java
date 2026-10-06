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
 * 剂量上限登记 API 测试：主流程、不可变性、期望版本、权限先于重放与幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DoseAccountApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    /** 测试基准时刻（UTC）。 */
    static final Instant BASE = Instant.parse("2026-09-22T08:00:00Z");

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
        MvcResult result = mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return result;
    }

    private MvcResult registerCap(String actor, String evidenceKey, String requestId,
                                  Object cap, Integer expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("capLuxMinutes", cap);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/evidence/{key}/dose-cap", evidenceKey)
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

    @Test
    void registerCapCreatesImmutableLedger() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        MvcResult result = registerCap("alice", evidenceKey, uniqueKey("REQ"), "100", 0);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode view = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(view.get("evidenceKey").asText()).isEqualTo(evidenceKey);
        assertThat(view.get("capLuxMinutes").decimalValue()).isEqualByComparingTo("100");
        assertThat(view.get("cumulativeLuxMinutes").decimalValue()).isEqualByComparingTo("0");
        assertThat(view.get("cumulativeDisplay").asText()).isEqualTo("0.0000");
        assertThat(view.get("doseBlocked").asBoolean()).isFalse();
        assertThat(view.get("version").asInt()).isEqualTo(1);
        assertThat(view.get("registeredBy").asText()).isEqualTo("alice");
        assertThat(view.get("returns")).isEmpty();

        // 上限不可变：重复登记（新 requestId）拒绝
        assertThat(registerCap("alice", evidenceKey, uniqueKey("REQ"), "200", 1)
                .getResponse().getStatus()).isEqualTo(409);
        JsonNode ledger = ledger("alice", evidenceKey);
        assertThat(ledger.get("capLuxMinutes").decimalValue()).isEqualByComparingTo("100");
    }

    @Test
    void registerCapByNonCustodianRejectedBeforeReplay() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String requestId = uniqueKey("REQ");
        intake("alice", evidenceKey);
        assertThat(registerCap("alice", evidenceKey, requestId, "100", 0)
                .getResponse().getStatus()).isEqualTo(201);

        // 权限先于重放：非保管人持同一 requestId 重放，命中权限拒绝而非首次结果
        assertThat(registerCap("mallory", evidenceKey, requestId, "100", 0)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void registerCapWithWrongExpectedVersionReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(registerCap("alice", evidenceKey, uniqueKey("REQ"), "100", 1)
                .getResponse().getStatus()).isEqualTo(409);
        // 失败不占键：同 requestId 修正参数后可成功
        String requestId = uniqueKey("REQ");
        assertThat(registerCap("alice", evidenceKey, requestId, "100", 5)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(registerCap("alice", evidenceKey, requestId, "100", 0)
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void registerCapWithInvalidValueReturns400() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        // 零、负值、超过3位小数、缺字段均 400
        assertThat(registerCap("alice", evidenceKey, uniqueKey("REQ"), "0", 0)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(registerCap("alice", evidenceKey, uniqueKey("REQ"), "-1", 0)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(registerCap("alice", evidenceKey, uniqueKey("REQ"), "0.0001", 0)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(registerCap("alice", evidenceKey, uniqueKey("REQ"), null, 0)
                .getResponse().getStatus()).isEqualTo(400);
        // 最多3位小数的正值合法
        assertThat(registerCap("alice", evidenceKey, uniqueKey("REQ"), "0.001", 0)
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void registerCapOnNonSealedEvidenceRejected() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob",
                utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(200);
        // 借出期间不可登记
        assertThat(registerCap("alice", evidenceKey, uniqueKey("REQ"), "100", 0)
                .getResponse().getStatus()).isEqualTo(409);
        // 证物不存在
        assertThat(registerCap("alice", uniqueKey("MISSING"), uniqueKey("REQ"), "100", 0)
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void registerCapReplayReturnsFirstResponseAndChangedParamsConflict() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String requestId = uniqueKey("REQ");
        intake("alice", evidenceKey);

        MvcResult first = registerCap("alice", evidenceKey, requestId, "100", 0);
        MvcResult replay = registerCap("alice", evidenceKey, requestId, "100", 0);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 同键异参 409；账本仍只有一份
        String other = uniqueKey("EV");
        intake("alice", other);
        assertThat(registerCap("alice", other, requestId, "100", 0)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(ledger("alice", evidenceKey).get("version").asInt()).isEqualTo(1);
    }
}
