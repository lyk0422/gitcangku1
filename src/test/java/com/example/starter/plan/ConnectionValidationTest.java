package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 接续保障失败分支与幂等边界测试（H2 内存库）：登记 422 分支、
 * 批量改签前置校验、requestKey 重放/异参 409/失败不占键。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConnectionValidationTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 登记失败分支 ----------

    @Test
    void guaranteeMinutesOutOfRangeReturns422() throws Exception {
        String sec1 = key("SEC");
        String sec2 = key("SEC");
        createSection(sec1, "Alpha", "Beta");
        createSection(sec2, "Beta", "Gamma");
        String pred = key("SCH");
        String succ = key("SCH");
        createAndPublish(pred, DAY, occJson("G1", sec1, iso(8, 0), iso(9, 0)));
        createAndPublish(succ, DAY, occJson("G2", sec2, iso(9, 30), iso(10, 30)));

        MvcResult tooSmall = registerConnection(key("REQ"), pred, succ, 3, false)
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(tooSmall);
        assertThat(error.get("code").asText()).isEqualTo("GUARANTEE_RANGE_INVALID");
        assertThat(error.get("details").get(0).get("actualMinutes").asInt()).isEqualTo(3);
        assertThat(error.get("details").get(0).get("requiredMinMinutes").asInt()).isEqualTo(5);
        assertThat(error.get("details").get(0).get("requiredMaxMinutes").asInt()).isEqualTo(240);

        MvcResult tooLarge = registerConnection(key("REQ"), pred, succ, 500, false)
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(tooLarge).get("code").asText()).isEqualTo("GUARANTEE_RANGE_INVALID");
    }

    @Test
    void cancelledOrDraftPlanReturns422() throws Exception {
        String sec1 = key("SEC");
        String sec2 = key("SEC");
        createSection(sec1, "Alpha", "Beta");
        createSection(sec2, "Beta", "Gamma");
        String pred = key("SCH");
        String succ = key("SCH");
        createAndPublish(pred, DAY, occJson("G1", sec1, iso(8, 0), iso(9, 0)));
        createAndPublish(succ, DAY, occJson("G2", sec2, iso(9, 30), iso(10, 30)));

        // 计划已取消：422
        mvc.perform(post("/api/v1/plans/{key}/cancel", pred)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isOk());
        MvcResult cancelledPred = registerConnection(key("REQ"), pred, succ, 20, false)
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(cancelledPred).get("code").asText()).isEqualTo("PLAN_NOT_PUBLISHED");

        // 后车仍为草稿：422
        String draft = key("SCH");
        createPlan(draft, DAY, occJson("G3", sec2, iso(11, 0), iso(12, 0)));
        MvcResult draftSucc = registerConnection(key("REQ"), succ, draft, 20, false)
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(draftSucc).get("code").asText()).isEqualTo("PLAN_NOT_PUBLISHED");
    }

    @Test
    void discontinuousStationsOrUnknownSectionReturns422() throws Exception {
        String sec1 = key("SEC");
        String sec2 = key("SEC");
        createSection(sec1, "Alpha", "Beta");
        createSection(sec2, "Gamma", "Delta");
        String pred = key("SCH");
        String succ = key("SCH");
        createAndPublish(pred, DAY, occJson("G1", sec1, iso(8, 0), iso(9, 0)));
        createAndPublish(succ, DAY, occJson("G2", sec2, iso(9, 30), iso(10, 30)));

        // 站点不连续：前车终到 Beta，后车始发 Gamma
        MvcResult discontinuous = registerConnection(key("REQ"), pred, succ, 20, false)
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(discontinuous);
        assertThat(error.get("code").asText()).isEqualTo("STATION_NOT_CONTINUOUS");
        assertThat(error.get("details").get(0).get("arrivalStation").asText()).isEqualTo("Beta");
        assertThat(error.get("details").get(0).get("departureStation").asText()).isEqualTo("Gamma");

        // 区段未登记：422
        String sec3 = key("SEC");
        String succ2 = key("SCH");
        createAndPublish(succ2, DAY, occJson("G4", sec3, iso(9, 30), iso(10, 30)));
        MvcResult unknown = registerConnection(key("REQ"), pred, succ2, 20, false)
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(unknown).get("code").asText()).isEqualTo("SECTION_UNKNOWN");
    }

    @Test
    void insufficientGuaranteeAtRegistrationReturns422WithAmounts() throws Exception {
        String sec1 = key("SEC");
        String sec2 = key("SEC");
        createSection(sec1, "Alpha", "Beta");
        createSection(sec2, "Beta", "Gamma");
        String pred = key("SCH");
        String succ = key("SCH");
        createAndPublish(pred, DAY, occJson("G1", sec1, iso(8, 0), iso(9, 0)));
        createAndPublish(succ, DAY, occJson("G2", sec2, iso(9, 10), iso(10, 30)));

        MvcResult insufficient = registerConnection(key("REQ"), pred, succ, 20, false)
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(insufficient);
        assertThat(error.get("code").asText()).isEqualTo("GUARANTEE_INSUFFICIENT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("requiredMinutes").asInt()).isEqualTo(20);
        assertThat(detail.get("actualMinutes").asInt()).isEqualTo(10);
        assertThat(detail.get("shortfallMinutes").asInt()).isEqualTo(10);
    }
    // ---------- 幂等边界 ----------

    @Test
    void registerIdempotencyReplayConflictAndFailureNotOccupyingKey() throws Exception {
        String sec1 = key("SEC");
        String sec2 = key("SEC");
        createSection(sec1, "Alpha", "Beta");
        createSection(sec2, "Beta", "Gamma");
        String pred = key("SCH");
        String succ = key("SCH");
        createAndPublish(pred, DAY, occJson("G1", sec1, iso(8, 0), iso(9, 0)));
        createAndPublish(succ, DAY, occJson("G2", sec2, iso(9, 30), iso(10, 30)));

        // 失败不占键：非法保障分钟数 422 后，同键修正参数可成功
        String reusedKey = key("REQ");
        registerConnection(reusedKey, pred, succ, 3, false)
                .andExpect(status().isUnprocessableEntity());
        MvcResult created = registerConnection(reusedKey, pred, succ, 20, false)
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(created);

        // 同键同参重放：返回首次结果，不新增接续
        MvcResult replay = registerConnection(reusedKey, pred, succ, 20, false)
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(body);
        JsonNode connections = read(mvc.perform(get("/api/v1/plans/{key}/connections", succ))
                .andExpect(status().isOk()).andReturn());
        assertThat(connections).hasSize(1);

        // 同键异参：409
        registerConnection(reusedKey, pred, succ, 25, false)
                .andExpect(status().isConflict());

        // 异键重复登记同一前后车：409 CONNECTION_EXISTS
        MvcResult duplicate = registerConnection(key("REQ"), pred, succ, 20, false)
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(duplicate).get("code").asText()).isEqualTo("CONNECTION_EXISTS");
    }

    @Test
    void batchGuaranteeRescheduleIdempotentReplay() throws Exception {
        String sec1 = key("SEC");
        String sec2 = key("SEC");
        createSection(sec1, "Alpha", "Beta");
        createSection(sec2, "Beta", "Gamma");
        String pred = key("SCH");
        String succ = key("SCH");
        createAndPublish(pred, DAY, occJson("G1", sec1, iso(8, 0), iso(9, 0)));
        createAndPublish(succ, DAY, occJson("G2", sec2, iso(9, 30), iso(10, 30)));
        registerConnection(key("REQ"), pred, succ, 20, false)
                .andExpect(status().isCreated());
        String pred2 = key("SCH");
        createPlan(pred2, DAY, occJson("G1", sec1, iso(9, 20), iso(10, 20)));
        reschedule(pred, pred2);

        String batchKey = key("REQ");
        String batchBody = "{\"requestKey\":\"" + batchKey
                + "\",\"scheduleKeys\":[\"" + succ + "\"]}";
        MvcResult first = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(batchBody))
                .andExpect(status().isOk()).andReturn();

        // 同键同参重放：风险标记已清除仍返回首次结果，不重复平移
        MvcResult replay = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(batchBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        JsonNode succBody = read(mvc.perform(get("/api/v1/plans/{key}", succ))
                .andExpect(status().isOk()).andReturn());
        assertThat(succBody.get("version").asInt()).isEqualTo(2);
        assertThat(succBody.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(10, 40));

        // 同键异参：409
        mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + batchKey
                                + "\",\"scheduleKeys\":[\"" + pred2 + "\"]}"))
                .andExpect(status().isConflict());
    }

    // ---------- 批量改签前置校验 ----------

    @Test
    void batchRejectsPlanNotAtRiskOrNotFound() throws Exception {
        String sec1 = key("SEC");
        String sec2 = key("SEC");
        createSection(sec1, "Alpha", "Beta");
        createSection(sec2, "Beta", "Gamma");
        String pred = key("SCH");
        String succ = key("SCH");
        createAndPublish(pred, DAY, occJson("G1", sec1, iso(8, 0), iso(9, 0)));
        createAndPublish(succ, DAY, occJson("G2", sec2, iso(9, 30), iso(10, 30)));
        registerConnection(key("REQ"), pred, succ, 20, false)
                .andExpect(status().isCreated());

        // 未处于风险状态：422
        MvcResult notAtRisk = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ")
                                + "\",\"scheduleKeys\":[\"" + succ + "\"]}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(notAtRisk).get("code").asText()).isEqualTo("PLAN_NOT_AT_RISK");

        // 计划不存在：404
        mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ")
                                + "\",\"scheduleKeys\":[\"" + key("SCH") + "\"]}"))
                .andExpect(status().isNotFound());

        // 重复计划：400
        mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ")
                                + "\",\"scheduleKeys\":[\"" + succ + "\",\"" + succ + "\"]}"))
                .andExpect(status().isBadRequest());
    }
    // ---------- 辅助 ----------

    private void createSection(String sectionId, String from, String to) throws Exception {
        mvc.perform(post("/api/v1/sections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sectionId\":\"" + sectionId + "\",\"fromStation\":\"" + from
                                + "\",\"toStation\":\"" + to + "\"}"))
                .andExpect(status().isCreated());
    }

    private void createPlan(String scheduleKey, LocalDate opDate, String... occupancies)
            throws Exception {
        mvc.perform(post("/api/v1/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + opDate
                                + "\",\"occupancies\":[" + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void createAndPublish(String scheduleKey, LocalDate opDate, String... occupancies)
            throws Exception {
        createPlan(scheduleKey, opDate, occupancies);
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isOk());
    }

    private void reschedule(String oldKey, String newKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"newScheduleKey\":\""
                                + newKey + "\",\"expectedOldVersion\":1,\"expectedNewVersion\":1}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions registerConnection(
            String requestKey, String pred, String succ, int guarantee, boolean crossesMidnight)
            throws Exception {
        return mvc.perform(post("/api/v1/connections")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestKey\":\"" + requestKey + "\",\"predecessorKey\":\"" + pred
                        + "\",\"successorKey\":\"" + succ + "\",\"guaranteeMinutes\":" + guarantee
                        + ",\"crossesMidnight\":" + crossesMidnight + "}"));
    }

    private static String occJson(String trainNo, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + trainNo + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}