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
 * 施工占用窗口 API 主流程、失败分支与幂等边界的端到端测试（H2 内存库）。
 * 覆盖：施工单创建/重叠 409、计划发布与改签联合校验 422、版本修改重校验、
 * 取消释放与不可变取消记录、查询接口与幂等重放。
 * 各用例使用唯一 workKey/requestKey/区段 ID，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkOrderApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    private static final LocalDate FUTURE_DAY = LocalDate.of(2099, 1, 1);
    private static final LocalDate PAST_DAY = LocalDate.of(2020, 1, 1);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程 ----------

    @Test
    void createWorkOrderThenQueryDetailAndWindows() throws Exception {
        String sectionA = registerSection();
        String sectionB = registerSection();
        String workKey = key("WK");

        // 区段集合乱序提交，响应按字典序返回
        MvcResult created = mvc.perform(post("/api/v1/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkBody(key("REQ"), "dispatcher-1", workKey,
                                iso(8), iso(10), sectionB, sectionA)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = read(created);
        assertThat(body.get("workKey").asText()).isEqualTo(workKey);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("operator").asText()).isEqualTo("dispatcher-1");
        assertThat(body.get("startUtc").asText()).isEqualTo(iso(8));
        assertThat(body.get("endUtc").asText()).isEqualTo(iso(10));
        assertThat(body.get("sectionIds")).hasSize(2);
        assertThat(body.get("sectionIds").get(0).asText())
                .isLessThanOrEqualTo(body.get("sectionIds").get(1).asText());

        MvcResult detail = mvc.perform(get("/api/v1/work-orders/{key}", workKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);

        MvcResult windows = mvc.perform(get("/api/v1/work-windows")
                        .param("sectionId", sectionA))
                .andExpect(status().isOk()).andReturn();
        JsonNode windowList = read(windows);
        assertThat(windowList).hasSize(1);
        assertThat(windowList.get(0).get("workKey").asText()).isEqualTo(workKey);
        assertThat(windowList.get(0).get("startUtc").asText()).isEqualTo(iso(8));
    }

    // ---------- 参数非法 400 ----------

    @Test
    void createRejectsUnknownSectionAndInvalidWindow() throws Exception {
        // 区段未注册
        MvcResult unknown = mvc.perform(post("/api/v1/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkBody(key("REQ"), "op", key("WK"),
                                iso(8), iso(9), key("SEC"))))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(read(unknown).get("code").asText()).isEqualTo("SECTION_NOT_FOUND");

        String section = registerSection();
        // 结束等于开始
        mvc.perform(post("/api/v1/work-orders").contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkBody(key("REQ"), "op", key("WK"),
                                iso(8), iso(8), section)))
                .andExpect(status().isBadRequest());
        // 结束早于开始
        mvc.perform(post("/api/v1/work-orders").contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkBody(key("REQ"), "op", key("WK"),
                                iso(9), iso(8), section)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 施工单重叠 409 ----------

    @Test
    void overlappingWorkOrdersRejected409WithStableWorkKeys() throws Exception {
        String section = registerSection();
        String first = key("WK");
        createWorkOrder(first, iso(8), iso(10), section);

        // 时间窗相交 → 409，稳定列出冲突 workKey
        MvcResult conflict = mvc.perform(post("/api/v1/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkBody(key("REQ"), "op", key("WK"),
                                iso(9), iso(11), section)))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("WORK_WINDOW_OVERLAP");
        JsonNode keys = error.get("details").get(0).get("conflictingWorkKeys");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).asText()).isEqualTo(first);

        // 相邻窗口（10:00 紧接，左闭右开）合法
        createWorkOrder(key("WK"), iso(10), iso(11), section);
        // 不同区段互不影响
        createWorkOrder(key("WK"), iso(8), iso(10), registerSection());
    }

    // ---------- 计划发布/改签联合校验 422 ----------

    @Test
    void publishPlanIntersectingWorkWindowRejected422() throws Exception {
        String section = registerSection();
        String workKey = key("WK");
        createWorkOrder(workKey, iso(8), iso(10), section);

        String scheduleKey = key("SCH");
        String publishKey = key("REQ");
        createPlan(scheduleKey, DAY, occ("G1", section, iso(9), iso(11)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("WORK_WINDOW_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("WORK_WINDOW_CONFLICT");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("workKey").asText()).isEqualTo(workKey);
        assertThat(detail.get("windowStartUtc").asText()).isEqualTo(iso(8));
        assertThat(detail.get("windowEndUtc").asText()).isEqualTo(iso(10));

        // 计划保持草稿；失败不占键，修正占用后同键重发成功
        MvcResult after = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after).get("status").asText()).isEqualTo("DRAFT");

        mvc.perform(put("/api/v1/plans/{key}/occupancies", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1,
                                occ("G1", section, iso(10), iso(11)))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isOk());
    }

    @Test
    void rescheduleIntoWorkWindowRejected422AndRolledBack() throws Exception {
        String sectionA = key("SEC");
        String sectionB = registerSection();
        String workKey = key("WK");
        createWorkOrder(workKey, iso(10), iso(11), sectionB);

        String oldPlan = key("SCH");
        createPlan(oldPlan, DAY, occ("G1", sectionA, iso(8), iso(9)));
        publishPlan(oldPlan, key("REQ"));

        String newPlan = key("SCH");
        createPlan(newPlan, DAY, occ("G2", sectionB, iso(10), iso(11)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newPlan, 1, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("WORK_WINDOW_CONFLICT");

        // 整单回滚：旧计划仍发布、新计划仍草稿、无改签关联
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", oldPlan))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("PUBLISHED");
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", newPlan))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("DRAFT");
        JsonNode chain = read(mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", oldPlan))
                .andExpect(status().isOk()).andReturn());
        assertThat(chain.get("chain")).hasSize(1);
    }

    // ---------- 施工单版本修改重校验 ----------

    @Test
    void updateWorkOrderRevalidatesPublishedPlans() throws Exception {
        String section = registerSection();
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, DAY, occ("G1", section, iso(8), iso(9)));
        publishPlan(scheduleKey, key("REQ"));

        String workKey = key("WK");
        createWorkOrder(workKey, iso(20), iso(21), section);

        // 修改后与已发布计划相交 → 422，施工单不部分生效
        MvcResult conflict = mvc.perform(put("/api/v1/work-orders/{key}", workKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateWorkBody(key("REQ"), "op", 1,
                                iso(8), iso(9), section)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("WORK_PLAN_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);

        JsonNode unchanged = read(mvc.perform(get("/api/v1/work-orders/{key}", workKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(unchanged.get("version").asInt()).isEqualTo(1);
        assertThat(unchanged.get("startUtc").asText()).isEqualTo(iso(20));

        // 修改到不相交窗口 → 成功，版本加一
        MvcResult updated = mvc.perform(put("/api/v1/work-orders/{key}", workKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateWorkBody(key("REQ"), "op-2", 1,
                                iso(21), iso(22), section)))
                .andExpect(status().isOk()).andReturn();
        JsonNode updatedBody = read(updated);
        assertThat(updatedBody.get("version").asInt()).isEqualTo(2);
        assertThat(updatedBody.get("operator").asText()).isEqualTo("op-2");
        assertThat(updatedBody.get("startUtc").asText()).isEqualTo(iso(21));
    }

    @Test
    void updateWorkOrderRejectsOverlapAndVersionConflict() throws Exception {
        String section = registerSection();
        String first = key("WK");
        createWorkOrder(first, iso(8), iso(10), section);
        String second = key("WK");
        createWorkOrder(second, iso(20), iso(22), section);

        // 修改后与其他生效施工单重叠 → 409 并列出冲突 workKey
        MvcResult overlap = mvc.perform(put("/api/v1/work-orders/{key}", second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateWorkBody(key("REQ"), "op", 1,
                                iso(9), iso(11), section)))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(overlap);
        assertThat(error.get("code").asText()).isEqualTo("WORK_WINDOW_OVERLAP");
        assertThat(error.get("details").get(0).get("conflictingWorkKeys").get(0).asText())
                .isEqualTo(first);

        // 版本不匹配 → 409
        mvc.perform(put("/api/v1/work-orders/{key}", second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateWorkBody(key("REQ"), "op", 5,
                                iso(20), iso(21), section)))
                .andExpect(status().isConflict());
    }

    // ---------- 取消释放与取消记录 ----------

    @Test
    void cancelNotStartedReleasesOccupancyAndKeepsImmutableRecord() throws Exception {
        String section = registerSection();
        String workKey = key("WK");
        createWorkOrder(workKey, futureIso(8), futureIso(10), section);

        // 生效窗口阻塞计划发布
        String blocked = key("SCH");
        createPlan(blocked, FUTURE_DAY, occ("G1", section, futureIso(8), futureIso(9)));
        mvc.perform(post("/api/v1/plans/{key}/publish", blocked)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity());

        String cancelKey = key("REQ");
        MvcResult cancelled = mvc.perform(post("/api/v1/work-orders/{key}/cancel", workKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workActionBody(cancelKey, "op-cancel")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(cancelled).get("status").asText()).isEqualTo("CANCELLED");

        // 同键重放返回首次取消响应
        MvcResult replay = mvc.perform(post("/api/v1/work-orders/{key}/cancel", workKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workActionBody(cancelKey, "op-cancel")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(cancelled));

        // 占用立即释放：窗口查询为空，被阻计划可发布
        MvcResult windows = mvc.perform(get("/api/v1/work-windows")
                        .param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(windows)).isEmpty();
        publishPlan(blocked, key("REQ"));

        // 不可变取消记录可查询
        MvcResult record = mvc.perform(get("/api/v1/work-orders/{key}/cancellation", workKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode recordBody = read(record);
        assertThat(recordBody.get("workKey").asText()).isEqualTo(workKey);
        assertThat(recordBody.get("version").asInt()).isEqualTo(1);
        assertThat(recordBody.get("operator").asText()).isEqualTo("op-cancel");
        assertThat(recordBody.get("cancelledAt").asText()).isNotBlank();

        MvcResult all = mvc.perform(get("/api/v1/work-cancellations"))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(all).toString()).contains(workKey);

        // 已取消不可再取消（新幂等键）、不可修改
        mvc.perform(post("/api/v1/work-orders/{key}/cancel", workKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workActionBody(key("REQ"), "op-cancel")))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/work-orders/{key}", workKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateWorkBody(key("REQ"), "op", 1,
                                futureIso(8), futureIso(9), section)))
                .andExpect(status().isConflict());
    }

    @Test
    void cancelStartedWorkOrderRejected() throws Exception {
        String section = registerSection();
        String workKey = key("WK");
        // 窗口起点早于当前时刻（2020 年），视为已开始
        createWorkOrder(workKey, pastIso(8), pastIso(10), section);

        MvcResult result = mvc.perform(post("/api/v1/work-orders/{key}/cancel", workKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workActionBody(key("REQ"), "op")))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("WORK_ALREADY_STARTED");

        // 未产生取消记录
        mvc.perform(get("/api/v1/work-orders/{key}/cancellation", workKey))
                .andExpect(status().isNotFound());
    }

    // ---------- 受影响计划查询 ----------

    @Test
    void affectedPlansQueryReturnsPublishedPlansIntersectingWindow() throws Exception {
        String section = registerSection();
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, DAY, occ("G1", section, iso(8), iso(9)));
        publishPlan(scheduleKey, key("REQ"));

        String workKey = key("WK");
        createWorkOrder(workKey, iso(8), iso(10), section);

        MvcResult affected = mvc.perform(get("/api/v1/work-orders/{key}/affected-plans", workKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = read(affected);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(list.get(0).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(list.get(0).get("conflicts")).hasSize(1);
        assertThat(list.get(0).get("conflicts").get(0).get("sectionId").asText())
                .isEqualTo(section);

        // 施工单不存在 → 404
        mvc.perform(get("/api/v1/work-orders/{key}/affected-plans", key("WK")))
                .andExpect(status().isNotFound());
    }

    // ---------- 不存在 404 ----------

    @Test
    void missingWorkOrderReturns404() throws Exception {
        String missing = key("WK");
        mvc.perform(get("/api/v1/work-orders/{key}", missing)).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/work-orders/{key}", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateWorkBody(key("REQ"), "op", 1, iso(8), iso(9),
                                registerSection())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/work-orders/{key}/cancel", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workActionBody(key("REQ"), "op")))
                .andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void createIdempotentReplaySectionOrderInsensitiveAndParamConflict() throws Exception {
        String sectionA = registerSection();
        String sectionB = registerSection();
        String workKey = key("WK");
        String requestKey = key("REQ");

        MvcResult first = mvc.perform(post("/api/v1/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkBody(requestKey, "op", workKey,
                                iso(8), iso(9), sectionB, sectionA)))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参（区段换序视为同参）重放返回首次完整响应
        MvcResult replay = mvc.perform(post("/api/v1/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkBody(requestKey, "op", workKey,
                                iso(8), iso(9), sectionA, sectionB)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键异参（操作者不同）→ 409
        mvc.perform(post("/api/v1/work-orders").contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkBody(requestKey, "other-op", workKey,
                                iso(8), iso(9), sectionA, sectionB)))
                .andExpect(status().isConflict());
        // 同键异参（时段不同）→ 409
        mvc.perform(post("/api/v1/work-orders").contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkBody(requestKey, "op", workKey,
                                iso(8), iso(10), sectionA, sectionB)))
                .andExpect(status().isConflict());
    }

    @Test
    void failedCreateDoesNotOccupyIdempotencyKey() throws Exception {
        String section = registerSection();
        createWorkOrder(key("WK"), iso(8), iso(10), section);

        String requestKey = key("REQ");
        String workKey = key("WK");
        // 首次因重叠 409 失败，不占键
        mvc.perform(post("/api/v1/work-orders").contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkBody(requestKey, "op", workKey,
                                iso(9), iso(11), section)))
                .andExpect(status().isConflict());
        // 修正参数后同键创建成功
        mvc.perform(post("/api/v1/work-orders").contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkBody(requestKey, "op", workKey,
                                iso(10), iso(11), section)))
                .andExpect(status().isCreated());
    }

    // ---------- 辅助 ----------

    private String registerSection() throws Exception {
        String sectionId = key("SEC");
        mvc.perform(post("/api/v1/sections").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sectionId\":\"" + sectionId + "\"}"))
                .andExpect(status().isCreated());
        return sectionId;
    }

    private void createWorkOrder(String workKey, String startIso, String endIso,
                                 String... sectionIds) throws Exception {
        mvc.perform(post("/api/v1/work-orders").contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkBody(key("REQ"), "op", workKey,
                                startIso, endIso, sectionIds)))
                .andExpect(status().isCreated());
    }

    private void createPlan(String scheduleKey, LocalDate opDate, String... occupancies)
            throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + opDate
                                + "\",\"occupancies\":[" + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
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
        return DAY.atTime(hour, 0).atZone(SH).toInstant().toString();
    }

    /** 未来日（2099-01-01）hour 点（Asia/Shanghai）的 UTC 时刻，用于未开始施工单。 */
    private static String futureIso(int hour) {
        return FUTURE_DAY.atTime(hour, 0).atZone(SH).toInstant().toString();
    }

    /** 过去日（2020-01-01）hour 点（Asia/Shanghai）的 UTC 时刻，用于已开始施工单。 */
    private static String pastIso(int hour) {
        return PAST_DAY.atTime(hour, 0).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String sectionsJson(String... sectionIds) {
        StringBuilder sb = new StringBuilder();
        for (String sectionId : sectionIds) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append('\"').append(sectionId).append('\"');
        }
        return sb.toString();
    }

    private static String createWorkBody(String requestKey, String operator, String workKey,
                                         String startIso, String endIso, String... sectionIds) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"workKey\":\"" + workKey + "\",\"startUtc\":\"" + startIso
                + "\",\"endUtc\":\"" + endIso + "\",\"sectionIds\":[" + sectionsJson(sectionIds)
                + "]}";
    }

    private static String updateWorkBody(String requestKey, String operator, int expectedVersion,
                                         String startIso, String endIso, String... sectionIds) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"expectedVersion\":" + expectedVersion + ",\"startUtc\":\"" + startIso
                + "\",\"endUtc\":\"" + endIso + "\",\"sectionIds\":[" + sectionsJson(sectionIds)
                + "]}";
    }

    private static String workActionBody(String requestKey, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator + "\"}";
    }

    private static String updateBody(String requestKey, int expectedVersion,
                                     String... occupancies) {
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
