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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 剂量归还校验测试：段覆盖/重叠/精度、期望版本、重放不重复累计、
 * 封条异常走原 SEAL_BROKEN、未登记上限证物不受新规则影响。
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
        mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private void register(String actor, String evidenceKey, String limit) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", uniqueKey("REQ"));
        body.put("limitLuxMinutes", new BigDecimal(limit));
        body.put("expectedVersion", 0);
        MvcResult result = mockMvc.perform(post("/api/evidence/{key}/dose-limit", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private void borrow(String actor, String evidenceKey, String loanKey,
                        String borrower, LocalDateTime dueAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", loanKey);
        body.put("borrowerId", borrower);
        body.put("purpose", "光照实验");
        body.put("dueAt", dueAt.toString());
        MvcResult result = mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
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

    private JsonNode doseDetail(String actor, String evidenceKey, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/dose", evidenceKey)
                        .header(ACTOR_HEADER, actor))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void returnWithDoseFreezesSegmentsAndCumulative() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        fixClock(BASE);
        register("alice", evidenceKey, "1000");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(7200)));

        // 三段（乱序提交）：10分钟×6 + 15分钟×4 + 5分钟×0.5 = 60 + 60 + 2.5 = 122.5
        fixClock(BASE.plusSeconds(1800));
        List<Map<String, Object>> segments = new ArrayList<>();
        segments.add(segment(BASE.plusSeconds(600), BASE.plusSeconds(1500), "4"));
        segments.add(segment(BASE.plusSeconds(1500), BASE.plusSeconds(1800), "0.5"));
        segments.add(segment(BASE, BASE.plusSeconds(600), "6"));
        MvcResult returned = returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey,
                true, 1L, segments);
        assertThat(returned.getResponse().getStatus()).isEqualTo(200);

        JsonNode detail = doseDetail("alice", evidenceKey, 200);
        assertThat(detail.get("cumulativeLuxMin").decimalValue())
                .isEqualByComparingTo(new BigDecimal("122.5"));
        assertThat(detail.get("cumulativeDisplay").asText()).isEqualTo("122.5000");
        assertThat(detail.get("version").asInt()).isEqualTo(2);
        JsonNode firstReturn = detail.get("returns").get(0);
        assertThat(firstReturn.get("loanKey").asText()).isEqualTo(loanKey);
        assertThat(firstReturn.get("doseThisReturn").decimalValue())
                .isEqualByComparingTo(new BigDecimal("122.5"));
        assertThat(firstReturn.get("cumulativeAfter").decimalValue())
                .isEqualByComparingTo(new BigDecimal("122.5"));
        // 冻结的剂量段按时间顺序编号，照度与区间原样保存
        JsonNode frozen = firstReturn.get("segments");
        assertThat(frozen).hasSize(3);
        assertThat(frozen.get(0).get("segmentNo").asInt()).isEqualTo(1);
        assertThat(frozen.get(0).get("illuminanceLux").decimalValue())
                .isEqualByComparingTo(new BigDecimal("6"));
        assertThat(frozen.get(0).get("doseLuxMin").decimalValue())
                .isEqualByComparingTo(new BigDecimal("60"));
        assertThat(frozen.get(1).get("segmentNo").asInt()).isEqualTo(2);
        assertThat(frozen.get(1).get("doseLuxMin").decimalValue())
                .isEqualByComparingTo(new BigDecimal("60"));
        assertThat(frozen.get(2).get("segmentNo").asInt()).isEqualTo(3);
        assertThat(frozen.get(2).get("doseLuxMin").decimalValue())
                .isEqualByComparingTo(new BigDecimal("2.5"));
        assertThat(frozen.get(2).get("doseDisplay").asText()).isEqualTo("2.5000");
    }

    @Test
    void returnWithMissingOrInvalidSegmentsReturns400AndKeepsLoanActive() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        fixClock(BASE);
        register("alice", evidenceKey, "1000");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(7200)));
        fixClock(BASE.plusSeconds(1200));

        Instant t0 = BASE;
        Instant t10 = BASE.plusSeconds(600);
        Instant t15 = BASE.plusSeconds(900);
        Instant t20 = BASE.plusSeconds(1200);

        // 缺段（null 与空列表）
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, 1L, null)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, 1L, List.of())
                .getResponse().getStatus()).isEqualTo(400);
        // 重叠
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, 1L,
                List.of(segment(t0, t10, "1"), segment(BASE.plusSeconds(300), t20, "1")))
                .getResponse().getStatus()).isEqualTo(400);
        // 缺口
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, 1L,
                List.of(segment(t0, t10, "1"), segment(t15, t20, "1")))
                .getResponse().getStatus()).isEqualTo(400);
        // 未从实际借出时刻开始
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, 1L,
                List.of(segment(BASE.plusSeconds(60), t20, "1")))
                .getResponse().getStatus()).isEqualTo(400);
        // 未覆盖到实际归还时刻
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, 1L,
                List.of(segment(t0, t15, "1")))
                .getResponse().getStatus()).isEqualTo(400);
        // 负照度 / 照度超过3位小数 / 结束不晚于开始
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, 1L,
                List.of(segment(t0, t20, "-1"))).getResponse().getStatus()).isEqualTo(400);
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, 1L,
                List.of(segment(t0, t20, "1.0001"))).getResponse().getStatus()).isEqualTo(400);
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, 1L,
                List.of(segment(t0, t0, "1"))).getResponse().getStatus()).isEqualTo(400);
        // 超过 20 段
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(segment(t0.plusSeconds(i), t0.plusSeconds(i + 1), "1"));
        }
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, 1L, tooMany)
                .getResponse().getStatus()).isEqualTo(400);

        // 全部失败：不归还、不记剂量
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("BORROWED");
        JsonNode detail = doseDetail("alice", evidenceKey, 200);
        assertThat(detail.get("cumulativeLuxMin").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(detail.get("version").asInt()).isEqualTo(1);
        assertThat(detail.get("returns")).isEmpty();

        // 合法归还仍可进行
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, 1L,
                List.of(segment(t0, t20, "1"))).getResponse().getStatus()).isEqualTo(200);
        assertThat(doseDetail("alice", evidenceKey, 200).get("cumulativeLuxMin").decimalValue())
                .isEqualByComparingTo(new BigDecimal("20"));
    }

    @Test
    void returnWithWrongExpectedVersionReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        fixClock(BASE);
        register("alice", evidenceKey, "1000");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(7200)));
        fixClock(BASE.plusSeconds(600));

        List<Map<String, Object>> segments = List.of(segment(BASE, BASE.plusSeconds(600), "6"));
        // 缺期望版本 / 版本不符 → 409，不归还
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, null, segments)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, 5L, segments)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("BORROWED");

        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, 1L, segments)
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void replayedReturnDoesNotAccumulateTwice() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        String commandKey = uniqueKey("CMD");
        intake("alice", evidenceKey);
        fixClock(BASE);
        register("alice", evidenceKey, "1000");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(7200)));
        fixClock(BASE.plusSeconds(600));

        List<Map<String, Object>> segments = List.of(segment(BASE, BASE.plusSeconds(600), "6"));
        MvcResult first = returnLoan("alice", evidenceKey, commandKey, loanKey, true, 1L, segments);
        MvcResult replay = returnLoan("alice", evidenceKey, commandKey, loanKey, true, 1L, segments);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 重放不重复累计：累计 60、一条归还、一段剂量段
        JsonNode detail = doseDetail("alice", evidenceKey, 200);
        assertThat(detail.get("cumulativeLuxMin").decimalValue())
                .isEqualByComparingTo(new BigDecimal("60"));
        assertThat(detail.get("version").asInt()).isEqualTo(2);
        assertThat(detail.get("returns")).hasSize(1);
        assertThat(detail.get("returns").get(0).get("segments")).hasSize(1);

        // 同键改参 409
        assertThat(returnLoan("alice", evidenceKey, commandKey, loanKey, true, 1L,
                List.of(segment(BASE, BASE.plusSeconds(600), "7"))).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void failedReturnDoesNotOccupyCommandKey() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        String commandKey = uniqueKey("CMD");
        intake("alice", evidenceKey);
        fixClock(BASE);
        register("alice", evidenceKey, "1000");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(7200)));
        fixClock(BASE.plusSeconds(600));

        // 首次段非法（缺口）400，不占 commandKey
        assertThat(returnLoan("alice", evidenceKey, commandKey, loanKey, true, 1L,
                List.of(segment(BASE, BASE.plusSeconds(300), "6"))).getResponse().getStatus())
                .isEqualTo(400);
        // 同键合法参数重试成功
        assertThat(returnLoan("alice", evidenceKey, commandKey, loanKey, true, 1L,
                List.of(segment(BASE, BASE.plusSeconds(600), "6"))).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void sealBrokenReturnFollowsOriginalFlowWithoutDose() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        fixClock(BASE);
        register("alice", evidenceKey, "1000");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(7200)));
        fixClock(BASE.plusSeconds(600));

        // 封条异常携带剂量段 → 400（原流程不接受剂量字段）
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, false, null,
                List.of(segment(BASE, BASE.plusSeconds(600), "6"))).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("BORROWED");

        // 封条异常不带剂量段 → 原 SEAL_BROKEN 流程，不记剂量
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, false, null, null)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEAL_BROKEN");
        JsonNode detail = doseDetail("alice", evidenceKey, 200);
        assertThat(detail.get("cumulativeLuxMin").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(detail.get("version").asInt()).isEqualTo(1);
        assertThat(detail.get("returns")).isEmpty();
    }

    @Test
    void unregisteredEvidenceReturnUnaffectedByNewRules() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", evidenceKey);
        fixClock(BASE);
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(7200)));
        fixClock(BASE.plusSeconds(600));

        // 未登记上限：携带剂量段 → 400；不携带 → 原流程正常归还
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, 1L,
                List.of(segment(BASE, BASE.plusSeconds(600), "6"))).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, null, null)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");
        doseDetail("alice", evidenceKey, 404);

        // 归还后可再次借出（不受新规则影响）
        borrow("alice", evidenceKey, uniqueKey("LOAN"), "carol", utc(BASE.plusSeconds(7200)));
    }
}
