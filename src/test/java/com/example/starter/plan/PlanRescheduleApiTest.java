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
 * 原子改签 API 端到端测试（H2 内存库）：主流程、链式改签、失败回滚、
 * 排除旧计划但不排除第三方、400/404/409/422 分支与幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanRescheduleApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程 ----------

    @Test
    void rescheduleAtomicallyCancelsOldAndPublishesNew() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        createPlan(newKey, occ("G2", section, iso(10), iso(11)));

        MvcResult result = mvc.perform(post("/api/v1/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, newKey, 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("scheduleKey").asText()).isEqualTo(newKey);
        assertThat(body.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(body.get("version").asInt()).isEqualTo(1);

        // 旧计划取消，原始占用保留不被覆盖
        JsonNode oldDetail = planDetail(oldKey);
        assertThat(oldDetail.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(oldDetail.get("occupancies")).hasSize(1);
        assertThat(oldDetail.get("occupancies").get(0).get("trainNo").asText()).isEqualTo("G1");
        assertThat(oldDetail.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(8));

        // 新计划发布，占用生效
        JsonNode slots = publishedSlots(section);
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).get("scheduleKey").asText()).isEqualTo(newKey);
        assertThat(slots.get(0).get("trainNo").asText()).isEqualTo("G2");

        // 改签链：[旧, 新]，有序
        JsonNode chain = chain(oldKey);
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).get("scheduleKey").asText()).isEqualTo(oldKey);
        assertThat(chain.get(1).get("scheduleKey").asText()).isEqualTo(newKey);
        JsonNode chainFromNew = chain(newKey);
        assertThat(chainFromNew).hasSize(2);
        assertThat(chainFromNew.get(0).get("scheduleKey").asText()).isEqualTo(oldKey);
    }

    @Test
    void rescheduleAllowsNewDraftOverlappingOldPlanOnly() throws Exception {
        // 新草稿与旧计划占用完全重叠：旧占用在此次校验中被排除，合法
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        createPlan(oldKey, occ("G1", section, iso(8), iso(10)));
        publishPlan(oldKey, key("REQ"));
        createPlan(newKey, occ("G2", section, iso(8), iso(9)), occ("G2", section, iso(9), iso(10)));

        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, newKey, 1)))
                .andExpect(status().isOk());
        assertThat(publishedSlots(section)).hasSize(2);
    }

    @Test
    void successorCanBeRescheduledAgainAndChainIsComplete() throws Exception {
        String section = key("SEC");
        String first = key("P1");
        String second = key("P2");
        String third = key("P3");
        createPlan(first, occ("G1", section, iso(8), iso(9)));
        publishPlan(first, key("REQ"));
        createPlan(second, occ("G2", section, iso(9), iso(10)));
        reschedule(first, 1, second, 1, key("REQ"));
        createPlan(third, occ("G3", section, iso(10), iso(11)));
        reschedule(second, 1, third, 1, key("REQ"));

        JsonNode chain = chain(first);
        assertThat(chain).hasSize(3);
        assertThat(chain.get(0).get("scheduleKey").asText()).isEqualTo(first);
        assertThat(chain.get(0).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(chain.get(1).get("scheduleKey").asText()).isEqualTo(second);
        assertThat(chain.get(1).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(chain.get(2).get("scheduleKey").asText()).isEqualTo(third);
        assertThat(chain.get(2).get("status").asText()).isEqualTo("PUBLISHED");

        // 从链上任意节点查询都得到同一完整链
        assertThat(chain(third)).hasSize(3);
        assertThat(chain(second)).hasSize(3);
    }

    // ---------- 失败回滚 422 ----------

    @Test
    void conflictWithThirdPartyRollsBackEverything() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String thirdParty = key("TP");
        String newKey = key("NEW");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        // 第三方已发布计划占用 10:00-11:00（不是旧计划，不能排除）
        createPlan(thirdParty, occ("G9", section, iso(10), iso(11)));
        publishPlan(thirdParty, key("REQ"));
        createPlan(newKey, occ("G2", section, iso(10), iso(11)));

        String requestKey = key("REQ");
        MvcResult result = mvc.perform(post("/api/v1/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(requestKey, oldKey, 1, newKey, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SECTION_CONFLICT");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(thirdParty);

        // 回滚：旧仍发布、原始占用不变；新仍草稿；时隙仍为旧+第三方
        assertThat(planDetail(oldKey).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(planDetail(oldKey).get("occupancies")).hasSize(1);
        assertThat(planDetail(newKey).get("status").asText()).isEqualTo("DRAFT");
        assertThat(publishedSlots(section)).hasSize(2);
        assertThat(chain(oldKey)).hasSize(1);

        // 失败不占键：调整新草稿避开第三方后，同一 requestKey 重试成功
        mvc.perform(put("/api/v1/plans/{key}/occupancies", newKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, occ("G2", section, iso(12), iso(13)))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(requestKey, oldKey, 1, newKey, 2)))
                .andExpect(status().isOk());
    }

    @Test
    void trainOverlapInsideNewDraftRejectedAndRolledBack() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        createPlan(newKey, occ("G2", section, iso(10), iso(12)),
                occ("G2", section, iso(11), iso(13)));

        MvcResult result = mvc.perform(post("/api/v1/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, newKey, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("details").get(0).get("type").asText())
                .isEqualTo("TRAIN_OVERLAP");
        assertThat(planDetail(oldKey).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(planDetail(newKey).get("status").asText()).isEqualTo("DRAFT");
    }

    // ---------- 400 / 404 / 409 ----------

    @Test
    void samePlanOrInvalidBodyReturns400() throws Exception {
        String oldKey = key("OLD");
        createPlan(oldKey, occ("G1", key("SEC"), iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));

        // 新旧为同一计划
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, oldKey, 1)))
                .andExpect(status().isBadRequest());
        // 缺字段
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldScheduleKey\":\"" + oldKey + "\",\"expectedOldVersion\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingPlanReturns404() throws Exception {
        String existing = key("OLD");
        createPlan(existing, occ("G1", key("SEC"), iso(8), iso(9)));
        publishPlan(existing, key("REQ"));
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), key("MISS"), 1, key("MISS2"), 1)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), existing, 1, key("MISS"), 1)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", key("MISS")))
                .andExpect(status().isNotFound());
    }

    @Test
    void stateVersionAndRelationConflictsReturn409() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        // 旧计划仍是草稿 → 状态冲突
        createPlan(newKey, occ("G2", section, iso(10), iso(11)));
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, newKey, 1)))
                .andExpect(status().isConflict());

        publishPlan(oldKey, key("REQ"));
        // 旧版本不匹配
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 99, newKey, 1)))
                .andExpect(status().isConflict());
        // 新版本不匹配
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, newKey, 99)))
                .andExpect(status().isConflict());

        // 新计划已发布（非草稿）→ 状态冲突
        String publishedNew = key("PUB");
        createPlan(publishedNew, occ("G3", key("SEC"), iso(8), iso(9)));
        publishPlan(publishedNew, key("REQ"));
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, publishedNew, 1)))
                .andExpect(status().isConflict());

        // 不同运营日
        String otherDay = key("OTHER");
        String otherStart = DAY.plusDays(1).atTime(8, 0).atZone(SH).toInstant().toString();
        String otherEnd = DAY.plusDays(1).atTime(9, 0).atZone(SH).toInstant().toString();
        String otherBody = "{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\"" + otherDay
                + "\",\"opDate\":\"" + DAY.plusDays(1) + "\",\"occupancies\":["
                + occ("G4", key("SEC"), otherStart, otherEnd) + "]}";
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON).content(otherBody))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, otherDay, 1)))
                .andExpect(status().isConflict());

        // 先完成一次改签：旧取消、新发布并建立关联
        reschedule(oldKey, 1, newKey, 1, key("REQ"));

        // 旧计划已被取消：再次改签先得到状态冲突 409（旧计划原始占用与关联未变）
        String another = key("NEW2");
        createPlan(another, occ("G5", section, iso(8), iso(9)));
        MvcResult relation = mvc.perform(post("/api/v1/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), oldKey, 1, another, 1)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(relation).get("code").asText()).isEqualTo("PLAN_STATE_CONFLICT");
        // 关联未新增：another 仍是单元素链
        assertThat(chain(another)).hasSize(1);
        // 原改签链仍为两环
        assertThat(chain(oldKey)).hasSize(2);
    }

    // ---------- 幂等 ----------

    @Test
    void rescheduleIdempotentReplayAndParamConflict() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        createPlan(newKey, occ("G2", section, iso(10), iso(11)));
        String requestKey = key("REQ");
        String body = rescheduleBody(requestKey, oldKey, 1, newKey, 1);

        MvcResult first = mvc.perform(post("/api/v1/reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(requestKey, oldKey, 1, key("OTHER"), 1)))
                .andExpect(status().isConflict());
    }

    @Test
    void replayDoesNotResurrectCancelledNewPlan() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        createPlan(newKey, occ("G2", section, iso(10), iso(11)));
        String requestKey = key("REQ");
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(requestKey, oldKey, 1, newKey, 1)))
                .andExpect(status().isOk());

        // 改签后的新计划再被取消
        cancelPlan(newKey, key("REQ"));
        assertThat(planDetail(newKey).get("status").asText()).isEqualTo("CANCELLED");

        // 重放改签请求只返回首次快照，不改变现状（不复活）
        MvcResult replay = mvc.perform(post("/api/v1/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(requestKey, oldKey, 1, newKey, 1)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(planDetail(newKey).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(publishedSlots(section)).isEmpty();
    }

    // ---------- 辅助 ----------

    private void reschedule(String oldKey, int oldVersion, String newKey, int newVersion,
                            String requestKey) throws Exception {
        mvc.perform(post("/api/v1/reschedule").contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(requestKey, oldKey, oldVersion, newKey, newVersion)))
                .andExpect(status().isOk());
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, occupancies)))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + requestKey + "\"}"))
                .andExpect(status().isOk());
    }

    private void cancelPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + requestKey + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode planDetail(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private JsonNode publishedSlots(String section) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private JsonNode chain(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("plans");
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour) {
        Instant instant = DAY.atTime(hour, 0).atZone(SH).toInstant();
        return instant.toString();
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

    private static String updateBody(String requestKey, int expectedVersion, String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"occupancies\":[" + String.join(",", occupancies) + "]}";
    }

    private static String rescheduleBody(String requestKey, String oldKey, int oldVersion,
                                         String newKey, int newVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"oldScheduleKey\":\"" + oldKey
                + "\",\"expectedOldVersion\":" + oldVersion
                + ",\"newScheduleKey\":\"" + newKey
                + "\",\"expectedNewVersion\":" + newVersion + "}";
    }
}
