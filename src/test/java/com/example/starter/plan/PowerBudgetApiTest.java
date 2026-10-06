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
 * 变电所供电预算端到端测试（H2 内存库）：主流程、超限 422、调参/改绑 409、
 * 绑定前置取消、改签功率集合、取消释放、全局 requestId 幂等与裁决快照。
 * 各用例使用唯一业务键，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PowerBudgetApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 题干合成场景 ----------

    @Test
    void synthesisTwoSections60KwCap100() throws Exception {
        String sub = key("SUB");
        String sectionA = key("SEC-A");
        String sectionB = key("SEC-B");
        createSubstation(sub, 100);
        bindSection(sectionA, sub, null);
        bindSection(sectionB, sub, null);

        // 第一单：SEC-A 09:10-10:10 取 60 千瓦，发布成功
        String first = key("SCH");
        createPlan(first, occ("G1", sectionA, iso(9, 10), iso(10, 10), 60));
        publishPlan(first, key("REQ"));

        // 第二单：SEC-B 09:30-10:30 取 60 千瓦，同时段累计 120 > 100 → 422，整单不发布
        String second = key("SCH");
        createPlan(second, occ("G2", sectionB, iso(9, 30), iso(10, 30), 60));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("POWER_BUDGET_EXCEEDED");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("substationKey").asText()).isEqualTo(sub);
        assertThat(detail.get("startUtc").asText()).isEqualTo(iso(9, 30));
        assertThat(detail.get("endUtc").asText()).isEqualTo(iso(10, 10));
        assertThat(detail.get("actualPowerKw").asLong()).isEqualTo(120);
        assertThat(detail.get("capKw").asInt()).isEqualTo(100);
        // 整次发布不写任何占用：计划保持草稿
        assertThat(getPlanStatus(second)).isEqualTo("DRAFT");

        // 前单 10:10 结束、后单 10:10 开始（左闭右开相邻）→ 允许
        String adjacent = key("SCH");
        createPlan(adjacent, occ("G3", sectionB, iso(10, 10), iso(11, 0), 60));
        publishPlan(adjacent, key("REQ"));
        assertThat(getPlanStatus(adjacent)).isEqualTo("PUBLISHED");

        // 裁决快照：首次 PUBLISH 冻结上限版本 1、上限 100、切片 60 千瓦
        JsonNode audits = read(mvc.perform(get("/api/v1/substations/{key}/power-audits", sub))
                .andExpect(status().isOk()).andReturn());
        assertThat(audits.size()).isGreaterThanOrEqualTo(3);
        JsonNode firstPublish = null;
        for (JsonNode audit : audits) {
            if ("PUBLISH".equals(audit.get("opType").asText())) {
                firstPublish = audit;
                break;
            }
        }
        assertThat(firstPublish).isNotNull();
        assertThat(firstPublish.get("capVersion").asInt()).isEqualTo(1);
        assertThat(firstPublish.get("capKw").asInt()).isEqualTo(100);
        assertThat(firstPublish.get("slices").get(0).get("totalPowerKw").asLong()).isEqualTo(60);
    }

    // ---------- 未绑定区段保持原模式 ----------

    @Test
    void unboundSectionKeepsOriginalSlotMode() throws Exception {
        String section = key("SEC");
        // 未绑定区段声明超大功率仍按原时隙模式发布成功
        String first = key("SCH");
        createPlan(first, occ("G1", section, iso(8, 0), iso(9, 0), 5000));
        publishPlan(first, key("REQ"));

        // 同区段重叠仍按原有时隙规则 422
        String overlapping = key("SCH");
        createPlan(overlapping, occ("G2", section, iso(8, 30), iso(9, 30), 1));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", overlapping)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("SLOT_CONFLICT");

        // 未绑定区段查询绑定返回 404
        mvc.perform(get("/api/v1/sections/{id}/binding", section))
                .andExpect(status().isNotFound());
    }

    // ---------- 绑定前须取消旧占用 ----------

    @Test
    void bindRequiresCancelOfPublishedOccupancies() throws Exception {
        String sub = key("SUB");
        String section = key("SEC");
        createSubstation(sub, 100);
        String plan = key("SCH");
        createPlan(plan, occ("G1", section, iso(8, 0), iso(9, 0), 10));
        publishPlan(plan, key("REQ"));

        // 区段存在已发布占用 → 绑定 409
        MvcResult rejected = mvc.perform(put("/api/v1/sections/{id}/binding", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody(key("RID"), null, sub)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(rejected).get("code").asText())
                .isEqualTo("SECTION_HAS_ACTIVE_OCCUPANCIES");

        // 取消旧占用后绑定成功，版本 1
        cancelPlan(plan, key("REQ"));
        MvcResult bound = mvc.perform(put("/api/v1/sections/{id}/binding", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody(key("RID"), null, sub)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(bound);
        assertThat(body.get("substationKey").asText()).isEqualTo(sub);
        assertThat(body.get("version").asInt()).isEqualTo(1);
    }

    // ---------- 调低上限 409 ----------

    @Test
    void lowerCapCausingExceedRejected() throws Exception {
        String sub = key("SUB");
        String sectionA = key("SEC-A");
        String sectionB = key("SEC-B");
        createSubstation(sub, 200);
        bindSection(sectionA, sub, null);
        bindSection(sectionB, sub, null);
        String planA = key("SCH");
        createPlan(planA, occ("G1", sectionA, iso(8, 0), iso(9, 0), 60));
        publishPlan(planA, key("REQ"));
        String planB = key("SCH");
        createPlan(planB, occ("G2", sectionB, iso(8, 0), iso(9, 0), 60));
        publishPlan(planB, key("REQ"));

        // 版本不匹配 → 409
        mvc.perform(put("/api/v1/substations/{key}", sub)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateSubBody(key("RID"), 9, 300)))
                .andExpect(status().isConflict());

        // 调低到 100：现存 08:00-09:00 累计 120 超限 → 409，携带时间片与实际功率
        MvcResult rejected = mvc.perform(put("/api/v1/substations/{key}", sub)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateSubBody(key("RID"), 1, 100)))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("POWER_CAP_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("actualPowerKw").asLong()).isEqualTo(120);
        assertThat(detail.get("capKw").asInt()).isEqualTo(100);
        assertThat(detail.get("startUtc").asText()).isEqualTo(iso(8, 0));

        // 上限未变，仍为 200 / 版本 1
        JsonNode current = read(mvc.perform(get("/api/v1/substations/{key}", sub))
                .andExpect(status().isOk()).andReturn());
        assertThat(current.get("powerCapKw").asInt()).isEqualTo(200);
        assertThat(current.get("version").asInt()).isEqualTo(1);

        // 调到 120 正好容纳 → 成功，版本 2
        MvcResult updated = mvc.perform(put("/api/v1/substations/{key}", sub)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateSubBody(key("RID"), 1, 120)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(updated).get("version").asInt()).isEqualTo(2);
    }

    // ---------- 改绑定 409 ----------

    @Test
    void rebindCausingExceedRejected() throws Exception {
        String subA = key("SUB-A");
        String subB = key("SUB-B");
        String subC = key("SUB-C");
        String sectionE = key("SEC-E");
        String sectionF = key("SEC-F");
        createSubstation(subA, 200);
        createSubstation(subB, 100);
        createSubstation(subC, 150);
        bindSection(sectionE, subA, null);
        bindSection(sectionF, subB, null);
        String planE = key("SCH");
        createPlan(planE, occ("G1", sectionE, iso(8, 0), iso(9, 0), 80));
        publishPlan(planE, key("REQ"));
        String planF = key("SCH");
        createPlan(planF, occ("G2", sectionF, iso(8, 0), iso(9, 0), 60));
        publishPlan(planF, key("REQ"));

        // 改绑 SEC-E 到 SUB-B：80 + 60 = 140 > 100 → 409，绑定不变
        MvcResult rejected = mvc.perform(put("/api/v1/sections/{id}/binding", sectionE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody(key("RID"), 1, subB)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(rejected).get("code").asText()).isEqualTo("POWER_CAP_CONFLICT");
        JsonNode binding = read(mvc.perform(get("/api/v1/sections/{id}/binding", sectionE))
                .andExpect(status().isOk()).andReturn());
        assertThat(binding.get("substationKey").asText()).isEqualTo(subA);
        assertThat(binding.get("version").asInt()).isEqualTo(1);

        // 绑定版本不匹配 → 409
        mvc.perform(put("/api/v1/sections/{id}/binding", sectionE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody(key("RID"), 7, subC)))
                .andExpect(status().isConflict());

        // 改绑到容量足够的 SUB-C（80 <= 150）→ 成功，绑定版本 2
        MvcResult rebound = mvc.perform(put("/api/v1/sections/{id}/binding", sectionE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody(key("RID"), 1, subC)))
                .andExpect(status().isOk()).andReturn();
        JsonNode reboundBody = read(rebound);
        assertThat(reboundBody.get("substationKey").asText()).isEqualTo(subC);
        assertThat(reboundBody.get("version").asInt()).isEqualTo(2);
    }

    // ---------- 改签功率集合：移除旧计划、加入完整新计划 ----------

    @Test
    void reschedulePowerCheckExcludesOldPlan() throws Exception {
        String sub = key("SUB");
        String sectionG = key("SEC-G");
        String sectionH = key("SEC-H");
        createSubstation(sub, 150);
        bindSection(sectionG, sub, null);
        bindSection(sectionH, sub, null);
        String other = key("SCH");
        createPlan(other, occ("G9", sectionH, iso(8, 0), iso(9, 0), 40));
        publishPlan(other, key("REQ"));
        String oldPlan = key("SCH");
        createPlan(oldPlan, occ("G1", sectionG, iso(8, 0), iso(9, 0), 80));
        publishPlan(oldPlan, key("REQ"));

        // 新计划 100 千瓦：功率集合 = 150 上限下 (40 + 100) = 140，自身旧占用 80 不重复计 → 允许
        String newPlan = key("SCH");
        createPlan(newPlan, occ("G2", sectionG, iso(8, 0), iso(9, 0), 100));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newPlan, 1, 1)))
                .andExpect(status().isOk());
        assertThat(getPlanStatus(oldPlan)).isEqualTo("CANCELLED");
        assertThat(getPlanStatus(newPlan)).isEqualTo("PUBLISHED");

        // 反向：新计划 120 千瓦，(40 + 120) = 160 > 150 → 422，旧计划仍发布、新计划仍草稿
        String oldPlan2 = key("SCH");
        createPlan(oldPlan2, occ("G3", sectionG, iso(10, 0), iso(11, 0), 80));
        publishPlan(oldPlan2, key("REQ"));
        String other2 = key("SCH");
        createPlan(other2, occ("G8", sectionH, iso(10, 0), iso(11, 0), 40));
        publishPlan(other2, key("REQ"));
        String newPlan2 = key("SCH");
        createPlan(newPlan2, occ("G4", sectionG, iso(10, 0), iso(11, 0), 120));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldPlan2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newPlan2, 1, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("POWER_BUDGET_EXCEEDED");
        assertThat(error.get("details").get(0).get("actualPowerKw").asLong()).isEqualTo(160);
        assertThat(getPlanStatus(oldPlan2)).isEqualTo("PUBLISHED");
        assertThat(getPlanStatus(newPlan2)).isEqualTo("DRAFT");
    }

    // ---------- 取消释放预算 ----------

    @Test
    void cancelReleasesPowerBudget() throws Exception {
        String sub = key("SUB");
        String sectionI = key("SEC-I");
        String sectionJ = key("SEC-J");
        createSubstation(sub, 100);
        bindSection(sectionI, sub, null);
        bindSection(sectionJ, sub, null);
        String first = key("SCH");
        createPlan(first, occ("G1", sectionI, iso(8, 0), iso(9, 0), 60));
        publishPlan(first, key("REQ"));

        String second = key("SCH");
        createPlan(second, occ("G2", sectionJ, iso(8, 0), iso(9, 0), 60));
        mvc.perform(post("/api/v1/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity());

        // 取消前单后预算释放，第二单发布成功
        cancelPlan(first, key("REQ"));
        publishPlan(second, key("REQ"));
        assertThat(getPlanStatus(second)).isEqualTo("PUBLISHED");
    }

    // ---------- 同一列车不同占用分别计 ----------

    @Test
    void sameTrainOccupanciesCountedSeparatelyAcrossPlans() throws Exception {
        String sub = key("SUB");
        String sectionK = key("SEC-K");
        String sectionL = key("SEC-L");
        createSubstation(sub, 100);
        bindSection(sectionK, sub, null);
        bindSection(sectionL, sub, null);
        // 同一列车 G1 在不同计划、不同区段同时段各取 60 千瓦，分别累计 → 120 > 100
        String first = key("SCH");
        createPlan(first, occ("G1", sectionK, iso(8, 0), iso(9, 0), 60));
        publishPlan(first, key("REQ"));
        String second = key("SCH");
        createPlan(second, occ("G1", sectionL, iso(8, 0), iso(9, 0), 60));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", second)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(rejected).get("details").get(0).get("actualPowerKw").asLong())
                .isEqualTo(120);
    }

    // ---------- 全局 requestId 幂等 ----------

    @Test
    void substationIdempotentReplayAndParamConflict() throws Exception {
        String sub = key("SUB");
        String requestId = key("RID");
        String body = createSubBody(requestId, sub, 100);
        MvcResult first = mvc.perform(post("/api/v1/substations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同键为重放：返回首次完整响应
        MvcResult replay = mvc.perform(post("/api/v1/substations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同 requestId 改参 → 409
        mvc.perform(post("/api/v1/substations").contentType(MediaType.APPLICATION_JSON)
                        .content(createSubBody(requestId, sub, 200)))
                .andExpect(status().isConflict());
        // 同 requestId 跨操作 → 409（结构化比较含操作类型）
        mvc.perform(put("/api/v1/substations/{key}", sub)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateSubBody(requestId, 1, 200)))
                .andExpect(status().isConflict());

        // 调参重放：版本只加一次
        String capRequestId = key("RID");
        String capBody = updateSubBody(capRequestId, 1, 150);
        MvcResult capFirst = mvc.perform(put("/api/v1/substations/{key}", sub)
                        .contentType(MediaType.APPLICATION_JSON).content(capBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult capReplay = mvc.perform(put("/api/v1/substations/{key}", sub)
                        .contentType(MediaType.APPLICATION_JSON).content(capBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(capReplay)).isEqualTo(read(capFirst));
        JsonNode current = read(mvc.perform(get("/api/v1/substations/{key}", sub))
                .andExpect(status().isOk()).andReturn());
        assertThat(current.get("version").asInt()).isEqualTo(2);

        // 绑定重放：同键同参返回首次结果，同键改参 409
        String section = key("SEC");
        String bindRequestId = key("RID");
        String bindBody = bindBody(bindRequestId, null, sub);
        MvcResult bindFirst = mvc.perform(put("/api/v1/sections/{id}/binding", section)
                        .contentType(MediaType.APPLICATION_JSON).content(bindBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult bindReplay = mvc.perform(put("/api/v1/sections/{id}/binding", section)
                        .contentType(MediaType.APPLICATION_JSON).content(bindBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(bindReplay)).isEqualTo(read(bindFirst));
        String otherSub = key("SUB");
        createSubstation(otherSub, 100);
        mvc.perform(put("/api/v1/sections/{id}/binding", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody(bindRequestId, 1, otherSub)))
                .andExpect(status().isConflict());
    }

    @Test
    void failureDoesNotConsumeRequestId() throws Exception {
        // 404 失败不占键：同一 requestId 随后可用于成功请求
        String requestId = key("RID");
        mvc.perform(put("/api/v1/substations/{key}", key("SUB-MISSING"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateSubBody(requestId, 1, 100)))
                .andExpect(status().isNotFound());
        String sub = key("SUB");
        mvc.perform(post("/api/v1/substations").contentType(MediaType.APPLICATION_JSON)
                        .content(createSubBody(requestId, sub, 100)))
                .andExpect(status().isCreated());

        // 409 失败同样不占键
        String bindRequestId = key("RID");
        mvc.perform(put("/api/v1/sections/{id}/binding", key("SEC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody(bindRequestId, null, key("SUB-MISSING"))))
                .andExpect(status().isNotFound());
        String section = key("SEC");
        mvc.perform(put("/api/v1/sections/{id}/binding", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody(bindRequestId, null, sub)))
                .andExpect(status().isOk());
    }

    // ---------- 裁决快照历史不漂移 ----------

    @Test
    void auditHistoryFrozenAcrossCapChangeAndCancel() throws Exception {
        String sub = key("SUB");
        String section = key("SEC");
        createSubstation(sub, 200);
        bindSection(section, sub, null);
        String plan = key("SCH");
        createPlan(plan, occ("G1", section, iso(8, 0), iso(9, 0), 60));
        publishPlan(plan, key("REQ"));

        JsonNode before = read(mvc.perform(get("/api/v1/substations/{key}/power-audits", sub))
                .andExpect(status().isOk()).andReturn());
        assertThat(before.size()).isEqualTo(2);
        assertThat(before.get(0).get("opType").asText()).isEqualTo("BIND");
        JsonNode publishAudit = before.get(1);
        assertThat(publishAudit.get("opType").asText()).isEqualTo("PUBLISH");
        assertThat(publishAudit.get("capVersion").asInt()).isEqualTo(1);
        assertThat(publishAudit.get("capKw").asInt()).isEqualTo(200);
        assertThat(publishAudit.get("slices").get(0).get("totalPowerKw").asLong()).isEqualTo(60);

        // 调参后历史快照不漂移，新快照冻结新版本
        mvc.perform(put("/api/v1/substations/{key}", sub)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateSubBody(key("RID"), 1, 300)))
                .andExpect(status().isOk());
        cancelPlan(plan, key("REQ"));
        JsonNode after = read(mvc.perform(get("/api/v1/substations/{key}/power-audits", sub))
                .andExpect(status().isOk()).andReturn());
        assertThat(after.size()).isEqualTo(4);
        assertThat(after.get(1)).isEqualTo(publishAudit);
        assertThat(after.get(2).get("opType").asText()).isEqualTo("CAP_CHANGE");
        assertThat(after.get(2).get("capVersion").asInt()).isEqualTo(2);
        assertThat(after.get(2).get("capKw").asInt()).isEqualTo(300);
        assertThat(after.get(3).get("opType").asText()).isEqualTo("CANCEL");
        assertThat(after.get(3).get("slices")).isEmpty();
    }

    // ---------- 零功率与参数校验 ----------

    @Test
    void zeroAndDefaultPowerBypassBudget() throws Exception {
        String sub = key("SUB");
        String sectionA = key("SEC-A");
        String sectionB = key("SEC-B");
        createSubstation(sub, 1);
        bindSection(sectionA, sub, null);
        bindSection(sectionB, sub, null);
        // 显式 0 千瓦与缺省功率（缺省按 0）均不占用预算
        String first = key("SCH");
        createPlan(first, occ("G1", sectionA, iso(8, 0), iso(9, 0), 0));
        publishPlan(first, key("REQ"));
        String second = key("SCH");
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + second + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + "{\"trainNo\":\"G2\",\"sectionId\":\"" + sectionB
                                + "\",\"startUtc\":\"" + iso(8, 0) + "\",\"endUtc\":\""
                                + iso(9, 0) + "\"}" + "]}"))
                .andExpect(status().isCreated());
        publishPlan(second, key("REQ"));
    }

    @Test
    void powerParamsValidated() throws Exception {
        // 负功率 → 400
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"),
                                occ("G1", key("SEC"), iso(8, 0), iso(9, 0), -1))))
                .andExpect(status().isBadRequest());
        // 缺操作者 / 缺 requestId / 非正上限 → 400
        mvc.perform(post("/api/v1/substations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key("RID") + "\",\"substationKey\":\""
                                + key("SUB") + "\",\"powerCapKw\":100}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/substations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"tester\",\"substationKey\":\"" + key("SUB")
                                + "\",\"powerCapKw\":100}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/substations").contentType(MediaType.APPLICATION_JSON)
                        .content(createSubBody(key("RID"), key("SUB"), 0)))
                .andExpect(status().isBadRequest());
        // 变电所不存在 → 404
        mvc.perform(get("/api/v1/substations/{key}", key("SUB")))
                .andExpect(status().isNotFound());
    }

    // __MORE_TESTS__

    // ---------- 辅助 ----------

    private void createSubstation(String substationKey, int capKw) throws Exception {
        mvc.perform(post("/api/v1/substations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createSubBody(key("RID"), substationKey, capKw)))
                .andExpect(status().isCreated());
    }

    private void bindSection(String sectionId, String substationKey, Integer expectedVersion)
            throws Exception {
        mvc.perform(put("/api/v1/sections/{id}/binding", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody(key("RID"), expectedVersion, substationKey)))
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

    private String getPlanStatus(String scheduleKey) throws Exception {
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

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
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

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }

    private static String createSubBody(String requestId, String substationKey, int capKw) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"tester\""
                + ",\"substationKey\":\"" + substationKey + "\",\"powerCapKw\":" + capKw + "}";
    }

    private static String updateSubBody(String requestId, int expectedVersion, int capKw) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"tester\""
                + ",\"expectedVersion\":" + expectedVersion + ",\"powerCapKw\":" + capKw + "}";
    }

    private static String bindBody(String requestId, Integer expectedVersion,
                                   String substationKey) {
        String version = expectedVersion == null ? "" : "\"expectedVersion\":" + expectedVersion + ",";
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"tester\"," + version
                + "\"substationKey\":\"" + substationKey + "\"}";
    }
}
