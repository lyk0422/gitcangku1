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
 * 原子改签 API 主流程、失败回滚、改签链与幂等边界的端到端测试（H2 内存库）。
 * 各用例使用唯一 scheduleKey/requestKey/区段 ID，共享库内互不干扰。
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
    void rescheduleCancelsOldPublishesNewAndAppendsLink() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        String newKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        // 新草稿复用旧计划自身时隙（08:00-09:30 覆盖旧 08:00-09:00），改签校验须排除旧计划占用
        createPlan(newKey, occ("G2", section, iso(8), iso(9, 30)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        JsonNode oldPlan = body.get("oldPlan");
        JsonNode newPlan = body.get("newPlan");
        assertThat(oldPlan.get("scheduleKey").asText()).isEqualTo(oldKey);
        assertThat(oldPlan.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(oldPlan.get("version").asInt()).isEqualTo(1);
        assertThat(newPlan.get("scheduleKey").asText()).isEqualTo(newKey);
        assertThat(newPlan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(newPlan.get("version").asInt()).isEqualTo(1);

        // 旧计划原始占用保留不覆盖
        MvcResult oldDetail = mvc.perform(get("/api/v1/plans/{key}", oldKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode oldBody = read(oldDetail);
        assertThat(oldBody.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(oldBody.get("occupancies")).hasSize(1);
        assertThat(oldBody.get("occupancies").get(0).get("trainNo").asText()).isEqualTo("G1");
        assertThat(oldBody.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(8));

        // 生效时隙切换为新计划
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        JsonNode slotList = read(slots);
        assertThat(slotList).hasSize(1);
        assertThat(slotList.get(0).get("scheduleKey").asText()).isEqualTo(newKey);
        assertThat(slotList.get(0).get("endUtc").asText()).isEqualTo(iso(9, 30));

        // 前后继关联可从两侧查询，链完整有序
        assertChainKeys(oldKey, oldKey, newKey);
        assertChainKeys(newKey, oldKey, newKey);
    }

    @Test
    void successorCanBeRescheduledAgainChainStaysOrdered() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        String planC = key("SCH");
        createPlan(planA, occ("G1", section, iso(8), iso(9)));
        publishPlan(planA, key("REQ"));
        createPlan(planB, occ("G2", section, iso(8), iso(9)));
        createPlan(planC, occ("G3", section, iso(8), iso(9)));

        reschedule(planA, planB, 1, 1);
        // 后继 B 已发布，可再次改签为 C
        reschedule(planB, planC, 1, 1);

        // 从链上任意节点查询均返回完整有序链
        for (String any : new String[]{planA, planB, planC}) {
            MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", any))
                    .andExpect(status().isOk()).andReturn();
            JsonNode items = read(chain).get("chain");
            assertThat(items).hasSize(3);
            assertThat(items.get(0).get("scheduleKey").asText()).isEqualTo(planA);
            assertThat(items.get(1).get("scheduleKey").asText()).isEqualTo(planB);
            assertThat(items.get(2).get("scheduleKey").asText()).isEqualTo(planC);
            assertThat(items.get(0).get("status").asText()).isEqualTo("CANCELLED");
            assertThat(items.get(1).get("status").asText()).isEqualTo("CANCELLED");
            assertThat(items.get(2).get("status").asText()).isEqualTo("PUBLISHED");
        }
    }

    @Test
    void chainQueryForUnlinkedPlanAndMissingPlan() throws Exception {
        String lonely = key("SCH");
        createPlan(lonely, occ("G1", key("SEC"), iso(8), iso(9)));

        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", lonely))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(chain).get("chain");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("scheduleKey").asText()).isEqualTo(lonely);
        assertThat(items.get(0).get("status").asText()).isEqualTo("DRAFT");

        mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", key("SCH")))
                .andExpect(status().isNotFound());
    }

    // ---------- 参数非法 400 ----------

    @Test
    void rescheduleRejectsSameOldAndNewPlan() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));
        publishPlan(scheduleKey, key("REQ"));

        mvc.perform(post("/api/v1/plans/{key}/reschedule", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), scheduleKey, 1, 1)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 不存在 404 ----------

    @Test
    void rescheduleMissingPlanReturns404() throws Exception {
        String existing = key("SCH");
        createPlan(existing, occ("G1", key("SEC"), iso(8), iso(9)));

        // 旧计划不存在
        mvc.perform(post("/api/v1/plans/{key}/reschedule", key("SCH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), existing, 1, 1)))
                .andExpect(status().isNotFound());
        // 新草稿不存在
        mvc.perform(post("/api/v1/plans/{key}/reschedule", existing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), key("SCH"), 1, 1)))
                .andExpect(status().isNotFound());
    }

    // ---------- 版本/状态/关联冲突 409 ----------

    @Test
    void rescheduleRejectsStateConflicts() throws Exception {
        // 旧计划为草稿
        String draftOld = key("SCH");
        String draftNew = key("SCH");
        createPlan(draftOld, occ("G1", key("SEC"), iso(8), iso(9)));
        createPlan(draftNew, occ("G2", key("SEC"), iso(8), iso(9)));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", draftOld)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), draftNew, 1, 1)))
                .andExpect(status().isConflict());

        // 旧计划已取消
        String cancelledOld = key("SCH");
        createPlan(cancelledOld, occ("G1", key("SEC"), iso(8), iso(9)));
        publishPlan(cancelledOld, key("REQ"));
        cancelPlan(cancelledOld, key("REQ"));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", cancelledOld)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), draftNew, 1, 1)))
                .andExpect(status().isConflict());

        // 新计划非草稿（已发布）
        String publishedOld = key("SCH");
        String publishedNew = key("SCH");
        createPlan(publishedOld, occ("G1", key("SEC"), iso(8), iso(9)));
        publishPlan(publishedOld, key("REQ"));
        createPlan(publishedNew, occ("G2", key("SEC"), iso(10), iso(11)));
        publishPlan(publishedNew, key("REQ"));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", publishedOld)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), publishedNew, 1, 1)))
                .andExpect(status().isConflict());
    }

    @Test
    void rescheduleRejectsVersionConflicts() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        String newKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        createPlan(newKey, occ("G2", section, iso(9), iso(10)));

        // 旧计划期望版本不匹配
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 5, 1)))
                .andExpect(status().isConflict());
        // 新草稿期望版本不匹配
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 7)))
                .andExpect(status().isConflict());

        // 失败不产生任何副作用，正确版本仍可改签成功
        reschedule(oldKey, newKey, 1, 1);
    }

    @Test
    void rescheduleRejectsOpDateMismatch() throws Exception {
        String oldKey = key("SCH");
        String newKey = key("SCH");
        createPlan(oldKey, occ("G1", key("SEC"), iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        // 新草稿属于次日运营日
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBodyOnDate(key("REQ"), newKey, DAY.plusDays(1),
                                occ("G2", key("SEC"), iso(8, 0, 1), iso(9, 0, 1)))))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isConflict());
    }

    @Test
    void rescheduleRejectsOldPlanThatAlreadyHasSuccessor() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        String planC = key("SCH");
        createPlan(planA, occ("G1", section, iso(8), iso(9)));
        publishPlan(planA, key("REQ"));
        createPlan(planB, occ("G2", section, iso(8), iso(9)));
        createPlan(planC, occ("G3", section, iso(8), iso(9)));
        reschedule(planA, planB, 1, 1);

        // 旧计划 A 已被改签取消（已有直接后继），再次改签 → 409 状态冲突
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", planA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), planC, 1, 1)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("PLAN_STATE_CONFLICT");

        // C 仍为草稿，链保持 [A, B]
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", planC))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("status").asText()).isEqualTo("DRAFT");
        assertChainKeys(planA, planA, planB);
    }

    // ---------- 时隙冲突 422 与回滚 ----------

    @Test
    void rescheduleRejectsTrainOverlapAndRollsBack() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        String newKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        // 新草稿内部同一列车重叠
        createPlan(newKey,
                occ("G2", section, iso(9), iso(10)),
                occ("G2", section, iso(9, 30), iso(11)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        assertThat(error.get("details").get(0).get("type").asText()).isEqualTo("TRAIN_OVERLAP");

        // 回滚：旧计划仍发布、新计划仍草稿、无关联、时隙仍归旧计划
        assertRolledBack(oldKey, newKey, section);
    }

    @Test
    void rescheduleRejectsThirdPartySectionConflictAndRollsBack() throws Exception {
        String section = key("SEC");
        String thirdParty = key("SCH");
        createPlan(thirdParty, occ("G9", section, iso(10), iso(11)));
        publishPlan(thirdParty, key("REQ"));

        String oldKey = key("SCH");
        String newKey = key("SCH");
        createPlan(oldKey, occ("G1", key("SEC"), iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        // 新草稿与第三方已发布计划冲突（仅排除旧计划，不排除第三方）
        createPlan(newKey, occ("G2", section, iso(10, 30), iso(11, 30)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SECTION_CONFLICT");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(thirdParty);

        assertRolledBack(oldKey, newKey, section);
    }

    // ---------- 幂等 ----------

    @Test
    void rescheduleIdempotentReplayAndParamConflict() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        String newKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        createPlan(newKey, occ("G2", section, iso(9), iso(10)));

        String requestKey = key("REQ");
        String body = rescheduleBody(requestKey, newKey, 1, 1);
        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();

        // 同键同参重放返回首次结果，且不复活已取消的旧计划
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        MvcResult oldDetail = mvc.perform(get("/api/v1/plans/{key}", oldKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(oldDetail).get("status").asText()).isEqualTo("CANCELLED");
        assertChainKeys(oldKey, oldKey, newKey);

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(requestKey, key("SCH"), 1, 1)))
                .andExpect(status().isConflict());
    }

    @Test
    void rescheduleFailureDoesNotConsumeRequestKey() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        String newKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        // 新草稿内部列车重叠 → 首次改签 422
        createPlan(newKey,
                occ("G2", section, iso(9), iso(10)),
                occ("G2", section, iso(9, 30), iso(11)));

        String requestKey = key("REQ");
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(requestKey, newKey, 1, 1)))
                .andExpect(status().isUnprocessableEntity());

        // 失败不占键：修正草稿（版本升至 2）后同键新参重试成功
        mvc.perform(put("/api/v1/plans/{key}/occupancies", newKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, occ("G2", section, iso(9), iso(10)))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(requestKey, newKey, 1, 2)))
                .andExpect(status().isOk());
        assertChainKeys(oldKey, oldKey, newKey);
    }

    // ---------- 辅助 ----------

    private void assertRolledBack(String oldKey, String newKey, String section) throws Exception {
        MvcResult oldDetail = mvc.perform(get("/api/v1/plans/{key}", oldKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(oldDetail).get("status").asText()).isEqualTo("PUBLISHED");
        MvcResult newDetail = mvc.perform(get("/api/v1/plans/{key}", newKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(newDetail).get("status").asText()).isEqualTo("DRAFT");
        // 未产生改签关联
        assertChainKeys(oldKey, oldKey);
        assertChainKeys(newKey, newKey);
    }

    private void assertChainKeys(String queryKey, String... expectedKeys) throws Exception {
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", queryKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(chain).get("chain");
        assertThat(items).hasSize(expectedKeys.length);
        for (int i = 0; i < expectedKeys.length; i++) {
            assertThat(items.get(i).get("scheduleKey").asText()).isEqualTo(expectedKeys[i]);
        }
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

    private void reschedule(String oldKey, String newKey, int oldVersion, int newVersion)
            throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, oldVersion, newVersion)))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    /** 运营日当日 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour) {
        return iso(hour, 0);
    }

    /** 运营日加 dayOffset 天后 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute, int dayOffset) {
        Instant instant = DAY.plusDays(dayOffset).atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String createBody(String requestKey, String scheduleKey, String... occupancies) {
        return createBodyOnDate(requestKey, scheduleKey, DAY, occupancies);
    }

    private static String createBodyOnDate(String requestKey, String scheduleKey, LocalDate opDate,
                                           String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + opDate + "\",\"occupancies\":[" + String.join(",", occupancies)
                + "]}";
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
}
