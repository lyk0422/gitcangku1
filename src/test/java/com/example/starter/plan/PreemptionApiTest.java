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
 * 走廊等级优先级抢占与降级回退的端到端测试（H2 内存库，MODE=MySQL）。
 * 覆盖：区段登记、抢占校验（422/409）、原子降级与整单回退、抢占链记录、
 * PREEMPTED 终态约束、幂等边界与按区段等级占用查询。
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
    void registerSectionValidatesPriorityAndSupportsQuery() throws Exception {
        String section = key("SEC");
        // 未登记 → 404
        mvc.perform(get("/api/v1/sections/{id}", section)).andExpect(status().isNotFound());
        // 等级越界 → 400
        mvc.perform(put("/api/v1/sections/{id}", section).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/sections/{id}", section).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":6}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/sections/{id}", section).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // 登记与更新
        registerSection(section, 3);
        MvcResult view = mvc.perform(get("/api/v1/sections/{id}", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(view).get("priority").asInt()).isEqualTo(3);
        registerSection(section, 5);
        MvcResult updated = mvc.perform(get("/api/v1/sections/{id}", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(updated).get("priority").asInt()).isEqualTo(5);
    }

    // ---------- 抢占主流程 ----------

    @Test
    void preemptLowerLevelPlanSucceedsAndDemotesAtomically() throws Exception {
        String secLow = key("SEC");
        String secHigh = key("SEC");
        registerSection(secLow, 2);
        registerSection(secHigh, 5);

        // 低等级计划发布，继承区段等级 2
        String low = key("SCH");
        createPlan(low, occ("G1", secLow, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);
        MvcResult lowPublished = mvc.perform(get("/api/v1/plans/{key}", low))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(lowPublished).get("planLevel").asInt()).isEqualTo(2);

        // 高等级草稿（等级 5）携带 preemptKey 抢占
        String draft = key("SCH");
        String preemptKey = key("PRE");
        createPlan(draft, occ("G2", secLow, iso(8), iso(9)), occ("G3", secHigh, iso(10), iso(11)));
        MvcResult won = mvc.perform(post("/api/v1/plans/{key}/publish", draft)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), preemptKey)))
                .andExpect(status().isOk()).andReturn();
        JsonNode wonBody = read(won);
        assertThat(wonBody.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(wonBody.get("planLevel").asInt()).isEqualTo(5);

        // 被抢占计划转为 PREEMPTED 终态，原始占用保留可查询
        MvcResult lowAfter = mvc.perform(get("/api/v1/plans/{key}", low))
                .andExpect(status().isOk()).andReturn();
        JsonNode lowBody = read(lowAfter);
        assertThat(lowBody.get("status").asText()).isEqualTo("PREEMPTED");
        assertThat(lowBody.get("occupancies")).hasSize(1);
        assertThat(lowBody.get("planLevel").asInt()).isEqualTo(2);

        // PREEMPTED 终态：不可再改签、取消或发布
        mvc.perform(put("/api/v1/plans/{key}/occupancies", low)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, occ("G1", secLow, iso(8), iso(9)))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/plans/{key}/cancel", low)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/plans/{key}/publish", low)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());

        // 时隙释放并归属抢占方：区段上只有抢占方的生效时隙
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", secLow))
                .andExpect(status().isOk()).andReturn();
        JsonNode slotList = read(slots);
        assertThat(slotList).hasSize(1);
        assertThat(slotList.get(0).get("scheduleKey").asText()).isEqualTo(draft);

        // 不可变抢占记录：固化双方计划、涉及区段与各自等级
        MvcResult records = mvc.perform(get("/api/v1/preemptions")
                        .param("scheduleKey", low))
                .andExpect(status().isOk()).andReturn();
        JsonNode recordList = read(records);
        assertThat(recordList).hasSize(1);
        JsonNode record = recordList.get(0);
        assertThat(record.get("preemptKey").asText()).isEqualTo(preemptKey);
        assertThat(record.get("winnerScheduleKey").asText()).isEqualTo(draft);
        assertThat(record.get("winnerLevel").asInt()).isEqualTo(5);
        assertThat(record.get("loserScheduleKey").asText()).isEqualTo(low);
        assertThat(record.get("loserLevel").asInt()).isEqualTo(2);
        assertThat(record.get("opDate").asText()).isEqualTo(DAY.toString());
        assertThat(record.get("sections")).hasSize(1);
        assertThat(record.get("sections").get(0).get("sectionId").asText()).isEqualTo(secLow);
        assertThat(record.get("sections").get(0).get("sectionLevel").asInt()).isEqualTo(2);

        // 按区段的当前等级占用查询
        MvcResult occupancy = mvc.perform(get("/api/v1/section-occupancy")
                        .param("date", DAY.toString()).param("sectionId", secLow))
                .andExpect(status().isOk()).andReturn();
        JsonNode occList = read(occupancy);
        assertThat(occList).hasSize(1);
        assertThat(occList.get(0).get("scheduleKey").asText()).isEqualTo(draft);
        assertThat(occList.get(0).get("planLevel").asInt()).isEqualTo(5);
        assertThat(occList.get(0).get("sectionLevel").asInt()).isEqualTo(2);
    }

    @Test
    void conflictWithoutPreemptKeyStillRejected422() throws Exception {
        String section = key("SEC");
        registerSection(section, 1);
        String low = key("SCH");
        createPlan(low, occ("G1", section, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        String draft = key("SCH");
        createPlan(draft, occ("G2", section, iso(8), iso(9)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", draft)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("SLOT_CONFLICT");
        // 双方状态不变
        assertThat(getStatus(low)).isEqualTo("PUBLISHED");
        assertThat(getStatus(draft)).isEqualTo("DRAFT");
    }

    @Test
    void preemptBlockedBySectionNotLowerThanDraftReturns422() throws Exception {
        String section = key("SEC");
        registerSection(section, 5);
        String low = key("SCH");
        createPlan(low, occ("G1", section, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        // 草稿等级 5，对方区段等级 5 不低于草稿 → 422 返回该区段与等级
        String draft = key("SCH");
        createPlan(draft, occ("G2", section, iso(8), iso(9)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", draft)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), key("PRE"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("PREEMPT_LEVEL_INSUFFICIENT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("sectionLevel").asInt()).isEqualTo(5);
        assertThat(detail.get("draftLevel").asInt()).isEqualTo(5);

        // 未登记区段按 1 级：双方默认 1 级同样不满足"全部低于"
        String plain = key("SEC");
        String lowPlain = key("SCH");
        createPlan(lowPlain, occ("G1", plain, iso(8), iso(9)));
        publishPlan(lowPlain, key("REQ"), null);
        String draftPlain = key("SCH");
        createPlan(draftPlain, occ("G2", plain, iso(8), iso(9)));
        mvc.perform(post("/api/v1/plans/{key}/publish", draftPlain)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), key("PRE"))))
                .andExpect(status().isUnprocessableEntity());

        assertThat(getStatus(low)).isEqualTo("PUBLISHED");
        assertThat(getStatus(draft)).isEqualTo("DRAFT");
    }

    @Test
    void preemptChainRecordedAndQueryable() throws Exception {
        String secA = key("SEC");
        String secB = key("SEC");
        String secC = key("SEC");
        registerSection(secA, 1);
        registerSection(secB, 3);
        registerSection(secC, 5);

        // L（等级 1）→ A（等级 3）抢占 → C（等级 5）再抢占 A，形成抢占链
        String low = key("SCH");
        createPlan(low, occ("G1", secA, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        String mid = key("SCH");
        createPlan(mid, occ("G2", secA, iso(8), iso(9)), occ("G3", secB, iso(10), iso(11)));
        publishPlan(mid, key("REQ"), key("PRE"));

        String top = key("SCH");
        createPlan(top, occ("G4", secA, iso(8), iso(9)), occ("G5", secC, iso(10), iso(11)));
        publishPlan(top, key("REQ"), key("PRE"));

        assertThat(getStatus(low)).isEqualTo("PREEMPTED");
        assertThat(getStatus(mid)).isEqualTo("PREEMPTED");
        assertThat(getStatus(top)).isEqualTo("PUBLISHED");

        // 链式记录可按任一方计划键查询，按提交顺序返回
        MvcResult midRecords = mvc.perform(get("/api/v1/preemptions")
                        .param("scheduleKey", mid))
                .andExpect(status().isOk()).andReturn();
        JsonNode records = read(midRecords);
        assertThat(records).hasSize(2);
        assertThat(records.get(0).get("winnerScheduleKey").asText()).isEqualTo(mid);
        assertThat(records.get(0).get("loserScheduleKey").asText()).isEqualTo(low);
        assertThat(records.get(0).get("winnerLevel").asInt()).isEqualTo(3);
        assertThat(records.get(1).get("winnerScheduleKey").asText()).isEqualTo(top);
        assertThat(records.get(1).get("loserScheduleKey").asText()).isEqualTo(mid);
        assertThat(records.get(1).get("loserLevel").asInt()).isEqualTo(3);

        // 全部记录可按运营日过滤
        MvcResult dayRecords = mvc.perform(get("/api/v1/preemptions")
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(dayRecords).size()).isGreaterThanOrEqualTo(2);

        // 最终区段上只有 top 的占用
        MvcResult occupancy = mvc.perform(get("/api/v1/section-occupancy")
                        .param("date", DAY.toString()).param("sectionId", secA))
                .andExpect(status().isOk()).andReturn();
        JsonNode occList = read(occupancy);
        assertThat(occList).hasSize(1);
        assertThat(occList.get(0).get("scheduleKey").asText()).isEqualTo(top);
        assertThat(occList.get(0).get("planLevel").asInt()).isEqualTo(5);
    }

    @Test
    void secondPreemptionOfSameSlotRejected409() throws Exception {
        String secA = key("SEC");
        String secB = key("SEC");
        String secC = key("SEC");
        registerSection(secA, 1);
        registerSection(secB, 5);
        registerSection(secC, 5);

        String low = key("SCH");
        createPlan(low, occ("G1", secA, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        String winner = key("SCH");
        createPlan(winner, occ("G2", secA, iso(8), iso(9)), occ("G3", secB, iso(10), iso(11)));
        publishPlan(winner, key("REQ"), key("PRE"));

        // 同等级草稿再抢同一时隙：当前持有者由抢占获得且等级不低 → 409
        String challenger = key("SCH");
        createPlan(challenger, occ("G4", secA, iso(8), iso(9)), occ("G5", secC, iso(10), iso(11)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", challenger)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), key("PRE"))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("SLOT_ALREADY_PREEMPTED");

        assertThat(getStatus(challenger)).isEqualTo("DRAFT");
        assertThat(getStatus(winner)).isEqualTo("PUBLISHED");
        // 仍然只有一条抢占记录
        MvcResult records = mvc.perform(get("/api/v1/preemptions")
                        .param("scheduleKey", low))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(records)).hasSize(1);
    }

    @Test
    void preemptRollsBackEntirelyWhenAnyConflictNotPreemptable() throws Exception {
        String secLow = key("SEC");
        String secHigh = key("SEC");
        registerSection(secLow, 1);
        registerSection(secHigh, 5);

        String prey = key("SCH");
        createPlan(prey, occ("G1", secLow, iso(8), iso(9)));
        publishPlan(prey, key("REQ"), null);
        String blocker = key("SCH");
        createPlan(blocker, occ("G2", secHigh, iso(8), iso(9)));
        publishPlan(blocker, key("REQ"), null);

        // 草稿同时冲突可抢占的 prey 与不可抢占的 blocker → 整单回退
        String draft = key("SCH");
        createPlan(draft, occ("G3", secLow, iso(8), iso(9)), occ("G4", secHigh, iso(8), iso(9)));
        mvc.perform(post("/api/v1/plans/{key}/publish", draft)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), key("PRE"))))
                .andExpect(status().isUnprocessableEntity());

        // 已发布计划不变，无抢占记录，草稿保持草稿
        assertThat(getStatus(prey)).isEqualTo("PUBLISHED");
        assertThat(getStatus(blocker)).isEqualTo("PUBLISHED");
        assertThat(getStatus(draft)).isEqualTo("DRAFT");
        MvcResult records = mvc.perform(get("/api/v1/preemptions")
                        .param("scheduleKey", prey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(records)).isEmpty();
    }

    // ---------- 幂等边界 ----------

    @Test
    void preemptIdempotentReplayAndKeyConflicts() throws Exception {
        String secLow = key("SEC");
        String secHigh = key("SEC");
        registerSection(secLow, 1);
        registerSection(secHigh, 5);

        String low = key("SCH");
        createPlan(low, occ("G1", secLow, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        String draft = key("SCH");
        createPlan(draft, occ("G2", secLow, iso(8), iso(9)), occ("G3", secHigh, iso(10), iso(11)));
        String requestKey = key("REQ");
        String preemptKey = key("PRE");
        String body = publishBody(requestKey, preemptKey);

        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/publish", draft)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放首次结果，不产生第二条抢占记录
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", draft)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        MvcResult records = mvc.perform(get("/api/v1/preemptions")
                        .param("scheduleKey", low))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(records)).hasSize(1);

        // 同 requestKey 不同 preemptKey → 409
        mvc.perform(post("/api/v1/plans/{key}/publish", draft)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(requestKey, key("PRE"))))
                .andExpect(status().isConflict());

        // 已用 preemptKey 被另一抢占复用 → 409
        String low2 = key("SCH");
        String secLow2 = key("SEC");
        registerSection(secLow2, 1);
        createPlan(low2, occ("G1", secLow2, iso(8), iso(9)));
        publishPlan(low2, key("REQ"), null);
        String draft2 = key("SCH");
        createPlan(draft2, occ("G2", secLow2, iso(8), iso(9)), occ("G3", secHigh, iso(12), iso(13)));
        MvcResult reused = mvc.perform(post("/api/v1/plans/{key}/publish", draft2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), preemptKey)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(reused).get("code").asText()).isEqualTo("PREEMPT_KEY_REUSED");
        assertThat(getStatus(low2)).isEqualTo("PUBLISHED");
    }

    @Test
    void failedPreemptDoesNotOccupyRequestKey() throws Exception {
        String secLow = key("SEC");
        String secHigh = key("SEC");
        registerSection(secLow, 2);
        registerSection(secHigh, 5);

        String low = key("SCH");
        createPlan(low, occ("G1", secLow, iso(8), iso(9)));
        publishPlan(low, key("REQ"), null);

        // 草稿等级 2 不满足抢占条件 → 422，失败不占 requestKey
        String draft = key("SCH");
        String requestKey = key("REQ");
        String preemptKey = key("PRE");
        createPlan(draft, occ("G2", secLow, iso(8), iso(9)));
        mvc.perform(post("/api/v1/plans/{key}/publish", draft)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(requestKey, preemptKey)))
                .andExpect(status().isUnprocessableEntity());

        // 修正草稿（加入高等级区段）后同 requestKey 重发成功
        mvc.perform(put("/api/v1/plans/{key}/occupancies", draft)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1,
                                occ("G2", secLow, iso(8), iso(9)),
                                occ("G3", secHigh, iso(10), iso(11)))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{key}/publish", draft)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(requestKey, preemptKey)))
                .andExpect(status().isOk());
        assertThat(getStatus(low)).isEqualTo("PREEMPTED");
    }

    // ---------- 辅助 ----------

    private void registerSection(String sectionId, int priority) throws Exception {
        mvc.perform(put("/api/v1/sections/{id}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":" + priority + "}"))
                .andExpect(status().isOk());
    }

    private String getStatus(String scheduleKey) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(detail).get("status").asText();
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

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String publishBody(String requestKey, String preemptKey) {
        if (preemptKey == null) {
            return actionBody(requestKey);
        }
        return "{\"requestKey\":\"" + requestKey + "\",\"preemptKey\":\"" + preemptKey + "\"}";
    }
}
