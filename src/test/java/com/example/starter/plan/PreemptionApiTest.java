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
 * 走廊等级优先级抢占与降级回退的端到端测试（H2 内存库）：
 * 区段登记、抢占主流程、等级不足 422、同一时隙仅抢占一次 409、
 * PREEMPTED 终态约束、整单回滚与幂等边界。
 * 各用例使用唯一 scheduleKey/requestKey/区段 ID，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PreemptionApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 区段登记 ----------

    @Test
    void registerSectionValidationAndIdempotency() throws Exception {
        String section = key("SEC");
        String requestKey = key("REQ");

        MvcResult created = mvc.perform(post("/api/v1/sections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(requestKey, section, 3)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("sectionId").asText()).isEqualTo(section);
        assertThat(body.get("priority").asInt()).isEqualTo(3);

        // 同键同参重放首次结果
        MvcResult replay = mvc.perform(post("/api/v1/sections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(requestKey, section, 3)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(body);

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/sections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(requestKey, key("SEC"), 3)))
                .andExpect(status().isConflict());

        // 重复登记同一区段（新幂等键）→ 409
        mvc.perform(post("/api/v1/sections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("REQ"), section, 5)))
                .andExpect(status().isConflict());

        // 等级越界 → 400
        mvc.perform(post("/api/v1/sections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("REQ"), key("SEC"), 0)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/sections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("REQ"), key("SEC"), 6)))
                .andExpect(status().isBadRequest());
        // 缺 priority → 400
        mvc.perform(post("/api/v1/sections").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"sectionId\":\""
                                + key("SEC") + "\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 抢占主流程 ----------

    @Test
    void preemptLowerPriorityPlanSucceeds() throws Exception {
        String secA = key("SEC");
        String secB = key("SEC");
        registerSection(secA, 1);
        registerSection(secB, 5);

        String low = key("SCH");
        createPlan(low, occ("G1", secA, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        String high = key("SCH");
        MvcResult draft = mvc.perform(post("/api/v1/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), high,
                                occ("G2", secA, iso(8), iso(9)),
                                occ("G3", secB, iso(8), iso(9)))))
                .andExpect(status().isCreated()).andReturn();
        // 草稿等级继承全部占用区段最高等级
        assertThat(read(draft).get("level").asInt()).isEqualTo(5);

        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", high)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), key("PREEMPT"))))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(published).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(read(published).get("level").asInt()).isEqualTo(5);

        // 被抢占计划整体转为 PREEMPTED 终态，原始占用保留可查询
        MvcResult lowDetail = mvc.perform(get("/api/v1/plans/{key}", low))
                .andExpect(status().isOk()).andReturn();
        JsonNode lowBody = read(lowDetail);
        assertThat(lowBody.get("status").asText()).isEqualTo("PREEMPTED");
        assertThat(lowBody.get("occupancies")).hasSize(1);

        // 时隙释放并转交抢占方：区段上只有 HIGH 生效
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", secA))
                .andExpect(status().isOk()).andReturn();
        JsonNode slotList = read(slots);
        assertThat(slotList).hasSize(1);
        assertThat(slotList.get(0).get("scheduleKey").asText()).isEqualTo(high);

        // 不可变抢占记录：固化双方计划、涉及区段与各自等级
        JsonNode records = preemptionsFor(high, low);
        assertThat(records).hasSize(1);
        JsonNode record = records.get(0);
        assertThat(record.get("preemptingScheduleKey").asText()).isEqualTo(high);
        assertThat(record.get("preemptedScheduleKey").asText()).isEqualTo(low);
        assertThat(record.get("preemptingLevel").asInt()).isEqualTo(5);
        assertThat(record.get("preemptedLevel").asInt()).isEqualTo(1);
        assertThat(record.get("slots")).hasSize(1);
        assertThat(record.get("slots").get(0).get("sectionId").asText()).isEqualTo(secA);
        assertThat(record.get("slots").get(0).get("sectionLevel").asInt()).isEqualTo(1);

        // 按区段的当前等级占用查询
        MvcResult occupancy = mvc.perform(get("/api/v1/sections/{sectionId}/occupancy", secA)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode occList = read(occupancy);
        assertThat(occList).hasSize(1);
        assertThat(occList.get(0).get("scheduleKey").asText()).isEqualTo(high);
        assertThat(occList.get(0).get("sectionLevel").asInt()).isEqualTo(1);
        assertThat(occList.get(0).get("planLevel").asInt()).isEqualTo(5);
    }

    @Test
    void preemptMultipleTargetsAtomically() throws Exception {
        String secA = key("SEC");
        String secB = key("SEC");
        String secC = key("SEC");
        registerSection(secA, 1);
        registerSection(secB, 2);
        registerSection(secC, 5);

        String low1 = key("SCH");
        createPlan(low1, occ("G1", secA, iso(8), iso(9)));
        publishPlan(low1, key("REQ"), null);
        String low2 = key("SCH");
        createPlan(low2, occ("G2", secB, iso(8), iso(9)));
        publishPlan(low2, key("REQ"), null);

        String high = key("SCH");
        createPlan(high,
                occ("G3", secA, iso(8), iso(9)),
                occ("G4", secB, iso(8), iso(9)),
                occ("G5", secC, iso(8), iso(9)));
        publishPlan(high, key("REQ"), key("PREEMPT"));

        assertThat(getStatus(low1)).isEqualTo("PREEMPTED");
        assertThat(getStatus(low2)).isEqualTo("PREEMPTED");
        assertThat(getStatus(high)).isEqualTo("PUBLISHED");

        assertThat(preemptionsFor(high, low1, low2)).hasSize(2);
    }

    @Test
    void preemptionReleasesAllSlotsOfTargetPlan() throws Exception {
        String secA = key("SEC");
        String secZ = key("SEC");
        String secB = key("SEC");
        registerSection(secA, 1);
        registerSection(secB, 5);

        String low = key("SCH");
        createPlan(low,
                occ("G1", secA, iso(8), iso(9)),
                occ("G2", secZ, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        String high = key("SCH");
        createPlan(high, occ("G3", secA, iso(8), iso(9)), occ("G4", secB, iso(8), iso(9)));
        publishPlan(high, key("REQ"), key("PREEMPT"));

        // 整个计划降级：未参与冲突的 secZ 时隙同样释放，新计划可直接发布
        String follower = key("SCH");
        createPlan(follower, occ("G5", secZ, iso(8), iso(9)));
        publishPlan(follower, key("REQ"), null);
    }

    // ---------- 抢占条件不满足 422 ----------

    @Test
    void preemptRejectedWhenTargetSectionLevelNotLower() throws Exception {
        String secA = key("SEC");
        String secC = key("SEC");
        String secD = key("SEC");
        registerSection(secA, 1);
        registerSection(secC, 5);
        registerSection(secD, 5);

        String low = key("SCH");
        createPlan(low,
                occ("G1", secA, iso(8), iso(9)),
                occ("G2", secC, iso(10), iso(11)));
        publishPlan(low, key("REQ"), null);

        // 草稿等级 5，但对方 secC 等级 5 不低于草稿 → 422 返回该区段与等级
        String high = key("SCH");
        createPlan(high,
                occ("G3", secA, iso(8), iso(9)),
                occ("G4", secD, iso(8), iso(9)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", high)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), key("PREEMPT"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("PREEMPTION_LEVEL_INSUFFICIENT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SECTION_LEVEL_NOT_LOWER");
        assertThat(detail.get("sectionId").asText()).isEqualTo(secC);
        assertThat(detail.get("level").asInt()).isEqualTo(5);

        // 整单回滚：双方状态不变，无抢占记录
        assertThat(getStatus(low)).isEqualTo("PUBLISHED");
        assertThat(getStatus(high)).isEqualTo("DRAFT");
        assertThat(preemptionsFor(high, low)).isEmpty();
    }

    @Test
    void preemptRollsBackWhenOneOfMultipleTargetsLevelInsufficient() throws Exception {
        String secA = key("SEC");
        String secB = key("SEC");
        String secC = key("SEC");
        String secD = key("SEC");
        registerSection(secA, 1);
        registerSection(secB, 1);
        registerSection(secC, 5);
        registerSection(secD, 5);

        String low1 = key("SCH");
        createPlan(low1, occ("G1", secA, iso(8), iso(9)));
        publishPlan(low1, key("REQ"), null);
        String low2 = key("SCH");
        createPlan(low2,
                occ("G2", secB, iso(8), iso(9)),
                occ("G3", secC, iso(10), iso(11)));
        publishPlan(low2, key("REQ"), null);

        String high = key("SCH");
        createPlan(high,
                occ("G4", secA, iso(8), iso(9)),
                occ("G5", secB, iso(8), iso(9)),
                occ("G6", secD, iso(8), iso(9)));
        mvc.perform(post("/api/v1/plans/{key}/publish", high)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), key("PREEMPT"))))
                .andExpect(status().isUnprocessableEntity());

        // 任一目标不满足条件即整单回滚：low1 也不被降级
        assertThat(getStatus(low1)).isEqualTo("PUBLISHED");
        assertThat(getStatus(low2)).isEqualTo("PUBLISHED");
        assertThat(getStatus(high)).isEqualTo("DRAFT");
        assertThat(preemptionsFor(high, low1, low2)).isEmpty();
    }

    @Test
    void publishConflictWithoutPreemptKeyStillRejected() throws Exception {
        String secA = key("SEC");
        registerSection(secA, 1);

        String low = key("SCH");
        createPlan(low, occ("G1", secA, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        String high = key("SCH");
        createPlan(high, occ("G2", secA, iso(8), iso(9)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", high)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("SLOT_CONFLICT");
        assertThat(getStatus(low)).isEqualTo("PUBLISHED");
        assertThat(getStatus(high)).isEqualTo("DRAFT");
    }

    @Test
    void trainOverlapWithPreemptKeyStillRejectedAndRollsBack() throws Exception {
        String secA = key("SEC");
        registerSection(secA, 1);

        String low = key("SCH");
        createPlan(low, occ("G1", secA, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        // 同批草稿内部列车重叠，即使携带 preemptKey 也按原规则 422
        String high = key("SCH");
        createPlan(high,
                occ("G9", secA, iso(8), iso(9)),
                occ("G9", secA, iso(8), iso(9)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", high)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), key("PREEMPT"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("SLOT_CONFLICT");
        assertThat(getStatus(low)).isEqualTo("PUBLISHED");
        assertThat(preemptionsFor(high, low)).isEmpty();
    }

    // ---------- 同一时隙只能被抢占一次 ----------

    @Test
    void sameSlotCannotBePreemptedTwice() throws Exception {
        String secA = key("SEC");
        String secB = key("SEC");
        String secE = key("SEC");
        registerSection(secA, 1);
        registerSection(secB, 5);
        registerSection(secE, 5);

        String low = key("SCH");
        createPlan(low, occ("G1", secA, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        String first = key("SCH");
        createPlan(first, occ("G2", secA, iso(8), iso(9)), occ("G3", secB, iso(8), iso(9)));
        publishPlan(first, key("REQ"), key("PREEMPT"));
        assertThat(getStatus(low)).isEqualTo("PREEMPTED");

        // 第二个高等级草稿抢占同一时隙 → 409，即使等级不低于先前者
        String second = key("SCH");
        createPlan(second, occ("G4", secA, iso(8), iso(9)), occ("G5", secE, iso(8), iso(9)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), key("PREEMPT"))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("SLOT_ALREADY_PREEMPTED");

        assertThat(getStatus(first)).isEqualTo("PUBLISHED");
        assertThat(getStatus(second)).isEqualTo("DRAFT");
        assertThat(preemptionsFor(first, second, low)).hasSize(1);
    }

    // ---------- PREEMPTED 终态约束 ----------

    @Test
    void preemptedPlanIsTerminal() throws Exception {
        String secA = key("SEC");
        String secB = key("SEC");
        registerSection(secA, 1);
        registerSection(secB, 5);

        String low = key("SCH");
        createPlan(low, occ("G1", secA, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        String high = key("SCH");
        createPlan(high, occ("G2", secA, iso(8), iso(9)), occ("G3", secB, iso(8), iso(9)));
        publishPlan(high, key("REQ"), key("PREEMPT"));

        // 不可改签
        mvc.perform(put("/api/v1/plans/{key}/occupancies", low)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, occ("G1", secA, iso(9), iso(10)))))
                .andExpect(status().isConflict());
        // 不可取消
        mvc.perform(post("/api/v1/plans/{key}/cancel", low)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), null)))
                .andExpect(status().isConflict());
        // 不可再发布
        mvc.perform(post("/api/v1/plans/{key}/publish", low)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), key("PREEMPT"))))
                .andExpect(status().isConflict());

        // 原始占用与发布历史保留可查询
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", low))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(detail);
        assertThat(body.get("status").asText()).isEqualTo("PREEMPTED");
        assertThat(body.get("occupancies")).hasSize(1);
        assertThat(body.get("occupancies").get(0).get("trainNo").asText()).isEqualTo("G1");
    }

    // ---------- 幂等 ----------

    @Test
    void preemptIdempotentReplayAndParamConflict() throws Exception {
        String secA = key("SEC");
        String secB = key("SEC");
        registerSection(secA, 1);
        registerSection(secB, 5);

        String low = key("SCH");
        createPlan(low, occ("G1", secA, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        String high = key("SCH");
        createPlan(high, occ("G2", secA, iso(8), iso(9)), occ("G3", secB, iso(8), iso(9)));
        String requestKey = key("REQ");
        String preemptKey = key("PREEMPT");

        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/publish", high)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(requestKey, preemptKey)))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放首次结果，不重复降级或写记录
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", high)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(requestKey, preemptKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        assertThat(preemptionsFor(high, low)).hasSize(1);

        // 同键不同参（不同 preemptKey）→ 409
        mvc.perform(post("/api/v1/plans/{key}/publish", high)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(requestKey, key("PREEMPT"))))
                .andExpect(status().isConflict());
    }

    @Test
    void failedPublishDoesNotOccupyRequestKey() throws Exception {
        String secA = key("SEC");
        String secB = key("SEC");
        registerSection(secA, 1);
        registerSection(secB, 5);

        String low = key("SCH");
        createPlan(low, occ("G1", secA, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        String high = key("SCH");
        createPlan(high, occ("G2", secA, iso(8), iso(9)), occ("G3", secB, iso(8), iso(9)));
        String requestKey = key("REQ");

        // 首次不带 preemptKey → 422，失败不占键
        mvc.perform(post("/api/v1/plans/{key}/publish", high)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(requestKey, null)))
                .andExpect(status().isUnprocessableEntity());
        // 同键补 preemptKey 重发 → 成功（而非 409 幂等冲突）
        mvc.perform(post("/api/v1/plans/{key}/publish", high)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(requestKey, key("PREEMPT"))))
                .andExpect(status().isOk());
        assertThat(getStatus(low)).isEqualTo("PREEMPTED");
        assertThat(getStatus(high)).isEqualTo("PUBLISHED");
    }

    // ---------- 未登记区段默认等级 ----------

    @Test
    void unregisteredSectionDefaultsToLowestLevel() throws Exception {
        String secX = key("SEC");
        String secY = key("SEC");

        String low = key("SCH");
        createPlan(low, occ("G1", secX, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        // 双方均未登记（各按 1 级）：1 不低于 1 → 422
        String equalLevel = key("SCH");
        createPlan(equalLevel, occ("G2", secX, iso(8), iso(9)));
        mvc.perform(post("/api/v1/plans/{key}/publish", equalLevel)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), key("PREEMPT"))))
                .andExpect(status().isUnprocessableEntity());

        // 登记 2 级区段后草稿等级提升 → 可抢占未登记（1 级）计划
        registerSection(secY, 2);
        String higher = key("SCH");
        createPlan(higher,
                occ("G3", secX, iso(8), iso(9)),
                occ("G4", secY, iso(8), iso(9)));
        publishPlan(higher, key("REQ"), key("PREEMPT"));
        assertThat(getStatus(low)).isEqualTo("PREEMPTED");
    }

    // ---------- 辅助 ----------

    /** 查询涉及给定计划（抢占方或被抢占方）的抢占记录，过滤共享库中其他用例的数据。 */
    private JsonNode preemptionsFor(String... scheduleKeys) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/preemptions"))
                .andExpect(status().isOk()).andReturn();
        java.util.Set<String> keys = java.util.Set.of(scheduleKeys);
        java.util.List<JsonNode> matched = new java.util.ArrayList<>();
        for (JsonNode record : read(result)) {
            if (keys.contains(record.get("preemptingScheduleKey").asText())
                    || keys.contains(record.get("preemptedScheduleKey").asText())) {
                matched.add(record);
            }
        }
        return objectMapper.valueToTree(matched);
    }

    private String getStatus(String scheduleKey) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(detail).get("status").asText();
    }

    private void registerSection(String sectionId, int priority) throws Exception {
        mvc.perform(post("/api/v1/sections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("REQ"), sectionId, priority)))
                .andExpect(status().isCreated());
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, occupancies)))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey, String requestKey, String preemptKey)
            throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(requestKey, preemptKey)))
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

    private static String publishBody(String requestKey, String preemptKey) {
        StringBuilder sb = new StringBuilder("{\"requestKey\":\"").append(requestKey).append("\"");
        if (preemptKey != null) {
            sb.append(",\"preemptKey\":\"").append(preemptKey).append("\"");
        }
        return sb.append("}").toString();
    }

    private static String sectionBody(String requestKey, String sectionId, int priority) {
        return "{\"requestKey\":\"" + requestKey + "\",\"sectionId\":\"" + sectionId
                + "\",\"priority\":" + priority + "}";
    }
}
