package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 编组与站台适配端到端测试（真实 H2 内存库）：长度/制式/无障碍不适配 422、
 * 到发窗口重叠 422 且不留部分占位、候选站台与原因查询、取消释放窗口、
 * 能力版本快照、发布幂等重放与改编组/时段 409。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformConsistApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 23);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 题干主流程 ----------

    @Test
    void consist200mRejectedAt180mPlatformThenPublishedAt220mAndReleasedOnCancel() throws Exception {
        // 用唯一站台复刻 180/220 两座站台，避免与其他用例共享窗口
        String plt180 = registerPlatform(180, "AC25KV", true);
        String plt220 = registerPlatform(220, "AC25KV", true);

        // 200 米、AC25KV、无障碍需求的编组，申请 180 米站台
        String scheduleKey = key("SCH");
        createConsistPlan(scheduleKey, plt180, 200, "AC25KV", true, 8, 9);
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode mismatch = error.get("details").get(0);
        assertThat(mismatch.get("type").asText()).isEqualTo("PLATFORM_MISMATCH");
        assertThat(mismatch.get("platformId").asText()).isEqualTo(plt180);
        assertThat(mismatch.get("reasons")).map(JsonNode::asText)
                .containsExactly("LENGTH_EXCEEDED");

        // 不留任何占位登记，计划保持草稿
        assertThat(detail(scheduleKey).get("status").asText()).isEqualTo("DRAFT");
        assertThat(bookingCount(scheduleKey)).isZero();
        assertThat(activeBookings(plt180)).isEmpty();

        // 候选站台：180 不适配（原因明确），220 可用
        JsonNode candidates = read(mvc.perform(get("/api/v1/plans/{key}/candidate-platforms", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        JsonNode candidate180 = findCandidate(candidates, plt180);
        assertThat(candidate180.get("compatible").asBoolean()).isFalse();
        assertThat(candidate180.get("available").asBoolean()).isFalse();
        assertThat(candidate180.get("reasons")).map(JsonNode::asText).contains("LENGTH_EXCEEDED");
        assertThat(findCandidate(candidates, plt220).get("available").asBoolean()).isTrue();

        // 改用 220 米且供电匹配的空闲站台（整体替换草稿占用）后可发布
        replaceWithPlatform(scheduleKey, plt220, 8, 9);
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        JsonNode published = detail(scheduleKey);
        assertThat(published.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.get("occupancies").get(0).get("platformId").asText()).isEqualTo(plt220);

        // 站台管理方可查占用，登记快照当时能力版本 1
        JsonNode bookings = activeBookings(plt220);
        assertThat(bookings).hasSize(1);
        assertThat(bookings.get(0).get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(bookings.get(0).get("capabilityVersion").asInt()).isEqualTo(1);

        // 取消后窗口释放
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        assertThat(activeBookings(plt220)).isEmpty();
        // 登记历史保留（含能力版本快照）
        Integer historyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_platform_booking b JOIN rail_day_plan p ON p.id = b.plan_id"
                        + " WHERE p.schedule_key = ?", Integer.class, scheduleKey);
        assertThat(historyRows).isEqualTo(1);

        // 窗口已释放：新计划可同时段发布同一站台
        String follower = key("SCH");
        createConsistPlan(follower, plt220, 200, "AC25KV", true, 8, 9);
        mvc.perform(post("/api/v1/plans/{key}/publish", follower)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        assertThat(activeBookings(plt220)).hasSize(1);
    }

    // ---------- 制式 / 无障碍不适配 ----------

    @Test
    void powerAndAccessibilityMismatchesReturn422WithReasons() throws Exception {
        String acOnly = registerPlatform(300, "AC25KV", false);

        // 直流制式不兼容
        String dcPlan = key("SCH");
        createConsistPlan(dcPlan, acOnly, 200, "DC1500V", false, 8, 9);
        JsonNode dcError = publishExpect422(dcPlan);
        assertThat(firstMismatch(dcError, acOnly).get("reasons")).map(JsonNode::asText)
                .containsExactly("POWER_SUPPLY_INCOMPATIBLE");
        assertThat(bookingCount(dcPlan)).isZero();

        // 无障碍需求不满足（同一站台，编组改交流后仅差无障碍）
        String accessiblePlan = key("SCH");
        createConsistPlan(accessiblePlan, acOnly, 200, "AC25KV", true, 10, 11);
        JsonNode accessError = publishExpect422(accessiblePlan);
        assertThat(firstMismatch(accessError, acOnly).get("reasons")).map(JsonNode::asText)
                .containsExactly("ACCESSIBLE_UNAVAILABLE");
        assertThat(bookingCount(accessiblePlan)).isZero();

        // 无供电需求（NONE）且无无障碍要求时该站台可用
        String nonePlan = key("SCH");
        createConsistPlan(nonePlan, acOnly, 200, "NONE", false, 12, 13);
        mvc.perform(post("/api/v1/plans/{key}/publish", nonePlan)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    // ---------- 窗口重叠 422 与相邻合法、不留部分占位 ----------

    @Test
    void windowOverlapReturns422AdjacentAllowedAndNoPartialBooking() throws Exception {
        String platform = registerPlatform(300, "AC25KV", true);
        String first = key("SCH");
        createConsistPlan(first, platform, 200, "AC25KV", false, 8, 9);
        publishOk(first);

        // 重叠窗口 08:30-09:30 → 422
        String overlapping = key("SCH");
        createConsistPlan(overlapping, platform, 200, "AC25KV", false, 8, 30, 9, 30);
        JsonNode error = publishExpect422(overlapping);
        JsonNode conflict = error.get("details").get(0);
        assertThat(conflict.get("type").asText()).isEqualTo("PLATFORM_WINDOW_CONFLICT");
        assertThat(conflict.get("conflictingScheduleKey").asText()).isEqualTo(first);
        assertThat(bookingCount(overlapping)).isZero();
        assertThat(detail(overlapping).get("status").asText()).isEqualTo("DRAFT");

        // 候选查询体现 WINDOW_CONFLICT 与冲突明细
        JsonNode candidates = read(mvc.perform(
                        get("/api/v1/plans/{key}/candidate-platforms", overlapping))
                .andExpect(status().isOk()).andReturn());
        JsonNode candidate = findCandidate(candidates, platform);
        assertThat(candidate.get("compatible").asBoolean()).isTrue();
        assertThat(candidate.get("available").asBoolean()).isFalse();
        assertThat(candidate.get("reasons")).map(JsonNode::asText).contains("WINDOW_CONFLICT");
        assertThat(candidate.get("conflictingBookings")).hasSize(1);
        assertThat(candidate.get("conflictingBookings").get(0).get("scheduleKey").asText())
                .isEqualTo(first);

        // 多条到发中第二条与已发布窗口重叠 → 全部回滚，不留部分占位
        String multi = key("SCH");
        createConsistPlan(multi, platform, 200, "AC25KV", false,
                new int[][]{new int[]{10, 0, 11, 0}, new int[]{8, 30, 8, 45}});
        publishExpect422(multi);
        assertThat(bookingCount(multi)).isZero();

        // 相邻窗口 09:00-10:00（左闭右开）合法
        String adjacent = key("SCH");
        createConsistPlan(adjacent, platform, 200, "AC25KV", false, 9, 10);
        publishOk(adjacent);
        assertThat(activeBookings(platform)).hasSize(2);
    }

    // ---------- 幂等：重放首次站台/占用结果；改编组或时段 409 ----------

    @Test
    void publishIdempotentReplayKeepsPlatformResultAndRejectsChangedContent() throws Exception {
        String platform = registerPlatform(250, "AC25KV", true);
        String scheduleKey = key("SCH");
        createConsistPlan(scheduleKey, platform, 200, "AC25KV", false, 8, 9);
        String publishKey = key("REQ");

        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        assertThat(bookingCount(scheduleKey)).isEqualTo(1);

        // 时段变更后用同一 requestKey 发布（草稿需先取消重建才能再改，这里直接验证新草稿同键）
        String changed = key("SCH");
        createConsistPlan(changed, platform, 200, "AC25KV", false, 11, 12);
        mvc.perform(post("/api/v1/plans/{key}/publish", changed)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isConflict());

        // 编组长度变更后同键同样 409
        String changedConsist = key("SCH");
        createConsistPlan(changedConsist, platform, 210, "AC25KV", false, 13, 14);
        mvc.perform(post("/api/v1/plans/{key}/publish", changedConsist)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isConflict());
    }

    // ---------- 能力版本快照与历史保留 ----------

    @Test
    void capabilityVersionIsSnapshottedAndHistoryKeepsOldVersion() throws Exception {
        String platform = key("PLT");
        // 版本 1：180 米
        registerCapability(platform, 180, "AC25KV", true);
        String shortConsist = key("SCH");
        createConsistPlan(shortConsist, platform, 180, "AC25KV", false, 7, 8);
        publishOk(shortConsist);
        JsonNode bookingV1 = activeBookings(platform).get(0);
        assertThat(bookingV1.get("capabilityVersion").asInt()).isEqualTo(1);

        // 能力升级到版本 2：260 米
        JsonNode v2 = read(mvc.perform(post("/api/v1/platforms/{id}/capabilities", platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(capabilityBody(key("REQ"), platform, 260, "AC25KV", true)))
                .andExpect(status().isCreated()).andReturn());
        assertThat(v2.get("version").asInt()).isEqualTo(2);

        // 同键同参重放能力登记返回首次结果；同键不同参 409
        String capKey = key("REQ");
        MvcResult capFirst = mvc.perform(post("/api/v1/platforms/{id}/capabilities", platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(capabilityBody(capKey, platform, 300, "AC25KV", true)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(capFirst).get("version").asInt()).isEqualTo(3);
        MvcResult capReplay = mvc.perform(post("/api/v1/platforms/{id}/capabilities", platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(capabilityBody(capKey, platform, 300, "AC25KV", true)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(capReplay)).isEqualTo(read(capFirst));
        mvc.perform(post("/api/v1/platforms/{id}/capabilities", platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(capabilityBody(capKey, platform, 280, "AC25KV", true)))
                .andExpect(status().isConflict());

        // 取消旧计划后，新登记快照新版本，旧历史登记保留版本 1
        mvc.perform(post("/api/v1/plans/{key}/cancel", shortConsist)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        String longConsist = key("SCH");
        createConsistPlan(longConsist, platform, 260, "AC25KV", false, 7, 8);
        publishOk(longConsist);
        JsonNode bookingNow = activeBookings(platform).get(0);
        assertThat(bookingNow.get("scheduleKey").asText()).isEqualTo(longConsist);
        assertThat(bookingNow.get("capabilityVersion").asInt()).isEqualTo(3);
        Integer oldVersionKept = jdbc.queryForObject(
                "SELECT capability_version FROM rail_platform_booking b"
                        + " JOIN rail_day_plan p ON p.id = b.plan_id"
                        + " WHERE p.schedule_key = ?", Integer.class, shortConsist);
        assertThat(oldVersionKept).isEqualTo(1);
    }

    // ---------- 改签按最终时段校验并整体更新 ----------

    @Test
    void rescheduleValidatesFinalPlatformWindowAndRegistersTogether() throws Exception {
        String platformA = registerPlatform(300, "AC25KV", true);
        String platformB = registerPlatform(300, "AC25KV", true);
        String oldKey = key("SCH");
        createConsistPlan(oldKey, platformA, 200, "AC25KV", false, 8, 9);
        publishOk(oldKey);

        // 第三方占住 platformB 的 10:00-11:00
        String third = key("SCH");
        createConsistPlan(third, platformB, 200, "AC25KV", false, 10, 11);
        publishOk(third);

        // 新草稿最终时段与第三方冲突 → 改签 422 整体回滚
        String newKey = key("SCH");
        createConsistPlan(newKey, platformB, 200, "AC25KV", false, 10, 30, 11, 30);
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(detail(oldKey).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(detail(newKey).get("status").asText()).isEqualTo("DRAFT");
        assertThat(bookingCount(newKey)).isZero();

        // 修正为空闲时段后改签成功：旧取消释放 platformA，新发布登记 platformB
        replaceWithPlatform(newKey, platformB, 11, 12);
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 2)))
                .andExpect(status().isOk());
        assertThat(activeBookings(platformA)).isEmpty();
        JsonNode bookingsB = activeBookings(platformB);
        assertThat(bookingsB).hasSize(2);
        assertThat(bookingsB).map(b -> b.get("scheduleKey").asText())
                .containsExactlyInAnyOrder(third, newKey);
    }

    // ---------- 参数与状态校验 ----------

    @Test
    void invalidConsistRequestsRejected() throws Exception {
        String platform = registerPlatform(300, "AC25KV", true);

        // 含编组但占用未带站台 → 400
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(key("REQ"), key("SCH"),
                                "{\"lengthM\":200,\"powerSupplyType\":\"AC25KV\",\"accessibleRequired\":false}",
                                occ("G1", key("SEC"), null, iso(8), iso(9)))))
                .andExpect(status().isBadRequest());

        // 未登记编组却携带站台 → 400
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(key("REQ"), key("SCH"), null,
                                occ("G1", key("SEC"), platform, iso(8), iso(9)))))
                .andExpect(status().isBadRequest());

        // 未知供电制式 → 400
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(key("REQ"), key("SCH"),
                                "{\"lengthM\":200,\"powerSupplyType\":\"MAGIC\",\"accessibleRequired\":false}",
                                occ("G1", key("SEC"), platform, iso(8), iso(9)))))
                .andExpect(status().isBadRequest());

        // 编组长度非正 → 400
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(key("REQ"), key("SCH"),
                                "{\"lengthM\":0,\"powerSupplyType\":\"AC25KV\",\"accessibleRequired\":false}",
                                occ("G1", key("SEC"), platform, iso(8), iso(9)))))
                .andExpect(status().isBadRequest());

        // 向未登记编组的计划查询候选站台 → 400
        String plain = key("SCH");
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(key("REQ"), plain, null,
                                occ("G1", key("SEC"), null, iso(8), iso(9)))))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/plans/{key}/candidate-platforms", plain))
                .andExpect(status().isBadRequest());

        // 不存在站台查询占用 → 404
        mvc.perform(get("/api/v1/platforms/{id}/bookings", key("NOPE")))
                .andExpect(status().isNotFound());
    }

    // ---------- 辅助 ----------

    private String registerPlatform(int maxLengthM, String powerType, boolean accessible)
            throws Exception {
        String platformId = key("PLT");
        registerCapability(platformId, maxLengthM, powerType, accessible);
        return platformId;
    }

    private void registerCapability(String platformId, int maxLengthM, String powerType,
                                    boolean accessible) throws Exception {
        mvc.perform(post("/api/v1/platforms/{id}/capabilities", platformId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(capabilityBody(key("REQ"), platformId, maxLengthM,
                                List.of(powerType), accessible)))
                .andExpect(status().isCreated());    }

    private void createConsistPlan(String scheduleKey, String platformId, int lengthM,
                                   String powerType, boolean accessible, int startHour,
                                   int endHour) throws Exception {
        createConsistPlan(scheduleKey, platformId, lengthM, powerType, accessible,
                new int[][]{new int[]{startHour, 0, endHour, 0}});
    }

    private void createConsistPlan(String scheduleKey, String platformId, int lengthM,
                                   String powerType, boolean accessible, int sh, int sm,
                                   int eh, int em) throws Exception {
        createConsistPlan(scheduleKey, platformId, lengthM, powerType, accessible,
                new int[][]{new int[]{sh, sm, eh, em}});
    }

    private void createConsistPlan(String scheduleKey, String platformId, int lengthM,
                                   String powerType, boolean accessible, int[][] windows)
            throws Exception {
        String[] occs = new String[windows.length];
        for (int i = 0; i < windows.length; i++) {
            int[] w = windows[i];
            occs[i] = occ("G" + i, key("SEC"), platformId, iso(w[0], w[1]), iso(w[2], w[3]));
        }
        String consist = "{\"lengthM\":" + lengthM + ",\"powerSupplyType\":\"" + powerType
                + "\",\"accessibleRequired\":" + accessible + "}";
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(key("REQ"), scheduleKey, consist, occs)))
                .andExpect(status().isCreated());
    }

    private void replaceWithPlatform(String scheduleKey, String platformId, int startHour,
                                     int endHour) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/plans/{key}/occupancies", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1,
                                occ("G1", key("SEC"), platformId, iso(startHour), iso(endHour)))))
                .andExpect(status().isOk());
    }

    private void publishOk(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    private JsonNode publishExpect422(String scheduleKey) throws Exception {
        return read(mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn());
    }

    private JsonNode firstMismatch(JsonNode error, String platformId) {
        for (JsonNode detail : error.get("details")) {
            if ("PLATFORM_MISMATCH".equals(detail.get("type").asText())
                    && platformId.equals(detail.get("platformId").asText())) {
                return detail;
            }
        }
        throw new AssertionError("未找到站台 " + platformId + " 的 PLATFORM_MISMATCH 明细");
    }

    private JsonNode findCandidate(JsonNode candidates, String platformId) {
        for (JsonNode candidate : candidates) {
            if (platformId.equals(candidate.get("platformId").asText())) {
                return candidate;
            }
        }
        throw new AssertionError("候选站台中不存在 " + platformId);
    }

    private JsonNode detail(String scheduleKey) throws Exception {
        return read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode activeBookings(String platformId) throws Exception {
        return read(mvc.perform(get("/api/v1/platforms/{id}/bookings", platformId))
                .andExpect(status().isOk()).andReturn());
    }

    private Integer bookingCount(String scheduleKey) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_platform_booking b JOIN rail_day_plan p ON p.id = b.plan_id"
                        + " WHERE p.schedule_key = ?", Integer.class, scheduleKey);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour, int minute) {
        Instant instant = DAY.atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String iso(int hour) {
        return iso(hour, 0);
    }

    private static String occ(String train, String section, String platformId,
                              String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section + "\","
                + "\"platformId\":" + (platformId == null ? "null" : "\"" + platformId + "\"")
                + ",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String planBody(String requestKey, String scheduleKey, String consistJson,
                                   String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\","
                + (consistJson == null ? "\"consist\":null," : "\"consist\":" + consistJson + ",")
                + "\"occupancies\":[" + String.join(",", occupancies) + "]}";
    }

    private static String updateBody(String requestKey, int expectedVersion, String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"occupancies\":[" + String.join(",", occupancies) + "]}";
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

    private String capabilityBody(String requestKey, String platformId, int maxLengthM,
                                  String powerType, boolean accessible) {
        return capabilityBody(requestKey, platformId, maxLengthM, List.of(powerType), accessible);
    }

    private String capabilityBody(String requestKey, String platformId, int maxLengthM,
                                  List<String> powerTypes, boolean accessible) {
        String types = powerTypes.stream().map(t -> "\"" + t + "\"").reduce((a, b) -> a + "," + b)
                .orElse("");
        return "{\"requestKey\":\"" + requestKey + "\",\"platformId\":\"" + platformId
                + "\",\"maxLengthM\":" + maxLengthM + ",\"supportedPowerTypes\":[" + types
                + "],\"accessible\":" + accessible + "}";
    }
}
