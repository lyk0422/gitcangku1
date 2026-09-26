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
 * 气象限速触发的计划整体顺延重排测试（H2 内存库）：发布/改签违反限速时按最小间隔整体顺延、
 * 重排记录固化与不可改写、顺延后再冲突整次 422、无法满足与已开始运行的失败分支、
 * 以及限速令只影响后续发布/改签的边界。
 * 运营日取未来日期 2030-01-05（Asia/Shanghai），"已开始运行"用例取过去日期 2020-01-06。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WeatherRearrangementApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2030, 1, 5);
    private static final LocalDate PAST_DAY = LocalDate.of(2020, 1, 6);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 发布触发整体顺延 ----------

    @Test
    void publishViolatingRestrictionShiftsWholePlanAndPersistsRecord() throws Exception {
        String section = key("SEC");
        String restrictionKey = key("RST");
        // 限速窗口 10:00-12:00，计划 09:00-11:00 与之相交 60 分钟
        registerRestriction(restrictionKey, section, iso(DAY, 10, 0), iso(DAY, 12, 0), 60);
        String plan = key("SCH");
        createPlan(plan, DAY, occ("G1", section, iso(DAY, 9, 0), iso(DAY, 11, 0)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), "op-pub")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("status").asText()).isEqualTo("PUBLISHED");
        // 最小间隔为计划内最短占用 120 分钟：顺延 120 仍相交，顺延 240 避开 → 整体顺延 240 分钟
        JsonNode occupancies = body.get("occupancies");
        assertThat(occupancies).hasSize(1);
        assertThat(occupancies.get(0).get("startUtc").asText()).isEqualTo(iso(DAY, 13, 0));
        assertThat(occupancies.get(0).get("endUtc").asText()).isEqualTo(iso(DAY, 15, 0));

        // 响应内嵌重排记录：固化限速令版本、原计划时刻与新计划时刻、受影响分钟数
        JsonNode rearrangement = body.get("rearrangement");
        assertThat(rearrangement.get("opType").asText()).isEqualTo("PUBLISH");
        assertThat(rearrangement.get("shiftMinutes").asLong()).isEqualTo(240);
        JsonNode segments = rearrangement.get("segments");
        assertThat(segments).hasSize(1);
        JsonNode segment = segments.get(0);
        assertThat(segment.get("oldStartUtc").asText()).isEqualTo(iso(DAY, 9, 0));
        assertThat(segment.get("oldEndUtc").asText()).isEqualTo(iso(DAY, 11, 0));
        assertThat(segment.get("newStartUtc").asText()).isEqualTo(iso(DAY, 13, 0));
        assertThat(segment.get("newEndUtc").asText()).isEqualTo(iso(DAY, 15, 0));
        assertThat(segment.get("affectedMinutes").asLong()).isEqualTo(60);
        assertThat(segment.get("restrictionKey").asText()).isEqualTo(restrictionKey);
        assertThat(segment.get("restrictionVersion").asInt()).isEqualTo(1);
        assertThat(segment.get("maxSpeedKmh").asInt()).isEqualTo(60);

        // 重排历史查询返回同一记录；撤销限速令不改写已生成的重排记录
        revokeRestriction(restrictionKey);
        MvcResult history = mvc.perform(get("/api/v1/plans/{key}/rearrangements", plan))
                .andExpect(status().isOk()).andReturn();
        JsonNode records = read(history);
        assertThat(records).hasSize(1);
        assertThat(records.get(0)).isEqualTo(rearrangement);

        // 生效时隙为顺延后时刻
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        JsonNode slotList = read(slots);
        assertThat(slotList).hasSize(1);
        assertThat(slotList.get(0).get("startUtc").asText()).isEqualTo(iso(DAY, 13, 0));
    }

    @Test
    void publishWithoutViolationKeepsOriginalTimesAndNoRecord() throws Exception {
        String section = key("SEC");
        registerRestriction(key("RST"), section, iso(DAY, 10, 0), iso(DAY, 12, 0), 60);
        String plan = key("SCH");
        // 14:00-15:00 与限速窗口不相交
        createPlan(plan, DAY, occ("G1", section, iso(DAY, 14, 0), iso(DAY, 15, 0)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(DAY, 14, 0));
        assertThat(body.get("rearrangement").isNull()).isTrue();

        MvcResult history = mvc.perform(get("/api/v1/plans/{key}/rearrangements", plan))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(history)).isEmpty();
    }

    @Test
    void revokedRestrictionDoesNotAffectSubsequentPublish() throws Exception {
        String section = key("SEC");
        String restrictionKey = key("RST");
        registerRestriction(restrictionKey, section, iso(DAY, 10, 0), iso(DAY, 12, 0), 60);
        revokeRestriction(restrictionKey);
        String plan = key("SCH");
        createPlan(plan, DAY, occ("G1", section, iso(DAY, 9, 0), iso(DAY, 11, 0)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(DAY, 9, 0));
        assertThat(body.get("rearrangement").isNull()).isTrue();
    }

    @Test
    void restrictionRegisteredAfterPublishOnlyAffectsSubsequentPublish() throws Exception {
        String section = key("SEC");
        String first = key("SCH");
        createPlan(first, DAY, occ("G1", section, iso(DAY, 9, 0), iso(DAY, 11, 0)));
        publishPlan(first);

        // 限速令登记在发布之后：已发布计划不重排、不补记
        registerRestriction(key("RST"), section, iso(DAY, 10, 0), iso(DAY, 12, 0), 60);
        MvcResult firstDetail = mvc.perform(get("/api/v1/plans/{key}", first))
                .andExpect(status().isOk()).andReturn();
        JsonNode firstBody = read(firstDetail);
        assertThat(firstBody.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(DAY, 9, 0));
        assertThat(firstBody.get("rearrangement").isNull()).isTrue();

        // 后续发布受同一限速令约束而整体顺延
        String second = key("SCH");
        createPlan(second, DAY, occ("G2", section, iso(DAY, 9, 0), iso(DAY, 11, 0)));
        MvcResult secondResult = mvc.perform(post("/api/v1/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(secondResult).get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(DAY, 13, 0));
    }

    @Test
    void revisedRestrictionAppliesToNewPublishWithoutRewritingOldRecord() throws Exception {
        String section = key("SEC");
        String restrictionKey = key("RST");
        registerRestriction(restrictionKey, section, iso(DAY, 10, 0), iso(DAY, 12, 0), 60);
        String first = key("SCH");
        createPlan(first, DAY, occ("G1", section, iso(DAY, 11, 0), iso(DAY, 12, 0)));
        publishPlan(first);

        // 首次发布按 v1 顺延 60 分钟（最小间隔 60）
        MvcResult firstHistory = mvc.perform(get("/api/v1/plans/{key}/rearrangements", first))
                .andExpect(status().isOk()).andReturn();
        JsonNode firstRecord = read(firstHistory).get(0);
        assertThat(firstRecord.get("shiftMinutes").asLong()).isEqualTo(60);
        assertThat(firstRecord.get("segments").get(0).get("restrictionVersion").asInt()).isEqualTo(1);

        // 修订为 v2：窗口扩大、速度降低
        reviseRestriction(restrictionKey, section, iso(DAY, 10, 0), iso(DAY, 13, 0), 40);

        // 已生成的重排记录不被修订改写
        MvcResult firstHistoryAfter = mvc.perform(get("/api/v1/plans/{key}/rearrangements", first))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(firstHistoryAfter).get(0)).isEqualTo(firstRecord);

        // 后续发布按 v2 裁决：10:30-11:30 依次顺延 60/120/180，180 时避开 [10:00,13:00)
        String second = key("SCH");
        createPlan(second, DAY, occ("G2", section, iso(DAY, 10, 30), iso(DAY, 11, 30)));
        MvcResult secondResult = mvc.perform(post("/api/v1/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isOk()).andReturn();
        JsonNode rearrangement = read(secondResult).get("rearrangement");
        assertThat(rearrangement.get("shiftMinutes").asLong()).isEqualTo(180);
        JsonNode segment = rearrangement.get("segments").get(0);
        assertThat(segment.get("restrictionVersion").asInt()).isEqualTo(2);
        assertThat(segment.get("maxSpeedKmh").asInt()).isEqualTo(40);
        assertThat(segment.get("newStartUtc").asText()).isEqualTo(iso(DAY, 13, 30));
    }

    // ---------- 改签触发整体顺延 ----------

    @Test
    void rescheduleShiftsNewPlanAndRecordsRearrangement() throws Exception {
        String oldSection = key("SEC");
        String newSection = key("SEC");
        registerRestriction(key("RST"), newSection, iso(DAY, 10, 0), iso(DAY, 12, 0), 60);
        String oldPlan = key("SCH");
        String newPlan = key("SCH");
        createPlan(oldPlan, DAY, occ("G1", oldSection, iso(DAY, 8, 0), iso(DAY, 9, 0)));
        publishPlan(oldPlan);
        createPlan(newPlan, DAY, occ("G2", newSection, iso(DAY, 9, 0), iso(DAY, 11, 0)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newPlan, 1, 1, "op-rsch")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("oldPlan").get("status").asText()).isEqualTo("CANCELLED");
        JsonNode newPlanBody = body.get("newPlan");
        assertThat(newPlanBody.get("status").asText()).isEqualTo("PUBLISHED");
        // 新计划整体顺移至 13:00-15:00，重排记录场景为 RESCHEDULE
        assertThat(newPlanBody.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(DAY, 13, 0));
        JsonNode rearrangement = newPlanBody.get("rearrangement");
        assertThat(rearrangement.get("opType").asText()).isEqualTo("RESCHEDULE");
        assertThat(rearrangement.get("shiftMinutes").asLong()).isEqualTo(240);
        assertThat(rearrangement.get("segments").get(0).get("oldStartUtc").asText())
                .isEqualTo(iso(DAY, 9, 0));

        // 改签链不受影响，旧计划无重排记录
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", oldPlan))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(chain).get("chain")).hasSize(2);
        MvcResult oldHistory = mvc.perform(get("/api/v1/plans/{key}/rearrangements", oldPlan))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(oldHistory)).isEmpty();
    }

    // ---------- 顺延后冲突 / 无法满足 / 已开始运行 ----------

    @Test
    void publishShiftedIntoConflictFailsWholeOperationWith422() throws Exception {
        String section = key("SEC");
        // 第三方已发布计划占据顺延目标时隙 13:00-15:00
        String thirdParty = key("SCH");
        createPlan(thirdParty, DAY, occ("G9", section, iso(DAY, 13, 0), iso(DAY, 15, 0)));
        publishPlan(thirdParty);
        registerRestriction(key("RST"), section, iso(DAY, 10, 0), iso(DAY, 12, 0), 60);
        String plan = key("SCH");
        createPlan(plan, DAY, occ("G1", section, iso(DAY, 9, 0), iso(DAY, 11, 0)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SECTION_CONFLICT");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(thirdParty);
        // 冲突明细携带顺延后实际冲突时段
        assertThat(detail.get("startUtc").asText()).isEqualTo(iso(DAY, 13, 0));
        assertThat(detail.get("endUtc").asText()).isEqualTo(iso(DAY, 15, 0));

        // 整次失败：计划保持草稿、占用未改、无重排记录（查询不到半成品）
        MvcResult detail2 = mvc.perform(get("/api/v1/plans/{key}", plan))
                .andExpect(status().isOk()).andReturn();
        JsonNode planBody = read(detail2);
        assertThat(planBody.get("status").asText()).isEqualTo("DRAFT");
        assertThat(planBody.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(DAY, 9, 0));
        MvcResult history = mvc.perform(get("/api/v1/plans/{key}/rearrangements", plan))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(history)).isEmpty();
    }

    @Test
    void publishUnsatisfiableRestrictionReturns422WithRequiredValues() throws Exception {
        String section = key("SEC");
        // 限速窗口覆盖全天 08:00-23:00，计划 09:00-21:00 最小间隔 720 分钟，顺延即出运营日
        registerRestriction(key("RST"), section, iso(DAY, 8, 0), iso(DAY, 23, 0), 60);
        String plan = key("SCH");
        createPlan(plan, DAY, occ("G1", section, iso(DAY, 9, 0), iso(DAY, 21, 0)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("RESTRICTION_UNSATISFIABLE");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("minIntervalMinutes").asLong()).isEqualTo(720);
        assertThat(detail.get("requiredShiftMinutes").asLong()).isEqualTo(720);

        MvcResult planDetail = mvc.perform(get("/api/v1/plans/{key}", plan))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(planDetail).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void publishAlreadyStartedPlanCannotBeRearranged() throws Exception {
        String section = key("SEC");
        registerRestriction(key("RST"), section, iso(PAST_DAY, 10, 0), iso(PAST_DAY, 12, 0), 60);
        String plan = key("SCH");
        // 过去运营日的计划首段开始时刻早于当前时间，视为已开始运行
        createPlan(plan, PAST_DAY, occ("G1", section, iso(PAST_DAY, 9, 0), iso(PAST_DAY, 11, 0)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("REARRANGE_NOT_ALLOWED");
        assertThat(error.get("details").get(0).get("scheduleKey").asText()).isEqualTo(plan);

        MvcResult planDetail = mvc.perform(get("/api/v1/plans/{key}", plan))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(planDetail).get("status").asText()).isEqualTo("DRAFT");
    }

    // ---------- 幂等指纹包含限速版本 ----------

    @Test
    void publishIdempotentReplayAndRestrictionVersionInFingerprint() throws Exception {
        String section = key("SEC");
        registerRestriction(key("RST"), section, iso(DAY, 10, 0), iso(DAY, 12, 0), 60);
        String plan = key("SCH");
        createPlan(plan, DAY, occ("G1", section, iso(DAY, 9, 0), iso(DAY, 11, 0)));
        String requestKey = key("REQ");
        String body = actionBody(requestKey, "op-idem");

        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/publish", plan)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放首次完整结果（含重排记录），不重复顺延
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", plan)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        MvcResult history = mvc.perform(get("/api/v1/plans/{key}/rearrangements", plan))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(history)).hasSize(1);

        // 指纹包含限速版本：同区段新登记限速令后同键重放判定为异参 → 409
        registerRestriction(key("RST"), section, iso(DAY, 18, 0), iso(DAY, 19, 0), 80);
        mvc.perform(post("/api/v1/plans/{key}/publish", plan)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    // ---------- 辅助 ----------

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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isOk());
    }

    private void registerRestriction(String restrictionKey, String section, String startIso,
                                     String endIso, int speed) throws Exception {
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"restrictionKey\":\""
                                + restrictionKey + "\",\"sectionId\":\"" + section
                                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso
                                + "\",\"maxSpeedKmh\":" + speed + ",\"operator\":\"op-test\"}"))
                .andExpect(status().isCreated());
    }

    private void revokeRestriction(String restrictionKey) throws Exception {
        mvc.perform(post("/api/v1/restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ")
                                + "\",\"operator\":\"op-test\"}"))
                .andExpect(status().isOk());
    }

    private void reviseRestriction(String restrictionKey, String section, String startIso,
                                   String endIso, int speed) throws Exception {
        mvc.perform(post("/api/v1/restrictions/{key}/revise", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"sectionId\":\""
                                + section + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\""
                                + endIso + "\",\"maxSpeedKmh\":" + speed
                                + ",\"operator\":\"op-test\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 指定运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String actionBody(String requestKey, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\""
                + (operator == null ? "" : ",\"operator\":\"" + operator + "\"") + "}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion,
                                         String operator) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion
                + ",\"operator\":\"" + operator + "\"}";
    }
}
