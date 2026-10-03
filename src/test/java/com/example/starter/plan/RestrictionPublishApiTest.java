package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 限速对发布与改签的影响：整体顺延、重排记录固化、冲突 422、诊断与历史不可改写。
 * 运营日取未来日期（2099-01-01），保证计划尚未开始运行。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestrictionPublishApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2099, 1, 1);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 主流程：限速导致整体顺延 ----------

    @Test
    void publishShiftsWholePlanByMinInterval() throws Exception {
        String section = key("SEC");
        String section2 = key("SEC");
        String restrictionKey = key("RST");
        // 限速 08:00-08:10（左闭右开），最小间隔 5 分钟，需顺延 10 分钟
        createRestriction(restrictionKey, section, iso(8, 0), iso(8, 10), 120);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey,
                occ("G1", section, iso(8, 0), iso(9, 0)),
                occ("G1", section2, iso(9, 0), iso(10, 0)));

        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionJson(key("REQ"), "op-dispatch")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(published);
        assertThat(body.get("status").asText()).isEqualTo("PUBLISHED");
        // 整体顺延 10 分钟：两段一起移动，版本加一
        assertThat(body.get("version").asInt()).isEqualTo(2);
        assertThat(body.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(8, 10));
        assertThat(body.get("occupancies").get(0).get("endUtc").asText()).isEqualTo(iso(9, 10));
        assertThat(body.get("occupancies").get(1).get("startUtc").asText()).isEqualTo(iso(9, 10));
        assertThat(body.get("occupancies").get(1).get("endUtc").asText()).isEqualTo(iso(10, 10));

        // 重排记录固化限速令版本、原时刻与新时刻
        MvcResult rearranges = mvc.perform(get("/api/v1/plans/{key}/rearranges", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode records = read(rearranges);
        assertThat(records).hasSize(1);
        JsonNode record = records.get(0);
        assertThat(record.get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(record.get("shiftMinutes").asInt()).isEqualTo(10);
        assertThat(record.get("operator").asText()).isEqualTo("op-dispatch");
        assertThat(record.get("restrictions")).hasSize(1);
        assertThat(record.get("restrictions").get(0).get("restrictionKey").asText())
                .isEqualTo(restrictionKey);
        assertThat(record.get("restrictions").get(0).get("version").asInt()).isEqualTo(1);
        assertThat(record.get("originalOccupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(8, 0));
        assertThat(record.get("newOccupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(8, 10));

        // 已发布时隙为顺延后的新时刻
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots).get(0).get("startUtc").asText()).isEqualTo(iso(8, 10));
    }

    @Test
    void publishWithoutActiveRestrictionDoesNotShift() throws Exception {
        String section = key("SEC");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8, 0), iso(9, 0)));
        publishPlan(scheduleKey, key("REQ"), null);
        JsonNode plan = getPlan(scheduleKey);
        assertThat(plan.get("version").asInt()).isEqualTo(1);
        assertThat(plan.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(8, 0));

        // 已撤销的限速令不再影响发布
        String section2 = key("SEC");
        String restrictionKey = key("RST");
        createRestriction(restrictionKey, section2, iso(8, 0), iso(9, 0), 100);
        revokeRestriction(restrictionKey);
        String scheduleKey2 = key("SCH");
        createPlan(scheduleKey2, occ("G2", section2, iso(8, 0), iso(9, 0)));
        publishPlan(scheduleKey2, key("REQ"), null);
        JsonNode plan2 = getPlan(scheduleKey2);
        assertThat(plan2.get("version").asInt()).isEqualTo(1);
        assertThat(plan2.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(8, 0));

        MvcResult rearranges = mvc.perform(get("/api/v1/plans/{key}/rearranges", scheduleKey2))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(rearranges)).isEmpty();
    }

    @Test
    void stackedRestrictionsUseLowestSpeedAndUnionWindow() throws Exception {
        String section = key("SEC");
        // 同一区段同一时刻叠加两条限速，生效速度取最低值，影响窗口取并集
        createRestriction(key("RST"), section, iso(8, 0), iso(8, 30), 200);
        createRestriction(key("RST"), section, iso(8, 15), iso(8, 45), 80);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8, 0), iso(9, 0)));

        MvcResult impact = mvc.perform(get("/api/v1/plans/{key}/restriction-impact", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode view = read(impact);
        assertThat(view.get("requiredShiftMinutes").asInt()).isEqualTo(45);
        assertThat(view.get("alreadyStarted").asBoolean()).isFalse();
        JsonNode segment = view.get("segments").get(0);
        assertThat(segment.get("affectedMinutes").asLong()).isEqualTo(45);
        assertThat(segment.get("effectiveMaxSpeedKmh").asInt()).isEqualTo(80);
        assertThat(segment.get("restrictions")).hasSize(2);

        // 诊断只读：再次调用结果一致，计划仍为草稿且版本不变
        MvcResult impactAgain = mvc.perform(
                        get("/api/v1/plans/{key}/restriction-impact", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(impactAgain)).isEqualTo(view);
        JsonNode plan = getPlan(scheduleKey);
        assertThat(plan.get("status").asText()).isEqualTo("DRAFT");
        assertThat(plan.get("version").asInt()).isEqualTo(1);
    }
    // ---------- 失败分支：冲突与越界 422，无半成品 ----------

    @Test
    void shiftConflictWithPublishedPlanReturns422AndLeavesNoPartialState() throws Exception {
        String section = key("SEC");
        // 已发布计划占用 08:15-09:15
        String blocker = key("SCH");
        createPlan(blocker, occ("G9", section, iso(8, 15), iso(9, 15)));
        publishPlan(blocker, key("REQ"), null);

        // 限速 08:00-08:10，本计划顺延 10 分钟后与已发布计划 08:15-09:15 冲突
        createRestriction(key("RST"), section, iso(8, 0), iso(8, 10), 100);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8, 0), iso(9, 0)));

        String publishKey = key("REQ");
        MvcResult conflict = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionJson(publishKey, "op-dispatch")))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(blocker);
        assertThat(detail.get("startUtc").asText()).isEqualTo(iso(8, 10));

        // 无半成品：计划保持草稿、占用未顺延、无重排记录、失败不占幂等键
        JsonNode plan = getPlan(scheduleKey);
        assertThat(plan.get("status").asText()).isEqualTo("DRAFT");
        assertThat(plan.get("version").asInt()).isEqualTo(1);
        assertThat(plan.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(8, 0));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_rearrange WHERE schedule_key = ?",
                Integer.class, scheduleKey)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_key = ?",
                Integer.class, publishKey)).isZero();

        // 冲突解除后同键重试成功（失败不占键）
        mvc.perform(post("/api/v1/plans/{key}/cancel", blocker)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionJson(key("REQ"), null)))
                .andExpect(status().isOk());
        MvcResult retried = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionJson(publishKey, "op-dispatch")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(retried).get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(8, 10));
    }

    @Test
    void shiftBeyondOperationDayReturns422() throws Exception {
        String section = key("SEC");
        // 限速覆盖到运营日末尾附近，任何顺延都会越出运营日
        createRestriction(key("RST"), section, iso(23, 0), iso(23, 45), 60);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(23, 30), iso(23, 50)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionJson(key("REQ"), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("SHIFT_BEYOND_OPERATION_DAY");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(detail.get("differenceMinutes").asLong()).isGreaterThan(0);

        JsonNode plan = getPlan(scheduleKey);
        assertThat(plan.get("status").asText()).isEqualTo("DRAFT");
    }

    // ---------- 改签与历史不可改写 ----------

    @Test
    void rescheduleShiftsNewPlanAndRecordsRearrange() throws Exception {
        String sectionOld = key("SEC");
        String sectionNew = key("SEC");
        String oldKey = key("SCH");
        createPlan(oldKey, occ("G1", sectionOld, iso(12, 0), iso(13, 0)));
        publishPlan(oldKey, key("REQ"), null);

        createRestriction(key("RST"), sectionNew, iso(8, 0), iso(8, 10), 90);
        String newKey = key("SCH");
        createPlan(newKey, occ("G2", sectionNew, iso(8, 0), iso(9, 0)));

        MvcResult rescheduled = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleJson(key("REQ"), newKey, 1, 1, "op-re")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(rescheduled);
        assertThat(body.get("oldPlan").get("status").asText()).isEqualTo("CANCELLED");
        JsonNode newPlan = body.get("newPlan");
        assertThat(newPlan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(newPlan.get("version").asInt()).isEqualTo(2);
        assertThat(newPlan.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(8, 10));

        MvcResult rearranges = mvc.perform(get("/api/v1/plans/{key}/rearranges", newKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(rearranges)).hasSize(1);
        assertThat(read(rearranges).get(0).get("operator").asText()).isEqualTo("op-re");
    }

    @Test
    void rearrangeHistorySurvivesRestrictionReviseAndRevoke() throws Exception {
        String section = key("SEC");
        String restrictionKey = key("RST");
        createRestriction(restrictionKey, section, iso(8, 0), iso(8, 10), 100);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8, 0), iso(9, 0)));
        publishPlan(scheduleKey, key("REQ"), "op-hist");

        // 修订再撤销限速令
        mvc.perform(put("/api/v1/restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(key("REQ"), 1, iso(8, 0), iso(8, 20), 60)))
                .andExpect(status().isOk());
        revokeRestriction(restrictionKey);

        // 已生成的重排记录不被改写：仍固化版本 1 的快照与原/新时刻
        MvcResult rearranges = mvc.perform(get("/api/v1/plans/{key}/rearranges", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode records = read(rearranges);
        assertThat(records).hasSize(1);
        JsonNode record = records.get(0);
        assertThat(record.get("restrictions").get(0).get("version").asInt()).isEqualTo(1);
        assertThat(record.get("restrictions").get(0).get("maxSpeedKmh").asInt()).isEqualTo(100);
        assertThat(record.get("originalOccupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(8, 0));
        assertThat(record.get("newOccupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(8, 10));
        assertThat(record.get("shiftMinutes").asInt()).isEqualTo(10);
    }

    @Test
    void rearrangesAndImpactOfMissingPlanReturn404() throws Exception {
        mvc.perform(get("/api/v1/plans/{key}/rearranges", key("SCH")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/restriction-impact", key("SCH")))
                .andExpect(status().isNotFound());
    }
    // ---------- 辅助 ----------

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("requestKey", key("REQ"));
        node.put("scheduleKey", scheduleKey);
        node.put("opDate", DAY.toString());
        node.putArray("occupancies").addAll(java.util.Arrays.stream(occupancies)
                .map(this::parseJson).toList());
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(node.toString()))
                .andExpect(status().isCreated());
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void publishPlan(String scheduleKey, String requestKey, String operator)
            throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionJson(requestKey, operator)))
                .andExpect(status().isOk());
    }

    private JsonNode getPlan(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private void createRestriction(String restrictionKey, String section,
                                   String startIso, String endIso, int speed) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("requestKey", key("REQ"));
        node.put("restrictionKey", restrictionKey);
        node.put("sectionId", section);
        node.put("startUtc", startIso);
        node.put("endUtc", endIso);
        node.put("maxSpeedKmh", speed);
        node.put("operator", "op-test");
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(node.toString()))
                .andExpect(status().isCreated());
    }

    private void revokeRestriction(String restrictionKey) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("requestKey", key("REQ"));
        node.put("operator", "op-test");
        mvc.perform(post("/api/v1/restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON).content(node.toString()))
                .andExpect(status().isOk());
    }

    private String actionJson(String requestKey, String operator) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("requestKey", requestKey);
        if (operator != null) {
            node.put("operator", operator);
        }
        return node.toString();
    }

    private String rescheduleJson(String requestKey, String newScheduleKey,
                                  int expectedOldVersion, int expectedNewVersion,
                                  String operator) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("requestKey", requestKey);
        node.put("newScheduleKey", newScheduleKey);
        node.put("expectedOldVersion", expectedOldVersion);
        node.put("expectedNewVersion", expectedNewVersion);
        if (operator != null) {
            node.put("operator", operator);
        }
        return node.toString();
    }

    private String reviseJson(String requestKey, int expectedVersion,
                              String startIso, String endIso, int speed) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("requestKey", requestKey);
        node.put("expectedVersion", expectedVersion);
        node.put("startUtc", startIso);
        node.put("endUtc", endIso);
        node.put("maxSpeedKmh", speed);
        node.put("operator", "op-test");
        return node.toString();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }
}