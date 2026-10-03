package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 乘务资格门禁端到端测试（H2 内存库）：主流程、资格边界、等级不足、人员冲突、
 * 整单回滚、幂等重放与历史快照保留。各用例使用唯一业务键，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanCrewQualificationTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 主流程与历史快照 ----------

    @Test
    void publishWithCrewMainFlowAndSnapshotPreservedAfterRevoke() throws Exception {
        String section = key("SEC");
        jdbc.update("INSERT INTO rail_section_grade (section_id, required_grade) VALUES (?, ?)",
                section, 3);
        String memberA = key("M");
        String memberB = key("M");
        grant(memberA, 3, iso(8), iso(12));
        grant(memberB, 3, iso(8), iso(12));
        String group = key("G");
        createGroup(group, memberB, memberA);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(10), iso(11)));
        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), group)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(published).get("status").asText()).isEqualTo("PUBLISHED");

        // 逐人核查：按人员稳定排序，覆盖通过，占用窗口等于计划行程
        JsonNode check = crewCheck(scheduleKey, null);
        assertThat(check.get("requiredGrade").asInt()).isEqualTo(3);
        assertThat(check.get("journeyStartUtc").asText()).isEqualTo(iso(10));
        assertThat(check.get("journeyEndUtc").asText()).isEqualTo(iso(11));
        assertThat(check.get("members")).hasSize(2);
        assertThat(check.get("members").get(0).get("memberCode").asText()).isEqualTo(memberA);
        assertThat(check.get("members").get(1).get("memberCode").asText()).isEqualTo(memberB);
        for (JsonNode member : check.get("members")) {
            assertThat(member.get("covered").asBoolean()).isTrue();
            assertThat(member.get("snapshot").asBoolean()).isTrue();
            assertThat(member.get("qualVersion").asInt()).isEqualTo(1);
            assertThat(member.get("occupyStartUtc").asText()).isEqualTo(iso(10));
            assertThat(member.get("occupyEndUtc").asText()).isEqualTo(iso(11));
            assertThat(member.get("conflictingPlans")).isEmpty();
        }

        // 撤销资格后历史发布仍保留当时的资格版本与人员名单
        revoke(memberA);
        grant(memberA, 3, iso(8), iso(20));
        JsonNode after = crewCheck(scheduleKey, null);
        assertThat(after.get("members")).hasSize(2);
        JsonNode memberAView = after.get("members").get(0);
        assertThat(memberAView.get("memberCode").asText()).isEqualTo(memberA);
        assertThat(memberAView.get("snapshot").asBoolean()).isTrue();
        assertThat(memberAView.get("qualVersion").asInt()).isEqualTo(1);
        assertThat(memberAView.get("covered").asBoolean()).isTrue();
    }

    // ---------- 资格区间边界（题例：10:00-11:00 行程） ----------

    @Test
    void qualificationEndingExactlyAtJourneyEndPublishes() throws Exception {
        String member = key("M");
        // 资格 11:00 结束（左闭右开），完整覆盖 10:00-11:00 行程
        grant(member, 1, iso(9), iso(11));
        String group = key("G");
        createGroup(group, member);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(10), iso(11)));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), group)))
                .andExpect(status().isOk());
    }

    @Test
    void qualificationEndingBeforeJourneyEndFailsWholeOrder() throws Exception {
        String section = key("SEC");
        String member = key("M");
        // 资格 10:59 结束，不覆盖 10:00-11:00 行程
        grant(member, 1, iso(9), iso(10, 59));
        String group = key("G");
        createGroup(group, member);

        String scheduleKey = key("SCH");
        String publishKey = key("REQ");
        createPlan(scheduleKey, occ("G1", section, iso(10), iso(11)));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, group)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("CREW_QUALIFICATION_CONFLICT");
        assertThat(error.get("details").get(0).get("type").asText())
                .isEqualTo("QUALIFICATION_EXPIRED");
        assertThat(error.get("details").get(0).get("memberCode").asText()).isEqualTo(member);

        // 整单不占线路时隙或乘务资源
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
        assertThat(countAssignments(scheduleKey)).isZero();
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).isEmpty();

        // 草稿实时核查可见逐人失败原因
        JsonNode check = crewCheck(scheduleKey, group);
        assertThat(check.get("members").get(0).get("covered").asBoolean()).isFalse();
        assertThat(check.get("members").get(0).get("failureReason").asText())
                .isEqualTo("QUALIFICATION_EXPIRED");
        assertThat(check.get("members").get(0).get("snapshot").asBoolean()).isFalse();

        // 失败不缓存：补足资格后同键重发成功
        grant(member, 1, iso(9), iso(12));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, group)))
                .andExpect(status().isOk());
        assertThat(countAssignments(scheduleKey)).isEqualTo(1);
    }
    // ---------- 等级不足与人员冲突 ----------

    @Test
    void insufficientLineGradeFails() throws Exception {
        String section = key("SEC");
        jdbc.update("INSERT INTO rail_section_grade (section_id, required_grade) VALUES (?, ?)",
                section, 3);
        String member = key("M");
        grant(member, 2, iso(8), iso(12));
        String group = key("G");
        createGroup(group, member);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(10), iso(11)));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), group)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("CREW_QUALIFICATION_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("GRADE_INSUFFICIENT");
        assertThat(detail.get("requiredGrade").asInt()).isEqualTo(3);
        assertThat(detail.get("qualLineGrade").asInt()).isEqualTo(2);
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
        assertThat(countAssignments(scheduleKey)).isZero();
    }

    @Test
    void crewConflictAcrossPlansAndAdjacentHandover() throws Exception {
        String section = key("SEC");
        String member = key("M");
        grant(member, 1, iso(8), iso(20));
        String group = key("G");
        createGroup(group, member);

        String first = key("SCH");
        createPlan(first, occ("G1", section, iso(10), iso(11)));
        publishOk(first, group);

        // 重叠计划同一成员 → 422 人员冲突（不同区段，隔离时隙冲突）
        String overlapping = key("SCH");
        createPlan(overlapping, occ("G2", key("SEC"), iso(10), iso(11)));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", overlapping)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), group)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("CREW_QUALIFICATION_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("CREW_CONFLICT");
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(first);
        assertThat(planStatus(overlapping)).isEqualTo("DRAFT");
        assertThat(countAssignments(overlapping)).isZero();

        // 草稿核查展示冲突计划
        JsonNode check = crewCheck(overlapping, group);
        JsonNode memberView = check.get("members").get(0);
        assertThat(memberView.get("covered").asBoolean()).isFalse();
        assertThat(memberView.get("failureReason").asText()).isEqualTo("CREW_CONFLICT");
        assertThat(memberView.get("conflictingPlans").get(0).asText()).isEqualTo(first);

        // 相邻计划端点可交接：11:00 紧接合法
        String adjacent = key("SCH");
        createPlan(adjacent, occ("G3", section, iso(11), iso(12)));
        publishOk(adjacent, group);

        // 已发布计划的核查中可见来自相邻计划的占用窗口不冲突
        JsonNode firstCheck = crewCheck(first, null);
        assertThat(firstCheck.get("members").get(0).get("conflictingPlans")).isEmpty();
    }

    @Test
    void multiMemberFailureRollsBackWholeOrder() throws Exception {
        String section = key("SEC");
        String qualified = key("M");
        String expired = key("M");
        grant(qualified, 1, iso(8), iso(20));
        grant(expired, 1, iso(8), iso(10, 30));
        String group = key("G");
        createGroup(group, qualified, expired);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(10), iso(11)));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), group)))
                .andExpect(status().isUnprocessableEntity());

        // 任一成员失败整单回滚：合格成员也不占乘务资源，时隙不生效
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
        assertThat(countAssignments(scheduleKey)).isZero();
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).isEmpty();

        // 修正后整单可发布，两名成员都写入快照
        grant(expired, 1, iso(8), iso(20));
        publishOk(scheduleKey, group);
        assertThat(countAssignments(scheduleKey)).isEqualTo(2);
    }

    // ---------- 幂等与状态边界 ----------

    @Test
    void publishIdempotentReplayAndCrewGroupChangeConflict() throws Exception {
        String member = key("M");
        grant(member, 1, iso(8), iso(20));
        String group = key("G");
        createGroup(group, member);
        String otherGroup = key("G");
        createGroup(otherGroup, member);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(10), iso(11)));
        String publishKey = key("REQ");
        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, group)))
                .andExpect(status().isOk()).andReturn();

        // 同一 requestId 重放首次人员与时隙分配
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, group)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        assertThat(countAssignments(scheduleKey)).isEqualTo(1);

        // 同一 requestId 换乘务组 → 409
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, otherGroup)))
                .andExpect(status().isConflict());
    }

    @Test
    void revokedQualificationCannotPublish() throws Exception {
        String member = key("M");
        grant(member, 1, iso(8), iso(20));
        revoke(member);
        String group = key("G");
        createGroup(group, member);

        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(10), iso(11)));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), group)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(rejected).get("details").get(0).get("type").asText())
                .isEqualTo("QUALIFICATION_EXPIRED");
        assertThat(planStatus(scheduleKey)).isEqualTo("DRAFT");
        assertThat(countAssignments(scheduleKey)).isZero();
    }
    // ---------- 乘务组与资格管理 ----------

    @Test
    void crewGroupCreateIdempotentAndValidated() throws Exception {
        String group = key("G");
        String requestKey = key("REQ");
        String body = groupBody(requestKey, group, key("M"), key("M"));
        MvcResult first = mvc.perform(post("/api/v1/crew-groups")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/crew-groups")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参 → 409；同组键不同键 → 409；成员重复 → 400
        mvc.perform(post("/api/v1/crew-groups").contentType(MediaType.APPLICATION_JSON)
                        .content(groupBody(requestKey, group, key("M"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/crew-groups").contentType(MediaType.APPLICATION_JSON)
                        .content(groupBody(key("REQ"), group, key("M"))))
                .andExpect(status().isConflict());
        String dup = key("M");
        mvc.perform(post("/api/v1/crew-groups").contentType(MediaType.APPLICATION_JSON)
                        .content(groupBody(key("REQ"), key("G"), dup, dup)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void qualificationGrantRevokeIdempotentAndValidated() throws Exception {
        String member = key("M");
        String grantKey = key("REQ");
        String body = grantBody(grantKey, member, 2, iso(8), iso(20));
        MvcResult granted = mvc.perform(post("/api/v1/qualifications/grant")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(granted).get("version").asInt()).isEqualTo(1);
        // 同键重放返回首次版本，不重复递增
        MvcResult replay = mvc.perform(post("/api/v1/qualifications/grant")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(granted));

        // 再次授予版本递增，旧版本作废
        MvcResult second = mvc.perform(post("/api/v1/qualifications/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(key("REQ"), member, 3, iso(8), iso(22))))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(second).get("version").asInt()).isEqualTo(2);

        // 失效不晚于生效 → 400
        mvc.perform(post("/api/v1/qualifications/grant").contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(key("REQ"), member, 1, iso(20), iso(8))))
                .andExpect(status().isBadRequest());

        // 撤销幂等：同键重放返回首次结果
        String revokeKey = key("REQ");
        MvcResult revoked = mvc.perform(post("/api/v1/qualifications/revoke")
                        .contentType(MediaType.APPLICATION_JSON).content(revokeBody(revokeKey, member)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(revoked).get("status").asText()).isEqualTo("REVOKED");
        assertThat(read(revoked).get("version").asInt()).isEqualTo(2);
        MvcResult revokeReplay = mvc.perform(post("/api/v1/qualifications/revoke")
                        .contentType(MediaType.APPLICATION_JSON).content(revokeBody(revokeKey, member)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(revokeReplay)).isEqualTo(read(revoked));

        // 无有效资格再撤销 → 404
        mvc.perform(post("/api/v1/qualifications/revoke").contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody(key("REQ"), member)))
                .andExpect(status().isNotFound());
    }

    @Test
    void crewCheckEdgeCasesAndStableOrdering() throws Exception {
        // 草稿未指定乘务组 → 404；乘务组不存在 → 404；计划不存在 → 404
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(10), iso(11)));
        mvc.perform(get("/api/v1/plans/{key}/crew-check", scheduleKey))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/crew-check", scheduleKey)
                        .param("crewGroup", key("G")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/crew-check", key("SCH")))
                .andExpect(status().isNotFound());

        // 发布时登记不存在的乘务组 → 400
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), key("G"))))
                .andExpect(status().isBadRequest());

        // 成员按业务键升序稳定排序（建组顺序无关）
        String memberZ = "MZ-" + UUID.randomUUID();
        String memberA = "MA-" + UUID.randomUUID();
        grant(memberZ, 1, iso(8), iso(20));
        grant(memberA, 1, iso(8), iso(20));
        String group = key("G");
        createGroup(group, memberZ, memberA);
        JsonNode check = crewCheck(scheduleKey, group);
        assertThat(check.get("members")).hasSize(2);
        assertThat(check.get("members").get(0).get("memberCode").asText()).isEqualTo(memberA);
        assertThat(check.get("members").get(1).get("memberCode").asText()).isEqualTo(memberZ);
        assertThat(check.get("members").get(0).get("covered").asBoolean()).isTrue();
    }

    // ---------- 辅助 ----------

    private void createGroup(String group, String... members) throws Exception {
        mvc.perform(post("/api/v1/crew-groups").contentType(MediaType.APPLICATION_JSON)
                        .content(groupBody(key("REQ"), group, members)))
                .andExpect(status().isCreated());
    }

    private void grant(String member, int grade, String fromIso, String toIso) throws Exception {
        mvc.perform(post("/api/v1/qualifications/grant").contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(key("REQ"), member, grade, fromIso, toIso)))
                .andExpect(status().isCreated());
    }

    private void revoke(String member) throws Exception {
        mvc.perform(post("/api/v1/qualifications/revoke").contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody(key("REQ"), member)))
                .andExpect(status().isOk());
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, occupancies)))
                .andExpect(status().isCreated());
    }

    private void publishOk(String scheduleKey, String group) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), group)))
                .andExpect(status().isOk());
    }

    private JsonNode crewCheck(String scheduleKey, String group) throws Exception {
        var request = get("/api/v1/plans/{key}/crew-check", scheduleKey);
        if (group != null) {
            request = request.param("crewGroup", group);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private String planStatus(String scheduleKey) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(detail).get("status").asText();
    }

    private int countAssignments(String scheduleKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_crew_assignment a JOIN rail_day_plan p"
                        + " ON p.id = a.plan_id WHERE p.schedule_key = ?",
                Integer.class, scheduleKey);
        return count == null ? 0 : count;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour) {
        return iso(hour, 0);
    }

    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
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

    private static String publishBody(String requestKey, String crewGroup) {
        return "{\"requestKey\":\"" + requestKey + "\",\"crewGroup\":\"" + crewGroup + "\"}";
    }

    private static String groupBody(String requestKey, String groupCode, String... members) {
        StringBuilder sb = new StringBuilder("{\"requestKey\":\"").append(requestKey)
                .append("\",\"groupCode\":\"").append(groupCode).append("\",\"memberCodes\":[");
        for (int i = 0; i < members.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('\"').append(members[i]).append('\"');
        }
        return sb.append("]}").toString();
    }

    private static String grantBody(String requestKey, String member, int grade,
                                    String fromIso, String toIso) {
        return "{\"requestKey\":\"" + requestKey + "\",\"memberCode\":\"" + member
                + "\",\"lineGrade\":" + grade + ",\"validFromUtc\":\"" + fromIso
                + "\",\"validToUtc\":\"" + toIso + "\"}";
    }

    private static String revokeBody(String requestKey, String member) {
        return "{\"requestKey\":\"" + requestKey + "\",\"memberCode\":\"" + member + "\"}";
    }
}