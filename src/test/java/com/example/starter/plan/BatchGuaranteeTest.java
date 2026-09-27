package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 批量保障改签测试（H2 内存库，可控时钟）：同时满足线路时隙与全部接续分钟数；
 * 任一计划找不到不冲突时间窗时整次 422，已发布计划时刻、风险标记与时隙占用均回滚；
 * 决策历史固化前车版本、原新时刻与保障差额。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchGuaranteeTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    /** 运营日当日 00:00（Asia/Shanghai），早于全部测试计划时刻。 */
    private static final Instant BEFORE_DAY = DAY.atStartOfDay(SH).toInstant();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUpClock() {
        when(clock.instant()).thenReturn(BEFORE_DAY);
    }

    // ---------- 主流程 ----------

    @Test
    void batchShiftsSuccessorAndRecordsDecision() throws Exception {
        String section1 = key("SEC");
        String section2 = key("SEC");
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", section1, "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", section2, "STA-X", "STA-B", iso(9, 30), iso(10, 30)));
        publishPlan(succ);
        register(pred, succ, "STA-X", 30);
        // 前车终到延至 09:50：后车缺口 50 分钟，最早可行始发 10:20
        String predNew = key("SCH");
        createPlan(predNew, occ("G1", section1, "STA-A", "STA-X", iso(8, 30), iso(9, 50)));
        reschedule(pred, predNew);
        assertThat(planDetail(succ).get("connectionRisk").asText())
                .isEqualTo("CONNECTION_AT_RISK");

        String requestKey = key("REQ");
        String body = batchBody(requestKey, succ);
        MvcResult result = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode decisions = read(result).get("decisions");
        assertThat(decisions).hasSize(1);
        JsonNode decision = decisions.get(0);
        assertThat(decision.get("batchKey").asText()).isEqualTo(requestKey);
        assertThat(decision.get("scheduleKey").asText()).isEqualTo(succ);
        assertThat(decision.get("newVersion").asInt()).isEqualTo(2);
        assertThat(decision.get("predecessorVersion").asInt()).isEqualTo(1);
        assertThat(decision.get("oldFirstStartUtc").asText()).isEqualTo(iso(9, 30));
        assertThat(decision.get("newFirstStartUtc").asText()).isEqualTo(iso(10, 20));
        assertThat(decision.get("shiftMinutes").asLong()).isEqualTo(50);
        assertThat(decision.get("deficitMinutes").asLong()).isEqualTo(50);

        // 后车时刻整体后移、版本加一、风险标记清除
        JsonNode succDetail = planDetail(succ);
        assertThat(succDetail.get("version").asInt()).isEqualTo(2);
        assertThat(succDetail.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(succDetail.get("connectionRisk").isNull()).isTrue();
        assertThat(succDetail.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(10, 20));
        assertThat(succDetail.get("occupancies").get(0).get("endUtc").asText())
                .isEqualTo(iso(11, 20));

        // 生效时隙切换到新时刻
        JsonNode slots = publishedSlots(section2);
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).get("startUtc").asText()).isEqualTo(iso(10, 20));

        // 决策历史可查询且与响应一致
        JsonNode history = decisionsOf(succ);
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).isEqualTo(decision);

        // 同键同参重放返回首次结果，不重复后移
        MvcResult replay = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(result));
        assertThat(planDetail(succ).get("version").asInt()).isEqualTo(2);
        assertThat(decisionsOf(succ)).hasSize(1);

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(requestKey, key("SCH"))))
                .andExpect(status().isConflict());
    }

    @Test
    void batchSkipsOccupiedSlotsOfThirdParty() throws Exception {
        String section1 = key("SEC");
        String section2 = key("SEC");
        String pred = key("SCH");
        String succ = key("SCH");
        String thirdParty = key("SCH");
        createPlan(pred, occ("G1", section1, "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        // 后车 09:30-10:10；第三方已占 10:20-11:00
        createPlan(succ, occ("G2", section2, "STA-X", "STA-B", iso(9, 30), iso(10, 10)));
        publishPlan(succ);
        createPlan(thirdParty, occ("G9", section2, "STA-C", "STA-D", iso(10, 20), iso(11, 0)));
        publishPlan(thirdParty);
        register(pred, succ, "STA-X", 30);
        String predNew = key("SCH");
        createPlan(predNew, occ("G1", section1, "STA-A", "STA-X", iso(8, 30), iso(9, 50)));
        reschedule(pred, predNew);

        // 最早可行始发 10:20 与第三方冲突，须跳过至 11:00
        MvcResult result = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), succ)))
                .andExpect(status().isOk()).andReturn();
        JsonNode decision = read(result).get("decisions").get(0);
        assertThat(decision.get("newFirstStartUtc").asText()).isEqualTo(iso(11, 0));
        assertThat(decision.get("shiftMinutes").asLong()).isEqualTo(90);

        // 区段上两个时隙相邻不重叠
        JsonNode slots = publishedSlots(section2);
        assertThat(slots).hasSize(2);
        assertThat(slots.get(0).get("startUtc").asText()).isEqualTo(iso(10, 20));
        assertThat(slots.get(1).get("startUtc").asText()).isEqualTo(iso(11, 0));
    }

    @Test
    void batchPropagatesRiskToNextSuccessor() throws Exception {
        String section1 = key("SEC");
        String section2 = key("SEC");
        String section3 = key("SEC");
        String pred = key("SCH");
        String middle = key("SCH");
        String last = key("SCH");
        createPlan(pred, occ("G1", section1, "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        createPlan(middle, occ("G2", section2, "STA-X", "STA-Y", iso(9, 30), iso(10, 30)));
        publishPlan(middle);
        createPlan(last, occ("G3", section3, "STA-Y", "STA-Z", iso(11, 0), iso(12, 0)));
        publishPlan(last);
        register(pred, middle, "STA-X", 30);
        register(middle, last, "STA-Y", 30);
        String predNew = key("SCH");
        createPlan(predNew, occ("G1", section1, "STA-A", "STA-X", iso(8, 30), iso(9, 50)));
        reschedule(pred, predNew);

        // 中间车后移至 10:20-11:20，其终到 11:20 使末车接续缺口 50 分钟
        mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), middle)))
                .andExpect(status().isOk());

        // 末车在同一事务链路内被标记风险并生成历史记录，但不被自动修改
        JsonNode lastDetail = planDetail(last);
        assertThat(lastDetail.get("connectionRisk").asText()).isEqualTo("CONNECTION_AT_RISK");
        assertThat(lastDetail.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(11, 0));
        JsonNode risks = risksOf(last);
        assertThat(risks).hasSize(1);
        JsonNode risk = risks.get(0);
        assertThat(risk.get("predecessorKey").asText()).isEqualTo(middle);
        assertThat(risk.get("predecessorVersion").asInt()).isEqualTo(2);
        assertThat(risk.get("requiredMinutes").asLong()).isEqualTo(30);
        assertThat(risk.get("actualMinutes").asLong()).isEqualTo(-20);
        assertThat(risk.get("deficitMinutes").asLong()).isEqualTo(50);
        assertThat(risk.get("windowStartUtc").asText()).isEqualTo(iso(11, 50));
    }

    // ---------- 整次 422 与回滚 ----------

    @Test
    void batchRollsBackEntirelyWhenAnyPlanInfeasible() throws Exception {
        String section1 = key("SEC");
        String section2 = key("SEC");
        String section3 = key("SEC");
        String pred = key("SCH");
        String feasible = key("SCH");
        String infeasible = key("SCH");
        createPlan(pred, occ("G1", section1, "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        createPlan(feasible, occ("G2", section2, "STA-X", "STA-B", iso(9, 30), iso(10, 30)));
        publishPlan(feasible);
        // 后车 09:45-23:45（14 小时）：要求始发 10:20 时终到越出运营日
        createPlan(infeasible, occ("G3", section3, "STA-X", "STA-C", iso(9, 45), iso(23, 45)));
        publishPlan(infeasible);
        register(pred, feasible, "STA-X", 30);
        register(pred, infeasible, "STA-X", 30);
        String predNew = key("SCH");
        createPlan(predNew, occ("G1", section1, "STA-A", "STA-X", iso(8, 30), iso(9, 50)));
        reschedule(pred, predNew);
        assertThat(planDetail(feasible).get("connectionRisk").asText())
                .isEqualTo("CONNECTION_AT_RISK");
        assertThat(planDetail(infeasible).get("connectionRisk").asText())
                .isEqualTo("CONNECTION_AT_RISK");

        // 任一计划找不到不冲突时间窗 → 整次 422
        MvcResult result = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), feasible, infeasible)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("BATCH_NO_FEASIBLE_WINDOW");
        assertThat(error.get("details").get(0).get("scheduleKey").asText()).isEqualTo(infeasible);

        // 回滚：两车时刻、版本、风险标记与时隙占用均不变，无决策记录
        JsonNode feasibleDetail = planDetail(feasible);
        assertThat(feasibleDetail.get("version").asInt()).isEqualTo(1);
        assertThat(feasibleDetail.get("connectionRisk").asText())
                .isEqualTo("CONNECTION_AT_RISK");
        assertThat(feasibleDetail.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(9, 30));
        JsonNode infeasibleDetail = planDetail(infeasible);
        assertThat(infeasibleDetail.get("version").asInt()).isEqualTo(1);
        assertThat(infeasibleDetail.get("connectionRisk").asText())
                .isEqualTo("CONNECTION_AT_RISK");
        assertThat(decisionsOf(feasible)).isEmpty();
        assertThat(decisionsOf(infeasible)).isEmpty();
        JsonNode slots = publishedSlots(section2);
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).get("startUtc").asText()).isEqualTo(iso(9, 30));

        // 失败不占键：剔除不可行计划后同批可成功
        mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), feasible)))
                .andExpect(status().isOk());
        assertThat(planDetail(feasible).get("connectionRisk").isNull()).isTrue();
    }

    // ---------- 候选校验 422/409/404/400 ----------

    @Test
    void batchRejectsNotAtRiskDraftAndMissingPlans() throws Exception {
        String published = key("SCH");
        createPlan(published, occ("G1", key("SEC"), "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(published);
        // 已发布但无接续风险 → 422
        MvcResult notAtRisk = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), published)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(notAtRisk).get("code").asText()).isEqualTo("PLAN_NOT_AT_RISK");

        // 草稿 → 409
        String draft = key("SCH");
        createPlan(draft, occ("G2", key("SEC"), "STA-A", "STA-X", iso(8), iso(9)));
        mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), draft)))
                .andExpect(status().isConflict());

        // 不存在 → 404
        mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), key("SCH"))))
                .andExpect(status().isNotFound());

        // 重复计划键 → 400
        mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), published, published)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void batchRejectsAlreadyStartedPlan() throws Exception {
        String section1 = key("SEC");
        String section2 = key("SEC");
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", section1, "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", section2, "STA-X", "STA-B", iso(9, 30), iso(10, 30)));
        publishPlan(succ);
        register(pred, succ, "STA-X", 30);
        String predNew = key("SCH");
        createPlan(predNew, occ("G1", section1, "STA-A", "STA-X", iso(8, 30), iso(9, 50)));
        reschedule(pred, predNew);
        assertThat(planDetail(succ).get("connectionRisk").asText())
                .isEqualTo("CONNECTION_AT_RISK");

        // 时钟推进到后车始发之后：已开始计划不可作为候选改签 → 422
        when(clock.instant()).thenReturn(DAY.atTime(10, 0).atZone(SH).toInstant());
        MvcResult result = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), succ)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("PLAN_ALREADY_STARTED");
        assertThat(error.get("details").get(0).get("scheduleKey").asText()).isEqualTo(succ);

        // 时刻与风险标记不变
        JsonNode detail = planDetail(succ);
        assertThat(detail.get("version").asInt()).isEqualTo(1);
        assertThat(detail.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(9, 30));
        assertThat(detail.get("connectionRisk").asText()).isEqualTo("CONNECTION_AT_RISK");
    }

    // ---------- 辅助 ----------

    private JsonNode planDetail(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private JsonNode risksOf(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}/connection-risks", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private JsonNode decisionsOf(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}/guarantee-decisions", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private JsonNode publishedSlots(String section) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private void register(String pred, String succ, String station, int guarantee)
            throws Exception {
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"predecessorKey\":\""
                                + pred + "\",\"successorKey\":\"" + succ
                                + "\",\"transferStation\":\"" + station
                                + "\",\"guaranteeMinutes\":" + guarantee + "}"))
                .andExpect(status().isCreated());
    }

    private void createPlan(String scheduleKey, String occupancy) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + occupancy + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
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

    private static String occ(String train, String section, String from, String to,
                              String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"fromStation\":\"" + from + "\",\"toStation\":\"" + to
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String batchBody(String requestKey, String... scheduleKeys) {
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

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }
}
