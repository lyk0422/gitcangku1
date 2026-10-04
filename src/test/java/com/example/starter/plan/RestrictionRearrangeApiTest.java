package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 限速重排主流程与失败分支测试（H2 内存库）：发布/改签时违反限速则整张计划
 * 按线路最小间隔整体顺延并固化重排记录；冲突整次 422 回滚；已开始运行计划不可重排。
 * 各用例使用唯一 scheduleKey/requestKey/区段 ID，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestrictionRearrangeApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setClockBeforeOperationDay() {
        clock.setInstant(Instant.parse("2026-09-21T00:00:00Z"));
    }

    // ---------- 主流程：整体顺延与重排记录 ----------

    @Test
    void publishShiftsWholePlanAndPersistsRearrangeRecord() throws Exception {
        String section = key("SEC");
        String otherSection = key("SEC");
        register(key("RST"), section, iso(9), iso(10), 60);

        String scheduleKey = key("SCH");
        // 第一段 08:30-09:30 与限速时段相交 30 分钟；第二段不相交，验证整张计划整体顺延
        createPlan(scheduleKey,
                occ("G1", section, iso(8, 30), iso(9, 30)),
                occ("G1", otherSection, iso(10, 30), iso(11)));

        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(published);
        assertThat(body.get("status").asText()).isEqualTo("PUBLISHED");
        // 顺延 90 分钟（18 步 × 5 分钟最小间隔），整张计划全部占用一起顺延
        assertThat(body.get("version").asInt()).isEqualTo(2);
        JsonNode occs = body.get("occupancies");
        assertThat(occs.get(0).get("startUtc").asText()).isEqualTo(iso(10));
        assertThat(occs.get(0).get("endUtc").asText()).isEqualTo(iso(11));
        assertThat(occs.get(1).get("startUtc").asText()).isEqualTo(iso(12));
        assertThat(occs.get(1).get("endUtc").asText()).isEqualTo(iso(12, 30));

        // 重排记录固化限速令版本快照与原/新计划时刻
        MvcResult records = mvc.perform(get("/api/v1/plans/{key}/rearrange-records", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(records);
        assertThat(items).hasSize(1);
        JsonNode record = items.get(0);
        assertThat(record.get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(record.get("restrictionVersion").asInt()).isEqualTo(1);
        assertThat(record.get("sectionId").asText()).isEqualTo(section);
        assertThat(record.get("maxSpeedKmh").asInt()).isEqualTo(60);
        assertThat(record.get("restrictionStartUtc").asText()).isEqualTo(iso(9));
        assertThat(record.get("restrictionEndUtc").asText()).isEqualTo(iso(10));
        assertThat(record.get("originalStartUtc").asText()).isEqualTo(iso(8, 30));
        assertThat(record.get("originalEndUtc").asText()).isEqualTo(iso(9, 30));
        assertThat(record.get("shiftedStartUtc").asText()).isEqualTo(iso(10));
        assertThat(record.get("shiftedEndUtc").asText()).isEqualTo(iso(11));
        assertThat(record.get("shiftMinutes").asInt()).isEqualTo(90);
        assertThat(record.get("affectedMinutes").asInt()).isEqualTo(30);

        // 生效时隙为顺延后的时刻
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        JsonNode slotList = read(slots);
        assertThat(slotList).hasSize(1);
        assertThat(slotList.get(0).get("startUtc").asText()).isEqualTo(iso(10));
    }

    @Test
    void publishWithoutViolationKeepsOriginalTimes() throws Exception {
        String section = key("SEC");
        register(key("RST"), section, iso(9), iso(10), 60);

        String scheduleKey = key("SCH");
        // 相邻不相交（左闭右开）：10:00 紧接限速结束时刻
        createPlan(scheduleKey, occ("G1", section, iso(10), iso(11)));
        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(published);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(10));

        MvcResult records = mvc.perform(get("/api/v1/plans/{key}/rearrange-records", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(records)).isEmpty();
    }

    @Test
    void stackedRestrictionsEachRecordedWithOwnVersion() throws Exception {
        String section = key("SEC");
        String rst1 = key("RST");
        String rst2 = key("RST");
        register(rst1, section, iso(9), iso(10), 120);
        register(rst2, section, iso(9), iso(10), 60);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8, 30), iso(9, 30)));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());

        // 同一占用触发两条叠加限速令，各自固化一条记录
        MvcResult records = mvc.perform(get("/api/v1/plans/{key}/rearrange-records", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(records);
        assertThat(items).hasSize(2);
        java.util.Map<String, Integer> speedByKey = new java.util.HashMap<>();
        items.forEach(item -> speedByKey.put(item.get("restrictionKey").asText(),
                item.get("maxSpeedKmh").asInt()));
        assertThat(speedByKey).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of(rst1, 120, rst2, 60));
    }
    // ---------- 撤销/修订不影响历史，已运行计划不可重排 ----------

    @Test
    void revokedRestrictionDoesNotAffectPublish() throws Exception {
        String section = key("SEC");
        String restrictionKey = key("RST");
        register(restrictionKey, section, iso(9), iso(10), 60);
        mvc.perform(post("/api/v1/restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"operator\":\"op\"}"))
                .andExpect(status().isOk());

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8, 30), iso(9, 30)));
        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(published);
        assertThat(body.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(8, 30));

        MvcResult records = mvc.perform(get("/api/v1/plans/{key}/rearrange-records", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(records)).isEmpty();
    }

    @Test
    void amendDoesNotRewriteExistingRearrangeRecords() throws Exception {
        String section = key("SEC");
        String restrictionKey = key("RST");
        register(restrictionKey, section, iso(9), iso(10), 60);

        String planA = key("SCH");
        createPlan(planA, occ("G1", section, iso(8, 30), iso(9, 30)));
        mvc.perform(post("/api/v1/plans/{key}/publish", planA)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());

        // 修订限速令：版本升至 2，时段改到下午
        mvc.perform(put("/api/v1/restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"expectedVersion\":1"
                                + ",\"sectionId\":\"" + section + "\",\"startUtc\":\"" + iso(14)
                                + "\",\"endUtc\":\"" + iso(15) + "\",\"maxSpeedKmh\":100"
                                + ",\"operator\":\"op\"}"))
                .andExpect(status().isOk());

        // 已生成的重排记录不被改写：仍为版本 1 与原限速时段快照
        MvcResult records = mvc.perform(get("/api/v1/plans/{key}/rearrange-records", planA))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(records);
        assertThat(items).hasSize(1);
        JsonNode record = items.get(0);
        assertThat(record.get("restrictionVersion").asInt()).isEqualTo(1);
        assertThat(record.get("maxSpeedKmh").asInt()).isEqualTo(60);
        assertThat(record.get("restrictionStartUtc").asText()).isEqualTo(iso(9));
        assertThat(record.get("restrictionEndUtc").asText()).isEqualTo(iso(10));

        // 修订后的限速令只按新时段生效：上午占用不再顺延
        String planB = key("SCH");
        createPlan(planB, occ("G2", section, iso(8, 30), iso(9, 30)));
        MvcResult publishedB = mvc.perform(post("/api/v1/plans/{key}/publish", planB)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(publishedB).get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(8, 30));
        MvcResult recordsB = mvc.perform(get("/api/v1/plans/{key}/rearrange-records", planB))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(recordsB)).isEmpty();
    }

    @Test
    void startedPlanIsNotRearranged() throws Exception {
        String section = key("SEC");
        register(key("RST"), section, iso(9), iso(10), 60);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8, 30), iso(9, 30)));
        // 时钟推进到计划首条占用开始之后（09:00 Asia/Shanghai = 01:00Z）：计划已开始运行
        clock.setInstant(Instant.parse("2026-09-22T01:00:00Z"));

        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(published);
        // 不可重排：保持原时刻发布，不产生重排记录
        assertThat(body.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(8, 30));
        assertThat(body.get("version").asInt()).isEqualTo(1);
        MvcResult records = mvc.perform(get("/api/v1/plans/{key}/rearrange-records", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(records)).isEmpty();
    }

    // ---------- 失败分支：整次 422 回滚 ----------

    @Test
    void shiftConflictReturns422AndRollsBack() throws Exception {
        String section = key("SEC");
        register(key("RST"), section, iso(9), iso(10), 60);

        // 阻塞计划已占用顺延目标窗口 10:00-11:00
        String blocker = key("SCH");
        createPlan(blocker, occ("G9", section, iso(10), iso(11)));
        mvc.perform(post("/api/v1/plans/{key}/publish", blocker)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());

        String scheduleKey = key("SCH");
        String publishKey = key("REQ");
        createPlan(scheduleKey, occ("G1", section, iso(8, 30), iso(9, 30)));
        MvcResult conflict = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SECTION_CONFLICT");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(blocker);
        assertThat(detail.get("startUtc").asText()).isEqualTo(iso(10));
        assertThat(detail.get("endUtc").asText()).isEqualTo(iso(11));

        // 整次回滚：计划保持草稿与原时刻，无重排记录
        MvcResult after = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode afterBody = read(after);
        assertThat(afterBody.get("status").asText()).isEqualTo("DRAFT");
        assertThat(afterBody.get("version").asInt()).isEqualTo(1);
        assertThat(afterBody.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(8, 30));
        MvcResult records = mvc.perform(get("/api/v1/plans/{key}/rearrange-records", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(records)).isEmpty();

        // 失败不占键：取消阻塞计划后同键重发成功并顺延
        mvc.perform(post("/api/v1/plans/{key}/cancel", blocker)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        MvcResult retried = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(retried).get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(10));
    }

    @Test
    void shiftBeyondOperationDayReturns422() throws Exception {
        String section = key("SEC");
        register(key("RST"), section, iso(23, 15), iso(23, 50), 60);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(23), iso(23, 45)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("SHIFT_EXCEEDS_OPERATION_DAY");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.has("shiftedEndUtc")).isTrue();
        assertThat(detail.has("operationDayEndUtc")).isTrue();
        assertThat(detail.get("excessMinutes").asInt()).isPositive();

        MvcResult after = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after).get("status").asText()).isEqualTo("DRAFT");
    }
    // ---------- 改签重排 ----------

    @Test
    void rescheduleShiftsNewPlanAndKeepsOldHistory() throws Exception {
        String section = key("SEC");
        String oldSection = key("SEC");
        register(key("RST"), section, iso(9), iso(10), 60);

        String oldKey = key("SCH");
        String newKey = key("SCH");
        createPlan(oldKey, occ("G1", oldSection, iso(8), iso(9)));
        mvc.perform(post("/api/v1/plans/{key}/publish", oldKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        createPlan(newKey, occ("G2", section, iso(8, 30), iso(9, 30)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        // 旧计划按原时刻取消，历史不改写
        assertThat(body.get("oldPlan").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("oldPlan").get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(8));
        // 新计划整体顺延后发布
        JsonNode newPlan = body.get("newPlan");
        assertThat(newPlan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(newPlan.get("version").asInt()).isEqualTo(2);
        assertThat(newPlan.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(10));

        // 重排记录记在新计划上，改签链完整
        MvcResult records = mvc.perform(get("/api/v1/plans/{key}/rearrange-records", newKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(records);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("scheduleKey").asText()).isEqualTo(newKey);
        MvcResult oldRecords = mvc.perform(get("/api/v1/plans/{key}/rearrange-records", oldKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(oldRecords)).isEmpty();
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", oldKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(chain).get("chain")).hasSize(2);
    }

    // ---------- 幂等指纹包含限速版本与操作者 ----------

    @Test
    void publishFingerprintIncludesRestrictionVersionAndOperator() throws Exception {
        String section = key("SEC");
        String restrictionKey = key("RST");
        register(restrictionKey, section, iso(9), iso(10), 60);

        String scheduleKey = key("SCH");
        String publishKey = key("REQ");
        createPlan(scheduleKey, occ("G1", section, iso(8, 30), iso(9, 30)));
        String body = "{\"requestKey\":\"" + publishKey + "\",\"operator\":\"dispatcher-7\"}";
        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();

        // 同键同参（含操作者与限速版本）重放返回首次完整结果
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同操作者 → 409
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + publishKey + "\",\"operator\":\"other\"}"))
                .andExpect(status().isConflict());

        // 限速令修订后版本变化，同键重放判定为异参 → 409
        mvc.perform(put("/api/v1/restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"expectedVersion\":1"
                                + ",\"sectionId\":\"" + section + "\",\"startUtc\":\"" + iso(9)
                                + "\",\"endUtc\":\"" + iso(10) + "\",\"maxSpeedKmh\":80"
                                + ",\"operator\":\"op\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    // ---------- 辅助 ----------

    private void register(String restrictionKey, String section, String startIso, String endIso,
                          int speed) throws Exception {
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"restrictionKey\":\""
                                + restrictionKey + "\",\"sectionId\":\"" + section
                                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso
                                + "\",\"maxSpeedKmh\":" + speed + ",\"operator\":\"op\"}"))
                .andExpect(status().isCreated());
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour) {
        return iso(hour, 0);
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }
}