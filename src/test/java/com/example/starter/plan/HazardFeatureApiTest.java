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
 * 铁路危险品隔离端到端测试（真实 H2 内存库）：
 * 编组登记、互斥类别间隔、限制区段授权、422 冲突对（实际/要求/差额）、
 * 发布快照固化、配置版本化、诊断只读、幂等与批量改签原子性。
 *
 * <p>危险品类别与区段使用随机代码，避免全局规则集版本在各用例间相互影响；
 * 规则集版本通过 GET 先查询当前值再做乐观替换。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HazardFeatureApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 编组登记 ----------

    @Test
    void consistRegistrationReplacesAndKeepsNullSemantics() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, key("SEC"), "G1");
        String body = consistBody(key("REQ"), 1,
                car(2, hazard()), car(1, null), car(3, hazard()));

        MvcResult result = mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan = read(result);
        assertThat(plan.get("version").asInt()).isEqualTo(2);
        JsonNode consist = plan.get("consist");
        // 按位置升序，null 普通车厢语义稳定输出为 JSON null
        assertThat(consist).hasSize(3);
        assertThat(consist.get(0).get("position").asInt()).isEqualTo(1);
        assertThat(consist.get(0).get("hazardClass").isNull()).isTrue();
        assertThat(consist.get(1).get("hazardClass").asText()).startsWith("HZ");

        // 明细查询返回相同编组
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("consist")).isEqualTo(consist);

        // 已发布后编组不可改（409）
        publishPlan(scheduleKey, key("REQ"), "dispatcher-a");
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), 2, car(1, hazard()))))
                .andExpect(status().isConflict());
    }

    @Test
    void duplicateConsistPositionsReturn422WithConflictPairsAndNoVersionBump() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, key("SEC"), "G1");
        String h = hazard();

        MvcResult result = mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), 1, car(5, h), car(5, null), car(5, h))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("HAZARD_CONFLICT");
        JsonNode details = error.get("details");
        assertThat(details).hasSize(3);
        assertThat(details.get(0).get("type").asText()).isEqualTo("CONSIST_POSITION_DUPLICATE");
        assertThat(details.get(0).get("position").asInt()).isEqualTo(5);
        assertThat(details.get(0).get("firstIndex").asInt()).isEqualTo(0);
        assertThat(details.get(0).get("otherIndex").asInt()).isEqualTo(1);

        // 未产生变更：版本仍为 1、编组为空
        JsonNode plan = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(plan.get("version").asInt()).isEqualTo(1);
        assertThat(plan.get("consist")).isEmpty();
    }

    // ---------- 互斥类别间隔 ----------

    @Test
    void publishRejectsInsufficientSeparationAndAllowsAfterFix() throws Exception {
        String a = hazard();
        String b = hazard();
        installRules(rule(a, b, 5), null);

        String section = key("SEC");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, section, "G1");
        putConsist(scheduleKey, 1, car(1, a), car(4, b));
        String publishKey = key("REQ");

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(publishKey, "dispatcher-a")))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("HAZARD_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("HAZARD_SEPARATION");
        // 类别对按字母序规范化，positionA/B 与较小/较大类别对应
        String classA = a.compareTo(b) <= 0 ? a : b;
        String classB = a.compareTo(b) <= 0 ? b : a;
        assertThat(detail.get("classA").asText()).isEqualTo(classA);
        assertThat(detail.get("classB").asText()).isEqualTo(classB);
        int expectedPosA = classA.equals(a) ? 1 : 4;
        int expectedPosB = classB.equals(b) ? 4 : 1;
        assertThat(detail.get("positionA").asInt()).isEqualTo(expectedPosA);
        assertThat(detail.get("positionB").asInt()).isEqualTo(expectedPosB);
        assertThat(detail.get("actualCars").asInt()).isEqualTo(3);
        assertThat(detail.get("requiredCars").asInt()).isEqualTo(5);
        assertThat(detail.get("deficitCars").asInt()).isEqualTo(2);

        // 计划保持草稿，失败不占键：修正间隔后同键发布成功
        assertThat(getPlan(scheduleKey).get("status").asText()).isEqualTo("DRAFT");
        putConsist(scheduleKey, 2, car(1, a), car(6, b));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(publishKey, "dispatcher-a")))
                .andExpect(status().isOk());
    }

    // ---------- 限制区段授权 ----------

    @Test
    void publishRejectsUnauthorizedClassOnRestrictedSection() throws Exception {
        String allowed = hazard();
        String forbidden = hazard();
        String restricted = key("SEC");
        String open = key("SEC");
        installSectionRestriction(restricted, 0, allowed);

        // 路径含限制区段与未配置区段：未配置区段不限制，限制区段拒绝未授权类别
        String scheduleKey = key("SCH");
        createPlanWithOccupancies(scheduleKey,
                occJson("G1", restricted, iso(8), iso(9)),
                occJson("G1", open, iso(9), iso(10)));
        putConsist(scheduleKey, 1, car(1, allowed), car(2, forbidden));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), "dispatcher-a")))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode details = read(result).get("details");
        assertThat(details).hasSize(1);
        JsonNode detail = details.get(0);
        assertThat(detail.get("type").asText()).isEqualTo("HAZARD_UNAUTHORIZED");
        assertThat(detail.get("sectionId").asText()).isEqualTo(restricted);
        assertThat(detail.get("position").asInt()).isEqualTo(2);
        assertThat(detail.get("hazardClass").asText()).isEqualTo(forbidden);
        assertThat(detail.get("allowedClasses").get(0).asText()).isEqualTo(allowed);

        // 全部类别在开放清单内后发布成功
        installSectionRestriction(restricted, 1, allowed, forbidden);
        publishPlan(scheduleKey, key("REQ"), "dispatcher-a");
    }

    @Test
    void emptyAllowedListRejectsAllHazardClassesButNormalCarsPass() throws Exception {
        String section = key("SEC");
        installSectionRestriction(section, 0);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, section, "G1");
        putConsist(scheduleKey, 1, car(1, null), car(2, null));
        publishPlan(scheduleKey, key("REQ"), "dispatcher-a");
    }

    // ---------- 发布快照固化 ----------

    @Test
    void snapshotFreezesConsistRulesAndSectionVersionsAfterConfigChangeAndCancel() throws Exception {
        String a = hazard();
        String b = hazard();
        int ruleVersion = installRules(rule(a, b, 3), null);
        String section = key("SEC");
        int sectionVersion = installSectionRestriction(section, 0, a, b);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, section, "G1");
        putConsist(scheduleKey, 1, car(1, a), car(4, b));
        publishPlan(scheduleKey, key("REQ"), "dispatcher-a");

        MvcResult snapshotResult = mvc.perform(get("/api/v1/plans/{key}/publish-snapshot",
                        scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode snapshot = read(snapshotResult);
        assertThat(snapshot.get("result").asText()).isEqualTo("PASS");
        assertThat(snapshot.get("ruleVersion").asInt()).isEqualTo(ruleVersion);
        assertThat(snapshot.get("operator").asText()).isEqualTo("dispatcher-a");
        assertThat(snapshot.get("cars")).hasSize(2);
        assertThat(snapshot.get("cars").get(1).get("hazardClass").asText()).isEqualTo(b);
        assertThat(snapshot.get("pathSections")).hasSize(1);
        assertThat(snapshot.get("pathSections").get(0).asText()).isEqualTo(section);
        JsonNode restriction = snapshot.get("sectionRestrictions").get(0);
        assertThat(restriction.get("sectionId").asText()).isEqualTo(section);
        assertThat(restriction.get("version").asInt()).isEqualTo(sectionVersion);
        String frozenHash = snapshot.get("rulesHash").asText();
        String frozenCreatedAt = snapshot.get("createdAt").asText();
        String frozenBody = snapshotResult.getResponse().getContentAsString();

        // 配置整体替换、计划取消后，历史快照原样返回（字段、排序、时间精度、null 语义不变）
        installRules(rule(a, b, 6), ruleVersion);
        installSectionRestriction(section, sectionVersion, a);
        cancelPlan(scheduleKey, key("REQ"));
        MvcResult againResult = mvc.perform(get("/api/v1/plans/{key}/publish-snapshot",
                        scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode again = read(againResult);
        assertThat(againResult.getResponse().getContentAsString()).isEqualTo(frozenBody);
        assertThat(again.get("rulesHash").asText()).isEqualTo(frozenHash);
        assertThat(again.get("createdAt").asText()).isEqualTo(frozenCreatedAt);
        assertThat(again.get("sectionRestrictions").get(0).get("version").asInt())
                .isEqualTo(sectionVersion);
        assertThat(again.get("sectionRestrictions").get(0).get("allowedClasses")).hasSize(2);

        // 取消后编组历史仍原样可查
        JsonNode plan = getPlan(scheduleKey);
        assertThat(plan.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(plan.get("consist")).hasSize(2);
    }

    @Test
    void snapshotMissingForDraftAndPlanMissing404() throws Exception {
        String draft = key("SCH");
        createPlan(draft, key("SEC"), "G1");
        mvc.perform(get("/api/v1/plans/{key}/publish-snapshot", draft))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/plans/{key}/publish-snapshot", key("SCH")))
                .andExpect(status().isNotFound());
    }

    // ---------- 诊断只读 ----------

    @Test
    void diagnosticsReflectsCurrentConfigWithoutChangingState() throws Exception {
        String a = hazard();
        String b = hazard();
        installRules(rule(a, b, 5), null);
        String section = key("SEC");
        installSectionRestriction(section, 0, a);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, section, "G1");
        putConsist(scheduleKey, 1, car(1, a), car(3, b));

        MvcResult fail = mvc.perform(get("/api/v1/plans/{key}/diagnostics", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode failBody = read(fail);
        assertThat(failBody.get("result").asText()).isEqualTo("FAIL");
        assertThat(failBody.get("conflicts")).hasSize(2);
        assertThat(failBody.get("ruleVersion").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(failBody.get("sectionRestrictions").get(0).get("sectionId").asText())
                .isEqualTo(section);

        // 查询不修改状态：计划仍草稿、无快照
        assertThat(getPlan(scheduleKey).get("status").asText()).isEqualTo("DRAFT");
        mvc.perform(get("/api/v1/plans/{key}/publish-snapshot", scheduleKey))
                .andExpect(status().isConflict());

        // 放开清单并放宽规则后诊断转 PASS
        installSectionRestriction(section, 1, a, b);
        installRules(rule(a, b, 2), null);
        MvcResult pass = mvc.perform(get("/api/v1/plans/{key}/diagnostics", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(pass).get("result").asText()).isEqualTo("PASS");
        assertThat(read(pass).get("conflicts")).isEmpty();
    }

    // ---------- 配置版本化与幂等 ----------

    @Test
    void ruleConfigVersionConflictInvalidPayloadAndIdempotency() throws Exception {
        String a = hazard();
        String b = hazard();
        String c = hazard();
        int current = currentRuleVersion();
        String requestKey = key("REQ");
        String body = rulesBody(requestKey, current, "cfg-operator", rule(a, b, 4));

        mvc.perform(put("/api/v1/hazard-rules").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        // 同参重放返回首次完整响应
        MvcResult replay = mvc.perform(put("/api/v1/hazard-rules")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay).get("version").asInt()).isEqualTo(current + 1);
        // 同键异参 409
        mvc.perform(put("/api/v1/hazard-rules").contentType(MediaType.APPLICATION_JSON)
                        .content(rulesBody(requestKey, current + 1, "cfg-operator", rule(a, c, 4))))
                .andExpect(status().isConflict());
        // 期望版本过期 409
        mvc.perform(put("/api/v1/hazard-rules").contentType(MediaType.APPLICATION_JSON)
                        .content(rulesBody(key("REQ"), current, "cfg-operator", rule(a, c, 4))))
                .andExpect(status().isConflict());
        // 配置非法（同类别对要求不一致）422 且不落库，失败不占键
        String invalidKey = key("REQ");
        mvc.perform(put("/api/v1/hazard-rules").contentType(MediaType.APPLICATION_JSON)
                        .content(rulesBody(invalidKey, current + 1, "cfg-operator",
                                rule(a, c, 2), rule(c, a, 9))))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/hazard-rules").contentType(MediaType.APPLICATION_JSON)
                        .content(rulesBody(invalidKey, current + 1, "cfg-operator", rule(a, c, 2))))
                .andExpect(status().isOk());

        // 区段清单类别重复 422
        String section = key("SEC");
        mvc.perform(put("/api/v1/sections/{sectionId}/restriction", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("REQ"), 0, "cfg-operator", a, a)))
                .andExpect(status().isUnprocessableEntity());
        MvcResult view = mvc.perform(get("/api/v1/sections/{sectionId}/restriction", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(view).get("version").asInt()).isZero();
    }

    // ---------- 单改签也执行危险品校验并写快照 ----------

    @Test
    void rescheduleValidatesHazardsAndFreezesSnapshot() throws Exception {
        String a = hazard();
        String b = hazard();
        installRules(rule(a, b, 4), null);
        String section = key("SEC");
        installSectionRestriction(section, 0, a, b);

        String oldKey = key("SCH");
        createPlan(oldKey, section, "G1");
        publishPlan(oldKey, key("REQ"), "dispatcher-a");

        String newKey = key("SCH");
        createPlan(newKey, section, "G2");
        putConsist(newKey, 1, car(1, a), car(2, b));

        // 间隔不足 → 422 回滚
        MvcResult blocked = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 2, "dispatcher-b")))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(blocked).get("details").get(0).get("type").asText())
                .isEqualTo("HAZARD_SEPARATION");
        assertThat(getPlan(oldKey).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(newKey).get("status").asText()).isEqualTo("DRAFT");

        // 修正后改签成功，新计划有发布快照、旧计划无快照变化（旧计划从未危险品发布也已有快照）
        putConsist(newKey, 2, car(1, a), car(5, b));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 3, "dispatcher-b")))
                .andExpect(status().isOk());
        JsonNode snapshot = read(mvc.perform(get("/api/v1/plans/{key}/publish-snapshot", newKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(snapshot.get("result").asText()).isEqualTo("PASS");
        assertThat(snapshot.get("cars")).hasSize(2);
        assertThat(snapshot.get("operator").asText()).isEqualTo("dispatcher-b");
    }

    // ---------- 批量改签原子性 ----------

    @Test
    void batchRescheduleSucceedsOnlyWhenEveryItemLegal() throws Exception {
        String a = hazard();
        installRules(null, null);
        String s1 = key("SEC");
        String s2 = key("SEC");
        installSectionRestriction(s1, 0, a);

        String old1 = key("SCH");
        String old2 = key("SCH");
        createPlan(old1, s1, "G1");
        createPlan(old2, s2, "G2");
        publishPlan(old1, key("REQ"), "dispatcher-a");
        publishPlan(old2, key("REQ"), "dispatcher-a");

        String new1 = key("SCH");
        String new2 = key("SCH");
        // 两个新计划时隙互不重叠（08-09 / 10-11），仅 new2 含限制区段未授权类别
        createPlanWithOccupancies(new1, occJson("G3", s1, iso(8), iso(9)));
        putConsist(new1, 1, car(1, a));
        createPlanWithOccupancies(new2, occJson("G4", s1, iso(10), iso(11)));
        putConsist(new2, 1, car(1, hazard()));

        MvcResult result = mvc.perform(post("/api/v1/batch-reschedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), "dispatcher-b",
                                batchItem(old1, new1, 1, 2),
                                batchItem(old2, new2, 1, 2))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("HAZARD_CONFLICT");
        JsonNode details = error.get("details");
        assertThat(details.get(0).get("type").asText()).isEqualTo("HAZARD_UNAUTHORIZED");
        // 非法项带 itemIndex 与新旧计划键，便于定位冲突对
        assertThat(details.get(0).get("itemIndex").asInt()).isEqualTo(1);
        assertThat(details.get(0).get("oldScheduleKey").asText()).isEqualTo(old2);

        // 整次未生效：两个旧计划仍发布、两个新计划仍草稿、无关联
        assertThat(getPlan(old1).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(old2).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(new1).get("status").asText()).isEqualTo("DRAFT");
        assertThat(getPlan(new2).get("status").asText()).isEqualTo("DRAFT");
        mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", old1))
                .andExpect(status().isOk());
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", old1))
                .andReturn()).get("chain")).hasSize(1);
    }

    @Test
    void batchRescheduleRejectsInternalOverlapAndVersionMismatchWithoutPartialEffects()
            throws Exception {
        String section = key("SEC");
        String old1 = key("SCH");
        String old2 = key("SCH");
        createPlan(old1, section, "G1");
        createPlanWithOccupancies(old2, occJson("G2", section, iso(9), iso(10)));
        publishPlan(old1, key("REQ"), "dispatcher-a");
        publishPlan(old2, key("REQ"), "dispatcher-a");
        // 两个新草稿在同一区段同一时隙 08-09：旧计划均被排除，完整后态下两新计划互斥
        String new1 = key("SCH");
        String new2 = key("SCH");
        createPlan(new1, section, "G3");
        createPlan(new2, section, "G4");

        mvc.perform(post("/api/v1/batch-reschedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), "dispatcher-b",
                                batchItem(old1, new1, 1, 1),
                                batchItem(old2, new2, 1, 1))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(getPlan(new1).get("status").asText()).isEqualTo("DRAFT");
        assertThat(getPlan(new2).get("status").asText()).isEqualTo("DRAFT");
        assertThat(getPlan(old1).get("status").asText()).isEqualTo("PUBLISHED");

        // 版本不符 → 409 且无部分结果
        mvc.perform(post("/api/v1/batch-reschedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), "dispatcher-b",
                                batchItem(old1, new1, 9, 1),
                                batchItem(old2, new2, 1, 1))))
                .andExpect(status().isConflict());
        assertThat(getPlan(new1).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void batchRescheduleSuccessCommitsAtomicallyAndReplays() throws Exception {
        String s1 = key("SEC");
        String s2 = key("SEC");
        String old1 = key("SCH");
        String old2 = key("SCH");
        createPlan(old1, s1, "G1");
        createPlan(old2, s2, "G2");
        publishPlan(old1, key("REQ"), "dispatcher-a");
        publishPlan(old2, key("REQ"), "dispatcher-a");
        String new1 = key("SCH");
        String new2 = key("SCH");
        createPlan(new1, s1, "G3");
        createPlan(new2, s2, "G4");

        String requestKey = key("REQ");
        String body = batchBody(requestKey, "dispatcher-b",
                batchItem(old1, new1, 1, 1), batchItem(old2, new2, 1, 1));
        MvcResult first = mvc.perform(post("/api/v1/batch-reschedules")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode firstBody = read(first);
        assertThat(firstBody.get("count").asInt()).isEqualTo(2);
        assertThat(firstBody.get("results")).hasSize(2);
        // 同参重放（即使改签项顺序调换）返回首次完整响应快照
        String reordered = batchBody(requestKey, "dispatcher-b",
                batchItem(old2, new2, 1, 1), batchItem(old1, new1, 1, 1));
        MvcResult replay = mvc.perform(post("/api/v1/batch-reschedules")
                        .contentType(MediaType.APPLICATION_JSON).content(reordered))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(firstBody);

        assertThat(getPlan(old1).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(getPlan(new1).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(old2).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(getPlan(new2).get("status").asText()).isEqualTo("PUBLISHED");
    }

    // ---------- 辅助 ----------

    private JsonNode getPlan(String scheduleKey) throws Exception {
        return read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
    }

    private void createPlan(String scheduleKey, String section, String trainNo) throws Exception {
        createPlanWithOccupancies(scheduleKey, occJson(trainNo, section, iso(8), iso(9)));
    }

    private void createPlanWithOccupancies(String scheduleKey, String... occs) throws Exception {
        String body = "{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":[" + String.join(",", occs) + "]}";
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void putConsist(String scheduleKey, int expectedVersion, String... cars)
            throws Exception {
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), expectedVersion, cars)))
                .andExpect(status().isOk());
    }

    private void publishPlan(String scheduleKey, String requestKey, String operator)
            throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey, operator)))
                .andExpect(status().isOk());
    }

    private void cancelPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey, null)))
                .andExpect(status().isOk());
    }

    private int currentRuleVersion() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/hazard-rules"))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("version").asInt();
    }

    /**
     * 以当前规则集版本为 expectedVersion 整体替换规则集，返回新版本号；
     * expectedVersion 非 null 时强制指定。
     */
    private int installRules(String singleRule, Integer expectedVersion) throws Exception {
        String rules = singleRule == null ? "" : singleRule;
        int base = expectedVersion == null ? currentRuleVersion() : expectedVersion;
        String body = "{\"requestKey\":\"" + key("REQ") + "\",\"expectedVersion\":" + base
                + ",\"operator\":\"cfg-operator\",\"rules\":[" + rules + "]}";
        MvcResult result = mvc.perform(put("/api/v1/hazard-rules")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("version").asInt();
    }

    private int installSectionRestriction(String section, int expectedVersion, String... classes)
            throws Exception {
        StringBuilder classArray = new StringBuilder();
        for (int i = 0; i < classes.length; i++) {
            if (i > 0) {
                classArray.append(',');
            }
            classArray.append('"').append(classes[i]).append('"');
        }
        String body = "{\"requestKey\":\"" + key("REQ") + "\",\"expectedVersion\":"
                + expectedVersion + ",\"operator\":\"cfg-operator\",\"allowedClasses\":["
                + classArray + "]}";
        MvcResult result = mvc.perform(put("/api/v1/sections/{sectionId}/restriction", section)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("version").asInt();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 随机危险品类别代码（大写字母数字，符合 [A-Z0-9]{1,32}）。 */
    private static String hazard() {
        return "HZ" + UUID.randomUUID().toString().substring(0, 8).toUpperCase().replace("-", "");
    }

    private static String iso(int hour) {
        Instant instant = DAY.atTime(hour, 0).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String occJson(String train, String section, String start, String end) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + start + "\",\"endUtc\":\"" + end + "\"}";
    }

    private static String car(int position, String hazardClass) {
        String hazardJson = hazardClass == null ? "null" : "\"" + hazardClass + "\"";
        return "{\"position\":" + position + ",\"hazardClass\":" + hazardJson + "}";
    }

    private static String consistBody(String requestKey, int expectedVersion, String... cars) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"cars\":[" + String.join(",", cars) + "]}";
    }

    private static String actionBody(String requestKey, String operator) {
        String op = operator == null ? "null" : "\"" + operator + "\"";
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":" + op + "}";
    }

    private static String rule(String classA, String classB, int minSeparation) {
        return "{\"classA\":\"" + classA + "\",\"classB\":\"" + classB
                + "\",\"minSeparationCars\":" + minSeparation + "}";
    }

    private static String rulesBody(String requestKey, int expectedVersion, String operator,
                                    String... rules) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"operator\":\"" + operator + "\",\"rules\":[" + String.join(",", rules) + "]}";
    }

    private static String sectionBody(String requestKey, int expectedVersion, String operator,
                                      String... classes) {
        StringBuilder classArray = new StringBuilder();
        for (int i = 0; i < classes.length; i++) {
            if (i > 0) {
                classArray.append(',');
            }
            classArray.append('"').append(classes[i]).append('"');
        }
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"operator\":\"" + operator + "\",\"allowedClasses\":[" + classArray + "]}";
    }

    private static String rescheduleBody(String requestKey, String newKey, int oldVersion,
                                         int newVersion, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newKey
                + "\",\"expectedOldVersion\":" + oldVersion + ",\"expectedNewVersion\":"
                + newVersion + ",\"operator\":\"" + operator + "\"}";
    }

    private static String batchItem(String oldKey, String newKey, int oldVersion, int newVersion) {
        return "{\"oldScheduleKey\":\"" + oldKey + "\",\"newScheduleKey\":\"" + newKey
                + "\",\"expectedOldVersion\":" + oldVersion + ",\"expectedNewVersion\":"
                + newVersion + "}";
    }

    private static String batchBody(String requestKey, String operator, String... items) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"items\":[" + String.join(",", items) + "]}";
    }
}
