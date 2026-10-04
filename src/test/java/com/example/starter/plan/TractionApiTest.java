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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 牵引档案与牵引通行检查的端到端测试（H2 内存库）：档案版本与乐观校验、
 * 发布/改签牵引适配 422、档案变更 409、快照冻结与幂等边界。
 * 各用例使用唯一 scheduleKey/requestId/区段 ID/列车编号，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TractionApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 档案主流程与版本 ----------

    @Test
    void sectionProfileCreateUpdateAndVersionFlow() throws Exception {
        String section = key("SEC");
        MvcResult created = mvc.perform(post("/api/v1/section-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createSectionBody(key("REQ"), section, true, false)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("sectionId").asText()).isEqualTo(section);
        assertThat(body.get("electrified").asBoolean()).isTrue();
        assertThat(body.get("dieselAllowed").asBoolean()).isFalse();
        assertThat(body.get("version").asInt()).isEqualTo(1);

        MvcResult detail = mvc.perform(get("/api/v1/section-profiles/{id}", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);

        // 期望版本匹配：变更成功版本加一
        MvcResult updated = mvc.perform(put("/api/v1/section-profiles/{id}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateSectionBody(key("REQ"), false, true, 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode updatedBody = read(updated);
        assertThat(updatedBody.get("version").asInt()).isEqualTo(2);
        assertThat(updatedBody.get("electrified").asBoolean()).isFalse();
        assertThat(updatedBody.get("dieselAllowed").asBoolean()).isTrue();

        // 期望版本不匹配 → 409，档案不变
        mvc.perform(put("/api/v1/section-profiles/{id}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateSectionBody(key("REQ"), true, true, 1)))
                .andExpect(status().isConflict());
        MvcResult after = mvc.perform(get("/api/v1/section-profiles/{id}", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after)).isEqualTo(updatedBody);

        // 重复创建 → 409
        mvc.perform(post("/api/v1/section-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createSectionBody(key("REQ"), section, true, true)))
                .andExpect(status().isConflict());

        // 不存在 → 404
        mvc.perform(get("/api/v1/section-profiles/{id}", key("SEC")))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/section-profiles/{id}", key("SEC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateSectionBody(key("REQ"), true, true, 1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void trainTractionUpsertAndGet() throws Exception {
        String train = key("G");
        mvc.perform(get("/api/v1/trains/{no}/traction", train))
                .andExpect(status().isNotFound());

        mvc.perform(put("/api/v1/trains/{no}/traction", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tractionBody(key("REQ"), "ELECTRIC")))
                .andExpect(status().isOk());
        MvcResult detail = mvc.perform(get("/api/v1/trains/{no}/traction", train))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("tractionMode").asText()).isEqualTo("ELECTRIC");

        mvc.perform(put("/api/v1/trains/{no}/traction", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tractionBody(key("REQ"), "DIESEL")))
                .andExpect(status().isOk());
        MvcResult changed = mvc.perform(get("/api/v1/trains/{no}/traction", train))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(changed).get("tractionMode").asText()).isEqualTo("DIESEL");

        // 非法牵引方式与缺 requestId → 400
        mvc.perform(put("/api/v1/trains/{no}/traction", key("G"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tractionBody(key("REQ"), "STEAM")))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/trains/{no}/traction", key("G"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"test\",\"tractionMode\":\"DIESEL\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 发布牵引检查 422 ----------

    @Test
    void publishRejectsMissingProfiles() throws Exception {
        String scheduleKey = key("SCH");
        String train = key("G");
        String section = key("SEC");
        createPlan(scheduleKey, occ(train, section, iso(8), iso(9)));

        // 列车档案缺失 → 422
        MvcResult missingTrain = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(missingTrain);
        assertThat(error.get("code").asText()).isEqualTo("TRACTION_CHECK_FAILED");
        assertThat(error.get("details").get(0).get("type").asText())
                .isEqualTo("TRAIN_PROFILE_MISSING");
        assertThat(error.get("details").get(0).get("trainNo").asText()).isEqualTo(train);

        // 补列车档案后区段档案缺失 → 422 携带区段号
        upsertTraction(train, "ELECTRIC");
        MvcResult missingSection = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error2 = read(missingSection);
        assertThat(error2.get("code").asText()).isEqualTo("TRACTION_CHECK_FAILED");
        assertThat(error2.get("details").get(0).get("type").asText())
                .isEqualTo("SECTION_PROFILE_MISSING");
        assertThat(error2.get("details").get(0).get("sectionId").asText()).isEqualTo(section);

        // 计划保持草稿；补齐档案后发布成功
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
        createSection(section, true, false);
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }
    @Test
    void electricTrainRejectedOnUnelectrifiedSectionThenDieselPublishes() throws Exception {
        // 合成场景：电力车占用有电 A 与无电 B
        String sectionA = key("SEC-A");
        String sectionB = key("SEC-B");
        createSection(sectionA, true, false);
        createSection(sectionB, false, false);
        String train = key("G");
        upsertTraction(train, "ELECTRIC");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey,
                occ(train, sectionA, iso(8), iso(9)),
                occ(train, sectionB, iso(9), iso(10)));

        // 整单拒绝：422 携带不适配区段号 B，A 也不占用
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("TRACTION_CHECK_FAILED");
        assertThat(error.get("details")).hasSize(1);
        assertThat(error.get("details").get(0).get("type").asText())
                .isEqualTo("TRACTION_INCOMPATIBLE");
        assertThat(error.get("details").get(0).get("sectionId").asText()).isEqualTo(sectionB);
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
        assertThat(publishedSlots(sectionA)).isEmpty();
        assertThat(publishedSlots(sectionB)).isEmpty();

        // 改为柴油并允许两段柴油后可整单发布
        upsertTraction(train, "DIESEL");
        updateSection(sectionA, true, true, 1);
        updateSection(sectionB, false, true, 1);
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        assertThat(planStatus(scheduleKey)).isEqualTo("PUBLISHED");
        assertThat(publishedSlots(sectionA)).hasSize(1);
        assertThat(publishedSlots(sectionB)).hasSize(1);

        // 快照冻结为柴油与发布时档案值
        JsonNode detail = planDetail(scheduleKey);
        JsonNode snapshots = detail.get("tractionSnapshots");
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).get("tractionMode").asText()).isEqualTo("DIESEL");
        assertThat(snapshots.get(0).get("sectionId").asText()).isEqualTo(sectionA);
        assertThat(snapshots.get(0).get("sectionVersion").asInt()).isEqualTo(2);
    }

    // ---------- 档案变更 409 ----------

    @Test
    void sectionUpdateMakingPublishedPlanIllegalConflict() throws Exception {
        String section = key("SEC");
        createSection(section, true, false);
        String train = key("G");
        upsertTraction(train, "ELECTRIC");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(train, section, iso(8), iso(9)));
        publishPlan(scheduleKey);

        // 取消供电使已发布计划非法 → 409 携带计划与区段，档案不变
        MvcResult conflict = mvc.perform(put("/api/v1/section-profiles/{id}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateSectionBody(key("REQ"), false, false, 1)))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("PROFILE_IN_USE");
        assertThat(error.get("details").get(0).get("scheduleKey").asText())
                .isEqualTo(scheduleKey);
        assertThat(error.get("details").get(0).get("sectionId").asText()).isEqualTo(section);
        JsonNode profile = sectionProfile(section);
        assertThat(profile.get("version").asInt()).isEqualTo(1);
        assertThat(profile.get("electrified").asBoolean()).isTrue();

        // 先取消相关计划才能变更
        cancelPlan(scheduleKey);
        mvc.perform(put("/api/v1/section-profiles/{id}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateSectionBody(key("REQ"), false, false, 1)))
                .andExpect(status().isOk());
        assertThat(sectionProfile(section).get("version").asInt()).isEqualTo(2);
    }

    @Test
    void trainTractionChangeMakingPublishedPlanIllegalConflict() throws Exception {
        String section = key("SEC");
        createSection(section, false, true);
        String train = key("G");
        upsertTraction(train, "DIESEL");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(train, section, iso(8), iso(9)));
        publishPlan(scheduleKey);

        // 无电区段上已发布柴油车改电力 → 409，牵引方式不变
        MvcResult conflict = mvc.perform(put("/api/v1/trains/{no}/traction", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tractionBody(key("REQ"), "ELECTRIC")))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("PROFILE_IN_USE");
        assertThat(error.get("details").get(0).get("scheduleKey").asText())
                .isEqualTo(scheduleKey);
        MvcResult traction = mvc.perform(get("/api/v1/trains/{no}/traction", train))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(traction).get("tractionMode").asText()).isEqualTo("DIESEL");
    }

    @Test
    void snapshotFrozenAfterLaterProfileChange() throws Exception {
        String section = key("SEC");
        createSection(section, true, false);
        String train = key("G");
        upsertTraction(train, "ELECTRIC");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(train, section, iso(8), iso(9)));
        publishPlan(scheduleKey);

        // 取消后档案合法变更：断电并改柴油
        cancelPlan(scheduleKey);
        updateSection(section, false, true, 1);
        upsertTraction(train, "DIESEL");

        // 历史快照不改：仍冻结发布时的电力方式、供电值与版本 1
        JsonNode snapshots = planDetail(scheduleKey).get("tractionSnapshots");
        assertThat(snapshots).hasSize(1);
        JsonNode snap = snapshots.get(0);
        assertThat(snap.get("tractionMode").asText()).isEqualTo("ELECTRIC");
        assertThat(snap.get("electrified").asBoolean()).isTrue();
        assertThat(snap.get("dieselAllowed").asBoolean()).isFalse();
        assertThat(snap.get("sectionVersion").asInt()).isEqualTo(1);
    }
    // ---------- 改签牵引检查与回滚 ----------

    @Test
    void rescheduleRejectsTractionIncompatibleNewPlanAndRollsBack() throws Exception {
        String oldSection = key("SEC");
        createSection(oldSection, true, false);
        String newSection = key("SEC");
        createSection(newSection, false, false);
        String train = key("G");
        upsertTraction(train, "ELECTRIC");
        String oldKey = key("SCH");
        String newKey = key("SCH");
        createPlan(oldKey, occ(train, oldSection, iso(8), iso(9)));
        publishPlan(oldKey);
        // 新草稿为电力车占用无电区段
        createPlan(newKey, occ(train, newSection, iso(8), iso(9)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("TRACTION_CHECK_FAILED");
        assertThat(error.get("details").get(0).get("sectionId").asText()).isEqualTo(newSection);

        // 旧计划和全部时隙保持不变：旧仍发布、新仍草稿、无改签关联
        assertThat(planStatus(oldKey)).isEqualTo("PUBLISHED");
        assertThat(planStatus(newKey)).isEqualTo("DRAFT");
        assertThat(publishedSlots(oldSection)).hasSize(1);
        assertThat(publishedSlots(newSection)).isEmpty();
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", oldKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(chain).get("chain")).hasSize(1);
    }

    // ---------- 幂等 ----------

    @Test
    void sectionProfileIdempotentReplayAndParamConflict() throws Exception {
        String section = key("SEC");
        String requestId = key("REQ");
        String body = createSectionBody(requestId, section, true, false);
        MvcResult first = mvc.perform(post("/api/v1/section-profiles")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放首次完整响应
        MvcResult replay = mvc.perform(post("/api/v1/section-profiles")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        // 同键异参 → 409
        mvc.perform(post("/api/v1/section-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createSectionBody(requestId, key("SEC"), true, false)))
                .andExpect(status().isConflict());

        // 区段变更幂等：同键同参重放版本不加二
        String updateId = key("REQ");
        String updateBody = updateSectionBody(updateId, false, true, 1);
        MvcResult updated = mvc.perform(put("/api/v1/section-profiles/{id}", section)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult updatedReplay = mvc.perform(put("/api/v1/section-profiles/{id}", section)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(updatedReplay)).isEqualTo(read(updated));
        assertThat(sectionProfile(section).get("version").asInt()).isEqualTo(2);
        // 同键异参（不同期望版本）→ 409
        mvc.perform(put("/api/v1/section-profiles/{id}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateSectionBody(updateId, false, true, 2)))
                .andExpect(status().isConflict());
    }

    @Test
    void trainTractionIdempotentReplayAndFailureDoesNotConsumeKey() throws Exception {
        String train = key("G");
        String requestId = key("REQ");
        String body = tractionBody(requestId, "ELECTRIC");
        MvcResult first = mvc.perform(put("/api/v1/trains/{no}/traction", train)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(put("/api/v1/trains/{no}/traction", train)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        // 同键异参 → 409
        mvc.perform(put("/api/v1/trains/{no}/traction", train)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tractionBody(requestId, "DIESEL")))
                .andExpect(status().isConflict());

        // 失败不占键：区段变更版本冲突 409 后，同 requestId 以正确参数重试成功
        String section = key("SEC");
        createSection(section, true, false);
        String failId = key("REQ");
        mvc.perform(put("/api/v1/section-profiles/{id}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateSectionBody(failId, false, true, 5)))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/section-profiles/{id}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateSectionBody(failId, false, true, 1)))
                .andExpect(status().isOk());
        assertThat(sectionProfile(section).get("version").asInt()).isEqualTo(2);
    }
    // ---------- 辅助 ----------

    private void createSection(String sectionId, boolean electrified, boolean dieselAllowed)
            throws Exception {
        mvc.perform(post("/api/v1/section-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createSectionBody(key("REQ"), sectionId, electrified, dieselAllowed)))
                .andExpect(status().isCreated());
    }

    private void updateSection(String sectionId, boolean electrified, boolean dieselAllowed,
                               int expectedVersion) throws Exception {
        mvc.perform(put("/api/v1/section-profiles/{id}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateSectionBody(key("REQ"), electrified, dieselAllowed,
                                expectedVersion)))
                .andExpect(status().isOk());
    }

    private JsonNode sectionProfile(String sectionId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/section-profiles/{id}", sectionId))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private void upsertTraction(String trainNo, String mode) throws Exception {
        mvc.perform(put("/api/v1/trains/{no}/traction", trainNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tractionBody(key("REQ"), mode)))
                .andExpect(status().isOk());
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
        return planDetail(scheduleKey).get("status").asText();
    }

    private JsonNode planDetail(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private JsonNode publishedSlots(String sectionId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", sectionId))
                .andExpect(status().isOk()).andReturn();
        return read(result);
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

    private static String createSectionBody(String requestId, String sectionId,
                                            boolean electrified, boolean dieselAllowed) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"dispatcher-1\","
                + "\"sectionId\":\"" + sectionId + "\",\"electrified\":" + electrified
                + ",\"dieselAllowed\":" + dieselAllowed + "}";
    }

    private static String updateSectionBody(String requestId, boolean electrified,
                                            boolean dieselAllowed, int expectedVersion) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"dispatcher-1\","
                + "\"electrified\":" + electrified + ",\"dieselAllowed\":" + dieselAllowed
                + ",\"expectedVersion\":" + expectedVersion + "}";
    }

    private static String tractionBody(String requestId, String mode) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"dispatcher-1\","
                + "\"tractionMode\":\"" + mode + "\"}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }
}