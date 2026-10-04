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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 牵引档案与牵引通行检查端到端测试（H2 内存库）：档案版本、发布/改签牵引 422、
 * 档案变更 409 守卫、冻结快照与幂等边界。各用例使用唯一键隔离数据。
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

    // ---------- 档案新增/变更与版本 ----------

    @Test
    void trainProfileCreateUpdateAndVersionGuards() throws Exception {
        String trainNo = key("TRN");

        // 新增：expectedVersion=0 → 版本 1
        MvcResult created = putTrain(trainNo, key("RID"), "op-1", "ELECTRIC", 0)
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("trainNo").asText()).isEqualTo(trainNo);
        assertThat(body.get("tractionMode").asText()).isEqualTo("ELECTRIC");
        assertThat(body.get("version").asInt()).isEqualTo(1);

        // 重复新增（新 requestId）→ 409
        putTrain(trainNo, key("RID"), "op-1", "DIESEL", 0).andExpect(status().isConflict());
        // 变更版本不匹配 → 409
        putTrain(trainNo, key("RID"), "op-1", "DIESEL", 5).andExpect(status().isConflict());
        // 变更成功 → 版本 2
        MvcResult updated = putTrain(trainNo, key("RID"), "op-1", "DIESEL", 1)
                .andExpect(status().isOk()).andReturn();
        assertThat(read(updated).get("version").asInt()).isEqualTo(2);
        assertThat(read(updated).get("tractionMode").asText()).isEqualTo("DIESEL");

        MvcResult queried = mvc.perform(get("/api/v1/train-profiles/{trainNo}", trainNo))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(queried).get("tractionMode").asText()).isEqualTo("DIESEL");
        assertThat(read(queried).get("version").asInt()).isEqualTo(2);

        // 变更不存在的档案（expectedVersion>0）→ 404
        putTrain(key("TRN"), key("RID"), "op-1", "ELECTRIC", 1)
                .andExpect(status().isNotFound());
    }

    @Test
    void sectionProfileCreateUpdateAndVersionGuards() throws Exception {
        String sectionId = key("SEC");

        MvcResult created = putSection(sectionId, key("RID"), "op-1", true, false, 0)
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("sectionId").asText()).isEqualTo(sectionId);
        assertThat(body.get("electrified").asBoolean()).isTrue();
        assertThat(body.get("dieselAllowed").asBoolean()).isFalse();
        assertThat(body.get("version").asInt()).isEqualTo(1);

        putSection(sectionId, key("RID"), "op-1", false, true, 0).andExpect(status().isConflict());
        putSection(sectionId, key("RID"), "op-1", false, true, 3).andExpect(status().isConflict());

        MvcResult updated = putSection(sectionId, key("RID"), "op-1", false, true, 1)
                .andExpect(status().isOk()).andReturn();
        JsonNode updatedBody = read(updated);
        assertThat(updatedBody.get("electrified").asBoolean()).isFalse();
        assertThat(updatedBody.get("dieselAllowed").asBoolean()).isTrue();
        assertThat(updatedBody.get("version").asInt()).isEqualTo(2);

        putSection(key("SEC"), key("RID"), "op-1", true, true, 1)
                .andExpect(status().isNotFound());
    }

    @Test
    void missingProfileQueryReturns404() throws Exception {
        mvc.perform(get("/api/v1/train-profiles/{trainNo}", key("TRN")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/section-profiles/{sectionId}", key("SEC")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/traction-snapshot", key("SCH")))
                .andExpect(status().isNotFound());
    }
    // ---------- 发布牵引检查 422 ----------

    @Test
    void publishRejectsMissingProfilesWithSectionId() throws Exception {
        String section = key("SEC");
        String trainNo = key("TRN");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(trainNo, section, iso(8), iso(9)));

        // 列车档案缺失 → 422，携带区段号
        MvcResult missingTrain = publishPlan(scheduleKey)
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode trainError = read(missingTrain);
        assertThat(trainError.get("code").asText()).isEqualTo("TRACTION_CHECK_FAILED");
        assertThat(trainError.get("details").get(0).get("type").asText())
                .isEqualTo("TRAIN_PROFILE_MISSING");
        assertThat(trainError.get("details").get(0).get("sectionId").asText()).isEqualTo(section);

        // 补列车档案后仍缺区段档案 → 422
        putTrain(trainNo, key("RID"), "op-1", "ELECTRIC", 0).andExpect(status().isOk());
        MvcResult missingSection = publishPlan(scheduleKey)
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode sectionError = read(missingSection);
        assertThat(sectionError.get("details").get(0).get("type").asText())
                .isEqualTo("SECTION_PROFILE_MISSING");
        assertThat(sectionError.get("details").get(0).get("sectionId").asText())
                .isEqualTo(section);

        // 计划保持草稿；补区段档案后同键可发布
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("status").asText()).isEqualTo("DRAFT");
        putSection(section, key("RID"), "op-1", true, false, 0).andExpect(status().isOk());
        publishPlan(scheduleKey).andExpect(status().isOk());
    }

    @Test
    void electricTrainRejectedThenDieselPublishesAfterBothSectionsAllowDiesel() throws Exception {
        // 合成场景：电力车占用有电 A 与无电 B
        String sectionA = key("SECA");
        String sectionB = key("SECB");
        String trainNo = key("TRN");
        putTrain(trainNo, key("RID"), "op-1", "ELECTRIC", 0).andExpect(status().isOk());
        putSection(sectionA, key("RID"), "op-1", true, false, 0).andExpect(status().isOk());
        putSection(sectionB, key("RID"), "op-1", false, false, 0).andExpect(status().isOk());

        String scheduleKey = key("SCH");
        createPlan(scheduleKey,
                occ(trainNo, sectionA, iso(8), iso(9)),
                occ(trainNo, sectionB, iso(9), iso(10)));

        // 电力车要求全程供电 → 422，携带不适配区段号 B
        MvcResult rejected = publishPlan(scheduleKey)
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("TRACTION_CHECK_FAILED");
        assertThat(error.get("details")).hasSize(1);
        assertThat(error.get("details").get(0).get("type").asText())
                .isEqualTo("SECTION_NOT_ELECTRIFIED");
        assertThat(error.get("details").get(0).get("sectionId").asText()).isEqualTo(sectionB);

        // 整单拒绝：A 也不占用，计划仍草稿
        assertThat(publishedSlots(sectionA)).isEmpty();
        assertThat(publishedSlots(sectionB)).isEmpty();
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("status").asText()).isEqualTo("DRAFT");

        // 改为柴油车：两段均未允许柴油 → 仍 422，携带 A、B 两段
        putTrain(trainNo, key("RID"), "op-1", "DIESEL", 1).andExpect(status().isOk());
        MvcResult dieselRejected = publishPlan(scheduleKey)
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode dieselError = read(dieselRejected);
        assertThat(dieselError.get("details")).hasSize(2);
        assertThat(dieselError.get("details").get(0).get("type").asText())
                .isEqualTo("DIESEL_NOT_ALLOWED");

        // 允许两段柴油后整单发布
        putSection(sectionA, key("RID"), "op-1", true, true, 1).andExpect(status().isOk());
        putSection(sectionB, key("RID"), "op-1", false, true, 1).andExpect(status().isOk());
        publishPlan(scheduleKey).andExpect(status().isOk());
        assertThat(publishedSlots(sectionA)).hasSize(1);
        assertThat(publishedSlots(sectionB)).hasSize(1);

        // 冻结快照：柴油方式与两段发布时刻档案值、版本
        JsonNode snapshot = tractionSnapshot(scheduleKey);
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0).get("tractionMode").asText()).isEqualTo("DIESEL");
        assertThat(snapshot.get(0).get("sectionId").asText()).isEqualTo(sectionA);
        assertThat(snapshot.get(0).get("electrified").asBoolean()).isTrue();
        assertThat(snapshot.get(0).get("dieselAllowed").asBoolean()).isTrue();
        assertThat(snapshot.get(0).get("sectionVersion").asInt()).isEqualTo(2);
        assertThat(snapshot.get(1).get("sectionId").asText()).isEqualTo(sectionB);
        assertThat(snapshot.get(1).get("electrified").asBoolean()).isFalse();
        assertThat(snapshot.get(1).get("sectionVersion").asInt()).isEqualTo(2);
    }
    // ---------- 档案变更 409 守卫与冻结 ----------

    @Test
    void profileChangeMakingPublishedPlanIllegalReturns409UntilCancelled() throws Exception {
        String section = key("SEC");
        String trainNo = key("TRN");
        putTrain(trainNo, key("RID"), "op-1", "ELECTRIC", 0).andExpect(status().isOk());
        putSection(section, key("RID"), "op-1", true, false, 0).andExpect(status().isOk());
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(trainNo, section, iso(8), iso(9)));
        publishPlan(scheduleKey).andExpect(status().isOk());

        // 区段改为不供电将使已发布计划非法 → 409，携带计划与区段号
        MvcResult conflict = putSection(section, key("RID"), "op-1", false, false, 1)
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("PROFILE_CHANGE_CONFLICT");
        assertThat(error.get("details").get(0).get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(error.get("details").get(0).get("sectionId").asText()).isEqualTo(section);
        assertThat(error.get("details").get(0).get("type").asText())
                .isEqualTo("SECTION_NOT_ELECTRIFIED");

        // 档案未被修改，计划仍发布
        MvcResult sectionAfter = mvc.perform(get("/api/v1/section-profiles/{id}", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(sectionAfter).get("electrified").asBoolean()).isTrue();
        assertThat(read(sectionAfter).get("version").asInt()).isEqualTo(1);

        // 牵引方式改为柴油同样被守卫（区段不允许柴油）→ 409
        putTrain(trainNo, key("RID"), "op-1", "DIESEL", 1).andExpect(status().isConflict());

        // 先取消相关计划后才能变更，不自动取消或改派
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        putSection(section, key("RID"), "op-1", false, false, 1).andExpect(status().isOk());
        putTrain(trainNo, key("RID"), "op-1", "DIESEL", 1).andExpect(status().isOk());

        // 历史快照保持发布时刻冻结值：电力、供电、区段版本 1
        JsonNode snapshot = tractionSnapshot(scheduleKey);
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).get("tractionMode").asText()).isEqualTo("ELECTRIC");
        assertThat(snapshot.get(0).get("electrified").asBoolean()).isTrue();
        assertThat(snapshot.get(0).get("sectionVersion").asInt()).isEqualTo(1);
    }

    // ---------- 改签牵引检查 ----------

    @Test
    void rescheduleRejectsIncompatibleNewDraftAndRollsBack() throws Exception {
        String sectionA = key("SECA");
        String sectionB = key("SECB");
        String trainOld = key("TRN");
        String trainNew = key("TRN");
        putTrain(trainOld, key("RID"), "op-1", "ELECTRIC", 0).andExpect(status().isOk());
        putTrain(trainNew, key("RID"), "op-1", "ELECTRIC", 0).andExpect(status().isOk());
        putSection(sectionA, key("RID"), "op-1", true, true, 0).andExpect(status().isOk());
        putSection(sectionB, key("RID"), "op-1", false, false, 0).andExpect(status().isOk());

        String oldKey = key("SCH");
        String newKey = key("SCH");
        createPlan(oldKey, occ(trainOld, sectionA, iso(8), iso(9)));
        publishPlan(oldKey).andExpect(status().isOk());
        // 新草稿占用无电区段 B，电力车不适配
        createPlan(newKey, occ(trainNew, sectionB, iso(8), iso(9)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"newScheduleKey\":\""
                                + newKey + "\",\"expectedOldVersion\":1,\"expectedNewVersion\":1}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("TRACTION_CHECK_FAILED");
        assertThat(error.get("details").get(0).get("sectionId").asText()).isEqualTo(sectionB);

        // 整体回滚：旧计划仍发布、新草稿仍草稿、无改签关联、时隙仍归旧计划
        MvcResult oldDetail = mvc.perform(get("/api/v1/plans/{key}", oldKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(oldDetail).get("status").asText()).isEqualTo("PUBLISHED");
        MvcResult newDetail = mvc.perform(get("/api/v1/plans/{key}", newKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(newDetail).get("status").asText()).isEqualTo("DRAFT");
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", oldKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(chain).get("chain")).hasSize(1);
        assertThat(publishedSlots(sectionA)).hasSize(1);
        assertThat(publishedSlots(sectionB)).isEmpty();
    }
    // ---------- 幂等 ----------

    @Test
    void profileIdempotentReplayAndParamConflict() throws Exception {
        String trainNo = key("TRN");
        String requestId = key("RID");

        MvcResult first = putTrain(trainNo, requestId, "op-1", "ELECTRIC", 0)
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放返回首次完整响应
        MvcResult replay = putTrain(trainNo, requestId, "op-1", "ELECTRIC", 0)
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        MvcResult queried = mvc.perform(get("/api/v1/train-profiles/{trainNo}", trainNo))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(queried).get("version").asInt()).isEqualTo(1);

        // 同键异参 → 409：不同操作者、不同牵引方式、不同期望版本
        putTrain(trainNo, requestId, "op-2", "ELECTRIC", 0).andExpect(status().isConflict());
        putTrain(trainNo, requestId, "op-1", "DIESEL", 0).andExpect(status().isConflict());
        putTrain(trainNo, requestId, "op-1", "ELECTRIC", 1).andExpect(status().isConflict());
    }

    @Test
    void profileFailureDoesNotConsumeRequestId() throws Exception {
        String section = key("SEC");
        putSection(section, key("RID"), "op-1", true, true, 0).andExpect(status().isOk());

        // 版本不匹配失败 → 409，不占键
        String requestId = key("RID");
        putSection(section, requestId, "op-1", false, true, 5).andExpect(status().isConflict());
        // 同键正确参数重试成功，版本加一
        MvcResult retried = putSection(section, requestId, "op-1", false, true, 1)
                .andExpect(status().isOk()).andReturn();
        assertThat(read(retried).get("version").asInt()).isEqualTo(2);
        assertThat(read(retried).get("electrified").asBoolean()).isFalse();
    }

    // ---------- 辅助 ----------

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private ResultActions publishPlan(String scheduleKey) throws Exception {
        return mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))));
    }

    private JsonNode publishedSlots(String sectionId) throws Exception {
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", sectionId))
                .andExpect(status().isOk()).andReturn();
        return read(slots);
    }

    private JsonNode tractionSnapshot(String scheduleKey) throws Exception {
        MvcResult snapshot = mvc.perform(get("/api/v1/plans/{key}/traction-snapshot", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(snapshot).get("snapshots");
    }

    private ResultActions putTrain(String trainNo, String requestId, String operator, String mode,
                                   int expectedVersion) throws Exception {
        return mvc.perform(put("/api/v1/train-profiles/{trainNo}", trainNo)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                        + "\",\"tractionMode\":\"" + mode + "\",\"expectedVersion\":"
                        + expectedVersion + "}"));
    }

    private ResultActions putSection(String sectionId, String requestId, String operator,
                                     boolean electrified, boolean dieselAllowed,
                                     int expectedVersion) throws Exception {
        return mvc.perform(put("/api/v1/section-profiles/{sectionId}", sectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                        + "\",\"electrified\":" + electrified + ",\"dieselAllowed\":"
                        + dieselAllowed + ",\"expectedVersion\":" + expectedVersion + "}"));
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