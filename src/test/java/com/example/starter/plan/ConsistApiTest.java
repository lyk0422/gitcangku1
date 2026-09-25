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
 * 列车编组登记与站台联合复核 API 测试（H2 内存库）：编组规范化、参数与状态失败分支、
 * 发布/改签时的长度与同站台重叠裁决、整批回滚与编组幂等边界。
 * 各用例使用唯一 scheduleKey/requestKey/站台/区段 ID，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsistApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 编组登记主流程 ----------

    @Test
    void registerConsistNormalizesCarsAndBumpsVersion() throws Exception {
        String platformA = key("PLA");
        String platformB = key("PLB");
        createPlatform(platformA, 10);
        createPlatform(platformB, 8);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));

        // 车厢乱序且含重复、站台乱序且含重复 → 去重并规范排序
        MvcResult updated = mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), "op-1", 1, 4,
                                "[\"10\",\"2\",\"2\",\"A1\"]",
                                "[\"" + platformB + "\",\"" + platformA + "\",\""
                                        + platformB + "\"]")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(updated);
        assertThat(body.get("version").asInt()).isEqualTo(2);
        assertThat(body.get("consistLength").asInt()).isEqualTo(4);
        assertThat(body.get("cars").get(0).asText()).isEqualTo("2");
        assertThat(body.get("cars").get(1).asText()).isEqualTo("10");
        assertThat(body.get("cars").get(2).asText()).isEqualTo("A1");
        assertThat(body.get("cars")).hasSize(3);
        assertThat(body.get("platformCodes")).hasSize(2);
        assertThat(body.get("platformCodes").get(0).asText()).isEqualTo(platformA);
        assertThat(body.get("platformCodes").get(1).asText()).isEqualTo(platformB);
        assertThat(body.get("platformRisk").asBoolean()).isFalse();

        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);
    }

    // ---------- 参数与状态失败分支 ----------

    @Test
    void consistUpdateRejectsInvalidParams() throws Exception {
        String platform = key("PLA");
        createPlatform(platform, 10);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));

        // 编组长度非正
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), "op-1", 1, 0,
                                "[\"1\"]", "[\"" + platform + "\"]")))
                .andExpect(status().isBadRequest());
        // 空车厢集合
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), "op-1", 1, 2,
                                "[]", "[\"" + platform + "\"]")))
                .andExpect(status().isBadRequest());
        // 空白车厢编号
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), "op-1", 1, 2,
                                "[\" \"]", "[\"" + platform + "\"]")))
                .andExpect(status().isBadRequest());
        // 缺操作者
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"expectedVersion\":1"
                                + ",\"consistLength\":2,\"cars\":[\"1\"]"
                                + ",\"platformCodes\":[\"" + platform + "\"]}"))
                .andExpect(status().isBadRequest());
        // 站台不存在 → 可区分错误码
        MvcResult unknown = mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), "op-1", 1, 2,
                                "[\"1\"]", "[\"" + key("PLX") + "\"]")))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(read(unknown).get("code").asText()).isEqualTo("PLATFORM_NOT_FOUND");

        // 全部失败不留半成品：计划仍是版本 1 且无编组
        JsonNode detail = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(detail.get("version").asInt()).isEqualTo(1);
        assertThat(detail.get("consistLength").isNull()).isTrue();
        assertThat(detail.get("cars")).isEmpty();
    }

    @Test
    void consistUpdateRejectsVersionAndStateConflicts() throws Exception {
        String platform = key("PLA");
        createPlatform(platform, 10);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));

        // 版本不匹配
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), "op-1", 5, 2,
                                "[\"1\"]", "[\"" + platform + "\"]")))
                .andExpect(status().isConflict());

        // 已发布（无风险）不可变更编组
        publishPlan(scheduleKey, key("REQ"));
        MvcResult published = mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), "op-1", 1, 2,
                                "[\"1\"]", "[\"" + platform + "\"]")))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(published).get("code").asText()).isEqualTo("PLAN_STATE_CONFLICT");

        // 已取消不可变更编组
        String cancelled = key("SCH");
        createPlan(cancelled, occ("G1", key("SEC"), iso(8), iso(9)));
        publishPlan(cancelled, key("REQ"));
        cancelPlan(cancelled, key("REQ"));
        MvcResult cancelledResult = mvc.perform(put("/api/v1/plans/{key}/consist", cancelled)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), "op-1", 1, 2,
                                "[\"1\"]", "[\"" + platform + "\"]")))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(cancelledResult).get("code").asText()).isEqualTo("PLAN_STATE_CONFLICT");

        // 计划不存在 → 404
        mvc.perform(put("/api/v1/plans/{key}/consist", key("SCH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), "op-1", 1, 2,
                                "[\"1\"]", "[\"" + platform + "\"]")))
                .andExpect(status().isNotFound());
    }

    // ---------- 编组幂等 ----------

    @Test
    void consistIdempotentReplayAndFingerprint() throws Exception {
        String platform = key("PLA");
        createPlatform(platform, 10);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));
        String requestKey = key("REQ");

        MvcResult first = mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(requestKey, "op-1", 1, 3,
                                "[\"2\",\"10\"]", "[\"" + platform + "\"]")))
                .andExpect(status().isOk()).andReturn();

        // 同键同参（车厢乱序加重复，规范化后相同）→ 重放首次完整响应，版本不重复加一
        MvcResult replay = mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(requestKey, "op-1", 1, 3,
                                "[\"10\",\"2\",\"2\"]", "[\"" + platform + "\"]")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        JsonNode detail = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(detail.get("version").asInt()).isEqualTo(2);

        // 同键异参（编组长度不同）→ 409
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(requestKey, "op-1", 1, 4,
                                "[\"2\",\"10\"]", "[\"" + platform + "\"]")))
                .andExpect(status().isConflict());
        // 同键异参（操作者不同）→ 409
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(requestKey, "op-2", 1, 3,
                                "[\"2\",\"10\"]", "[\"" + platform + "\"]")))
                .andExpect(status().isConflict());
    }

    @Test
    void consistFailureDoesNotOccupyRequestKey() throws Exception {
        String platform = key("PLA");
        createPlatform(platform, 10);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));
        String requestKey = key("REQ");

        // 版本不匹配失败不占键
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(requestKey, "op-1", 9, 2,
                                "[\"1\"]", "[\"" + platform + "\"]")))
                .andExpect(status().isConflict());
        // 修正参数后同键成功
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(requestKey, "op-1", 1, 2,
                                "[\"1\"]", "[\"" + platform + "\"]")))
                .andExpect(status().isOk());
    }

    // ---------- 发布联合复核 ----------

    @Test
    void publishRejectsOverLengthConsist() throws Exception {
        String platform = key("PLA");
        createPlatform(platform, 4);
        String scheduleKey = key("SCH");
        String publishKey = key("REQ");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));
        updateConsist(scheduleKey, 1, 5, "[\"1\",\"2\",\"3\",\"4\",\"5\"]",
                "[\"" + platform + "\"]");

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("PLATFORM_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("PLATFORM_LENGTH_EXCEEDED");
        assertThat(detail.get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(detail.get("platformCode").asText()).isEqualTo(platform);
        assertThat(detail.get("consistLength").asInt()).isEqualTo(5);
        assertThat(detail.get("platformLength").asInt()).isEqualTo(4);

        // 计划保持草稿；失败不占键，缩短编组后同键发布成功
        JsonNode plan = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(plan.get("status").asText()).isEqualTo("DRAFT");
        updateConsist(scheduleKey, 2, 4, "[\"1\",\"2\",\"3\",\"4\"]",
                "[\"" + platform + "\"]");
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isOk());
    }

    @Test
    void publishRejectsSamePlatformOverlapButAllowsAdjacent() throws Exception {
        String platform = key("PLA");
        createPlatform(platform, 10);
        String first = key("SCH");
        createPlan(first, occ("G1", key("SEC"), iso(8), iso(10)));
        updateConsist(first, 1, 2, "[\"1\",\"2\"]", "[\"" + platform + "\"]");
        publishPlan(first, key("REQ"));

        // 同站台窗口重叠（不同区段，排除时隙冲突干扰）→ 422
        String overlapping = key("SCH");
        createPlan(overlapping, occ("G2", key("SEC"), iso(9), iso(11)));
        updateConsist(overlapping, 1, 2, "[\"1\",\"2\"]", "[\"" + platform + "\"]");
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", overlapping)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("PLATFORM_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("PLATFORM_OVERLAP");
        assertThat(detail.get("platformCode").asText()).isEqualTo(platform);
        assertThat(detail.get("scheduleKey").asText()).isEqualTo(overlapping);
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(first);

        // 相邻窗口（10:00 紧接）合法
        String adjacent = key("SCH");
        createPlan(adjacent, occ("G3", key("SEC"), iso(10), iso(12)));
        updateConsist(adjacent, 1, 2, "[\"1\",\"2\"]", "[\"" + platform + "\"]");
        publishPlan(adjacent, key("REQ"));

        // 无编组计划不受站台复核影响
        String noConsist = key("SCH");
        createPlan(noConsist, occ("G4", key("SEC"), iso(8), iso(10)));
        publishPlan(noConsist, key("REQ"));
    }

    // ---------- 改签联合复核与整批回滚 ----------

    @Test
    void reschedulePlatformViolationRollsBackWholeBatch() throws Exception {
        String platform = key("PLA");
        createPlatform(platform, 4);
        String section = key("SEC");
        String oldKey = key("SCH");
        String newKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        createPlan(newKey, occ("G2", section, iso(8), iso(9)));
        updateConsist(newKey, 1, 5, "[\"1\",\"2\",\"3\",\"4\",\"5\"]",
                "[\"" + platform + "\"]");
        String rescheduleKey = key("REQ");

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(rescheduleKey, newKey, 1, 2)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("PLATFORM_CONFLICT");
        assertThat(error.get("details").get(0).get("type").asText())
                .isEqualTo("PLATFORM_LENGTH_EXCEEDED");
        assertThat(error.get("details").get(0).get("scheduleKey").asText()).isEqualTo(newKey);

        // 整批不写入：旧计划仍发布、新计划仍草稿且版本不变、无改签关联
        JsonNode oldPlan = read(mvc.perform(get("/api/v1/plans/{key}", oldKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(oldPlan.get("status").asText()).isEqualTo("PUBLISHED");
        JsonNode newPlan = read(mvc.perform(get("/api/v1/plans/{key}", newKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(newPlan.get("status").asText()).isEqualTo("DRAFT");
        assertThat(newPlan.get("version").asInt()).isEqualTo(2);
        JsonNode chain = read(mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", oldKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(chain.get("chain")).hasSize(1);

        // 失败不占键：缩短编组后同键改签成功
        updateConsist(newKey, 2, 4, "[\"1\",\"2\",\"3\",\"4\"]", "[\"" + platform + "\"]");
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(rescheduleKey, newKey, 1, 3)))
                .andExpect(status().isOk());
    }

    @Test
    void rescheduleSamePlatformOverlapRollsBackWholeBatch() throws Exception {
        String platform = key("PLA");
        createPlatform(platform, 10);
        // 第三方已发布计划占用站台 09:00-11:00
        String thirdParty = key("SCH");
        createPlan(thirdParty, occ("G9", key("SEC"), iso(9), iso(11)));
        updateConsist(thirdParty, 1, 2, "[\"1\",\"2\"]", "[\"" + platform + "\"]");
        publishPlan(thirdParty, key("REQ"));

        String oldKey = key("SCH");
        String newKey = key("SCH");
        createPlan(oldKey, occ("G1", key("SEC"), iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        // 新草稿与第三方同站台窗口重叠（区段不同，排除时隙冲突干扰）
        createPlan(newKey, occ("G2", key("SEC"), iso(10), iso(12)));
        updateConsist(newKey, 1, 2, "[\"1\",\"2\"]", "[\"" + platform + "\"]");

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 2)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("PLATFORM_CONFLICT");
        assertThat(error.get("details").get(0).get("type").asText())
                .isEqualTo("PLATFORM_OVERLAP");
        assertThat(error.get("details").get(0).get("conflictingScheduleKey").asText())
                .isEqualTo(thirdParty);

        // 整批回滚：旧仍发布、新仍草稿
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", oldKey))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("PUBLISHED");
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", newKey))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("DRAFT");
    }

    // ---------- 站台占用查询 ----------

    @Test
    void platformOccupancyQueryReturnsPublishedWindows() throws Exception {
        String platform = key("PLA");
        createPlatform(platform, 10);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)),
                occ("G1", key("SEC"), iso(10), iso(11)));
        updateConsist(scheduleKey, 1, 2, "[\"1\",\"2\"]", "[\"" + platform + "\"]");
        publishPlan(scheduleKey, key("REQ"));

        // 草稿计划不计入站台占用
        String draft = key("SCH");
        createPlan(draft, occ("G2", key("SEC"), iso(12), iso(13)));
        updateConsist(draft, 1, 2, "[\"1\",\"2\"]", "[\"" + platform + "\"]");

        MvcResult occupancy = mvc.perform(get("/api/v1/platforms/{code}/occupancy", platform)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode rows = read(occupancy);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(rows.get(0).get("platformCode").asText()).isEqualTo(platform);
        assertThat(rows.get(0).get("consistLength").asInt()).isEqualTo(2);
        // 占用窗口为计划区段占用整体跨度 08:00-11:00
        assertThat(rows.get(0).get("startUtc").asText()).isEqualTo(iso(8));
        assertThat(rows.get(0).get("endUtc").asText()).isEqualTo(iso(11));

        // 站台不存在 → 404
        mvc.perform(get("/api/v1/platforms/{code}/occupancy", key("PLX"))
                        .param("date", DAY.toString()))
                .andExpect(status().isNotFound());
    }

    // ---------- 辅助 ----------

    private void createPlatform(String code, int effectiveLength) throws Exception {
        mvc.perform(post("/api/v1/platforms").contentType(MediaType.APPLICATION_JSON)
                        .content(platformBody(key("REQ"), "op-1", code, effectiveLength)))
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

    private void cancelPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private void updateConsist(String scheduleKey, int expectedVersion, int consistLength,
                               String carsJson, String platformsJson) throws Exception {
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), "op-1", expectedVersion, consistLength,
                                carsJson, platformsJson)))
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

    private static String createBody(String requestKey, String scheduleKey, String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":[" + String.join(",", occupancies)
                + "]}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String consistBody(String requestKey, String operator, int expectedVersion,
                                      int consistLength, String carsJson, String platformsJson) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"expectedVersion\":" + expectedVersion
                + ",\"consistLength\":" + consistLength
                + ",\"cars\":" + carsJson + ",\"platformCodes\":" + platformsJson + "}";
    }

    private static String platformBody(String requestKey, String operator, String code,
                                       int effectiveLength) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"code\":\"" + code + "\",\"effectiveLength\":" + effectiveLength + "}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }
}
