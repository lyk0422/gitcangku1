package com.example.starter.workblock;

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
 * 施工占用窗口 API 主流程、失败分支、联合校验与幂等边界的端到端测试（H2 内存库）。
 * 各用例使用唯一 workKey/requestKey/区段 ID，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkBlockApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    /** 施工窗口统一放在未来日期，保证“未开始”取消判定不受真实当前时间影响。 */
    private static final LocalDate DAY = LocalDate.of(2030, 6, 1);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 创建与查询 ----------

    @Test
    void createWorkBlockNormalizesSectionsAndGet() throws Exception {
        // 固定可排序前缀，保证规范化后 A 在 B 前
        String sectionA = registerSection("AAA");
        String sectionB = registerSection("BBB");
        String workKey = key("WB");
        // 区段集合故意换序，响应应规范化为字典序
        String body = createBody(key("REQ"), workKey, iso(8), iso(10), sectionB, sectionA);

        MvcResult created = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode view = read(created);
        assertThat(view.get("workKey").asText()).isEqualTo(workKey);
        assertThat(view.get("version").asInt()).isEqualTo(1);
        assertThat(view.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(view.get("sectionIds")).hasSize(2);
        assertThat(view.get("sectionIds").get(0).asText()).isEqualTo(sectionA);
        assertThat(view.get("sectionIds").get(1).asText()).isEqualTo(sectionB);
        assertThat(view.get("cancelledAt").isNull()).isTrue();

        MvcResult detail = mvc.perform(get("/api/v1/work-blocks/{key}", workKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(view);
    }

    @Test
    void createRejectsBadParamsAndUnknownSection() throws Exception {
        String section = registerSection();
        // 起点不早于终点
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("WB"), iso(9), iso(9), section)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("WB"), iso(10), iso(9), section)))
                .andExpect(status().isBadRequest());
        // 未登记区段
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("WB"), iso(8), iso(10),
                                "SEC-MISSING-" + UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        // 缺字段
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workKey\":\"" + key("WB") + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateWorkKeyReturns409() throws Exception {
        String section = registerSection();
        String workKey = key("WB");
        createWorkBlock(workKey, section, 8, 10);
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), workKey, iso(8), iso(10), section)))
                .andExpect(status().isConflict());
    }

    // ---------- 窗口重叠 409 ----------

    @Test
    void overlappingWindowsReturn409StableWorkKeysAdjacentAllowed() throws Exception {
        String sameSection = registerSection();
        String otherSection = registerSection();
        String first = key("WB");
        createWorkBlock(first, sameSection, 10, 12);

        // 同区段时段重叠 → 409，稳定列出冲突 workKey
        String second = key("WB");
        MvcResult conflict = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), second, iso(11), iso(13), sameSection)))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("WORK_BLOCK_OVERLAP");
        assertThat(error.get("message").asText()).contains(first);
        assertThat(error.get("details")).hasSize(1);
        assertThat(error.get("details").get(0).get("type").asText())
                .isEqualTo("WORK_BLOCK_OVERLAP");
        assertThat(error.get("details").get(0).get("workKey").asText()).isEqualTo(first);
        assertThat(error.get("details").get(0).get("sectionId").asText()).isEqualTo(sameSection);
        // 冲突方未落库
        mvc.perform(get("/api/v1/work-blocks/{key}", second))
                .andExpect(status().isNotFound());

        // 左闭右开相邻 [12,13) 合法
        String adjacent = key("WB");
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), adjacent, iso(12), iso(13), sameSection)))
                .andExpect(status().isCreated());

        // 不同区段时段重叠合法
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("WB"), iso(11), iso(13), otherSection)))
                .andExpect(status().isCreated());
    }

    // ---------- 计划发布/改签联合校验 422 ----------

    @Test
    void publishConflictingWithWindow422RollsBackAndCancelReleases() throws Exception {
        String section = registerSection();
        String workKey = key("WB");
        createWorkBlock(workKey, section, 8, 10);

        String plan = createPlan(key("SCH"), section, 8, 9);
        MvcResult blocked = mvc.perform(post("/api/v1/plans/{key}/publish", plan)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(blocked);
        assertThat(error.get("code").asText()).isEqualTo("WORK_BLOCK_CONFLICT");
        JsonNode first = error.get("details").get(0);
        assertThat(first.get("sectionId").asText()).isEqualTo(section);
        assertThat(first.get("workKey").asText()).isEqualTo(workKey);
        assertThat(first.get("scheduleKey").asText()).isEqualTo(plan);
        // 整单回滚：计划仍为草稿，既有时隙不变
        JsonNode detail = read(mvc.perform(get("/api/v1/plans/{key}", plan))
                .andExpect(status().isOk()).andReturn());
        assertThat(detail.get("status").asText()).isEqualTo("DRAFT");
        assertThat(detail.get("occupancies")).hasSize(1);

        // 相邻 [10,11) 与窗口不相交，可发布
        String adjacentPlan = createPlan(key("SCH"), section, 10, 11);
        publishPlan(adjacentPlan);

        // 取消施工单立即释放，原计划可发布
        cancelWorkBlock(workKey);
        publishPlan(plan);
    }

    @Test
    void rescheduleConflictingWithWindow422WholeTxRolledBack() throws Exception {
        String section = registerSection();
        createWorkBlock(key("WB"), section, 10, 12);

        String oldKey = createPlan(key("SCH"), section, 8, 9);
        publishPlan(oldKey);
        String newKey = createPlan(key("SCH"), section, 11, 12);

        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isUnprocessableEntity());

        // 整单回滚：旧仍发布、新仍草稿、无改签关联
        JsonNode oldDetail = read(mvc.perform(get("/api/v1/plans/{key}", oldKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(oldDetail.get("status").asText()).isEqualTo("PUBLISHED");
        JsonNode newDetail = read(mvc.perform(get("/api/v1/plans/{key}", newKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(newDetail.get("status").asText()).isEqualTo("DRAFT");
        JsonNode chain = read(mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", oldKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(chain.get("chain")).hasSize(1);
    }

    // ---------- 版本修改重校验 ----------

    @Test
    void updateVersionRechecksPublishedPlansAndIsAtomic() throws Exception {
        String section = registerSection();
        String plan = createPlan(key("SCH"), section, 8, 9);
        publishPlan(plan);

        String workKey = key("WB");
        // 创建时 [10,12) 与已发布计划不相交
        createWorkBlock(workKey, section, 10, 12);

        // 修改为 [8,9) 与已发布计划相交 → 422，不部分生效
        MvcResult rejected = mvc.perform(put("/api/v1/work-blocks/{key}", workKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, iso(8), iso(9), section)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("PUBLISHED_PLAN_CONFLICT");
        assertThat(error.get("details").get(0).get("sectionId").asText()).isEqualTo(section);
        assertThat(error.get("details").get(0).get("conflictingScheduleKey").asText())
                .isEqualTo(plan);

        // 窗口版本与时段保持原样
        JsonNode unchanged = read(mvc.perform(get("/api/v1/work-blocks/{key}", workKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(unchanged.get("version").asInt()).isEqualTo(1);
        assertThat(unchanged.get("startUtc").asText()).isEqualTo(iso(10));

        // 修改为不相交时段 → 成功，版本加一
        mvc.perform(put("/api/v1/work-blocks/{key}", workKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, iso(12), iso(13), section)))
                .andExpect(status().isOk());
        JsonNode updated = read(mvc.perform(get("/api/v1/work-blocks/{key}", workKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(updated.get("version").asInt()).isEqualTo(2);
        assertThat(updated.get("startUtc").asText()).isEqualTo(iso(12));

        // expectedVersion 不匹配 → 409
        mvc.perform(put("/api/v1/work-blocks/{key}", workKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, iso(13), iso(14), section)))
                .andExpect(status().isConflict());

        // 版本修改同样要做窗口重叠 409 校验
        String blocker = key("WB");
        createWorkBlock(blocker, section, 14, 16);
        mvc.perform(put("/api/v1/work-blocks/{key}", workKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 2, iso(15), iso(17), section)))
                .andExpect(status().isConflict());
    }

    // ---------- 取消释放与不可变记录 ----------

    @Test
    void cancelReleasesWindowAndKeepsImmutableRecord() throws Exception {
        String section = registerSection();
        String workKey = key("WB");
        createWorkBlock(workKey, section, 8, 10);

        // 窗口生效时受影响计划查询为空（无已发布计划）
        assertThat(read(mvc.perform(get("/api/v1/work-blocks/{key}/affected-plans", workKey))
                .andExpect(status().isOk()).andReturn())).isEmpty();

        String cancelRequest = key("REQ");
        MvcResult cancelled = mvc.perform(post("/api/v1/work-blocks/{key}/cancel", workKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelBody(cancelRequest, "op-b")))
                .andExpect(status().isOk()).andReturn();
        JsonNode view = read(cancelled);
        assertThat(view.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(view.get("version").asInt()).isEqualTo(1);
        assertThat(view.get("cancelledAt").isNull()).isFalse();

        // 默认列表不含已取消，includeCancelled=true 含
        JsonNode activeList = read(mvc.perform(get("/api/v1/work-blocks"))
                .andExpect(status().isOk()).andReturn());
        assertThat(activeList.findValuesAsText("workKey")).doesNotContain(workKey);
        JsonNode allList = read(mvc.perform(get("/api/v1/work-blocks")
                        .param("includeCancelled", "true"))
                .andExpect(status().isOk()).andReturn());
        assertThat(allList.findValuesAsText("workKey")).contains(workKey);

        // 不可变取消记录可查
        JsonNode records = read(mvc.perform(get("/api/v1/work-blocks/cancellations")
                        .param("workKey", workKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("workKey").asText()).isEqualTo(workKey);
        assertThat(records.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(records.get(0).get("operator").asText()).isEqualTo("op-b");

        // 同键同参重放返回首次响应
        MvcResult replay = mvc.perform(post("/api/v1/work-blocks/{key}/cancel", workKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelBody(cancelRequest, "op-b")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(view);

        // 已取消不可再取消（新键）→ 409
        mvc.perform(post("/api/v1/work-blocks/{key}/cancel", workKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelBody(key("REQ"), "op-b")))
                .andExpect(status().isConflict());

        // 已取消不可修改
        mvc.perform(put("/api/v1/work-blocks/{key}", workKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, iso(9), iso(11), section)))
                .andExpect(status().isConflict());

        // 占用已释放：同区段重叠窗口可创建，计划可发布
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("WB"), iso(8), iso(10), section)))
                .andExpect(status().isCreated());
    }

    @Test
    void affectedPlansQueryReturnsIntersectingPublishedPlans() throws Exception {
        String section = registerSection();
        String plan = createPlan(key("SCH"), section, 9, 11);
        publishPlan(plan);
        String workKey = key("WB");
        createWorkBlock(workKey, section, 8, 10);

        JsonNode affected = read(mvc.perform(get("/api/v1/work-blocks/{key}/affected-plans", workKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(affected).hasSize(1);
        assertThat(affected.get(0).get("scheduleKey").asText()).isEqualTo(plan);
        assertThat(affected.get(0).get("workKey").asText()).isEqualTo(workKey);
        assertThat(affected.get(0).get("sectionId").asText()).isEqualTo(section);
    }

    @Test
    void missingWorkBlockReturns404() throws Exception {
        String missing = key("WB");
        mvc.perform(get("/api/v1/work-blocks/{key}", missing)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/work-blocks/{key}/affected-plans", missing))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/work-blocks/{key}", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, iso(8), iso(9), registerSection())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/work-blocks/{key}/cancel", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelBody(key("REQ"), "op")))
                .andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void createIdempotentReplayReorderSectionsAndParamConflict() throws Exception {
        String sectionA = registerSection();
        String sectionB = registerSection();
        String workKey = key("WB");
        String requestKey = key("REQ");
        String operator = "op-fixed";
        String body = createBody(requestKey, workKey, iso(8), iso(10),
                List.of(sectionA, sectionB), operator);

        MvcResult first = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放
        MvcResult replay = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 区段集合换序视为同参 → 重放而非冲突/重复创建
        String reordered = createBody(requestKey, workKey, iso(8), iso(10),
                List.of(sectionB, sectionA), operator);
        MvcResult reorderedResult = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON).content(reordered))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(reorderedResult)).isEqualTo(read(first));

        // 同键异参（不同操作者）→ 409
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(requestKey, key("WB"), iso(8), iso(10),
                                List.of(sectionA, sectionB), "other-op")))
                .andExpect(status().isConflict());
    }

    @Test
    void failedOverlapDoesNotOccupyRequestKey() throws Exception {
        String section = registerSection();
        String first = key("WB");
        createWorkBlock(first, section, 8, 10);
        String requestKey = key("REQ");
        String second = key("WB");

        // 首次 409（重叠），失败不占键
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(requestKey, second, iso(9), iso(11), section)))
                .andExpect(status().isConflict());
        // 同键修正为不相邻冲突的参数后成功，且响应为新建窗口
        MvcResult ok = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(requestKey, second, iso(10), iso(11), section)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(ok).get("workKey").asText()).isEqualTo(second);
    }

    // ---------- 辅助 ----------

    private String registerSection() {
        return registerSection("SEC");
    }

    private String registerSection(String prefix) {
        String sectionId = prefix + "-" + UUID.randomUUID();
        jdbc.update("MERGE INTO rail_section KEY(section_id) VALUES (?, ?, ?)",
                sectionId, "测试区段-" + sectionId, 0L);
        return sectionId;
    }

    private void createWorkBlock(String workKey, String section, int startHour, int endHour)
            throws Exception {
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), workKey, iso(startHour), iso(endHour),
                                section)))
                .andExpect(status().isCreated());
    }

    private void cancelWorkBlock(String workKey) throws Exception {
        mvc.perform(post("/api/v1/work-blocks/{key}/cancel", workKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelBody(key("REQ"), "op")))
                .andExpect(status().isOk());
    }

    private String createPlan(String scheduleKey, String section, int startHour, int endHour)
            throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(planCreateBody(key("REQ"), scheduleKey,
                                planOcc("G1", section, iso(startHour), iso(endHour)))))
                .andExpect(status().isCreated());
        return scheduleKey;
    }

    private void publishPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant().toString();
    }

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String createBody(String requestKey, String workKey, String startIso,
                                     String endIso, String... sections) {
        return createBody(requestKey, workKey, startIso, endIso, java.util.List.of(sections),
                "op-" + UUID.randomUUID());
    }

    private static String createBody(String requestKey, String workKey, String startIso,
                                     String endIso, java.util.List<String> sections,
                                     String operator) {
        return "{\"requestKey\":\"" + requestKey + "\",\"workKey\":\"" + workKey
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso
                + "\",\"sectionIds\":[" + sections.stream().map(s -> "\"" + s + "\"")
                .collect(java.util.stream.Collectors.joining(","))
                + "],\"operator\":\"" + operator + "\"}";
    }

    private static String updateBody(String requestKey, int expectedVersion, String startIso,
                                     String endIso, String... sections) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso
                + "\",\"sectionIds\":[" + java.util.Arrays.stream(sections)
                .map(s -> "\"" + s + "\"").collect(java.util.stream.Collectors.joining(","))
                + "],\"operator\":\"op-" + UUID.randomUUID() + "\"}";
    }

    private static String cancelBody(String requestKey, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator + "\"}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String planOcc(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String planCreateBody(String requestKey, String scheduleKey, String... occs) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":[" + String.join(",", occs) + "]}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }
}
