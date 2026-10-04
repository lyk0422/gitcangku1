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
 * 批量保障改签 API 测试：主流程、时隙/接续分钟数/运营日边界 422 整次回滚、
 * 状态与幂等边界（H2 内存库，时钟可控）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GuaranteeRescheduleApiTest {

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

    // ---------- 主流程 ----------

    @Test
    void batchRescheduleShiftsSuccessorAndRecordsDecision() throws Exception {
        Stations st = stations();
        String[] setup = setupAtRisk(st, 30);
        String succ = setup[1];
        String connKey = setup[2];

        MvcResult result = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), succ, 20)))
                .andExpect(status().isOk()).andReturn();
        JsonNode item = read(result).get("items").get(0);
        assertThat(item.get("scheduleKey").asText()).isEqualTo(succ);
        assertThat(item.get("oldDepartureUtc").asText()).isEqualTo(iso(11));
        assertThat(item.get("newDepartureUtc").asText()).isEqualTo(iso(11, 20));
        assertThat(item.get("shiftMinutes").asInt()).isEqualTo(20);
        assertThat(item.get("version").asInt()).isEqualTo(2);

        // 后车时刻整体平移、版本加一、仍为已发布
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan = read(detail);
        assertThat(plan.get("version").asInt()).isEqualTo(2);
        assertThat(plan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(plan.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(11, 20));
        assertThat(plan.get("occupancies").get(0).get("endUtc").asText())
                .isEqualTo(iso(12, 20));

        // 风险标记清除，诊断恢复满足
        MvcResult diagnosis = mvc.perform(get("/api/v1/plans/{key}/connection-diagnosis", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode diag = read(diagnosis);
        assertThat(diag.get("riskStatus").asText()).isEqualTo("NONE");
        assertThat(diag.get("evaluations").get(0).get("satisfied").asBoolean()).isTrue();

        // 生效时隙同步平移
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section(st.b(), st.c())))
                .andExpect(status().isOk()).andReturn();
        JsonNode slotList = read(slots);
        assertThat(slotList).hasSize(1);
        assertThat(slotList.get(0).get("startUtc").asText()).isEqualTo(iso(11, 20));

        // 决策历史固化：批次键、接续、前车版本、原新时刻与改签前差额
        MvcResult decisions = mvc.perform(get("/api/v1/plans/{key}/guarantee-decisions", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode decisionList = read(decisions);
        assertThat(decisionList).hasSize(1);
        JsonNode decision = decisionList.get(0);
        assertThat(decision.get("connectionKey").asText()).isEqualTo(connKey);
        assertThat(decision.get("successorScheduleKey").asText()).isEqualTo(succ);
        assertThat(decision.get("predecessorVersion").asInt()).isEqualTo(1);
        assertThat(decision.get("oldDepartureUtc").asText()).isEqualTo(iso(11));
        assertThat(decision.get("newDepartureUtc").asText()).isEqualTo(iso(11, 20));
        assertThat(decision.get("shiftMinutes").asInt()).isEqualTo(20);
        assertThat(decision.get("shortfallBeforeMinutes").asLong()).isEqualTo(15);
    }

    // ---------- 422 整次回滚 ----------

    @Test
    void batchRejectsSlotConflictAndRollsBack() throws Exception {
        Stations st = stations();
        String[] setup = setupAtRisk(st, 30);
        String succ = setup[1];
        // 第三方已发布计划占用同区段 12:00-13:00，延后 20 分钟后 11:20-12:20 与之重叠
        String third = key("SCH");
        createPlan(third, DAY, occ("G9", section(st.b(), st.c()), iso(12), iso(13)));
        publishPlan(third);

        MvcResult result = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), succ, 20)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        assertThat(error.get("details").get(0).get("conflictingScheduleKey").asText())
                .isEqualTo(third);

        // 回滚：时刻、版本、风险标记、时隙占用均不变，无决策记录
        assertSuccessorRolledBack(succ, st);
    }

    @Test
    void batchRejectsGuaranteeStillNotMetAndRollsBack() throws Exception {
        Stations st = stations();
        String[] setup = setupAtRisk(st, 30);
        String succ = setup[1];

        // 要求始发 11:15，仅延后 10 分钟到 11:10 仍不足 → 422 携带差额
        MvcResult result = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), succ, 10)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("GUARANTEE_NOT_MET");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("requiredDepartureUtc").asText()).isEqualTo(iso(11, 15));
        assertThat(detail.get("actualDepartureUtc").asText()).isEqualTo(iso(11, 10));
        assertThat(detail.get("shortfallMinutes").asLong()).isEqualTo(5);

        assertSuccessorRolledBack(succ, st);
    }

    @Test
    void batchIsAllOrNothingAcrossItems() throws Exception {
        Stations st = stations();
        // 同一前车的两个风险后车：B 区段 stB-stC，C 区段 stB-stD
        String pred = key("SCH");
        createPlan(pred, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10)));
        publishPlan(pred);
        String succB = key("SCH");
        createPlan(succB, DAY, occ("G2", section(st.b(), st.c()), iso(11), iso(12)));
        publishPlan(succB);
        String succC = key("SCH");
        createPlan(succC, DAY, occ("G3", section(st.b(), st.d()), iso(11), iso(12)));
        publishPlan(succC);
        registerConnection(key("CONN"), pred, succB, 30);
        registerConnection(key("CONN"), pred, succC, 30);
        String predNew = key("SCH");
        createPlan(predNew, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10, 45)));
        reschedule(pred, predNew);
        // 第三方占用 C 的区段 12:00-13:00，C 延后 20 分钟冲突
        String third = key("SCH");
        createPlan(third, DAY, occ("G9", section(st.b(), st.d()), iso(12), iso(13)));
        publishPlan(third);

        String body = "{\"requestKey\":\"" + key("REQ") + "\",\"items\":["
                + "{\"scheduleKey\":\"" + succB + "\",\"shiftMinutes\":20},"
                + "{\"scheduleKey\":\"" + succC + "\",\"shiftMinutes\":20}]}";
        mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());

        // 整次回滚：B 也未改签，仍处风险标记
        assertSuccessorRolledBack(succB, st, section(st.b(), st.c()));
        assertSuccessorRolledBack(succC, st, section(st.b(), st.d()));
    }

    @Test
    void batchRejectsShiftOutOfOperationDay() throws Exception {
        Stations st = stations();
        String pred = key("SCH");
        createPlan(pred, DAY, occ("G1", section(st.a(), st.b()), iso(21), iso(22, 30)));
        publishPlan(pred);
        String succ = key("SCH");
        createPlan(succ, DAY, occ("G2", section(st.b(), st.c()), iso(23), iso(23, 50)));
        publishPlan(succ);
        registerConnection(key("CONN"), pred, succ, 30);
        // 前车终到延至 22:50，要求始发 23:20，差额 20
        String predNew = key("SCH");
        createPlan(predNew, DAY, occ("G1", section(st.a(), st.b()), iso(21), iso(22, 50)));
        reschedule(pred, predNew);

        // 延后 20 分钟后占用 23:20-00:10 越出运营日 → 422
        MvcResult result = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), succ, 20)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("SHIFT_OUT_OF_OPERATION_DAY");

        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(23));
    }
    // ---------- 状态与参数边界 ----------

    @Test
    void batchRejectsStateViolations() throws Exception {
        Stations st = stations();
        // 未标记风险的已发布计划 → 409
        String notAtRisk = key("SCH");
        createPlan(notAtRisk, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(9)));
        publishPlan(notAtRisk);
        MvcResult notAtRiskResult = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), notAtRisk, 20)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(notAtRiskResult).get("code").asText()).isEqualTo("CONNECTION_NOT_AT_RISK");

        // 草稿计划 → 409
        String draft = key("SCH");
        createPlan(draft, DAY, occ("G2", section(st.a(), st.b()), iso(10), iso(11)));
        MvcResult draftResult = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), draft, 20)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(draftResult).get("code").asText()).isEqualTo("PLAN_STATE_CONFLICT");

        // 计划不存在 → 404
        mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), key("SCH"), 20)))
                .andExpect(status().isNotFound());

        // 已开始的计划不可保障改签 → 422（独立站点避免与本用例已发布计划冲突）
        String[] setup = setupAtRisk(stations(), 30);
        clock.setInstant(Instant.parse("2026-09-23T00:00:00Z"));
        MvcResult startedResult = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), setup[1], 20)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(startedResult).get("code").asText()).isEqualTo("PLAN_ALREADY_STARTED");
    }

    @Test
    void batchRejectsInvalidShiftMinutes() throws Exception {
        Stations st = stations();
        String[] setup = setupAtRisk(st, 30);
        String succ = setup[1];
        for (int shift : new int[]{0, -5, 1441}) {
            MvcResult result = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(batchBody(key("REQ"), succ, shift)))
                    .andExpect(status().isUnprocessableEntity()).andReturn();
            JsonNode error = read(result);
            assertThat(error.get("code").asText()).isEqualTo("INVALID_SHIFT_MINUTES");
            assertThat(error.get("details").get(0).get("actualMinutes").asInt()).isEqualTo(shift);
        }
        // 失败不占键也不产生副作用
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("version").asInt()).isEqualTo(1);
    }

    // ---------- 幂等 ----------

    @Test
    void batchIdempotentReplayAndParamConflict() throws Exception {
        Stations st = stations();
        String[] setup = setupAtRisk(st, 30);
        String succ = setup[1];

        String requestKey = key("REQ");
        String body = batchBody(requestKey, succ, 20);
        MvcResult first = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放返回首次结果，且不重复平移（版本保持 2）
        MvcResult replay = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("version").asInt()).isEqualTo(2);
        assertThat(read(detail).get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(11, 20));

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(requestKey, succ, 25)))
                .andExpect(status().isConflict());
    }

    @Test
    void batchFailureDoesNotConsumeRequestKey() throws Exception {
        Stations st = stations();
        String[] setup = setupAtRisk(st, 30);
        String succ = setup[1];

        String requestKey = key("REQ");
        // 首次延后 10 分钟保障仍不足 → 422，不占键
        mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(requestKey, succ, 10)))
                .andExpect(status().isUnprocessableEntity());
        // 同键改为延后 20 分钟 → 成功
        mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(requestKey, succ, 20)))
                .andExpect(status().isOk());
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(11, 20));
    }

    // ---------- 辅助 ----------

    private record Stations(String a, String b, String c, String d) {
    }

    private static Stations stations() {
        String rand = UUID.randomUUID().toString().substring(0, 8);
        return new Stations("SA" + rand, "SB" + rand, "SC" + rand, "SD" + rand);
    }

    private static String section(String from, String to) {
        return from + "-" + to;
    }

    /**
     * 构造风险后车场景：前车 08:00-10:00、后车 11:00-12:00 登记接续后，
     * 前车改签延至 10:45 终到，后车被标记 CONNECTION_AT_RISK（差额 15 分钟）。
     *
     * @return [新前车 scheduleKey, 后车 scheduleKey, connectionKey]
     */
    private String[] setupAtRisk(Stations st, int guarantee) throws Exception {
        String pred = key("SCH");
        createPlan(pred, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10)));
        publishPlan(pred);
        String succ = key("SCH");
        createPlan(succ, DAY, occ("G2", section(st.b(), st.c()), iso(11), iso(12)));
        publishPlan(succ);
        String connKey = key("CONN");
        registerConnection(connKey, pred, succ, guarantee);
        String predNew = key("SCH");
        createPlan(predNew, DAY, occ("G1", section(st.a(), st.b()), iso(8), iso(10, 45)));
        reschedule(pred, predNew);
        MvcResult diagnosis = mvc.perform(get("/api/v1/plans/{key}/connection-diagnosis", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(diagnosis).get("riskStatus").asText()).isEqualTo("CONNECTION_AT_RISK");
        return new String[]{predNew, succ, connKey};
    }

    /** 校验风险后车未被改签：版本 1、时刻不变、仍处风险、无决策记录。 */
    private void assertSuccessorRolledBack(String succ, Stations st) throws Exception {
        assertSuccessorRolledBack(succ, st, section(st.b(), st.c()));
    }

    private void assertSuccessorRolledBack(String succ, Stations st, String succSection)
            throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan = read(detail);
        assertThat(plan.get("version").asInt()).isEqualTo(1);
        assertThat(plan.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(11));
        MvcResult diagnosis = mvc.perform(get("/api/v1/plans/{key}/connection-diagnosis", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(diagnosis).get("riskStatus").asText()).isEqualTo("CONNECTION_AT_RISK");
        MvcResult decisions = mvc.perform(get("/api/v1/plans/{key}/guarantee-decisions", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(decisions)).isEmpty();
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

    private void reschedule(String oldKey, String newKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"newScheduleKey\":\""
                                + newKey + "\",\"expectedOldVersion\":1,\"expectedNewVersion\":1}"))
                .andExpect(status().isOk());
    }

    private void registerConnection(String connKey, String pred, String succ, int guarantee)
            throws Exception {
        mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"connectionKey\":\""
                                + connKey + "\",\"predecessorScheduleKey\":\"" + pred
                                + "\",\"successorScheduleKey\":\"" + succ
                                + "\",\"guaranteeMinutes\":" + guarantee
                                + ",\"crossMidnight\":false}"))
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

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String batchBody(String requestKey, String scheduleKey, int shiftMinutes) {
        return "{\"requestKey\":\"" + requestKey + "\",\"items\":[{\"scheduleKey\":\""
                + scheduleKey + "\",\"shiftMinutes\":" + shiftMinutes + "}]}";
    }
}