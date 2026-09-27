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
 * 危险品编组隔离与区段通行 API 测试：主流程、422 失败分支（位置重复、间隔不足、
 * 类别未授权、配置无效）、发布快照固化与不可变、诊断查询与幂等边界（H2 内存库）。
 * 各用例使用唯一 scheduleKey/requestKey/区段 ID；配置版本为全局单调值，断言取相对增量。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HazardFormationApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程：规则 + 编组 + 发布 + 快照 ----------

    @Test
    void registerFormationAndPublishWritesSnapshot() throws Exception {
        int ruleVersionBefore = currentRuleVersion();
        replaceRules(key("REQ"), rule("EXPLOSIVE", "FLAMMABLE", 3));
        assertThat(currentRuleVersion()).isEqualTo(ruleVersionBefore + 1);

        String section = key("SEC");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8), iso(9)));

        MvcResult cars = mvc.perform(put("/api/v1/plans/{key}/hazard-cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carsBody(key("REQ"), "op-1", 1,
                                car(1, "EXPLOSIVE"), car(4, "FLAMMABLE"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode carsBody = read(cars);
        assertThat(carsBody.get("version").asInt()).isEqualTo(2);
        assertThat(carsBody.get("cars")).hasSize(2);
        assertThat(carsBody.get("cars").get(0).get("carPosition").asInt()).isEqualTo(1);
        assertThat(carsBody.get("cars").get(1).get("carPosition").asInt()).isEqualTo(4);

        publishPlan(scheduleKey, key("REQ"), "op-1");

        MvcResult snapshots = mvc.perform(get("/api/v1/plans/{key}/hazard-snapshots", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = read(snapshots);
        assertThat(list).hasSize(1);
        JsonNode snapshot = list.get(0);
        assertThat(snapshot.get("scheduleKey").asText()).isEqualTo(scheduleKey);
        assertThat(snapshot.get("source").asText()).isEqualTo("PUBLISH");
        assertThat(snapshot.get("operator").asText()).isEqualTo("op-1");
        assertThat(snapshot.get("validationResult").asText()).isEqualTo("PASSED");
        assertThat(snapshot.get("ruleVersion").asInt()).isEqualTo(currentRuleVersion());
        assertThat(snapshot.get("sectionVersion").asInt()).isEqualTo(currentSectionVersion());
        assertThat(snapshot.get("formation")).hasSize(2);
        assertThat(snapshot.get("formation").get(0).get("hazardClass").asText())
                .isEqualTo("EXPLOSIVE");
        assertThat(snapshot.get("sections")).hasSize(1);
        assertThat(snapshot.get("sections").get(0).asText()).isEqualTo(section);
        assertThat(snapshot.get("createdAtUtc").asText()).endsWith("Z");
    }

    // ---------- 422：车厢位置重复 ----------

    @Test
    void duplicateCarPositionRejectedWithConflictPair() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));

        MvcResult result = mvc.perform(put("/api/v1/plans/{key}/hazard-cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carsBody(key("REQ"), "op-1", 1,
                                car(3, "EXPLOSIVE"), car(3, "FLAMMABLE"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("HAZARD_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("DUPLICATE_CAR_POSITION");
        assertThat(detail.get("carPosition").asInt()).isEqualTo(3);
        assertThat(detail.get("occurrences").asInt()).isEqualTo(2);

        // 编组未变更：版本与编组保持原样
        MvcResult after = mvc.perform(get("/api/v1/plans/{key}/hazard-cars", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after).get("version").asInt()).isEqualTo(1);
        assertThat(read(after).get("cars")).isEmpty();
    }

    // ---------- 422：互斥间隔不足（含实际值/要求值/差额） ----------

    @Test
    void separationViolationRejectedWithActualAndRequired() throws Exception {
        replaceRules(key("REQ"), rule("EXPLOSIVE", "FLAMMABLE", 3));
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));

        MvcResult result = mvc.perform(put("/api/v1/plans/{key}/hazard-cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carsBody(key("REQ"), "op-1", 1,
                                car(1, "EXPLOSIVE"), car(2, "FLAMMABLE"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode detail = read(result).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SEPARATION_VIOLATION");
        assertThat(detail.get("actualSeparation").asInt()).isEqualTo(1);
        assertThat(detail.get("requiredSeparation").asInt()).isEqualTo(3);
        assertThat(detail.get("deficit").asInt()).isEqualTo(2);

        // 间隔恰好满足要求值则合法
        mvc.perform(put("/api/v1/plans/{key}/hazard-cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carsBody(key("REQ"), "op-1", 1,
                                car(1, "EXPLOSIVE"), car(4, "FLAMMABLE"))))
                .andExpect(status().isOk());
    }

    // ---------- 422：类别未授权（发布时完整路径校验） ----------

    @Test
    void publishRejectsUnauthorizedClassOnRestrictedSection() throws Exception {
        String section = key("SEC");
        configureSection(section, key("REQ"), "\"FLAMMABLE\"");

        String scheduleKey = key("SCH");
        String publishKey = key("REQ");
        createPlan(scheduleKey, occ("G1", section, iso(8), iso(9)));
        putCars(scheduleKey, key("REQ"), 1, car(1, "EXPLOSIVE"));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(publishKey, "op-1")))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("HAZARD_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SECTION_CLASS_NOT_ALLOWED");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("hazardClass").asText()).isEqualTo("EXPLOSIVE");
        assertThat(detail.get("allowedClasses").get(0).asText()).isEqualTo("FLAMMABLE");

        // 计划保持草稿、无快照；失败不占键，修正编组后同键发布成功
        assertThat(getPlanStatus(scheduleKey)).isEqualTo("DRAFT");
        MvcResult snapshots = mvc.perform(get("/api/v1/plans/{key}/hazard-snapshots", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(snapshots)).isEmpty();

        putCars(scheduleKey, key("REQ"), 2, car(1, "FLAMMABLE"));
        publishPlan(scheduleKey, publishKey, "op-1");
        assertThat(getPlanStatus(scheduleKey)).isEqualTo("PUBLISHED");
    }

    // ---------- 422：限制区段配置无效 ----------

    @Test
    void invalidSectionConfigRejected() throws Exception {
        String section = key("SEC");
        // 空清单
        MvcResult empty = mvc.perform(put("/api/v1/hazard/restricted-sections/{id}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("REQ"), "op-1")))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(empty).get("code").asText()).isEqualTo("INVALID_SECTION_CONFIG");
        // 重复类别
        mvc.perform(put("/api/v1/hazard/restricted-sections/{id}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("REQ"), "op-1",
                                "\"FLAMMABLE\"", "\"FLAMMABLE\"")))
                .andExpect(status().isUnprocessableEntity());
        // 配置无效不占版本
        MvcResult missing = mvc.perform(get("/api/v1/hazard/restricted-sections/{id}", section))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(read(missing).get("code").asText()).isEqualTo("SECTION_NOT_FOUND");
    }

    // ---------- 422：互斥规则配置无效 ----------

    @Test
    void invalidRuleConfigRejected() throws Exception {
        int versionBefore = currentRuleVersion();
        // 类别对两个类别相同
        mvc.perform(put("/api/v1/hazard/separation-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rulesBody(key("REQ"), "op-1", rule("EXPLOSIVE", "EXPLOSIVE", 2))))
                .andExpect(status().isUnprocessableEntity());
        // 类别对重复（含逆序重复）
        MvcResult dup = mvc.perform(put("/api/v1/hazard/separation-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rulesBody(key("REQ"), "op-1",
                                rule("EXPLOSIVE", "FLAMMABLE", 2),
                                rule("FLAMMABLE", "EXPLOSIVE", 3))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(dup).get("code").asText()).isEqualTo("INVALID_RULE_CONFIG");
        // 配置无效不增版本
        assertThat(currentRuleVersion()).isEqualTo(versionBefore);
    }

    // ---------- 诊断查询：不改变状态 ----------

    @Test
    void diagnosticsReflectsCurrentConfigWithoutSideEffects() throws Exception {
        // 先登记合法编组（间隔 2 满足当时规则），再收紧规则使既有编组变为违规
        replaceRules(key("REQ"), rule("EXPLOSIVE", "FLAMMABLE", 2));
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));
        putCars(scheduleKey, key("REQ"), 1, car(1, "EXPLOSIVE"), car(3, "FLAMMABLE"));
        replaceRules(key("REQ"), rule("EXPLOSIVE", "FLAMMABLE", 5));

        MvcResult bad = mvc.perform(get("/api/v1/plans/{key}/hazard-check", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode badBody = read(bad);
        assertThat(badBody.get("valid").asBoolean()).isFalse();
        assertThat(badBody.get("conflicts").get(0).get("type").asText())
                .isEqualTo("SEPARATION_VIOLATION");
        assertThat(badBody.get("conflicts").get(0).get("actualSeparation").asInt()).isEqualTo(2);
        assertThat(badBody.get("conflicts").get(0).get("requiredSeparation").asInt()).isEqualTo(5);
        assertThat(badBody.get("ruleVersion").asInt()).isEqualTo(currentRuleVersion());
        assertThat(badBody.get("formation")).hasSize(2);

        // 诊断不改变状态与版本
        assertThat(getPlanStatus(scheduleKey)).isEqualTo("DRAFT");
        MvcResult cars = mvc.perform(get("/api/v1/plans/{key}/hazard-cars", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(cars).get("version").asInt()).isEqualTo(2);

        // 修正编组后诊断通过
        putCars(scheduleKey, key("REQ"), 2, car(1, "EXPLOSIVE"), car(6, "FLAMMABLE"));
        MvcResult good = mvc.perform(get("/api/v1/plans/{key}/hazard-check", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(good).get("valid").asBoolean()).isTrue();
        assertThat(read(good).get("conflicts")).isEmpty();
    }

    // ---------- 快照不可变：配置变更与取消不改写历史 ----------

    @Test
    void snapshotImmutableAfterConfigChangeAndCancel() throws Exception {
        String section = key("SEC");
        configureSection(section, key("REQ"), "\"FLAMMABLE\"");
        replaceRules(key("REQ"), rule("EXPLOSIVE", "FLAMMABLE", 2));

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8), iso(9)));
        putCars(scheduleKey, key("REQ"), 1, car(1, "FLAMMABLE"));
        publishPlan(scheduleKey, key("REQ"), "op-1");

        MvcResult before = mvc.perform(get("/api/v1/plans/{key}/hazard-snapshots", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode snapshotBefore = read(before).get(0);

        // 后续配置变更与计划取消
        replaceRules(key("REQ"), rule("EXPLOSIVE", "FLAMMABLE", 9));
        configureSection(section, key("REQ"), "\"EXPLOSIVE\"");
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), "op-2")))
                .andExpect(status().isOk());

        MvcResult after = mvc.perform(get("/api/v1/plans/{key}/hazard-snapshots", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = read(after);
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).isEqualTo(snapshotBefore);
    }

    // ---------- 编组修改幂等：同参重放、异参 409、失败不占键 ----------

    @Test
    void hazardCarsIdempotentReplayAndConflict() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));
        String requestKey = key("REQ");
        String body = carsBody(requestKey, "op-1", 1, car(1, "EXPLOSIVE"));

        MvcResult first = mvc.perform(put("/api/v1/plans/{key}/hazard-cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(put("/api/v1/plans/{key}/hazard-cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        // 重放不重复加版本
        MvcResult cars = mvc.perform(get("/api/v1/plans/{key}/hazard-cars", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(cars).get("version").asInt()).isEqualTo(2);

        // 同键异参 → 409
        mvc.perform(put("/api/v1/plans/{key}/hazard-cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carsBody(requestKey, "op-1", 1, car(2, "EXPLOSIVE"))))
                .andExpect(status().isConflict());

        // 失败不占键：422 后同键修正参数成功
        String failKey = key("REQ");
        mvc.perform(put("/api/v1/plans/{key}/hazard-cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carsBody(failKey, "op-1", 2, car(5, "A"), car(5, "B"))))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/plans/{key}/hazard-cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carsBody(failKey, "op-1", 2, car(5, "A"), car(6, "B"))))
                .andExpect(status().isOk());
    }

    // ---------- 编组修改：版本与状态冲突 409 ----------

    @Test
    void hazardCarsVersionAndStateConflicts() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));

        mvc.perform(put("/api/v1/plans/{key}/hazard-cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carsBody(key("REQ"), "op-1", 7, car(1, "EXPLOSIVE"))))
                .andExpect(status().isConflict());

        publishPlan(scheduleKey, key("REQ"), null);
        mvc.perform(put("/api/v1/plans/{key}/hazard-cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carsBody(key("REQ"), "op-1", 1, car(1, "EXPLOSIVE"))))
                .andExpect(status().isConflict());
    }

    // ---------- 限制区段查询：稳定排序 ----------

    @Test
    void restrictedSectionQueriesStableOrdering() throws Exception {
        int versionBefore = currentSectionVersion();
        String sectionB = "ZZZ-" + key("SEC");
        String sectionA = "AAA-" + key("SEC");
        configureSection(sectionB, key("REQ"), "\"FLAMMABLE\"");
        configureSection(sectionA, key("REQ"), "\"EXPLOSIVE\"", "\"FLAMMABLE\"");
        assertThat(currentSectionVersion()).isEqualTo(versionBefore + 2);

        MvcResult list = mvc.perform(get("/api/v1/hazard/restricted-sections"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(list);
        assertThat(body.get("sectionVersion").asInt()).isEqualTo(versionBefore + 2);
        JsonNode sections = body.get("sections");
        assertThat(sections.size()).isGreaterThanOrEqualTo(2);
        // 按区段 ID 字典序
        String previous = null;
        for (JsonNode item : sections) {
            String id = item.get("sectionId").asText();
            if (previous != null) {
                assertThat(id).isGreaterThan(previous);
            }
            previous = id;
        }

        MvcResult single = mvc.perform(get("/api/v1/hazard/restricted-sections/{id}", sectionA))
                .andExpect(status().isOk()).andReturn();
        JsonNode singleBody = read(single);
        assertThat(singleBody.get("allowedClasses")).hasSize(2);
        assertThat(singleBody.get("allowedClasses").get(0).asText()).isEqualTo("EXPLOSIVE");
    }

    // ---------- 辅助 ----------

    private int currentRuleVersion() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/hazard/separation-rules"))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("ruleVersion").asInt();
    }

    private int currentSectionVersion() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/hazard/restricted-sections"))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("sectionVersion").asInt();
    }

    private void replaceRules(String requestKey, String... rules) throws Exception {
        mvc.perform(put("/api/v1/hazard/separation-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rulesBody(requestKey, "op-1", rules)))
                .andExpect(status().isOk());
    }

    private void configureSection(String sectionId, String requestKey,
                                  String... allowedClasses) throws Exception {
        mvc.perform(put("/api/v1/hazard/restricted-sections/{id}", sectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(requestKey, "op-1", allowedClasses)))
                .andExpect(status().isOk());
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void putCars(String scheduleKey, String requestKey, int expectedVersion,
                         String... cars) throws Exception {
        mvc.perform(put("/api/v1/plans/{key}/hazard-cars", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(carsBody(requestKey, "op-1", expectedVersion, cars)))
                .andExpect(status().isOk());
    }

    private void publishPlan(String scheduleKey, String requestKey, String operator)
            throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(requestKey, operator)))
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

    private static String iso(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String car(int position, String hazardClass) {
        return "{\"carPosition\":" + position + ",\"hazardClass\":\"" + hazardClass + "\"}";
    }

    private static String rule(String classA, String classB, int minSeparation) {
        return "{\"classA\":\"" + classA + "\",\"classB\":\"" + classB
                + "\",\"minSeparation\":" + minSeparation + "}";
    }

    private static String carsBody(String requestKey, String operator, int expectedVersion,
                                   String... cars) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"expectedVersion\":" + expectedVersion
                + ",\"cars\":[" + String.join(",", cars) + "]}";
    }

    private static String rulesBody(String requestKey, String operator, String... rules) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"rules\":[" + String.join(",", rules) + "]}";
    }

    private static String sectionBody(String requestKey, String operator,
                                      String... allowedClasses) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"allowedClasses\":[" + String.join(",", allowedClasses) + "]}";
    }

    private static String actionBody(String requestKey, String operator) {
        if (operator == null) {
            return "{\"requestKey\":\"" + requestKey + "\"}";
        }
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator + "\"}";
    }
}
