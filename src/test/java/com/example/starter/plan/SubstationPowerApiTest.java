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
 * 供电预算 API 端到端测试（H2 内存库）：合成共享变电所场景、绑定规则、
 * 调上限裁决、幂等重放与功率快照冻结。各用例使用唯一业务键隔离数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubstationPowerApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 合成场景：两区段各 60 千瓦、上限 100 ----------

    @Test
    void sharedSubstationRejectsOverloadButAllowsAdjacent() throws Exception {
        String substation = key("SUB");
        createSubstation(substation, 100);
        String sectionA = key("SEC");
        String sectionB = key("SEC");
        bindSection(sectionA, substation, 1);
        bindSection(sectionB, substation, 1);

        // 第一单：区段 A 10:00-10:10 取 60 千瓦，发布成功
        String first = key("SCH");
        createPlan(first, occ("G1", sectionA, iso(10, 0), iso(10, 10), 60));
        publishPlan(first, key("REQ"));

        // 第二单：不同区段、时隙无碰撞，但同片累计 120 > 100 → 422，给出时间片与实际功率
        String second = key("SCH");
        createPlan(second, occ("G2", sectionB, iso(10, 0), iso(10, 10), 60));
        String publishKey = key("REQ");
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("POWER_LIMIT_EXCEEDED");
        assertThat(detail.get("substationKey").asText()).isEqualTo(substation);
        assertThat(detail.get("startUtc").asText()).isEqualTo(iso(10, 0));
        assertThat(detail.get("endUtc").asText()).isEqualTo(iso(10, 10));
        assertThat(detail.get("limitKw").asInt()).isEqualTo(100);
        assertThat(detail.get("actualKw").asInt()).isEqualTo(120);

        // 整次发布不写任何占用：计划保持草稿，区段 B 无生效时隙
        MvcResult detail2 = mvc.perform(get("/api/v1/plans/{key}", second))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail2).get("status").asText()).isEqualTo("DRAFT");
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", sectionB))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).isEmpty();

        // 前单 10:10 结束、后单 10:10 开始（左闭右开相邻）允许
        String adjacent = key("SCH");
        createPlan(adjacent, occ("G3", sectionB, iso(10, 10), iso(10, 20), 60));
        publishPlan(adjacent, key("REQ"));
    }

    @Test
    void accumulatesCandidateOccupanciesWithinPlan() throws Exception {
        String substation = key("SUB");
        createSubstation(substation, 100);
        String sectionA = key("SEC");
        String sectionB = key("SEC");
        bindSection(sectionA, substation, 1);
        bindSection(sectionB, substation, 1);

        // 同一计划两条占用落在不同已绑定区段，同片 60+60 > 100 → 422
        String plan = key("SCH");
        createPlan(plan,
                occ("G1", sectionA, iso(10, 0), iso(11, 0), 60),
                occ("G2", sectionB, iso(10, 0), iso(11, 0), 60));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", plan)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(rejected).get("details").get(0).get("actualKw").asInt()).isEqualTo(120);

        // 降为 40+60=100 恰好达到上限，允许
        String fits = key("SCH");
        createPlan(fits,
                occ("G1", sectionA, iso(10, 0), iso(11, 0), 40),
                occ("G2", sectionB, iso(10, 0), iso(11, 0), 60));
        publishPlan(fits, key("REQ"));
    }

    // ---------- 绑定规则 ----------

    @Test
    void bindRequiresCancellingLegacyOccupancies() throws Exception {
        String substation = key("SUB");
        createSubstation(substation, 100);
        String section = key("SEC");

        // 旧模式：未绑定区段发布占用，仅时隙校验
        String legacy = key("SCH");
        createPlan(legacy, occ("G1", section, iso(8, 0), iso(9, 0)));
        publishPlan(legacy, key("REQ"));

        // 存在已发布占用时绑定 → 409
        mvc.perform(put("/api/v1/sections/{sectionId}/binding", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody(key("RID"), "OP", substation, 1)))
                .andExpect(status().isConflict());

        // 取消旧占用后绑定成功；此后功率预算生效
        cancelPlan(legacy, key("REQ"));
        bindSection(section, substation, 1);

        String measured = key("SCH");
        createPlan(measured, occ("G2", section, iso(8, 0), iso(9, 0), 150));
        mvc.perform(post("/api/v1/plans/{key}/publish", measured)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unboundSectionKeepsLegacyMode() throws Exception {
        // 未绑定区段不校验功率：无 powerKw 声明照常发布
        String section = key("SEC");
        String plan = key("SCH");
        createPlan(plan, occ("G1", section, iso(8, 0), iso(9, 0)));
        publishPlan(plan, key("REQ"));
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", plan))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("occupancies").get(0).get("powerKw").asInt()).isEqualTo(0);
    }

    // ---------- 调上限裁决 ----------

    @Test
    void updateLimitValidatesVersionAndExistingUsage() throws Exception {
        String substation = key("SUB");
        createSubstation(substation, 100);
        String section = key("SEC");
        bindSection(section, substation, 1);
        String plan = key("SCH");
        createPlan(plan, occ("G1", section, iso(8, 0), iso(9, 0), 60));
        publishPlan(plan, key("REQ"));

        // 版本不匹配 → 409
        mvc.perform(put("/api/v1/substations/{key}", substation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateLimitBody(key("RID"), "OP", 200, 5)))
                .andExpect(status().isConflict());

        // 调低到 50 使现存 60 千瓦超限 → 409，上限保持 100
        MvcResult conflict = mvc.perform(put("/api/v1/substations/{key}", substation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateLimitBody(key("RID"), "OP", 50, 1)))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("POWER_LIMIT_CONFLICT");
        assertThat(error.get("details").get(0).get("actualKw").asInt()).isEqualTo(60);
        MvcResult after = mvc.perform(get("/api/v1/substations/{key}", substation))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after).get("powerLimitKw").asInt()).isEqualTo(100);
        assertThat(read(after).get("version").asInt()).isEqualTo(1);

        // 调到 60 恰好容纳 → 成功，版本加一
        MvcResult updated = mvc.perform(put("/api/v1/substations/{key}", substation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateLimitBody(key("RID"), "OP", 60, 1)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(updated).get("powerLimitKw").asInt()).isEqualTo(60);
        assertThat(read(updated).get("version").asInt()).isEqualTo(2);
    }

    @Test
    void rebindChecksTargetCapacity() throws Exception {
        String subA = key("SUB");
        String subB = key("SUB");
        createSubstation(subA, 100);
        createSubstation(subB, 50);
        String section = key("SEC");
        bindSection(section, subA, 1);
        String plan = key("SCH");
        createPlan(plan, occ("G1", section, iso(8, 0), iso(9, 0), 60));
        publishPlan(plan, key("REQ"));

        // 改绑到上限 50 的 B 使现存 60 千瓦超限 → 409
        mvc.perform(put("/api/v1/sections/{sectionId}/binding", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody(key("RID"), "OP", subB, 1)))
                .andExpect(status().isConflict());

        // B 上调到 100 后改绑成功，后续占用计入 B 的预算
        mvc.perform(put("/api/v1/substations/{key}", subB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateLimitBody(key("RID"), "OP", 100, 1)))
                .andExpect(status().isOk());
        bindSection(section, subB, 2);

        String section2 = key("SEC");
        bindSection(section2, subB, 2);
        String second = key("SCH");
        createPlan(second, occ("G2", section2, iso(8, 0), iso(9, 0), 60));
        mvc.perform(post("/api/v1/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 幂等 ----------

    @Test
    void substationIdempotentReplayAndGlobalKeyConflict() throws Exception {
        String substation = key("SUB");
        String requestId = key("RID");
        String body = substationBody(requestId, "OP", substation, 100);

        MvcResult first = mvc.perform(post("/api/v1/substations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放返回首次完整响应
        MvcResult replay = mvc.perform(post("/api/v1/substations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键改参 → 409
        mvc.perform(post("/api/v1/substations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(substationBody(requestId, "OP", substation, 200)))
                .andExpect(status().isConflict());

        // 全局 requestId：跨操作复用同键 → 409
        mvc.perform(put("/api/v1/sections/{sectionId}/binding", key("SEC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody(requestId, "OP", substation, 1)))
                .andExpect(status().isConflict());
    }

    @Test
    void failureDoesNotOccupyRequestId() throws Exception {
        String substation = key("SUB");
        createSubstation(substation, 100);

        // 重复 substationKey 创建失败（409），requestId 不被占用
        String requestId = key("RID");
        mvc.perform(post("/api/v1/substations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(substationBody(requestId, "OP", substation, 200)))
                .andExpect(status().isConflict());
        // 修正参数后同键重试成功
        mvc.perform(post("/api/v1/substations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(substationBody(requestId, "OP", key("SUB"), 200)))
                .andExpect(status().isCreated());

        // 缺 operator / requestId → 400
        mvc.perform(post("/api/v1/substations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"OP\",\"substationKey\":\"" + key("SUB")
                                + "\",\"powerLimitKw\":100}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/substations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key("RID") + "\",\"substationKey\":\""
                                + key("SUB") + "\",\"powerLimitKw\":100}"))
                .andExpect(status().isBadRequest());
        // 非正上限 → 400
        mvc.perform(post("/api/v1/substations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(substationBody(key("RID"), "OP", key("SUB"), 0)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 快照冻结与改签 ----------

    @Test
    void powerSnapshotFrozenAtDecisionAndHistoryStable() throws Exception {
        String substation = key("SUB");
        createSubstation(substation, 100);
        String section = key("SEC");
        bindSection(section, substation, 1);
        String plan = key("SCH");
        createPlan(plan, occ("G1", section, iso(8, 0), iso(9, 0), 60));
        publishPlan(plan, key("REQ"));

        // 快照冻结裁决时绑定、功率与上限版本
        MvcResult snapshot = mvc.perform(get("/api/v1/plans/{key}/power-snapshot", plan))
                .andExpect(status().isOk()).andReturn();
        JsonNode frozen = read(snapshot);
        assertThat(frozen).hasSize(1);
        assertThat(frozen.get(0).get("substationKey").asText()).isEqualTo(substation);
        assertThat(frozen.get(0).get("limitVersion").asInt()).isEqualTo(1);
        assertThat(frozen.get(0).get("limitKw").asInt()).isEqualTo(100);
        assertThat(frozen.get(0).get("powerKw").asInt()).isEqualTo(60);

        // 上限调整后历史快照不漂移；新发布冻结新版本
        mvc.perform(put("/api/v1/substations/{key}", substation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateLimitBody(key("RID"), "OP", 200, 1)))
                .andExpect(status().isOk());
        MvcResult snapshotAfter = mvc.perform(get("/api/v1/plans/{key}/power-snapshot", plan))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(snapshotAfter).get(0).get("limitVersion").asInt()).isEqualTo(1);
        assertThat(read(snapshotAfter).get(0).get("limitKw").asInt()).isEqualTo(100);

        String section2 = key("SEC");
        bindSection(section2, substation, 2);
        String newer = key("SCH");
        createPlan(newer, occ("G2", section2, iso(8, 0), iso(9, 0), 90));
        publishPlan(newer, key("REQ"));
        MvcResult snapshot2 = mvc.perform(get("/api/v1/plans/{key}/power-snapshot", newer))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(snapshot2).get(0).get("limitVersion").asInt()).isEqualTo(2);
        assertThat(read(snapshot2).get(0).get("limitKw").asInt()).isEqualTo(200);
    }

    @Test
    void reschedulePowerCheckRemovesOldPlan() throws Exception {
        String substation = key("SUB");
        createSubstation(substation, 100);
        String sectionA = key("SEC");
        String sectionB = key("SEC");
        bindSection(sectionA, substation, 1);
        bindSection(sectionB, substation, 1);

        // 旧计划 60 千瓦已发布；新草稿 60 千瓦同时段，移除旧计划后恰好不超限
        String oldPlan = key("SCH");
        createPlan(oldPlan, occ("G1", sectionA, iso(10, 0), iso(11, 0), 60));
        publishPlan(oldPlan, key("REQ"));
        String newPlan = key("SCH");
        createPlan(newPlan, occ("G2", sectionB, iso(10, 0), iso(11, 0), 60));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newPlan, 1, 1)))
                .andExpect(status().isOk());

        // 第三方已发布 60 千瓦在场（与旧单 40 千瓦合计 100 恰好容纳）；
        // 新草稿 60 千瓦替换旧单后 60+60=120 超限 → 改签 422 且整体回滚
        String oldPlan2 = key("SCH");
        createPlan(oldPlan2, occ("G3", sectionA, iso(12, 0), iso(13, 0), 40));
        publishPlan(oldPlan2, key("REQ"));
        String third = key("SCH");
        createPlan(third, occ("G4", sectionB, iso(12, 0), iso(13, 0), 60));
        publishPlan(third, key("REQ"));
        String newPlan2 = key("SCH");
        createPlan(newPlan2, occ("G5", sectionA, iso(12, 30), iso(13, 0), 60));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldPlan2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newPlan2, 1, 1)))
                .andExpect(status().isUnprocessableEntity());
        MvcResult oldAfter = mvc.perform(get("/api/v1/plans/{key}", oldPlan2))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(oldAfter).get("status").asText()).isEqualTo("PUBLISHED");
        MvcResult newAfter = mvc.perform(get("/api/v1/plans/{key}", newPlan2))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(newAfter).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void publishPowerFailureKeepsDraftAndKeyReusable() throws Exception {
        String substation = key("SUB");
        createSubstation(substation, 100);
        String section = key("SEC");
        bindSection(section, substation, 1);
        String plan = key("SCH");
        createPlan(plan, occ("G1", section, iso(8, 0), iso(9, 0), 150));

        // 超限 422 不写占用、不占幂等键；修正后同键重发成功
        String publishKey = key("REQ");
        mvc.perform(post("/api/v1/plans/{key}/publish", plan)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/plans/{key}/occupancies", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1,
                                occ("G1", section, iso(8, 0), iso(9, 0), 80))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{key}/publish", plan)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isOk());
    }

    @Test
    void negativePowerRejected() throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"),
                                occ("G1", key("SEC"), iso(8, 0), iso(9, 0), -1))))
                .andExpect(status().isBadRequest());
    }

    // ---------- 辅助 ----------

    private void createSubstation(String substationKey, int limitKw) throws Exception {
        mvc.perform(post("/api/v1/substations").contentType(MediaType.APPLICATION_JSON)
                        .content(substationBody(key("RID"), "OP", substationKey, limitKw)))
                .andExpect(status().isCreated());
    }

    private void bindSection(String sectionId, String substationKey, int expectedVersion)
            throws Exception {
        mvc.perform(put("/api/v1/sections/{sectionId}/binding", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody(key("RID"), "OP", substationKey, expectedVersion)))
                .andExpect(status().isOk());
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

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String occ(String train, String section, String startIso, String endIso,
                              int powerKw) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso
                + "\",\"powerKw\":" + powerKw + "}";
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

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }

    private static String substationBody(String requestId, String operator, String substationKey,
                                         int limitKw) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"substationKey\":\"" + substationKey + "\",\"powerLimitKw\":" + limitKw + "}";
    }

    private static String updateLimitBody(String requestId, String operator, int limitKw,
                                          int expectedVersion) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"powerLimitKw\":" + limitKw + ",\"expectedVersion\":" + expectedVersion + "}";
    }

    private static String bindBody(String requestId, String operator, String substationKey,
                                   int expectedVersion) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"substationKey\":\"" + substationKey + "\",\"expectedVersion\":"
                + expectedVersion + "}";
    }
}
