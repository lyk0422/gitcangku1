package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.plan.service.TimeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 编组登记、站台长度联合复核、批量发布整批回滚、PLATFORM_RISK 风险门禁
 * 与幂等边界的端到端测试（H2 内存库）。时钟固定到运营日之前，使运营日属于"未来"。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanConsistPlatformApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TimeService timeService;

    @BeforeEach
    void pinClock() {
        // 固定到运营日两天前，使 DAY 属于"未来已发布计划"回查范围
        timeService.pin(DAY.minusDays(2).atStartOfDay(SH).toInstant());
    }

    @AfterEach
    void resetClock() {
        timeService.reset();
    }

    // ---------- 编组登记主流程 ----------

    @Test
    void registerConsistNormalizesCarsAndIncrementsVersion() throws Exception {
        String platform = key("PLT");
        registerPlatform(platform, 500);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));

        MvcResult result = mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), 1, 400, platform, "op-1",
                                "C02", "C01", "C02", "C10")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("version").asInt()).isEqualTo(2);
        JsonNode consist = body.get("consist");
        assertThat(consist.get("version").asInt()).isEqualTo(2);
        assertThat(consist.get("trainLength").asInt()).isEqualTo(400);
        assertThat(consist.get("platformCode").asText()).isEqualTo(platform);
        // 车厢去重并规范化升序
        assertThat(consist.get("cars")).hasSize(3);
        assertThat(consist.get("cars").get(0).asText()).isEqualTo("C01");
        assertThat(consist.get("cars").get(1).asText()).isEqualTo("C02");
        assertThat(consist.get("cars").get(2).asText()).isEqualTo("C10");
        assertThat(body.get("platformRisk").asBoolean()).isFalse();
        assertThat(body.get("riskSnapshot").isNull()).isTrue();

        // 明细查询返回编组
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("consist").get("trainLength").asInt()).isEqualTo(400);
    }

    @Test
    void registerConsistRejectsInvalidParams() throws Exception {
        String platform = key("PLT");
        registerPlatform(platform, 500);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));

        // 长度为零/负 → 400
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), 1, 0, platform, "op-1", "C01")))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), 1, -5, platform, "op-1", "C01")))
                .andExpect(status().isBadRequest());
        // 空车厢集合 → 400
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), 1, 100, platform, "op-1")))
                .andExpect(status().isBadRequest());
        // 站台不存在 → 404
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), 1, 100, key("PLT"), "op-1", "C01")))
                .andExpect(status().isNotFound());
        // 计划不存在 → 404
        mvc.perform(put("/api/v1/plans/{key}/consist", key("SCH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), 1, 100, platform, "op-1", "C01")))
                .andExpect(status().isNotFound());
        // 版本不匹配 → 409
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), 7, 100, platform, "op-1", "C01")))
                .andExpect(status().isConflict());
        // 失败不占键：修正版本后同键成功
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), 1, 100, platform, "op-1", "C01")))
                .andExpect(status().isOk());
    }

    @Test
    void cancelledPlanCannotRegisterConsist() throws Exception {
        String platform = key("PLT");
        registerPlatform(platform, 500);
        String scheduleKey = key("SCH");
        String section = key("SEC");
        createPlan(scheduleKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(scheduleKey, key("REQ"));
        cancelPlan(scheduleKey, key("REQ"));

        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), 1, 100, platform, "op-1", "C01")))
                .andExpect(status().isConflict());
    }

    // ---------- 站台登记与调整 ----------

    @Test
    void registerAndQueryPlatform() throws Exception {
        String platform = key("PLT");
        MvcResult created = mvc.perform(post("/api/v1/platforms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(platformBody(key("REQ"), platform, 600)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("platformCode").asText()).isEqualTo(platform);
        assertThat(body.get("effectiveLength").asInt()).isEqualTo(600);
        assertThat(body.get("version").asInt()).isEqualTo(1);

        MvcResult detail = mvc.perform(get("/api/v1/platforms/{code}", platform))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);

        // 重复代码 → 409
        mvc.perform(post("/api/v1/platforms").contentType(MediaType.APPLICATION_JSON)
                        .content(platformBody(key("REQ"), platform, 700)))
                .andExpect(status().isConflict());
        // 长度非正 → 400
        mvc.perform(post("/api/v1/platforms").contentType(MediaType.APPLICATION_JSON)
                        .content(platformBody(key("REQ"), key("PLT"), 0)))
                .andExpect(status().isBadRequest());
        // 不存在 → 404
        mvc.perform(get("/api/v1/platforms/{code}", key("PLT")))
                .andExpect(status().isNotFound());
    }

    @Test
    void adjustPlatformLengthIncreaseAndVersionConflict() throws Exception {
        String platform = key("PLT");
        registerPlatform(platform, 500);

        MvcResult adjusted = mvc.perform(put("/api/v1/platforms/{code}/length", platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(key("REQ"), 1, 800)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(adjusted);
        assertThat(body.get("platform").get("effectiveLength").asInt()).isEqualTo(800);
        assertThat(body.get("platform").get("version").asInt()).isEqualTo(2);
        assertThat(body.get("affectedPlans")).isEmpty();

        // 版本不匹配 → 409
        mvc.perform(put("/api/v1/platforms/{code}/length", platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(key("REQ"), 1, 900)))
                .andExpect(status().isConflict());
        // 长度相同 → 409
        mvc.perform(put("/api/v1/platforms/{code}/length", platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(key("REQ"), 2, 800)))
                .andExpect(status().isConflict());
        // 站台不存在 → 404
        mvc.perform(put("/api/v1/platforms/{code}/length", key("PLT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(key("REQ"), 1, 100)))
                .andExpect(status().isNotFound());
    }

    // ---------- 发布/改签的站台联合复核 ----------

    @Test
    void publishRejectsTooLongConsistAndPlatformOverlap() throws Exception {
        String platform = key("PLT");
        registerPlatform(platform, 300);

        // 编组超长 → 422 PLATFORM_TOO_LONG，计划保持草稿
        String tooLong = key("SCH");
        createPlan(tooLong, occ("G1", key("SEC"), iso(8), iso(9)));
        registerConsist(tooLong, 1, 400, platform);
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", tooLong)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("PLATFORM_TOO_LONG");
        assertThat(detail.get("platformCode").asText()).isEqualTo(platform);
        assertThat(detail.get("trainLength").asInt()).isEqualTo(400);
        assertThat(detail.get("platformLength").asInt()).isEqualTo(300);
        MvcResult after = mvc.perform(get("/api/v1/plans/{key}", tooLong))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after).get("status").asText()).isEqualTo("DRAFT");

        // 缩短到合格长度后同站台时段重叠 → 422 PLATFORM_OVERLAP
        String first = key("SCH");
        String section = key("SEC");
        createPlan(first, occ("G2", section, iso(8), iso(9)));
        registerConsist(first, 1, 200, platform);
        publishPlan(first, key("REQ"));

        String overlapping = key("SCH");
        createPlan(overlapping, occ("G3", key("SEC"), iso(8, 30), iso(10)));
        registerConsist(overlapping, 1, 200, platform);
        MvcResult overlap = mvc.perform(post("/api/v1/plans/{key}/publish", overlapping)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode overlapError = read(overlap);
        assertThat(overlapError.get("details").get(0).get("type").asText())
                .isEqualTo("PLATFORM_OVERLAP");
        assertThat(overlapError.get("details").get(0).get("conflictingScheduleKey").asText())
                .isEqualTo(first);

        // 相邻时段（紧接不重叠）合法
        String adjacent = key("SCH");
        createPlan(adjacent, occ("G4", key("SEC"), iso(9), iso(10)));
        registerConsist(adjacent, 1, 200, platform);
        publishPlan(adjacent, key("REQ"));
    }

    @Test
    void rescheduleValidatesNewDraftConsist() throws Exception {
        String platform = key("PLT");
        registerPlatform(platform, 300);
        String section = key("SEC");
        String oldKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));

        // 新草稿编组超长 → 改签 422，旧计划仍发布、新计划仍草稿
        String newKey = key("SCH");
        createPlan(newKey, occ("G2", section, iso(8), iso(9)));
        registerConsist(newKey, 1, 400, platform);
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 2)))
                .andExpect(status().isUnprocessableEntity());
        MvcResult oldAfter = mvc.perform(get("/api/v1/plans/{key}", oldKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(oldAfter).get("status").asText()).isEqualTo("PUBLISHED");
        MvcResult newAfter = mvc.perform(get("/api/v1/plans/{key}", newKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(newAfter).get("status").asText()).isEqualTo("DRAFT");

        // 缩短编组后改签成功
        registerConsist(newKey, 2, 200, platform);
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 3)))
                .andExpect(status().isOk());
    }

    // ---------- 批量发布：最终态联合校验与整批回滚 ----------

    @Test
    void batchPublishSuccessAndQueryOccupancies() throws Exception {
        String platform = key("PLT");
        registerPlatform(platform, 500);
        String first = key("SCH");
        String second = key("SCH");
        createPlan(first, occ("G1", key("SEC"), iso(8), iso(9)));
        registerConsist(first, 1, 300, platform);
        createPlan(second, occ("G2", key("SEC"), iso(9), iso(10)));
        registerConsist(second, 1, 400, platform);

        MvcResult result = mvc.perform(post("/api/v1/plans/batch-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), first, second)))
                .andExpect(status().isOk()).andReturn();
        JsonNode plans = read(result).get("plans");
        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(plans.get(1).get("status").asText()).isEqualTo("PUBLISHED");

        // 站台占用查询：两条占用按开始时刻升序
        MvcResult occs = mvc.perform(get("/api/v1/platforms/{code}/occupancies", platform)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = read(occs);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("scheduleKey").asText()).isEqualTo(first);
        assertThat(list.get(1).get("scheduleKey").asText()).isEqualTo(second);
    }

    @Test
    void batchPublishRejectsTooLongAndRollsBackAll() throws Exception {
        String platform = key("PLT");
        registerPlatform(platform, 300);
        String good = key("SCH");
        String bad = key("SCH");
        createPlan(good, occ("G1", key("SEC"), iso(8), iso(9)));
        registerConsist(good, 1, 200, platform);
        createPlan(bad, occ("G2", key("SEC"), iso(9), iso(10)));
        registerConsist(bad, 1, 400, platform);

        MvcResult result = mvc.perform(post("/api/v1/plans/batch-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), good, bad)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("PLATFORM_CONFLICT");
        assertThat(error.get("details").get(0).get("type").asText())
                .isEqualTo("PLATFORM_TOO_LONG");
        assertThat(error.get("details").get(0).get("scheduleKey").asText()).isEqualTo(bad);

        // 整批不写入：合格计划也保持草稿
        MvcResult goodAfter = mvc.perform(get("/api/v1/plans/{key}", good))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(goodAfter).get("status").asText()).isEqualTo("DRAFT");
        MvcResult badAfter = mvc.perform(get("/api/v1/plans/{key}", bad))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(badAfter).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void batchPublishRejectsInternalPlatformOverlapAndRollsBackAll() throws Exception {
        String platform = key("PLT");
        registerPlatform(platform, 500);
        String first = key("SCH");
        String second = key("SCH");
        createPlan(first, occ("G1", key("SEC"), iso(8), iso(9)));
        registerConsist(first, 1, 200, platform);
        createPlan(second, occ("G2", key("SEC"), iso(8, 30), iso(10)));
        registerConsist(second, 1, 200, platform);

        MvcResult result = mvc.perform(post("/api/v1/plans/batch-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), first, second)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("PLATFORM_CONFLICT");
        assertThat(error.get("details").get(0).get("type").asText())
                .isEqualTo("PLATFORM_OVERLAP");

        MvcResult firstAfter = mvc.perform(get("/api/v1/plans/{key}", first))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(firstAfter).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void batchPublishTreatsNonContiguousOccupanciesAsDistinct() throws Exception {
        // 计划 A 占用 [08,09) 与 [10,11)，计划 B 占用 [09,10)：区间相邻不重叠，
        // 不得按整体包络 [08,11) 误判为同站台重叠
        String platform = key("PLT");
        registerPlatform(platform, 500);
        String first = key("SCH");
        String second = key("SCH");
        createPlan(first, occ("G1", key("SEC"), iso(8), iso(9)),
                occ("G1", key("SEC"), iso(10), iso(11)));
        registerConsist(first, 1, 200, platform);
        createPlan(second, occ("G2", key("SEC"), iso(9), iso(10)));
        registerConsist(second, 1, 200, platform);

        mvc.perform(post("/api/v1/plans/batch-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), first, second)))
                .andExpect(status().isOk());

        // 单计划发布与库内已发布计划比对时同样按区间判定：[11,12) 相邻合法
        String third = key("SCH");
        createPlan(third, occ("G3", key("SEC"), iso(11), iso(12)));
        registerConsist(third, 1, 200, platform);
        publishPlan(third, key("REQ"));
    }

    @Test
    void batchPublishRejectsOverlapWithPublishedPlan() throws Exception {
        String platform = key("PLT");
        registerPlatform(platform, 500);
        String published = key("SCH");
        createPlan(published, occ("G1", key("SEC"), iso(8), iso(9)));
        registerConsist(published, 1, 200, platform);
        publishPlan(published, key("REQ"));

        String draft = key("SCH");
        createPlan(draft, occ("G2", key("SEC"), iso(8, 30), iso(10)));
        registerConsist(draft, 1, 200, platform);

        mvc.perform(post("/api/v1/plans/batch-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), draft)))
                .andExpect(status().isUnprocessableEntity());
        MvcResult after = mvc.perform(get("/api/v1/plans/{key}", draft))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void batchPublishRejectsMissingAndDuplicateKeys() throws Exception {
        // 批量内存在不存在的计划 → 404
        mvc.perform(post("/api/v1/plans/batch-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), key("SCH"))))
                .andExpect(status().isNotFound());
        // 批量内计划键重复 → 400
        String dup = key("SCH");
        mvc.perform(post("/api/v1/plans/batch-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), dup, dup)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 站台下调回查与 PLATFORM_RISK ----------

    @Test
    void platformShortenMarksFuturePublishedPlansWithSnapshot() throws Exception {
        String platform = key("PLT");
        registerPlatform(platform, 500);
        String affected = key("SCH");
        String section = key("SEC");
        createPlan(affected, occ("G1", section, iso(8), iso(9)));
        registerConsist(affected, 1, 400, platform);
        publishPlan(affected, key("REQ"));
        // 无编组的已发布计划不受影响
        String noConsist = key("SCH");
        createPlan(noConsist, occ("G2", key("SEC"), iso(10), iso(11)));
        publishPlan(noConsist, key("REQ"));

        MvcResult adjusted = mvc.perform(put("/api/v1/platforms/{code}/length", platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(key("REQ"), 1, 300)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(adjusted);
        assertThat(body.get("platform").get("effectiveLength").asInt()).isEqualTo(300);
        JsonNode affectedPlans = body.get("affectedPlans");
        assertThat(affectedPlans).hasSize(1);
        JsonNode snap = affectedPlans.get(0);
        assertThat(snap.get("scheduleKey").asText()).isEqualTo(affected);
        assertThat(snap.get("trainLength").asInt()).isEqualTo(400);
        // 固化下调前原长度 500
        assertThat(snap.get("platformLengthSnapshot").asInt()).isEqualTo(500);

        // 计划被标记 PLATFORM_RISK 但未自动取消，历史占用保留
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", affected))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan = read(detail);
        assertThat(plan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(plan.get("platformRisk").asBoolean()).isTrue();
        assertThat(plan.get("riskSnapshot").get("platformLengthSnapshot").asInt())
                .isEqualTo(500);
        assertThat(plan.get("occupancies")).hasSize(1);

        // 无编组计划不受影响
        MvcResult other = mvc.perform(get("/api/v1/plans/{key}", noConsist))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(other).get("platformRisk").asBoolean()).isFalse();
    }

    @Test
    void riskPlanGatesConsistChangeUntilCompliant() throws Exception {
        String platform = key("PLT");
        registerPlatform(platform, 500);
        String scheduleKey = key("SCH");
        String section = key("SEC");
        createPlan(scheduleKey, occ("G1", section, iso(8), iso(9)));
        registerConsist(scheduleKey, 1, 400, platform);
        publishPlan(scheduleKey, key("REQ"));
        adjustPlatform(platform, 1, 300);

        // 风险未消除：换到仍然超长的站台 → 422，风险保持
        String shortPlatform = key("PLT");
        registerPlatform(shortPlatform, 350);
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), 2, 400, shortPlatform, "op-1", "C01")))
                .andExpect(status().isUnprocessableEntity());
        MvcResult stillRisk = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(stillRisk).get("platformRisk").asBoolean()).isTrue();

        // 缩短编组到合格长度 → 风险清除，快照删除
        MvcResult fixed = mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), 2, 250, platform, "op-1", "C01")))
                .andExpect(status().isOk()).andReturn();
        JsonNode fixedBody = read(fixed);
        assertThat(fixedBody.get("platformRisk").asBoolean()).isFalse();
        assertThat(fixedBody.get("riskSnapshot").isNull()).isTrue();
        assertThat(fixedBody.get("consist").get("trainLength").asInt()).isEqualTo(250);
    }

    @Test
    void riskPlanCanSwitchToQualifiedPlatform() throws Exception {
        String platform = key("PLT");
        registerPlatform(platform, 500);
        String qualified = key("PLT");
        registerPlatform(qualified, 600);
        String scheduleKey = key("SCH");
        String section = key("SEC");
        createPlan(scheduleKey, occ("G1", section, iso(8), iso(9)));
        registerConsist(scheduleKey, 1, 400, platform);
        publishPlan(scheduleKey, key("REQ"));
        adjustPlatform(platform, 1, 300);

        // 替换为合格站台 → 风险清除
        MvcResult fixed = mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), 2, 400, qualified, "op-1", "C01")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(fixed);
        assertThat(body.get("platformRisk").asBoolean()).isFalse();
        assertThat(body.get("consist").get("platformCode").asText()).isEqualTo(qualified);
    }

    @Test
    void riskPlanCannotRescheduleUntilFixed() throws Exception {
        String platform = key("PLT");
        registerPlatform(platform, 500);
        String section = key("SEC");
        String oldKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        registerConsist(oldKey, 1, 400, platform);
        publishPlan(oldKey, key("REQ"));
        adjustPlatform(platform, 1, 300);

        String newKey = key("SCH");
        createPlan(newKey, occ("G2", section, iso(8), iso(9)));
        registerConsist(newKey, 1, 200, platform);

        // 风险未消除不可改签 → 422
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 2)))
                .andExpect(status().isUnprocessableEntity());

        // 缩短编组消除风险后可改签（整改后旧计划版本为 3）
        registerConsist(oldKey, 2, 200, platform);
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 3, 2)))
                .andExpect(status().isOk());
    }

    // ---------- 幂等 ----------

    @Test
    void consistIdempotentReplayAndParamConflict() throws Exception {
        String platform = key("PLT");
        registerPlatform(platform, 500);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(8), iso(9)));
        String requestKey = key("REQ");
        String body = consistBody(requestKey, 1, 400, platform, "op-1", "C02", "C01");

        MvcResult first = mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        // 重放不再递增版本
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("version").asInt()).isEqualTo(2);

        // 同键不同参（车厢不同）→ 409
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(requestKey, 1, 400, platform, "op-1", "C09")))
                .andExpect(status().isConflict());
        // 同键不同参（操作者不同）→ 409
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(requestKey, 1, 400, platform, "op-2", "C01", "C02")))
                .andExpect(status().isConflict());
    }

    @Test
    void platformOpsIdempotentReplay() throws Exception {
        String platform = key("PLT");
        String registerKey = key("REQ");
        String registerBody = platformBody(registerKey, platform, 500);
        MvcResult first = mvc.perform(post("/api/v1/platforms")
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/platforms")
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        // 同键不同参 → 409
        mvc.perform(post("/api/v1/platforms").contentType(MediaType.APPLICATION_JSON)
                        .content(platformBody(registerKey, platform, 600)))
                .andExpect(status().isConflict());

        String adjustKey = key("REQ");
        String adjustBody = adjustBody(adjustKey, 1, 400);
        MvcResult adjFirst = mvc.perform(put("/api/v1/platforms/{code}/length", platform)
                        .contentType(MediaType.APPLICATION_JSON).content(adjustBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult adjReplay = mvc.perform(put("/api/v1/platforms/{code}/length", platform)
                        .contentType(MediaType.APPLICATION_JSON).content(adjustBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(adjReplay)).isEqualTo(read(adjFirst));
        // 同键不同参 → 409
        mvc.perform(put("/api/v1/platforms/{code}/length", platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(adjustKey, 1, 300)))
                .andExpect(status().isConflict());
    }

    @Test
    void batchPublishIdempotentReplay() throws Exception {
        String platform = key("PLT");
        registerPlatform(platform, 500);
        String first = key("SCH");
        String second = key("SCH");
        createPlan(first, occ("G1", key("SEC"), iso(8), iso(9)));
        registerConsist(first, 1, 200, platform);
        createPlan(second, occ("G2", key("SEC"), iso(9), iso(10)));
        registerConsist(second, 1, 200, platform);

        String requestKey = key("REQ");
        String body = batchBody(requestKey, first, second);
        MvcResult firstResult = mvc.perform(post("/api/v1/plans/batch-publish")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/plans/batch-publish")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(firstResult));
        // 同键不同参 → 409
        mvc.perform(post("/api/v1/plans/batch-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(requestKey, first)))
                .andExpect(status().isConflict());
    }

    // ---------- 辅助 ----------

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
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

    private void registerPlatform(String platformCode, int length) throws Exception {
        mvc.perform(post("/api/v1/platforms").contentType(MediaType.APPLICATION_JSON)
                        .content(platformBody(key("REQ"), platformCode, length)))
                .andExpect(status().isCreated());
    }

    private void registerConsist(String scheduleKey, int expectedVersion, int length,
                                 String platformCode) throws Exception {
        mvc.perform(put("/api/v1/plans/{key}/consist", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consistBody(key("REQ"), expectedVersion, length, platformCode,
                                "op-1", "C01")))
                .andExpect(status().isOk());
    }

    private void adjustPlatform(String platformCode, int expectedVersion, int length)
            throws Exception {
        mvc.perform(put("/api/v1/platforms/{code}/length", platformCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(key("REQ"), expectedVersion, length)))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour) {
        return iso(hour, 0);
    }

    private static String iso(int hour, int minuteOffset) {
        Instant instant = DAY.atTime(hour, 0).plusMinutes(minuteOffset).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String platformBody(String requestKey, String platformCode, int length) {
        return "{\"requestKey\":\"" + requestKey + "\",\"platformCode\":\"" + platformCode
                + "\",\"effectiveLength\":" + length + "}";
    }

    private static String adjustBody(String requestKey, int expectedVersion, int length) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"effectiveLength\":" + length + "}";
    }

    private static String consistBody(String requestKey, int expectedVersion, int length,
                                      String platformCode, String operator, String... cars) {
        StringBuilder carJson = new StringBuilder();
        for (int i = 0; i < cars.length; i++) {
            if (i > 0) {
                carJson.append(',');
            }
            carJson.append('"').append(cars[i]).append('"');
        }
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"trainLength\":" + length + ",\"platformCode\":\"" + platformCode
                + "\",\"operator\":\"" + operator + "\",\"cars\":[" + carJson + "]}";
    }

    private static String batchBody(String requestKey, String... scheduleKeys) {
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < scheduleKeys.length; i++) {
            if (i > 0) {
                keys.append(',');
            }
            keys.append('"').append(scheduleKeys[i]).append('"');
        }
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKeys\":[" + keys + "]}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }
}
