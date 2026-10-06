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
 * 剂量账本 API 测试（真实 H2）：上限登记、剂量归还、DOSE_BLOCKED 联合裁决、
 * 幂等重放、权限先于重放与展示舍入不参与判定（固定时钟驱动）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DoseLedgerApiTest {

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

    private Map<String, Object> seg(Instant start, Instant end, String lux) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("startAt", utc(start).toString());
        segment.put("endAt", utc(end).toString());
        segment.put("lux", new BigDecimal(lux));
        return segment;
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

    private MvcResult registerLimit(String actor, String evidenceKey, String requestId,
                                    String limit, Long expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        if (limit != null) {
            body.put("limitLuxMinutes", new BigDecimal(limit));
        }
        if (expectedVersion != null) {
            body.put("expectedVersion", expectedVersion);
        }
        return mockMvc.perform(post("/api/evidence/{key}/dose-limit", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult registerLimit(String actor, String evidenceKey, String limit) throws Exception {
        return registerLimit(actor, evidenceKey, uniqueKey("REQ"), limit, null);
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

    private MvcResult doseLedgerRaw(String actor, String evidenceKey) throws Exception {
        return mockMvc.perform(get("/api/evidence/{key}/dose-ledger", evidenceKey)
                        .header(ACTOR_HEADER, actor))
                .andReturn();
    }

    private JsonNode doseLedger(String actor, String evidenceKey) throws Exception {
        MvcResult result = doseLedgerRaw(actor, evidenceKey);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * 断言 BigDecimal 字段数值（Jackson 树模型默认将小数解析为 DoubleNode，按数值比较）。
     */
    private static void assertDecimal(JsonNode node, String field, String expected) {
        assertThat(node.get(field).decimalValue()).isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    void synthesisLimit100ReachesBlockedAndRejectsThirdBorrow() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        MvcResult registered = registerLimit("alice", evidenceKey, "100");
        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
        JsonNode ledger = objectMapper.readTree(registered.getResponse().getContentAsString());
        assertDecimal(ledger, "limitLuxMinutes", "100");
        assertDecimal(ledger, "cumulativeLuxMinutes", "0");
        assertThat(ledger.get("status").asText()).isEqualTo("OK");
        assertThat(ledger.get("version").asLong()).isEqualTo(1);

        // 第一次：10 分钟照度 6，累计 60
        String loan1 = uniqueKey("LOAN");
        assertThat(borrow("alice", evidenceKey, loan1, "bob", utc(BASE.plusSeconds(3600)))
                .getResponse().getStatus()).isEqualTo(200);
        fixClock(BASE.plusSeconds(600));
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loan1, true,
                List.of(seg(BASE, BASE.plusSeconds(600), "6")))
                .getResponse().getStatus()).isEqualTo(200);

        JsonNode after60 = doseLedger("alice", evidenceKey);
        assertDecimal(after60, "cumulativeLuxMinutes", "60");
        assertThat(after60.get("status").asText()).isEqualTo("OK");
        assertThat(after60.get("version").asLong()).isEqualTo(2);
        assertThat(after60.get("returns")).hasSize(1);
        assertDecimal(after60.get("returns").get(0), "doseLuxMinutes", "60");
        assertThat(after60.get("returns").get(0).get("segments")).hasSize(1);
        assertDecimal(after60.get("returns").get(0).get("segments").get(0), "lux", "6");

        // 第二次：10 分钟照度 4，累计 100，达到上限
        String loan2 = uniqueKey("LOAN");
        assertThat(borrow("alice", evidenceKey, loan2, "bob", utc(BASE.plusSeconds(3600)))
                .getResponse().getStatus()).isEqualTo(200);
        fixClock(BASE.plusSeconds(1200));
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loan2, true,
                List.of(seg(BASE.plusSeconds(600), BASE.plusSeconds(1200), "4")))
                .getResponse().getStatus()).isEqualTo(200);

        JsonNode blocked = doseLedger("alice", evidenceKey);
        assertDecimal(blocked, "cumulativeLuxMinutes", "100");
        assertThat(blocked.get("status").asText()).isEqualTo("DOSE_BLOCKED");
        assertThat(blocked.get("version").asLong()).isEqualTo(3);
        // 证物回到 SEALED，但达到上限视为 DOSE_BLOCKED
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");

        // 第三次借出被拒绝 422
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob", utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void registerLimitValidatesActorParamsAndImmutability() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        // 非保管人 409
        assertThat(registerLimit("mallory", evidenceKey, "100").getResponse().getStatus()).isEqualTo(409);
        // 非法上限 400：零、负值、超过3位小数、缺失
        assertThat(registerLimit("alice", evidenceKey, "0").getResponse().getStatus()).isEqualTo(400);
        assertThat(registerLimit("alice", evidenceKey, "-1").getResponse().getStatus()).isEqualTo(400);
        assertThat(registerLimit("alice", evidenceKey, "1.0001").getResponse().getStatus()).isEqualTo(400);
        assertThat(registerLimit("alice", evidenceKey, null).getResponse().getStatus()).isEqualTo(400);
        // 期望版本不匹配 409；期望版本 0 可登记
        assertThat(registerLimit("alice", evidenceKey, uniqueKey("REQ"), "100", 1L)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(registerLimit("alice", evidenceKey, uniqueKey("REQ"), "100", 0L)
                .getResponse().getStatus()).isEqualTo(201);
        // 重复登记 409（上限不可变）
        assertThat(registerLimit("alice", evidenceKey, "200").getResponse().getStatus()).isEqualTo(409);
        assertThat(registerLimit("alice", evidenceKey, uniqueKey("REQ"), "100", 0L)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(doseLedger("alice", evidenceKey).get("limitLuxMinutes").decimalValue())
                .isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    void registerLimitRejectedWhenBorrowedOrSealBroken() throws Exception {
        fixClock(BASE);
        String borrowed = uniqueKey("EV");
        intake("alice", borrowed);
        borrow("alice", borrowed, uniqueKey("LOAN"), "bob", utc(BASE.plusSeconds(3600)));
        assertThat(registerLimit("alice", borrowed, "100").getResponse().getStatus()).isEqualTo(409);

        String broken = uniqueKey("EV");
        intake("alice", broken);
        assertThat(inspect("alice", broken, false).getResponse().getStatus()).isEqualTo(200);
        assertThat(registerLimit("alice", broken, "100").getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void failedRegisterDoesNotOccupyRequestId() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        String requestId = uniqueKey("REQ");
        // 参数非法失败不占键
        assertThat(registerLimit("alice", evidenceKey, requestId, "0", null)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(registerLimit("alice", evidenceKey, requestId, "100", null)
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void registerReplayReturnsFirstResultAndParamChangeConflicts() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        String requestId = uniqueKey("REQ");
        MvcResult first = registerLimit("alice", evidenceKey, requestId, "100", null);
        MvcResult replay = registerLimit("alice", evidenceKey, requestId, "100", null);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 同键改参 409
        assertThat(registerLimit("alice", evidenceKey, requestId, "200", null)
                .getResponse().getStatus()).isEqualTo(409);
        // 账本仍一条，上限未变
        JsonNode ledger = doseLedger("alice", evidenceKey);
        assertDecimal(ledger, "limitLuxMinutes", "100");
        assertThat(ledger.get("version").asLong()).isEqualTo(1);
    }

    @Test
    void dosePermissionCheckedBeforeReplay() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        String requestId = uniqueKey("REQ");
        assertThat(registerLimit("alice", evidenceKey, requestId, "100", null)
                .getResponse().getStatus()).isEqualTo(201);
        // 保管权交接给 carol
        assertThat(initiate("alice", evidenceKey, "carol").getResponse().getStatus()).isEqualTo(200);
        assertThat(accept("carol", evidenceKey).getResponse().getStatus()).isEqualTo(200);
        // 原保管人重放同键同参：权限先于重放，409 而非首次结果
        assertThat(registerLimit("alice", evidenceKey, requestId, "100", null)
                .getResponse().getStatus()).isEqualTo(409);
        // 新保管人用同键同参：身份含操作者，指纹不同 409
        assertThat(registerLimit("carol", evidenceKey, requestId, "100", null)
                .getResponse().getStatus()).isEqualTo(409);
        // 账本不随交接变化
        assertThat(doseLedger("carol", evidenceKey).get("version").asLong()).isEqualTo(1);
    }

    @Test
    void returnWithInvalidSegmentsReturns400AndKeepsLoanActive() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        registerLimit("alice", evidenceKey, "1000");
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(600));

        // 缺段 400（不带 segments 与空列表）
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, null)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, List.of())
                .getResponse().getStatus()).isEqualTo(400);
        // 超过 20 段 400
        List<Map<String, Object>> tooMany = new ArrayList<>();
        Instant cursor = BASE;
        for (int i = 0; i < 21; i++) {
            tooMany.add(seg(cursor, cursor.plusSeconds(30), "1"));
            cursor = cursor.plusSeconds(30);
        }
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, tooMany)
                .getResponse().getStatus()).isEqualTo(400);
        // 首段不等于实际借出时刻 400
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(seg(BASE.plusSeconds(60), BASE.plusSeconds(600), "1")))
                .getResponse().getStatus()).isEqualTo(400);
        // 区间重叠 400
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(seg(BASE, BASE.plusSeconds(300), "1"),
                        seg(BASE.plusSeconds(120), BASE.plusSeconds(600), "1")))
                .getResponse().getStatus()).isEqualTo(400);
        // 区间断档（未完整覆盖）400
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(seg(BASE, BASE.plusSeconds(300), "1"),
                        seg(BASE.plusSeconds(400), BASE.plusSeconds(600), "1")))
                .getResponse().getStatus()).isEqualTo(400);
        // 开始不早于结束 400
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(seg(BASE, BASE, "1")))
                .getResponse().getStatus()).isEqualTo(400);
        // 负照度 400
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(seg(BASE, BASE.plusSeconds(600), "-1")))
                .getResponse().getStatus()).isEqualTo(400);
        // 照度超过3位小数 400
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(seg(BASE, BASE.plusSeconds(600), "1.0001")))
                .getResponse().getStatus()).isEqualTo(400);
        // 末段晚于服务端当前时刻 400
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(seg(BASE, BASE.plusSeconds(1200), "1")))
                .getResponse().getStatus()).isEqualTo(400);

        // 全部失败：不归还、不记剂量
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("BORROWED");
        JsonNode ledger = doseLedger("alice", evidenceKey);
        assertDecimal(ledger, "cumulativeLuxMinutes", "0");
        assertThat(ledger.get("returns")).isEmpty();

        // 合法归还成功
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(seg(BASE, BASE.plusSeconds(600), "1")))
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void failedReturnDoesNotOccupyCommandKey() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        registerLimit("alice", evidenceKey, "1000");
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(600));

        String commandKey = uniqueKey("CMD");
        // 缺段失败不占键
        assertThat(returnLoan("alice", evidenceKey, commandKey, loanKey, true, null)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(returnLoan("alice", evidenceKey, commandKey, loanKey, true,
                List.of(seg(BASE, BASE.plusSeconds(600), "6")))
                .getResponse().getStatus()).isEqualTo(200);
        assertDecimal(doseLedger("alice", evidenceKey), "cumulativeLuxMinutes", "60");
    }

    @Test
    void returnWithSegmentsButNoLimitRegisteredReturns400() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        // 未登记上限：不接受剂量区间（原借出不受新规则影响）
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(seg(BASE, BASE.plusSeconds(600), "1")))
                .getResponse().getStatus()).isEqualTo(400);
        // 不带区间按原规则归还成功
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, null)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void multiSegmentDoseAccumulatesExactly() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        registerLimit("alice", evidenceKey, "10");
        String loan1 = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loan1, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(330));
        // 三段：2分钟@2.5 = 5；90秒@2 = 3；2分钟@0.125 = 0.25；合计 8.25
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loan1, true,
                List.of(seg(BASE, BASE.plusSeconds(120), "2.5"),
                        seg(BASE.plusSeconds(120), BASE.plusSeconds(210), "2"),
                        seg(BASE.plusSeconds(210), BASE.plusSeconds(330), "0.125")))
                .getResponse().getStatus()).isEqualTo(200);

        JsonNode ledger = doseLedger("alice", evidenceKey);
        assertDecimal(ledger, "cumulativeLuxMinutes", "8.25");
        assertThat(ledger.get("status").asText()).isEqualTo("OK");
        JsonNode segments = ledger.get("returns").get(0).get("segments");
        assertThat(segments).hasSize(3);
        assertDecimal(segments.get(0), "doseLuxMinutes", "5");
        assertDecimal(segments.get(1), "doseLuxMinutes", "3");
        assertDecimal(segments.get(2), "doseLuxMinutes", "0.25");

        // 第二次：105 秒 @1 = 1.75，累计恰好 10，达到上限
        String loan2 = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loan2, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(435));
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loan2, true,
                List.of(seg(BASE.plusSeconds(330), BASE.plusSeconds(435), "1")))
                .getResponse().getStatus()).isEqualTo(200);
        JsonNode blocked = doseLedger("alice", evidenceKey);
        assertDecimal(blocked, "cumulativeLuxMinutes", "10");
        assertThat(blocked.get("status").asText()).isEqualTo("DOSE_BLOCKED");
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob", utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void displayRoundingDoesNotAffectJudgment() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        registerLimit("alice", evidenceKey, "0.667");
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(21600)));
        fixClock(BASE.plusSeconds(20009));
        // 20009 秒 @0.002 lux = 0.666966... 勒克斯分钟，展示四舍五入为 0.6670，但精确值未达上限
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(seg(BASE, BASE.plusSeconds(20009), "0.002")))
                .getResponse().getStatus()).isEqualTo(200);
        MvcResult ledgerResult = doseLedgerRaw("alice", evidenceKey);
        assertThat(ledgerResult.getResponse().getStatus()).isEqualTo(200);
        // 展示值 HALF_UP 四位：原始 JSON 中为 0.6670
        assertThat(ledgerResult.getResponse().getContentAsString())
                .contains("\"cumulativeLuxMinutes\":0.6670");
        JsonNode ledger = objectMapper.readTree(ledgerResult.getResponse().getContentAsString());
        assertThat(ledger.get("status").asText()).isEqualTo("OK");
        // 判定使用精确值：仍可借出
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob",
                utc(BASE.plusSeconds(21600))).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void overLimitReturnSucceedsAndBlocksNextBorrow() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        registerLimit("alice", evidenceKey, "10");
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(1200));
        // 超限归还必须成功保存实际剂量，不能拒收
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(seg(BASE, BASE.plusSeconds(1200), "1")))
                .getResponse().getStatus()).isEqualTo(200);
        JsonNode ledger = doseLedger("alice", evidenceKey);
        assertDecimal(ledger, "cumulativeLuxMinutes", "20");
        assertThat(ledger.get("status").asText()).isEqualTo("DOSE_BLOCKED");
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");
        // 不能拒收后继续外借
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob", utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void replayReturnDoesNotDoubleAccumulate() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        registerLimit("alice", evidenceKey, "100");
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(600));

        String commandKey = uniqueKey("CMD");
        List<Map<String, Object>> segments = List.of(seg(BASE, BASE.plusSeconds(600), "6"));
        MvcResult first = returnLoan("alice", evidenceKey, commandKey, loanKey, true, segments);
        MvcResult replay = returnLoan("alice", evidenceKey, commandKey, loanKey, true, segments);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放不重复累计
        JsonNode ledger = doseLedger("alice", evidenceKey);
        assertDecimal(ledger, "cumulativeLuxMinutes", "60");
        assertThat(ledger.get("version").asLong()).isEqualTo(2);
        assertThat(ledger.get("returns")).hasSize(1);
    }

    @Test
    void custodyTransferKeepsLedgerAndNewCustodianInheritsBlock() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        registerLimit("alice", evidenceKey, "5");
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(600));
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(seg(BASE, BASE.plusSeconds(600), "1")))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(doseLedger("alice", evidenceKey).get("status").asText())
                .isEqualTo("DOSE_BLOCKED");

        // 交接仍按原规则可行，保管权变化不清账
        assertThat(initiate("alice", evidenceKey, "carol").getResponse().getStatus()).isEqualTo(200);
        assertThat(accept("carol", evidenceKey).getResponse().getStatus()).isEqualTo(200);
        JsonNode ledger = doseLedger("carol", evidenceKey);
        assertDecimal(ledger, "cumulativeLuxMinutes", "10");
        assertThat(ledger.get("status").asText()).isEqualTo("DOSE_BLOCKED");
        assertThat(ledger.get("returns")).hasSize(1);
        // 新保管人借出仍被 DOSE_BLOCKED 拒绝；原保管人不再有权查明细
        assertThat(borrow("carol", evidenceKey, uniqueKey("LOAN"), "bob", utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(doseLedgerRaw("alice", evidenceKey).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void sealBrokenReturnStillRecordsDose() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        registerLimit("alice", evidenceKey, "100");
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(600));
        // 封条异常继续走原 SEAL_BROKEN，剂量照常核算
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, false,
                List.of(seg(BASE, BASE.plusSeconds(600), "6")))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("SEAL_BROKEN");
        JsonNode ledger = doseLedger("alice", evidenceKey);
        assertDecimal(ledger, "cumulativeLuxMinutes", "60");
        assertThat(ledger.get("returns")).hasSize(1);
        // 封条异常：禁止借出 422
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob", utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void doseLedgerDetailOnlyForCurrentCustodian() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        // 未登记：保管人 404；非保管人 409（权限优先）
        assertThat(doseLedgerRaw("alice", evidenceKey).getResponse().getStatus()).isEqualTo(404);
        assertThat(doseLedgerRaw("mallory", evidenceKey).getResponse().getStatus()).isEqualTo(409);
        registerLimit("alice", evidenceKey, "100");
        assertThat(doseLedgerRaw("mallory", evidenceKey).getResponse().getStatus()).isEqualTo(409);
        assertThat(doseLedgerRaw("alice", evidenceKey).getResponse().getStatus()).isEqualTo(200);
        // 证物不存在 404
        assertThat(doseLedgerRaw("alice", uniqueKey("MISSING")).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void zeroLuxSegmentAccumulatesZero() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        registerLimit("alice", evidenceKey, "1");
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        fixClock(BASE.plusSeconds(600));
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true,
                List.of(seg(BASE, BASE.plusSeconds(600), "0")))
                .getResponse().getStatus()).isEqualTo(200);
        JsonNode ledger = doseLedger("alice", evidenceKey);
        assertDecimal(ledger, "cumulativeLuxMinutes", "0");
        assertThat(ledger.get("status").asText()).isEqualTo("OK");
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), "bob", utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void exactlyTwentySegmentsAllowed() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        registerLimit("alice", evidenceKey, "1000");
        String loanKey = uniqueKey("LOAN");
        borrow("alice", evidenceKey, loanKey, "bob", utc(BASE.plusSeconds(3600)));
        List<Map<String, Object>> segments = new ArrayList<>();
        Instant cursor = BASE;
        for (int i = 0; i < 20; i++) {
            segments.add(seg(cursor, cursor.plusSeconds(60), "1"));
            cursor = cursor.plusSeconds(60);
        }
        fixClock(BASE.plusSeconds(1200));
        assertThat(returnLoan("alice", evidenceKey, uniqueKey("CMD"), loanKey, true, segments)
                .getResponse().getStatus()).isEqualTo(200);
        JsonNode ledger = doseLedger("alice", evidenceKey);
        assertDecimal(ledger, "cumulativeLuxMinutes", "20");
        assertThat(ledger.get("returns").get(0).get("segments")).hasSize(20);
    }
}
