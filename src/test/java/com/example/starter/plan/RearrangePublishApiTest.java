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
 * 发布时气象限速整体顺延的主流程与失败分支测试（H2 内存库，受控时钟）。
 * 时钟固定在运营日之前，保证计划未开始运行；特定用例再覆盖为运行中时刻。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RearrangePublishApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    /** 固定时钟：运营日前一天，所有计划均未开始运行。 */
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

    // ---------- 主流程：整体顺延 ----------

    @Test
    void publishShiftsWholePlanAndPersistsRearrangement() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 30);
        String restrictionKey = key("RST");
        registerRestriction(restrictionKey, section, iso(8), iso(9), 100);

        // 两段占用：受限区段 08:00-09:00 + 未登记区段 09:00-10:00
        String otherSection = key("SEC");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey,
                occ("G1", section, iso(8), iso(9)),
                occ("G1", otherSection, iso(9), iso(10)));

        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(published);
        assertThat(body.get("status").asText()).isEqualTo("PUBLISHED");
        // 整体顺延 60 分钟（两次最小间隔步进），不只调整单段：两段占用同步后移
        assertThat(body.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(9));
        assertThat(body.get("occupancies").get(0).get("endUtc").asText()).isEqualTo(iso(10));
        assertThat(body.get("occupancies").get(1).get("startUtc").asText()).isEqualTo(iso(10));
        assertThat(body.get("occupancies").get(1).get("endUtc").asText()).isEqualTo(iso(11));

        // 重排记录：固化限速令版本、原计划时刻与新计划时刻
        MvcResult history = mvc.perform(get("/api/v1/plans/{key}/rearrangements", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode records = read(history);
        assertThat(records).hasSize(1);
        JsonNode record = records.get(0);
        assertThat(record.get("opType").asText()).isEqualTo("PUBLISH");
        assertThat(record.get("shiftMinutes").asLong()).isEqualTo(60);
        assertThat(record.get("items")).hasSize(2);
        JsonNode item0 = record.get("items").get(0);
        assertThat(item0.get("origStartUtc").asText()).isEqualTo(iso(8));
        assertThat(item0.get("origEndUtc").asText()).isEqualTo(iso(9));
        assertThat(item0.get("newStartUtc").asText()).isEqualTo(iso(9));
        assertThat(item0.get("newEndUtc").asText()).isEqualTo(iso(10));
        assertThat(record.get("restrictions")).hasSize(1);
        JsonNode snapshot = record.get("restrictions").get(0);
        assertThat(snapshot.get("restrictionKey").asText()).isEqualTo(restrictionKey);
        assertThat(snapshot.get("restrictionVersion").asInt()).isEqualTo(1);
        assertThat(snapshot.get("maxSpeedKmh").asInt()).isEqualTo(100);
        assertThat(snapshot.get("affectedMinutes").asLong()).isEqualTo(60);

        // 生效时隙按新时刻发布
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots).get(0).get("startUtc").asText()).isEqualTo(iso(9));
    }

    @Test
    void stackedRestrictionsLowestSpeedDecides() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 60);
        // 不低于设计速度的限速不构成违反；低于设计速度的才触发顺延
        registerRestriction(key("RST"), section, iso(8), iso(10), 250);
        String lowKey = key("RST");
        registerRestriction(lowKey, section, iso(8), iso(9), 80);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8), iso(9)));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());

        MvcResult history = mvc.perform(get("/api/v1/plans/{key}/rearrangements", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode records = read(history);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("shiftMinutes").asLong()).isEqualTo(60);
        // 快照仅包含实际被违反的低速限速令
        assertThat(records.get(0).get("restrictions")).hasSize(1);
        assertThat(records.get(0).get("restrictions").get(0).get("restrictionKey").asText())
                .isEqualTo(lowKey);
    }

    @Test
    void limitAboveDesignSpeedDoesNotShift() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 30);
        registerRestriction(key("RST"), section, iso(8), iso(9), 300);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8), iso(9)));
        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(published).get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(8));

        MvcResult history = mvc.perform(get("/api/v1/plans/{key}/rearrangements", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(history)).isEmpty();
    }

    // ---------- 失败分支 ----------

    @Test
    void shiftConflictWithPublishedPlanReturns422AndKeyReusable() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 60);
        // 先发布 09:00-10:00 的占用计划（登记限速前，不受影响）
        String blocker = key("SCH");
        createPlan(blocker, occ("G1", section, iso(9), iso(10)));
        publishPlan(blocker, key("REQ"));
        // 登记限速 08:00-09:00，新计划顺延后落入 09:00-10:00 与已发布计划冲突
        registerRestriction(key("RST"), section, iso(8), iso(9), 100);

        String scheduleKey = key("SCH");
        String publishKey = key("REQ");
        createPlan(scheduleKey, occ("G2", section, iso(8), iso(9)));
        MvcResult conflict = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(blocker);
        assertThat(detail.get("startUtc").asText()).isEqualTo(iso(9));

        // 计划保持草稿、占用未改写、无重排记录（查不到半成品）
        MvcResult after = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan = read(after);
        assertThat(plan.get("status").asText()).isEqualTo("DRAFT");
        assertThat(plan.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(8));
        MvcResult history = mvc.perform(get("/api/v1/plans/{key}/rearrangements", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(history)).isEmpty();

        // 失败不占键：取消阻塞计划后同键重发成功
        mvc.perform(post("/api/v1/plans/{key}/cancel", blocker)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(published).get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(9));
    }

    @Test
    void startedPlanCannotBeRearranged() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 30);
        registerRestriction(key("RST"), section, iso(8), iso(9), 100);
        // 时钟推进到 08:30（Asia/Shanghai），计划 08:00 已开始运行
        Instant running = DAY.atTime(8, 30).atZone(SH).toInstant();
        when(clock.instant()).thenReturn(running);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8), iso(9)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("PLAN_ALREADY_RUNNING");
        assertThat(error.get("details").get(0).get("scheduleKey").asText()).isEqualTo(scheduleKey);

        MvcResult after = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void shiftBeyondOperationDayReturns422() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 60);
        registerRestriction(key("RST"), section, iso(22), iso(24), 100);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(23), iso(24)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("REARRANGE_OUT_OF_DAY");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("requiredDayEndUtc").asText()).isNotBlank();
        assertThat(detail.get("actualEndUtc").asText()).isNotBlank();

        MvcResult after = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after).get("status").asText()).isEqualTo("DRAFT");
    }

    // ---------- 辅助 ----------

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

    /** 运营日当日 hour 点（Asia/Shanghai）的 UTC 时刻，hour 可超过 24 表示次日。 */
    private static String iso(int hour) {
        return DAY.plusDays(hour / 24L).atTime(hour % 24, 0).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }
}
