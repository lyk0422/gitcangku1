package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.plan.service.TimeProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 接续保障 API 测试：登记主流程与 422 失败分支、改签触发风险标记、批量保障改签与回滚、
 * 历史事件稳定性与幂等边界（H2 内存库）。用例间以唯一业务键/站点隔离数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConnectionApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    /** 固定业务时钟到运营日前一天，保证用例中的计划"未开始"。 */
    private static final Instant FIXED_NOW = Instant.parse("2026-09-21T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TimeProvider timeProvider;

    @BeforeEach
    void fixClock() {
        timeProvider.setClock(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void resetClock() {
        timeProvider.reset();
    }

    // ---------- 登记主流程 ----------

    @Test
    void registerConnectionSucceedsAndExposesEvaluation() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", secAB, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secBC, iso(10), iso(11)));
        publishPlan(succ);

        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, 30, false)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(result);
        long connectionId = body.get("id").asLong();
        assertThat(body.get("predecessorScheduleKey").asText()).isEqualTo(pred);
        assertThat(body.get("successorScheduleKey").asText()).isEqualTo(succ);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("guaranteeMinutes").asInt()).isEqualTo(30);
        assertThat(body.get("actualMinutes").asLong()).isEqualTo(60);
        assertThat(body.get("deficitMinutes").asLong()).isEqualTo(-30);
        assertThat(body.get("predecessorArrivalUtc").asText()).isEqualTo(iso(9));
        assertThat(body.get("successorDepartureUtc").asText()).isEqualTo(iso(10));
        assertThat(body.get("cancelledAt").isNull()).isTrue();

        // 明细查询一致
        MvcResult detail = mvc.perform(get("/api/v1/connections/{id}", connectionId))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);

        // 计划接续列表与风险诊断（只读）
        MvcResult list = mvc.perform(get("/api/v1/plans/{key}/connections", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(list)).hasSize(1);
        MvcResult risk = mvc.perform(get("/api/v1/plans/{key}/connection-risk", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode riskBody = read(risk);
        assertThat(riskBody.get("riskStatus").asText()).isEqualTo("NONE");
        assertThat(riskBody.get("windowStartUtc").isNull()).isTrue();
        JsonNode inbound = riskBody.get("inboundConnections");
        assertThat(inbound).hasSize(1);
        assertThat(inbound.get(0).get("sufficient").asBoolean()).isTrue();
        assertThat(inbound.get(0).get("actualMinutes").asLong()).isEqualTo(60);

        // 登记事件已固化
        JsonNode events = eventsOf(succ);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("eventType").asText()).isEqualTo("REGISTERED");
        assertThat(events.get(0).get("predecessorVersion").asInt()).isEqualTo(1);
        assertThat(events.get(0).get("deficitMinutes").asLong()).isEqualTo(-30);
        assertThat(events.get(0).get("windowStartUtc").isNull()).isTrue();
        assertThat(events.get(0).get("previousSuccessorDepartureUtc").isNull()).isTrue();
    }

    @Test
    void registerCrossMidnightRequiresExplicitFlag() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", secAB, iso(23, 0), iso(23, 50)));
        publishPlan(pred);
        createPlanOnDate(succ, DAY.plusDays(1), occ("G2", secBC, iso(0, 30, 1), iso(1, 30, 1)));
        publishPlan(succ);

        // 跨零点但未明确声明 → 422
        MvcResult rejected = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, 30, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(rejected).get("code").asText()).isEqualTo("OP_DATE_MISMATCH");

        // 明确跨零点 → 201，实际接续 40 分钟
        MvcResult accepted = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, 30, true)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(accepted).get("actualMinutes").asLong()).isEqualTo(40);
    }

    // ---------- 登记失败分支 400/404/409/422 ----------

    @Test
    void registerRejectsInvalidGuaranteeRange() throws Exception {
        String pred = key("SCH");
        String succ = key("SCH");
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, -1, false)))
                .andExpect(status().isUnprocessableEntity());
        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, 721, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("INVALID_GUARANTEE_MINUTES");
        assertThat(error.get("details").get(0).get("maxMinutes").asInt()).isEqualTo(720);
    }

    @Test
    void registerRejectsSamePlanAndMissingPlan() throws Exception {
        String plan = key("SCH");
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), plan, plan, 30, false)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("SCH"), key("SCH"), 30, false)))
                .andExpect(status().isNotFound());
    }

    @Test
    void registerRejectsCancelledAndDraftPlans() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String cancelled = key("SCH");
        createPlan(cancelled, occ("G1", secAB, iso(8), iso(9)));
        publishPlan(cancelled);
        cancelPlan(cancelled);
        String published = key("SCH");
        createPlan(published, occ("G2", secBC, iso(10), iso(11)));
        publishPlan(published);

        MvcResult cancelledResult = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), cancelled, published, 30, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(cancelledResult).get("code").asText()).isEqualTo("PLAN_CANCELLED");

        String draft = key("SCH");
        createPlan(draft, occ("G3", sectionFrom(destOf(secBC)), iso(12), iso(13)));
        MvcResult draftResult = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), published, draft, 30, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(draftResult).get("code").asText()).isEqualTo("PLAN_NOT_PUBLISHED");
    }

    @Test
    void registerRejectsStationDiscontinuity() throws Exception {
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", section(), iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", section(), iso(10), iso(11)));
        publishPlan(succ);

        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, 30, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("STATION_NOT_CONTIGUOUS");
        assertThat(error.get("details").get(0).get("predecessorArrivalStation").asText())
                .isNotEqualTo(error.get("details").get(0).get("successorOriginStation").asText());
    }

    @Test
    void registerRejectsInsufficientGuaranteeWithDeficit() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", secAB, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secBC, iso(9, 10), iso(10, 10)));
        publishPlan(succ);

        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, 30, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("GUARANTEE_INSUFFICIENT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("requiredMinutes").asInt()).isEqualTo(30);
        assertThat(detail.get("actualMinutes").asLong()).isEqualTo(10);
        assertThat(detail.get("deficitMinutes").asLong()).isEqualTo(20);
    }

    @Test
    void registerDuplicateConflictAndIdempotentReplay() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", secAB, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secBC, iso(10), iso(11)));
        publishPlan(succ);

        String requestKey = key("REQ");
        String body = registerBody(requestKey, pred, succ, 30, false);
        MvcResult first = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放首次结果
        MvcResult replay = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        // 同键异参 → 409
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, pred, succ, 45, false)))
                .andExpect(status().isConflict());
        // 同对计划重复登记（新键）→ 409
        MvcResult duplicate = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, 30, false)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(duplicate).get("code").asText()).isEqualTo("CONNECTION_EXISTS");
    }

    // ---------- 改签触发风险标记 ----------

    @Test
    void predecessorRescheduleMarksRiskWithWindowAndEvidence() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", secAB, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secBC, iso(9, 40), iso(10, 40)));
        publishPlan(succ);
        register(pred, succ, 30);

        // 前车改签延后：终到 09:00 → 10:00，接续实际 -20 分钟，保障差额 50 分钟
        String predV2 = key("SCH");
        createPlan(predV2, occ("G1", secAB, iso(9), iso(10)));
        reschedule(pred, predV2, 1, 1);

        // 后车被标记风险，时刻不被自动修改
        MvcResult risk = mvc.perform(get("/api/v1/plans/{key}/connection-risk", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode riskBody = read(risk);
        assertThat(riskBody.get("riskStatus").asText()).isEqualTo("CONNECTION_AT_RISK");
        assertThat(riskBody.get("windowStartUtc").asText()).isEqualTo(iso(10, 30));
        assertThat(riskBody.get("windowEndUtc").isNull()).isFalse();
        JsonNode inbound = riskBody.get("inboundConnections");
        assertThat(inbound).hasSize(1);
        assertThat(inbound.get(0).get("sufficient").asBoolean()).isFalse();
        assertThat(inbound.get(0).get("actualMinutes").asLong()).isEqualTo(-20);
        assertThat(inbound.get(0).get("deficitMinutes").asLong()).isEqualTo(50);

        MvcResult succDetail = mvc.perform(get("/api/v1/plans/{key}", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(succDetail).get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(9, 40));

        // 风险判定事件固化：前车版本、时刻与差额
        JsonNode events = eventsOf(succ);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("eventType").asText()).isEqualTo("REGISTERED");
        JsonNode marked = events.get(1);
        assertThat(marked.get("eventType").asText()).isEqualTo("RISK_MARKED");
        assertThat(marked.get("predecessorScheduleKey").asText()).isEqualTo(predV2);
        assertThat(marked.get("predecessorVersion").asInt()).isEqualTo(1);
        assertThat(marked.get("predecessorArrivalUtc").asText()).isEqualTo(iso(10));
        assertThat(marked.get("successorDepartureUtc").asText()).isEqualTo(iso(9, 40));
        assertThat(marked.get("deficitMinutes").asLong()).isEqualTo(50);
        assertThat(marked.get("windowStartUtc").asText()).isEqualTo(iso(10, 30));
        assertThat(marked.get("windowEndUtc").isNull()).isFalse();
    }

    @Test
    void startedPlanIsNotMarkedAsRiskCandidate() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", secAB, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secBC, iso(9, 40), iso(10, 40)));
        publishPlan(succ);
        register(pred, succ, 30);

        // 时钟推进到后车始发之后：已开始计划不可自动标记
        timeProvider.setClock(Clock.fixed(Instant.parse("2026-09-22T02:00:00Z"), ZoneOffset.UTC));
        String predV2 = key("SCH");
        createPlan(predV2, occ("G1", secAB, iso(9), iso(10)));
        reschedule(pred, predV2, 1, 1);

        MvcResult risk = mvc.perform(get("/api/v1/plans/{key}/connection-risk", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(risk).get("riskStatus").asText()).isEqualTo("NONE");
        JsonNode events = eventsOf(succ);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("eventType").asText()).isEqualTo("REGISTERED");
    }

    @Test
    void repeatedRescheduleAppendsEvidenceWithoutRewritingHistory() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", secAB, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secBC, iso(9, 40), iso(10, 40)));
        publishPlan(succ);
        register(pred, succ, 30);

        String predV2 = key("SCH");
        createPlan(predV2, occ("G1", secAB, iso(9), iso(10)));
        reschedule(pred, predV2, 1, 1);
        // 再次改签：终到 10:30，差额从 50 变为 80
        String predV3 = key("SCH");
        createPlan(predV3, occ("G1", secAB, iso(9, 30), iso(10, 30)));
        reschedule(predV2, predV3, 1, 1);

        JsonNode events = eventsOf(succ);
        assertThat(events).hasSize(3);
        assertThat(events.get(0).get("eventType").asText()).isEqualTo("REGISTERED");
        assertThat(events.get(1).get("eventType").asText()).isEqualTo("RISK_MARKED");
        assertThat(events.get(1).get("deficitMinutes").asLong()).isEqualTo(50);
        assertThat(events.get(1).get("predecessorScheduleKey").asText()).isEqualTo(predV2);
        assertThat(events.get(2).get("eventType").asText()).isEqualTo("RISK_MARKED");
        assertThat(events.get(2).get("deficitMinutes").asLong()).isEqualTo(80);
        assertThat(events.get(2).get("predecessorScheduleKey").asText()).isEqualTo(predV3);
        // 事件按发生顺序排列
        assertThat(events.get(0).get("id").asLong()).isLessThan(events.get(1).get("id").asLong());
        assertThat(events.get(1).get("id").asLong()).isLessThan(events.get(2).get("id").asLong());
    }

    // ---------- 接续取消 ----------

    @Test
    void cancelConnectionClearsRiskAndKeepsHistory() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", secAB, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secBC, iso(9, 40), iso(10, 40)));
        publishPlan(succ);
        long connectionId = register(pred, succ, 30);
        String predV2 = key("SCH");
        createPlan(predV2, occ("G1", secAB, iso(9), iso(10)));
        reschedule(pred, predV2, 1, 1);

        String requestKey = key("REQ");
        MvcResult cancelled = mvc.perform(post("/api/v1/connections/{id}/cancel", connectionId)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(cancelled);
        assertThat(body.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("cancelledAt").isNull()).isFalse();

        // 同键重放返回首次结果；再次取消（新键）→ 409
        MvcResult replay = mvc.perform(post("/api/v1/connections/{id}/cancel", connectionId)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(body);
        mvc.perform(post("/api/v1/connections/{id}/cancel", connectionId)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());

        // 风险标记解除，历史 RISK_MARKED 记录保留不改写
        MvcResult risk = mvc.perform(get("/api/v1/plans/{key}/connection-risk", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(risk).get("riskStatus").asText()).isEqualTo("NONE");
        JsonNode events = eventsOf(succ);
        assertThat(events).hasSize(3);
        assertThat(events.get(0).get("eventType").asText()).isEqualTo("REGISTERED");
        assertThat(events.get(1).get("eventType").asText()).isEqualTo("RISK_MARKED");
        assertThat(events.get(1).get("deficitMinutes").asLong()).isEqualTo(50);
        assertThat(events.get(2).get("eventType").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void planCancelCancelsActiveConnections() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", secAB, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secBC, iso(10), iso(11)));
        publishPlan(succ);
        long connectionId = register(pred, succ, 30);

        cancelPlan(pred);

        MvcResult detail = mvc.perform(get("/api/v1/connections/{id}", connectionId))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("status").asText()).isEqualTo("CANCELLED");
        JsonNode events = eventsOf(succ);
        assertThat(events).hasSize(2);
        assertThat(events.get(1).get("eventType").asText()).isEqualTo("CANCELLED");
    }

    // ---------- 批量保障改签 ----------

    @Test
    void guaranteeRescheduleShiftsToSatisfyGuarantee() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", secAB, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secBC, iso(9, 40), iso(10, 40)));
        publishPlan(succ);
        register(pred, succ, 30);
        String predV2 = key("SCH");
        createPlan(predV2, occ("G1", secAB, iso(9), iso(10)));
        reschedule(pred, predV2, 1, 1);

        MvcResult result = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(guaranteeBody(key("REQ"), succ)))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(result).get("items");
        assertThat(items).hasSize(1);
        JsonNode item = items.get(0);
        assertThat(item.get("scheduleKey").asText()).isEqualTo(succ);
        assertThat(item.get("version").asInt()).isEqualTo(2);
        assertThat(item.get("previousDepartureUtc").asText()).isEqualTo(iso(9, 40));
        assertThat(item.get("newDepartureUtc").asText()).isEqualTo(iso(10, 30));
        assertThat(item.get("shiftMinutes").asLong()).isEqualTo(50);
        assertThat(item.get("riskStatus").asText()).isEqualTo("NONE");

        // 计划时刻更新、风险解除、时隙生效
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan = read(detail);
        assertThat(plan.get("version").asInt()).isEqualTo(2);
        assertThat(plan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(plan.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(10, 30));
        assertThat(plan.get("occupancies").get(0).get("endUtc").asText()).isEqualTo(iso(11, 30));
        MvcResult risk = mvc.perform(get("/api/v1/plans/{key}/connection-risk", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(risk).get("riskStatus").asText()).isEqualTo("NONE");
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", secBC))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots).get(0).get("startUtc").asText()).isEqualTo(iso(10, 30));

        // 决策证据：RESCHEDULED 记录原/新时刻，RISK_CLEARED 记录解除
        JsonNode events = eventsOf(succ);
        assertThat(events).hasSize(4);
        assertThat(events.get(2).get("eventType").asText()).isEqualTo("RESCHEDULED");
        assertThat(events.get(2).get("previousSuccessorDepartureUtc").asText())
                .isEqualTo(iso(9, 40));
        assertThat(events.get(2).get("successorDepartureUtc").asText()).isEqualTo(iso(10, 30));
        assertThat(events.get(2).get("deficitMinutes").asLong()).isEqualTo(50);
        assertThat(events.get(3).get("eventType").asText()).isEqualTo("RISK_CLEARED");
    }

    @Test
    void guaranteeRescheduleAvoidsPublishedSlotConflicts() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", secAB, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secBC, iso(9, 40), iso(10, 40)));
        publishPlan(succ);
        register(pred, succ, 30);
        // 第三方占用后车平移后的目标时隙 10:50-11:20（与当前 09:40-10:40 不冲突）
        String thirdParty = key("SCH");
        createPlan(thirdParty, occ("G9", secBC, iso(10, 50), iso(11, 20)));
        publishPlan(thirdParty);
        String predV2 = key("SCH");
        createPlan(predV2, occ("G1", secAB, iso(9), iso(10)));
        reschedule(pred, predV2, 1, 1);

        // 最小保障平移 50 分钟（10:30-11:30）与第三方冲突，须继续顺延至 11:20
        MvcResult result = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(guaranteeBody(key("REQ"), succ)))
                .andExpect(status().isOk()).andReturn();
        JsonNode item = read(result).get("items").get(0);
        assertThat(item.get("newDepartureUtc").asText()).isEqualTo(iso(11, 20));
        assertThat(item.get("shiftMinutes").asLong()).isEqualTo(100);
    }

    @Test
    void guaranteeRescheduleRollsBackEntireBatchWhenAnyPlanInfeasible() throws Exception {
        // S1：可保障改签的风险后车
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred1 = key("SCH");
        String succ1 = key("SCH");
        createPlan(pred1, occ("G1", secAB, iso(8), iso(9)));
        publishPlan(pred1);
        createPlan(succ1, occ("G2", secBC, iso(9, 40), iso(10, 40)));
        publishPlan(succ1);
        register(pred1, succ1, 30);
        String pred1V2 = key("SCH");
        createPlan(pred1V2, occ("G1", secAB, iso(9), iso(10)));
        reschedule(pred1, pred1V2, 1, 1);

        // S2：保障需求超出运营日边界，找不到可行时间窗
        String secDE = section();
        String secEF = sectionFrom(destOf(secDE));
        String pred2 = key("SCH");
        String succ2 = key("SCH");
        createPlan(pred2, occ("G3", secDE, iso(21, 0), iso(22, 0)));
        publishPlan(pred2);
        createPlan(succ2, occ("G4", secEF, iso(22, 40), iso(23, 30)));
        publishPlan(succ2);
        register(pred2, succ2, 30);
        String pred2V2 = key("SCH");
        createPlan(pred2V2, occ("G3", secDE, iso(22, 0), iso(23, 0)));
        reschedule(pred2, pred2V2, 1, 1);

        String requestKey = key("REQ");
        MvcResult result = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(guaranteeBody(requestKey, succ1, succ2)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("NO_FEASIBLE_WINDOW");
        assertThat(error.get("details").get(0).get("scheduleKey").asText()).isEqualTo(succ2);

        // 整次回滚：S1/S2 时刻、版本与风险标记均不变，时隙仍归原计划
        assertPlanUnchanged(succ1, 1, iso(9, 40));
        assertPlanUnchanged(succ2, 1, iso(22, 40));
        MvcResult risk1 = mvc.perform(get("/api/v1/plans/{key}/connection-risk", succ1))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(risk1).get("riskStatus").asText()).isEqualTo("CONNECTION_AT_RISK");
        assertThat(eventsOf(succ1)).hasSize(2);
        assertThat(eventsOf(succ2)).hasSize(2);

        // 失败不占键：排除不可行的 S2 后同键重试成功
        MvcResult retry = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(guaranteeBody(requestKey, succ1)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(retry).get("items")).hasSize(1);
    }

    @Test
    void guaranteeRescheduleRejectsNotAtRiskAndUnknownPlans() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", secAB, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secBC, iso(10), iso(11)));
        publishPlan(succ);
        register(pred, succ, 30);

        // 保障充足、未处于风险状态 → 422
        MvcResult notAtRisk = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(guaranteeBody(key("REQ"), succ)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(notAtRisk).get("code").asText()).isEqualTo("CONNECTION_NOT_AT_RISK");

        // 计划不存在 → 404
        mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(guaranteeBody(key("REQ"), key("SCH"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void guaranteeRescheduleIdempotentReplay() throws Exception {
        String secAB = section();
        String secBC = sectionFrom(destOf(secAB));
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", secAB, iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", secBC, iso(9, 40), iso(10, 40)));
        publishPlan(succ);
        register(pred, succ, 30);
        String predV2 = key("SCH");
        createPlan(predV2, occ("G1", secAB, iso(9), iso(10)));
        reschedule(pred, predV2, 1, 1);

        String requestKey = key("REQ");
        String body = guaranteeBody(requestKey, succ);
        MvcResult first = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放首次结果，不重复平移
        MvcResult replay = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("version").asInt()).isEqualTo(2);
        // 同键异参 → 409
        mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(guaranteeBody(requestKey, succ, key("SCH"))))
                .andExpect(status().isConflict());
    }

    // ---------- 辅助 ----------

    private JsonNode eventsOf(String scheduleKey) throws Exception {
        MvcResult events = mvc.perform(get("/api/v1/plans/{key}/connection-events", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(events);
    }

    private void assertPlanUnchanged(String scheduleKey, int version, String startIso)
            throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan = read(detail);
        assertThat(plan.get("version").asInt()).isEqualTo(version);
        assertThat(plan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(plan.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(startIso);
    }

    private long register(String pred, String succ, int guaranteeMinutes) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, guaranteeMinutes, false)))
                .andExpect(status().isCreated()).andReturn();
        return read(result).get("id").asLong();
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        createPlanOnDate(scheduleKey, DAY, occupancies);
    }

    private void createPlanOnDate(String scheduleKey, LocalDate opDate, String... occupancies)
            throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + opDate + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
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

    private void reschedule(String oldKey, String newKey, int oldVersion, int newVersion)
            throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"newScheduleKey\":\""
                                + newKey + "\",\"expectedOldVersion\":" + oldVersion
                                + ",\"expectedNewVersion\":" + newVersion + "}"))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 生成唯一站点名（不含分隔符）。 */
    private static String station() {
        return "S" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 生成 "出发站-到达站" 区段 ID。 */
    private static String section() {
        return station() + "-" + station();
    }

    /** 生成以指定站为出发站的区段 ID。 */
    private static String sectionFrom(String originStation) {
        return originStation + "-" + station();
    }

    /** 取区段 ID 的到达站（最后一个分隔符之后）。 */
    private static String destOf(String sectionId) {
        return sectionId.substring(sectionId.lastIndexOf('-') + 1);
    }

    private static String iso(int hour) {
        return iso(hour, 0);
    }

    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String iso(int hour, int minute, int dayOffset) {
        return DAY.plusDays(dayOffset).atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String registerBody(String requestKey, String pred, String succ,
                                       int guaranteeMinutes, boolean crossMidnight) {
        return "{\"requestKey\":\"" + requestKey + "\",\"predecessorScheduleKey\":\"" + pred
                + "\",\"successorScheduleKey\":\"" + succ + "\",\"guaranteeMinutes\":"
                + guaranteeMinutes + ",\"crossMidnight\":" + crossMidnight + "}";
    }

    private static String guaranteeBody(String requestKey, String... scheduleKeys) {
        StringBuilder sb = new StringBuilder("{\"requestKey\":\"" + requestKey
                + "\",\"scheduleKeys\":[");
        for (int i = 0; i < scheduleKeys.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('\"').append(scheduleKeys[i]).append('\"');
        }
        return sb.append("]}").toString();
    }
}
