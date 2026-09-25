package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 乘务资质门禁端到端测试（H2 内存库）：资质生命周期、角色互异、到期边界、
 * 区段覆盖、整单回滚、提前终止回查、风险持续门禁与缺口诊断。
 * 使用固定时钟（2026-09-26T00:00Z），计划运营日 2026-10-01 为“未来”。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CrewQualificationApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    /** 计划终到时刻：2026-10-01 09:00 +08 = 01:00 UTC。 */
    private static final Instant PLAN_END = Instant.parse("2026-10-01T01:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 资质生命周期 ----------

    @Test
    void createQualificationNormalizesSectionsAndReplaysIdempotently() throws Exception {
        String qualCode = key("QUAL");
        String requestKey = key("REQ");
        String body = qualBody(requestKey, qualCode, key("CREW"),
                "SEC-B,SEC-A,SEC-B", "2026-12-31T16:00:00Z");

        MvcResult first = mvc.perform(post("/api/v1/crew-qualifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode created = read(first);
        assertThat(created.get("qualCode").asText()).isEqualTo(qualCode);
        assertThat(created.get("version").asInt()).isEqualTo(1);
        assertThat(created.get("terminated").asBoolean()).isFalse();
        // 区段集合规范化：去重排序，换序视为同参
        assertThat(created.get("sections")).hasSize(2);
        assertThat(created.get("sections").get(0).asText()).isEqualTo("SEC-A");
        assertThat(created.get("sections").get(1).asText()).isEqualTo("SEC-B");

        // 同键同参重放返回首次结果
        MvcResult replay = mvc.perform(post("/api/v1/crew-qualifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(created);

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/crew-qualifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody(requestKey, key("QUAL"), key("CREW"),
                                "SEC-A", "2026-12-31T16:00:00Z")))
                .andExpect(status().isConflict());
    }

    @Test
    void updateQualificationChecksVersionAndTerminatedState() throws Exception {
        String qualCode = key("QUAL");
        String crew = key("CREW");
        createQual(qualCode, crew, "SEC-A", "2026-12-31T16:00:00Z");

        // 版本不匹配 → 409
        mvc.perform(put("/api/v1/crew-qualifications/{code}", qualCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateQualBody(key("REQ"), 5, crew, "SEC-A,SEC-C",
                                "2027-01-31T16:00:00Z")))
                .andExpect(status().isConflict());

        // 正确版本修改成功，版本加一，区段换序视为同参
        MvcResult updated = mvc.perform(put("/api/v1/crew-qualifications/{code}", qualCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateQualBody(key("REQ"), 1, crew, "SEC-C,SEC-A",
                                "2027-01-31T16:00:00Z")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(updated);
        assertThat(body.get("version").asInt()).isEqualTo(2);
        assertThat(body.get("sections").get(0).asText()).isEqualTo("SEC-A");
        assertThat(body.get("sections").get(1).asText()).isEqualTo("SEC-C");

        // 不可改派给其他乘务员
        mvc.perform(put("/api/v1/crew-qualifications/{code}", qualCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateQualBody(key("REQ"), 2, key("CREW"), "SEC-A,SEC-C",
                                "2027-01-31T16:00:00Z")))
                .andExpect(status().isConflict());

        // 提前终止后不可修改
        terminateQual(qualCode, 2);
        mvc.perform(put("/api/v1/crew-qualifications/{code}", qualCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateQualBody(key("REQ"), 3, crew, "SEC-A,SEC-C",
                                "2027-01-31T16:00:00Z")))
                .andExpect(status().isConflict());
    }

    // ---------- 发布门禁：角色互异 / 成对指定 ----------

    @Test
    void publishRejectsSameCrewForBothRoles() throws Exception {
        String crew = key("CREW");
        String qual = key("QUAL");
        createQual(qual, crew, "SEC-A", "2026-12-31T16:00:00Z");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(key("G"), "SEC-A", iso(8), iso(9)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), crew, qual, crew, qual)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("CREW_QUALIFICATION_GAP");
        assertThat(error.get("details")).hasSize(2);
        assertThat(error.get("details").get(0).get("reason").asText())
                .isEqualTo("SAME_CREW_BOTH_ROLES");
        // 稳定列出两个角色
        assertThat(error.get("details").get(0).get("role").asText()).isEqualTo("CONDUCTOR");
        assertThat(error.get("details").get(1).get("role").asText()).isEqualTo("DRIVER");

        assertThat(getPlanStatus(scheduleKey)).isEqualTo("DRAFT");
    }

    @Test
    void publishRejectsSingleRoleOnly() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(key("G"), "SEC-A", iso(8), iso(9)));
        // 仅指定司机，缺车长 → 400
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ")
                                + "\",\"driver\":{\"crewId\":\"" + key("CREW")
                                + "\",\"qualCode\":\"" + key("QUAL") + "\"}}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 发布门禁：到期边界与区段覆盖 ----------

    @Test
    void publishRejectsExpiryNotStrictlyAfterPlanEnd() throws Exception {
        String driver = key("CREW");
        String conductor = key("CREW");
        String driverQual = key("QUAL");
        String conductorQual = key("QUAL");
        // 司机资质到期恰等于计划终到 → 不满足“严格晚于”
        createQual(driverQual, driver, "SEC-A", PLAN_END.toString());
        createQual(conductorQual, conductor, "SEC-A", "2026-12-31T16:00:00Z");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(key("G"), "SEC-A", iso(8), iso(9)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), driver, driverQual,
                                conductor, conductorQual)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("CREW_QUALIFICATION_GAP");
        assertThat(error.get("details")).hasSize(1);
        assertThat(error.get("details").get(0).get("role").asText()).isEqualTo("DRIVER");
        assertThat(error.get("details").get(0).get("reason").asText()).isEqualTo("QUAL_EXPIRED");
        assertThat(getPlanStatus(scheduleKey)).isEqualTo("DRAFT");

        // 到期严格晚于终到 1 毫秒 → 放行
        String okDriver = key("CREW");
        String exactOkDriverQual = key("QUAL");
        createQual(exactOkDriverQual, okDriver, "SEC-A",
                PLAN_END.plusMillis(1).toString());
        String okPlan = key("SCH");
        createPlan(okPlan, occ(key("G"), "SEC-A", iso(8), iso(9)));
        mvc.perform(post("/api/v1/plans/{key}/publish", okPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), okDriver, exactOkDriverQual,
                                conductor, conductorQual)))
                .andExpect(status().isOk());
    }

    @Test
    void publishRejectsMissingSectionCoverageAndListsGap() throws Exception {
        String sectionA = key("SEC");
        String sectionB = key("SEC");
        String driver = key("CREW");
        String conductor = key("CREW");
        String driverQual = key("QUAL");
        String conductorQual = key("QUAL");
        // 司机资质只覆盖 sectionA，计划跨 sectionA+sectionB
        createQual(driverQual, driver, sectionA, "2026-12-31T16:00:00Z");
        // 车长资质区段换序提供，覆盖全集 → 视为同参通过
        createQual(conductorQual, conductor, sectionB + "," + sectionA,
                "2026-12-31T16:00:00Z");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey,
                occ(key("G"), sectionA, iso(8), iso(8, 30)),
                occ(key("G"), sectionB, iso(8, 30), iso(9)));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), driver, driverQual,
                                conductor, conductorQual)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("CREW_QUALIFICATION_GAP");
        JsonNode gap = error.get("details").get(0);
        assertThat(gap.get("role").asText()).isEqualTo("DRIVER");
        assertThat(gap.get("reason").asText()).isEqualTo("SECTION_NOT_COVERED");
        assertThat(gap.get("missingSections")).hasSize(1);
        assertThat(gap.get("missingSections").get(0).asText()).isEqualTo(sectionB);
        assertThat(getPlanStatus(scheduleKey)).isEqualTo("DRAFT");
    }

    @Test
    void publishRejectsUnknownQualAndCrewMismatch() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(key("G"), "SEC-A", iso(8), iso(9)));

        // 资质不存在
        MvcResult notFound = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), key("CREW"), key("QUAL"),
                                key("CREW"), key("QUAL"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(notFound);
        assertThat(error.get("details")).hasSize(2);
        assertThat(error.get("details").get(0).get("reason").asText())
                .isEqualTo("QUAL_NOT_FOUND");

        // 资质属于其他乘务员
        String qual = key("QUAL");
        createQual(qual, key("CREW"), "SEC-A", "2026-12-31T16:00:00Z");
        String conductorQual = key("QUAL");
        String conductor = key("CREW");
        createQual(conductorQual, conductor, "SEC-A", "2026-12-31T16:00:00Z");
        MvcResult mismatch = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), key("CREW"), qual,
                                conductor, conductorQual)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(mismatch).get("details").get(0).get("reason").asText())
                .isEqualTo("CREW_MISMATCH");
    }

    @Test
    void publishCrewGapFailureRollsBackAndDoesNotConsumeKey() throws Exception {
        String section = key("SEC");
        String driver = key("CREW");
        String conductor = key("CREW");
        String driverQual = key("QUAL");
        String conductorQual = key("QUAL");
        // 司机资质在计划终到前到期
        createQual(driverQual, driver, section, "2026-09-30T16:00:00Z");
        createQual(conductorQual, conductor, section, "2026-12-31T16:00:00Z");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(key("G"), section, iso(8), iso(9)));

        String publishKey = key("REQ");
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, driver, driverQual,
                                conductor, conductorQual)))
                .andExpect(status().isUnprocessableEntity());
        // 整单回滚：计划保持草稿，无乘务快照
        assertThat(getPlanStatus(scheduleKey)).isEqualTo("DRAFT");
        MvcResult crew = mvc.perform(get("/api/v1/plans/{key}/crew", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(crew)).isEmpty();

        // 失败不占键：延长司机资质后同键重发成功
        mvc.perform(put("/api/v1/crew-qualifications/{code}", driverQual)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateQualBody(key("REQ"), 1, driver, section,
                                "2026-12-31T16:00:00Z")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, driver, driverQual,
                                conductor, conductorQual)))
                .andExpect(status().isOk());

        // 乘务快照按角色写入
        MvcResult crewAfter = mvc.perform(get("/api/v1/plans/{key}/crew", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode crewList = read(crewAfter);
        assertThat(crewList).hasSize(2);
        assertThat(crewList.get(0).get("role").asText()).isEqualTo("CONDUCTOR");
        assertThat(crewList.get(0).get("crewId").asText()).isEqualTo(conductor);
        assertThat(crewList.get(1).get("role").asText()).isEqualTo("DRIVER");
        assertThat(crewList.get(1).get("crewId").asText()).isEqualTo(driver);
    }

    // ---------- 提前终止：回查风险记录，不自动取消 ----------

    @Test
    void terminateWritesRiskRecordsForFuturePublishedPlansOnly() throws Exception {
        String section = key("SEC");
        String driver = key("CREW");
        String conductor = key("CREW");
        String driverQual = key("QUAL");
        String conductorQual = key("QUAL");
        createQual(driverQual, driver, section, "2026-12-31T16:00:00Z");
        createQual(conductorQual, conductor, section, "2026-12-31T16:00:00Z");

        // 未来已发布计划（占用未结束）→ 命中回查
        String futurePlan = key("SCH");
        createPlan(futurePlan, occ(key("G"), section, iso(8), iso(9)));
        publishPlan(futurePlan, driver, driverQual, conductor, conductorQual);
        // 过去已发布计划（占用已结束，相对固定时钟 2026-09-26）→ 不命中
        String pastPlan = key("SCH");
        createPlanOnDate(pastPlan, LocalDate.of(2026, 9, 20),
                occ(key("G"), section, isoOn(LocalDate.of(2026, 9, 20), 8),
                        isoOn(LocalDate.of(2026, 9, 20), 9)));
        publishPlan(pastPlan, driver, driverQual, conductor, conductorQual);
        // 草稿计划 → 不命中
        String draftPlan = key("SCH");
        createPlan(draftPlan, occ(key("G"), section, iso(10), iso(11)));

        MvcResult terminated = terminateQual(driverQual, 1);
        JsonNode qual = read(terminated);
        assertThat(qual.get("terminated").asBoolean()).isTrue();
        assertThat(qual.get("version").asInt()).isEqualTo(2);

        // 未来计划写入司机角色风险记录，计划不自动取消
        MvcResult risks = mvc.perform(get("/api/v1/plans/{key}/crew-risks", futurePlan))
                .andExpect(status().isOk()).andReturn();
        JsonNode riskList = read(risks);
        assertThat(riskList).hasSize(1);
        JsonNode risk = riskList.get(0);
        assertThat(risk.get("scheduleKey").asText()).isEqualTo(futurePlan);
        assertThat(risk.get("role").asText()).isEqualTo("DRIVER");
        assertThat(risk.get("crewId").asText()).isEqualTo(driver);
        assertThat(risk.get("qualCode").asText()).isEqualTo(driverQual);
        assertThat(risk.get("reason").asText()).isEqualTo("QUAL_TERMINATED");
        assertThat(getPlanStatus(futurePlan)).isEqualTo("PUBLISHED");

        // 过去计划与草稿计划无风险记录
        MvcResult pastRisks = mvc.perform(get("/api/v1/plans/{key}/crew-risks", pastPlan))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(pastRisks)).isEmpty();
        MvcResult draftRisks = mvc.perform(get("/api/v1/plans/{key}/crew-risks", draftPlan))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(draftRisks)).isEmpty();
    }

    @Test
    void terminateChecksVersionStateAndReplaysIdempotently() throws Exception {
        String qualCode = key("QUAL");
        createQual(qualCode, key("CREW"), "SEC-A", "2026-12-31T16:00:00Z");

        // 版本不匹配 → 409
        mvc.perform(post("/api/v1/crew-qualifications/{code}/terminate", qualCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(terminateBody(key("REQ"), 7)))
                .andExpect(status().isConflict());

        String requestKey = key("REQ");
        String body = terminateBody(requestKey, 1);
        MvcResult first = mvc.perform(post("/api/v1/crew-qualifications/{code}/terminate",
                        qualCode).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放返回首次结果
        MvcResult replay = mvc.perform(post("/api/v1/crew-qualifications/{code}/terminate",
                        qualCode).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 已终止不可重复终止（新幂等键）→ 409
        mvc.perform(post("/api/v1/crew-qualifications/{code}/terminate", qualCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(terminateBody(key("REQ"), 2)))
                .andExpect(status().isConflict());

        // 不存在的资质 → 404
        mvc.perform(post("/api/v1/crew-qualifications/{code}/terminate", key("QUAL"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(terminateBody(key("REQ"), 1)))
                .andExpect(status().isNotFound());
    }

    // ---------- 风险持续门禁 ----------

    @Test
    void riskStateBlocksNormalRescheduleUntilBothRolesReplaced() throws Exception {
        String section = key("SEC");
        String driver = key("CREW");
        String conductor = key("CREW");
        String driverQual = key("QUAL");
        String conductorQual = key("QUAL");
        createQual(driverQual, driver, section, "2026-12-31T16:00:00Z");
        createQual(conductorQual, conductor, section, "2026-12-31T16:00:00Z");
        String oldPlan = key("SCH");
        createPlan(oldPlan, occ(key("G"), section, iso(8), iso(9)));
        publishPlan(oldPlan, driver, driverQual, conductor, conductorQual);
        terminateQual(driverQual, 1);

        // 普通改签（不带乘务）→ 409 风险门禁
        String newPlan = key("SCH");
        createPlan(newPlan, occ(key("G"), section, iso(8), iso(9)));
        MvcResult blocked = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newPlan, 1, 1)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(blocked).get("code").asText()).isEqualTo("RISK_STATE_CONFLICT");
        assertThat(getPlanStatus(oldPlan)).isEqualTo("PUBLISHED");
        assertThat(getPlanStatus(newPlan)).isEqualTo("DRAFT");

        // 仍指定被终止的司机 → 409
        MvcResult sameCrew = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBodyWithCrew(key("REQ"), newPlan, 1, 1,
                                driver, driverQual, conductor, conductorQual)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(sameCrew).get("code").asText()).isEqualTo("RISK_STATE_CONFLICT");

        // 两角色均替换为合格人员 → 放行
        String newDriver = key("CREW");
        String newDriverQual = key("QUAL");
        createQual(newDriverQual, newDriver, section, "2026-12-31T16:00:00Z");
        MvcResult rescheduled = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBodyWithCrew(key("REQ"), newPlan, 1, 1,
                                newDriver, newDriverQual, conductor, conductorQual)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(rescheduled);
        assertThat(body.get("oldPlan").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("newPlan").get("status").asText()).isEqualTo("PUBLISHED");

        // 新计划乘务快照为替换后人员；旧计划风险记录不可变保留
        MvcResult newCrew = mvc.perform(get("/api/v1/plans/{key}/crew", newPlan))
                .andExpect(status().isOk()).andReturn();
        JsonNode crewList = read(newCrew);
        assertThat(crewList).hasSize(2);
        assertThat(crewList.get(1).get("crewId").asText()).isEqualTo(newDriver);
        MvcResult oldRisks = mvc.perform(get("/api/v1/plans/{key}/crew-risks", oldPlan))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(oldRisks)).hasSize(1);
    }

    @Test
    void riskStateBlocksSameTrainNewSegmentUntilResolved() throws Exception {
        String sectionA = key("SEC");
        String sectionB = key("SEC");
        String train = "G-" + UUID.randomUUID();
        String driver = key("CREW");
        String conductor = key("CREW");
        String driverQual = key("QUAL");
        String conductorQual = key("QUAL");
        createQual(driverQual, driver, sectionA + "," + sectionB, "2026-12-31T16:00:00Z");
        createQual(conductorQual, conductor, sectionA + "," + sectionB, "2026-12-31T16:00:00Z");
        String riskyPlan = key("SCH");
        createPlan(riskyPlan, occ(train, sectionA, iso(8), iso(9)));
        publishPlan(riskyPlan, driver, driverQual, conductor, conductorQual);
        terminateQual(driverQual, 1);

        // 同车底新增段（同列车、不同区段、无时隙冲突、乘务合格）→ 409 风险门禁
        String newDriver = key("CREW");
        String newDriverQual = key("QUAL");
        createQual(newDriverQual, newDriver, sectionB, "2026-12-31T16:00:00Z");
        String newSegment = key("SCH");
        createPlan(newSegment, occ(train, sectionB, iso(10), iso(11)));
        String publishKey = key("REQ");
        MvcResult blocked = mvc.perform(post("/api/v1/plans/{key}/publish", newSegment)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, newDriver, newDriverQual,
                                conductor, conductorQual)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(blocked).get("code").asText()).isEqualTo("RISK_STATE_CONFLICT");
        assertThat(getPlanStatus(newSegment)).isEqualTo("DRAFT");

        // 风险计划取消后同车底新段放行；失败不占键，同键重发成功
        cancelPlan(riskyPlan);
        mvc.perform(post("/api/v1/plans/{key}/publish", newSegment)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, newDriver, newDriverQual,
                                conductor, conductorQual)))
                .andExpect(status().isOk());
    }

    // ---------- 缺口诊断查询 ----------

    @Test
    void gapDiagnosisMatchesPublishGate() throws Exception {
        String sectionA = key("SEC");
        String sectionB = key("SEC");
        String driver = key("CREW");
        String conductor = key("CREW");
        String driverQual = key("QUAL");
        String conductorQual = key("QUAL");
        createQual(driverQual, driver, sectionA, "2026-12-31T16:00:00Z");
        createQual(conductorQual, conductor, sectionA + "," + sectionB,
                "2026-12-31T16:00:00Z");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey,
                occ(key("G"), sectionA, iso(8), iso(8, 30)),
                occ(key("G"), sectionB, iso(8, 30), iso(9)));

        // 诊断列出司机缺口，与发布门禁一致
        MvcResult diagnosis = mvc.perform(get("/api/v1/plans/{key}/crew-gap-diagnosis",
                        scheduleKey)
                        .param("driverCrewId", driver).param("driverQualCode", driverQual)
                        .param("conductorCrewId", conductor)
                        .param("conductorQualCode", conductorQual))
                .andExpect(status().isOk()).andReturn();
        JsonNode gaps = read(diagnosis);
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).get("role").asText()).isEqualTo("DRIVER");
        assertThat(gaps.get(0).get("reason").asText()).isEqualTo("SECTION_NOT_COVERED");

        // 合格组合诊断为空
        String fullDriver = key("CREW");
        String fullDriverQual = key("QUAL");
        createQual(fullDriverQual, fullDriver, sectionA + "," + sectionB,
                "2026-12-31T16:00:00Z");
        MvcResult ok = mvc.perform(get("/api/v1/plans/{key}/crew-gap-diagnosis", scheduleKey)
                        .param("driverCrewId", fullDriver)
                        .param("driverQualCode", fullDriverQual)
                        .param("conductorCrewId", conductor)
                        .param("conductorQualCode", conductorQual))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(ok)).isEmpty();

        // 同一乘务员同时任两角色 → 角色互异缺口
        MvcResult sameCrew = mvc.perform(get("/api/v1/plans/{key}/crew-gap-diagnosis",
                        scheduleKey)
                        .param("driverCrewId", conductor).param("driverQualCode", conductorQual)
                        .param("conductorCrewId", conductor)
                        .param("conductorQualCode", conductorQual))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(sameCrew)).hasSize(2);
    }

    // ---------- 辅助 ----------

    private void createQual(String qualCode, String crewId, String sectionsCsv, String expiresIso)
            throws Exception {
        mvc.perform(post("/api/v1/crew-qualifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(qualBody(key("REQ"), qualCode, crewId, sectionsCsv, expiresIso)))
                .andExpect(status().isCreated());
    }

    private MvcResult terminateQual(String qualCode, int expectedVersion) throws Exception {
        return mvc.perform(post("/api/v1/crew-qualifications/{code}/terminate", qualCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(terminateBody(key("REQ"), expectedVersion)))
                .andExpect(status().isOk()).andReturn();
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        createPlanOnDate(scheduleKey, DAY, occupancies);
    }

    private void createPlanOnDate(String scheduleKey, LocalDate opDate, String... occupancies)
            throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + opDate + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey, String driver, String driverQual,
                             String conductor, String conductorQual) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), driver, driverQual, conductor,
                                conductorQual)))
                .andExpect(status().isOk());
    }

    private void cancelPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
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

    /** 运营日当日 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour) {
        return iso(hour, 0);
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        return isoOn(DAY, hour, minute);
    }

    private static String isoOn(LocalDate date, int hour) {
        return isoOn(date, hour, 0);
    }

    private static String isoOn(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String qualBody(String requestKey, String qualCode, String crewId,
                                   String sectionsCsv, String expiresIso) {
        StringBuilder sections = new StringBuilder();
        for (String s : sectionsCsv.split(",")) {
            if (sections.length() > 0) {
                sections.append(',');
            }
            sections.append('\"').append(s).append('\"');
        }
        return "{\"requestKey\":\"" + requestKey + "\",\"qualCode\":\"" + qualCode
                + "\",\"crewId\":\"" + crewId + "\",\"sections\":[" + sections
                + "],\"expiresUtc\":\"" + expiresIso + "\"}";
    }

    private static String updateQualBody(String requestKey, int expectedVersion, String crewId,
                                         String sectionsCsv, String expiresIso) {
        StringBuilder sections = new StringBuilder();
        for (String s : sectionsCsv.split(",")) {
            if (sections.length() > 0) {
                sections.append(',');
            }
            sections.append('\"').append(s).append('\"');
        }
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"crewId\":\"" + crewId + "\",\"sections\":[" + sections
                + "],\"expiresUtc\":\"" + expiresIso + "\"}";
    }

    private static String terminateBody(String requestKey, int expectedVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"operator\":\"op-" + requestKey + "\"}";
    }

    private static String publishBody(String requestKey, String driver, String driverQual,
                                      String conductor, String conductorQual) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"op-" + requestKey + "\""
                + ",\"driver\":{\"crewId\":\"" + driver + "\",\"qualCode\":\"" + driverQual + "\"}"
                + ",\"conductor\":{\"crewId\":\"" + conductor + "\",\"qualCode\":\""
                + conductorQual + "\"}}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }

    private static String rescheduleBodyWithCrew(String requestKey, String newScheduleKey,
                                                 int expectedOldVersion, int expectedNewVersion,
                                                 String driver, String driverQual,
                                                 String conductor, String conductorQual) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion
                + ",\"operator\":\"op-" + requestKey + "\""
                + ",\"driver\":{\"crewId\":\"" + driver + "\",\"qualCode\":\"" + driverQual + "\"}"
                + ",\"conductor\":{\"crewId\":\"" + conductor + "\",\"qualCode\":\""
                + conductorQual + "\"}}";
    }
}
