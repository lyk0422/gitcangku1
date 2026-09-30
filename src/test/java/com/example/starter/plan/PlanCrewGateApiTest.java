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
 * 乘务资格门禁端到端测试（真实 H2 内存库，MODE=MySQL）：
 * 主流程、资格过期/等级不足/人员冲突 422、端点相邻交接、幂等重放与换组 409、
 * 撤销并发语义的事后验证、历史发布资格版本保留、crew-check 逐人查询稳定排序。
 * 时间一律使用绝对 UTC 时刻（Asia/Shanghai 日历日内）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanCrewGateApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    /** 计划窗口固定为 UTC 10:00-11:00（题面示例）。 */
    private static final Instant T10 = Instant.parse("2026-09-22T10:00:00Z");
    private static final Instant T1059 = Instant.parse("2026-09-22T10:59:00Z");
    private static final Instant T11 = Instant.parse("2026-09-22T11:00:00Z");
    private static final Instant T12 = Instant.parse("2026-09-22T12:00:00Z");
    private static final Instant T09 = Instant.parse("2026-09-22T09:00:00Z");
    private static final Instant T08 = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程：端点相等可发布 ----------

    @Test
    void qualificationEndingExactlyAtTripEndCanPublish() throws Exception {
        String section = key("SEC");
        String line = key("LINE");
        String member = key("M");
        registerSection(section, line, 2);
        registerMember(member, "乘务员甲");
        grant(member, line, 2, T09, T11);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, section, T10, T11);

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish-with-crew", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishCrewBody(key("REQ"), member)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("status").asText()).isEqualTo("PUBLISHED");
        JsonNode crew = body.get("crew");
        assertThat(crew.get("lineId").asText()).isEqualTo(line);
        assertThat(crew.get("requiredGrade").asInt()).isEqualTo(2);
        assertThat(crew.get("members")).hasSize(1);
        JsonNode assignment = crew.get("members").get(0);
        assertThat(assignment.get("memberKey").asText()).isEqualTo(member);
        assertThat(assignment.get("qualificationVersion").asInt()).isEqualTo(1);
        assertThat(assignment.get("gradeLevel").asInt()).isEqualTo(2);
        assertThat(assignment.get("validFromUtc").asText()).isEqualTo(T09.toString());
        assertThat(assignment.get("validToUtc").asText()).isEqualTo(T11.toString());

        // 已发布时隙生效
        JsonNode slots = read(mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn());
        assertThat(slots).hasSize(1);
    }

    // ---------- 失败：资格 10:59 结束整单 422，站台与人员均不占用 ----------

    @Test
    void qualificationEndingOneMinuteBeforeTripEndRejectsAndOccupiesNothing() throws Exception {
        String section = key("SEC");
        String line = key("LINE");
        String member = key("M");
        registerSection(section, line, 2);
        registerMember(member, "乘务员甲");
        grant(member, line, 2, T09, T1059);
        String scheduleKey = key("SCH");
        String publishKey = key("REQ");
        createPlan(scheduleKey, section, T10, T11);

        MvcResult failed = mvc.perform(post("/api/v1/plans/{key}/publish-with-crew", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishCrewBody(publishKey, member)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(failed);
        assertThat(error.get("code").asText()).isEqualTo("CREW_GATE");
        assertThat(error.get("details")).anyMatch(d ->
                "QUALIFICATION_EXPIRED".equals(d.get("type").asText())
                        && member.equals(d.get("memberKey").asText()));

        // 计划保持草稿
        JsonNode detail = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(detail.get("status").asText()).isEqualTo("DRAFT");
        assertThat(detail.get("crew").isNull()).isTrue();

        // 站台时隙不占用
        JsonNode slots = read(mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn());
        assertThat(slots).isEmpty();

        // 乘务资源不占用：另一张 11:00-12:00 计划的预检中该人员无冲突、未被占用
        String otherPlan = key("SCH");
        createPlan(otherPlan, section, T11, T12);
        JsonNode check = read(mvc.perform(post("/api/v1/plans/{key}/crew-check", otherPlan)
                        .contentType(MediaType.APPLICATION_JSON).content(crewBody(member)))
                .andExpect(status().isOk()).andReturn());
        JsonNode row = check.get("crew").get(0);
        assertThat(row.get("occupied").asBoolean()).isFalse();
        assertThat(row.get("conflicts")).isEmpty();

        // 失败不缓存：换发覆盖全程的新版本后，同一 requestKey 重试成功
        grant(member, line, 2, T09, T12);
        mvc.perform(post("/api/v1/plans/{key}/publish-with-crew", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishCrewBody(publishKey, member)))
                .andExpect(status().isOk());
    }

    // ---------- 失败：等级不足 422 ----------

    @Test
    void insufficientGradeRejected() throws Exception {
        String section = key("SEC");
        String line = key("LINE");
        String member = key("M");
        registerSection(section, line, 3);
        registerMember(member, "乘务员乙");
        grant(member, line, 2, T08, T12);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, section, T10, T11);

        MvcResult failed = mvc.perform(post("/api/v1/plans/{key}/publish-with-crew", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishCrewBody(key("REQ"), member)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(failed);
        assertThat(error.get("code").asText()).isEqualTo("CREW_GATE");
        assertThat(error.get("details")).anyMatch(d ->
                "GRADE_INSUFFICIENT".equals(d.get("type").asText())
                        && d.get("requiredGrade").asInt() == 3
                        && d.get("gradeLevel").asInt() == 2);
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("DRAFT");
    }

    // ---------- 人员冲突：重叠 422、相邻可交接、取消后释放 ----------

    @Test
    void overlappingCrewConflictButAdjacentEndpointHandsOver() throws Exception {
        String line = key("LINE");
        String member = key("M");
        String sectionA = key("SEC-A");
        String sectionB = key("SEC-B");
        String sectionC = key("SEC-C");
        registerSection(sectionA, line, 1);
        registerSection(sectionB, line, 1);
        registerSection(sectionC, line, 1);
        registerMember(member, "乘务员甲");
        grant(member, line, 1, T08, T12);

        String planA = key("SCH");
        String planB = key("SCH");
        String planC = key("SCH");
        createPlan(planA, sectionA, T10, T11);
        createPlan(planB, sectionB, T11, T12);
        createPlan(planC, sectionC, T10, T11);

        publishWithCrew(planA, key("REQ"), member);

        // 相邻端点 11:00 交接，合法
        publishWithCrew(planB, key("REQ"), member);

        // 同窗口重叠 → 422，返回冲突计划
        MvcResult failed = mvc.perform(post("/api/v1/plans/{key}/publish-with-crew", planC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishCrewBody(key("REQ"), member)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(failed);
        assertThat(error.get("details")).anyMatch(d ->
                "CREW_CONFLICT".equals(d.get("type").asText())
                        && planA.equals(d.get("conflictingScheduleKey").asText()));
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", planC))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("DRAFT");

        // 取消 A 后人员窗口释放，C 可发布（B 的 11-12 与 C 的 10-11 仅端点相邻）
        cancelPlan(planA, key("REQ"));
        publishWithCrew(planC, key("REQ"), member);
    }

    // ---------- 幂等：同键同组重放首次快照，换组 409 ----------

    @Test
    void publishReplaySameCrewReturnsFirstSnapshotDifferentCrewConflicts() throws Exception {
        String section = key("SEC");
        String line = key("LINE");
        String memberA = key("MA");
        String memberB = key("MB");
        registerSection(section, line, 1);
        registerMember(memberA, "甲");
        registerMember(memberB, "乙");
        grant(memberA, line, 1, T08, T12);
        grant(memberB, line, 1, T08, T12);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, section, T10, T11);
        String requestKey = key("REQ");
        String body = publishCrewBody(requestKey, memberA);

        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/publish-with-crew", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 计划已发布后，同键同组重放仍返回首次成功结果（而非状态冲突）
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish-with-crew", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同一 requestKey 换乘务组 → 409
        mvc.perform(post("/api/v1/plans/{key}/publish-with-crew", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishCrewBody(requestKey, memberB)))
                .andExpect(status().isConflict());
    }

    // ---------- 撤销并发语义的事后验证 + 历史版本保留 ----------

    @Test
    void revocationAfterPublishKeepsSnapshotAndBlocksNewPublish() throws Exception {
        String section = key("SEC");
        String line = key("LINE");
        String member = key("M");
        registerSection(section, line, 1);
        registerMember(member, "乘务员甲");
        grant(member, line, 1, T08, T12);
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, section, T10, T11);
        publishWithCrew(scheduleKey, key("REQ"), member);

        // 撤销当前 ACTIVE 版本
        mvc.perform(post("/api/v1/crew-members/{m}/qualifications/{l}/versions/1/revoke",
                        member, line).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        // 重复撤销应 409（资格已非 ACTIVE）
        mvc.perform(post("/api/v1/crew-members/{m}/qualifications/{l}/versions/1/revoke",
                        member, line).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict());

        // 历史发布保留当时资格版本与名单，不随撤销改写
        JsonNode detail = read(mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn());
        assertThat(detail.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(detail.get("crew").get("members").get(0).get("qualificationVersion").asInt())
                .isEqualTo(1);

        // 基于已失效资格的新发布必须 422
        String nextPlan = key("SCH");
        createPlan(nextPlan, section, T11, T12);
        mvc.perform(post("/api/v1/plans/{key}/publish-with-crew", nextPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishCrewBody(key("REQ"), member)))
                .andExpect(status().isUnprocessableEntity());

        // 换发新版本后可再次发布
        grant(member, line, 1, T08, null);
        publishWithCrew(nextPlan, key("REQ2"), member);
    }

    // ---------- crew-check 逐人查询，按工号稳定排序 ----------

    @Test
    void crewCheckShowsCoverageWindowsAndConflictsSortedByMember() throws Exception {
        String section = key("SEC");
        String line = key("LINE");
        String memberA = key("MA");
        String memberB = key("MB");
        String memberC = key("MC");
        registerSection(section, line, 2);
        registerMember(memberA, "甲");
        registerMember(memberB, "乙");
        registerMember(memberC, "丙");
        grant(memberA, line, 2, T08, T12);
        grant(memberB, line, 1, T08, T12); // 等级不足
        // 丙不授予资格
        String planA = key("SCH");
        String planX = key("SCH");
        createPlan(planA, section, T10, T11);
        createPlan(planX, section, T10, T11);
        publishWithCrew(planA, key("REQ"), memberA);

        JsonNode check = read(mvc.perform(post("/api/v1/plans/{key}/crew-check", planX)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(crewBody(memberC, memberA, memberB)))
                .andExpect(status().isOk()).andReturn());
        assertThat(check.get("lineId").asText()).isEqualTo(line);
        assertThat(check.get("requiredGrade").asInt()).isEqualTo(2);
        assertThat(check.get("tripStartUtc").asText()).isEqualTo(T10.toString());
        assertThat(check.get("tripEndUtc").asText()).isEqualTo(T11.toString());
        JsonNode crew = check.get("crew");
        assertThat(crew).hasSize(3);
        // 稳定排序：按 memberKey 升序
        assertThat(crew.get(0).get("memberKey").asText()).isEqualTo(memberA);
        assertThat(crew.get(1).get("memberKey").asText()).isEqualTo(memberB);
        assertThat(crew.get(2).get("memberKey").asText()).isEqualTo(memberC);

        JsonNode a = crew.get(0);
        assertThat(a.get("intervalCovered").asBoolean()).isTrue();
        assertThat(a.get("gradeSufficient").asBoolean()).isTrue();
        assertThat(a.get("occupied").asBoolean()).isTrue();
        assertThat(a.get("windowStartUtc").asText()).isEqualTo(T10.toString());
        assertThat(a.get("windowEndUtc").asText()).isEqualTo(T11.toString());
        assertThat(a.get("conflicts").get(0).get("scheduleKey").asText()).isEqualTo(planA);

        JsonNode b = crew.get(1);
        assertThat(b.get("gradeSufficient").asBoolean()).isFalse();
        assertThat(b.get("occupied").asBoolean()).isFalse();

        JsonNode c = crew.get(2);
        assertThat(c.get("intervalCovered").asBoolean()).isFalse();
        assertThat(c.get("qualificationVersion").isNull()).isTrue();
    }

    // ---------- 参数与资源边界 ----------

    @Test
    void rejectsUnknownMemberUnknownSectionDuplicateCrew() throws Exception {
        String section = key("SEC");
        String line = key("LINE");
        registerSection(section, line, 1);
        String plan = key("SCH");
        createPlan(plan, section, T10, T11);

        // 未登记人员
        mvc.perform(post("/api/v1/plans/{key}/publish-with-crew", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishCrewBody(key("REQ"), key("GHOST"))))
                .andExpect(status().isUnprocessableEntity());

        // 乘务组为空 / 成员重复
        mvc.perform(post("/api/v1/plans/{key}/publish-with-crew", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"crew\":[]}"))
                .andExpect(status().isBadRequest());
        String dup = key("DUP");
        mvc.perform(post("/api/v1/plans/{key}/publish-with-crew", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishCrewBody(key("REQ"), dup, dup)))
                .andExpect(status().isBadRequest());

        // 行程区段未登记线路
        String plan2 = key("SCH");
        String ghostSection = key("GHOST-SEC");
        createPlan(plan2, ghostSection, T10, T11);
        registerMember(key("SOLO"), "丁");
        mvc.perform(post("/api/v1/plans/{key}/publish-with-crew", plan2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishCrewBody(key("REQ"), key("SOLO"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rescheduleReevaluatesGateAndKeepsOldSnapshot() throws Exception {
        String sectionOld = key("SEC-OLD");
        String sectionNew = key("SEC-NEW");
        String line = key("LINE");
        String member = key("M");
        registerSection(sectionOld, line, 1);
        registerSection(sectionNew, line, 2);
        registerMember(member, "乘务员甲");
        // v1：等级 1，仅覆盖到 11:00
        grant(member, line, 1, T08, T11);
        String oldPlan = key("SCH");
        createPlan(oldPlan, sectionOld, T10, T11);
        publishWithCrew(oldPlan, key("REQ"), member);

        // v2：等级 2，覆盖到 12:00；v1 变 SUPERSEDED，但旧计划快照保留 v1
        grant(member, line, 2, T08, T12);
        String newPlan = key("SCH");
        createPlan(newPlan, sectionNew, T11, T12);

        // 预检按当前 ACTIVE（v2）评估
        JsonNode check = read(mvc.perform(post("/api/v1/plans/{key}/crew-check", newPlan)
                        .contentType(MediaType.APPLICATION_JSON).content(crewBody(member)))
                .andExpect(status().isOk()).andReturn());
        assertThat(check.get("crew").get(0).get("qualificationVersion").asInt()).isEqualTo(2);

        // 改签携带乘务组：按 v2 门禁通过；旧计划取消、新计划发布
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleCrewBody(key("REQ"), newPlan, 1, 1, member)))
                .andExpect(status().isOk());

        JsonNode oldDetail = read(mvc.perform(get("/api/v1/plans/{key}", oldPlan))
                .andExpect(status().isOk()).andReturn());
        assertThat(oldDetail.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(oldDetail.get("crew").get("members").get(0).get("qualificationVersion").asInt())
                .isEqualTo(1);
        JsonNode newDetail = read(mvc.perform(get("/api/v1/plans/{key}", newPlan))
                .andExpect(status().isOk()).andReturn());
        assertThat(newDetail.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(newDetail.get("crew").get("requiredGrade").asInt()).isEqualTo(2);
        assertThat(newDetail.get("crew").get("members").get(0).get("qualificationVersion").asInt())
                .isEqualTo(2);

        // 旧计划取消后人员窗口释放：10:00-11:00 的第三个计划可由同乘务员发布（端点相邻）
        String third = key("SCH");
        createPlan(third, sectionOld, T10, T11);
        publishWithCrew(third, key("REQ3"), member);
    }

    @Test
    void qualificationVersioningSupersedesOldVersion() throws Exception {        String line = key("LINE");
        String member = key("M");
        registerMember(member, "甲");
        grant(member, line, 1, T08, T11);

        MvcResult second = mvc.perform(post("/api/v1/crew-members/{m}/qualifications", member)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(line, 2, T08, T12)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(second).get("version").asInt()).isEqualTo(2);
        assertThat(read(second).get("status").asText()).isEqualTo("ACTIVE");

        JsonNode versions = read(mvc.perform(get("/api/v1/crew-members/{m}/qualifications", member)
                        .param("lineId", line))
                .andExpect(status().isOk()).andReturn());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(versions.get(0).get("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(versions.get(1).get("gradeLevel").asInt()).isEqualTo(2);
    }

    // ---------- 辅助 ----------

    private void registerMember(String memberKey, String name) throws Exception {
        mvc.perform(post("/api/v1/crew-members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberKey\":\"" + memberKey + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated());
    }

    private void registerSection(String section, String line, int grade) throws Exception {
        mvc.perform(put("/api/v1/line-sections/{section}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineId\":\"" + line + "\",\"requiredGrade\":" + grade + "}"))
                .andExpect(status().isOk());
    }

    private void grant(String member, String line, int grade, Instant from, Instant to)
            throws Exception {
        mvc.perform(post("/api/v1/crew-members/{m}/qualifications", member)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(line, grade, from, to)))
                .andExpect(status().isCreated());
    }

    private void createPlan(String scheduleKey, String section, Instant start, Instant end)
            throws Exception {
        String occ = "{\"trainNo\":\"G-" + UUID.randomUUID() + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + start + "\",\"endUtc\":\"" + end + "\"}";
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY
                                + "\",\"occupancies\":[" + occ + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishWithCrew(String scheduleKey, String requestKey, String... members)
            throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish-with-crew", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishCrewBody(requestKey, members)))
                .andExpect(status().isOk());
    }

    private void cancelPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + requestKey + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String grantBody(String line, int grade, Instant from, Instant to) {
        String toJson = to == null ? null : "\"" + to + "\"";
        return "{\"lineId\":\"" + line + "\",\"gradeLevel\":" + grade
                + ",\"validFromUtc\":\"" + from + "\",\"validToUtc\":" + toJson + "}";
    }

    private static String publishCrewBody(String requestKey, String... members) {
        return "{\"requestKey\":\"" + requestKey + "\",\"crew\":[" + crewItems(members) + "]}";
    }

    private static String crewBody(String... members) {
        return "{\"crew\":[" + crewItems(members) + "]}";
    }

    private static String rescheduleCrewBody(String requestKey, String newPlan,
                                             int expectedOld, int expectedNew,
                                             String... members) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newPlan
                + "\",\"expectedOldVersion\":" + expectedOld
                + ",\"expectedNewVersion\":" + expectedNew
                + ",\"crew\":[" + crewItems(members) + "]}";
    }

    private static String crewItems(String[] members) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < members.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"memberKey\":\"").append(members[i]).append("\"}");
        }
        return sb.toString();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
