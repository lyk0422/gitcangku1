package com.example.starter.plan;

import static com.example.starter.plan.ConnectionApiTest.actionBody;
import static com.example.starter.plan.ConnectionApiTest.iso;
import static com.example.starter.plan.ConnectionApiTest.key;
import static com.example.starter.plan.ConnectionApiTest.occ;
import static com.example.starter.plan.ConnectionApiTest.registerBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 批量保障改签端到端测试：成功平移、时隙冲突避让、整次 422 回滚、
 * 状态边界（未风险/已开始）与幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchGuaranteeRescheduleTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.setInstant(Instant.parse("2026-09-21T00:00:00Z"));
    }

    @Test
    void batchRescheduleShiftsToWindowStartAndClearsRisk() throws Exception {
        String sectionA = key("SEC");
        String sectionB = key("SEC");
        String pred = publishedPlan(occ("G1", sectionA, iso(8, 0), iso(10, 0)));
        String succ = publishedPlan(occ("G2", sectionB, iso(10, 45), iso(12, 0)));
        long connectionId = register(pred, succ, 30);
        delayPredecessor(pred, sectionA, iso(8, 0), iso(10, 30));
        assertThat(diagnostics(succ).get("connectionRisk").asText()).isEqualTo("CONNECTION_AT_RISK");

        MvcResult result = mvc.perform(post("/api/v1/connections/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), succ, 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode item = read(result).get("items").get(0);
        assertThat(item.get("scheduleKey").asText()).isEqualTo(succ);
        assertThat(item.get("version").asInt()).isEqualTo(2);
        assertThat(item.get("oldDepartureUtc").asText()).isEqualTo(iso(10, 45));
        assertThat(item.get("newDepartureUtc").asText()).isEqualTo(iso(11, 0));
        assertThat(item.get("shiftMinutes").asLong()).isEqualTo(15);
        assertThat(item.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(11, 0));
        assertThat(item.get("occupancies").get(0).get("endUtc").asText()).isEqualTo(iso(12, 15));

        // 风险标记清除、时刻生效、时隙占用同步
        JsonNode diag = diagnostics(succ);
        assertThat(diag.get("connectionRisk").asText()).isEqualTo("NONE");
        assertThat(diag.get("openRiskWindows")).isEmpty();
        JsonNode plan = plan(succ);
        assertThat(plan.get("version").asInt()).isEqualTo(2);
        assertThat(plan.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(11, 0));
        JsonNode slots = publishedSlots(sectionB);
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).get("startUtc").asText()).isEqualTo(iso(11, 0));
        assertThat(slots.get(0).get("endUtc").asText()).isEqualTo(iso(12, 15));

        // 改签决策固化：前车版本、原新始发时刻、保障差额（改签后实际 30 分钟）
        JsonNode events = history(connectionId);
        assertThat(events).hasSize(3);
        JsonNode decided = events.get(2);
        assertThat(decided.get("eventType").asText()).isEqualTo("RESCHEDULE_DECIDED");
        assertThat(decided.get("predecessorVersion").asInt()).isEqualTo(1);
        assertThat(decided.get("oldTimeUtc").asText()).isEqualTo(iso(10, 45));
        assertThat(decided.get("newTimeUtc").asText()).isEqualTo(iso(11, 0));
        assertThat(decided.get("requiredMinutes").asInt()).isEqualTo(30);
        assertThat(decided.get("actualMinutes").asInt()).isEqualTo(30);
        assertThat(decided.get("deficitMinutes").asInt()).isEqualTo(0);
    }

    @Test
    void batchPushesPastConflictingPublishedSlot() throws Exception {
        String sectionA = key("SEC");
        String sectionB = key("SEC");
        String sectionC = key("SEC");
        String pred = publishedPlan(occ("G1", sectionA, iso(8, 0), iso(10, 0)));
        // 后车两段占用：SEC-B 10:45-11:00、SEC-C 11:00-12:00
        String succ = publishedPlan(
                occ("G2", sectionB, iso(10, 45), iso(11, 0)),
                occ("G2", sectionC, iso(11, 0), iso(12, 0)));
        // 第三方已发布 SEC-B 11:00-11:30，与后车当前占用相邻不冲突
        publishedPlan(occ("G9", sectionB, iso(11, 0), iso(11, 30)));
        register(pred, succ, 30);
        delayPredecessor(pred, sectionA, iso(8, 0), iso(10, 30));

        // 理想平移 15 分钟会与第三方时隙冲突，须推到 11:30（平移 45 分钟）
        MvcResult result = mvc.perform(post("/api/v1/connections/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), succ, 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode item = read(result).get("items").get(0);
        assertThat(item.get("newDepartureUtc").asText()).isEqualTo(iso(11, 30));
        assertThat(item.get("shiftMinutes").asLong()).isEqualTo(45);
        assertThat(item.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(11, 30));
        assertThat(item.get("occupancies").get(0).get("endUtc").asText()).isEqualTo(iso(11, 45));
        assertThat(item.get("occupancies").get(1).get("startUtc").asText()).isEqualTo(iso(11, 45));
        assertThat(item.get("occupancies").get(1).get("endUtc").asText()).isEqualTo(iso(12, 45));

        // SEC-B 上两个计划的时隙相邻不重叠
        JsonNode slots = publishedSlots(sectionB);
        assertThat(slots).hasSize(2);
        assertThat(diagnostics(succ).get("connectionRisk").asText()).isEqualTo("NONE");
    }
    @Test
    void batchRollsBackEntirelyWhenAnyPlanHasNoFeasibleWindow() throws Exception {
        // 可行车次对：延后后保障不足，但运营日内有可行窗
        String sectionA1 = key("SEC");
        String sectionB1 = key("SEC");
        String predA = publishedPlan(occ("G1", sectionA1, iso(8, 0), iso(10, 0)));
        String succA = publishedPlan(occ("G2", sectionB1, iso(10, 45), iso(12, 0)));
        register(predA, succA, 30);
        delayPredecessor(predA, sectionA1, iso(8, 0), iso(10, 30));
        // 不可行车次对：终到 23:35，保障 15 分钟要求始发 23:50，
        // 后车时长 15 分钟最晚 23:45 始发，运营日内无可行窗
        String sectionA2 = key("SEC");
        String sectionB2 = key("SEC");
        String predB = publishedPlan(occ("G3", sectionA2, iso(22, 0), iso(23, 0)));
        String succB = publishedPlan(occ("G4", sectionB2, iso(23, 20), iso(23, 35)));
        register(predB, succB, 15);
        delayPredecessor(predB, sectionA2, iso(22, 0), iso(23, 35));
        assertThat(diagnostics(succA).get("connectionRisk").asText()).isEqualTo("CONNECTION_AT_RISK");
        assertThat(diagnostics(succB).get("connectionRisk").asText()).isEqualTo("CONNECTION_AT_RISK");

        MvcResult result = mvc.perform(post("/api/v1/connections/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), succA, 1, succB, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("NO_FEASIBLE_WINDOW");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("scheduleKey").asText()).isEqualTo(succB);
        assertThat(detail.get("requiredMinutes").asInt()).isEqualTo(15);
        assertThat(detail.get("actualMinutes").asInt()).isEqualTo(-15);
        assertThat(detail.get("deficitMinutes").asInt()).isEqualTo(30);

        // 整次回滚：可行计划的时刻、版本、风险标记与时隙占用全部保持原样
        JsonNode planA = plan(succA);
        assertThat(planA.get("version").asInt()).isEqualTo(1);
        assertThat(planA.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(10, 45));
        assertThat(diagnostics(succA).get("connectionRisk").asText()).isEqualTo("CONNECTION_AT_RISK");
        JsonNode slotsA = publishedSlots(sectionB1);
        assertThat(slotsA).hasSize(1);
        assertThat(slotsA.get(0).get("startUtc").asText()).isEqualTo(iso(10, 45));
        JsonNode planB = plan(succB);
        assertThat(planB.get("version").asInt()).isEqualTo(1);
        assertThat(planB.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(23, 20));
        assertThat(diagnostics(succB).get("connectionRisk").asText()).isEqualTo("CONNECTION_AT_RISK");
    }

    @Test
    void batchRejectsNotAtRiskAndStartedPlans() throws Exception {
        // 未处于风险状态的已发布计划 → 422 NOT_AT_RISK
        String calm = publishedPlan(occ("G5", key("SEC"), iso(14, 0), iso(15, 0)));
        MvcResult notAtRisk = mvc.perform(post("/api/v1/connections/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), calm, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(notAtRisk).get("code").asText()).isEqualTo("NOT_AT_RISK");

        // 已开始的计划不可作为候选改签 → 422 PLAN_ALREADY_STARTED
        String sectionA = key("SEC");
        String pred = publishedPlan(occ("G1", sectionA, iso(8, 0), iso(10, 0)));
        String succ = publishedPlan(occ("G2", key("SEC"), iso(10, 45), iso(12, 0)));
        register(pred, succ, 30);
        delayPredecessor(pred, sectionA, iso(8, 0), iso(10, 30));
        assertThat(diagnostics(succ).get("connectionRisk").asText()).isEqualTo("CONNECTION_AT_RISK");
        clock.setInstant(Instant.parse(iso(11, 0)));
        MvcResult started = mvc.perform(post("/api/v1/connections/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), succ, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(started).get("code").asText()).isEqualTo("PLAN_ALREADY_STARTED");
        // 时刻与风险标记未被修改
        assertThat(plan(succ).get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(10, 45));
        assertThat(diagnostics(succ).get("connectionRisk").asText()).isEqualTo("CONNECTION_AT_RISK");
    }

    @Test
    void batchIdempotencyReplayConflictAndFailureNotConsumingKey() throws Exception {
        String sectionA = key("SEC");
        String pred = publishedPlan(occ("G1", sectionA, iso(8, 0), iso(10, 0)));
        String succ = publishedPlan(occ("G2", key("SEC"), iso(10, 45), iso(12, 0)));
        register(pred, succ, 30);
        delayPredecessor(pred, sectionA, iso(8, 0), iso(10, 30));

        // 失败不占键：版本不符 409 后，同键修正版本成功
        String requestKey = key("REQ");
        mvc.perform(post("/api/v1/connections/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(requestKey, succ, 5)))
                .andExpect(status().isConflict());
        MvcResult first = mvc.perform(post("/api/v1/connections/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(requestKey, succ, 1)))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放返回首次结果，且不重复平移
        MvcResult replay = mvc.perform(post("/api/v1/connections/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(requestKey, succ, 1)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        assertThat(plan(succ).get("version").asInt()).isEqualTo(2);
        assertThat(plan(succ).get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(11, 0));
        // 同键不同参 → 409
        mvc.perform(post("/api/v1/connections/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(requestKey, succ, 2)))
                .andExpect(status().isConflict());
        // 新键对已解除风险的计划 → 422 NOT_AT_RISK
        MvcResult again = mvc.perform(post("/api/v1/connections/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), succ, 2)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(again).get("code").asText()).isEqualTo("NOT_AT_RISK");
    }

    // ---------- 辅助 ----------

    private String publishedPlan(String... occupancies) throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occupancies);
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        return scheduleKey;
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\"" + scheduleKey
                                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private long register(String pred, String succ, int guarantee) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, guarantee, "西安北", "西安北", false)))
                .andExpect(status().isCreated()).andReturn();
        return read(result).get("id").asLong();
    }

    /** 将前车改签为同区段、终到 delayedArrival 的新计划，触发接续重算。 */
    private void delayPredecessor(String pred, String section, String startIso, String delayedArrival)
            throws Exception {
        String predNew = key("SCH");
        createPlan(predNew, occ("G1", section, startIso, delayedArrival));
        reschedule(pred, predNew);
    }

    private void reschedule(String oldKey, String newKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"newScheduleKey\":\"" + newKey
                                + "\",\"expectedOldVersion\":1,\"expectedNewVersion\":1}"))
                .andExpect(status().isOk());
    }

    private JsonNode diagnostics(String scheduleKey) throws Exception {
        return read(mvc.perform(get("/api/v1/plans/{key}/connection-diagnostics", scheduleKey))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode history(long connectionId) throws Exception {
        return read(mvc.perform(get("/api/v1/connections/{id}/history", connectionId))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode plan(String scheduleKey) throws Exception {
        return read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode publishedSlots(String sectionId) throws Exception {
        return read(mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", sectionId))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    static String batchBody(String requestKey, String scheduleKey, int expectedVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"items\":[{\"scheduleKey\":\""
                + scheduleKey + "\",\"expectedVersion\":" + expectedVersion + "}]}";
    }

    static String batchBody(String requestKey, String key1, int version1, String key2, int version2) {
        return "{\"requestKey\":\"" + requestKey + "\",\"items\":["
                + "{\"scheduleKey\":\"" + key1 + "\",\"expectedVersion\":" + version1 + "},"
                + "{\"scheduleKey\":\"" + key2 + "\",\"expectedVersion\":" + version2 + "}]}";
    }
}