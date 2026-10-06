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
import java.util.List;
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
 * 车轴局部集中承载核验的端到端测试（H2 内存库）：主流程、越限 422、档案缺失 404、
 * 发布冻结快照与精确求和边界。各用例使用唯一业务键隔离数据。
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

    // ---------- 主流程与冻结快照 ----------

    @Test
    void publishWithRegisteredProfilesSucceedsAndFreezesSnapshot() throws Exception {
        String train = key("TRAIN");
        String section = key("SEC");
        registerTrain(train, "[4000,8000,4000,6000]");
        registerSection(section, 10000, 18000);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(train, section, iso(8), iso(9)));
        publishPlan(scheduleKey, key("REQ"));

        // 发布冻结当时轴序列与区段限值、版本
        String planIdSubquery = "(SELECT id FROM rail_day_plan WHERE schedule_key = ?)";
        List<Long> frozenWeights = jdbc.queryForList(
                "SELECT weight_kg FROM rail_plan_frozen_axle WHERE plan_id = "
                        + planIdSubquery + " ORDER BY seq", Long.class, scheduleKey);
        assertThat(frozenWeights).containsExactly(4000L, 8000L, 4000L, 6000L);
        Integer frozenTrainVersion = jdbc.queryForObject(
                "SELECT profile_version FROM rail_plan_frozen_axle WHERE plan_id = "
                        + planIdSubquery + " AND seq = 0", Integer.class, scheduleKey);
        assertThat(frozenTrainVersion).isEqualTo(1);
        Long frozenSingle = jdbc.queryForObject(
                "SELECT single_axle_limit_kg FROM rail_plan_frozen_section WHERE plan_id = "
                        + planIdSubquery, Long.class, scheduleKey);
        Long frozenWindow = jdbc.queryForObject(
                "SELECT window3_limit_kg FROM rail_plan_frozen_section WHERE plan_id = "
                        + planIdSubquery, Long.class, scheduleKey);
        Integer frozenSectionVersion = jdbc.queryForObject(
                "SELECT profile_version FROM rail_plan_frozen_section WHERE plan_id = "
                        + planIdSubquery, Integer.class, scheduleKey);
        assertThat(frozenSingle).isEqualTo(10000L);
        assertThat(frozenWindow).isEqualTo(18000L);
        assertThat(frozenSectionVersion).isEqualTo(1);

        // 后续合法修改（末轴 6000→5000，已发布计划仍合法）成功且不改写历史快照
        mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("RID"), "dispatcher-1", 1,
                                "[4000,8000,4000,5000]")))
                .andExpect(status().isOk());
        List<Long> frozenAfter = jdbc.queryForList(
                "SELECT weight_kg FROM rail_plan_frozen_axle WHERE plan_id = "
                        + planIdSubquery + " ORDER BY seq", Long.class, scheduleKey);
        assertThat(frozenAfter).containsExactly(4000L, 8000L, 4000L, 6000L);
    }

    // ---------- 题干合成算例：4、8、4、8 吨，单轴限 10 吨、连续 3 轴限 18 吨 ----------

    @Test
    void secondWindowExceedRejectsWholePlanThenFixedLastAxlePasses() throws Exception {
        String train = key("TRAIN");
        String section = key("SEC");
        registerTrain(train, "[4000,8000,4000,8000]");
        registerSection(section, 10000, 18000);

        String scheduleKey = key("SCH");
        String publishKey = key("REQ");
        createPlan(scheduleKey, occ(train, section, iso(8), iso(9)));

        // 第二窗 8+4+8=20 吨 > 18 吨，整单拒绝
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("AXLE_LIMIT_EXCEEDED");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("WINDOW_3");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("windowStartIndex").asInt()).isEqualTo(1);
        assertThat(detail.get("actualKg").asLong()).isEqualTo(20000L);
        assertThat(detail.get("limitKg").asLong()).isEqualTo(18000L);

        // 整次不写占用：计划保持草稿，无生效时隙
        MvcResult detail1 = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail1).get("status").asText()).isEqualTo("DRAFT");
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).isEmpty();

        // 改末轴为 6 吨后合法：两窗 16、18 均不越限；发布失败不占键，同键重发成功
        mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("RID"), "dispatcher-1", 1,
                                "[4000,8000,4000,6000]")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isOk());
    }

    @Test
    void singleAxleExceedRejectedWithAxleIndex() throws Exception {
        String train = key("TRAIN");
        String section = key("SEC");
        registerTrain(train, "[4000,11000,4000]");
        registerSection(section, 10000, 100000);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(train, section, iso(8), iso(9)));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode detail = read(rejected).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SINGLE_AXLE");
        assertThat(detail.get("axleIndex").asInt()).isEqualTo(1);
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
    }

    @Test
    void fewerThanThreeAxlesOnlySingleAxleChecked() throws Exception {
        String train = key("TRAIN");
        String section = key("SEC");
        // 两轴各 9 吨，单轴限 10 吨合法；三轴窗限设为 1 千克，少于 3 轴不检查滑窗
        registerTrain(train, "[9000,9000]");
        registerSection(section, 10000, 1);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(train, section, iso(8), iso(9)));
        publishPlan(scheduleKey, key("REQ"));
    }

    // ---------- 档案缺失 404 ----------

    @Test
    void missingProfileReturns404() throws Exception {
        // 仅列车档案：区段档案缺失 → 404
        String trainOnly = key("TRAIN");
        registerTrain(trainOnly, "[4000,4000]");
        String planA = key("SCH");
        createPlan(planA, occ(trainOnly, key("SEC"), iso(8), iso(9)));
        MvcResult missingSection = mvc.perform(post("/api/v1/plans/{key}/publish", planA)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(read(missingSection).get("code").asText()).isEqualTo("SECTION_LIMIT_NOT_FOUND");

        // 仅区段档案：列车档案缺失 → 404
        String sectionOnly = key("SEC");
        registerSection(sectionOnly, 10000, 18000);
        String planB = key("SCH");
        createPlan(planB, occ(key("TRAIN"), sectionOnly, iso(8), iso(9)));
        MvcResult missingTrain = mvc.perform(post("/api/v1/plans/{key}/publish", planB)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(read(missingTrain).get("code").asText()).isEqualTo("TRAIN_PROFILE_NOT_FOUND");

        // 档案资源本身缺失 → 404
        mvc.perform(get("/api/v1/train-axle-profiles/{trainNo}", key("TRAIN")))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", key("TRAIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("RID"), "dispatcher-1", 1, "[4000,4000]")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/section-axle-limits/{sectionId}", key("SEC")))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/section-axle-limits/{sectionId}", key("SEC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionUpdateBody(key("RID"), "dispatcher-1", 1, 10000, 18000)))
                .andExpect(status().isNotFound());
    }

    // ---------- 改签同样核验轴窗 ----------

    @Test
    void rescheduleChecksAxleEnvelopeAndFreezesNewSnapshot() throws Exception {
        String train = key("TRAIN");
        String section = key("SEC");
        registerTrain(train, "[4000,8000,4000,8000]");
        registerSection(section, 10000, 18000);

        // 旧计划用合法轴重发布（先改档案为合法值再发布）
        mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("RID"), "dispatcher-1", 1,
                                "[4000,8000,4000,6000]")))
                .andExpect(status().isOk());
        String oldPlan = key("SCH");
        createPlan(oldPlan, occ(train, section, iso(8), iso(9)));
        publishPlan(oldPlan, key("REQ"));

        // 新草稿沿用同一列车；把档案改回越限值前会先使旧计划非法，
        // 因此另用越限列车登记新档案构造非法新草稿
        String badTrain = key("TRAIN");
        registerTrain(badTrain, "[4000,8000,4000,8000]");
        String newPlan = key("SCH");
        createPlan(newPlan, occ(badTrain, section, iso(10), iso(11)));

        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"newScheduleKey\":\""
                                + newPlan + "\",\"expectedOldVersion\":1,\"expectedNewVersion\":1}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(rejected).get("code").asText()).isEqualTo("AXLE_LIMIT_EXCEEDED");

        // 整体回滚：旧计划仍发布，新计划仍草稿
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", oldPlan))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("PUBLISHED");
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", newPlan))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("DRAFT");

        // 修正新草稿列车档案后改签成功，并冻结新计划快照
        mvc.perform(put("/api/v1/train-axle-profiles/{trainNo}", badTrain)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("RID"), "dispatcher-1", 1,
                                "[4000,8000,4000,6000]")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"newScheduleKey\":\""
                                + newPlan + "\",\"expectedOldVersion\":1,\"expectedNewVersion\":1}"))
                .andExpect(status().isOk());
        Integer frozenCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_frozen_axle"
                        + " WHERE plan_id = (SELECT id FROM rail_day_plan WHERE schedule_key = ?)",
                Integer.class, newPlan);
        assertThat(frozenCount).isEqualTo(4);
    }

    // ---------- 精确求和：边界相等放行，long 溢出不得放行 ----------

    @Test
    void windowSumExactBoundaryAndNoLongOverflow() throws Exception {
        // 三窗和恰等于上限：合法
        String exactTrain = key("TRAIN");
        String exactSection = key("SEC");
        registerTrain(exactTrain, "[6000,6000,6000]");
        registerSection(exactSection, 10000, 18000);
        String exactPlan = key("SCH");
        createPlan(exactPlan, occ(exactTrain, exactSection, iso(8), iso(9)));
        publishPlan(exactPlan, key("REQ"));

        // 三轴均为 Long.MAX：单轴不越限，但三轴和精确计算远超上限，不得溢出后放行
        String bigTrain = key("TRAIN");
        String bigSection = key("SEC");
        registerTrain(bigTrain,
                "[9223372036854775807,9223372036854775807,9223372036854775807]");
        registerSection(bigSection, Long.MAX_VALUE, Long.MAX_VALUE);
        String bigPlan = key("SCH");
        createPlan(bigPlan, occ(bigTrain, bigSection, iso(8), iso(9)));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", bigPlan)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode detail = read(rejected).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("WINDOW_3");
        assertThat(detail.get("actualKg").asText())
                .isEqualTo("27670116110564327421");
    }

    // ---------- 辅助 ----------

    private void registerTrain(String trainNo, String weightsJson) throws Exception {
        mvc.perform(post("/api/v1/train-axle-profiles/{trainNo}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainCreateBody(key("RID"), "dispatcher-1", weightsJson)))
                .andExpect(status().isCreated());
    }

    private void registerSection(String sectionId, long singleLimit, long window3Limit)
            throws Exception {
        mvc.perform(post("/api/v1/section-axle-limits/{sectionId}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionCreateBody(key("RID"), "dispatcher-1",
                                singleLimit, window3Limit)))
                .andExpect(status().isCreated());
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, occupancies)))
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

    static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String createBody(String requestKey, String scheduleKey, String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":[" + String.join(",", occupancies)
                + "]}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    static String trainCreateBody(String requestId, String operator, String weightsJson) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"axleWeightsKg\":" + weightsJson + "}";
    }

    static String trainUpdateBody(String requestId, String operator, int expectedVersion,
                                  String weightsJson) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"expectedVersion\":" + expectedVersion
                + ",\"axleWeightsKg\":" + weightsJson + "}";
    }

    static String sectionCreateBody(String requestId, String operator, long singleLimit,
                                    long window3Limit) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"singleAxleLimitKg\":" + singleLimit
                + ",\"window3LimitKg\":" + window3Limit + "}";
    }

    static String sectionUpdateBody(String requestId, String operator, int expectedVersion,
                                    long singleLimit, long window3Limit) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"expectedVersion\":" + expectedVersion
                + ",\"singleAxleLimitKg\":" + singleLimit
                + ",\"window3LimitKg\":" + window3Limit + "}";
    }
}
