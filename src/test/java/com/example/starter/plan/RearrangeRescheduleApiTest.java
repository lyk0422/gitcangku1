package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * 改签时的限速顺延、重排记录不可改性与幂等指纹（计划集合+限速版本+操作者）测试。
 * 时钟固定在运营日之前，计划均未开始运行。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RearrangeRescheduleApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    private static final Instant FIXED_NOW = Instant.parse("2026-09-20T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void stubClock() {
        when(clock.instant()).thenReturn(FIXED_NOW);
        when(clock.millis()).thenReturn(FIXED_NOW.toEpochMilli());
    }

    @Test
    void rescheduleShiftsNewPlanAndRecordSurvivesReviseAndRevoke() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 60);
        // 旧计划在限速登记前发布，按原时刻生效（限速只影响后续发布或改签）
        String oldKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        assertThat(planStart(oldKey)).isEqualTo(iso(8));

        String restrictionKey = key("RST");
        registerRestriction(restrictionKey, section, iso(8), iso(9), 100);

        // 改签：新草稿 08:00-09:00 违反限速，整体顺延至 09:00-10:00
        String newKey = key("SCH");
        createPlan(newKey, occ("G1", section, iso(8), iso(9)));
        MvcResult rescheduled = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1, "op-x")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(rescheduled);
        assertThat(body.get("oldPlan").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("newPlan").get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(body.get("newPlan").get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(9));

        // 重排记录固化：操作类型、限速令版本 1、原/新时刻
        JsonNode records = read(mvc.perform(get("/api/v1/plans/{key}/rearrangements", newKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(records).hasSize(1);
        JsonNode record = records.get(0);
        assertThat(record.get("opType").asText()).isEqualTo("RESCHEDULE");
        assertThat(record.get("shiftMinutes").asLong()).isEqualTo(60);
        assertThat(record.get("items").get(0).get("origStartUtc").asText()).isEqualTo(iso(8));
        assertThat(record.get("items").get(0).get("newStartUtc").asText()).isEqualTo(iso(9));
        assertThat(record.get("restrictions").get(0).get("restrictionVersion").asInt())
                .isEqualTo(1);
        // 旧计划未被重排，无重排记录
        JsonNode oldRecords = read(mvc.perform(get("/api/v1/plans/{key}/rearrangements", oldKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(oldRecords).isEmpty();

        // 修订限速令（版本 2）并撤销：已生成的重排记录不得改写
        mvc.perform(put("/api/v1/restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"startUtc\":\""
                                + iso(12) + "\",\"endUtc\":\"" + iso(13)
                                + "\",\"maxSpeedKmh\":90}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isOk());

        JsonNode after = read(mvc.perform(get("/api/v1/plans/{key}/rearrangements", newKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(after).isEqualTo(records);
        // 改签链保持完整
        JsonNode chain = read(mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", newKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(chain.get("chain")).hasSize(2);
    }

    @Test
    void publishFingerprintCoversOperatorAndRestrictionVersion() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 60);
        String restrictionKey = key("RST");
        registerRestriction(restrictionKey, section, iso(8), iso(9), 100);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8), iso(9)));
        String requestKey = key("REQ");
        String body = "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"op-1\"}";

        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放：返回首次结果，不重复顺延、不重复生成重排记录
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        JsonNode records = read(mvc.perform(get("/api/v1/plans/{key}/rearrangements", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(records).hasSize(1);

        // 同键不同操作者 → 409
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + requestKey + "\",\"operator\":\"op-2\"}"))
                .andExpect(status().isConflict());

        // 限速令修订后指纹变化：同键同参也判定异参 → 409
        mvc.perform(put("/api/v1/restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"startUtc\":\""
                                + iso(8) + "\",\"endUtc\":\"" + iso(9)
                                + "\",\"maxSpeedKmh\":90}"))
                .andExpect(status().isOk());
        MvcResult reused = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(reused).get("code").asText()).isEqualTo("IDEMPOTENT_KEY_REUSED");
    }

    @Test
    void rescheduleConflictKeepsBothPlansUnchanged() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 60);
        // 第三方已发布计划占住 09:00-10:00
        String blocker = key("SCH");
        createPlan(blocker, occ("G9", section, iso(9), iso(10)));
        publishPlan(blocker, key("REQ"));
        // 旧计划 08:00-09:00 发布（限速登记前）
        String oldKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        registerRestriction(key("RST"), section, iso(8), iso(9), 100);

        // 新草稿顺延后与第三方冲突 → 整次 422，旧计划仍发布、新计划仍草稿
        String newKey = key("SCH");
        createPlan(newKey, occ("G1", section, iso(8), iso(9)));
        MvcResult conflict = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1, null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        assertThat(error.get("details").get(0).get("conflictingScheduleKey").asText())
                .isEqualTo(blocker);

        assertThat(planStatus(oldKey)).isEqualTo("PUBLISHED");
        assertThat(planStatus(newKey)).isEqualTo("DRAFT");
        assertThat(planStart(newKey)).isEqualTo(iso(8));
        JsonNode records = read(mvc.perform(get("/api/v1/plans/{key}/rearrangements", newKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(records).isEmpty();
    }

    // ---------- 辅助 ----------

    private String planStatus(String scheduleKey) throws Exception {
        return read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn()).get("status").asText();
    }

    private String planStart(String scheduleKey) throws Exception {
        return read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn())
                .get("occupancies").get(0).get("startUtc").asText();
    }

    private void registerSection(String sectionId, int designSpeed, int headway) throws Exception {
        mvc.perform(post("/api/v1/sections").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sectionId\":\"" + sectionId + "\",\"designSpeedKmh\":"
                                + designSpeed + ",\"minHeadwayMinutes\":" + headway + "}"))
                .andExpect(status().isCreated());
    }

    private void registerRestriction(String restrictionKey, String sectionId,
                                     String startIso, String endIso, int maxSpeed) throws Exception {
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"restrictionKey\":\""
                                + restrictionKey + "\",\"sectionId\":\"" + sectionId
                                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso
                                + "\",\"maxSpeedKmh\":" + maxSpeed + "}"))
                .andExpect(status().isCreated());
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String rescheduleBody(String requestKey, String newKey, int oldVersion,
                                         int newVersion, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newKey
                + "\",\"expectedOldVersion\":" + oldVersion + ",\"expectedNewVersion\":"
                + newVersion
                + (operator == null ? "" : ",\"operator\":\"" + operator + "\"") + "}";
    }
}
