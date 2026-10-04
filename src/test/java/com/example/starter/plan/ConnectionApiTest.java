package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 接续登记/取消 API 主流程、422 失败分支、改签后风险判定与历史不可变的端到端测试（H2 内存库）。
 * 各用例使用唯一 scheduleKey/requestKey/站点编码，共享库内互不干扰；时钟可控。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConnectionApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    private static final Instant T0 = Instant.parse("2026-09-21T00:00:00Z");

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
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
        clock.setInstant(T0);
    }

    // ---------- 主流程与查询 ----------

    @Test
    void registerMainFlowAndQueries() throws Exception {
        Stations st = stations();
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10)));
        publishPlan(pred);
        createPlan(succ, DAY, occ("G2", section(st.b(), st.c()), iso(11), iso(12)));
        publishPlan(succ);
        String connKey = key("CONN");

        MvcResult created = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), connKey, pred, succ, 30, false)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("connectionKey").asText()).isEqualTo(connKey);
        assertThat(body.get("predecessorScheduleKey").asText()).isEqualTo(pred);
        assertThat(body.get("successorScheduleKey").asText()).isEqualTo(succ);
        assertThat(body.get("guaranteeMinutes").asInt()).isEqualTo(30);
        assertThat(body.get("crossMidnight").asBoolean()).isFalse();
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("registeredPredecessorVersion").asInt()).isEqualTo(1);
        assertThat(body.get("requiredDepartureUtc").asText()).isEqualTo(iso(10, 30));
        assertThat(body.get("actualDepartureUtc").asText()).isEqualTo(iso(11));
        assertThat(body.get("slackMinutes").asLong()).isEqualTo(30);

        // 明细查询与登记响应一致
        MvcResult detail = mvc.perform(get("/api/v1/connections/{key}", connKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);

        // 双侧列表查询角色正确
        MvcResult succList = mvc.perform(get("/api/v1/plans/{key}/connections", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode succItems = read(succList);
        assertThat(succItems).hasSize(1);
        assertThat(succItems.get(0).get("role").asText()).isEqualTo("SUCCESSOR");
        assertThat(succItems.get(0).get("connection").get("connectionKey").asText())
                .isEqualTo(connKey);
        MvcResult predList = mvc.perform(get("/api/v1/plans/{key}/connections", pred))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(predList).get(0).get("role").asText()).isEqualTo("PREDECESSOR");

        // 诊断：无风险，接续满足
        MvcResult diagnosis = mvc.perform(get("/api/v1/plans/{key}/connection-diagnosis", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode diag = read(diagnosis);
        assertThat(diag.get("riskStatus").asText()).isEqualTo("NONE");
        assertThat(diag.get("evaluations")).hasSize(1);
        JsonNode eval = diag.get("evaluations").get(0);
        assertThat(eval.get("satisfied").asBoolean()).isTrue();
        assertThat(eval.get("slackMinutes").asLong()).isEqualTo(30);
        assertThat(eval.get("otherScheduleKey").asText()).isEqualTo(pred);
    }

    // ---------- 422 校验分支 ----------

    @Test
    void registerRejectsInvalidGuaranteeMinutes() throws Exception {
        Stations st = stations();
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10)));
        publishPlan(pred);
        createPlan(succ, DAY, occ("G2", section(st.b(), st.c()), iso(11), iso(12)));
        publishPlan(succ);

        MvcResult negative = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("CONN"), pred, succ, -1, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(negative);
        assertThat(error.get("code").asText()).isEqualTo("INVALID_GUARANTEE_MINUTES");
        assertThat(error.get("details").get(0).get("actualMinutes").asInt()).isEqualTo(-1);
        assertThat(error.get("details").get(0).get("maxMinutes").asInt()).isEqualTo(1440);

        mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("CONN"), pred, succ, 1441, false)))
                .andExpect(status().isUnprocessableEntity());

        // 边界值 0 与 1440 合法（1440 时保障不足另算，此处用足够间隔验证 0）
        mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("CONN"), pred, succ, 0, false)))
                .andExpect(status().isCreated());
    }

    @Test
    void registerRejectsPlanStateProblems() throws Exception {
        Stations st = stations();
        String cancelled = key("SCH");
        createPlan(cancelled, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10)));
        publishPlan(cancelled);
        cancelPlan(cancelled);
        String succ = key("SCH");
        createPlan(succ, DAY, occ("G2", section(st.b(), st.c()), iso(11), iso(12)));
        publishPlan(succ);

        // 前车已取消 → 422
        MvcResult cancelledResult = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("CONN"), cancelled, succ, 30, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(cancelledResult).get("code").asText()).isEqualTo("PLAN_CANCELLED");

        // 后车已取消 → 422
        String pred = key("SCH");
        createPlan(pred, DAY, occ("G3", section(st.a(), st.b()), iso(8), iso(10)));
        publishPlan(pred);
        String cancelledSucc = key("SCH");
        createPlan(cancelledSucc, DAY, occ("G4", section(st.b(), st.c()), iso(13), iso(14)));
        publishPlan(cancelledSucc);
        cancelPlan(cancelledSucc);
        mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("CONN"), pred, cancelledSucc, 30,
                                false)))
                .andExpect(status().isUnprocessableEntity());

        // 草稿未发布 → 422
        String draft = key("SCH");
        createPlan(draft, DAY, occ("G5", section(st.b(), st.c()), iso(15), iso(16)));
        MvcResult draftResult = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("CONN"), pred, draft, 30, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(draftResult).get("code").asText()).isEqualTo("PLAN_NOT_PUBLISHED");

        // 计划不存在 → 404
        mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("CONN"), pred, key("SCH"), 30,
                                false)))
                .andExpect(status().isNotFound());

        // 前后车同一计划 → 400
        mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("CONN"), pred, pred, 30, false)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerRejectsStationDiscontinuity() throws Exception {
        Stations st = stations();
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10)));
        publishPlan(pred);
        // 后车始发站与前车终到站不一致
        createPlan(succ, DAY, occ("G2", section(st.c(), st.a()), iso(11), iso(12)));
        publishPlan(succ);

        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("CONN"), pred, succ, 30, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("STATION_NOT_CONTINUOUS");
        assertThat(error.get("details").get(0).get("arrivalStation").asText()).isEqualTo(st.b());
        assertThat(error.get("details").get(0).get("departureStation").asText()).isEqualTo(st.c());
    }

    @Test
    void registerRequiresSameDayOrExplicitCrossMidnight() throws Exception {
        Stations st = stations();
        String pred = key("SCH");
        createPlan(pred, DAY, occ("G1", section(st.a(), st.b()), iso(22), iso(23)));
        publishPlan(pred);
        // 后车次日 01:00 始发：未声明跨零点 → 422
        String nextDay = key("SCH");
        createPlan(nextDay, DAY.plusDays(1),
                occ("G2", section(st.b(), st.c()), iso(1, 0, 1), iso(2, 0, 1)));
        publishPlan(nextDay);
        MvcResult rejected = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("CONN"), pred, nextDay, 30, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("OP_DATE_MISMATCH");
        assertThat(error.get("details").get(0).get("departureDay").asText())
                .isEqualTo(DAY.plusDays(1).toString());

        // 明确跨零点 → 201
        mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("CONN"), pred, nextDay, 30, true)))
                .andExpect(status().isCreated());

        // 跨两天即使声明跨零点也 → 422
        String twoDaysLater = key("SCH");
        createPlan(twoDaysLater, DAY.plusDays(2),
                occ("G3", section(st.b(), st.c()), iso(1, 0, 2), iso(2, 0, 2)));
        publishPlan(twoDaysLater);
        mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("CONN"), pred, twoDaysLater, 30,
                                true)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void registerRejectsGuaranteeNotMet() throws Exception {
        Stations st = stations();
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10)));
        publishPlan(pred);
        createPlan(succ, DAY, occ("G2", section(st.b(), st.c()), iso(11), iso(12)));
        publishPlan(succ);

        // 间隔 60 分钟，要求 90 分钟 → 差额 30
        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("CONN"), pred, succ, 90, false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("GUARANTEE_NOT_MET");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("requiredDepartureUtc").asText()).isEqualTo(iso(11, 30));
        assertThat(detail.get("actualDepartureUtc").asText()).isEqualTo(iso(11));
        assertThat(detail.get("shortfallMinutes").asLong()).isEqualTo(30);
    }
    // ---------- 幂等与并发登记 ----------

    @Test
    void registerIdempotentReplayAndParamConflict() throws Exception {
        Stations st = stations();
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10)));
        publishPlan(pred);
        createPlan(succ, DAY, occ("G2", section(st.b(), st.c()), iso(11), iso(12)));
        publishPlan(succ);

        String requestKey = key("REQ");
        String connKey = key("CONN");
        String body = registerBody(requestKey, connKey, pred, succ, 30, false);
        MvcResult first = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放返回首次结果
        MvcResult replay = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, key("CONN"), pred, succ, 45, false)))
                .andExpect(status().isConflict());

        // 失败不占键：独立计划对，先用保障不足的参数失败，再用同键合法参数成功
        String pred2 = key("SCH");
        String succ2 = key("SCH");
        createPlan(pred2, DAY, occ("G3", section(st.a(), st.b()), iso(12), iso(14)));
        publishPlan(pred2);
        createPlan(succ2, DAY, occ("G4", section(st.b(), st.c()), iso(15), iso(16)));
        publishPlan(succ2);
        String failKey = key("REQ");
        mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(failKey, key("CONN"), pred2, succ2, 90, false)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(failKey, key("CONN"), pred2, succ2, 30, false)))
                .andExpect(status().isCreated());
    }

    @Test
    void registerRejectsDuplicateActivePair() throws Exception {
        Stations st = stations();
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10)));
        publishPlan(pred);
        createPlan(succ, DAY, occ("G2", section(st.b(), st.c()), iso(11), iso(12)));
        publishPlan(succ);
        registerConnection(key("CONN"), pred, succ, 30, false);

        // 同一前后车组合重复登记（不同 connectionKey）→ 409
        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("CONN"), pred, succ, 30, false)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("CONNECTION_EXISTS");
    }

    // ---------- 取消 ----------

    @Test
    void cancelConnectionKeepsHistoryAndClearsEvaluation() throws Exception {
        Stations st = stations();
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10)));
        publishPlan(pred);
        createPlan(succ, DAY, occ("G2", section(st.b(), st.c()), iso(11), iso(12)));
        publishPlan(succ);
        String connKey = key("CONN");
        registerConnection(connKey, pred, succ, 30, false);

        String cancelKey = key("REQ");
        MvcResult cancelled = mvc.perform(post("/api/v1/connections/{key}/cancel", connKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(cancelKey)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(cancelled);
        assertThat(body.get("status").asText()).isEqualTo("CANCELLED");
        // 已取消接续不再评估，评估字段为 null
        assertThat(body.get("requiredDepartureUtc").isNull()).isTrue();
        assertThat(body.get("actualDepartureUtc").isNull()).isTrue();
        assertThat(body.get("slackMinutes").isNull()).isTrue();

        // 同键重放取消返回首次结果
        MvcResult replay = mvc.perform(post("/api/v1/connections/{key}/cancel", connKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(cancelKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(body);

        // 重复取消（新幂等键）→ 409
        mvc.perform(post("/api/v1/connections/{key}/cancel", connKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());

        // 历史保留：列表仍可见该接续，状态 CANCELLED
        MvcResult list = mvc.perform(get("/api/v1/plans/{key}/connections", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(list);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("connection").get("status").asText())
                .isEqualTo("CANCELLED");

        // 取消不存在的接续 → 404
        mvc.perform(post("/api/v1/connections/{key}/cancel", key("CONN"))
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound());
    }
    // ---------- 改签触发风险判定 ----------

    @Test
    void predecessorRescheduleMarksRiskAndKeepsSuccessorUntouched() throws Exception {
        Stations st = stations();
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10)));
        publishPlan(pred);
        createPlan(succ, DAY, occ("G2", section(st.b(), st.c()), iso(11), iso(12)));
        publishPlan(succ);
        String connKey = key("CONN");
        registerConnection(connKey, pred, succ, 30, false);

        // 前车改签延后：终到 10:00 → 10:45，接续仅剩 15 分钟 < 保障 30 分钟
        String predNew = key("SCH");
        createPlan(predNew, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10, 45)));
        reschedule(pred, predNew);

        // 后车被标记 CONNECTION_AT_RISK，但时刻不被自动修改
        MvcResult diagnosis = mvc.perform(get("/api/v1/plans/{key}/connection-diagnosis", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode diag = read(diagnosis);
        assertThat(diag.get("riskStatus").asText()).isEqualTo("CONNECTION_AT_RISK");
        assertThat(diag.get("evaluations").get(0).get("satisfied").asBoolean()).isFalse();
        assertThat(diag.get("evaluations").get(0).get("slackMinutes").asLong()).isEqualTo(-15);

        MvcResult succDetail = mvc.perform(get("/api/v1/plans/{key}", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode succBody = read(succDetail);
        assertThat(succBody.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(succBody.get("version").asInt()).isEqualTo(1);
        assertThat(succBody.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(11));

        // 风险历史固化：前车版本、原新时刻、保障差额与可选改签时间窗
        MvcResult risks = mvc.perform(get("/api/v1/plans/{key}/connection-risks", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode riskList = read(risks);
        assertThat(riskList).hasSize(1);
        JsonNode risk = riskList.get(0);
        assertThat(risk.get("connectionKey").asText()).isEqualTo(connKey);
        assertThat(risk.get("predecessorScheduleKey").asText()).isEqualTo(predNew);
        assertThat(risk.get("successorScheduleKey").asText()).isEqualTo(succ);
        assertThat(risk.get("predecessorVersion").asInt()).isEqualTo(1);
        assertThat(risk.get("oldArrivalUtc").asText()).isEqualTo(iso(10));
        assertThat(risk.get("newArrivalUtc").asText()).isEqualTo(iso(10, 45));
        assertThat(risk.get("oldDepartureUtc").asText()).isEqualTo(iso(11));
        assertThat(risk.get("newDepartureUtc").asText()).isEqualTo(iso(11));
        assertThat(risk.get("guaranteeMinutes").asInt()).isEqualTo(30);
        assertThat(risk.get("actualMinutes").asLong()).isEqualTo(15);
        assertThat(risk.get("shortfallMinutes").asLong()).isEqualTo(15);
        assertThat(risk.get("windowStartUtc").asText()).isEqualTo(iso(11, 15));
        assertThat(risk.get("windowEndUtc").asText()).isEqualTo(iso(0, 0, 1));
        assertThat(risk.get("marked").asBoolean()).isTrue();

        // 接续指向前车新计划
        MvcResult conn = mvc.perform(get("/api/v1/connections/{key}", connKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(conn).get("predecessorScheduleKey").asText()).isEqualTo(predNew);
    }

    @Test
    void riskHistoryImmutableAfterCancelAndFurtherReschedule() throws Exception {
        Stations st = stations();
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10)));
        publishPlan(pred);
        createPlan(succ, DAY, occ("G2", section(st.b(), st.c()), iso(11), iso(12)));
        publishPlan(succ);
        registerConnection(key("CONN"), pred, succ, 30, false);
        String pred2 = key("SCH");
        createPlan(pred2, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10, 45)));
        reschedule(pred, pred2);

        MvcResult risksBefore = mvc.perform(get("/api/v1/plans/{key}/connection-risks", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode snapshot = read(risksBefore);
        assertThat(snapshot).hasSize(1);

        // 取消接续：历史风险记录不改写，风险标记清除
        String connKey = read(mvc.perform(get("/api/v1/plans/{key}/connections", succ))
                .andExpect(status().isOk()).andReturn())
                .get(0).get("connection").get("connectionKey").asText();
        mvc.perform(post("/api/v1/connections/{key}/cancel", connKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        MvcResult risksAfterCancel = mvc.perform(
                        get("/api/v1/plans/{key}/connection-risks", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(risksAfterCancel)).isEqualTo(snapshot);
        MvcResult diagAfterCancel = mvc.perform(
                        get("/api/v1/plans/{key}/connection-diagnosis", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(diagAfterCancel).get("riskStatus").asText()).isEqualTo("NONE");

        // 重新登记（保障 15 分钟，恰好满足）后再次改签延后：新风险追加，旧记录原样保留
        String connKey2 = key("CONN");
        registerConnection(connKey2, pred2, succ, 15, false);
        String pred3 = key("SCH");
        createPlan(pred3, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(11, 5)));
        reschedule(pred2, pred3);

        MvcResult risksFinal = mvc.perform(get("/api/v1/plans/{key}/connection-risks", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode finalList = read(risksFinal);
        assertThat(finalList).hasSize(2);
        assertThat(finalList.get(0)).isEqualTo(snapshot.get(0));
        JsonNode second = finalList.get(1);
        assertThat(second.get("connectionKey").asText()).isEqualTo(connKey2);
        assertThat(second.get("actualMinutes").asLong()).isEqualTo(-5);
        assertThat(second.get("shortfallMinutes").asLong()).isEqualTo(20);
        assertThat(second.get("marked").asBoolean()).isTrue();
    }

    @Test
    void startedPlanIsNotMarkedAsRescheduleCandidate() throws Exception {
        Stations st = stations();
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10)));
        publishPlan(pred);
        createPlan(succ, DAY, occ("G2", section(st.b(), st.c()), iso(11), iso(12)));
        publishPlan(succ);
        registerConnection(key("CONN"), pred, succ, 30, false);

        // 时钟拨到后车始发之后：计划已开始
        clock.setInstant(Instant.parse("2026-09-23T00:00:00Z"));
        String predNew = key("SCH");
        createPlan(predNew, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10, 45)));
        reschedule(pred, predNew);

        // 风险判定仍固化历史，但不自动标记为候选改签
        MvcResult risks = mvc.perform(get("/api/v1/plans/{key}/connection-risks", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode riskList = read(risks);
        assertThat(riskList).hasSize(1);
        assertThat(riskList.get(0).get("marked").asBoolean()).isFalse();
        assertThat(riskList.get(0).get("shortfallMinutes").asLong()).isEqualTo(15);
        MvcResult diagnosis = mvc.perform(get("/api/v1/plans/{key}/connection-diagnosis", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(diagnosis).get("riskStatus").asText()).isEqualTo("NONE");
    }

    @Test
    void rescheduleBackToSatisfiedClearsRiskMark() throws Exception {
        Stations st = stations();
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10)));
        publishPlan(pred);
        createPlan(succ, DAY, occ("G2", section(st.b(), st.c()), iso(11), iso(12)));
        publishPlan(succ);
        registerConnection(key("CONN"), pred, succ, 30, false);
        String pred2 = key("SCH");
        createPlan(pred2, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10, 45)));
        reschedule(pred, pred2);
        MvcResult atRisk = mvc.perform(get("/api/v1/plans/{key}/connection-diagnosis", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(atRisk).get("riskStatus").asText()).isEqualTo("CONNECTION_AT_RISK");

        // 前车再次改签提前：终到 10:15，保障恢复满足 → 标记清除，历史不新增
        String pred3 = key("SCH");
        createPlan(pred3, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10, 15)));
        reschedule(pred2, pred3);
        MvcResult diagnosis = mvc.perform(get("/api/v1/plans/{key}/connection-diagnosis", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(diagnosis).get("riskStatus").asText()).isEqualTo("NONE");
        MvcResult risks = mvc.perform(get("/api/v1/plans/{key}/connection-risks", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(risks)).hasSize(1);
    }

    @Test
    void queriesReturn404ForMissingPlanOrConnection() throws Exception {
        String missing = key("SCH");
        mvc.perform(get("/api/v1/plans/{key}/connections", missing))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/connection-risks", missing))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/connection-diagnosis", missing))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/guarantee-decisions", missing))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/connections/{key}", key("CONN")))
                .andExpect(status().isNotFound());
    }

    // ---------- 辅助 ----------

    private record Stations(String a, String b, String c) {
    }

    private static Stations stations() {
        String rand = UUID.randomUUID().toString().substring(0, 8);
        return new Stations("SA" + rand, "SB" + rand, "SC" + rand);
    }

    private static String section(String from, String to) {
        return from + "-" + to;
    }

    private void createPlan(String scheduleKey, LocalDate opDate, String... occupancies)
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

    private void reschedule(String oldKey, String newKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"newScheduleKey\":\""
                                + newKey + "\",\"expectedOldVersion\":1,\"expectedNewVersion\":1}"))
                .andExpect(status().isOk());
    }

    private void registerConnection(String connKey, String pred, String succ, int guarantee,
                                    boolean crossMidnight) throws Exception {
        mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), connKey, pred, succ, guarantee,
                                crossMidnight)))
                .andExpect(status().isCreated());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    /** 运营日当日 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour) {
        return iso(hour, 0);
    }

    /** 运营日加 dayOffset 天后 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
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

    private static String registerBody(String requestKey, String connKey, String pred, String succ,
                                       int guarantee, boolean crossMidnight) {
        return "{\"requestKey\":\"" + requestKey + "\",\"connectionKey\":\"" + connKey
                + "\",\"predecessorScheduleKey\":\"" + pred + "\",\"successorScheduleKey\":\""
                + succ + "\",\"guaranteeMinutes\":" + guarantee
                + ",\"crossMidnight\":" + crossMidnight + "}";
    }
}