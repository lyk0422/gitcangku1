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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 气象限速下的发布与改签集成测试（H2 内存库，MODE=MySQL）：
 * 整体顺延与重排记录固化、顺延后时隙冲突 422、已开始运行不可重排、
 * 改签顺延、重排记录不可变、幂等指纹含限速版本。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class PlanShiftApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void resetClock() {
        clock.setInstant(TestClockConfig.DEFAULT_NOW);
        // 清理场景数据：各用例在干净的限速令与计划数据上执行（种子区段与发布锁保留）
        jdbc.update("DELETE FROM rail_plan_rearrangement");
        jdbc.update("DELETE FROM rail_speed_restriction");
        jdbc.update("DELETE FROM rail_plan_reschedule_link");
        jdbc.update("DELETE FROM rail_plan_occupancy");
        jdbc.update("DELETE FROM rail_day_plan");
        jdbc.update("DELETE FROM idempotency_record");
    }

    @Test
    void publishShiftsWholePlanAndRecordsRearrangement() throws Exception {
        String restrictionKey = key("RST");
        registerRestriction(restrictionKey, "SEC-L1-A", iso(0), iso(12), 80);

        // SEC-L1-A 08:00-09:00（100 km/h > 80 违例）+ SEC-L1-B 09:00-10:00（未限速）
        String scheduleKey = key("SCH");
        createPlan(scheduleKey,
                occ("G1", "SEC-L1-A", iso(8), iso(9)),
                occ("G1", "SEC-L1-B", iso(9), iso(10)));

        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan = read(published);
        assertThat(plan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(plan.get("version").asInt()).isEqualTo(2);
        // 整体顺延 240 分钟：两条占用一起平移，不只调整单段
        assertThat(plan.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(12));
        assertThat(plan.get("occupancies").get(0).get("endUtc").asText()).isEqualTo(iso(13));
        assertThat(plan.get("occupancies").get(1).get("startUtc").asText()).isEqualTo(iso(13));
        assertThat(plan.get("occupancies").get(1).get("endUtc").asText()).isEqualTo(iso(14));

        // 重排记录：固化限速令版本、原计划时刻与新计划时刻
        MvcResult rearrangements = mvc.perform(get("/api/v1/plans/{key}/rearrangements",
                        scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode records = read(rearrangements);
        assertThat(records).hasSize(1);
        JsonNode record = records.get(0);
        assertThat(record.get("opType").asText()).isEqualTo("PUBLISH");
        assertThat(record.get("shiftMinutes").asLong()).isEqualTo(240);
        assertThat(record.get("restrictionRefs")).hasSize(1);
        assertThat(record.get("restrictionRefs").get(0).get("restrictionKey").asText())
                .isEqualTo(restrictionKey);
        assertThat(record.get("restrictionRefs").get(0).get("version").asInt()).isEqualTo(1);
        assertThat(record.get("originalOccupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(8));
        assertThat(record.get("shiftedOccupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(12));
        assertThat(record.get("originalOccupancies").get(1).get("startUtc").asText())
                .isEqualTo(iso(9));
        assertThat(record.get("shiftedOccupancies").get(1).get("startUtc").asText())
                .isEqualTo(iso(13));
    }

    @Test
    void publishWithoutViolationKeepsTimesAndNoRecord() throws Exception {
        registerRestriction(key("RST"), "SEC-L1-A", iso(0), iso(12), 80);
        // 100 km / 2 h = 50 km/h，低于限速 80，不违例
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", "SEC-L1-A", iso(8), iso(10)));

        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan = read(published);
        assertThat(plan.get("version").asInt()).isEqualTo(1);
        assertThat(plan.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(8));

        MvcResult rearrangements = mvc.perform(get("/api/v1/plans/{key}/rearrangements",
                        scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(rearrangements)).isEmpty();
    }

    @Test
    void shiftedPlanConflictingWithPublishedReturns422() throws Exception {
        // X 在限速登记前发布 12:00-13:00（登记只影响后续发布/改签）
        String planX = key("SCH");
        createPlan(planX, occ("G1", "SEC-L1-A", iso(12), iso(13)));
        publishPlan(planX);

        registerRestriction(key("RST"), "SEC-L1-A", iso(0), iso(12), 80);

        // Y 08:00-09:00 违例，顺延至 12:00-13:00 后与 X 冲突 → 整次 422
        String planY = key("SCH");
        createPlan(planY, occ("G2", "SEC-L1-A", iso(8), iso(9)));
        MvcResult conflict = mvc.perform(post("/api/v1/plans/{key}/publish", planY)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SECTION_CONFLICT");
        assertThat(detail.get("sectionId").asText()).isEqualTo("SEC-L1-A");
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(planX);
        // 冲突时段为顺延后时刻，稳定返回
        assertThat(detail.get("startUtc").asText()).isEqualTo(iso(12));
        assertThat(detail.get("endUtc").asText()).isEqualTo(iso(13));

        // Y 保持草稿，无重排记录，查不到半成品
        MvcResult after = mvc.perform(get("/api/v1/plans/{key}", planY))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after).get("status").asText()).isEqualTo("DRAFT");
        assertThat(read(after).get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(8));
        MvcResult rearrangements = mvc.perform(get("/api/v1/plans/{key}/rearrangements", planY))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(rearrangements)).isEmpty();
    }

    @Test
    void alreadyRunningPlanCannotBeRearranged() throws Exception {
        // 时钟推进到运营日 08:30（Asia/Shanghai），计划 08:00-09:00 已开始运行
        clock.setInstant(DAY.atTime(8, 30).atZone(SH).toInstant());
        registerRestriction(key("RST"), "SEC-L1-A", iso(0), iso(12), 80);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", "SEC-L1-A", iso(8), iso(9)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("PLAN_ALREADY_RUNNING");

        // 计划保持草稿，无重排记录
        MvcResult after = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after).get("status").asText()).isEqualTo("DRAFT");
        MvcResult rearrangements = mvc.perform(get("/api/v1/plans/{key}/rearrangements",
                        scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(rearrangements)).isEmpty();
    }

    @Test
    void rescheduleAppliesShiftAndRecords() throws Exception {
        // 旧计划在限速登记前发布 08:00-09:00
        String oldKey = key("SCH");
        createPlan(oldKey, occ("G1", "SEC-L1-A", iso(8), iso(9)));
        publishPlan(oldKey);

        String restrictionKey = key("RST");
        registerRestriction(restrictionKey, "SEC-L1-A", iso(0), iso(12), 80);

        // 新草稿同样 08:00-09:00（违例），改签时整体顺延
        String newKey = key("SCH");
        createPlan(newKey, occ("G2", "SEC-L1-A", iso(8), iso(9)));
        MvcResult rescheduled = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"newScheduleKey\":\""
                                + newKey + "\",\"expectedOldVersion\":1,\"expectedNewVersion\":1}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(rescheduled);
        assertThat(body.get("oldPlan").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("newPlan").get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(body.get("newPlan").get("version").asInt()).isEqualTo(2);
        assertThat(body.get("newPlan").get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(12));

        // 改签重排记录固化在新计划上
        MvcResult rearrangements = mvc.perform(get("/api/v1/plans/{key}/rearrangements", newKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode records = read(rearrangements);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("opType").asText()).isEqualTo("RESCHEDULE");
        assertThat(records.get(0).get("shiftMinutes").asLong()).isEqualTo(240);
        assertThat(records.get(0).get("restrictionRefs").get(0).get("restrictionKey").asText())
                .isEqualTo(restrictionKey);

        // 改签链不受影响
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", newKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(chain).get("chain")).hasSize(2);
    }

    @Test
    void rearrangementImmutableAfterReviseAndRevoke() throws Exception {
        String restrictionKey = key("RST");
        registerRestriction(restrictionKey, "SEC-L1-A", iso(0), iso(12), 80);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", "SEC-L1-A", iso(8), iso(9)));
        publishPlan(scheduleKey);

        MvcResult before = mvc.perform(get("/api/v1/plans/{key}/rearrangements", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(before)).hasSize(1);

        // 修订限速令（产生新版本）不改写已生成的重排记录
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/speed-restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"startUtc\":\"" + iso(0)
                                + "\",\"endUtc\":\"" + iso(14) + "\",\"maxSpeedKmh\":60"
                                + ",\"operator\":\"dispatcher-2\"}"))
                .andExpect(status().isOk());
        MvcResult afterRevise = mvc.perform(get("/api/v1/plans/{key}/rearrangements", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(afterRevise)).isEqualTo(read(before));

        // 撤销限速令同样不改写历史证据
        mvc.perform(post("/api/v1/speed-restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ")
                                + "\",\"operator\":\"dispatcher-2\"}"))
                .andExpect(status().isOk());
        MvcResult afterRevoke = mvc.perform(get("/api/v1/plans/{key}/rearrangements", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(afterRevoke)).isEqualTo(read(before));
    }

    @Test
    void publishIdempotencyFingerprintIncludesRestrictionVersion() throws Exception {
        registerRestriction(key("RST"), "SEC-L1-A", iso(0), iso(12), 80);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", "SEC-L1-A", iso(8), iso(9)));

        String requestKey = key("REQ");
        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk()).andReturn();
        // 同键同参（限速版本未变）重放首次完整结果
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 新登记一条限速令改变限速版本快照后，同键重放视为异参 → 409
        registerRestriction(key("RST"), "SEC-L1-A", iso(0), iso(12), 60);
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isConflict());
    }

    // ---------- 辅助 ----------

    private void registerRestriction(String restrictionKey, String sectionId, String startIso,
                                     String endIso, int maxSpeed) throws Exception {
        mvc.perform(post("/api/v1/speed-restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"restrictionKey\":\""
                                + restrictionKey + "\",\"sectionId\":\"" + sectionId
                                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso
                                + "\",\"maxSpeedKmh\":" + maxSpeed
                                + ",\"operator\":\"dispatcher-1\"}"))
                .andExpect(status().isCreated());
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour 点（Asia/Shanghai）的 UTC 时刻。 */
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
}
