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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 危险品编组隔离与区段通行联合校验 API 测试（H2 内存库）：
 * 编组登记、配置整体替换、发布/改签联合校验、发布快照固化、诊断与批量改签。
 * 各用例使用唯一 scheduleKey/requestKey/区段 ID/类别代码，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HazardApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 编组登记 ----------

    @Test
    void registerCarsThenDetailShowsCarsAndVersionBumps() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));

        MvcResult result = mvc.perform(put("/api/v1/plans/{key}/cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carsBody(key("REQ"), "op-1", 1, car(0, cat()), car(5, cat()))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("version").asInt()).isEqualTo(2);
        assertThat(body.get("cars")).hasSize(2);
        assertThat(body.get("cars").get(0).get("carPosition").asInt()).isEqualTo(0);

        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);
    }

    @Test
    void replaceCarsIdempotentReplayAndParamConflict() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));
        String requestKey = key("REQ");
        String body = carsBody(requestKey, "op-1", 1, car(0, cat()));

        MvcResult first = mvc.perform(put("/api/v1/plans/{key}/cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(put("/api/v1/plans/{key}/cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("version").asInt()).isEqualTo(2);

        // 同键不同参 → 409
        mvc.perform(put("/api/v1/plans/{key}/cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carsBody(requestKey, "op-1", 1, car(1, cat()))))
                .andExpect(status().isConflict());
    }

    @Test
    void duplicateCarPositionRejected422() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));
        String categoryA = cat();
        String categoryB = cat();

        MvcResult result = mvc.perform(put("/api/v1/plans/{key}/cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carsBody(key("REQ"), "op-1", 1,
                                car(3, categoryA), car(3, categoryB))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("HAZARD_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("DUPLICATE_CAR_POSITION");
        assertThat(detail.get("carPosition").asInt()).isEqualTo(3);
        assertThat(detail.get("actualCount").asInt()).isEqualTo(2);
        assertThat(detail.get("requiredCount").asInt()).isEqualTo(1);

        // 编组未变更，版本不前进
        MvcResult after = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after).get("version").asInt()).isEqualTo(1);
        assertThat(read(after).get("cars")).isEmpty();
    }

    // ---------- 配置整体替换 ----------

    @Test
    void replaceConfigThenQuerySorted() throws Exception {
        String ruleA = cat();
        String ruleB = cat();
        String section = key("SEC");
        String body = configBody(key("REQ"), "op-cfg",
                List.of(rule(ruleB, ruleA, 3)),
                List.of(restriction(section, true, List.of(ruleB, ruleA))));

        mvc.perform(put("/api/v1/hazard/config")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        MvcResult config = mvc.perform(get("/api/v1/hazard/config"))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = read(config);
        JsonNode rules = json.get("separationRules");
        assertThat(rules).hasSize(1);
        // 类别对按字典序规范化存储
        String lo = ruleA.compareTo(ruleB) <= 0 ? ruleA : ruleB;
        String hi = ruleA.compareTo(ruleB) <= 0 ? ruleB : ruleA;
        assertThat(rules.get(0).get("categoryA").asText()).isEqualTo(lo);
        assertThat(rules.get(0).get("categoryB").asText()).isEqualTo(hi);
        assertThat(rules.get(0).get("minSeparation").asInt()).isEqualTo(3);
        JsonNode restrictions = json.get("sectionRestrictions");
        assertThat(restrictions).hasSize(1);
        assertThat(restrictions.get(0).get("sectionId").asText()).isEqualTo(section);
        assertThat(restrictions.get(0).get("restricted").asBoolean()).isTrue();
        assertThat(restrictions.get(0).get("version").asInt()).isEqualTo(1);
        // 开放清单按字典序返回
        assertThat(restrictions.get(0).get("allowedCategories").get(0).asText()).isEqualTo(lo);
    }

    @Test
    void invalidConfigRejected422AndUnchanged() throws Exception {
        // 先置一份合法基线配置
        replaceConfig(List.of(rule(cat(), cat(), 2)), List.of());
        MvcResult before = mvc.perform(get("/api/v1/hazard/config"))
                .andExpect(status().isOk()).andReturn();

        String categoryA = cat();
        String categoryB = cat();
        String section = key("SEC");
        // 自配对规则、重复规则、重复区段、限制区段空开放清单、非限制区段带开放清单
        String body = configBody(key("REQ"), "op-cfg",
                List.of(rule(categoryA, categoryA, 1),
                        rule(categoryA, categoryB, 2),
                        rule(categoryB, categoryA, 3)),
                List.of(restriction(section, true, List.of()),
                        restriction(section, true, List.of(categoryA)),
                        restriction(key("SEC"), false, List.of(categoryA))));

        MvcResult result = mvc.perform(put("/api/v1/hazard/config")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("HAZARD_CONFIG_INVALID");
        List<String> types = new ArrayList<>();
        error.get("details").forEach(d -> types.add(d.get("type").asText()));
        assertThat(types).contains("SELF_RULE", "DUPLICATE_RULE", "DUPLICATE_SECTION",
                "EMPTY_ALLOWED_LIST", "UNRESTRICTED_WITH_ALLOWED_LIST");

        // 配置整体未生效：与基线完全一致
        MvcResult after = mvc.perform(get("/api/v1/hazard/config"))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after)).isEqualTo(read(before));
    }

    @Test
    void configIdempotentReplayAndParamConflict() throws Exception {
        String requestKey = key("REQ");
        String body = configBody(requestKey, "op-cfg",
                List.of(rule(cat(), cat(), 2)), List.of());

        MvcResult first = mvc.perform(put("/api/v1/hazard/config")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(put("/api/v1/hazard/config")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        mvc.perform(put("/api/v1/hazard/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(requestKey, "op-cfg",
                                List.of(rule(cat(), cat(), 5)), List.of())))
                .andExpect(status().isConflict());
    }

    // ---------- 发布联合校验 ----------

    @Test
    void separationInsufficientBlocksPublishWithActualRequiredDeficit() throws Exception {
        String categoryA = cat();
        String categoryB = cat();
        replaceConfig(List.of(rule(categoryA, categoryB, 3)), List.of());
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));
        putCars(scheduleKey, 1, car(0, categoryA), car(2, categoryB));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("HAZARD_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SEPARATION_INSUFFICIENT");
        assertThat(detail.get("actualSeparation").asInt()).isEqualTo(2);
        assertThat(detail.get("requiredSeparation").asInt()).isEqualTo(3);
        assertThat(detail.get("deficit").asInt()).isEqualTo(1);

        // 计划保持草稿，诊断只读报告 FAILED
        assertThat(getStatus(scheduleKey)).isEqualTo("DRAFT");
        MvcResult diagnostic = mvc.perform(get("/api/v1/plans/{key}/hazard-diagnostic", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode diag = read(diagnostic);
        assertThat(diag.get("conclusion").asText()).isEqualTo("FAILED");
        assertThat(diag.get("checks").get(1).get("check").asText()).isEqualTo("SEPARATION");
        assertThat(diag.get("checks").get(1).get("result").asText()).isEqualTo("FAILED");
        assertThat(getStatus(scheduleKey)).isEqualTo("DRAFT");

        // 修正编组间隔到 3 后发布成功
        putCars(scheduleKey, 2, car(0, categoryA), car(3, categoryB));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    @Test
    void unauthorizedCategoryBlocksPublishUntilConfigOpens() throws Exception {
        String categoryA = cat();
        String categoryB = cat();
        String section = key("SEC");
        replaceConfig(List.of(), List.of(restriction(section, true, List.of(categoryB))));
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8), iso(9)));
        putCars(scheduleKey, 1, car(0, categoryA));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("HAZARD_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("CATEGORY_NOT_AUTHORIZED");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("category").asText()).isEqualTo(categoryA);
        assertThat(getStatus(scheduleKey)).isEqualTo("DRAFT");

        // 区段开放该类别后发布成功
        replaceConfig(List.of(), List.of(restriction(section, true, List.of(categoryA, categoryB))));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    @Test
    void planWithoutCarsPassesRestrictedSection() throws Exception {
        String section = key("SEC");
        replaceConfig(List.of(), List.of(restriction(section, true, List.of(cat()))));
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8), iso(9)));
        // 无危险品编组：限制区段不拦截
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    // ---------- 发布快照 ----------

    @Test
    void publishSnapshotFreezesMarshallingAndRestrictionVersion() throws Exception {
        String categoryA = cat();
        String categoryB = cat();
        String section = key("SEC");
        replaceConfig(List.of(rule(categoryA, categoryB, 2)),
                List.of(restriction(section, true, List.of(categoryA, categoryB))));
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8), iso(9)));
        putCars(scheduleKey, 1, car(0, categoryA), car(2, categoryB));

        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), "op-pub")))
                .andExpect(status().isOk());

        MvcResult snapshot = mvc.perform(get("/api/v1/plans/{key}/publish-snapshot", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode snap = read(snapshot);
        assertThat(snap.get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(snap.get("opType").asText()).isEqualTo("PUBLISH");
        assertThat(snap.get("conclusion").asText()).isEqualTo("PASSED");
        assertThat(snap.get("operator").asText()).isEqualTo("op-pub");
        JsonNode body = snap.get("snapshot");
        assertThat(body.get("cars")).hasSize(2);
        assertThat(body.get("cars").get(0).get("carPosition").asInt()).isEqualTo(0);
        assertThat(body.get("categories")).hasSize(2);
        assertThat(body.get("restrictionVersions")).hasSize(1);
        assertThat(body.get("restrictionVersions").get(0).get("sectionId").asText())
                .isEqualTo(section);
        assertThat(body.get("restrictionVersions").get(0).get("version").asInt()).isEqualTo(1);
        assertThat(body.get("checks")).hasSize(3);
        body.get("checks").forEach(c -> assertThat(c.get("result").asText()).isEqualTo("PASSED"));

        // 配置变更（区段版本升到 2）不改写历史快照
        replaceConfig(List.of(rule(categoryA, categoryB, 2)),
                List.of(restriction(section, true, List.of(categoryA))));
        MvcResult afterConfig = mvc.perform(get("/api/v1/plans/{key}/publish-snapshot", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(afterConfig)).isEqualTo(snap);

        // 取消计划不改写历史快照
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        MvcResult afterCancel = mvc.perform(get("/api/v1/plans/{key}/publish-snapshot", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(afterCancel)).isEqualTo(snap);

        // 历史列表稳定可查
        MvcResult history = mvc.perform(get("/api/v1/publish-snapshots")
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(history);
        assertThat(items).isNotEmpty();
        boolean found = false;
        for (JsonNode item : items) {
            if (item.get("scheduleKey").asText().equals(scheduleKey)) {
                found = true;
                assertThat(item.get("opType").asText()).isEqualTo("PUBLISH");
                assertThat(item.get("operator").asText()).isEqualTo("op-pub");
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void snapshotNotFoundForDraftPlan() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));
        mvc.perform(get("/api/v1/plans/{key}/publish-snapshot", scheduleKey))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/hazard-diagnostic", key("SCH")))
                .andExpect(status().isNotFound());
    }

    // ---------- 批量改签 ----------

    @Test
    void batchRescheduleHappyPath() throws Exception {
        String sectionA = key("SEC");
        String sectionB = key("SEC");
        String oldA = key("SCH");
        String oldB = key("SCH");
        String newA = key("SCH");
        String newB = key("SCH");
        createPlan(oldA, occ("G1", sectionA, iso(8), iso(9)));
        publishPlan(oldA, key("REQ"));
        createPlan(oldB, occ("G2", sectionB, iso(8), iso(9)));
        publishPlan(oldB, key("REQ"));
        createPlan(newA, occ("G3", sectionA, iso(8), iso(10)));
        createPlan(newB, occ("G4", sectionB, iso(8), iso(10)));

        MvcResult result = mvc.perform(post("/api/v1/plans/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), "op-batch",
                                item(oldA, newA, 1, 1), item(oldB, newB, 1, 1))))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(result).get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("oldPlan").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(items.get(0).get("newPlan").get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(items.get(1).get("newPlan").get("scheduleKey").asText()).isEqualTo(newB);

        // 快照按 BATCH_RESCHEDULE 固化
        MvcResult snap = mvc.perform(get("/api/v1/plans/{key}/publish-snapshot", newA))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(snap).get("opType").asText()).isEqualTo("BATCH_RESCHEDULE");
        assertThat(read(snap).get("operator").asText()).isEqualTo("op-batch");
    }

    @Test
    void batchRescheduleRollsBackEntirelyOnHazardViolation() throws Exception {
        String categoryA = cat();
        String categoryB = cat();
        replaceConfig(List.of(rule(categoryA, categoryB, 5)), List.of());
        String sectionA = key("SEC");
        String sectionB = key("SEC");
        String oldA = key("SCH");
        String oldB = key("SCH");
        String newA = key("SCH");
        String newB = key("SCH");
        createPlan(oldA, occ("G1", sectionA, iso(8), iso(9)));
        publishPlan(oldA, key("REQ"));
        createPlan(oldB, occ("G2", sectionB, iso(8), iso(9)));
        publishPlan(oldB, key("REQ"));
        createPlan(newA, occ("G3", sectionA, iso(8), iso(10)));
        createPlan(newB, occ("G4", sectionB, iso(8), iso(10)));
        // 第二项新计划编组间隔不足（规则要求 5，实际 1）
        putCars(newB, 1, car(0, categoryA), car(1, categoryB));

        MvcResult result = mvc.perform(post("/api/v1/plans/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), "op-batch",
                                item(oldA, newA, 1, 1), item(oldB, newB, 1, 2))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("HAZARD_CONFLICT");

        // 整次不改变时隙或编组：旧计划仍发布、新计划仍草稿、无关联、无快照
        assertThat(getStatus(oldA)).isEqualTo("PUBLISHED");
        assertThat(getStatus(oldB)).isEqualTo("PUBLISHED");
        assertThat(getStatus(newA)).isEqualTo("DRAFT");
        assertThat(getStatus(newB)).isEqualTo("DRAFT");
        mvc.perform(get("/api/v1/plans/{key}/publish-snapshot", newA))
                .andExpect(status().isNotFound());
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", oldA))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(chain).get("chain")).hasSize(1);
    }

    @Test
    void batchRescheduleCrossItemSlotConflictRollsBack() throws Exception {
        String section = key("SEC");
        String oldA = key("SCH");
        String oldB = key("SCH");
        String newA = key("SCH");
        String newB = key("SCH");
        createPlan(oldA, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldA, key("REQ"));
        createPlan(oldB, occ("G2", key("SEC"), iso(8), iso(9)));
        publishPlan(oldB, key("REQ"));
        // 两个新计划在同一区段同时段互相冲突
        createPlan(newA, occ("G3", section, iso(9), iso(10)));
        createPlan(newB, occ("G4", section, iso(9), iso(10)));

        MvcResult result = mvc.perform(post("/api/v1/plans/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), "op-batch",
                                item(oldA, newA, 1, 1), item(oldB, newB, 1, 1))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("SLOT_CONFLICT");
        assertThat(getStatus(oldA)).isEqualTo("PUBLISHED");
        assertThat(getStatus(newA)).isEqualTo("DRAFT");
        assertThat(getStatus(newB)).isEqualTo("DRAFT");
    }

    @Test
    void batchRescheduleIdempotentReplayAndParamConflict() throws Exception {
        String section = key("SEC");
        String oldA = key("SCH");
        String newA = key("SCH");
        createPlan(oldA, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldA, key("REQ"));
        createPlan(newA, occ("G2", section, iso(8), iso(10)));
        String requestKey = key("REQ");
        String body = batchBody(requestKey, "op-batch", item(oldA, newA, 1, 1));

        MvcResult first = mvc.perform(post("/api/v1/plans/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/plans/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/plans/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(requestKey, "op-batch-2", item(oldA, newA, 1, 1))))
                .andExpect(status().isConflict());
    }

    // ---------- 辅助 ----------

    private void createPlan(String scheduleKey, String occupancy) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + occupancy + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private void putCars(String scheduleKey, int expectedVersion, String... cars) throws Exception {
        mvc.perform(put("/api/v1/plans/{key}/cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carsBody(key("REQ"), "op-1", expectedVersion, cars)))
                .andExpect(status().isOk());
    }

    private void replaceConfig(List<String> rules, List<String> restrictions) throws Exception {
        mvc.perform(put("/api/v1/hazard/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op-cfg", rules, restrictions)))
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

    /** 唯一类别代码（33 字符，不超过 64 上限），避免用例间规则互相干扰。 */
    private static String cat() {
        return "C" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String iso(int hour) {
        return iso(hour, 0);
    }

    private static String iso(int hour, int dayOffset) {
        Instant instant = DAY.plusDays(dayOffset).atTime(hour, 0).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String car(int position, String category) {
        return "{\"carPosition\":" + position + ",\"category\":\"" + category + "\"}";
    }

    private static String rule(String categoryA, String categoryB, int minSeparation) {
        return "{\"categoryA\":\"" + categoryA + "\",\"categoryB\":\"" + categoryB
                + "\",\"minSeparation\":" + minSeparation + "}";
    }

    private static String restriction(String sectionId, boolean restricted, List<String> allowed) {
        return "{\"sectionId\":\"" + sectionId + "\",\"restricted\":" + restricted
                + ",\"allowedCategories\":[" + allowed.stream()
                        .map(c -> "\"" + c + "\"")
                        .reduce((a, b) -> a + "," + b).orElse("")
                + "]}";
    }

    private static String item(String oldKey, String newKey, int expectedOld, int expectedNew) {
        return "{\"oldScheduleKey\":\"" + oldKey + "\",\"newScheduleKey\":\"" + newKey
                + "\",\"expectedOldVersion\":" + expectedOld
                + ",\"expectedNewVersion\":" + expectedNew + "}";
    }

    private static String carsBody(String requestKey, String operator, int expectedVersion,
                                   String... cars) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"expectedVersion\":" + expectedVersion
                + ",\"cars\":[" + String.join(",", cars) + "]}";
    }

    private static String configBody(String requestKey, String operator, List<String> rules,
                                     List<String> restrictions) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"separationRules\":[" + String.join(",", rules)
                + "],\"sectionRestrictions\":[" + String.join(",", restrictions) + "]}";
    }

    private static String batchBody(String requestKey, String operator, String... items) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"items\":[" + String.join(",", items) + "]}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String actionBody(String requestKey, String operator) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("requestKey", requestKey);
        map.put("operator", operator);
        StringBuilder sb = new StringBuilder("{");
        map.forEach((k, v) -> sb.append(sb.length() > 1 ? "," : "")
                .append('"').append(k).append("\":\"").append(v).append('"'));
        return sb.append('}').toString();
    }
}
