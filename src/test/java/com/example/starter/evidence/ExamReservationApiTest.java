package com.example.starter.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 证物检验预约 API：主流程、失败分支、409/422 区分、批量原子性、幂等边界与只读查询测试。
 * 全部时间为 UTC 左闭右开；时钟固定以驱动开始/完成时刻与逾期无关的边界，无需真实等待。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExamReservationApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final Instant BASE = Instant.parse("2026-09-22T08:00:00Z");
    private static final String LAB = "lab-alice";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceClock evidenceClock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void resetClockAndClearExamScenarioData() {
        evidenceClock.setClock(Clock.systemUTC());
        // 清理本类检验场景数据，保证每个用例从空台位占用开始（合成检验台种子保留）。
        jdbcTemplate.execute("DELETE FROM exam_chain_event");
        jdbcTemplate.execute("DELETE FROM exam_reservation");
    }

    private void fixClock(Instant instant) {
        evidenceClock.setClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private LocalDateTime utc(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 22, hour, minute);
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

    private Map<String, Object> reserveItem(String reservationKey, String evidenceKey, String benchKey,
                                            LocalDateTime startAt, LocalDateTime endAt) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("reservationKey", reservationKey);
        item.put("evidenceKey", evidenceKey);
        item.put("benchKey", benchKey);
        item.put("startAt", startAt.toString());
        item.put("endAt", endAt.toString());
        return item;
    }

    @SafeVarargs
    private final MvcResult reserve(String actor, String commandKey, Map<String, Object>... items)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("items", List.of(items));
        return mockMvc.perform(post("/api/exam-reservations")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult reserve(String actor, String commandKey, List<Map<String, Object>> items)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("items", items);
        return mockMvc.perform(post("/api/exam-reservations")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult examAction(String actor, String reservationKey, String action,
                                 Map<String, Object> body) throws Exception {
        body.put("commandKey", body.getOrDefault("commandKey", uniqueKey("CMD")));
        return mockMvc.perform(post("/api/exam-reservations/{key}/{action}", reservationKey, action)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult start(String actor, String reservationKey) throws Exception {
        return examAction(actor, reservationKey, "start", new LinkedHashMap<>());
    }

    private MvcResult start(String actor, String reservationKey, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return examAction(actor, reservationKey, "start", body);
    }

    private MvcResult complete(String actor, String reservationKey, String newSealNo) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resultSummary", "检验完成，物证完好");
        body.put("newSealNo", newSealNo);
        body.put("newSealCheck", "CHECK-" + UUID.randomUUID().toString().substring(0, 8));
        return examAction(actor, reservationKey, "complete", body);
    }

    private MvcResult complete(String actor, String reservationKey, String commandKey,
                               String resultSummary, String newSealNo, String newSealCheck)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("resultSummary", resultSummary);
        body.put("newSealNo", newSealNo);
        body.put("newSealCheck", newSealCheck);
        return examAction(actor, reservationKey, "complete", body);
    }

    private MvcResult cancel(String actor, String reservationKey) throws Exception {
        return examAction(actor, reservationKey, "cancel", new LinkedHashMap<>());
    }

    private MvcResult cancel(String actor, String reservationKey, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return examAction(actor, reservationKey, "cancel", body);
    }

    private MvcResult borrow(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", "borrower-bob");
        body.put("purpose", "鉴定用");
        body.put("dueAt", utc(12, 0).toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
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

    private MvcResult inspectSeal(String actor, String evidenceKey, boolean passed) throws Exception {
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

    private JsonNode readBody(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return readBody(result);
    }

    private MvcResult occupancy(String benchKey, LocalDateTime startAt, LocalDateTime endAt)
            throws Exception {
        return mockMvc.perform(get("/api/exam-benches/{key}/occupancy", benchKey)
                        .param("startAt", startAt.toString())
                        .param("endAt", endAt.toString()))
                .andReturn();
    }

    @Test
    void reserveSingleCreatesReservationAndAppendsChainEventWithoutChangingEvidenceStatus()
            throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String reservationKey = uniqueKey("RSV");

        MvcResult result = reserve(LAB, uniqueKey("CMD"),
                reserveItem(reservationKey, evidenceKey, "BENCH-A", utc(10, 0), utc(11, 0)));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = readBody(result);
        assertThat(body.get("reservedCount").asInt()).isEqualTo(1);
        JsonNode rsv = body.get("reservations").get(0);
        assertThat(rsv.get("reservationKey").asText()).isEqualTo(reservationKey);
        assertThat(rsv.get("status").asText()).isEqualTo("RESERVED");
        assertThat(rsv.get("startedAt").isNull()).isTrue();
        assertThat(rsv.get("lockedSealVersion").isNull()).isTrue();

        // 预约仅占位：证物仍在库 SEALED；保管链追加不可变 RESERVED 事件，记录封签版本 1。
        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chain.get("examEvents")).hasSize(1);
        JsonNode event = chain.get("examEvents").get(0);
        assertThat(event.get("eventType").asText()).isEqualTo("RESERVED");
        assertThat(event.get("sealNo").asText()).isEqualTo("SEAL-1");
        assertThat(event.get("sealVersion").asInt()).isEqualTo(1);
    }

    @Test
    void startAndCompleteFlowLocksSealVersionRewritesNewSealAndReturnsToSealed() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String reservationKey = uniqueKey("RSV");
        reserve(LAB, uniqueKey("CMD"),
                reserveItem(reservationKey, evidenceKey, "BENCH-A", utc(10, 0), utc(11, 0)));

        // 开始：同一事务内证物转 EXAMINATION、写 STARTED 临时链记录并锁定原封签版本。
        fixClock(Instant.parse("2026-09-22T10:00:00Z"));
        MvcResult started = start(LAB, reservationKey);
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        JsonNode inProgress = readBody(started);
        assertThat(inProgress.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(inProgress.get("lockedSealNo").asText()).isEqualTo("SEAL-1");
        assertThat(inProgress.get("lockedSealVersion").asInt()).isEqualTo(1);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("EXAMINATION");

        // 完成：必须记录结果摘要与新封签校验，封签版本递增后回到 SEALED。
        fixClock(Instant.parse("2026-09-22T10:45:00Z"));
        MvcResult completed = complete(LAB, reservationKey, "SEAL-2");
        assertThat(completed.getResponse().getStatus()).isEqualTo(200);
        JsonNode done = readBody(completed);
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("resultSummary").asText()).isEqualTo("检验完成，物证完好");
        assertThat(done.get("newSealNo").asText()).isEqualTo("SEAL-2");
        assertThat(done.get("lockedSealVersion").asInt()).isEqualTo(1);

        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chain.get("evidence").get("sealNo").asText()).isEqualTo("SEAL-2");
        assertThat(chain.get("evidence").get("sealVersion").asInt()).isEqualTo(2);
        assertThat(chain.get("examEvents")).hasSize(3);
        assertThat(chain.get("examEvents").get(0).get("eventType").asText()).isEqualTo("RESERVED");
        JsonNode startedEvent = chain.get("examEvents").get(1);
        assertThat(startedEvent.get("eventType").asText()).isEqualTo("STARTED");
        assertThat(startedEvent.get("sealVersion").asInt()).isEqualTo(1);
        JsonNode completedEvent = chain.get("examEvents").get(2);
        assertThat(completedEvent.get("eventType").asText()).isEqualTo("COMPLETED");
        assertThat(completedEvent.get("sealVersion").asInt()).isEqualTo(2);
        assertThat(completedEvent.get("newSealNo").asText()).isEqualTo("SEAL-2");
        assertThat(completedEvent.get("newSealCheck").asText()).startsWith("CHECK-");
    }

    @Test
    void completeRequiresResultSummaryAndDifferentNewSeal() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String reservationKey = uniqueKey("RSV");
        reserve(LAB, uniqueKey("CMD"),
                reserveItem(reservationKey, evidenceKey, "BENCH-A", utc(10, 0), utc(11, 0)));
        start(LAB, reservationKey);

        // 缺结果摘要：Bean Validation 直接 400。
        MvcResult missingSummary = complete(LAB, reservationKey, uniqueKey("CMD"),
                "", "SEAL-2", "CHECK-X");
        assertThat(missingSummary.getResponse().getStatus()).isEqualTo(400);

        // 新封签与原封签相同：业务校验 400。
        MvcResult sameSeal = complete(LAB, reservationKey, uniqueKey("CMD"),
                "结果", "SEAL-1", "CHECK-X");
        assertThat(sameSeal.getResponse().getStatus()).isEqualTo(400);

        // 被拒后证物仍在检验中，可正常完成。
        assertThat(complete(LAB, reservationKey, "SEAL-2").getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void startCompleteCancelOnlyAllowedByReservationOperatorAndCorrectState() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String reservationKey = uniqueKey("RSV");
        reserve(LAB, uniqueKey("CMD"),
                reserveItem(reservationKey, evidenceKey, "BENCH-A", utc(10, 0), utc(11, 0)));

        // 非预约操作人不能开始。
        assertThat(start("lab-carol", reservationKey).getResponse().getStatus()).isEqualTo(409);
        assertThat(start(LAB, reservationKey).getResponse().getStatus()).isEqualTo(200);

        // 重复开始冲突；已开始不可取消；未开始过才能完成。
        assertThat(start(LAB, reservationKey).getResponse().getStatus()).isEqualTo(409);
        assertThat(cancel(LAB, reservationKey).getResponse().getStatus()).isEqualTo(409);
        assertThat(complete("lab-carol", reservationKey, "SEAL-2").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(complete(LAB, reservationKey, "SEAL-2").getResponse().getStatus()).isEqualTo(200);

        // 已终结不可再次完成或取消。
        assertThat(complete(LAB, reservationKey, "SEAL-3").getResponse().getStatus()).isEqualTo(409);
        assertThat(cancel(LAB, reservationKey).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void cancelUnstartedReservationReleasesBenchAndEvidence() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String reservationKey = uniqueKey("RSV");
        reserve(LAB, uniqueKey("CMD"),
                reserveItem(reservationKey, evidenceKey, "BENCH-A", utc(10, 0), utc(11, 0)));

        assertThat(cancel(LAB, reservationKey).getResponse().getStatus()).isEqualTo(200);
        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("examEvents")).hasSize(2);
        assertThat(chain.get("examEvents").get(1).get("eventType").asText()).isEqualTo("CANCELLED");

        // 释放后：台位窗口诊断不再计数，证物可被借出。
        JsonNode occ = readBody(occupancy("BENCH-A", utc(10, 0), utc(11, 0)));
        assertThat(occ.get("actualOccupancy").asInt()).isZero();
        assertThat(occ.get("remaining").asInt()).isEqualTo(2);
        assertThat(borrow("alice", evidenceKey).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void reservedEvidenceBlocksLoanTransferAndIndependentSealInspection() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String reservationKey = uniqueKey("RSV");
        reserve(LAB, uniqueKey("CMD"),
                reserveItem(reservationKey, evidenceKey, "BENCH-A", utc(10, 0), utc(11, 0)));

        assertThat(borrow("alice", evidenceKey).getResponse().getStatus()).isEqualTo(409);
        assertThat(initiate("alice", evidenceKey, "bob").getResponse().getStatus()).isEqualTo(409);
        assertThat(inspectSeal("alice", evidenceKey, true).getResponse().getStatus()).isEqualTo(409);

        // 取消释放后交接恢复可行。
        cancel(LAB, reservationKey);
        assertThat(initiate("alice", evidenceKey, "bob").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void reserveBorrowedOrSealBrokenEvidenceReturns422() throws Exception {
        fixClock(BASE);
        String borrowed = uniqueKey("EV");
        intake("alice", borrowed);
        assertThat(borrow("alice", borrowed).getResponse().getStatus()).isEqualTo(200);
        MvcResult borrowedResult = reserve(LAB, uniqueKey("CMD"),
                reserveItem(uniqueKey("RSV"), borrowed, "BENCH-A", utc(10, 0), utc(11, 0)));
        assertThat(borrowedResult.getResponse().getStatus()).isEqualTo(422);
        assertThat(readBody(borrowedResult).get("details").get(0).get("reason").asText())
                .isEqualTo("EVIDENCE_BORROWED");

        String broken = uniqueKey("EV");
        intake("alice", broken);
        inspectSeal("alice", broken, false);
        MvcResult brokenResult = reserve(LAB, uniqueKey("CMD"),
                reserveItem(uniqueKey("RSV"), broken, "BENCH-A", utc(10, 0), utc(11, 0)));
        assertThat(brokenResult.getResponse().getStatus()).isEqualTo(422);
        assertThat(readBody(brokenResult).get("details").get(0).get("reason").asText())
                .isEqualTo("SEAL_ABNORMAL");
    }

    @Test
    void reserveUnknownBenchOrEvidenceReturns404() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        MvcResult missingBench = reserve(LAB, uniqueKey("CMD"),
                reserveItem(uniqueKey("RSV"), evidenceKey, "BENCH-X", utc(10, 0), utc(11, 0)));
        assertThat(missingBench.getResponse().getStatus()).isEqualTo(404);
        assertThat(readBody(missingBench).get("details").get(0).get("reason").asText())
                .isEqualTo("BENCH_NOT_FOUND");

        MvcResult missingEvidence = reserve(LAB, uniqueKey("CMD"),
                reserveItem(uniqueKey("RSV"), uniqueKey("EV"), "BENCH-A", utc(10, 0), utc(11, 0)));
        assertThat(missingEvidence.getResponse().getStatus()).isEqualTo(404);
        assertThat(readBody(missingEvidence).get("details").get(0).get("reason").asText())
                .isEqualTo("EVIDENCE_NOT_FOUND");
    }

    @Test
    void batchRejectsInvalidWindowAndDuplicateEvidenceWith400AndNoOccupancy() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(reserveItem(uniqueKey("RSV"), ev1, "BENCH-A", utc(11, 0), utc(10, 0)));
        items.add(reserveItem(uniqueKey("RSV"), ev2, "BENCH-A", utc(10, 0), utc(11, 0)));
        items.add(reserveItem(uniqueKey("RSV"), ev2, "BENCH-B", utc(13, 0), utc(14, 0)));
        MvcResult result = reserve(LAB, uniqueKey("CMD"), items);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode details = readBody(result).get("details");
        List<String> reasons = new ArrayList<>();
        details.forEach(node -> reasons.add(node.get("reason").asText()));
        assertThat(reasons).containsExactlyInAnyOrder("WINDOW_INVALID", "DUPLICATE_EVIDENCE");
        JsonNode windowFailure = details.get(0);
        // 数量/时长类失败给出实际值、要求值与差额。
        assertThat(windowFailure.get("actual").asInt()).isEqualTo(-60);
        assertThat(windowFailure.get("required").asInt()).isZero();
        assertThat(windowFailure.get("deficit").asInt()).isEqualTo(-60);

        // 整批回滚：台位无任何占位。
        assertThat(readBody(occupancy("BENCH-A", utc(9, 0), utc(15, 0)))
                .get("actualOccupancy").asInt()).isZero();
        assertThat(readBody(occupancy("BENCH-B", utc(9, 0), utc(15, 0)))
                .get("actualOccupancy").asInt()).isZero();
    }

    @Test
    void batchCapacityExceededRollsBackAllItemsAndReportsActualRequiredDeficit() throws Exception {
        // BENCH-A 容量 2：先占 1 条，再批量提交 3 条重叠预约，最终占用 4 > 2，整批回滚。
        String existing = uniqueKey("EV");
        intake("alice", existing);
        String existingRsv = uniqueKey("RSV");
        assertThat(reserve(LAB, uniqueKey("CMD"),
                reserveItem(existingRsv, existing, "BENCH-A", utc(10, 0), utc(12, 0)))
                .getResponse().getStatus()).isEqualTo(201);

        List<String> newKeys = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String ev = uniqueKey("EV");
            intake("alice", ev);
            String rsv = uniqueKey("RSV");
            newKeys.add(rsv);
            items.add(reserveItem(rsv, ev, "BENCH-A", utc(10, 0), utc(11, 0)));
        }
        MvcResult result = reserve(LAB, uniqueKey("CMD"), items);
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        JsonNode details = readBody(result).get("details");
        assertThat(details).hasSize(3);
        for (JsonNode detail : details) {
            assertThat(detail.get("reason").asText()).isEqualTo("BENCH_CAPACITY_EXCEEDED");
            assertThat(detail.get("actual").asInt()).isEqualTo(4);
            assertThat(detail.get("required").asInt()).isEqualTo(2);
            assertThat(detail.get("deficit").asInt()).isEqualTo(2);
        }

        // 不留半成品：3 条新预约均不存在，台位实际占用仍只有先前 1 条。
        for (String key : newKeys) {
            MvcResult lookup = mockMvc.perform(get("/api/exam-reservations/{key}", key)).andReturn();
            assertThat(lookup.getResponse().getStatus()).isEqualTo(404);
        }
        assertThat(readBody(occupancy("BENCH-A", utc(10, 0), utc(11, 0)))
                .get("actualOccupancy").asInt()).isEqualTo(1);
    }

    @Test
    void halfOpenWindowsThatTouchAtEndpointDoNotOverlap() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        // 同证物、同台位：[10:00,11:00) 与 [11:00,12:00) 端点相接，不算重叠，两条都成功。
        assertThat(reserve(LAB, uniqueKey("CMD"),
                reserveItem(uniqueKey("RSV"), evidenceKey, "BENCH-A", utc(10, 0), utc(11, 0)))
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(reserve(LAB, uniqueKey("CMD"),
                reserveItem(uniqueKey("RSV"), evidenceKey, "BENCH-A", utc(11, 0), utc(12, 0)))
                .getResponse().getStatus()).isEqualTo(201);

        // 真正重叠（[10:30,11:30)）的同证物预约返回 409。
        MvcResult overlap = reserve(LAB, uniqueKey("CMD"),
                reserveItem(uniqueKey("RSV"), evidenceKey, "BENCH-B", utc(10, 30), utc(11, 30)));
        assertThat(overlap.getResponse().getStatus()).isEqualTo(409);
        assertThat(readBody(overlap).get("details").get(0).get("reason").asText())
                .isEqualTo("EVIDENCE_OCCUPIED");
    }

    @Test
    void reservationKeyDuplicateRejectedAndBatchIsAtomicAcrossBenches() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String reusedKey = uniqueKey("RSV");
        assertThat(reserve(LAB, uniqueKey("CMD"),
                reserveItem(reusedKey, ev1, "BENCH-A", utc(10, 0), utc(11, 0)))
                .getResponse().getStatus()).isEqualTo(201);

        // 一条复用已存在预约键、另一条台位容量本身可行：整批 400 且第二条不留占用。
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(reserveItem(reusedKey, ev2, "BENCH-A", utc(10, 0), utc(11, 0)));
        String benignKey = uniqueKey("RSV");
        items.add(reserveItem(benignKey, ev2, "BENCH-A", utc(13, 0), utc(14, 0)));
        MvcResult result = reserve(LAB, uniqueKey("CMD"), items);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(readBody(result).get("details").get(0).get("reason").asText())
                .isEqualTo("DUPLICATE_RESERVATION_KEY");
        assertThat(mockMvc.perform(get("/api/exam-reservations/{key}", benignKey)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void reserveReplayReturnsFirstSnapshotAndDifferentParamsReturn409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String commandKey = uniqueKey("CMD");
        String reservationKey = uniqueKey("RSV");

        MvcResult first = reserve(LAB, commandKey,
                reserveItem(reservationKey, evidenceKey, "BENCH-A", utc(10, 0), utc(11, 0)));
        MvcResult replay = reserve(LAB, commandKey,
                reserveItem(reservationKey, evidenceKey, "BENCH-A", utc(10, 0), utc(11, 0)));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放不产生第二条预约或事件。
        assertThat(chain(evidenceKey).get("examEvents")).hasSize(1);

        // 同键改参（换台位）返回 409。
        MvcResult different = reserve(LAB, commandKey,
                reserveItem(reservationKey, evidenceKey, "BENCH-B", utc(10, 0), utc(11, 0)));
        assertThat(different.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failedRequestDoesNotOccupyIdempotencyKey() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String commandKey = uniqueKey("CMD");
        String reservationKey = uniqueKey("RSV");

        // 首次非法窗口失败回滚，不占键。
        MvcResult invalid = reserve(LAB, commandKey,
                reserveItem(reservationKey, evidenceKey, "BENCH-A", utc(11, 0), utc(10, 0)));
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        // 同一 commandKey 改为合法参数后成功。
        MvcResult valid = reserve(LAB, commandKey,
                reserveItem(reservationKey, evidenceKey, "BENCH-A", utc(10, 0), utc(11, 0)));
        assertThat(valid.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void startAndCompleteReplayDoNotMutateStateAgain() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String reservationKey = uniqueKey("RSV");
        reserve(LAB, uniqueKey("CMD"),
                reserveItem(reservationKey, evidenceKey, "BENCH-A", utc(10, 0), utc(11, 0)));

        String startKey = uniqueKey("CMD");
        MvcResult firstStart = start(LAB, reservationKey, startKey);
        MvcResult replayStart = start(LAB, reservationKey, startKey);
        assertThat(firstStart.getResponse().getStatus()).isEqualTo(200);
        assertThat(replayStart.getResponse().getContentAsString())
                .isEqualTo(firstStart.getResponse().getContentAsString());
        // 开始重放不追加第二条 STARTED 事件。
        assertThat(chain(evidenceKey).get("examEvents")).hasSize(2);

        String completeKey = uniqueKey("CMD");
        MvcResult firstComplete = complete(LAB, reservationKey, completeKey,
                "结果摘要", "SEAL-9", "CHECK-9");
        MvcResult replayComplete = complete(LAB, reservationKey, completeKey,
                "结果摘要", "SEAL-9", "CHECK-9");
        assertThat(firstComplete.getResponse().getStatus()).isEqualTo(200);
        assertThat(replayComplete.getResponse().getContentAsString())
                .isEqualTo(firstComplete.getResponse().getContentAsString());
        assertThat(chain(evidenceKey).get("examEvents")).hasSize(3);
        // 异参重放（改结果摘要）返回 409，封签版本不被二次递增。
        assertThat(complete(LAB, reservationKey, completeKey, "另一摘要", "SEAL-9", "CHECK-9")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(evidenceKey).get("evidence").get("sealVersion").asInt()).isEqualTo(2);
    }

    @Test
    void readOnlyQueriesShowDetailHistoryAndDiagnosticsWithoutChangingState() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String rsv1 = uniqueKey("RSV");
        String rsv2 = uniqueKey("RSV");
        reserve(LAB, uniqueKey("CMD"),
                reserveItem(rsv1, ev1, "BENCH-A", utc(10, 0), utc(12, 0)));
        reserve(LAB, uniqueKey("CMD"),
                reserveItem(rsv2, ev2, "BENCH-A", utc(10, 30), utc(11, 30)));

        // 明细查询。
        MvcResult detail = mockMvc.perform(get("/api/exam-reservations/{key}", rsv1)).andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(detail).get("evidenceKey").asText()).isEqualTo(ev1);

        // 诊断：[10:00,12:00) 内实际 2、容量 2、差额 0；取消后占用下降。
        JsonNode before = readBody(occupancy("BENCH-A", utc(10, 0), utc(12, 0)));
        assertThat(before.get("actualOccupancy").asInt()).isEqualTo(2);
        assertThat(before.get("capacity").asInt()).isEqualTo(2);
        assertThat(before.get("remaining").asInt()).isZero();
        assertThat(before.get("overlapping")).hasSize(2);
        cancel(LAB, rsv2);
        JsonNode after = readBody(occupancy("BENCH-A", utc(10, 0), utc(12, 0)));
        assertThat(after.get("actualOccupancy").asInt()).isEqualTo(1);
        assertThat(after.get("remaining").asInt()).isEqualTo(1);

        // 历史：预约按发生顺序，事件为 RESERVED、RESERVED、CANCELLED。
        MvcResult history = mockMvc.perform(get("/api/evidence/{key}/exam-history", ev2)).andReturn();
        assertThat(history.getResponse().getStatus()).isEqualTo(200);
        JsonNode historyBody = readBody(history);
        assertThat(historyBody.get("reservations")).hasSize(1);
        assertThat(historyBody.get("events").get(0).get("eventType").asText()).isEqualTo("RESERVED");
        assertThat(historyBody.get("events").get(1).get("eventType").asText()).isEqualTo("CANCELLED");

        // 诊断窗口非法返回 400；未知台位返回 404。
        assertThat(occupancy("BENCH-A", utc(11, 0), utc(11, 0)).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(occupancy("BENCH-X", utc(10, 0), utc(11, 0)).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void reservationTimestampsKeepMicrosecondPrecision() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String reservationKey = uniqueKey("RSV");
        LocalDateTime start = LocalDateTime.of(2026, 9, 22, 10, 0, 0, 123456000);
        LocalDateTime end = LocalDateTime.of(2026, 9, 22, 11, 0, 0, 123456000);
        MvcResult result = reserve(LAB, uniqueKey("CMD"),
                reserveItem(reservationKey, evidenceKey, "BENCH-A", start, end));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode rsv = readBody(result).get("reservations").get(0);
        assertThat(rsv.get("startAt").asText()).isEqualTo("2026-09-22T10:00:00.123456");
        assertThat(rsv.get("endAt").asText()).isEqualTo("2026-09-22T11:00:00.123456");
    }
}
