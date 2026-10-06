package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
 * 制动包络与档案配置 API 测试（H2 内存库）：主流程、参数边界、缺档案 404、
 * 越限 422、档案修改 409、快照冻结与幂等。各用例使用唯一业务键互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanEnvelopeApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 档案主流程 ----------

    @Test
    void trainProfileCrudAndVersioning() throws Exception {
        String trainNo = key("G");
        MvcResult created = mvc.perform(post("/api/v1/train-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("REQ"), "op-1", trainNo, "1.000", "2.000")))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("trainNo").asText()).isEqualTo(trainNo);
        assertThat(decimal(body, "reactionTimeT")).isEqualByComparingTo("1");
        assertThat(decimal(body, "baseDecelA")).isEqualByComparingTo("2");
        assertThat(body.get("version").asInt()).isEqualTo(1);

        MvcResult fetched = mvc.perform(get("/api/v1/train-profiles/{trainNo}", trainNo))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(fetched)).isEqualTo(body);

        MvcResult updated = mvc.perform(put("/api/v1/train-profiles/{trainNo}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("REQ"), "op-1", "1.500", "2.000", 1)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(updated).get("version").asInt()).isEqualTo(2);

        // 过期版本 → 409
        mvc.perform(put("/api/v1/train-profiles/{trainNo}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("REQ"), "op-1", "1.500", "2.000", 1)))
                .andExpect(status().isConflict());
        // 缺档案 → 404
        mvc.perform(get("/api/v1/train-profiles/{trainNo}", key("G")))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/train-profiles/{trainNo}", key("G"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("REQ"), "op-1", "1.000", "2.000", 1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void sectionProfileCrudAndVersioning() throws Exception {
        String sectionId = key("SEC");
        mvc.perform(post("/api/v1/section-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("REQ"), "op-1", sectionId, "35.000")))
                .andExpect(status().isCreated());

        MvcResult updated = mvc.perform(put("/api/v1/section-profiles/{sectionId}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionUpdateBody(key("REQ"), "op-1", "40.000", 1)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(updated).get("version").asInt()).isEqualTo(2);
        assertThat(decimal(read(updated), "protectDistanceD")).isEqualByComparingTo("40");

        mvc.perform(put("/api/v1/section-profiles/{sectionId}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionUpdateBody(key("REQ"), "op-1", "40.000", 1)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/section-profiles/{sectionId}", key("SEC")))
                .andExpect(status().isNotFound());
    }

    // ---------- 参数边界 400 ----------

    @Test
    void profileRejectsInvalidDecimals() throws Exception {
        // t 必须为正
        mvc.perform(post("/api/v1/train-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("REQ"), "op-1", key("G"), "0", "2.000")))
                .andExpect(status().isBadRequest());
        // a 必须为正
        mvc.perform(post("/api/v1/train-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("REQ"), "op-1", key("G"), "1.000", "-1")))
                .andExpect(status().isBadRequest());
        // 最多 3 位小数
        mvc.perform(post("/api/v1/train-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("REQ"), "op-1", key("G"), "1.0001", "2.000")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/section-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("REQ"), "op-1", key("SEC"), "0")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/section-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("REQ"), "op-1", key("SEC"), "35.0001")))
                .andExpect(status().isBadRequest());
        // 占用速度必须非负且最多 3 位小数
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"),
                                occSpeed("G1", key("SEC"), iso(8), iso(9), "-0.5"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"),
                                occSpeed("G1", key("SEC"), iso(8), iso(9), "10.0001"))))
                .andExpect(status().isBadRequest());
    }

    // ---------- 缺档案 404 ----------

    @Test
    void publishRequiresTrainAndSectionProfiles() throws Exception {
        String trainNo = key("G");
        String sectionId = key("SEC");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occSpeed(trainNo, sectionId, iso(8), iso(9), "10"));

        // 列车档案缺失 → 404
        MvcResult missingTrain = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(read(missingTrain).get("code").asText()).isEqualTo("TRAIN_PROFILE_NOT_FOUND");

        createTrainProfile(trainNo, "1.000", "2.000");
        // 区段档案缺失 → 404
        MvcResult missingSection = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(read(missingSection).get("code").asText()).isEqualTo("SECTION_PROFILE_NOT_FOUND");

        // 计划保持草稿，补齐档案后可发布
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
        createSectionProfile(sectionId, "35.000");
        publishPlan(scheduleKey, key("REQ"));
    }

    // ---------- 包络判定：合成边界 ----------

    @Test
    void syntheticEnvelopeBoundary35AcceptedAnd34999Rejected() throws Exception {
        // 合成：v=10、t=1、a=2 → 所需距离 10*1 + 100/(2*2) = 35 米
        String trainNo = key("G");
        createTrainProfile(trainNo, "1.000", "2.000");

        // d=35 恰可发布，快照冻结精确判定依据（左值 140 = 右值 140）
        String okSection = key("SEC");
        createSectionProfile(okSection, "35.000");
        String okPlan = key("SCH");
        createPlan(okPlan, occSpeed(trainNo, okSection, iso(8), iso(9), "10"));
        publishPlan(okPlan, key("REQ"));

        MvcResult snapshots = mvc.perform(get("/api/v1/plans/{key}/envelope-snapshots", okPlan))
                .andExpect(status().isOk()).andReturn();
        JsonNode snapList = read(snapshots);
        assertThat(snapList).hasSize(1);
        JsonNode snap = snapList.get(0);
        assertThat(snap.get("trainNo").asText()).isEqualTo(trainNo);
        assertThat(snap.get("sectionId").asText()).isEqualTo(okSection);
        assertThat(decimal(snap, "speedV")).isEqualByComparingTo("10");
        assertThat(decimal(snap, "reactionTimeT")).isEqualByComparingTo("1");
        assertThat(decimal(snap, "baseDecelA")).isEqualByComparingTo("2");
        assertThat(decimal(snap, "protectDistanceD")).isEqualByComparingTo("35");
        assertThat(decimal(snap, "leftValue")).isEqualByComparingTo("140");
        assertThat(decimal(snap, "rightValue")).isEqualByComparingTo("140");

        // d=34.999 整单拒绝：422 携带列车与区段，计划保持草稿、不写占用
        String tightSection = key("SEC");
        createSectionProfile(tightSection, "34.999");
        String rejected = key("SCH");
        createPlan(rejected, occSpeed(trainNo, tightSection, iso(8), iso(9), "10"));
        MvcResult denied = mvc.perform(post("/api/v1/plans/{key}/publish", rejected)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(denied);
        assertThat(error.get("code").asText()).isEqualTo("ENVELOPE_EXCEEDED");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("trainNo").asText()).isEqualTo(trainNo);
        assertThat(detail.get("sectionId").asText()).isEqualTo(tightSection);
        assertThat(decimal(detail, "leftValue")).isEqualByComparingTo("140");
        assertThat(decimal(detail, "rightValue")).isEqualByComparingTo("139.996");

        assertThat(planStatus(rejected)).isEqualTo("DRAFT");
        MvcResult snaps = mvc.perform(get("/api/v1/plans/{key}/envelope-snapshots", rejected))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(snaps)).isEmpty();
    }

    @Test
    void occupancyWithoutSpeedSkipsEnvelopeAndKeepsOriginalInterface() throws Exception {
        // 无速度占用不适用包络检查：无任何档案也可发布，响应保持原接口（无 speedV 字段）
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));
        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode occupancy = read(published).get("occupancies").get(0);
        assertThat(occupancy.has("speedV")).isFalse();

        MvcResult snapshots = mvc.perform(get("/api/v1/plans/{key}/envelope-snapshots", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(snapshots)).isEmpty();
    }

    // ---------- 档案修改 409 与快照冻结 ----------

    @Test
    void profileUpdateBreakingPublishedOccupancyRejected409AndSnapshotFrozen() throws Exception {
        String trainNo = key("G");
        String sectionId = key("SEC");
        createTrainProfile(trainNo, "1.000", "2.000");
        createSectionProfile(sectionId, "35.000");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occSpeed(trainNo, sectionId, iso(8), iso(9), "10"));
        publishPlan(scheduleKey, key("REQ"));

        // 区段防护距离收紧到 34.999 → 409，配置不生效
        MvcResult denied = mvc.perform(put("/api/v1/section-profiles/{sectionId}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionUpdateBody(key("REQ"), "op-1", "34.999", 1)))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(denied);
        assertThat(error.get("code").asText()).isEqualTo("ENVELOPE_CONFIG_CONFLICT");
        assertThat(error.get("details").get(0).get("trainNo").asText()).isEqualTo(trainNo);
        assertThat(error.get("details").get(0).get("sectionId").asText()).isEqualTo(sectionId);
        assertThat(sectionVersion(sectionId)).isEqualTo(1);

        // 列车减速度降到 1.9 → 左值 138 大于右值 133 → 409
        mvc.perform(put("/api/v1/train-profiles/{trainNo}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(key("REQ"), "op-1", "1.000", "1.900", 1)))
                .andExpect(status().isConflict());
        assertThat(trainVersion(trainNo)).isEqualTo(1);

        // 放宽到 36 合法 → 版本 2；历史快照仍冻结 d=35、左值 140、右值 140
        mvc.perform(put("/api/v1/section-profiles/{sectionId}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionUpdateBody(key("REQ"), "op-1", "36.000", 1)))
                .andExpect(status().isOk());
        assertThat(sectionVersion(sectionId)).isEqualTo(2);

        MvcResult snapshots = mvc.perform(get("/api/v1/plans/{key}/envelope-snapshots", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode snap = read(snapshots).get(0);
        assertThat(decimal(snap, "protectDistanceD")).isEqualByComparingTo("35");
        assertThat(decimal(snap, "leftValue")).isEqualByComparingTo("140");
        assertThat(decimal(snap, "rightValue")).isEqualByComparingTo("140");
    }

    // ---------- 改签包络回滚 ----------

    @Test
    void rescheduleEnvelopeViolationRollsBackAtomically() throws Exception {
        String trainNo = key("G");
        createTrainProfile(trainNo, "1.000", "2.000");
        String oldSection = key("SEC");
        createSectionProfile(oldSection, "35.000");
        String oldKey = key("SCH");
        createPlan(oldKey, occSpeed(trainNo, oldSection, iso(8), iso(9), "10"));
        publishPlan(oldKey, key("REQ"));

        String tightSection = key("SEC");
        createSectionProfile(tightSection, "34.999");
        String newKey = key("SCH");
        createPlan(newKey, occSpeed(trainNo, tightSection, iso(10), iso(11), "10"));

        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isUnprocessableEntity());

        // 整体回滚：旧计划仍发布、新计划仍草稿、无改签关联
        assertThat(planStatus(oldKey)).isEqualTo("PUBLISHED");
        assertThat(planStatus(newKey)).isEqualTo("DRAFT");
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", oldKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(chain).get("chain")).hasSize(1);
    }

    // ---------- 幂等 ----------

    @Test
    void profileIdempotentReplayAndParamConflict() throws Exception {
        String trainNo = key("G");
        String requestId = key("REQ");
        String body = trainBody(requestId, "op-1", trainNo, "1.000", "2.000");
        MvcResult first = mvc.perform(post("/api/v1/train-profiles")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/train-profiles")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/train-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(requestId, "op-1", trainNo, "1.500", "2.000")))
                .andExpect(status().isConflict());

        // 失败不占键：错误版本 409 后，同键正确参数可成功
        String updateKey = key("REQ");
        mvc.perform(put("/api/v1/train-profiles/{trainNo}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(updateKey, "op-1", "1.500", "2.000", 9)))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/train-profiles/{trainNo}", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trainUpdateBody(updateKey, "op-1", "1.500", "2.000", 1)))
                .andExpect(status().isOk());
    }

    @Test
    void publishFailureDoesNotCacheIdempotencyKey() throws Exception {
        String trainNo = key("G");
        String sectionId = key("SEC");
        createTrainProfile(trainNo, "1.000", "2.000");
        createSectionProfile(sectionId, "34.999");
        String scheduleKey = key("SCH");
        String publishKey = key("REQ");
        createPlan(scheduleKey, occSpeed(trainNo, sectionId, iso(8), iso(9), "10"));

        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isUnprocessableEntity());

        // 修正防护距离后同键重发成功（422 失败未占键）
        mvc.perform(put("/api/v1/section-profiles/{sectionId}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionUpdateBody(key("REQ"), "op-1", "35.000", 1)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isOk());
    }

    @Test
    void snapshotEndpointMissingPlanReturns404() throws Exception {
        mvc.perform(get("/api/v1/plans/{key}/envelope-snapshots", key("SCH")))
                .andExpect(status().isNotFound());
    }

    // ---------- 辅助 ----------

    private void createTrainProfile(String trainNo, String t, String a) throws Exception {
        mvc.perform(post("/api/v1/train-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(trainBody(key("REQ"), "op-1", trainNo, t, a)))
                .andExpect(status().isCreated());
    }

    private void createSectionProfile(String sectionId, String d) throws Exception {
        mvc.perform(post("/api/v1/section-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("REQ"), "op-1", sectionId, d)))
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

    private String planStatus(String scheduleKey) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(detail).get("status").asText();
    }

    private int trainVersion(String trainNo) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/train-profiles/{trainNo}", trainNo))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("version").asInt();
    }

    private int sectionVersion(String sectionId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/section-profiles/{sectionId}", sectionId))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("version").asInt();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
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
                + "\",\"speedV\":" + speed + "}";
    }

    private static String createBody(String requestKey, String scheduleKey, String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":[" + String.join(",", occupancies)
                + "]}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String rescheduleBody(String requestKey, String newKey, int oldVersion,
                                         int newVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newKey
                + "\",\"expectedOldVersion\":" + oldVersion
                + ",\"expectedNewVersion\":" + newVersion + "}";
    }

    private static String trainBody(String requestId, String operator, String trainNo,
                                    String t, String a) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"trainNo\":\"" + trainNo + "\",\"reactionTimeT\":" + t
                + ",\"baseDecelA\":" + a + "}";
    }

    private static String trainUpdateBody(String requestId, String operator, String t, String a,
                                          int expectedVersion) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"reactionTimeT\":" + t + ",\"baseDecelA\":" + a
                + ",\"expectedVersion\":" + expectedVersion + "}";
    }

    private static String sectionBody(String requestId, String operator, String sectionId,
                                      String d) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"sectionId\":\"" + sectionId + "\",\"protectDistanceD\":" + d + "}";
    }

    private static String sectionUpdateBody(String requestId, String operator, String d,
                                            int expectedVersion) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"protectDistanceD\":" + d
                + ",\"expectedVersion\":" + expectedVersion + "}";
    }
}
