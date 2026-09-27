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
 * 证物检验预约 API 测试（真实 H2）：主流程、失败分支、批量原子性、
 * 幂等边界、状态机与诊断查询（固定时钟驱动，时间均为 UTC）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExamApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    /** 测试基准时刻（UTC）。 */
    private static final Instant BASE = Instant.parse("2026-09-27T08:00:00Z");

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

    private MvcResult createBench(String actor, String benchKey, int capacity) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("benchKey", benchKey);
        body.put("capacity", capacity);
        return mockMvc.perform(post("/api/exam/benches")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult reserve(String actor, String commandKey, String reservationKey,
                              String evidenceKey, String benchKey,
                              LocalDateTime startAt, LocalDateTime endAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("reservationKey", reservationKey);
        body.put("evidenceKey", evidenceKey);
        body.put("benchKey", benchKey);
        body.put("startAt", startAt == null ? null : startAt.toString());
        body.put("endAt", endAt == null ? null : endAt.toString());
        return mockMvc.perform(post("/api/exam/reservations")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult reserve(String actor, String reservationKey, String evidenceKey,
                              String benchKey, LocalDateTime startAt, LocalDateTime endAt)
            throws Exception {
        return reserve(actor, uniqueKey("CMD"), reservationKey, evidenceKey, benchKey,
                startAt, endAt);
    }

    private MvcResult reserveBatch(String actor, String commandKey,
                                   List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("items", items);
        return mockMvc.perform(post("/api/exam/reservations/batch")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private Map<String, Object> batchItem(String reservationKey, String evidenceKey,
                                          String benchKey, LocalDateTime startAt,
                                          LocalDateTime endAt) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("reservationKey", reservationKey);
        item.put("evidenceKey", evidenceKey);
        item.put("benchKey", benchKey);
        item.put("startAt", startAt.toString());
        item.put("endAt", endAt.toString());
        return item;
    }

    private MvcResult startExam(String actor, String reservationKey, String commandKey)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(post("/api/exam/reservations/{key}/start", reservationKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult complete(String actor, String reservationKey, String commandKey,
                               String resultSummary, String newSealNo, boolean newSealIntact,
                               String newSealNote) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("resultSummary", resultSummary);
        body.put("newSealNo", newSealNo);
        body.put("newSealIntact", newSealIntact);
        body.put("newSealNote", newSealNote);
        return mockMvc.perform(post("/api/exam/reservations/{key}/complete", reservationKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult cancel(String actor, String reservationKey, String commandKey, String reason)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/exam/reservations/{key}/cancel", reservationKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult borrow(String actor, String evidenceKey, String borrower,
                             LocalDateTime dueAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", borrower);
        body.put("purpose", "鉴定用");
        body.put("dueAt", dueAt.toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
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

    private JsonNode reservationDetail(String reservationKey) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/exam/reservations/{key}", reservationKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode reservationsOf(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/exam/reservations/by-evidence/{key}", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode benchSchedule(String benchKey, LocalDateTime from, LocalDateTime to)
            throws Exception {
        MvcResult result = mockMvc.perform(get("/api/exam/benches/{key}/schedule", benchKey)
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ---------- 检验台 ----------

    @Test
    void benchCreateAndDuplicateAndInvalidCapacity() throws Exception {
        String benchKey = uniqueKey("BENCH");
        MvcResult created = createBench("lab", benchKey, 2);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode bench = objectMapper.readTree(created.getResponse().getContentAsString());
        assertThat(bench.get("benchKey").asText()).isEqualTo(benchKey);
        assertThat(bench.get("capacity").asInt()).isEqualTo(2);

        // 重复 benchKey → 409
        assertThat(createBench("lab", benchKey, 2).getResponse().getStatus()).isEqualTo(409);
        // 容量非法 → 400
        assertThat(createBench("lab", uniqueKey("BENCH"), 0).getResponse().getStatus())
                .isEqualTo(400);
    }

    // ---------- 预约主流程与失败分支 ----------

    @Test
    void reserveHappyPathOccupiesBenchAndEvidence() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        String reservationKey = uniqueKey("RES");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 2);

        MvcResult result = reserve("lab", reservationKey, evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode reservation = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(reservation.get("reservationKey").asText()).isEqualTo(reservationKey);
        assertThat(reservation.get("evidenceKey").asText()).isEqualTo(evidenceKey);
        assertThat(reservation.get("benchKey").asText()).isEqualTo(benchKey);
        assertThat(reservation.get("examinerId").asText()).isEqualTo("lab");
        assertThat(reservation.get("status").asText()).isEqualTo("RESERVED");
        assertThat(reservation.get("startAt").asText()).isEqualTo("2026-09-27T09:00:00");
        assertThat(reservation.get("originalSealNo").isNull()).isTrue();
        assertThat(reservation.get("originalSealVersion").isNull()).isTrue();
        assertThat(reservation.get("startedAt").isNull()).isTrue();
        assertThat(reservation.get("resultSummary").isNull()).isTrue();
        assertThat(reservation.get("completedAt").isNull()).isTrue();
        assertThat(reservation.get("cancelledAt").isNull()).isTrue();

        // 预约不改变证物在库状态，但形成占用
        JsonNode view = chain(evidenceKey);
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(view.get("exams")).hasSize(1);
        assertThat(view.get("examEvents")).hasSize(1);
        assertThat(view.get("examEvents").get(0).get("eventType").asText()).isEqualTo("RESERVED");

        // 诊断：台位容量 2，窗口内占用 1，剩余 1
        JsonNode schedule = benchSchedule(benchKey, utc(BASE), utc(BASE.plusSeconds(10800)));
        assertThat(schedule.get("capacity").asInt()).isEqualTo(2);
        assertThat(schedule.get("activeInWindow").asInt()).isEqualTo(1);
        assertThat(schedule.get("remaining").asInt()).isEqualTo(1);
        assertThat(schedule.get("reservations")).hasSize(1);
    }

    @Test
    void reserveOnMissingEvidenceOrBenchReturns404() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        createBench("lab", benchKey, 1);
        assertThat(reserve("lab", uniqueKey("RES"), uniqueKey("MISSING"), benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(404);

        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(reserve("lab", uniqueKey("RES"), evidenceKey, uniqueKey("NOBENCH"),
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void reserveOnBorrowedOrPendingOrSealBrokenEvidence() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        createBench("lab", benchKey, 3);

        // 借出中 → 409
        String evBorrowed = uniqueKey("EV");
        intake("alice", evBorrowed);
        borrow("alice", evBorrowed, "bob", utc(BASE.plusSeconds(3600)));
        assertThat(reserve("lab", uniqueKey("RES"), evBorrowed, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(409);

        // 待接收交接 → 409
        String evPending = uniqueKey("EV");
        intake("alice", evPending);
        initiate("alice", evPending, "carol");
        assertThat(reserve("lab", uniqueKey("RES"), evPending, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(409);

        // 封条异常 → 422
        String evBroken = uniqueKey("EV");
        intake("alice", evBroken);
        inspect("alice", evBroken, false);
        assertThat(reserve("lab", uniqueKey("RES"), evBroken, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void reserveWithInvalidSlotReturns400() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 1);

        // 结束不晚于开始 → 400
        assertThat(reserve("lab", uniqueKey("RES"), evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(3600)))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(reserve("lab", uniqueKey("RES"), evidenceKey, benchKey,
                utc(BASE.plusSeconds(7200)), utc(BASE.plusSeconds(3600)))
                .getResponse().getStatus()).isEqualTo(400);
        // 开始时刻早于当前 → 400
        assertThat(reserve("lab", uniqueKey("RES"), evidenceKey, benchKey,
                utc(BASE.minusSeconds(60)), utc(BASE.plusSeconds(3600)))
                .getResponse().getStatus()).isEqualTo(400);
        // 失败不留下占用
        assertThat(reservationsOf(evidenceKey)).isEmpty();
    }

    @Test
    void secondActiveReservationOnSameEvidenceReturns409() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 10);

        assertThat(reserve("lab", uniqueKey("RES"), evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(201);
        // 即使时段不重叠，同一证物也只允许一条进行中预约
        assertThat(reserve("lab", uniqueKey("RES"), evidenceKey, benchKey,
                utc(BASE.plusSeconds(10800)), utc(BASE.plusSeconds(14400)))
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(reservationsOf(evidenceKey)).hasSize(1);
    }

    @Test
    void benchCapacityFullReturns409WithCountsAndHalfOpenSlotsDoNotOverlap() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        createBench("lab", benchKey, 1);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        intake("alice", ev3);

        // 容量 1：第一条占用 [09:00, 10:00)
        assertThat(reserve("lab", uniqueKey("RES"), ev1, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(201);
        // 重叠时段 [09:30, 10:30) → 容量满 409
        MvcResult full = reserve("lab", uniqueKey("RES"), ev2, benchKey,
                utc(BASE.plusSeconds(5400)), utc(BASE.plusSeconds(9000)));
        assertThat(full.getResponse().getStatus()).isEqualTo(409);
        assertThat(full.getResponse().getContentAsString()).contains("容量上限=1");

        // 左闭右开：[10:00, 11:00) 与 [09:00, 10:00) 不重叠，可预约
        assertThat(reserve("lab", uniqueKey("RES"), ev2, benchKey,
                utc(BASE.plusSeconds(7200)), utc(BASE.plusSeconds(10800)))
                .getResponse().getStatus()).isEqualTo(201);
        // [08:00, 09:00) 与 [09:00, 10:00) 不重叠，可预约
        assertThat(reserve("lab", uniqueKey("RES"), ev3, benchKey,
                utc(BASE.plusSeconds(1800)), utc(BASE.plusSeconds(3600)))
                .getResponse().getStatus()).isEqualTo(201);
    }

    // ---------- 开始检验 ----------

    @Test
    void startLocksOriginalSealAndMovesEvidenceToExamination() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        String reservationKey = uniqueKey("RES");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 1);
        reserve("lab", reservationKey, evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)));

        // 时段内开始（左闭：恰在开始时刻允许）
        fixClock(BASE.plusSeconds(3600));
        MvcResult result = startExam("lab", reservationKey, uniqueKey("CMD"));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode reservation = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(reservation.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(reservation.get("originalSealNo").asText()).isEqualTo("SEAL-1");
        assertThat(reservation.get("originalSealVersion").asInt()).isEqualTo(1);
        assertThat(reservation.get("startedAt").asText()).isEqualTo("2026-09-27T09:00:00");

        JsonNode view = chain(evidenceKey);
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("EXAMINATION");
        assertThat(view.get("examEvents")).hasSize(2);
        assertThat(view.get("examEvents").get(1).get("eventType").asText()).isEqualTo("STARTED");
        assertThat(view.get("examEvents").get(1).get("sealVersion").asInt()).isEqualTo(1);
    }

    @Test
    void startOutsideSlotReturns400() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        String reservationKey = uniqueKey("RES");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 1);
        reserve("lab", reservationKey, evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)));

        // 未到开始时刻 → 400
        assertThat(startExam("lab", reservationKey, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(400);
        // 时段已结束（右开：恰在结束时刻不允许）→ 400
        fixClock(BASE.plusSeconds(7200));
        assertThat(startExam("lab", reservationKey, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void startByNonExaminerOrTwiceOrOnMissingReturnsError() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        String reservationKey = uniqueKey("RES");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 1);
        reserve("lab", reservationKey, evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)));
        fixClock(BASE.plusSeconds(3600));

        // 非预约人 → 409
        assertThat(startExam("mallory", reservationKey, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(409);
        // 不存在 → 404
        assertThat(startExam("lab", uniqueKey("MISSING"), uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(404);

        assertThat(startExam("lab", reservationKey, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(200);
        // 重复开始 → 409
        assertThat(startExam("lab", reservationKey, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(409);
    }

    // ---------- 完成检验 ----------

    @Test
    void completeRecordsSummaryAndNewSealAndReturnsToSealed() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        String reservationKey = uniqueKey("RES");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 1);
        reserve("lab", reservationKey, evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)));
        fixClock(BASE.plusSeconds(3600));
        startExam("lab", reservationKey, uniqueKey("CMD"));

        fixClock(BASE.plusSeconds(5400));
        MvcResult result = complete("lab", reservationKey, uniqueKey("CMD"),
                "检出微量纤维", "SEAL-2", true, "新封签核验通过");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode reservation = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(reservation.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(reservation.get("resultSummary").asText()).isEqualTo("检出微量纤维");
        assertThat(reservation.get("newSealNo").asText()).isEqualTo("SEAL-2");
        assertThat(reservation.get("newSealVersion").asInt()).isEqualTo(2);
        assertThat(reservation.get("newSealNote").asText()).isEqualTo("新封签核验通过");
        assertThat(reservation.get("completedAt").asText()).isEqualTo("2026-09-27T09:30:00");
        // 历史字段：原封签锁定值不可改写
        assertThat(reservation.get("originalSealNo").asText()).isEqualTo("SEAL-1");
        assertThat(reservation.get("originalSealVersion").asInt()).isEqualTo(1);

        JsonNode view = chain(evidenceKey);
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(view.get("evidence").get("sealNo").asText()).isEqualTo("SEAL-2");
        assertThat(view.get("evidence").get("sealVersion").asInt()).isEqualTo(2);
        assertThat(view.get("examEvents")).hasSize(3);
        assertThat(view.get("examEvents").get(2).get("eventType").asText()).isEqualTo("COMPLETED");
        assertThat(view.get("examEvents").get(2).get("sealVersion").asInt()).isEqualTo(2);
        assertThat(view.get("examEvents").get(2).get("detail").asText()).isEqualTo("检出微量纤维");

        // 完成后占用释放：可再次预约
        assertThat(reserve("lab", uniqueKey("RES"), evidenceKey, benchKey,
                utc(BASE.plusSeconds(7200)), utc(BASE.plusSeconds(10800)))
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void completeRequiresSummaryAndPassingNewSealCheck() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        String reservationKey = uniqueKey("RES");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 1);
        reserve("lab", reservationKey, evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)));
        fixClock(BASE.plusSeconds(3600));

        // 未开始不能完成 → 409
        assertThat(complete("lab", reservationKey, uniqueKey("CMD"),
                "结果", "SEAL-2", true, null).getResponse().getStatus()).isEqualTo(409);

        startExam("lab", reservationKey, uniqueKey("CMD"));

        // 结果摘要为空 → 400
        assertThat(complete("lab", reservationKey, uniqueKey("CMD"),
                "  ", "SEAL-2", true, null).getResponse().getStatus()).isEqualTo(400);
        // 新封签编号为空 → 400
        assertThat(complete("lab", reservationKey, uniqueKey("CMD"),
                "结果", " ", true, null).getResponse().getStatus()).isEqualTo(400);
        // 新封签校验未通过 → 422
        assertThat(complete("lab", reservationKey, uniqueKey("CMD"),
                "结果", "SEAL-2", false, "封签贴合不良").getResponse().getStatus()).isEqualTo(422);
        // 非预约人 → 409
        assertThat(complete("mallory", reservationKey, uniqueKey("CMD"),
                "结果", "SEAL-2", true, null).getResponse().getStatus()).isEqualTo(409);

        // 失败均不改变状态
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("EXAMINATION");
        // 合法完成
        assertThat(complete("lab", reservationKey, uniqueKey("CMD"),
                "结果", "SEAL-2", true, null).getResponse().getStatus()).isEqualTo(200);
        // 重复完成 → 409
        assertThat(complete("lab", reservationKey, uniqueKey("CMD"),
                "结果", "SEAL-3", true, null).getResponse().getStatus()).isEqualTo(409);
    }

    // ---------- 取消 ----------

    @Test
    void cancelReleasesBenchAndEvidenceOccupancy() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        String reservationKey = uniqueKey("RES");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 1);
        reserve("lab", reservationKey, evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)));

        MvcResult result = cancel("lab", reservationKey, uniqueKey("CMD"), "检验计划调整");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode reservation = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(reservation.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(reservation.get("cancelReason").asText()).isEqualTo("检验计划调整");
        assertThat(reservation.get("cancelledAt").isNull()).isFalse();

        // 取消后释放：台位无占用，证物可再次预约、可借出
        JsonNode schedule = benchSchedule(benchKey, utc(BASE), utc(BASE.plusSeconds(10800)));
        assertThat(schedule.get("activeInWindow").asInt()).isEqualTo(0);
        assertThat(schedule.get("remaining").asInt()).isEqualTo(1);
        assertThat(reserve("lab", uniqueKey("RES"), evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(201);

        JsonNode view = chain(evidenceKey);
        assertThat(view.get("examEvents")).hasSize(3);
        assertThat(view.get("examEvents").get(1).get("eventType").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelOnlyAllowedBeforeStartAndByExaminer() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        String reservationKey = uniqueKey("RES");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 1);
        reserve("lab", reservationKey, evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)));

        // 非预约人 → 409
        assertThat(cancel("mallory", reservationKey, uniqueKey("CMD"), "x")
                .getResponse().getStatus()).isEqualTo(409);
        // 不存在 → 404
        assertThat(cancel("lab", uniqueKey("MISSING"), uniqueKey("CMD"), "x")
                .getResponse().getStatus()).isEqualTo(404);

        fixClock(BASE.plusSeconds(3600));
        startExam("lab", reservationKey, uniqueKey("CMD"));
        // 已开始 → 409
        assertThat(cancel("lab", reservationKey, uniqueKey("CMD"), "x")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("EXAMINATION");
    }

    // ---------- 预约占用对其他操作的阻塞 ----------

    @Test
    void activeReservationBlocksBorrowTransferAndInspection() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        String reservationKey = uniqueKey("RES");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 1);
        reserve("lab", reservationKey, evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)));

        assertThat(borrow("alice", evidenceKey, "bob", utc(BASE.plusSeconds(3600)))
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(initiate("alice", evidenceKey, "carol").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(inspect("alice", evidenceKey, true).getResponse().getStatus()).isEqualTo(409);

        // 检验中同样阻塞
        fixClock(BASE.plusSeconds(3600));
        startExam("lab", reservationKey, uniqueKey("CMD"));
        assertThat(borrow("alice", evidenceKey, "bob", utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(initiate("alice", evidenceKey, "carol").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(inspect("alice", evidenceKey, true).getResponse().getStatus())
                .isEqualTo(409);

        // 完成后解除阻塞
        complete("lab", reservationKey, uniqueKey("CMD"), "结果", "SEAL-2", true, null);
        assertThat(inspect("alice", evidenceKey, true).getResponse().getStatus()).isEqualTo(200);
    }

    // ---------- 批量预约 ----------

    @Test
    void batchReserveAllOrNothing() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        createBench("lab", benchKey, 5);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);

        // 全部合法 → 201，两条预约
        MvcResult ok = reserveBatch("lab", uniqueKey("CMD"), List.of(
                batchItem(uniqueKey("RES"), ev1, benchKey,
                        utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200))),
                batchItem(uniqueKey("RES"), ev2, benchKey,
                        utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)))));
        assertThat(ok.getResponse().getStatus()).isEqualTo(201);
        JsonNode list = objectMapper.readTree(ok.getResponse().getContentAsString());
        assertThat(list).hasSize(2);
        assertThat(reservationsOf(ev1)).hasSize(1);
        assertThat(reservationsOf(ev2)).hasSize(1);
    }

    @Test
    void batchReserveAnyFailureLeavesNoOccupancy() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        createBench("lab", benchKey, 5);
        String evOk = uniqueKey("EV");
        String evBroken = uniqueKey("EV");
        intake("alice", evOk);
        intake("alice", evBroken);
        inspect("alice", evBroken, false);

        // 第二项证物封条异常 → 整批失败，第一项也不落库
        MvcResult result = reserveBatch("lab", uniqueKey("CMD"), List.of(
                batchItem(uniqueKey("RES"), evOk, benchKey,
                        utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200))),
                batchItem(uniqueKey("RES"), evBroken, benchKey,
                        utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)))));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(reservationsOf(evOk)).isEmpty();
        assertThat(reservationsOf(evBroken)).isEmpty();
        JsonNode schedule = benchSchedule(benchKey, utc(BASE), utc(BASE.plusSeconds(10800)));
        assertThat(schedule.get("activeInWindow").asInt()).isEqualTo(0);
    }

    @Test
    void batchReserveValidatesDuplicatesAndInternalCapacity() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        createBench("lab", benchKey, 1);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);

        // 批内证物重复 → 400
        assertThat(reserveBatch("lab", uniqueKey("CMD"), List.of(
                batchItem(uniqueKey("RES"), ev1, benchKey,
                        utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200))),
                batchItem(uniqueKey("RES"), ev1, benchKey,
                        utc(BASE.plusSeconds(7200)), utc(BASE.plusSeconds(10800)))))
                .getResponse().getStatus()).isEqualTo(400);

        // 容量 1，批内两条同台位同时段 → 409，且不留占用
        assertThat(reserveBatch("lab", uniqueKey("CMD"), List.of(
                batchItem(uniqueKey("RES"), ev1, benchKey,
                        utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200))),
                batchItem(uniqueKey("RES"), ev2, benchKey,
                        utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)))))
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(reservationsOf(ev1)).isEmpty();
        assertThat(reservationsOf(ev2)).isEmpty();

        // 批内两条同台位不重叠时段 → 201
        assertThat(reserveBatch("lab", uniqueKey("CMD"), List.of(
                batchItem(uniqueKey("RES"), ev1, benchKey,
                        utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200))),
                batchItem(uniqueKey("RES"), ev2, benchKey,
                        utc(BASE.plusSeconds(7200)), utc(BASE.plusSeconds(10800)))))
                .getResponse().getStatus()).isEqualTo(201);
    }

    // ---------- 幂等 ----------

    @Test
    void reserveIdempotentReplayAndChangedParamsAndFailureNotOccupyingKey() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        String reservationKey = uniqueKey("RES");
        String commandKey = uniqueKey("CMD");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 2);

        MvcResult first = reserve("lab", commandKey, reservationKey, evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)));
        MvcResult replay = reserve("lab", commandKey, reservationKey, evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(reservationsOf(evidenceKey)).hasSize(1);

        // 同键改参 → 409
        assertThat(reserve("lab", commandKey, uniqueKey("RES"), evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(409);

        // 失败不占键：先以非法时段失败，再用同键合法参数成功
        String failKey = uniqueKey("CMD");
        assertThat(reserve("lab", failKey, uniqueKey("RES"), evidenceKey, benchKey,
                utc(BASE.plusSeconds(7200)), utc(BASE.plusSeconds(3600)))
                .getResponse().getStatus()).isEqualTo(400);
        String ev2 = uniqueKey("EV");
        intake("alice", ev2);
        assertThat(reserve("lab", failKey, uniqueKey("RES"), ev2, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)))
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void completeIdempotentReplayReturnsFirstSnapshot() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        String reservationKey = uniqueKey("RES");
        String commandKey = uniqueKey("CMD");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 1);
        reserve("lab", reservationKey, evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)));
        fixClock(BASE.plusSeconds(3600));
        startExam("lab", reservationKey, uniqueKey("CMD"));

        MvcResult first = complete("lab", reservationKey, commandKey,
                "结果摘要", "SEAL-2", true, null);
        // 时钟前进后重放：仍返回首次快照（completedAt 不变）
        fixClock(BASE.plusSeconds(7200));
        MvcResult replay = complete("lab", reservationKey, commandKey,
                "结果摘要", "SEAL-2", true, null);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        JsonNode view = chain(evidenceKey);
        assertThat(view.get("exams")).hasSize(1);
        assertThat(view.get("examEvents")).hasSize(3);
    }

    // ---------- 历史稳定性与诊断排序 ----------

    @Test
    void historyRemainsStableAcrossSubsequentOperations() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        String benchKey = uniqueKey("BENCH");
        String res1 = uniqueKey("RES");
        intake("alice", evidenceKey);
        createBench("lab", benchKey, 2);
        reserve("lab", res1, evidenceKey, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)));
        fixClock(BASE.plusSeconds(3600));
        startExam("lab", res1, uniqueKey("CMD"));
        complete("lab", res1, uniqueKey("CMD"), "第一轮结果", "SEAL-2", true, null);

        // 第二轮预约（新时段）
        String res2 = uniqueKey("RES");
        reserve("lab", res2, evidenceKey, benchKey,
                utc(BASE.plusSeconds(10800)), utc(BASE.plusSeconds(14400)));
        cancel("lab", res2, uniqueKey("CMD"), "计划取消");

        // 历史按发生顺序稳定返回，第一轮记录不被第二轮改写
        JsonNode history = reservationsOf(evidenceKey);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("reservationKey").asText()).isEqualTo(res1);
        assertThat(history.get(0).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(history.get(0).get("resultSummary").asText()).isEqualTo("第一轮结果");
        assertThat(history.get(0).get("originalSealVersion").asInt()).isEqualTo(1);
        assertThat(history.get(1).get("reservationKey").asText()).isEqualTo(res2);
        assertThat(history.get(1).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(history.get(1).get("resultSummary").isNull()).isTrue();
        assertThat(history.get(1).get("originalSealNo").isNull()).isTrue();

        // 明细含完整事件链
        JsonNode detail = reservationDetail(res1);
        assertThat(detail.get("events")).hasSize(3);
        assertThat(detail.get("events").get(0).get("eventType").asText()).isEqualTo("RESERVED");
        assertThat(detail.get("events").get(1).get("eventType").asText()).isEqualTo("STARTED");
        assertThat(detail.get("events").get(2).get("eventType").asText()).isEqualTo("COMPLETED");

        // 不存在预约明细 → 404
        MvcResult missing = mockMvc.perform(
                        get("/api/exam/reservations/{key}", uniqueKey("MISSING")))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void benchScheduleFiltersWindowAndSortsStably() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        createBench("lab", benchKey, 10);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        // [09:00,10:00) 与 [11:00,12:00)
        reserve("lab", uniqueKey("RES"), ev1, benchKey,
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)));
        reserve("lab", uniqueKey("RES"), ev2, benchKey,
                utc(BASE.plusSeconds(10800)), utc(BASE.plusSeconds(14400)));

        // 窗口 [09:30, 10:30) 只命中第一条
        JsonNode window = benchSchedule(benchKey,
                utc(BASE.plusSeconds(5400)), utc(BASE.plusSeconds(9000)));
        assertThat(window.get("activeInWindow").asInt()).isEqualTo(1);
        assertThat(window.get("remaining").asInt()).isEqualTo(9);
        assertThat(window.get("reservations").get(0).get("evidenceKey").asText()).isEqualTo(ev1);

        // 全窗口命中两条，按开始时刻排序
        JsonNode all = benchSchedule(benchKey, utc(BASE), utc(BASE.plusSeconds(20000)));
        assertThat(all.get("activeInWindow").asInt()).isEqualTo(2);
        assertThat(all.get("reservations").get(0).get("evidenceKey").asText()).isEqualTo(ev1);
        assertThat(all.get("reservations").get(1).get("evidenceKey").asText()).isEqualTo(ev2);

        // 非法窗口 → 400；不存在台位 → 404
        MvcResult badWindow = mockMvc.perform(get("/api/exam/benches/{key}/schedule", benchKey)
                        .param("from", utc(BASE.plusSeconds(7200)).toString())
                        .param("to", utc(BASE).toString()))
                .andReturn();
        assertThat(badWindow.getResponse().getStatus()).isEqualTo(400);
        MvcResult missing = mockMvc.perform(
                        get("/api/exam/benches/{key}/schedule", uniqueKey("NOBENCH")))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
    }
}
