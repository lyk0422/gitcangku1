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
 * 编组与站台适配的端到端测试（真实 H2 内存库，MODE=MySQL）：
 * 能力登记与版本历史、候选查询与不适配原因、200 米编组申请 180 米站台 422、
 * 改用 220 米供电匹配空闲站台发布、取消释放、窗口重叠 422 不留部分占位、
 * 改签按最终时段校验、发布幂等（改编组/时段 409）与能力版本固化。
 * 每个用例登记唯一站台业务键，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformConsistApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 题干主流程：200 米 → 180 米站台 422，改 220 米空闲站台发布，取消释放 ----------

    @Test
    void consist200RejectedBy180PlatformThenAcceptedBy220AndCancelReleases() throws Exception {
        String plt180 = registerPlatform(180, "AC25KV", true);
        String plt220 = registerPlatform(220, "AC25KV", true);
        String section = key("SEC");
        String scheduleKey = key("SCH");

        // 调度员先查候选：180 米站台不适配（给出原因），220 米站台空闲可用
        JsonNode cand180 = candidate(plt180, 200, "AC25KV", true, 8, 9);
        assertThat(cand180.get("available").asBoolean()).isFalse();
        assertThat(cand180.get("compatible").asBoolean()).isFalse();
        assertThat(cand180.get("incompatibilities")).hasSize(1);
        assertThat(cand180.get("incompatibilities").get(0).get("reasonCode").asText())
                .isEqualTo("LENGTH_EXCEEDED");
        JsonNode cand220 = candidate(plt220, 200, "AC25KV", true, 8, 9);
        assertThat(cand220.get("available").asBoolean()).isTrue();

        // 用 180 米站台登记草稿并发布：422，计划保持草稿，未留下任何站台占位
        createPlatformPlan(scheduleKey, "G1", section, plt180, 200, "AC25KV", true, 8, 9);
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("PLATFORM_INCOMPATIBLE");
        assertThat(detail.get("platformId").asText()).isEqualTo(plt180);
        assertThat(detail.get("reasonCode").asText()).isEqualTo("LENGTH_EXCEEDED");
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
        assertThat(platformOccupancies(plt180)).isEmpty();

        // 改用 220 米且供电匹配的空闲站台（占用整体替换，版本升 2）后发布成功
        mvc.perform(put("/api/v1/plans/{key}/occupancies", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1,
                                platformOcc("G1", section, plt220, 200, "AC25KV", true, 8, 9))))
                .andExpect(status().isOk());
        String publishKey = key("REQ");
        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan = read(published);
        assertThat(plan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(plan.get("occupancies").get(0).get("capabilityVersion").asInt()).isEqualTo(1);

        // 站台管理方可查到占用；候选查询该窗口变为不可用并指出冲突方
        JsonNode occs = platformOccupancies(plt220);
        assertThat(occs).hasSize(1);
        assertThat(occs.get(0).get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(occs.get(0).get("consistLengthMeters").asInt()).isEqualTo(200);
        JsonNode busy = candidate(plt220, 200, "AC25KV", true, 8, 9);
        assertThat(busy.get("available").asBoolean()).isFalse();
        assertThat(busy.get("windowConflicts")).hasSize(1);
        assertThat(busy.get("windowConflicts").get(0).get("scheduleKey").asText())
                .isEqualTo(scheduleKey);

        // 同键重放返回首次站台与占用结果
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(plan);
        assertThat(platformOccupancies(plt220)).hasSize(1);

        // 取消后窗口释放：占用查询为空，同窗口新计划可发布
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        assertThat(platformOccupancies(plt220)).isEmpty();
        assertThat(candidate(plt220, 200, "AC25KV", true, 8, 9).get("available").asBoolean())
                .isTrue();
        String follower = key("SCH");
        createPlatformPlan(follower, "G2", section, plt220, 200, "AC25KV", true, 8, 9);
        publish(follower);
        assertThat(platformOccupancies(plt220)).hasSize(1);
    }

    // ---------- 能力登记、版本历史与幂等 ----------

    @Test
    void platformRegistrationVersionsHistoryAndIdempotency() throws Exception {
        String platformId = key("PLT");
        String registerKey = key("REQ");
        String body = registerBody(registerKey, "一号站台", 220, "[\"AC25KV\"]", true);
        MvcResult first = mvc.perform(put("/api/v1/platforms/{id}", platformId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(first).get("version").asInt()).isEqualTo(1);

        // 同键同参重放返回首次结果
        MvcResult replay = mvc.perform(put("/api/v1/platforms/{id}", platformId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 能力相同（仅供电排列顺序不同，归一化后一致）不升版本
        mvc.perform(put("/api/v1/platforms/{id}", platformId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), "一号站台", 220,
                                "[\"AC25KV\",\"AC25KV\"]", true)))
                .andExpect(status().isOk());
        assertThat(getPlatform(platformId).get("version").asInt()).isEqualTo(1);

        // 能力变更升版本并保留历史
        mvc.perform(put("/api/v1/platforms/{id}", platformId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), "一号站台加长", 260,
                                "[\"AC25KV\",\"DC1500V\"]", false)))
                .andExpect(status().isOk());
        JsonNode current = getPlatform(platformId);
        assertThat(current.get("version").asInt()).isEqualTo(2);
        assertThat(current.get("maxLengthMeters").asInt()).isEqualTo(260);
        assertThat(current.get("powerSystems")).hasSize(2);

        MvcResult v1 = mvc.perform(get("/api/v1/platforms/{id}/capabilities/{v}", platformId, 1))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(v1).get("maxLengthMeters").asInt()).isEqualTo(220);
        assertThat(read(v1).get("accessible").asBoolean()).isTrue();
        mvc.perform(get("/api/v1/platforms/{id}/capabilities/{v}", platformId, 9))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/platforms/{id}", key("PLT")))
                .andExpect(status().isNotFound());

        // 同键不同参 → 409
        mvc.perform(put("/api/v1/platforms/{id}", platformId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(registerKey, "二号站台", 300,
                                "[\"AC25KV\"]", true)))
                .andExpect(status().isConflict());
    }

    // ---------- 历史发布保留当时能力版本 ----------

    @Test
    void publishedPlanKeepsCapabilityVersionAfterPlatformUpgrade() throws Exception {
        String platformId = registerPlatform(220, "AC25KV", true);
        String section = key("SEC");
        String first = key("SCH");
        createPlatformPlan(first, "G1", section, platformId, 200, "AC25KV", false, 8, 9);
        publish(first);
        JsonNode firstDetail = planDetail(first);
        assertThat(firstDetail.get("occupancies").get(0).get("capabilityVersion").asInt())
                .isEqualTo(1);

        // 站台能力升级到 v2（加长并增加直流制式）
        mvc.perform(put("/api/v1/platforms/{id}", platformId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), "加长站台", 280,
                                "[\"AC25KV\",\"DC1500V\"]", true)))
                .andExpect(status().isOk());

        // 历史发布记录仍指向 v1；新发布盖 v2
        JsonNode historyDetail = planDetail(first);
        assertThat(historyDetail.get("occupancies").get(0).get("capabilityVersion").asInt())
                .isEqualTo(1);
        String second = key("SCH");
        createPlatformPlan(second, "G2", key("SEC"), platformId, 210, "DC1500V", false, 10, 11);
        publish(second);
        assertThat(planDetail(second).get("occupancies").get(0).get("capabilityVersion").asInt())
                .isEqualTo(2);
        assertThat(platformOccupancies(platformId)).hasSize(2);
    }

    // ---------- 供电/无障碍/未知站台/窗口重叠失败分支 ----------

    @Test
    void publishRejectsPowerAccessibilityUnknownPlatformAndWindowOverlapAtomically()
            throws Exception {
        String ac220 = registerPlatform(220, "AC25KV", true);
        String noAccessible = registerPlatform(220, "AC25KV", false);
        String section = key("SEC");

        // 供电不兼容 → 422
        String wrongPower = key("SCH");
        createPlatformPlan(wrongPower, "G1", section, ac220, 200, "DC1500V", false, 8, 9);
        expectPlatform422(wrongPower, "POWER_INCOMPATIBLE");
        assertThat(platformOccupancies(ac220)).isEmpty();

        // 无障碍需求不满足 → 422
        String needAccessible = key("SCH");
        createPlatformPlan(needAccessible, "G2", section, noAccessible, 200, "AC25KV", true, 8, 9);
        expectPlatform422(needAccessible, "ACCESSIBILITY_UNAVAILABLE");

        // 未知站台 → 422 PLATFORM_NOT_FOUND，且单站台候选查询也返回该原因
        String unknown = key("SCH");
        createPlatformPlan(unknown, "G3", section, key("PLT-MISSING"), 200, "AC25KV", false, 8, 9);
        expectPlatform422(unknown, "PLATFORM_NOT_FOUND");
        MvcResult missingResult = mvc.perform(get("/api/v1/candidate-platforms")
                        .param("platformId", key("PLT-NEVER"))
                        .param("consistLengthMeters", "200")
                        .param("powerSystem", "AC25KV")
                        .param("accessibleRequired", "false")
                        .param("startUtc", iso(8)).param("endUtc", iso(9)))
                .andExpect(status().isOk()).andReturn();
        JsonNode missingCandidates = read(missingResult);
        assertThat(missingCandidates).hasSize(1);
        assertThat(missingCandidates.get(0).get("available").asBoolean()).isFalse();
        assertThat(missingCandidates.get(0).get("incompatibilities").get(0).get("reasonCode")
                .asText()).isEqualTo("PLATFORM_NOT_FOUND");

        // 先占住 08:00-09:00；重叠窗口 422，相邻窗口合法
        String holder = key("SCH");
        createPlatformPlan(holder, "G4", section, ac220, 200, "AC25KV", false, 8, 9);
        publish(holder);

        // 使用与 holder 不同的区段，隔离出站台同窗口裁决（排除区段冲突干扰）
        String overlap = key("SCH");
        createPlatformPlanWindow(overlap, "G5", key("SEC"), ac220, 200, "AC25KV", false,
                8, 30, 9, 0);
        MvcResult overlapResult = mvc.perform(post("/api/v1/plans/{key}/publish", overlap)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode overlapDetail = read(overlapResult).get("details").get(0);
        assertThat(overlapDetail.get("type").asText()).isEqualTo("PLATFORM_WINDOW_CONFLICT");
        assertThat(overlapDetail.get("conflictingScheduleKey").asText()).isEqualTo(holder);
        assertThat(planStatus(overlap)).isEqualTo("DRAFT");

        // 一条超长 + 一条撞窗口：整体 422，不留下任何部分占位（站台仍只有 holder 一条）
        String partial = key("SCH");
        createPlanRaw(partial,
                platformOccWindow("G6", key("SEC"), ac220, 260, "AC25KV", false, 12, 0, 13, 0),
                platformOccWindow("G7", key("SEC"), ac220, 200, "AC25KV", false, 8, 30, 9, 0));
        mvc.perform(post("/api/v1/plans/{key}/publish", partial)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(platformOccupancies(ac220)).hasSize(1);

        String adjacent = key("SCH");
        createPlatformPlan(adjacent, "G8", key("SEC"), ac220, 200, "AC25KV", false, 9, 10);
        publish(adjacent);
        assertThat(platformOccupancies(ac220)).hasSize(2);
    }

    // ---------- 发布幂等：改编组或时段使用同一 requestKey 返回 409，失败不占键 ----------

    @Test
    void publishRequestKeyRejectsChangedConsistOrWindowAndFailureDoesNotConsumeKey()
            throws Exception {
        String platformId = registerPlatform(220, "AC25KV", true);
        String section = key("SEC");
        String planA = key("SCH");
        createPlatformPlan(planA, "G1", section, platformId, 200, "AC25KV", false, 8, 9);
        String publishKey = key("REQ");
        mvc.perform(post("/api/v1/plans/{key}/publish", planA)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isOk());

        // 另一计划编组长度不同却复用同一发布 requestKey → 409
        String planB = key("SCH");
        createPlatformPlan(planB, "G2", key("SEC"), platformId, 210, "AC25KV", false, 11, 12);
        mvc.perform(post("/api/v1/plans/{key}/publish", planB)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isConflict());

        // 首次失败（180 米站台超长）不占键：站台修正为 220 后同键发布成功
        String plt180 = registerPlatform(180, "AC25KV", true);
        String plt220b = registerPlatform(220, "AC25KV", true);
        String planC = key("SCH");
        String retryKey = key("REQ");
        createPlatformPlan(planC, "G3", key("SEC"), plt180, 200, "AC25KV", false, 14, 15);
        mvc.perform(post("/api/v1/plans/{key}/publish", planC)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(retryKey)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/plans/{key}/occupancies", planC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1,
                                platformOcc("G3", key("SEC"), plt220b, 200, "AC25KV",
                                        false, 14, 15))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{key}/publish", planC)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(retryKey)))
                .andExpect(status().isOk());
    }

    // ---------- 改签：先按最终时段/编组校验再一起更新，失败整体回滚 ----------

    @Test
    void rescheduleValidatesFinalWindowAndReleasesOldOnSuccess() throws Exception {
        String platformId = registerPlatform(220, "AC25KV", true);
        String section = key("SEC");
        String oldKey = key("SCH");
        createPlatformPlan(oldKey, "G1", section, platformId, 200, "AC25KV", false, 8, 9);
        publish(oldKey);

        // 新草稿最终时段与第三方占用重叠 → 422 回滚，旧计划仍占 08:00-09:00
        String thirdParty = key("SCH");
        createPlatformPlan(thirdParty, "G9", section, platformId, 190, "AC25KV", false, 10, 11);
        publish(thirdParty);
        String badNew = key("SCH");
        createPlatformPlanWindow(badNew, "G2", section, platformId, 200, "AC25KV", false,
                10, 30, 11, 30);
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), badNew, 1, 1)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(planStatus(oldKey)).isEqualTo("PUBLISHED");
        assertThat(planStatus(badNew)).isEqualTo("DRAFT");
        assertThat(platformOccupancies(platformId).get(0).get("scheduleKey").asText())
                .isEqualTo(oldKey);

        // 改为空闲的最终时段 09:00-10:00 后改签成功，站台占用切换到新计划
        mvc.perform(put("/api/v1/plans/{key}/occupancies", badNew)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1,
                                platformOcc("G2", section, platformId, 200, "AC25KV",
                                        false, 9, 10))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), badNew, 1, 2)))
                .andExpect(status().isOk());
        assertThat(planStatus(oldKey)).isEqualTo("CANCELLED");
        assertThat(planStatus(badNew)).isEqualTo("PUBLISHED");
        JsonNode occs = platformOccupancies(platformId);
        assertThat(occs).hasSize(2);
        assertThat(occs).extracting(o -> o.get("scheduleKey").asText())
                .contains(thirdParty, badNew);
        // 旧窗口随改签释放
        assertThat(candidate(platformId, 200, "AC25KV", false, 8, 9).get("available").asBoolean())
                .isTrue();
    }

    // ---------- 参数非法 400 ----------

    @Test
    void platformOccupancyRequiresAllConsistFields() throws Exception {
        String platformId = registerPlatform(220, "AC25KV", true);
        // 有站台缺编组字段
        String incomplete = "{\"trainNo\":\"G1\",\"sectionId\":\"" + key("SEC")
                + "\",\"startUtc\":\"" + iso(8) + "\",\"endUtc\":\"" + iso(9)
                + "\",\"platformId\":\"" + platformId + "\"}";
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBodyRaw(key("REQ"), key("SCH"), incomplete)))
                .andExpect(status().isBadRequest());
        // 编组长度非正数
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBodyRaw(key("REQ"), key("SCH"),
                                platformOcc("G1", key("SEC"), platformId, 0, "AC25KV",
                                        true, 8, 9))))
                .andExpect(status().isBadRequest());
        // 候选查询缺供电制式
        mvc.perform(get("/api/v1/candidate-platforms")
                        .param("consistLengthMeters", "200")
                        .param("accessibleRequired", "false")
                        .param("startUtc", iso(8)).param("endUtc", iso(9)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 辅助 ----------

    private void expectPlatform422(String scheduleKey, String reasonCode) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode details = read(result).get("details");
        assertThat(details.get(0).get("type").asText()).isEqualTo("PLATFORM_INCOMPATIBLE");
        assertThat(details.get(0).get("reasonCode").asText()).isEqualTo(reasonCode);
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
    }

    private String registerPlatform(int maxLength, String powerSystems, boolean accessible)
            throws Exception {
        String platformId = key("PLT");
        mvc.perform(put("/api/v1/platforms/{id}", platformId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), platformId + "名称", maxLength,
                                "[\"" + powerSystems + "\"]", accessible)))
                .andExpect(status().isOk());
        return platformId;
    }

    private void createPlatformPlan(String scheduleKey, String train, String section,
                                    String platformId, int length, String power,
                                    boolean accessible, int startHour, int endHour)
            throws Exception {
        createPlanRaw(scheduleKey,
                platformOcc(train, section, platformId, length, power, accessible,
                        startHour, endHour));
    }

    private void createPlatformPlanWindow(String scheduleKey, String train, String section,
                                          String platformId, int length, String power,
                                          boolean accessible,
                                          int startHour, int startMinute,
                                          int endHour, int endMinute) throws Exception {
        createPlanRaw(scheduleKey,
                platformOccWindow(train, section, platformId, length, power, accessible,
                        startHour, startMinute, endHour, endMinute));
    }

    private void createPlanRaw(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBodyRaw(key("REQ"), scheduleKey, occupancies)))
                .andExpect(status().isCreated());
    }

    private void publish(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    private String planStatus(String scheduleKey) throws Exception {
        return planDetail(scheduleKey).get("status").asText();
    }

    private JsonNode planDetail(String scheduleKey) throws Exception {
        return read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode getPlatform(String platformId) throws Exception {
        return read(mvc.perform(get("/api/v1/platforms/{id}", platformId))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode platformOccupancies(String platformId) throws Exception {
        return read(mvc.perform(get("/api/v1/platforms/{id}/occupancies", platformId))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode candidate(String platformId, int length, String power, boolean accessible,
                               int startHour, int endHour) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/candidate-platforms")
                        .param("platformId", platformId)
                        .param("consistLengthMeters", String.valueOf(length))
                        .param("powerSystem", power)
                        .param("accessibleRequired", String.valueOf(accessible))
                        .param("startUtc", iso(startHour)).param("endUtc", iso(endHour)))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = read(result);
        assertThat(array).hasSize(1);
        return array.get(0);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour) {
        return iso(hour, 0);
    }

    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String platformOcc(String train, String section, String platformId,
                                      int length, String power, boolean accessible,
                                      int startHour, int endHour) {
        return platformOccWindow(train, section, platformId, length, power, accessible,
                startHour, 0, endHour, 0);
    }

    private static String platformOccWindow(String train, String section, String platformId,
                                            int length, String power, boolean accessible,
                                            int startHour, int startMinute,
                                            int endHour, int endMinute) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + iso(startHour, startMinute)
                + "\",\"endUtc\":\"" + iso(endHour, endMinute)
                + "\",\"platformId\":\"" + platformId + "\",\"consistLengthMeters\":" + length
                + ",\"powerSystem\":\"" + power + "\",\"accessibleRequired\":" + accessible + "}";
    }

    private static String createBodyRaw(String requestKey, String scheduleKey,
                                        String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":[" + String.join(",", occupancies)
                + "]}";
    }

    private static String updateBody(String requestKey, int expectedVersion, String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"occupancies\":[" + String.join(",", occupancies) + "]}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String registerBody(String requestKey, String name, int maxLength,
                                       String powerSystemsJson, boolean accessible) {
        return "{\"requestKey\":\"" + requestKey + "\",\"platformName\":\"" + name
                + "\",\"maxLengthMeters\":" + maxLength + ",\"powerSystems\":" + powerSystemsJson
                + ",\"accessible\":" + accessible + "}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }
}
