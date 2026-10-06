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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 应急制动包络端到端测试（H2 内存库）：精确边界判定、缺档案 404、越限 422、
 * 原无速度占用不适用、成功冻结快照且后续档案修改不改写历史。
 * 各用例使用唯一业务键，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EnvelopeApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 合成样例：v=10、t=1、a=2，所需距离 35 米 ----------

    @Test
    void publishSucceedsAtExactBoundaryAndFreezesSnapshot() throws Exception {
        String trainNo = key("TRN");
        String section = key("SEC");
        createTrainProfile(trainNo, "1", "2");
        createSectionProfile(section, "35");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occSpeed(trainNo, section, iso(8), iso(9), "10"));

        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());

        // 冻结速度、参数与精确判定依据：lhs = 2*2*10*1 + 100 = 140，rhs = 2*2*35 = 140
        JsonNode snapshots = read(mvc.perform(
                        get("/api/v1/plans/{key}/envelope-snapshots", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(snapshots).hasSize(1);
        JsonNode snap = snapshots.get(0);
        assertThat(snap.get("trainNo").asText()).isEqualTo(trainNo);
        assertThat(snap.get("sectionId").asText()).isEqualTo(section);
        assertThat(snap.get("speed").decimalValue()).isEqualByComparingTo("10");
        assertThat(snap.get("reactionTime").decimalValue()).isEqualByComparingTo("1");
        assertThat(snap.get("baseDeceleration").decimalValue()).isEqualByComparingTo("2");
        assertThat(snap.get("protectionDistance").decimalValue()).isEqualByComparingTo("35");
        assertThat(snap.get("trainProfileVersion").asInt()).isEqualTo(1);
        assertThat(snap.get("sectionProfileVersion").asInt()).isEqualTo(1);
        assertThat(snap.get("lhsScaled").decimalValue()).isEqualByComparingTo("140");
        assertThat(snap.get("rhsScaled").decimalValue()).isEqualByComparingTo("140");
    }

    @Test
    void publishRejectsWhenDistanceShortByOneMilli() throws Exception {
        String trainNo = key("TRN");
        String section = key("SEC");
        createTrainProfile(trainNo, "1", "2");
        createSectionProfile(section, "34.999");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occSpeed(trainNo, section, iso(8), iso(9), "10"));

        // d=34.999 < 35，整单拒绝并给出列车及区段
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("ENVELOPE_EXCEEDED");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("trainNo").asText()).isEqualTo(trainNo);
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);

        // 整次发布不生效：计划保持草稿，无快照
        MvcResult detail2 = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail2).get("status").asText()).isEqualTo("DRAFT");
        JsonNode snapshots = read(mvc.perform(
                        get("/api/v1/plans/{key}/envelope-snapshots", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(snapshots).isEmpty();
    }

    @Test
    void exactCrossMultiplicationWithoutRoundingDivision() throws Exception {
        // v=1、t=0.001、a=3：所需距离 = 0.001 + 1/6 = 0.167666...（无限循环，不可先舍入）
        String trainNo = key("TRN");
        createTrainProfile(trainNo, "0.001", "3");
        // d=0.167：精确判定 0.167666... > 0.167 → 越限（截断舍入会误判通过）
        String shortSection = key("SEC");
        createSectionProfile(shortSection, "0.167");
        String rejected = key("SCH");
        createPlan(rejected, occSpeed(trainNo, shortSection, iso(8), iso(9), "1"));
        mvc.perform(post("/api/v1/plans/{key}/publish", rejected)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity());
        // d=0.168：精确判定 0.167666... <= 0.168 → 通过
        String okSection = key("SEC");
        createSectionProfile(okSection, "0.168");
        String accepted = key("SCH");
        createPlan(accepted, occSpeed(trainNo, okSection, iso(8), iso(9), "1"));
        mvc.perform(post("/api/v1/plans/{key}/publish", accepted)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    // ---------- 缺档案 404 ----------

    @Test
    void publishMissingProfileReturns404() throws Exception {
        String trainNo = key("TRN");
        String section = key("SEC");
        // 列车与区段档案均缺失 → 404（列车档案）
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occSpeed("G1", section, iso(8), iso(9), "10"));
        MvcResult missingTrain = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(read(missingTrain).get("code").asText()).isEqualTo("TRAIN_PROFILE_NOT_FOUND");

        // 仅有列车档案，缺区段档案 → 404（区段档案）
        createTrainProfile(trainNo, "1", "2");
        String other = key("SCH");
        createPlan(other, occSpeed(trainNo, section, iso(8), iso(9), "10"));
        MvcResult missingSection = mvc.perform(post("/api/v1/plans/{key}/publish", other)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(read(missingSection).get("code").asText())
                .isEqualTo("SECTION_PROFILE_NOT_FOUND");
        // 失败不占键、不写快照，两计划均保持草稿
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("DRAFT");
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", other))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("DRAFT");
    }

    // ---------- 原无速度占用不适用 ----------

    @Test
    void legacyOccupancyWithoutSpeedNotApplicable() throws Exception {
        // 无任何档案，原无速度占用照常发布（不适用包络判定，不能补零冒充）
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        JsonNode snapshots = read(mvc.perform(
                        get("/api/v1/plans/{key}/envelope-snapshots", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(snapshots).isEmpty();

        // 混合计划：仅带速度占用参与判定并冻结，原无速度占用保持原接口（speed 为 null）
        String trainNo = key("TRN");
        String section = key("SEC");
        createTrainProfile(trainNo, "1", "2");
        createSectionProfile(section, "35");
        String mixed = key("SCH");
        createPlan(mixed,
                occ("G0", key("SEC"), iso(8), iso(9)),
                occSpeed(trainNo, section, iso(9), iso(10), "10"));
        mvc.perform(post("/api/v1/plans/{key}/publish", mixed)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        JsonNode mixedDetail = read(mvc.perform(get("/api/v1/plans/{key}", mixed))
                .andExpect(status().isOk()).andReturn());
        assertThat(mixedDetail.get("occupancies").get(0).get("speed").isNull()).isTrue();
        assertThat(mixedDetail.get("occupancies").get(1).get("speed").decimalValue())
                .isEqualByComparingTo("10");
        JsonNode mixedSnapshots = read(mvc.perform(
                        get("/api/v1/plans/{key}/envelope-snapshots", mixed))
                .andExpect(status().isOk()).andReturn());
        assertThat(mixedSnapshots).hasSize(1);
        assertThat(mixedSnapshots.get(0).get("occupancySeq").asInt()).isEqualTo(1);
    }

    // ---------- 冻结后档案修改不改写历史 ----------

    @Test
    void snapshotFrozenAfterLaterProfileChange() throws Exception {
        String trainNo = key("TRN");
        String section = key("SEC");
        createTrainProfile(trainNo, "1", "2");
        createSectionProfile(section, "35");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occSpeed(trainNo, section, iso(8), iso(9), "10"));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());

        // 取消后区段不再被已发布占用引用，可收紧防护距离
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/section-profiles/{sid}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("RID"), "op-a", 1, "34.999")))
                .andExpect(status().isOk());

        // 历史快照保持冻结值：d=35、lhs=rhs=140、档案版本 1
        JsonNode snapshots = read(mvc.perform(
                        get("/api/v1/plans/{key}/envelope-snapshots", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(snapshots).hasSize(1);
        JsonNode snap = snapshots.get(0);
        assertThat(snap.get("protectionDistance").decimalValue()).isEqualByComparingTo("35");
        assertThat(snap.get("sectionProfileVersion").asInt()).isEqualTo(1);
        assertThat(snap.get("lhsScaled").decimalValue()).isEqualByComparingTo("140");
        assertThat(snap.get("rhsScaled").decimalValue()).isEqualByComparingTo("140");
    }

    // ---------- 档案变更使已发布占用越限 → 409 ----------

    @Test
    void profileChangeBreakingPublishedOccupancyRejected() throws Exception {
        String trainNo = key("TRN");
        String section = key("SEC");
        createTrainProfile(trainNo, "1", "2");
        createSectionProfile(section, "35");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occSpeed(trainNo, section, iso(8), iso(9), "10"));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());

        // 区段 d 收紧到 34.999 → 已发布占用越限，409 且不生效
        MvcResult sectionConflict = mvc.perform(put("/api/v1/section-profiles/{sid}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("RID"), "op-a", 1, "34.999")))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(sectionConflict);
        assertThat(error.get("code").asText()).isEqualTo("ENVELOPE_WOULD_EXCEED");
        assertThat(error.get("details").get(0).get("trainNo").asText()).isEqualTo(trainNo);
        assertThat(error.get("details").get(0).get("sectionId").asText()).isEqualTo(section);
        JsonNode sectionAfter = read(mvc.perform(get("/api/v1/section-profiles/{sid}", section))
                .andExpect(status().isOk()).andReturn());
        assertThat(sectionAfter.get("protectionDistance").decimalValue())
                .isEqualByComparingTo("35");
        assertThat(sectionAfter.get("version").asInt()).isEqualTo(1);

        // 列车 a 减小到 1.999 → 所需距离 35.0125... > 35，409
        mvc.perform(put("/api/v1/train-profiles/{tn}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("RID"), "op-a", 1, "1", "1.999")))
                .andExpect(status().isConflict());
        // 列车 a 增大到 2.001 → 所需距离 34.9875... <= 35，允许，版本递增
        MvcResult trainUpdated = mvc.perform(put("/api/v1/train-profiles/{tn}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("RID"), "op-a", 1, "1", "2.001")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(trainUpdated).get("version").asInt()).isEqualTo(2);
    }

    // ---------- 改签的包络校验与原子回滚 ----------

    @Test
    void rescheduleValidatesEnvelopeAtomically() throws Exception {
        String trainNo = key("TRN");
        String section = key("SEC");
        createTrainProfile(trainNo, "1", "2");
        createSectionProfile(section, "34.999");

        // 旧计划：原无速度占用，已发布
        String oldKey = key("SCH");
        createPlan(oldKey, occ("G0", key("SEC"), iso(8), iso(9)));
        mvc.perform(post("/api/v1/plans/{key}/publish", oldKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        // 新草稿：带速度占用，包络越限（34.999 < 35）
        String newKey = key("SCH");
        createPlan(newKey, occSpeed(trainNo, section, iso(9), iso(10), "10"));

        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(rejected).get("code").asText()).isEqualTo("ENVELOPE_EXCEEDED");

        // 整体回滚：旧计划仍发布、新计划仍草稿、无快照、无改签关联
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", oldKey))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("PUBLISHED");
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", newKey))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("DRAFT");
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}/envelope-snapshots", newKey))
                .andExpect(status().isOk()).andReturn())).isEmpty();
        JsonNode chain = read(mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", oldKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(chain.get("chain")).hasSize(1);

        // 修正区段防护距离后改签成功，冻结新计划快照
        mvc.perform(put("/api/v1/section-profiles/{sid}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("RID"), "op-a", 1, "35")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isOk());
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", oldKey))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("CANCELLED");
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", newKey))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("PUBLISHED");
        JsonNode snapshots = read(mvc.perform(
                        get("/api/v1/plans/{key}/envelope-snapshots", newKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).get("protectionDistance").decimalValue())
                .isEqualByComparingTo("35");
    }

    // ---------- 占用速度参数校验 ----------

    @Test
    void rejectsInvalidSpeed() throws Exception {
        // 速度为负
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"),
                                occSpeed("G1", key("SEC"), iso(8), iso(9), "-0.5"))))
                .andExpect(status().isBadRequest());
        // 速度超过 3 位小数
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"),
                                occSpeed("G1", key("SEC"), iso(8), iso(9), "1.0001"))))
                .andExpect(status().isBadRequest());
    }

    // ---------- 辅助 ----------

    private void createTrainProfile(String trainNo, String reactionTime,
                                    String baseDeceleration) throws Exception {
        mvc.perform(put("/api/v1/train-profiles/{tn}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("RID"), "op-a", 0, reactionTime, baseDeceleration)))
                .andExpect(status().isOk());
    }

    private void createSectionProfile(String sectionId, String protectionDistance)
            throws Exception {
        mvc.perform(put("/api/v1/section-profiles/{sid}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("RID"), "op-a", 0, protectionDistance)))
                .andExpect(status().isOk());
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, occupancies)))
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
        return DAY.atTime(hour, 0).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String occSpeed(String train, String section, String startIso, String endIso,
                                   String speed) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso
                + "\",\"speed\":" + speed + "}";
    }

    private static String createBody(String requestKey, String scheduleKey,
                                     String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":[" + String.join(",", occupancies)
                + "]}";
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

    private static String trainBody(String requestId, String operator, int expectedVersion,
                                    String reactionTime, String baseDeceleration) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"expectedVersion\":" + expectedVersion
                + ",\"reactionTime\":" + reactionTime
                + ",\"baseDeceleration\":" + baseDeceleration + "}";
    }

    private static String sectionBody(String requestId, String operator, int expectedVersion,
                                      String protectionDistance) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"expectedVersion\":" + expectedVersion
                + ",\"protectionDistance\":" + protectionDistance + "}";
    }
}

