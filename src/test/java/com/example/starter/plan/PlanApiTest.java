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
 * 日计划 API 主流程、失败分支与幂等边界的端到端测试（H2 内存库）。
 * 各用例使用唯一 scheduleKey/requestKey/区段 ID，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程 ----------

    @Test
    void createDraftThenGetDetail() throws Exception {
        String scheduleKey = key("SCH");
        MvcResult created = mvc.perform(post("/api/v1/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey,
                                occ("G1", key("SEC"), iso(8), iso(9)),
                                occ("G2", key("SEC"), iso(9), iso(10)))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = read(created);
        assertThat(body.get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("status").asText()).isEqualTo("DRAFT");
        assertThat(body.get("occupancies")).hasSize(2);
        assertThat(body.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(8));

        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);
    }

    @Test
    void updateReplaceOccupanciesIncrementsVersion() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));

        MvcResult updated = mvc.perform(put("/api/v1/plans/{key}/occupancies", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1,
                                occ("G3", key("SEC"), iso(10), iso(11)))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(updated);
        assertThat(body.get("version").asInt()).isEqualTo(2);
        assertThat(body.get("occupancies")).hasSize(1);
        assertThat(body.get("occupancies").get(0).get("trainNo").asText()).isEqualTo("G3");
    }

    @Test
    void publishThenQueryPublishedSlots() throws Exception {
        String section = key("SEC");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(scheduleKey, key("REQ"));

        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("status").asText()).isEqualTo("PUBLISHED");

        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        JsonNode slotList = read(slots);
        assertThat(slotList).hasSize(1);
        assertThat(slotList.get(0).get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(slotList.get(0).get("trainNo").asText()).isEqualTo("G1");
    }

    @Test
    void cancelReleasesSlotsAndKeepsHistory() throws Exception {
        String section = key("SEC");
        String released = key("SCH");
        createPlan(released, occ("G1", section, iso(8), iso(9)));
        publishPlan(released, key("REQ"));

        mvc.perform(post("/api/v1/plans/{key}/cancel", released)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"))))
                .andExpect(status().isOk());

        // 历史计划与原始占用保留
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", released))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(detail);
        assertThat(body.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("occupancies")).hasSize(1);

        // 时隙已释放：查询为空，且新计划可发布重叠区间
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).isEmpty();

        String follower = key("SCH");
        createPlan(follower, occ("G9", section, iso(8), iso(9)));
        publishPlan(follower, key("REQ"));
    }

    // ---------- 参数非法 400 ----------

    @Test
    void createRejectsInvalidOccupancies() throws Exception {
        // 结束等于开始
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"),
                                occ("G1", key("SEC"), iso(8), iso(8)))))
                .andExpect(status().isBadRequest());
        // 结束早于开始
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"),
                                occ("G1", key("SEC"), iso(9), iso(8)))))
                .andExpect(status().isBadRequest());
        // 跨运营日（Asia/Shanghai）
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"),
                                occ("G1", key("SEC"), iso(23), iso(1, 1)))))
                .andExpect(status().isBadRequest());
        // 占用不在计划运营日内
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"),
                                occ("G1", key("SEC"), iso(8, 1), iso(9, 1)))))
                .andExpect(status().isBadRequest());
        // 空占用清单
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"))))
                .andExpect(status().isBadRequest());
        // 31 条占用超过上限
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"), manyOccs(31))))
                .andExpect(status().isBadRequest());
        // 缺 requestKey
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleKey\":\"" + key("SCH") + "\",\"opDate\":\"" + DAY
                                + "\",\"occupancies\":[" + occ("G1", key("SEC"), iso(8), iso(9))
                                + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAcceptsThirtyOccupanciesAndMidnightBoundary() throws Exception {
        // 30 条占用合法
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"), manyOccs(30))))
                .andExpect(status().isCreated());
        // 结束恰为次日 00:00（左闭右开，仍落在运营日内）合法
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"),
                                occ("G1", key("SEC"), iso(23), iso(0, 1)))))
                .andExpect(status().isCreated());
    }

    // ---------- 不存在 404 ----------

    @Test
    void missingPlanReturns404() throws Exception {
        String missing = key("SCH");
        mvc.perform(get("/api/v1/plans/{key}", missing)).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/plans/{key}/occupancies", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, occ("G1", key("SEC"), iso(8), iso(9)))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/plans/{key}/publish", missing)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/plans/{key}/cancel", missing)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound());
    }

    // ---------- 版本/状态/幂等键冲突 409 ----------

    @Test
    void duplicateScheduleKeyRejected() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey,
                                occ("G2", key("SEC"), iso(8), iso(9)))))
                .andExpect(status().isConflict());
    }

    @Test
    void updateRejectsVersionAndStateConflicts() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));

        // 版本不匹配
        mvc.perform(put("/api/v1/plans/{key}/occupancies", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 5,
                                occ("G1", key("SEC"), iso(8), iso(9)))))
                .andExpect(status().isConflict());

        // 已发布不可修改
        publishPlan(scheduleKey, key("REQ"));
        mvc.perform(put("/api/v1/plans/{key}/occupancies", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1,
                                occ("G1", key("SEC"), iso(8), iso(9)))))
                .andExpect(status().isConflict());

        // 已取消不可修改
        String cancelled = key("SCH");
        String section = key("SEC");
        createPlan(cancelled, occ("G1", section, iso(8), iso(9)));
        publishPlan(cancelled, key("REQ"));
        cancelPlan(cancelled, key("REQ"));
        mvc.perform(put("/api/v1/plans/{key}/occupancies", cancelled)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, occ("G1", section, iso(8), iso(9)))))
                .andExpect(status().isConflict());
    }

    @Test
    void publishAndCancelRejectStateConflicts() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));

        // 草稿不可取消
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());

        publishPlan(scheduleKey, key("REQ"));
        // 已发布不可重复发布（新幂等键）
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());

        cancelPlan(scheduleKey, key("REQ"));
        // 已取消不可发布、不可再取消
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());
    }

    // ---------- 时隙冲突 422 ----------

    @Test
    void publishRejectsTrainOverlapWithinPlan() throws Exception {
        String scheduleKey = key("SCH");
        String publishKey = key("REQ");
        createPlan(scheduleKey,
                occ("G1", key("SEC"), iso(8), iso(9)),
                occ("G1", key("SEC"), iso(8), iso(9)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        assertThat(error.get("details").get(0).get("type").asText()).isEqualTo("TRAIN_OVERLAP");

        // 计划保持草稿；失败不缓存，修正后同键重发成功
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("status").asText()).isEqualTo("DRAFT");

        mvc.perform(put("/api/v1/plans/{key}/occupancies", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1,
                                occ("G1", key("SEC"), iso(8), iso(9)))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isOk());
    }

    @Test
    void publishRejectsCrossPlanSectionConflictButAllowsAdjacent() throws Exception {
        String section = key("SEC");
        String first = key("SCH");
        createPlan(first, occ("G1", section, iso(8), iso(9)));
        publishPlan(first, key("REQ"));

        // 重叠 → 422，返回冲突区段与计划
        String overlapping = key("SCH");
        createPlan(overlapping, occ("G2", section, iso(8), iso(9)));
        MvcResult conflict = mvc.perform(post("/api/v1/plans/{key}/publish", overlapping)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SECTION_CONFLICT");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(first);

        // 冲突计划保持草稿
        MvcResult after = mvc.perform(get("/api/v1/plans/{key}", overlapping))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after).get("status").asText()).isEqualTo("DRAFT");

        // 相邻时隙（09:00 紧接）合法
        String adjacent = key("SCH");
        createPlan(adjacent, occ("G3", section, iso(9), iso(10)));
        publishPlan(adjacent, key("REQ"));

        // 不同区段互不影响
        String otherSection = key("SCH");
        createPlan(otherSection, occ("G4", key("SEC"), iso(8), iso(9)));
        publishPlan(otherSection, key("REQ"));
    }

    // ---------- 幂等 ----------

    @Test
    void createIdempotentReplayAndParamConflict() throws Exception {
        String scheduleKey = key("SCH");
        String requestKey = key("REQ");
        String body = createBody(requestKey, scheduleKey,
                occ("G1", key("SEC"), iso(8), iso(9)));

        MvcResult first = mvc.perform(post("/api/v1/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放返回首次结果
        MvcResult replay = mvc.perform(post("/api/v1/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(requestKey, key("SCH"),
                                occ("G1", key("SEC"), iso(8), iso(9)))))
                .andExpect(status().isConflict());
    }

    @Test
    void updateIdempotentReplayDoesNotIncrementTwice() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));
        String body = updateBody(key("REQ"), 1, occ("G2", key("SEC"), iso(10), iso(11)));

        MvcResult first = mvc.perform(put("/api/v1/plans/{key}/occupancies", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(put("/api/v1/plans/{key}/occupancies", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("version").asInt()).isEqualTo(2);
    }

    @Test
    void publishAndCancelIdempotentReplay() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));
        String publishKey = key("REQ");
        publishPlan(scheduleKey, publishKey);

        // 同键重放发布返回首次结果而非 409
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay).get("status").asText()).isEqualTo("PUBLISHED");

        String cancelKey = key("REQ");
        cancelPlan(scheduleKey, cancelKey);
        MvcResult cancelReplay = mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(cancelKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(cancelReplay).get("status").asText()).isEqualTo("CANCELLED");
    }

    // ---------- 辅助 ----------

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

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour) {
        return iso(hour, 0);
    }

    /** 运营日加 dayOffset 天后 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int dayOffset) {
        Instant instant = DAY.plusDays(dayOffset).atTime(hour, 0).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String[] manyOccs(int count) {
        String[] occs = new String[count];
        for (int i = 0; i < count; i++) {
            occs[i] = occ("G" + i, "SEC-M" + i, iso(8), iso(9));
        }
        return occs;
    }

    private static String createBody(String requestKey, String scheduleKey, String... occupancies) {
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
}
