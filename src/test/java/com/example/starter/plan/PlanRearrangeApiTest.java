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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 气象限速触发计划整体顺延（重排）的端到端测试（H2 内存库）。
 * 覆盖：发布/改签顺延主流程与重排记录固化、记录不可改写、422 冲突与不可行回滚、
 * 已开始运行 409、诊断只读与幂等指纹。成功用例使用未来运营日（2099-01-01），
 * 已开始运行用例使用过去运营日（2020-01-01）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanRearrangeApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2099, 1, 1);
    private static final LocalDate PAST_DAY = LocalDate.of(2020, 1, 1);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 发布顺延主流程 ----------

    @Test
    void publishShiftsWholePlanAndPersistsRearrangementRecord() throws Exception {
        String sectionA = key("SEC");
        String sectionB = key("SEC");
        String scheduleKey = key("SCH");
        // 两段占用：仅第一段落入限速时段，重排必须整计划顺延，不能只调整单段
        createPlan(scheduleKey, DAY,
                occ("G1", sectionA, iso(8), iso(9)),
                occ("G1", sectionB, iso(12), iso(13)));
        long restrictionId = registerRestriction(sectionA, iso(8, 30), iso(9, 30), 100);

        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), "dispatcher")))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan = read(published);
        assertThat(plan.get("status").asText()).isEqualTo("PUBLISHED");
        // 顺延改写占用，版本加一
        assertThat(plan.get("version").asInt()).isEqualTo(2);
        JsonNode occs = plan.get("occupancies");
        // 最小可行顺延 90 分钟：第一段 09:30-10:30，第二段同步顺延至 13:30-14:30
        assertThat(occs.get(0).get("startUtc").asText()).isEqualTo(iso(9, 30));
        assertThat(occs.get(0).get("endUtc").asText()).isEqualTo(iso(10, 30));
        assertThat(occs.get(1).get("startUtc").asText()).isEqualTo(iso(13, 30));
        assertThat(occs.get(1).get("endUtc").asText()).isEqualTo(iso(14, 30));

        // 重排记录固化：限速令版本、原计划时刻与新计划时刻
        JsonNode records = read(mvc.perform(get("/api/v1/plans/{key}/rearrangements", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(records).hasSize(1);
        JsonNode record = records.get(0);
        assertThat(record.get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(record.get("opType").asText()).isEqualTo("PUBLISH");
        assertThat(record.get("shiftMinutes").asInt()).isEqualTo(90);
        assertThat(record.get("restrictions")).hasSize(1);
        assertThat(record.get("restrictions").get(0).get("id").asLong()).isEqualTo(restrictionId);
        assertThat(record.get("restrictions").get(0).get("version").asInt()).isEqualTo(1);
        assertThat(record.get("restrictions").get(0).get("maxSpeedKmh").asInt()).isEqualTo(100);
        assertThat(record.get("originalOccupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(8));
        assertThat(record.get("shiftedOccupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(9, 30));
        assertThat(record.get("shiftedOccupancies").get(1).get("startUtc").asText())
                .isEqualTo(iso(13, 30));

        // 生效时隙为顺延后的时刻
        JsonNode slots = read(mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", sectionA))
                .andExpect(status().isOk()).andReturn());
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).get("startUtc").asText()).isEqualTo(iso(9, 30));
    }

    @Test
    void publishWithoutViolationKeepsTimesAndCreatesNoRecord() throws Exception {
        String section = key("SEC");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, DAY, occ("G1", section, iso(8), iso(9)));
        // 限速令在不相干区段上
        registerRestriction(key("SEC"), iso(8), iso(9), 100);

        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan = read(published);
        assertThat(plan.get("version").asInt()).isEqualTo(1);
        assertThat(plan.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(8));

        JsonNode records = read(mvc.perform(get("/api/v1/plans/{key}/rearrangements", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(records).isEmpty();
    }

    @Test
    void revokeDoesNotRewriteRecordAndStopsAffectingLaterPublishes() throws Exception {
        String section = key("SEC");
        String first = key("SCH");
        createPlan(first, DAY, occ("G1", section, iso(8), iso(9)));
        long restrictionId = registerRestriction(section, iso(8, 30), iso(9, 30), 100);
        publish(first, key("REQ"));
        JsonNode before = read(mvc.perform(get("/api/v1/plans/{key}/rearrangements", first))
                .andExpect(status().isOk()).andReturn());
        assertThat(before).hasSize(1);

        // 撤销限速令：已生成的重排记录不改写
        mvc.perform(post("/api/v1/restrictions/{id}/revoke", restrictionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody(key("REQ"))))
                .andExpect(status().isOk());
        JsonNode after = read(mvc.perform(get("/api/v1/plans/{key}/rearrangements", first))
                .andExpect(status().isOk()).andReturn());
        assertThat(after).isEqualTo(before);

        // 撤销后同形状计划发布不再顺延
        String second = key("SCH");
        createPlan(second, DAY, occ("G2", section, iso(8), iso(9)));
        // 与 first 顺延后的 09:30-10:30 不冲突（08:00-09:00 相邻前段）
        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(published).get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(8));
    }

    // ---------- 顺延冲突 422 与回滚 ----------

    @Test
    void shiftConflictWithPublishedPlanReturns422AndRollsBack() throws Exception {
        String section = key("SEC");
        // 第三方先发布 09:30-10:30（登记限速令之前）
        String thirdParty = key("SCH");
        createPlan(thirdParty, DAY, occ("G9", section, iso(9, 30), iso(10, 30)));
        publish(thirdParty, key("REQ"));
        registerRestriction(section, iso(8, 30), iso(9, 30), 100);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, DAY, occ("G1", section, iso(8), iso(9)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SECTION_CONFLICT");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(thirdParty);
        // 冲突明细携带顺延后与第三方冲突的时段
        assertThat(detail.get("startUtc").asText()).isEqualTo(iso(9, 30));
        assertThat(detail.get("endUtc").asText()).isEqualTo(iso(10, 30));

        // 整体回滚：计划仍为草稿、占用保持原时刻、无重排记录
        JsonNode plan = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(plan.get("status").asText()).isEqualTo("DRAFT");
        assertThat(plan.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(8));
        JsonNode records = read(mvc.perform(get("/api/v1/plans/{key}/rearrangements", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(records).isEmpty();
    }

    @Test
    void infeasibleRearrangeReturns422AndRollsBack() throws Exception {
        String section = key("SEC");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, DAY, occ("G1", section, iso(8), iso(9)));
        // 限速持续到运营日结束，任何顺延都无法消除违反
        registerRestrictionOn(section, iso(8),
                DAY.plusDays(1).atStartOfDay(SH).toInstant().toString(), 100);

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("REARRANGE_INFEASIBLE");
        assertThat(error.get("details").get(0).get("sectionId").asText()).isEqualTo(section);

        JsonNode plan = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(plan.get("status").asText()).isEqualTo("DRAFT");
        assertThat(plan.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(8));
    }

    // ---------- 已开始运行不可重排 ----------

    @Test
    void alreadyStartedPlanCannotBeRearranged() throws Exception {
        String section = key("SEC");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, PAST_DAY, occ("G1", section, pastIso(8), pastIso(9)));
        registerRestrictionOn(section, pastIso(8), pastIso(12), 100);

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("PLAN_ALREADY_RUNNING");

        JsonNode plan = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(plan.get("status").asText()).isEqualTo("DRAFT");
    }

    // ---------- 改签顺延 ----------

    @Test
    void rescheduleShiftsNewPlanAndAppendsLink() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        String newKey = key("SCH");
        createPlan(oldKey, DAY, occ("G1", section, iso(8), iso(9)));
        publish(oldKey, key("REQ"));
        // 限速令在旧计划发布之后登记，只影响后续发布/改签
        long restrictionId = registerRestriction(section, iso(9, 15), iso(10, 15), 120);
        createPlan(newKey, DAY, occ("G2", section, iso(9), iso(10)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("oldPlan").get("status").asText()).isEqualTo("CANCELLED");
        JsonNode newPlan = body.get("newPlan");
        assertThat(newPlan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(newPlan.get("version").asInt()).isEqualTo(2);
        // 新计划整体顺延 80 分钟：10:20-11:20
        assertThat(newPlan.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(10, 20));
        assertThat(newPlan.get("occupancies").get(0).get("endUtc").asText())
                .isEqualTo(iso(11, 20));

        // 改签链与重排记录
        JsonNode chain = read(mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", oldKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(chain.get("chain")).hasSize(2);
        JsonNode records = read(mvc.perform(get("/api/v1/plans/{key}/rearrangements", newKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("opType").asText()).isEqualTo("RESCHEDULE");
        assertThat(records.get(0).get("shiftMinutes").asInt()).isEqualTo(80);
        assertThat(records.get(0).get("restrictions").get(0).get("id").asLong())
                .isEqualTo(restrictionId);
    }

    // ---------- 诊断查询（只读） ----------

    @Test
    void impactDiagnosticIsReadOnly() throws Exception {
        String section = key("SEC");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, DAY, occ("G1", section, iso(8), iso(9)));
        long restrictionId = registerRestriction(section, iso(8, 30), iso(9, 30), 100);

        JsonNode impact = read(mvc.perform(get("/api/v1/plans/{key}/restriction-impact", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(impact.get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(impact.get("violation").asBoolean()).isTrue();
        assertThat(impact.get("feasible").asBoolean()).isTrue();
        assertThat(impact.get("requiredShiftMinutes").asInt()).isEqualTo(90);
        JsonNode segment = impact.get("segments").get(0);
        assertThat(segment.get("sectionId").asText()).isEqualTo(section);
        assertThat(segment.get("affectedMinutes").asLong()).isEqualTo(30);
        assertThat(segment.get("requiredMinutes").asLong()).isEqualTo(90);
        assertThat(segment.get("deficitMinutes").asLong()).isEqualTo(60);
        assertThat(segment.get("minLimitSpeedKmh").asInt()).isEqualTo(100);
        assertThat(segment.get("restrictionIds").get(0).asLong()).isEqualTo(restrictionId);

        // 只读：计划仍为草稿、无重排记录
        JsonNode plan = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(plan.get("status").asText()).isEqualTo("DRAFT");
        JsonNode records = read(mvc.perform(get("/api/v1/plans/{key}/rearrangements", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(records).isEmpty();

        // 计划不存在 → 404
        mvc.perform(get("/api/v1/plans/{key}/restriction-impact", key("SCH")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/rearrangements", key("SCH")))
                .andExpect(status().isNotFound());
    }

    @Test
    void impactDiagnosticReportsInfeasible() throws Exception {
        String section = key("SEC");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, DAY, occ("G1", section, iso(8), iso(9)));
        registerRestrictionOn(section, iso(8),
                DAY.plusDays(1).atStartOfDay(SH).toInstant().toString(), 100);

        JsonNode impact = read(mvc.perform(get("/api/v1/plans/{key}/restriction-impact", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(impact.get("violation").asBoolean()).isTrue();
        assertThat(impact.get("feasible").asBoolean()).isFalse();
        assertThat(impact.get("requiredShiftMinutes").isNull()).isTrue();
    }

    // ---------- 幂等指纹 ----------

    @Test
    void publishFingerprintCoversOperatorAndRestrictionVersion() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        createPlan(planA, DAY, occ("G1", section, iso(8), iso(9)));
        String requestKey = key("REQ");
        String body = actionBody(requestKey, "dispatcher-1");
        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/publish", planA)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();

        // 同键同参（含操作者）重放返回首次结果
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", planA)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同操作者 → 409
        mvc.perform(post("/api/v1/plans/{key}/publish", planA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey, "dispatcher-2")))
                .andExpect(status().isConflict());

        // 限速令版本进入指纹：发布后登记新限速令，同键重放判定为异参 409
        String planB = key("SCH");
        createPlan(planB, DAY, occ("G2", key("SEC"), iso(8), iso(9)));
        String keyB = key("REQ");
        mvc.perform(post("/api/v1/plans/{key}/publish", planB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(keyB, null)))
                .andExpect(status().isOk());
        registerRestriction(key("SEC"), iso(8), iso(9), 100);
        MvcResult stale = mvc.perform(post("/api/v1/plans/{key}/publish", planB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(keyB, null)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(stale).get("code").asText()).isEqualTo("IDEMPOTENT_KEY_REUSED");
    }

    // ---------- 辅助 ----------

    private void createPlan(String scheduleKey, LocalDate opDate, String... occupancies)
            throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + opDate
                                + "\",\"occupancies\":[" + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void publish(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey, null)))
                .andExpect(status().isOk());
    }

    private long registerRestriction(String section, String startIso, String endIso, int speed)
            throws Exception {
        return registerRestrictionOn(section, startIso, endIso, speed);
    }

    private long registerRestrictionOn(String section, String startIso, String endIso, int speed)
            throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"sectionId\":\"" + section
                                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso
                                + "\",\"maxSpeedKmh\":" + speed + "}"))
                .andExpect(status().isCreated()).andReturn();
        return read(result).get("id").asLong();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日（2099-01-01）当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    /** 运营日（2099-01-01）当日 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour) {
        return iso(hour, 0);
    }

    /** 过去运营日（2020-01-01）当日 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String pastIso(int hour) {
        return PAST_DAY.atTime(hour, 0).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String actionBody(String requestKey, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\""
                + (operator == null ? "" : ",\"operator\":\"" + operator + "\"") + "}";
    }

    private static String revokeBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }
}
