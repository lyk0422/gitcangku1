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
 * 走廊等级优先级抢占与降级回退的端到端测试（H2 内存库，MySQL 兼容模式）。
 * 覆盖：等级登记与继承、抢占主流程与原子降级、422 等级不足、409 重复抢占、
 * PREEMPTED 终态约束、幂等重放与异参 409、抢占记录与按区段等级占用查询。
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

    // ---------- 等级登记与继承 ----------

    @Test
    void registerSectionPriorityAndPlanInheritsMaxLevel() throws Exception {
        String sectionLow = key("SEC");
        String sectionHigh = key("SEC");
        registerPriority(sectionLow, 2);
        registerPriority(sectionHigh, 5);

        // 重复登记为 upsert，覆盖为最新等级
        MvcResult updated = mvc.perform(put("/api/v1/sections/{id}/priority", sectionLow)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"priority\":3}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(updated).get("priority").asInt()).isEqualTo(3);

        // 计划继承全部占用区段的最高等级
        String scheduleKey = key("SCH");
        MvcResult created = mvc.perform(post("/api/v1/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey,
                                occ("G1", sectionLow, iso(8), iso(9)),
                                occ("G2", sectionHigh, iso(9), iso(10)))))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(created).get("corridorLevel").asInt()).isEqualTo(5);

        // 未登记区段按最低等级 1
        String unregistered = key("SCH");
        MvcResult createdDefault = mvc.perform(post("/api/v1/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), unregistered,
                                occ("G1", key("SEC"), iso(8), iso(9)))))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(createdDefault).get("corridorLevel").asInt()).isEqualTo(1);
    }

    @Test
    void registerSectionPriorityRejectsInvalidLevel() throws Exception {
        String section = key("SEC");
        mvc.perform(put("/api/v1/sections/{id}/priority", section)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"priority\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/sections/{id}/priority", section)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"priority\":6}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/sections/{id}/priority", section)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 抢占主流程与原子降级 ----------

    @Test
    void preemptSuccessDemotesLowPlanAtomically() throws Exception {
        String section = key("SEC");
        registerPriority(section, 2);
        String low = key("SCH");
        createPlan(low, occ("G1", section, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        // 高等级草稿：占用同一区段（等级 2）与另一高等级区段（等级 4），继承等级 4
        String sectionHigh = key("SEC");
        registerPriority(sectionHigh, 4);
        String high = key("SCH");
        String preemptKey = key("PRE");
        createPlan(high,
                occ("G2", section, iso(8), iso(9)),
                occ("G3", sectionHigh, iso(8), iso(9)));

        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", high)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), preemptKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(published).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(read(published).get("corridorLevel").asInt()).isEqualTo(4);

        // 被抢占计划整体转为 PREEMPTED 终态，历史占用保留可查询
        MvcResult lowDetail = mvc.perform(get("/api/v1/plans/{key}", low))
                .andExpect(status().isOk()).andReturn();
        JsonNode lowBody = read(lowDetail);
        assertThat(lowBody.get("status").asText()).isEqualTo("PREEMPTED");
        assertThat(lowBody.get("occupancies")).hasSize(1);

        // 时隙释放：当前生效时隙只属于抢占方
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        JsonNode slotList = read(slots);
        assertThat(slotList).hasSize(1);
        assertThat(slotList.get(0).get("scheduleKey").asText()).isEqualTo(high);

        // 不可变抢占记录：固化双方计划、涉及区段与各自等级
        MvcResult records = mvc.perform(get("/api/v1/preemption-records")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        JsonNode recordList = read(records);
        assertThat(recordList).hasSize(1);
        JsonNode record = recordList.get(0);
        assertThat(record.get("preemptingScheduleKey").asText()).isEqualTo(high);
        assertThat(record.get("preemptedScheduleKey").asText()).isEqualTo(low);
        assertThat(record.get("preemptingLevel").asInt()).isEqualTo(4);
        assertThat(record.get("preemptedLevel").asInt()).isEqualTo(2);
        assertThat(record.get("preemptKey").asText()).isEqualTo(preemptKey);
        assertThat(record.get("sections")).hasSize(1);
        JsonNode recordSection = record.get("sections").get(0);
        assertThat(recordSection.get("sectionId").asText()).isEqualTo(section);
        assertThat(recordSection.get("sectionLevel").asInt()).isEqualTo(2);
        assertThat(recordSection.get("startUtc").asText()).isEqualTo(iso(8));
        assertThat(recordSection.get("endUtc").asText()).isEqualTo(iso(9));

        // 按其他区段过滤无记录
        MvcResult none = mvc.perform(get("/api/v1/preemption-records")
                        .param("date", DAY.toString()).param("sectionId", key("SEC")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(none)).isEmpty();
    }

    @Test
    void preemptedPlanIsTerminalAndKeepsHistory() throws Exception {
        String section = key("SEC");
        registerPriority(section, 1);
        String low = key("SCH");
        createPlan(low, occ("G1", section, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        String sectionHigh = key("SEC");
        registerPriority(sectionHigh, 5);
        String high = key("SCH");
        createPlan(high, occ("G2", section, iso(8), iso(9)),
                occ("G3", sectionHigh, iso(8), iso(9)));
        publishPlan(high, key("REQ"), key("PRE"));

        // PREEMPTED 不可再改签
        mvc.perform(put("/api/v1/plans/{key}/occupancies", low)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, occ("G1", section, iso(10), iso(11)))))
                .andExpect(status().isConflict());
        // PREEMPTED 不可取消
        mvc.perform(post("/api/v1/plans/{key}/cancel", low)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());
        // PREEMPTED 不可再发布
        mvc.perform(post("/api/v1/plans/{key}/publish", low)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());
        // 历史占用与发布历史（版本、占用清单）保留可查询
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", low))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(detail);
        assertThat(body.get("status").asText()).isEqualTo("PREEMPTED");
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("occupancies")).hasSize(1);
        assertThat(body.get("occupancies").get(0).get("trainNo").asText()).isEqualTo("G1");
    }

    // ---------- 422 未满足抢占条件 ----------

    @Test
    void preemptRejectsWhenAnySectionLevelNotLower() throws Exception {
        String sectionConflict = key("SEC");
        String sectionBlocking = key("SEC");
        registerPriority(sectionConflict, 2);
        registerPriority(sectionBlocking, 5);
        // 低等级冲突区段 + 高等级阻挡区段：被抢占方继承等级 5
        String low = key("SCH");
        createPlan(low,
                occ("G1", sectionConflict, iso(8), iso(9)),
                occ("G2", sectionBlocking, iso(9), iso(10)));
        publishPlan(low, key("REQ"), null);

        // 草稿继承等级 4，仅冲突 sectionConflict，但对方 sectionBlocking 等级 5 不低于 4
        String sectionMid = key("SEC");
        registerPriority(sectionMid, 4);
        String draft = key("SCH");
        createPlan(draft, occ("G3", sectionConflict, iso(8), iso(9)),
                occ("G4", sectionMid, iso(8), iso(9)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", draft)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), key("PRE"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("PREEMPT_CONDITION_NOT_MET");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("PREEMPT_LEVEL_INSUFFICIENT");
        assertThat(detail.get("sectionId").asText()).isEqualTo(sectionBlocking);
        assertThat(detail.get("sectionLevel").asInt()).isEqualTo(5);
        assertThat(detail.get("draftLevel").asInt()).isEqualTo(4);

        // 整单回滚：对方仍为已发布，草稿仍为草稿，无抢占记录
        assertThat(getStatus(low)).isEqualTo("PUBLISHED");
        assertThat(getStatus(draft)).isEqualTo("DRAFT");
        MvcResult records = mvc.perform(get("/api/v1/preemption-records")
                        .param("sectionId", sectionConflict))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(records)).isEmpty();
    }

    @Test
    void preemptRejectsEqualLevel() throws Exception {
        String section = key("SEC");
        registerPriority(section, 3);
        String low = key("SCH");
        createPlan(low, occ("G1", section, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        // 同等级（3）不满足"全部低于"，等级不足按 422 处理
        String draft = key("SCH");
        createPlan(draft, occ("G2", section, iso(8), iso(9)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", draft)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), key("PRE"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("PREEMPT_CONDITION_NOT_MET");
        assertThat(getStatus(low)).isEqualTo("PUBLISHED");
        assertThat(getStatus(draft)).isEqualTo("DRAFT");
    }

    // ---------- 409 同一时隙只能被抢占一次 ----------

    @Test
    void sameSlotCanOnlyBePreemptedOnce() throws Exception {
        String section = key("SEC");
        registerPriority(section, 1);
        String low = key("SCH");
        createPlan(low, occ("G1", section, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        // 第一次抢占：等级 4 抢占等级 1
        String sectionMid = key("SEC");
        registerPriority(sectionMid, 4);
        String first = key("SCH");
        createPlan(first, occ("G2", section, iso(8), iso(9)),
                occ("G3", sectionMid, iso(8), iso(9)));
        publishPlan(first, key("REQ"), key("PRE"));
        assertThat(getStatus(low)).isEqualTo("PREEMPTED");

        // 第二次抢占同一时隙：等级 5 草稿冲突的是 first（等级 4），
        // 等级满足但该时隙已被抢占过 → 409
        String sectionTop = key("SEC");
        registerPriority(sectionTop, 5);
        String second = key("SCH");
        createPlan(second, occ("G4", section, iso(8), iso(9)),
                occ("G5", sectionTop, iso(8), iso(9)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), key("PRE"))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("SLOT_ALREADY_PREEMPTED");

        // 状态不变：first 仍已发布，second 仍草稿，抢占记录仍只有一条
        assertThat(getStatus(first)).isEqualTo("PUBLISHED");
        assertThat(getStatus(second)).isEqualTo("DRAFT");
        MvcResult records = mvc.perform(get("/api/v1/preemption-records")
                        .param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(records)).hasSize(1);
    }

    // ---------- 幂等 ----------

    @Test
    void preemptIdempotentReplayAndParamConflict() throws Exception {
        String section = key("SEC");
        registerPriority(section, 1);
        String low = key("SCH");
        createPlan(low, occ("G1", section, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        String sectionHigh = key("SEC");
        registerPriority(sectionHigh, 4);
        String high = key("SCH");
        createPlan(high, occ("G2", section, iso(8), iso(9)),
                occ("G3", sectionHigh, iso(8), iso(9)));

        String requestKey = key("REQ");
        String preemptKey = key("PRE");
        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/publish", high)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey, preemptKey)))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放首次结果，不产生第二条抢占记录
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", high)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey, preemptKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        MvcResult records = mvc.perform(get("/api/v1/preemption-records")
                        .param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(records)).hasSize(1);

        // 同键异参（不同 preemptKey 或缺省 preemptKey）→ 409
        mvc.perform(post("/api/v1/plans/{key}/publish", high)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey, key("PRE"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/plans/{key}/publish", high)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isConflict());
    }

    @Test
    void failedPreemptDoesNotConsumeRequestKey() throws Exception {
        String section = key("SEC");
        registerPriority(section, 4);
        String low = key("SCH");
        createPlan(low, occ("G1", section, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        // 草稿等级 3 低于对方 4 → 422，失败不占键
        String sectionDraft = key("SEC");
        registerPriority(sectionDraft, 3);
        String draft = key("SCH");
        createPlan(draft, occ("G2", section, iso(8), iso(9)),
                occ("G3", sectionDraft, iso(8), iso(9)));
        String requestKey = key("REQ");
        String preemptKey = key("PRE");
        mvc.perform(post("/api/v1/plans/{key}/publish", draft)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey, preemptKey)))
                .andExpect(status().isUnprocessableEntity());

        // 提升草稿区段等级后同键重发成功
        registerPriority(sectionDraft, 5);
        mvc.perform(post("/api/v1/plans/{key}/publish", draft)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey, preemptKey)))
                .andExpect(status().isOk());
        assertThat(getStatus(low)).isEqualTo("PREEMPTED");
        assertThat(getStatus(draft)).isEqualTo("PUBLISHED");
    }

    // ---------- 按区段的当前等级占用查询 ----------

    @Test
    void sectionOccupancyQueryReturnsLevelAndLiveSlots() throws Exception {
        String section = key("SEC");
        registerPriority(section, 4);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(scheduleKey, key("REQ"), null);

        MvcResult view = mvc.perform(get("/api/v1/sections/{id}/occupancy", section)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(view);
        assertThat(body.get("sectionId").asText()).isEqualTo(section);
        assertThat(body.get("priority").asInt()).isEqualTo(4);
        assertThat(body.get("effectiveLevel").asInt()).isEqualTo(4);
        assertThat(body.get("occupancies")).hasSize(1);
        JsonNode slot = body.get("occupancies").get(0);
        assertThat(slot.get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(slot.get("opDate").asText()).isEqualTo(DAY.toString());

        // 未登记区段：priority 为 null，effectiveLevel 为 1，占用为空
        String unregistered = key("SEC");
        MvcResult empty = mvc.perform(get("/api/v1/sections/{id}/occupancy", unregistered))
                .andExpect(status().isOk()).andReturn();
        JsonNode emptyBody = read(empty);
        assertThat(emptyBody.get("priority").isNull()).isTrue();
        assertThat(emptyBody.get("effectiveLevel").asInt()).isEqualTo(1);
        assertThat(emptyBody.get("occupancies")).isEmpty();
    }

    // ---------- 辅助 ----------

    private void registerPriority(String sectionId, int priority) throws Exception {
        mvc.perform(put("/api/v1/sections/{id}/priority", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":" + priority + "}"))
                .andExpect(status().isOk());
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
                        .content(actionBody(requestKey, preemptKey)))
                .andExpect(status().isOk());
    }

    private String getStatus(String scheduleKey) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(detail).get("status").asText();
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

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String actionBody(String requestKey, String preemptKey) {
        return preemptKey == null
                ? actionBody(requestKey)
                : "{\"requestKey\":\"" + requestKey + "\",\"preemptKey\":\"" + preemptKey + "\"}";
    }
}
