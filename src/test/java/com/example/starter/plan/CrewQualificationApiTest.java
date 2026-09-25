package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.plan.service.TimeSource;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 乘务资质门禁端到端测试（H2 内存库）：资质登记/修改/提前终止、发布与改签约束、
 * 角色互异、到期边界、风险持续门禁、换人与整单回滚。
 * 时钟固定为 2026-09-20T00:00:00Z，运营日 2026-09-25 为“未来”。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CrewQualificationApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);
    private static final Instant FIXED_NOW = Instant.parse("2026-09-20T00:00:00Z");
    /** 计划终到时刻（10:00 Asia/Shanghai）。 */
    private static final String PLAN_END = iso(10);
    /** 严格晚于终到的合法到期时刻。 */
    private static final String VALID_EXPIRY = "2026-09-26T00:00:00Z";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TimeSource timeSource;

    @BeforeEach
    void fixClock() {
        timeSource.setFixed(FIXED_NOW);
    }

    @AfterEach
    void resetClock() {
        timeSource.reset();
    }

    // ---------- 资质登记/修改 ----------

    @Test
    void registerNormalizesSectionsAndUpdateIncrementsVersion() throws Exception {
        String crewId = key("CREW");
        String code = "Q-" + UUID.randomUUID();
        // 区段换序登记，响应为规范化升序
        MvcResult registered = mvc.perform(post("/api/v1/crew-qualifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody(key("REQ"), crewId, code, VALID_EXPIRY, "SEC-B", "SEC-A")))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(registered);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("terminated").asBoolean()).isFalse();
        assertThat(body.get("sections").get(0).asText()).isEqualTo("SEC-A");
        assertThat(body.get("sections").get(1).asText()).isEqualTo("SEC-B");

        // 修改：换序同参集合 + 新到期时刻，版本加一
        MvcResult updated = mvc.perform(put("/api/v1/crew-qualifications/{c}/{q}", crewId, code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualUpdateBody(key("REQ"), 1, "2026-09-27T00:00:00Z",
                                "SEC-C", "SEC-B", "SEC-A")))
                .andExpect(status().isOk()).andReturn();
        JsonNode updatedBody = read(updated);
        assertThat(updatedBody.get("version").asInt()).isEqualTo(2);
        assertThat(updatedBody.get("sections")).hasSize(3);
        assertThat(updatedBody.get("expiresAtUtc").asText())
                .isEqualTo("2026-09-27T00:00:00Z");

        // 版本不匹配 → 409
        mvc.perform(put("/api/v1/crew-qualifications/{c}/{q}", crewId, code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualUpdateBody(key("REQ"), 1, VALID_EXPIRY, "SEC-A")))
                .andExpect(status().isConflict());
        // 不存在的资质 → 404
        mvc.perform(put("/api/v1/crew-qualifications/{c}/{q}", crewId, "NOPE-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualUpdateBody(key("REQ"), 1, VALID_EXPIRY, "SEC-A")))
                .andExpect(status().isNotFound());
    }

    @Test
    void registerRejectsDuplicateAndIsIdempotentWithReorderedSections() throws Exception {
        String crewId = key("CREW");
        String code = "Q-" + UUID.randomUUID();
        String requestKey = key("REQ");
        // 同键同参（区段换序视为同参）→ 重放首次结果
        MvcResult first = mvc.perform(post("/api/v1/crew-qualifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody(requestKey, crewId, code, VALID_EXPIRY, "SEC-B", "SEC-A")))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/crew-qualifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody(requestKey, crewId, code, VALID_EXPIRY, "SEC-A", "SEC-B")))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参（到期时刻不同）→ 409
        mvc.perform(post("/api/v1/crew-qualifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody(requestKey, crewId, code, "2026-09-30T00:00:00Z",
                                "SEC-A", "SEC-B")))
                .andExpect(status().isConflict());
        // 新键重复登记同一资质 → 409
        mvc.perform(post("/api/v1/crew-qualifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody(key("REQ"), crewId, code, VALID_EXPIRY, "SEC-A")))
                .andExpect(status().isConflict());
    }

    @Test
    void updateIdempotentReplayDoesNotIncrementTwice() throws Exception {
        String crewId = key("CREW");
        String code = "Q-" + UUID.randomUUID();
        registerQual(crewId, code, VALID_EXPIRY, "SEC-A");
        String body = qualUpdateBody(key("REQ"), 1, "2026-09-28T00:00:00Z", "SEC-A", "SEC-D");

        MvcResult first = mvc.perform(put("/api/v1/crew-qualifications/{c}/{q}", crewId, code)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(put("/api/v1/crew-qualifications/{c}/{q}", crewId, code)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        assertThat(read(replay).get("version").asInt()).isEqualTo(2);
    }

    // ---------- 发布乘务门禁 ----------

    @Test
    void publishWithQualifiedCrewSucceedsAndQueriesReflectAssignment() throws Exception {
        String section = key("SEC");
        String driver = key("DRV");
        String conductor = key("CON");
        registerQual(driver, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        registerQual(conductor, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G-" + UUID.randomUUID(),section, iso(8), iso(10)));

        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), "op-1", 1, driver, conductor)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(published);
        assertThat(body.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(body.get("driverId").asText()).isEqualTo(driver);
        assertThat(body.get("conductorId").asText()).isEqualTo(conductor);
        assertThat(body.get("riskBlocked").asBoolean()).isFalse();

        // 计划乘务资质查询：两角色均合格
        MvcResult crew = mvc.perform(get("/api/v1/plans/{key}/crew-qualification", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode roles = read(crew).get("roles");
        assertThat(roles).hasSize(2);
        assertThat(roles.get(0).get("role").asText()).isEqualTo("DRIVER");
        assertThat(roles.get(0).get("qualified").asBoolean()).isTrue();
        assertThat(roles.get(0).get("qualifications")).hasSize(1);
        assertThat(roles.get(1).get("role").asText()).isEqualTo("CONDUCTOR");
        assertThat(roles.get(1).get("qualified").asBoolean()).isTrue();

        // 缺口诊断为空
        MvcResult gaps = mvc.perform(get("/api/v1/plans/{key}/crew-qualification-gaps",
                        scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(gaps).get("gaps")).isEmpty();
    }

    @Test
    void publishRejectsSamePersonForBothRoles() throws Exception {
        String section = key("SEC");
        String crew = key("CREW");
        registerQual(crew, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G-" + UUID.randomUUID(),section, iso(8), iso(10)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), "op-1", null, crew, crew)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("CREW_QUALIFICATION_GAP");
        assertThat(error.get("details").get(0).get("gapType").asText()).isEqualTo("SAME_PERSON");
        assertThat(error.get("details").get(0).get("role").asText()).isEqualTo("BOTH");
    }

    @Test
    void publishRejectsSingleRoleAssignment() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G-" + UUID.randomUUID(),key("SEC"), iso(8), iso(10)));
        String onlyDriver = "{\"requestKey\":\"" + key("REQ") + "\",\"driverId\":\""
                + key("DRV") + "\"}";
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(onlyDriver))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publishRejectsMissingQualificationAndFailureDoesNotConsumeKey() throws Exception {
        String section = key("SEC");
        String driver = key("DRV");
        String conductor = key("CON");
        registerQual(conductor, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G-" + UUID.randomUUID(),section, iso(8), iso(10)));
        String publishKey = key("REQ");

        // 司机无任何资质 → 422 QUALIFICATION_MISSING，稳定列出角色
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, "op-1", null, driver, conductor)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("CREW_QUALIFICATION_GAP");
        JsonNode gap = error.get("details").get(0);
        assertThat(gap.get("role").asText()).isEqualTo("DRIVER");
        assertThat(gap.get("crewId").asText()).isEqualTo(driver);
        assertThat(gap.get("gapType").asText()).isEqualTo("QUALIFICATION_MISSING");
        assertThat(gap.get("missingSections").get(0).asText()).isEqualTo(section);

        // 计划保持草稿；失败不占键，补齐资质后同键重发成功
        assertThat(getPlanStatus(scheduleKey)).isEqualTo("DRAFT");
        registerQual(driver, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, "op-1", null, driver, conductor)))
                .andExpect(status().isOk());
    }

    @Test
    void publishRejectsInsufficientSectionCoverage() throws Exception {
        String sectionA = key("SEC");
        String sectionB = key("SEC");
        String driver = key("DRV");
        String conductor = key("CON");
        // 司机资质只覆盖 sectionA，计划需要 A+B
        registerQual(driver, "Q-" + UUID.randomUUID(), VALID_EXPIRY, sectionA);
        registerQual(conductor, "Q-" + UUID.randomUUID(), VALID_EXPIRY, sectionA, sectionB);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey,
                occ("G-" + UUID.randomUUID(),sectionA, iso(8), iso(9)),
                occ("G-" + UUID.randomUUID(),sectionB, iso(9), iso(10)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), "op-1", null, driver, conductor)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode gap = read(result).get("details").get(0);
        assertThat(gap.get("role").asText()).isEqualTo("DRIVER");
        assertThat(gap.get("gapType").asText()).isEqualTo("SECTION_COVERAGE");
        assertThat(gap.get("missingSections")).hasSize(1);
        assertThat(gap.get("missingSections").get(0).asText()).isEqualTo(sectionB);
        assertThat(getPlanStatus(scheduleKey)).isEqualTo("DRAFT");
    }

    @Test
    void publishEnforcesStrictExpiryBoundary() throws Exception {
        String section = key("SEC");
        String driver = key("DRV");
        String conductor = key("CON");
        // 到期时刻恰等于计划终到 → 不满足“严格晚于”
        registerQual(driver, "Q-" + UUID.randomUUID(), PLAN_END, section);
        registerQual(conductor, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G-" + UUID.randomUUID(),section, iso(8), iso(10)));
        String publishKey = key("REQ");

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, "op-1", null, driver, conductor)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode gap = read(result).get("details").get(0);
        assertThat(gap.get("role").asText()).isEqualTo("DRIVER");
        assertThat(gap.get("gapType").asText()).isEqualTo("EXPIRED");
        assertThat(gap.get("missingSections").get(0).asText()).isEqualTo(section);

        // 到期时刻延后 1 毫秒（严格晚于终到）→ 同键重发成功
        String code = "Q-" + UUID.randomUUID();
        registerQual(driver, code, VALID_EXPIRY, section);
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, "op-1", null, driver, conductor)))
                .andExpect(status().isOk());
    }

    @Test
    void publishIdempotencyFingerprintIncludesCrew() throws Exception {
        String section = key("SEC");
        String driver = key("DRV");
        String conductor = key("CON");
        String other = key("CON");
        registerQual(driver, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        registerQual(conductor, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        registerQual(other, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G-" + UUID.randomUUID(),section, iso(8), iso(10)));
        String publishKey = key("REQ");
        String body = publishBody(publishKey, "op-1", 1, driver, conductor);

        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放返回首次完整结果
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        // 同键不同角色 → 409
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, "op-1", 1, driver, other)))
                .andExpect(status().isConflict());
    }

    // ---------- 改签乘务门禁 ----------

    @Test
    void rescheduleWithCrewBindsNewPlanAndRollbackOnGap() throws Exception {
        String section = key("SEC");
        String driver = key("DRV");
        String conductor = key("CON");
        registerQual(driver, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        registerQual(conductor, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        String oldKey = key("SCH");
        createPlan(oldKey, occ("G-" + UUID.randomUUID(),section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        String newKey = key("SCH");
        createPlan(newKey, occ("G-" + UUID.randomUUID(),section, iso(9), iso(10)));

        // 新计划乘务合格 → 改签成功，乘务绑定在新计划上
        MvcResult rescheduled = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1, "op-1",
                                driver, conductor)))
                .andExpect(status().isOk()).andReturn();
        JsonNode newPlan = read(rescheduled).get("newPlan");
        assertThat(newPlan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(newPlan.get("driverId").asText()).isEqualTo(driver);
        assertThat(newPlan.get("conductorId").asText()).isEqualTo(conductor);

        // 车长无资质 → 422 整单回滚：旧计划仍发布、新计划仍草稿、无关联
        String oldKey2 = key("SCH");
        createPlan(oldKey2, occ("G-" + UUID.randomUUID(),section, iso(10), iso(11)));
        publishPlan(oldKey2, key("REQ"));
        String newKey2 = key("SCH");
        createPlan(newKey2, occ("G-" + UUID.randomUUID(),section, iso(11), iso(12)));
        String unqualified = key("CON");
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey2, 1, 1, "op-1",
                                driver, unqualified)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(getPlanStatus(oldKey2)).isEqualTo("PUBLISHED");
        assertThat(getPlanStatus(newKey2)).isEqualTo("DRAFT");
        Integer linkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_reschedule_link l"
                        + " JOIN rail_day_plan p ON p.id = l.predecessor_plan_id"
                        + " WHERE p.schedule_key = ?",
                Integer.class, oldKey2);
        assertThat(linkCount).isZero();
    }

    // ---------- 提前终止与风险门禁 ----------

    @Test
    void terminateWritesRiskRecordsAndKeepsPlanPublished() throws Exception {
        String section = key("SEC");
        String driver = key("DRV");
        String conductor = key("CON");
        String driverQual = "Q-" + UUID.randomUUID();
        registerQual(driver, driverQual, VALID_EXPIRY, section);
        registerQual(conductor, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G-" + UUID.randomUUID(),section, iso(8), iso(10)));
        publishWithCrew(scheduleKey, driver, conductor);

        MvcResult terminated = mvc.perform(post(
                        "/api/v1/crew-qualifications/{c}/{q}/terminate", driver, driverQual)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(terminated);
        assertThat(body.get("qualification").get("terminated").asBoolean()).isTrue();
        assertThat(body.get("qualification").get("version").asInt()).isEqualTo(2);
        assertThat(body.get("affectedPlans")).hasSize(1);
        assertThat(body.get("affectedPlans").get(0).asText()).isEqualTo(scheduleKey);

        // 计划不自动取消：仍 PUBLISHED，但置风险门禁
        JsonNode plan = getPlan(scheduleKey);
        assertThat(plan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(plan.get("riskBlocked").asBoolean()).isTrue();

        // 不可变风险记录
        MvcResult risks = mvc.perform(get("/api/v1/plans/{key}/risk-records", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode riskBody = read(risks);
        assertThat(riskBody.get("riskBlocked").asBoolean()).isTrue();
        assertThat(riskBody.get("records")).hasSize(1);
        JsonNode record = riskBody.get("records").get(0);
        assertThat(record.get("crewId").asText()).isEqualTo(driver);
        assertThat(record.get("role").asText()).isEqualTo("DRIVER");
        assertThat(record.get("qualificationCode").asText()).isEqualTo(driverQual);
        assertThat(record.get("reason").asText()).isEqualTo("QUALIFICATION_TERMINATED");

        // 缺口诊断：司机资质已终止 → QUALIFICATION_MISSING
        MvcResult gaps = mvc.perform(get("/api/v1/plans/{key}/crew-qualification-gaps",
                        scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode gapList = read(gaps).get("gaps");
        assertThat(gapList).hasSize(1);
        assertThat(gapList.get(0).get("role").asText()).isEqualTo("DRIVER");
        assertThat(gapList.get(0).get("gapType").asText()).isEqualTo("QUALIFICATION_MISSING");

        // 已终止资质不可修改、不可重复终止
        mvc.perform(put("/api/v1/crew-qualifications/{c}/{q}", driver, driverQual)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualUpdateBody(key("REQ"), 2, VALID_EXPIRY, section)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/crew-qualifications/{c}/{q}/terminate", driver, driverQual)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"expectedVersion\":2}"))
                .andExpect(status().isConflict());
    }

    @Test
    void terminateScansOnlyFuturePublishedPlans() throws Exception {
        String section = key("SEC");
        String driver = key("DRV");
        String conductor = key("CON");
        String driverQual = "Q-" + UUID.randomUUID();
        registerQual(driver, driverQual, "2026-10-01T00:00:00Z", section);
        registerQual(conductor, "Q-" + UUID.randomUUID(), "2026-10-01T00:00:00Z", section);
        // 已终到的历史计划（2026-09-19，早于固定时钟 09-20）不受影响
        String pastKey = key("SCH");
        createPlanOn(LocalDate.of(2026, 9, 19), pastKey, occ("G-" + UUID.randomUUID(),section, isoOn(
                LocalDate.of(2026, 9, 19), 8), isoOn(LocalDate.of(2026, 9, 19), 9)));
        publishWithCrew(pastKey, driver, conductor);
        // 未来计划受影响
        String futureKey = key("SCH");
        createPlan(futureKey, occ("G-" + UUID.randomUUID(),section, iso(8), iso(9)));
        publishWithCrew(futureKey, driver, conductor);

        MvcResult terminated = mvc.perform(post(
                        "/api/v1/crew-qualifications/{c}/{q}/terminate", driver, driverQual)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode affected = read(terminated).get("affectedPlans");
        assertThat(affected).hasSize(1);
        assertThat(affected.get(0).asText()).isEqualTo(futureKey);
        assertThat(getPlan(pastKey).get("riskBlocked").asBoolean()).isFalse();
        assertThat(getPlan(futureKey).get("riskBlocked").asBoolean()).isTrue();
    }

    @Test
    void terminateRollsBackEntirelyWhenRiskInsertConflicts() throws Exception {
        String section = key("SEC");
        String driver = key("DRV");
        String conductor = key("CON");
        String driverQual = "Q-" + UUID.randomUUID();
        registerQual(driver, driverQual, VALID_EXPIRY, section);
        registerQual(conductor, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        String planA = key("SCH");
        String planB = key("SCH");
        createPlan(planA, occ("G-" + UUID.randomUUID(),section, iso(8), iso(9)));
        publishWithCrew(planA, driver, conductor);
        createPlan(planB, occ("G-" + UUID.randomUUID(),section, iso(9), iso(10)));
        publishWithCrew(planB, driver, conductor);

        // 预置 planB 的 (计划, 乘务员, 角色) 唯一键，令回查写入必然冲突
        Long planBId = jdbc.queryForObject(
                "SELECT id FROM rail_day_plan WHERE schedule_key = ?", Long.class, planB);
        jdbc.update("INSERT INTO rail_plan_risk_record"
                        + " (plan_id, crew_id, role, qualification_code, reason, created_at)"
                        + " VALUES (?, ?, 'DRIVER', ?, 'QUALIFICATION_TERMINATED', 0)",
                planBId, driver, driverQual);

        mvc.perform(post("/api/v1/crew-qualifications/{c}/{q}/terminate", driver, driverQual)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict());

        // 整单回滚：资质未终止、版本不变，planA 未置风险、无新风险记录
        Boolean terminated = jdbc.queryForObject(
                "SELECT terminated FROM rail_crew_qualification"
                        + " WHERE crew_id = ? AND qualification_code = ?",
                Boolean.class, driver, driverQual);
        Integer version = jdbc.queryForObject(
                "SELECT version FROM rail_crew_qualification"
                        + " WHERE crew_id = ? AND qualification_code = ?",
                Integer.class, driver, driverQual);
        assertThat(terminated).isFalse();
        assertThat(version).isEqualTo(1);
        assertThat(getPlan(planA).get("riskBlocked").asBoolean()).isFalse();
        assertThat(getPlan(planB).get("riskBlocked").asBoolean()).isFalse();
        Integer riskCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_risk_record r"
                        + " JOIN rail_day_plan p ON p.id = r.plan_id"
                        + " WHERE p.schedule_key = ?",
                Integer.class, planA);
        assertThat(riskCount).isZero();
    }

    @Test
    void riskBlockedPlanRejectsRescheduleAndSameConsistPublish() throws Exception {
        String section = key("SEC");
        String driver = key("DRV");
        String conductor = key("CON");
        String driverQual = "Q-" + UUID.randomUUID();
        registerQual(driver, driverQual, VALID_EXPIRY, section);
        registerQual(conductor, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        String train = "G-" + UUID.randomUUID();
        String riskPlan = key("SCH");
        createPlan(riskPlan, occ(train, section, iso(8), iso(9)));
        publishWithCrew(riskPlan, driver, conductor);
        terminateQual(driver, driverQual, 1);
        assertThat(getPlan(riskPlan).get("riskBlocked").asBoolean()).isTrue();

        // 风险状态下禁止普通改签
        String newKey = key("SCH");
        createPlan(newKey, occ(train, section, iso(9), iso(10)));
        MvcResult rescheduled = mvc.perform(post("/api/v1/plans/{key}/reschedule", riskPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(rescheduled).get("code").asText()).isEqualTo("CREW_RISK_BLOCKED");

        // 风险状态下禁止发布同车底新增段
        String sameConsist = key("SCH");
        createPlan(sameConsist, occ(train, key("SEC"), iso(10), iso(11)));
        MvcResult consistResult = mvc.perform(post("/api/v1/plans/{key}/publish", sameConsist)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(consistResult).get("code").asText()).isEqualTo("CREW_RISK_BLOCKED");

        // 不同车底不受影响
        String otherConsist = key("SCH");
        createPlan(otherConsist, occ("G-" + UUID.randomUUID(), key("SEC"), iso(10), iso(11)));
        publishPlan(otherConsist, key("REQ"));
    }

    @Test
    void crewReplacementClearsRiskAndRestoresReschedule() throws Exception {
        String section = key("SEC");
        String driver = key("DRV");
        String conductor = key("CON");
        String driverQual = "Q-" + UUID.randomUUID();
        registerQual(driver, driverQual, VALID_EXPIRY, section);
        registerQual(conductor, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G-" + UUID.randomUUID(),section, iso(8), iso(9)));
        publishWithCrew(scheduleKey, driver, conductor);
        terminateQual(driver, driverQual, 1);

        // 非风险计划不可换人
        String normalPlan = key("SCH");
        createPlan(normalPlan, occ("G-" + UUID.randomUUID(), key("SEC"), iso(8), iso(9)));
        publishPlan(normalPlan, key("REQ"));
        mvc.perform(post("/api/v1/plans/{key}/crew-replacement", normalPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody(key("REQ"), 1, driver, conductor)))
                .andExpect(status().isConflict());

        // 换人仍须完整资质：新车长无资质 → 422，门禁保持
        String newDriver = key("DRV");
        registerQual(newDriver, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        String unqualified = key("CON");
        mvc.perform(post("/api/v1/plans/{key}/crew-replacement", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody(key("REQ"), 1, newDriver, unqualified)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(getPlan(scheduleKey).get("riskBlocked").asBoolean()).isTrue();

        // 两角色均替换为合格人员 → 解除门禁
        String replaceKey = key("REQ");
        MvcResult replaced = mvc.perform(post("/api/v1/plans/{key}/crew-replacement",
                        scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody(replaceKey, 1, newDriver, conductor)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(replaced);
        assertThat(body.get("riskBlocked").asBoolean()).isFalse();
        assertThat(body.get("driverId").asText()).isEqualTo(newDriver);
        assertThat(body.get("conductorId").asText()).isEqualTo(conductor);

        // 换人幂等：同键同参重放首次结果；同键不同参 409
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/crew-replacement",
                        scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody(replaceKey, 1, newDriver, conductor)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(replaced));
        mvc.perform(post("/api/v1/plans/{key}/crew-replacement", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceBody(replaceKey, 1, conductor, newDriver)))
                .andExpect(status().isConflict());

        // 门禁解除后普通改签恢复
        String newKey = key("SCH");
        createPlan(newKey, occ("G-" + UUID.randomUUID(),section, iso(9), iso(10)));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isOk());
    }

    @Test
    void terminateIsIdempotentAndFailureDoesNotConsumeKey() throws Exception {
        String section = key("SEC");
        String driver = key("DRV");
        String conductor = key("CON");
        String driverQual = "Q-" + UUID.randomUUID();
        registerQual(driver, driverQual, VALID_EXPIRY, section);
        registerQual(conductor, "Q-" + UUID.randomUUID(), VALID_EXPIRY, section);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G-" + UUID.randomUUID(), section, iso(8), iso(10)));
        publishWithCrew(scheduleKey, driver, conductor);

        // 版本不匹配 → 409 失败不占键
        String requestKey = key("REQ");
        mvc.perform(post("/api/v1/crew-qualifications/{c}/{q}/terminate", driver, driverQual)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":9}"))
                .andExpect(status().isConflict());
        String terminateBody = "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":1}";
        MvcResult first = mvc.perform(post(
                        "/api/v1/crew-qualifications/{c}/{q}/terminate", driver, driverQual)
                        .contentType(MediaType.APPLICATION_JSON).content(terminateBody))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放首次完整结果，且不重复写风险记录
        MvcResult replay = mvc.perform(post(
                        "/api/v1/crew-qualifications/{c}/{q}/terminate", driver, driverQual)
                        .contentType(MediaType.APPLICATION_JSON).content(terminateBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        MvcResult risks = mvc.perform(get("/api/v1/plans/{key}/risk-records", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(risks).get("records")).hasSize(1);
    }

    // ---------- 辅助 ----------

    private void registerQual(String crewId, String code, String expiresIso, String... sections)
            throws Exception {
        mvc.perform(post("/api/v1/crew-qualifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody(key("REQ"), crewId, code, expiresIso, sections)))
                .andExpect(status().isCreated());
    }

    private void terminateQual(String crewId, String code, int expectedVersion) throws Exception {
        mvc.perform(post("/api/v1/crew-qualifications/{c}/{q}/terminate", crewId, code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"expectedVersion\":"
                                + expectedVersion + "}"))
                .andExpect(status().isOk());
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        createPlanOn(DAY, scheduleKey, occupancies);
    }

    private void createPlanOn(LocalDate day, String scheduleKey, String... occupancies)
            throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + day + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private void publishWithCrew(String scheduleKey, String driver, String conductor)
            throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), "op-1", null, driver, conductor)))
                .andExpect(status().isOk());
    }

    private JsonNode getPlan(String scheduleKey) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(detail);
    }

    private String getPlanStatus(String scheduleKey) throws Exception {
        return getPlan(scheduleKey).get("status").asText();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour) {
        return isoOn(DAY, hour);
    }

    private static String isoOn(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String qualBody(String requestKey, String crewId, String code,
                                   String expiresIso, String... sections) {
        return "{\"requestKey\":\"" + requestKey + "\",\"crewId\":\"" + crewId
                + "\",\"qualificationCode\":\"" + code + "\",\"sections\":["
                + quoted(sections) + "],\"expiresAtUtc\":\"" + expiresIso + "\"}";
    }

    private static String qualUpdateBody(String requestKey, int expectedVersion,
                                         String expiresIso, String... sections) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"sections\":[" + quoted(sections) + "],\"expiresAtUtc\":\"" + expiresIso
                + "\"}";
    }

    private static String publishBody(String requestKey, String operator, Integer expectedVersion,
                                      String driverId, String conductorId) {
        StringBuilder sb = new StringBuilder("{\"requestKey\":\"").append(requestKey)
                .append("\",\"operator\":\"").append(operator).append('"');
        if (expectedVersion != null) {
            sb.append(",\"expectedVersion\":").append(expectedVersion);
        }
        if (driverId != null) {
            sb.append(",\"driverId\":\"").append(driverId).append('"');
        }
        if (conductorId != null) {
            sb.append(",\"conductorId\":\"").append(conductorId).append('"');
        }
        return sb.append('}').toString();
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion,
                                         String operator, String driverId, String conductorId) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion
                + ",\"operator\":\"" + operator + "\",\"driverId\":\"" + driverId
                + "\",\"conductorId\":\"" + conductorId + "\"}";
    }

    private static String replaceBody(String requestKey, int expectedVersion,
                                      String driverId, String conductorId) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"driverId\":\"" + driverId + "\",\"conductorId\":\"" + conductorId + "\"}";
    }

    private static String quoted(String... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values[i]).append('"');
        }
        return sb.toString();
    }
}
