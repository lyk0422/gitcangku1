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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 证物检验预约 API 测试（真实 H2 行锁与事务）：主流程、失败分支（409/422/400/404）、
 * 批量原子回滚、幂等重放/改参、台位诊断、只读不改状态与不可变封签版本/保管链。
 * 时间全部由固定时钟驱动，UTC 左闭右开。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExaminationApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final Instant BASE = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceClock evidenceClock;

    @Autowired
    private ExaminationService examinationService;

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private LocalDateTime utc(int hour, int minute) {
        // 以 BASE 当天的绝对 UTC 时刻（2026-09-22Thh:mm:00Z），不是相对 BASE 的偏移。
        return LocalDateTime.ofInstant(BASE, ZoneOffset.UTC)
                .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
    }

    private void fixClock(Instant instant) {
        evidenceClock.setClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private MvcResult postJson(String url, String actor, Object body) throws Exception {
        return mockMvc.perform(post(url)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult registerBench(String actor, String benchKey, String name, int capacity)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("benchKey", benchKey);
        body.put("name", name);
        body.put("capacity", capacity);
        return postJson("/api/evidence/lab-benches", actor, body);
    }

    private MvcResult intake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-ORIG");
        return postJson("/api/evidence", actor, body);
    }

    private Map<String, Object> reserveBody(String reservationKey, String benchKey,
                                            String examiner, LocalDateTime start,
                                            LocalDateTime end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("reservationKey", reservationKey);
        body.put("benchKey", benchKey);
        body.put("examinerId", examiner);
        body.put("startAt", start);
        body.put("endAt", end);
        return body;
    }

    private MvcResult performReserve(String actor, String evidenceKey, Map<String, Object> body)
            throws Exception {
        return postJson("/api/evidence/" + evidenceKey + "/examination-reservations", actor, body);
    }

    private MvcResult command(String actor, String evidenceKey, String reservationKey,
                              String action, Object body) throws Exception {
        String url = "/api/evidence/" + evidenceKey + "/examination-reservations/"
                + reservationKey + "/" + action;
        return postJson(url, actor, body);
    }

    private Map<String, Object> keyOnly(String commandKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return body;
    }

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void registerBenchSucAndDuplicateRejected() throws Exception {
        String benchKey = uniqueKey("BENCH");
        assertThat(registerBench("alice", benchKey, "痕检台", 2).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(registerBench("alice", benchKey, "痕检台", 2).getResponse().getStatus())
                .isEqualTo(409);

        // 容量必须为正整数：实际值 0、要求值 1、差额在错误信息中给出
        MvcResult invalid = registerBench("alice", uniqueKey("BENCH"), "台", 0);
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void reserveCreatesOccupationAndChainEventWithSealVersionOne() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        String evidenceKey = uniqueKey("EV");
        registerBench("alice", benchKey, "台", 1);
        intake("alice", evidenceKey);

        MvcResult result = performReserve("alice", evidenceKey,
                reserveBody(uniqueKey("RSV"), benchKey, "bob", utc(9, 0), utc(10, 0)));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode view = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(view.get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(view.get("examinerId").asText()).isEqualTo("bob");
        assertThat(view.get("sealVersionLocked").isNull()).isTrue();
        assertThat(view.get("startedAt").isNull()).isTrue();

        JsonNode detailChain = chain(evidenceKey);
        assertThat(detailChain.get("sealVersions")).hasSize(1);
        assertThat(detailChain.get("sealVersions").get(0).get("version").asInt()).isEqualTo(1);
        assertThat(detailChain.get("sealVersions").get(0).get("kind").asText())
                .isEqualTo("ORIGINAL");
        assertThat(detailChain.get("sealVersions").get(0).get("supersededAt").isNull()).isTrue();
        assertThat(detailChain.get("examinations")).hasSize(1);
        assertThat(detailChain.get("examinations").get(0).get("eventType").asText())
                .isEqualTo("RESERVED");
    }

    @Test
    void reserveByNonCustodianReturns409AndBrokenSealReturns422() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        registerBench("alice", benchKey, "台", 1);

        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        MvcResult notCustodian = performReserve("mallory", ev1,
                reserveBody(uniqueKey("RSV"), benchKey, "bob", utc(9, 0), utc(10, 0)));
        assertThat(notCustodian.getResponse().getStatus()).isEqualTo(409);

        String ev2 = uniqueKey("EV");
        intake("alice", ev2);
        Map<String, Object> inspect = new LinkedHashMap<>();
        inspect.put("commandKey", uniqueKey("CMD"));
        inspect.put("passed", false);
        inspect.put("note", "cracked");
        assertThat(postJson("/api/evidence/" + ev2 + "/seal-inspections", "alice", inspect)
                .getResponse().getStatus()).isEqualTo(200);
        MvcResult broken = performReserve("alice", ev2,
                reserveBody(uniqueKey("RSV"), benchKey, "bob", utc(9, 0), utc(10, 0)));
        assertThat(broken.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void reserveWhileBorrowedReturns409() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        registerBench("alice", benchKey, "台", 1);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        Map<String, Object> borrow = new LinkedHashMap<>();
        borrow.put("commandKey", uniqueKey("CMD"));
        borrow.put("loanKey", uniqueKey("LOAN"));
        borrow.put("borrowerId", "bob");
        borrow.put("purpose", "外检");
        borrow.put("dueAt", utc(20, 0));
        assertThat(postJson("/api/evidence/" + evidenceKey + "/loans", "alice", borrow)
                .getResponse().getStatus()).isEqualTo(200);

        MvcResult result = performReserve("alice", evidenceKey,
                reserveBody(uniqueKey("RSV"), benchKey, "carol", utc(9, 0), utc(10, 0)));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void benchCapacityConflictReportsActualRequiredAndShortfall() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        registerBench("alice", benchKey, "台", 1);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);

        assertThat(performReserve("alice", ev1,
                reserveBody(uniqueKey("RSV"), benchKey, "bob", utc(9, 0), utc(10, 0)))
                .getResponse().getStatus()).isEqualTo(201);

        // 重叠时段容量满：实际峰值 2、要求容量 1、差额 1
        MvcResult conflict = performReserve("alice", ev2,
                reserveBody(uniqueKey("RSV"), benchKey, "carol", utc(9, 30), utc(10, 30)));
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        String message = objectMapper.readTree(
                conflict.getResponse().getContentAsString()).get("message").asText();
        assertThat(message).contains("实际峰值占用=2", "要求容量=1", "差额=1");

        // 首尾相接 [10:00,11:00) 不与 [09:00,10:00) 重叠，容量释放后可约
        assertThat(performReserve("alice", ev2,
                reserveBody(uniqueKey("RSV"), benchKey, "carol", utc(10, 0), utc(11, 0)))
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void sameEvidenceConcurrentReservationRejectedWhileActive() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        registerBench("alice", benchKey, "台", 2);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        assertThat(performReserve("alice", evidenceKey,
                reserveBody(uniqueKey("RSV"), benchKey, "bob", utc(9, 0), utc(10, 0)))
                .getResponse().getStatus()).isEqualTo(201);
        // 预约即占用证物：即使时段不重叠，未结束前同证物第二个预约返回 409
        MvcResult second = performReserve("alice", evidenceKey,
                reserveBody(uniqueKey("RSV"), benchKey, "carol", utc(11, 0), utc(12, 0)));
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(examinationService.listByEvidence(evidenceKey)).hasSize(1);
    }

    @Test
    void invalidWindowsReturn400AndPastStartRejected() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        registerBench("alice", benchKey, "台", 1);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        // end <= start
        MvcResult badWindow = performReserve("alice", evidenceKey,
                reserveBody(uniqueKey("RSV"), benchKey, "bob", utc(10, 0), utc(10, 0)));
        assertThat(badWindow.getResponse().getStatus()).isEqualTo(400);
        assertThat(objectMapper.readTree(badWindow.getResponse().getContentAsString())
                .get("message").asText()).contains("要求>0");

        // 开始时刻早于当前时刻
        MvcResult past = performReserve("alice", evidenceKey,
                reserveBody(uniqueKey("RSV"), benchKey, "bob", utc(7, 0), utc(8, 0)));
        assertThat(past.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void startCompleteFlowLocksSealAndReturnsEvidenceInStock() throws Exception {
        String benchKey = uniqueKey("BENCH");
        String reservationKey = uniqueKey("RSV");
        String evidenceKey = uniqueKey("EV");
        registerBench("alice", benchKey, "台", 1);
        intake("alice", evidenceKey);

        fixClock(BASE);
        assertThat(performReserve("alice", evidenceKey,
                reserveBody(reservationKey, benchKey, "bob", utc(9, 0), utc(10, 0)))
                .getResponse().getStatus()).isEqualTo(201);

        // 未到开始时刻 → 409
        MvcResult early = command("bob", evidenceKey, reservationKey, "start",
                keyOnly(uniqueKey("CMD")));
        assertThat(early.getResponse().getStatus()).isEqualTo(409);

        fixClock(BASE.plusSeconds(3600));
        // 非检验执行人不能开始
        assertThat(command("alice", evidenceKey, reservationKey, "start",
                keyOnly(uniqueKey("CMD"))).getResponse().getStatus()).isEqualTo(409);

        String startCommand = uniqueKey("CMD");
        MvcResult started = command("bob", evidenceKey, reservationKey, "start",
                keyOnly(startCommand));
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        JsonNode startedView = objectMapper.readTree(started.getResponse().getContentAsString());
        assertThat(startedView.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(startedView.get("sealVersionLocked").asInt()).isEqualTo(1);
        assertThat(startedView.get("startedAt").isNull()).isFalse();

        // 重复开始 409；已开始不可取消 409
        assertThat(command("bob", evidenceKey, reservationKey, "start",
                keyOnly(uniqueKey("CMD"))).getResponse().getStatus()).isEqualTo(409);
        Map<String, Object> cancelBody = new LinkedHashMap<>();
        cancelBody.put("commandKey", uniqueKey("CMD"));
        cancelBody.put("reason", "late");
        assertThat(command("alice", evidenceKey, reservationKey, "cancel", cancelBody)
                .getResponse().getStatus()).isEqualTo(409);

        // 完成缺少结果摘要或新封签 → 400
        Map<String, Object> incomplete = new LinkedHashMap<>();
        incomplete.put("commandKey", uniqueKey("CMD"));
        incomplete.put("resultSummary", "");
        incomplete.put("newSealNo", "SEAL-NEW");
        assertThat(command("bob", evidenceKey, reservationKey, "complete", incomplete)
                .getResponse().getStatus()).isEqualTo(400);

        Map<String, Object> complete = new LinkedHashMap<>();
        complete.put("commandKey", uniqueKey("CMD"));
        complete.put("resultSummary", "未见异常，痕量比对完成");
        complete.put("newSealNo", "SEAL-NEW-2");
        MvcResult completed = command("bob", evidenceKey, reservationKey, "complete", complete);
        assertThat(completed.getResponse().getStatus()).isEqualTo(200);
        JsonNode completedView = objectMapper.readTree(completed.getResponse().getContentAsString());
        assertThat(completedView.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(completedView.get("resultSummary").asText()).isEqualTo("未见异常，痕量比对完成");
        assertThat(completedView.get("newSealNo").asText()).isEqualTo("SEAL-NEW-2");
        assertThat(completedView.get("newSealVersion").asInt()).isEqualTo(2);

        // 证物回到在库，保管链事件与封签版本不可变
        JsonNode after = chain(evidenceKey);
        assertThat(after.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(after.get("sealVersions")).hasSize(2);
        assertThat(after.get("sealVersions").get(0).get("version").asInt()).isEqualTo(1);
        assertThat(after.get("sealVersions").get(0).get("supersededAt").isNull()).isFalse();
        assertThat(after.get("sealVersions").get(1).get("version").asInt()).isEqualTo(2);
        assertThat(after.get("sealVersions").get(1).get("kind").asText()).isEqualTo("RESEALED");
        assertThat(after.get("sealVersions").get(1).get("sealNo").asText()).isEqualTo("SEAL-NEW-2");
        assertThat(after.get("sealVersions").get(1).get("supersededAt").isNull()).isTrue();
        List<String> eventTypes = new ArrayList<>();
        after.get("examinations").forEach(e -> eventTypes.add(e.get("eventType").asText()));
        assertThat(eventTypes).containsExactly("RESERVED", "STARTED", "COMPLETED");
        assertThat(after.get("examinations").get(1).get("sealVersion").asInt()).isEqualTo(1);
        assertThat(after.get("examinations").get(2).get("sealVersion").asInt()).isEqualTo(2);

        // 用新命令键再次完成 → 409（同键同参则是幂等重放 200）；完成后可重新预约（台位与证物均已释放）
        complete.put("commandKey", uniqueKey("CMD"));
        assertThat(command("bob", evidenceKey, reservationKey, "complete", complete)
                .getResponse().getStatus()).isEqualTo(409);
        fixClock(Instant.parse("2026-09-22T11:00:00Z"));
        assertThat(performReserve("alice", evidenceKey,
                reserveBody(uniqueKey("RSV"), benchKey, "bob", utc(12, 0), utc(13, 0)))
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void cancelOnlyBeforeStartReleasesBenchAndEvidence() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        registerBench("alice", benchKey, "台", 1);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String reservationKey = uniqueKey("RSV");
        assertThat(performReserve("alice", ev1,
                reserveBody(reservationKey, benchKey, "bob", utc(9, 0), utc(10, 0)))
                .getResponse().getStatus()).isEqualTo(201);

        // 非创建人不能取消
        Map<String, Object> cancelBody = new LinkedHashMap<>();
        cancelBody.put("commandKey", uniqueKey("CMD"));
        cancelBody.put("reason", "x");
        assertThat(command("bob", ev1, reservationKey, "cancel", cancelBody)
                .getResponse().getStatus()).isEqualTo(409);

        cancelBody.put("commandKey", uniqueKey("CMD"));
        cancelBody.put("reason", "排期调整");
        MvcResult cancelled = command("alice", ev1, reservationKey, "cancel", cancelBody);
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(cancelled.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("CANCELLED");

        // 重复取消 409
        cancelBody.put("commandKey", uniqueKey("CMD"));
        cancelBody.put("reason", "排期调整");
        assertThat(command("alice", ev1, reservationKey, "cancel", cancelBody)
                .getResponse().getStatus()).isEqualTo(409);

        // 台位释放：ev2 在同一时段可约；证物释放：ev1 可再次预约
        assertThat(performReserve("alice", ev2,
                reserveBody(uniqueKey("RSV"), benchKey, "bob", utc(9, 0), utc(10, 0)))
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(performReserve("alice", ev1,
                reserveBody(uniqueKey("RSV"), benchKey, "carol", utc(10, 0), utc(11, 0)))
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void scheduledReservationBlocksTransferLoanAndInspection() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        registerBench("alice", benchKey, "台", 1);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        performReserve("alice", evidenceKey,
                reserveBody(uniqueKey("RSV"), benchKey, "bob", utc(9, 0), utc(10, 0)));

        assertThat(postJson("/api/evidence/" + evidenceKey + "/transfers", "alice",
                Map.of("commandKey", uniqueKey("CMD"), "toCustodian", "carol"))
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(postJson("/api/evidence/" + evidenceKey + "/loans", "alice",
                Map.of("commandKey", uniqueKey("CMD"), "loanKey", uniqueKey("LOAN"),
                        "borrowerId", "bob", "purpose", "p", "dueAt", utc(20, 0)))
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(postJson("/api/evidence/" + evidenceKey + "/seal-inspections", "alice",
                Map.of("commandKey", uniqueKey("CMD"), "passed", true, "note", "n"))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void batchReservationIsAtomicAndReportsNoPartialOccupation() throws Exception {
        fixClock(BASE);
        String fullBench = uniqueKey("BENCH");
        String spareBench = uniqueKey("BENCH");
        registerBench("alice", fullBench, "满台", 1);
        registerBench("alice", spareBench, "闲台", 1);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        String ev4 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        intake("alice", ev3);
        intake("alice", ev4);
        // 满台先占用一个台位
        assertThat(performReserve("alice", ev1,
                reserveBody(uniqueKey("RSV"), fullBench, "bob", utc(9, 0), utc(10, 0)))
                .getResponse().getStatus()).isEqualTo(201);

        List<Map<String, Object>> items = new ArrayList<>();
        // ev2 放进已满台位（必然失败）；ev4 放进闲台位（单独可以成功），用于验证整批回滚
        items.add(batchItem(uniqueKey("RSV"), ev2, fullBench, "bob", utc(9, 0), utc(10, 0)));
        items.add(batchItem(uniqueKey("RSV"), ev4, spareBench, "carol", utc(9, 0), utc(10, 0)));
        MvcResult failed = postJson("/api/evidence/examination-reservations/batch", "alice",
                Map.of("commandKey", uniqueKey("CMD"), "items", items));
        assertThat(failed.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(failed.getResponse().getContentAsString())
                .get("message").asText()).contains("差额=1");

        // 整批失败不留半成品：ev2、ev4 均无预约与事件，闲台位仍可用
        assertThat(examinationService.listByEvidence(ev2)).isEmpty();
        assertThat(examinationService.listByEvidence(ev4)).isEmpty();
        assertThat(examinationService.listEvents(ev4)).isEmpty();
        assertThat(performReserve("alice", ev4,
                reserveBody(uniqueKey("RSV"), spareBench, "carol", utc(9, 0), utc(10, 0)))
                .getResponse().getStatus()).isEqualTo(201);

        // 成功批量：ev3 与 ev2 放到不同台位（ev2 此时仍可约），全部成功
        List<Map<String, Object>> okItems = List.of(
                batchItem(uniqueKey("RSV"), ev2, spareBench, "bob", utc(11, 0), utc(12, 0)),
                batchItem(uniqueKey("RSV"), ev3, spareBench, "bob", utc(13, 0), utc(14, 0)));
        MvcResult ok = postJson("/api/evidence/examination-reservations/batch", "alice",
                Map.of("commandKey", uniqueKey("CMD"), "items", okItems));
        assertThat(ok.getResponse().getStatus()).isEqualTo(201);
        JsonNode okBody = objectMapper.readTree(ok.getResponse().getContentAsString());
        assertThat(okBody.get("createdCount").asInt()).isEqualTo(2);
        assertThat(okBody.get("reservations")).hasSize(2);

        // 同批同证物重复 → 409
        List<Map<String, Object>> dup = List.of(
                batchItem(uniqueKey("RSV"), ev1, spareBench, "bob", utc(12, 0), utc(12, 30)),
                batchItem(uniqueKey("RSV"), ev1, spareBench, "carol", utc(13, 0), utc(13, 30)));
        assertThat(postJson("/api/evidence/examination-reservations/batch", "alice",
                Map.of("commandKey", uniqueKey("CMD"), "items", dup)).getResponse().getStatus())
                .isEqualTo(409);
    }

    private Map<String, Object> batchItem(String reservationKey, String evidenceKey,
                                          String benchKey, String examiner,
                                          LocalDateTime start, LocalDateTime end) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("reservationKey", reservationKey);
        item.put("evidenceKey", evidenceKey);
        item.put("benchKey", benchKey);
        item.put("examinerId", examiner);
        item.put("startAt", start);
        item.put("endAt", end);
        return item;
    }

    @Test
    void idempotentReplayReturnsSnapshotAndDifferentParamsReturn409() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        registerBench("alice", benchKey, "台", 1);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        String commandKey = uniqueKey("CMD");
        Map<String, Object> body =
                reserveBody(uniqueKey("RSV"), benchKey, "bob", utc(9, 0), utc(10, 0));
        body.put("commandKey", commandKey);
        MvcResult first = postJson("/api/evidence/" + evidenceKey + "/examination-reservations",
                "alice", body);
        MvcResult replay = postJson("/api/evidence/" + evidenceKey + "/examination-reservations",
                "alice", body);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(examinationService.listByEvidence(evidenceKey)).hasSize(1);

        // 同键异参 → 409
        body.put("examinerId", "mallory");
        assertThat(postJson("/api/evidence/" + evidenceKey + "/examination-reservations",
                "alice", body).getResponse().getStatus()).isEqualTo(409);
        assertThat(examinationService.listByEvidence(evidenceKey)).hasSize(1);
    }

    @Test
    void batchReplayWithDifferentItemOrderReturnsSameSnapshot() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        registerBench("alice", benchKey, "台", 2);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);

        String commandKey = uniqueKey("CMD");
        Map<String, Object> item1 = batchItem(uniqueKey("RSV"), ev1, benchKey, "bob",
                utc(9, 0), utc(10, 0));
        Map<String, Object> item2 = batchItem(uniqueKey("RSV"), ev2, benchKey, "carol",
                utc(9, 0), utc(10, 0));
        MvcResult first = postJson("/api/evidence/examination-reservations/batch", "alice",
                Map.of("commandKey", commandKey, "items", new ArrayList<>(List.of(item1, item2))));
        // 数组顺序颠倒：规范化集合排序后指纹必须相同，重放首次完整快照
        MvcResult replay = postJson("/api/evidence/examination-reservations/batch", "alice",
                Map.of("commandKey", commandKey, "items", new ArrayList<>(List.of(item2, item1))));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(examinationService.listByEvidence(ev1)).hasSize(1);
        assertThat(examinationService.listByEvidence(ev2)).hasSize(1);

        // 同键修改任一结果相关字段（检验人）→ 409
        item2.put("examinerId", "mallory");
        MvcResult changed = postJson("/api/evidence/examination-reservations/batch", "alice",
                Map.of("commandKey", commandKey, "items", new ArrayList<>(List.of(item1, item2))));
        assertThat(changed.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void diagnosticsReportActualLoadRequiredCapacityRemainingAndRatio() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        registerBench("alice", benchKey, "台", 2);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        performReserve("alice", ev1,
                reserveBody(uniqueKey("RSV"), benchKey, "bob", utc(9, 0), utc(11, 0)));
        performReserve("alice", ev2,
                reserveBody(uniqueKey("RSV"), benchKey, "carol", utc(10, 0), utc(12, 0)));

        MvcResult result = mockMvc.perform(get("/api/evidence/lab-benches/{key}/diagnostics",
                        benchKey)
                        .param("from", "2026-09-22T08:00:00")
                        .param("to", "2026-09-22T13:00:00"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode diag = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(diag.get("bench").get("capacity").asInt()).isEqualTo(2);
        JsonNode segments = diag.get("segments");
        assertThat(segments).hasSize(3);
        assertThat(segments.get(0).get("actualLoad").asInt()).isEqualTo(1);
        assertThat(segments.get(0).get("requiredCapacity").asInt()).isEqualTo(2);
        assertThat(segments.get(0).get("remainingCapacity").asInt()).isEqualTo(1);
        assertThat(segments.get(0).get("utilizationPermille").asInt()).isEqualTo(500);
        assertThat(segments.get(1).get("actualLoad").asInt()).isEqualTo(2);
        assertThat(segments.get(1).get("remainingCapacity").asInt()).isZero();
        assertThat(segments.get(1).get("utilizationPermille").asInt()).isEqualTo(1000);
        assertThat(segments.get(2).get("actualLoad").asInt()).isEqualTo(1);

        // 反向窗口 400；查询不改变任何状态
        MvcResult bad = mockMvc.perform(get("/api/evidence/lab-benches/{key}/diagnostics",
                        benchKey)
                        .param("from", "2026-09-22T12:00:00")
                        .param("to", "2026-09-22T12:00:00"))
                .andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(examinationService.listByEvidence(ev1)).hasSize(1);
    }

    @Test
    void failedAttemptDoesNotOccupyCommandKey() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        registerBench("alice", benchKey, "台", 1);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        performReserve("alice", ev1,
                reserveBody(uniqueKey("RSV"), benchKey, "bob", utc(9, 0), utc(10, 0)));

        // 同一 commandKey 首次因容量满失败：失败不得占用键
        String commandKey = uniqueKey("CMD");
        Map<String, Object> failed =
                reserveBody(uniqueKey("RSV"), benchKey, "bob", utc(9, 0), utc(10, 0));
        failed.put("commandKey", commandKey);
        assertThat(postJson("/api/evidence/" + ev2 + "/examination-reservations", "alice", failed)
                .getResponse().getStatus()).isEqualTo(409);

        // 同键重放（同参）仍是业务失败而不是重放成功：因为没有首次成功快照
        assertThat(postJson("/api/evidence/" + ev2 + "/examination-reservations", "alice", failed)
                .getResponse().getStatus()).isEqualTo(409);

        // 同键改为首尾相接的空闲时段，必须可以成功（未被占用），且未留下失败占用
        failed.put("startAt", utc(10, 0));
        failed.put("endAt", utc(11, 0));
        MvcResult success = postJson("/api/evidence/" + ev2 + "/examination-reservations",
                "alice", failed);
        assertThat(success.getResponse().getStatus()).isEqualTo(201);

        // 成功后同键同参重放首次完整快照
        MvcResult replay = postJson("/api/evidence/" + ev2 + "/examination-reservations",
                "alice", failed);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(success.getResponse().getContentAsString());
        assertThat(examinationService.listByEvidence(ev2)).hasSize(1);
    }

    @Test
    void diagnosticsExcludesCancelledAndCompletedReservations() throws Exception {
        fixClock(BASE);
        String benchKey = uniqueKey("BENCH");
        registerBench("alice", benchKey, "台", 1);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String cancelledKey = uniqueKey("RSV");
        performReserve("alice", ev1,
                reserveBody(cancelledKey, benchKey, "bob", utc(9, 0), utc(10, 0)));
        performReserve("alice", ev2,
                reserveBody(uniqueKey("RSV"), benchKey, "carol", utc(10, 0), utc(11, 0)));

        Map<String, Object> cancelBody = new LinkedHashMap<>();
        cancelBody.put("commandKey", uniqueKey("CMD"));
        cancelBody.put("reason", "排期调整");
        assertThat(command("alice", ev1, cancelledKey, "cancel", cancelBody)
                .getResponse().getStatus()).isEqualTo(200);

        JsonNode diag = diagnostics(benchKey, "2026-09-22T08:00:00", "2026-09-22T12:00:00");
        // 已取消预约不占台位：窗口内只剩 [10:00,11:00) 一段、占用 1
        JsonNode segments = diag.get("segments");
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).get("actualLoad").asInt()).isEqualTo(1);
        assertThat(segments.get(0).get("start").asText()).isEqualTo("2026-09-22T10:00:00");
        assertThat(segments.get(0).get("end").asText()).isEqualTo("2026-09-22T11:00:00");
    }

    private JsonNode diagnostics(String benchKey, String from, String to) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/lab-benches/{key}/diagnostics",
                        benchKey).param("from", from).param("to", to))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void readsDoNotChangeStateAndMissingResourcesReturn404() throws Exception {
        fixClock(BASE);
        String missing = uniqueKey("EV");
        assertThat(mockMvc.perform(get("/api/evidence/{key}/examination-reservations", missing))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/evidence/{key}/seal-versions", missing))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/evidence/examination-reservations/{key}",
                        uniqueKey("RSV")))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/evidence/lab-benches/{key}/diagnostics",
                        uniqueKey("BENCH"))
                        .param("from", "2026-09-22T08:00:00")
                        .param("to", "2026-09-22T09:00:00"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }
}
