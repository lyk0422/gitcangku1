package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 接续保障 API 端到端测试（H2 内存库）：登记主流程与 422 失败分支、风险标记与证据固化、
 * 批量保障改签主流程/回滚/幂等、历史不可变与只读查询。各用例使用唯一业务键隔离数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConnectionApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    /** 默认判定时钟：运营日前夜，所有计划均未开始。 */
    private static final Instant BEFORE_DAY = Instant.parse("2026-09-21T16:00:00Z");

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(BEFORE_DAY);
        }
    }

    /** 可控时钟：支持"已开始计划不可自动标记候选改签"分支。 */
    static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant newInstant) {
            this.instant = newInstant;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(BEFORE_DAY);
    }

    // ---------- 登记主流程 ----------

    @Test
    void registerConnectionMainFlowAndEvaluation() throws Exception {
        String secT = key("SECT");
        String pred = key("PRED");
        String succ = key("SUCC");
        createPlan(pred, occ("G1", key("SECA"), iso(8), iso(8, 30)), occ("G1", secT, iso(8, 30), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secT, iso(9, 30), iso(9, 45)), occ("G2", key("SECB"), iso(9, 45), iso(10, 30)));
        publishPlan(succ);

        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, secT, 15, false)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(result);
        long connId = body.get("id").asLong();
        assertThat(body.get("predecessorScheduleKey").asText()).isEqualTo(pred);
        assertThat(body.get("successorScheduleKey").asText()).isEqualTo(succ);
        assertThat(body.get("transferSectionId").asText()).isEqualTo(secT);
        assertThat(body.get("guaranteeMinutes").asInt()).isEqualTo(15);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("crossMidnight").asBoolean()).isFalse();
        // 评估：要求 15 分钟，实际 30 分钟，差额 -15（富余）
        assertThat(body.get("requiredGapMinutes").asInt()).isEqualTo(15);
        assertThat(body.get("actualGapMinutes").asInt()).isEqualTo(30);
        assertThat(body.get("shortfallMinutes").asInt()).isEqualTo(-15);
        assertThat(body.get("predecessorArrivalUtc").asText()).isEqualTo(iso(9));
        assertThat(body.get("successorDepartureUtc").asText()).isEqualTo(iso(9, 30));
        assertThat(body.get("cancelledAt").isNull()).isTrue();

        // 明细查询与按计划查询一致
        MvcResult detail = mvc.perform(get("/api/v1/connections/{id}", connId))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);
        MvcResult byPlan = mvc.perform(get("/api/v1/plans/{key}/connections", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(byPlan)).hasSize(1);
        assertThat(read(byPlan).get(0).get("id").asLong()).isEqualTo(connId);
    }

    @Test
    void registerCrossMidnightSuccess() throws Exception {
        String secT = key("SECT");
        String pred = key("PRED");
        String succ = key("SUCC");
        createPlan(pred, occ("G1", secT, iso(23), iso(23, 50)));
        publishPlan(pred);
        // 后车次日运营日 00:20-01:00（Asia/Shanghai）
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBodyOnDate(key("REQ"), succ, DAY.plusDays(1),
                                occ("G2", secT, isoOn(1, 0, 20), isoOn(1, 1)))))
                .andExpect(status().isCreated());
        publishPlan(succ);

        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, secT, 15, true)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("crossMidnight").asBoolean()).isTrue();
        assertThat(body.get("actualGapMinutes").asInt()).isEqualTo(30);
    }

    // ---------- 登记 422 / 404 / 409 失败分支 ----------

    @Test
    void registerRejectsInvalidGuaranteeRange() throws Exception {
        String secT = key("SECT");
        String pred = key("PRED");
        String succ = key("SUCC");
        createPlan(pred, occ("G1", secT, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secT, iso(9, 30), iso(10)));
        publishPlan(succ);

        for (int guarantee : new int[]{0, -5, 1441}) {
            MvcResult result = mvc.perform(post("/api/v1/connections")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(registerBody(key("REQ"), pred, succ, secT, guarantee, false)))
                    .andExpect(status().isUnprocessableEntity()).andReturn();
            JsonNode error = read(result);
            assertThat(error.get("code").asText()).isEqualTo("GUARANTEE_RANGE_INVALID");
            JsonNode detail = error.get("details").get(0);
            assertThat(detail.get("minMinutes").asInt()).isEqualTo(1);
            assertThat(detail.get("maxMinutes").asInt()).isEqualTo(1440);
            assertThat(detail.get("actualMinutes").asInt()).isEqualTo(guarantee);
        }
    }

    @Test
    void registerRejectsCancelledAndDraftPlans() throws Exception {
        String secT = key("SECT");
        String cancelled = key("PRED");
        createPlan(cancelled, occ("G1", secT, iso(8), iso(9)));
        publishPlan(cancelled);
        cancelPlan(cancelled);
        String succ = key("SUCC");
        createPlan(succ, occ("G2", secT, iso(9, 30), iso(10)));
        publishPlan(succ);

        MvcResult cancelledResult = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), cancelled, succ, secT, 15, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(cancelledResult).get("code").asText()).isEqualTo("PLAN_CANCELLED");

        String draft = key("SUCC");
        createPlan(draft, occ("G3", secT, iso(11), iso(12)));
        String pred2 = key("PRED");
        createPlan(pred2, occ("G4", secT, iso(8), iso(9)));
        publishPlan(pred2);
        MvcResult draftResult = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred2, draft, secT, 15, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(draftResult).get("code").asText()).isEqualTo("PLAN_NOT_PUBLISHED");
    }

    @Test
    void registerRejectsStationNotContinuous() throws Exception {
        String secT = key("SECT");
        String pred = key("PRED");
        String succ = key("SUCC");
        createPlan(pred, occ("G1", key("SECA"), iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secT, iso(9, 30), iso(10)));
        publishPlan(succ);

        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, secT, 15, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("STATION_NOT_CONTINUOUS");
        assertThat(error.get("details").get(0).get("transferSectionId").asText()).isEqualTo(secT);
    }

    @Test
    void registerRejectsOpDateInvalid() throws Exception {
        String secT = key("SECT");
        String pred = key("PRED");
        createPlan(pred, occ("G1", secT, iso(23), iso(23, 50)));
        publishPlan(pred);
        // 次日但未声明跨零点
        String succNext = key("SUCC");
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBodyOnDate(key("REQ"), succNext, DAY.plusDays(1),
                                occ("G2", secT, isoOn(1, 0, 20), isoOn(1, 1)))))
                .andExpect(status().isCreated());
        publishPlan(succNext);
        MvcResult r1 = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succNext, secT, 15, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(r1).get("code").asText()).isEqualTo("OP_DATE_INVALID");

        // 相隔两天，即使声明跨零点也非法
        String succFar = key("SUCC");
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBodyOnDate(key("REQ"), succFar, DAY.plusDays(2),
                                occ("G3", secT, isoOn(2, 0, 20), isoOn(2, 1)))))
                .andExpect(status().isCreated());
        publishPlan(succFar);
        MvcResult r2 = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succFar, secT, 15, true)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(r2).get("code").asText()).isEqualTo("OP_DATE_INVALID");
    }

    @Test
    void registerRejectsGuaranteeNotMet() throws Exception {
        String secT = key("SECT");
        String pred = key("PRED");
        String succ = key("SUCC");
        createPlan(pred, occ("G1", secT, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secT, iso(9, 30), iso(10)));
        publishPlan(succ);

        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, secT, 45, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("GUARANTEE_NOT_MET");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("requiredGapMinutes").asInt()).isEqualTo(45);
        assertThat(detail.get("actualGapMinutes").asInt()).isEqualTo(30);
        assertThat(detail.get("shortfallMinutes").asInt()).isEqualTo(15);
    }

    @Test
    void registerMissingPlanAndDuplicatePair() throws Exception {
        String secT = key("SECT");
        String pred = key("PRED");
        String succ = key("SUCC");
        createPlan(pred, occ("G1", secT, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secT, iso(9, 30), iso(10)));
        publishPlan(succ);

        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, key("MISSING"), secT, 15, false)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, secT, 15, false)))
                .andExpect(status().isCreated());
        MvcResult dup = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, secT, 20, false)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(dup).get("code").asText()).isEqualTo("CONNECTION_EXISTS");
    }

    @Test
    void registerIdempotencyReplayAndKeyReuse() throws Exception {
        String secT = key("SECT");
        String pred = key("PRED");
        String succ = key("SUCC");
        createPlan(pred, occ("G1", secT, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secT, iso(9, 30), iso(10)));
        publishPlan(succ);

        String requestKey = key("REQ");
        String body = registerBody(requestKey, pred, succ, secT, 15, false);
        MvcResult first = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键异参 → 409
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, pred, succ, secT, 20, false)))
                .andExpect(status().isConflict());

        // 失败不占键：422 后同键修正参数可成功
        String failKey = key("REQ");
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(failKey, pred, succ, key("SECX"), 15, false)))
                .andExpect(status().isUnprocessableEntity());
        String pred2 = key("PRED");
        String succ2 = key("SUCC");
        String secT2 = key("SECT");
        createPlan(pred2, occ("G3", secT2, iso(8), iso(9)));
        publishPlan(pred2);
        createPlan(succ2, occ("G4", secT2, iso(9, 30), iso(10)));
        publishPlan(succ2);
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(failKey, pred2, succ2, secT2, 15, false)))
                .andExpect(status().isCreated());
    }

    // ---------- 风险标记与证据固化 ----------

    /** 场景：前车改签延后 09:00→10:50 终到，后车 09:30 始发，保障 15 分钟。 */
    private record RiskScene(String secT, String pred, String predNew, String succ, long connId) {
    }

    private RiskScene setupRiskScene() throws Exception {
        String secA = key("SECA");
        String secB = key("SECB");
        String secT = key("SECT");
        String pred = key("PRED");
        String succ = key("SUCC");
        createPlan(pred, occ("G1", secA, iso(8), iso(8, 30)), occ("G1", secT, iso(8, 30), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secT, iso(9, 30), iso(9, 45)), occ("G2", secB, iso(9, 45), iso(10, 30)));
        publishPlan(succ);
        MvcResult conn = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, secT, 15, false)))
                .andExpect(status().isCreated()).andReturn();
        long connId = read(conn).get("id").asLong();
        // 前车改签延后：新计划 09:50-10:20 / 10:20-10:50
        String predNew = key("PREDN");
        createPlan(predNew, occ("G1B", secA, iso(9, 50), iso(10, 20)), occ("G1B", secT, iso(10, 20), iso(10, 50)));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", pred)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), predNew, 1, 1)))
                .andExpect(status().isOk());
        return new RiskScene(secT, pred, predNew, succ, connId);
    }

    @Test
    void predecessorDelayMarksRiskAndRecordsEvidence() throws Exception {
        RiskScene scene = setupRiskScene();

        // 后车标记 CONNECTION_AT_RISK，但时刻不被自动修改
        MvcResult succDetail = mvc.perform(get("/api/v1/plans/{key}", scene.succ()))
                .andExpect(status().isOk()).andReturn();
        JsonNode succBody = read(succDetail);
        assertThat(succBody.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(succBody.get("connectionRisk").asText()).isEqualTo("AT_RISK");
        assertThat(succBody.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(9, 30));

        // 风险证据：固化前车版本、原新时刻与保障差额，含可选改签时间窗
        MvcResult risks = mvc.perform(get("/api/v1/plans/{key}/connection-risks", scene.succ()))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = read(risks);
        assertThat(list).hasSize(1);
        JsonNode risk = list.get(0);
        assertThat(risk.get("connectionId").asLong()).isEqualTo(scene.connId());
        assertThat(risk.get("predecessorScheduleKey").asText()).isEqualTo(scene.predNew());
        assertThat(risk.get("successorScheduleKey").asText()).isEqualTo(scene.succ());
        assertThat(risk.get("predecessorOldScheduleKey").asText()).isEqualTo(scene.pred());
        assertThat(risk.get("predecessorNewScheduleKey").asText()).isEqualTo(scene.predNew());
        assertThat(risk.get("predecessorBaseVersion").asInt()).isEqualTo(1);
        assertThat(risk.get("predecessorRescheduledVersion").asInt()).isEqualTo(1);
        assertThat(risk.get("oldPredArrivalUtc").asText()).isEqualTo(iso(9));
        assertThat(risk.get("newPredArrivalUtc").asText()).isEqualTo(iso(10, 50));
        assertThat(risk.get("oldSuccessorDepartureUtc").asText()).isEqualTo(iso(9, 30));
        assertThat(risk.get("newSuccessorDepartureUtc").asText()).isEqualTo(iso(11, 5));
        assertThat(risk.get("guaranteeMinutes").asInt()).isEqualTo(15);
        assertThat(risk.get("requiredGapMinutes").asInt()).isEqualTo(15);
        assertThat(risk.get("actualGapMinutes").asInt()).isEqualTo(-80);
        assertThat(risk.get("shortfallMinutes").asInt()).isEqualTo(95);
        assertThat(risk.get("candidateAvailable").asBoolean()).isTrue();
        assertThat(risk.get("successorStarted").asBoolean()).isFalse();
        assertThat(risk.get("candidateWindowStartUtc").asText()).isEqualTo(iso(11, 5));
        assertThat(risk.get("candidateWindowEndUtc").asText()).isNotNull();

        // 诊断查询汇总一致且只读
        MvcResult diag = mvc.perform(get("/api/v1/plans/{key}/connection-diagnostics", scene.succ()))
                .andExpect(status().isOk()).andReturn();
        JsonNode diagBody = read(diag);
        assertThat(diagBody.get("plan").get("connectionRisk").asText()).isEqualTo("AT_RISK");
        assertThat(diagBody.get("connections")).hasSize(1);
        assertThat(diagBody.get("risks")).hasSize(1);
        assertThat(diagBody.get("decisions")).hasSize(0);
    }

    @Test
    void startedSuccessorGetsNoCandidateWindow() throws Exception {
        // 判定时刻已过后车始发（09:30 SH = 01:30 UTC）：不可自动标记候选改签
        clock.set(Instant.parse("2026-09-22T02:00:00Z"));
        RiskScene scene = setupRiskScene();

        MvcResult risks = mvc.perform(get("/api/v1/plans/{key}/connection-risks", scene.succ()))
                .andExpect(status().isOk()).andReturn();
        JsonNode risk = read(risks).get(0);
        assertThat(risk.get("successorStarted").asBoolean()).isTrue();
        assertThat(risk.get("candidateAvailable").asBoolean()).isFalse();
        assertThat(risk.get("candidateWindowStartUtc").isNull()).isTrue();
        assertThat(risk.get("candidateWindowEndUtc").isNull()).isTrue();
        assertThat(risk.get("newSuccessorDepartureUtc").isNull()).isTrue();

        // 风险标记仍然打上
        MvcResult succDetail = mvc.perform(get("/api/v1/plans/{key}", scene.succ()))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(succDetail).get("connectionRisk").asText()).isEqualTo("AT_RISK");
    }

    @Test
    void riskHistoryImmutableAfterRerescheduleAndCancel() throws Exception {
        RiskScene scene = setupRiskScene();
        MvcResult firstRisks = mvc.perform(get("/api/v1/plans/{key}/connection-risks", scene.succ()))
                .andExpect(status().isOk()).andReturn();
        JsonNode firstList = read(firstRisks);
        assertThat(firstList).hasSize(1);

        // 前车再次改签延后（10:50 → 12:00 终到）：追加第二条风险记录，首条不改写
        String predNew2 = key("PREDN");
        createPlan(predNew2, occ("G1C", key("SECA"), iso(11), iso(11, 30)),
                occ("G1C", scene.secT(), iso(11, 30), iso(12)));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", scene.predNew())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), predNew2, 1, 1)))
                .andExpect(status().isOk());
        MvcResult secondRisks = mvc.perform(get("/api/v1/plans/{key}/connection-risks", scene.succ()))
                .andExpect(status().isOk()).andReturn();
        JsonNode secondList = read(secondRisks);
        assertThat(secondList).hasSize(2);
        assertThat(secondList.get(0)).isEqualTo(firstList.get(0));
        JsonNode second = secondList.get(1);
        assertThat(second.get("predecessorOldScheduleKey").asText()).isEqualTo(scene.predNew());
        assertThat(second.get("predecessorNewScheduleKey").asText()).isEqualTo(predNew2);
        assertThat(second.get("oldPredArrivalUtc").asText()).isEqualTo(iso(10, 50));
        assertThat(second.get("newPredArrivalUtc").asText()).isEqualTo(iso(12));
        assertThat(second.get("actualGapMinutes").asInt()).isEqualTo(-150);
        assertThat(second.get("shortfallMinutes").asInt()).isEqualTo(165);

        // 取消接续：历史风险记录不改写
        mvc.perform(post("/api/v1/connections/{id}/cancel", scene.connId())
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        MvcResult afterCancel = mvc.perform(get("/api/v1/plans/{key}/connection-risks", scene.succ()))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(afterCancel)).isEqualTo(secondList);
    }

    // ---------- 取消接续 ----------

    @Test
    void cancelConnectionLifecycleAndIdempotency() throws Exception {
        String secT = key("SECT");
        String pred = key("PRED");
        String succ = key("SUCC");
        createPlan(pred, occ("G1", secT, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secT, iso(9, 30), iso(10)));
        publishPlan(succ);
        MvcResult conn = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, secT, 15, false)))
                .andExpect(status().isCreated()).andReturn();
        long connId = read(conn).get("id").asLong();

        String cancelKey = key("REQ");
        MvcResult cancelled = mvc.perform(post("/api/v1/connections/{id}/cancel", connId)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(cancelKey)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(cancelled);
        assertThat(body.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("cancelledAt").isNull()).isFalse();

        // 同键重放返回首次结果
        MvcResult replay = mvc.perform(post("/api/v1/connections/{id}/cancel", connId)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(cancelKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(body);

        // 重复取消（新键）→ 409
        mvc.perform(post("/api/v1/connections/{id}/cancel", connId)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());

        // 不存在的接续 → 404
        mvc.perform(get("/api/v1/connections/{id}", 999999999L))
                .andExpect(status().isNotFound());
    }

    // ---------- 批量保障改签 ----------

    @Test
    void batchGuaranteeMainFlow() throws Exception {
        RiskScene scene = setupRiskScene();
        String succNew = key("SUCCN");
        // 新草稿 11:05 始发 = 前车终到 10:50 + 保障 15 分钟
        createPlan(succNew, occ("G2B", scene.secT(), iso(11, 5), iso(11, 20)),
                occ("G2B", key("SECB"), iso(11, 20), iso(12, 5)));

        MvcResult result = mvc.perform(post("/api/v1/connections/batch-guarantee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"),
                                item(scene.succ(), succNew, 1, 1))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("items")).hasSize(1);
        JsonNode decision = body.get("items").get(0);
        assertThat(decision.get("itemResult").asText()).isEqualTo("REBOOKED");
        assertThat(decision.get("connectionId").asLong()).isEqualTo(scene.connId());
        assertThat(decision.get("oldSuccessorScheduleKey").asText()).isEqualTo(scene.succ());
        assertThat(decision.get("newSuccessorScheduleKey").asText()).isEqualTo(succNew);
        assertThat(decision.get("predecessorScheduleKey").asText()).isEqualTo(scene.predNew());
        assertThat(decision.get("successorBaseVersion").asInt()).isEqualTo(1);
        assertThat(decision.get("oldDepartureUtc").asText()).isEqualTo(iso(9, 30));
        assertThat(decision.get("newDepartureUtc").asText()).isEqualTo(iso(11, 5));
        assertThat(decision.get("predecessorArrivalUtc").asText()).isEqualTo(iso(10, 50));
        assertThat(decision.get("guaranteeMinutes").asInt()).isEqualTo(15);
        assertThat(decision.get("actualGapMinutes").asInt()).isEqualTo(15);
        assertThat(decision.get("shortfallMinutes").asInt()).isEqualTo(0);
        assertThat(decision.get("reasonCode").isNull()).isTrue();

        // 旧后车取消、新车发布且风险清除；改签链有序
        MvcResult oldDetail = mvc.perform(get("/api/v1/plans/{key}", scene.succ()))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(oldDetail).get("status").asText()).isEqualTo("CANCELLED");
        MvcResult newDetail = mvc.perform(get("/api/v1/plans/{key}", succNew))
                .andExpect(status().isOk()).andReturn();
        JsonNode newBody = read(newDetail);
        assertThat(newBody.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(newBody.get("connectionRisk").asText()).isEqualTo("NONE");
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", scene.succ()))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(chain).get("chain")).hasSize(2);

        // 决策历史可查且只读
        MvcResult decisions = mvc.perform(get("/api/v1/plans/{key}/connection-decisions", scene.succ()))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(decisions)).hasSize(1);
        assertThat(read(decisions).get(0).get("itemResult").asText()).isEqualTo("REBOOKED");
    }

    @Test
    void batchRejectsNotAtRisk() throws Exception {
        String secT = key("SECT");
        String pred = key("PRED");
        String succ = key("SUCC");
        createPlan(pred, occ("G1", secT, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secT, iso(9, 30), iso(10)));
        publishPlan(succ);
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, secT, 15, false)))
                .andExpect(status().isCreated());
        String succNew = key("SUCCN");
        createPlan(succNew, occ("G2B", secT, iso(11), iso(12)));

        MvcResult result = mvc.perform(post("/api/v1/connections/batch-guarantee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), item(succ, succNew, 1, 1))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("NOT_AT_RISK");
        assertThat(error.get("details").get(0).get("connectionRisk").asText()).isEqualTo("NONE");

        // 无任何副作用
        MvcResult succDetail = mvc.perform(get("/api/v1/plans/{key}", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(succDetail).get("status").asText()).isEqualTo("PUBLISHED");
        MvcResult newDetail = mvc.perform(get("/api/v1/plans/{key}", succNew))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(newDetail).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void batchRejectsGuaranteeNotMetAndRollsBack() throws Exception {
        RiskScene scene = setupRiskScene();
        // 新草稿 10:55 始发，间隔仅 5 分钟 < 保障 15 分钟
        String succNew = key("SUCCN");
        createPlan(succNew, occ("G2B", scene.secT(), iso(10, 55), iso(11, 10)),
                occ("G2B", key("SECB"), iso(11, 10), iso(11, 55)));

        MvcResult result = mvc.perform(post("/api/v1/connections/batch-guarantee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), item(scene.succ(), succNew, 1, 1))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("GUARANTEE_NOT_MET");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("requiredGapMinutes").asInt()).isEqualTo(15);
        assertThat(detail.get("actualGapMinutes").asInt()).isEqualTo(5);
        assertThat(detail.get("shortfallMinutes").asInt()).isEqualTo(10);

        assertBatchRolledBack(scene.succ(), succNew);
    }

    @Test
    void batchRejectsSlotConflictAndRollsBack() throws Exception {
        RiskScene scene = setupRiskScene();
        // 第三方已发布计划占住 SEC-B 11:00-12:00，新草稿找不到不冲突时间窗
        String secB = key("SECB");
        String third = key("THIRD");
        createPlan(third, occ("G9", secB, iso(11), iso(12)));
        publishPlan(third);
        String succNew = key("SUCCN");
        createPlan(succNew, occ("G2B", scene.secT(), iso(11, 5), iso(11, 20)),
                occ("G2B", secB, iso(11, 20), iso(12, 5)));

        MvcResult risksBefore = mvc.perform(get("/api/v1/plans/{key}/connection-risks", scene.succ()))
                .andExpect(status().isOk()).andReturn();

        MvcResult result = mvc.perform(post("/api/v1/connections/batch-guarantee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), item(scene.succ(), succNew, 1, 1))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("SLOT_CONFLICT");

        assertBatchRolledBack(scene.succ(), succNew);
        // 风险历史不因失败批次新增
        MvcResult risksAfter = mvc.perform(get("/api/v1/plans/{key}/connection-risks", scene.succ()))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(risksAfter)).isEqualTo(read(risksBefore));
    }

    @Test
    void batchMultiItemAtomicity() throws Exception {
        RiskScene sceneA = setupRiskScene();
        RiskScene sceneB = setupRiskScene();
        String succNewA = key("SUCCN");
        createPlan(succNewA, occ("G2B", sceneA.secT(), iso(11, 5), iso(11, 20)),
                occ("G2B", key("SECB"), iso(11, 20), iso(12, 5)));
        // 第二条目保障不足 → 整次 422
        String succNewB = key("SUCCN");
        createPlan(succNewB, occ("G2C", sceneB.secT(), iso(10, 55), iso(11, 10)),
                occ("G2C", key("SECB"), iso(11, 10), iso(11, 55)));

        mvc.perform(post("/api/v1/connections/batch-guarantee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"),
                                item(sceneA.succ(), succNewA, 1, 1),
                                item(sceneB.succ(), succNewB, 1, 1))))
                .andExpect(status().isUnprocessableEntity());

        // 第一条目同样回滚：旧计划仍发布且 AT_RISK、新草稿仍草稿、无决策记录
        assertBatchRolledBack(sceneA.succ(), succNewA);
        assertBatchRolledBack(sceneB.succ(), succNewB);
    }

    @Test
    void batchIdempotencyReplayAndKeyReuse() throws Exception {
        RiskScene scene = setupRiskScene();
        String succNew = key("SUCCN");
        createPlan(succNew, occ("G2B", scene.secT(), iso(11, 5), iso(11, 20)),
                occ("G2B", key("SECB"), iso(11, 20), iso(12, 5)));

        String requestKey = key("REQ");
        String body = batchBody(requestKey, item(scene.succ(), succNew, 1, 1));
        MvcResult first = mvc.perform(post("/api/v1/connections/batch-guarantee")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/connections/batch-guarantee")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键异参 → 409
        mvc.perform(post("/api/v1/connections/batch-guarantee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(requestKey, item(scene.succ(), key("OTHER"), 1, 1))))
                .andExpect(status().isConflict());

        // 失败不占键：批次 422 后同键修正参数可成功
        RiskScene scene2 = setupRiskScene();
        String failKey = key("REQ");
        String badDraft = key("SUCCN");
        createPlan(badDraft, occ("G2C", scene2.secT(), iso(10, 55), iso(11, 10)),
                occ("G2C", key("SECB"), iso(11, 10), iso(11, 55)));
        mvc.perform(post("/api/v1/connections/batch-guarantee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(failKey, item(scene2.succ(), badDraft, 1, 1))))
                .andExpect(status().isUnprocessableEntity());
        String goodDraft = key("SUCCN");
        createPlan(goodDraft, occ("G2D", scene2.secT(), iso(11, 5), iso(11, 20)),
                occ("G2D", key("SECB"), iso(11, 20), iso(12, 5)));
        mvc.perform(post("/api/v1/connections/batch-guarantee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(failKey, item(scene2.succ(), goodDraft, 1, 1))))
                .andExpect(status().isOk());
    }

    // ---------- 只读查询 ----------

    @Test
    void readsDoNotChangeState() throws Exception {
        RiskScene scene = setupRiskScene();
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/v1/plans/{key}/connection-diagnostics", scene.succ()))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/plans/{key}/connections", scene.succ()))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/plans/{key}/connection-risks", scene.succ()))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/plans/{key}/connection-decisions", scene.succ()))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/connections/{id}", scene.connId()))
                    .andExpect(status().isOk());
        }
        MvcResult succDetail = mvc.perform(get("/api/v1/plans/{key}", scene.succ()))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(succDetail);
        assertThat(body.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(body.get("connectionRisk").asText()).isEqualTo("AT_RISK");
        MvcResult risks = mvc.perform(get("/api/v1/plans/{key}/connection-risks", scene.succ()))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(risks)).hasSize(1);

        // 不存在计划的查询 → 404
        mvc.perform(get("/api/v1/plans/{key}/connections", key("MISSING")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/connection-risks", key("MISSING")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/connection-diagnostics", key("MISSING")))
                .andExpect(status().isNotFound());
    }

    // ---------- 辅助 ----------

    private void assertBatchRolledBack(String oldSucc, String succNew) throws Exception {
        MvcResult oldDetail = mvc.perform(get("/api/v1/plans/{key}", oldSucc))
                .andExpect(status().isOk()).andReturn();
        JsonNode oldBody = read(oldDetail);
        assertThat(oldBody.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(oldBody.get("connectionRisk").asText()).isEqualTo("AT_RISK");
        MvcResult newDetail = mvc.perform(get("/api/v1/plans/{key}", succNew))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(newDetail).get("status").asText()).isEqualTo("DRAFT");
        // 无改签链、无决策记录
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", oldSucc))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(chain).get("chain")).hasSize(1);
        MvcResult decisions = mvc.perform(get("/api/v1/plans/{key}/connection-decisions", oldSucc))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(decisions)).hasSize(0);
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, occupancies)))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    private void cancelPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour) {
        return iso(hour, 0);
    }

    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String isoOn(int dayOffset, int hour) {
        return isoOn(dayOffset, hour, 0);
    }

    private static String isoOn(int dayOffset, int hour, int minute) {
        return DAY.plusDays(dayOffset).atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String createBody(String requestKey, String scheduleKey, String... occupancies) {
        return createBodyOnDate(requestKey, scheduleKey, DAY, occupancies);
    }

    private static String createBodyOnDate(String requestKey, String scheduleKey, LocalDate opDate,
                                           String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + opDate + "\",\"occupancies\":[" + String.join(",", occupancies)
                + "]}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }

    private static String registerBody(String requestKey, String pred, String succ,
                                       String transferSection, int guaranteeMinutes,
                                       boolean crossMidnight) {
        return "{\"requestKey\":\"" + requestKey + "\",\"predecessorScheduleKey\":\"" + pred
                + "\",\"successorScheduleKey\":\"" + succ + "\",\"transferSectionId\":\""
                + transferSection + "\",\"guaranteeMinutes\":" + guaranteeMinutes
                + ",\"crossMidnight\":" + crossMidnight + "}";
    }

    private static String item(String succ, String succNew, int expectedOldVersion,
                               int expectedNewVersion) {
        return "{\"successorScheduleKey\":\"" + succ + "\",\"newScheduleKey\":\"" + succNew
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }

    private static String batchBody(String requestKey, String... items) {
        return "{\"requestKey\":\"" + requestKey + "\",\"items\":[" + String.join(",", items)
                + "]}";
    }
}
