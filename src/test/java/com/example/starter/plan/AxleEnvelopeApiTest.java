package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 发布/改签的车轴局部集中承载（轴重包络）核验端到端测试（H2 内存库）。
 * 覆盖题干合成样例、单轴与三轴滑窗越限 422、档案缺失 404、整次不写占用、
 * 发布冻结快照、档案更新使已发布计划非法 409。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AxleEnvelopeApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 题干合成样例 ----------

    @Test
    void syntheticWindowViolationRejectedThenFixedByLastAxle() throws Exception {
        // 合成轴重 4、8、4、8 吨；单轴限 10 吨、连续 3 轴限 18 吨
        String trainNo = key("TRN");
        String section = key("SEC");
        createTrainProfile(trainNo, "[4000,8000,4000,8000]");
        createSectionProfile(section, 10000, 18000);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(trainNo, section, iso(8), iso(9)));

        // 第二窗 8+4+8=20 吨 > 18 吨 → 整单 422，给出区段与轴序号（窗口起点 1）
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("AXLE_LIMIT_EXCEEDED");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("WINDOW3_EXCEEDED");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("windowStart").asInt()).isEqualTo(1);
        assertThat(detail.get("sumKg").asLong()).isEqualTo(20000L);

        // 整次不写占用：计划保持草稿、无生效时隙、无快照
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
        assertThat(snapshotCount()).isZero();

        // 改末轴为 6 吨后合法：窗口 8+4+6=18 吨 ≤ 18 吨
        mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("RID"), 1, "[4000,8000,4000,6000]")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        assertThat(planStatus(scheduleKey)).isEqualTo("PUBLISHED");
    }

    // ---------- 越限 422 ----------

    @Test
    void publishRejectsSingleAxleExceeded() throws Exception {
        String trainNo = key("TRN");
        String section = key("SEC");
        createTrainProfile(trainNo, "[12000,4000]");
        createSectionProfile(section, 10000, 30000);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(trainNo, section, iso(8), iso(9)));

        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode detail = read(rejected).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SINGLE_AXLE_EXCEEDED");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("axleIndex").asInt()).isEqualTo(0);
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
    }

    @Test
    void publishWithTwoAxlesSkipsWindowCheck() throws Exception {
        // 少于 3 轴只查单轴：窗口上限设为 1kg 也不影响 2 轴列车
        String trainNo = key("TRN");
        String section = key("SEC");
        createTrainProfile(trainNo, "[9000,9000]");
        createSectionProfile(section, 10000, 1);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(trainNo, section, iso(8), iso(9)));
        publishPlan(scheduleKey);
        assertThat(planStatus(scheduleKey)).isEqualTo("PUBLISHED");
    }

    // ---------- 档案缺失 404 ----------

    @Test
    void publishMissingProfilesReturns404() throws Exception {
        String trainNo = key("TRN");
        String section = key("SEC");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(trainNo, section, iso(8), iso(9)));

        // 列车档案缺失
        MvcResult missingTrain = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(read(missingTrain).get("code").asText()).isEqualTo("TRAIN_PROFILE_NOT_FOUND");

        // 区段档案缺失
        createTrainProfile(trainNo, "[4000,4000]");
        MvcResult missingSection = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(read(missingSection).get("code").asText())
                .isEqualTo("SECTION_PROFILE_NOT_FOUND");
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
    }

    // ---------- 发布冻结 ----------

    @Test
    void publishFreezesAxleSequenceLimitsAndVersions() throws Exception {
        String trainNo = key("TRN");
        String section = key("SEC");
        createTrainProfile(trainNo, "[4000,8000,4000,6000]");
        createSectionProfile(section, 10000, 18000);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(trainNo, section, iso(8), iso(9)));
        publishPlan(scheduleKey);

        // 快照冻结当时轴序列、限值与档案版本
        java.util.Map<String, Object> snapshot = jdbc.queryForMap(
                "SELECT s.axle_weights_json, s.train_profile_version, s.section_profile_version,"
                        + " s.single_axle_limit_kg, s.window3_limit_kg"
                        + " FROM rail_plan_axle_snapshot s"
                        + " JOIN rail_day_plan p ON p.id = s.plan_id WHERE p.schedule_key = ?",
                scheduleKey);
        assertThat(snapshot.get("axle_weights_json").toString().replace(" ", ""))
                .isEqualTo("[4000,8000,4000,6000]");
        assertThat(((Number) snapshot.get("train_profile_version")).intValue()).isEqualTo(1);
        assertThat(((Number) snapshot.get("section_profile_version")).intValue()).isEqualTo(1);
        assertThat(((Number) snapshot.get("single_axle_limit_kg")).longValue()).isEqualTo(10000L);
        assertThat(((Number) snapshot.get("window3_limit_kg")).longValue()).isEqualTo(18000L);

        // 后续合法修改（仍满足已发布计划）不改写历史快照
        mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("RID"), 1, "[4000,4000,4000,4000]")))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/section-limit-profiles/{sectionId}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionUpdateBody(key("RID"), 1, 11000, 19000)))
                .andExpect(status().isOk());
        java.util.Map<String, Object> after = jdbc.queryForMap(
                "SELECT s.axle_weights_json, s.train_profile_version, s.section_profile_version"
                        + " FROM rail_plan_axle_snapshot s"
                        + " JOIN rail_day_plan p ON p.id = s.plan_id WHERE p.schedule_key = ?",
                scheduleKey);
        assertThat(after.get("axle_weights_json").toString().replace(" ", ""))
                .isEqualTo("[4000,8000,4000,6000]");
        assertThat(((Number) after.get("train_profile_version")).intValue()).isEqualTo(1);
        assertThat(((Number) after.get("section_profile_version")).intValue()).isEqualTo(1);
    }

    // ---------- 档案更新使已发布计划非法 409 ----------

    @Test
    void trainProfileUpdateInvalidatingPublishedPlanRejected409() throws Exception {
        String trainNo = key("TRN");
        String section = key("SEC");
        createTrainProfile(trainNo, "[4000,8000,4000,6000]");
        createSectionProfile(section, 10000, 18000);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(trainNo, section, iso(8), iso(9)));
        publishPlan(scheduleKey);

        // 改为 4、8、4、8 吨使第二窗 20 吨越限 → 409，版本不变
        MvcResult rejected = mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("RID"), 1, "[4000,8000,4000,8000]")))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("PROFILE_UPDATE_CONFLICT");
        assertThat(error.get("details").get(0).get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(error.get("details").get(0).get("sectionId").asText()).isEqualTo(section);
        assertThat(trainProfileVersion(trainNo)).isEqualTo(1);

        // 需先取消相关计划，不自动减载；取消后同一更新合法
        cancelPlan(scheduleKey);
        mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("RID"), 1, "[4000,8000,4000,8000]")))
                .andExpect(status().isOk());
        assertThat(trainProfileVersion(trainNo)).isEqualTo(2);
    }

    @Test
    void sectionProfileUpdateInvalidatingPublishedPlanRejected409() throws Exception {
        String trainNo = key("TRN");
        String section = key("SEC");
        createTrainProfile(trainNo, "[9000,9000]");
        createSectionProfile(section, 10000, 30000);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(trainNo, section, iso(8), iso(9)));
        publishPlan(scheduleKey);

        // 单轴上限降至 8 吨使已发布计划非法 → 409，版本不变
        MvcResult rejected = mvc.perform(put("/api/v1/section-limit-profiles/{sectionId}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionUpdateBody(key("RID"), 1, 8000, 30000)))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("PROFILE_UPDATE_CONFLICT");
        assertThat(error.get("details").get(0).get("type").asText())
                .isEqualTo("SINGLE_AXLE_EXCEEDED");
        assertThat(sectionProfileVersion(section)).isEqualTo(1);

        // 取消相关计划后更新合法
        cancelPlan(scheduleKey);
        mvc.perform(put("/api/v1/section-limit-profiles/{sectionId}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionUpdateBody(key("RID"), 1, 8000, 30000)))
                .andExpect(status().isOk());
        assertThat(sectionProfileVersion(section)).isEqualTo(2);
    }

    // ---------- 改签核验同一完整后态 ----------

    @Test
    void rescheduleChecksAllAxleWindowsOfNewDraft() throws Exception {
        String section = key("SEC");
        createSectionProfile(section, 10000, 18000);
        String oldTrain = key("TRN");
        createTrainProfile(oldTrain, "[4000,4000]");
        String oldKey = key("SCH");
        createPlan(oldKey, occ(oldTrain, section, iso(8), iso(9)));
        publishPlan(oldKey);

        // 新草稿列车 4、8、4、8 吨，第二窗 20 吨越限 → 改签 422 且整体回滚
        String newTrain = key("TRN");
        createTrainProfile(newTrain, "[4000,8000,4000,8000]");
        String newKey = key("SCH");
        createPlan(newKey, occ(newTrain, section, iso(8), iso(9)));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(rejected).get("code").asText()).isEqualTo("AXLE_LIMIT_EXCEEDED");
        assertThat(planStatus(oldKey)).isEqualTo("PUBLISHED");
        assertThat(planStatus(newKey)).isEqualTo("DRAFT");

        // 修正新草稿列车末轴为 6 吨后改签成功
        mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", newTrain)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("RID"), 1, "[4000,8000,4000,6000]")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isOk());
        assertThat(planStatus(oldKey)).isEqualTo("CANCELLED");
        assertThat(planStatus(newKey)).isEqualTo("PUBLISHED");
    }

    @Test
    void rescheduleMissingProfileReturns404() throws Exception {
        String section = key("SEC");
        createSectionProfile(section, 10000, 18000);
        String oldTrain = key("TRN");
        createTrainProfile(oldTrain, "[4000,4000]");
        String oldKey = key("SCH");
        createPlan(oldKey, occ(oldTrain, section, iso(8), iso(9)));
        publishPlan(oldKey);

        // 新草稿列车无轴重档案 → 404，旧计划不受影响
        String newKey = key("SCH");
        createPlan(newKey, occ(key("TRN"), section, iso(8), iso(9)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("TRAIN_PROFILE_NOT_FOUND");
        assertThat(planStatus(oldKey)).isEqualTo("PUBLISHED");
        assertThat(planStatus(newKey)).isEqualTo("DRAFT");
    }

    // ---------- 辅助 ----------

    private void createTrainProfile(String trainNo, String weightsJson) throws Exception {
        mvc.perform(post("/api/v1/train-axle-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key("RID") + "\",\"operator\":\"dispatcher\""
                                + ",\"trainNo\":\"" + trainNo + "\",\"axleWeightsKg\":"
                                + weightsJson + "}"))
                .andExpect(status().isCreated());
    }

    private void createSectionProfile(String sectionId, long single, long window3)
            throws Exception {
        mvc.perform(post("/api/v1/section-limit-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key("RID") + "\",\"operator\":\"dispatcher\""
                                + ",\"sectionId\":\"" + sectionId + "\",\"singleAxleLimitKg\":"
                                + single + ",\"window3LimitKg\":" + window3 + "}"))
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

    private void cancelPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    private String planStatus(String scheduleKey) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(detail).get("status").asText();
    }

    private int trainProfileVersion(String trainNo) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/train-axle-profiles/{trainNo}", trainNo))
                .andExpect(status().isOk()).andReturn();
        return read(detail).get("version").asInt();
    }

    private int sectionProfileVersion(String sectionId) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/section-limit-profiles/{sectionId}", sectionId))
                .andExpect(status().isOk()).andReturn();
        return read(detail).get("version").asInt();
    }

    private int snapshotCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_axle_snapshot", Integer.class);
        return count == null ? 0 : count;
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

    private static String trainUpdateBody(String requestId, int expectedVersion,
                                          String weightsJson) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"dispatcher\""
                + ",\"expectedVersion\":" + expectedVersion
                + ",\"axleWeightsKg\":" + weightsJson + "}";
    }

    private static String sectionUpdateBody(String requestId, int expectedVersion, long single,
                                            long window3) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"dispatcher\""
                + ",\"expectedVersion\":" + expectedVersion + ",\"singleAxleLimitKg\":" + single
                + ",\"window3LimitKg\":" + window3 + "}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }
}
