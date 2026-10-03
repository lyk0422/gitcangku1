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
 * 乘务资格门禁端到端测试（H2 内存库）：资格授予/撤销生命周期、发布门禁主流程与
 * 各类 422 失败分支、幂等重放与历史快照保留。各用例使用唯一业务键隔离数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CrewQualificationApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 资格授予/撤销生命周期 ----------

    @Test
    void grantRevokeAndRegrantLifecycle() throws Exception {
        String member = key("M");
        MvcResult granted = mvc.perform(post("/api/v1/crew-qualifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(key("REQ"), member, 2, iso(8), iso(20))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(granted);
        assertThat(body.get("crewMemberId").asText()).isEqualTo(member);
        assertThat(body.get("lineGrade").asInt()).isEqualTo(2);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("validToUtc").asText()).isEqualTo(iso(20));

        MvcResult queried = mvc.perform(get("/api/v1/crew-qualifications/{m}", member))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(queried)).isEqualTo(body);

        // 有效资格存在时重复授予（新幂等键）→ 409
        mvc.perform(post("/api/v1/crew-qualifications").contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(key("REQ"), member, 3, iso(8), iso(22))))
                .andExpect(status().isConflict());

        MvcResult revoked = mvc.perform(post("/api/v1/crew-qualifications/{m}/revoke", member)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode revokedBody = read(revoked);
        assertThat(revokedBody.get("status").asText()).isEqualTo("REVOKED");
        assertThat(revokedBody.get("version").asInt()).isEqualTo(2);

        // 已撤销再撤销（新幂等键）→ 409
        mvc.perform(post("/api/v1/crew-qualifications/{m}/revoke", member)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());

        // 撤销后再授予：版本加一，状态恢复有效
        MvcResult regranted = mvc.perform(post("/api/v1/crew-qualifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(key("REQ"), member, 3, iso(6), iso(23))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode regrantedBody = read(regranted);
        assertThat(regrantedBody.get("version").asInt()).isEqualTo(3);
        assertThat(regrantedBody.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(regrantedBody.get("lineGrade").asInt()).isEqualTo(3);
    }

    @Test
    void grantAndRevokeIdempotency() throws Exception {
        String member = key("M");
        String grantKey = key("REQ");
        String body = grantBody(grantKey, member, 1, iso(8), iso(20));
        MvcResult first = mvc.perform(post("/api/v1/crew-qualifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/crew-qualifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/crew-qualifications").contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(grantKey, member, 2, iso(8), iso(20))))
                .andExpect(status().isConflict());

        String revokeKey = key("REQ");
        MvcResult revoked = mvc.perform(post("/api/v1/crew-qualifications/{m}/revoke", member)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(revokeKey)))
                .andExpect(status().isOk()).andReturn();
        MvcResult revokeReplay = mvc.perform(post("/api/v1/crew-qualifications/{m}/revoke", member)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(revokeKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(revokeReplay)).isEqualTo(read(revoked));
    }

    @Test
    void grantRejectsInvalidWindowAndSectionGradeIdempotency() throws Exception {
        // 失效时刻等于/早于生效时刻 → 400
        mvc.perform(post("/api/v1/crew-qualifications").contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(key("REQ"), key("M"), 1, iso(10), iso(10))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/crew-qualifications").contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(key("REQ"), key("M"), 1, iso(11), iso(10))))
                .andExpect(status().isBadRequest());

        String section = key("SEC");
        String gradeKey = key("REQ");
        MvcResult registered = mvc.perform(put("/api/v1/sections/{s}/grade", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gradeBody(gradeKey, 3)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(registered).get("lineGrade").asInt()).isEqualTo(3);
        // 同键同参重放
        mvc.perform(put("/api/v1/sections/{s}/grade", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gradeBody(gradeKey, 3)))
                .andExpect(status().isOk());
        // 同键不同等级 → 409
        mvc.perform(put("/api/v1/sections/{s}/grade", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gradeBody(gradeKey, 4)))
                .andExpect(status().isConflict());
    }
    // ---------- 发布门禁：资格区间覆盖 ----------

    @Test
    void publishWithCrewQualificationEndingAtTripEndSucceeds() throws Exception {
        // 题干示例：计划 10:00-11:00，乘务员资格 11:00 结束可发布
        String member = key("M");
        grant(member, 1, iso(8), iso(11));
        String section = key("SEC");
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(10), iso(11)));

        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), member)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(published);
        assertThat(body.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(body.get("crewMembers")).hasSize(1);
        assertThat(body.get("crewMembers").get(0).asText()).isEqualTo(member);

        MvcResult check = mvc.perform(get("/api/v1/plans/{key}/crew-check", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode checkBody = read(check);
        assertThat(checkBody.get("requiredLineGrade").asInt()).isEqualTo(0);
        JsonNode item = checkBody.get("members").get(0);
        assertThat(item.get("crewMemberId").asText()).isEqualTo(member);
        assertThat(item.get("qualificationVersion").asInt()).isEqualTo(1);
        assertThat(item.get("covered").asBoolean()).isTrue();
        assertThat(item.get("tripStartUtc").asText()).isEqualTo(iso(10));
        assertThat(item.get("tripEndUtc").asText()).isEqualTo(iso(11));
        assertThat(item.get("conflictingScheduleKeys")).isEmpty();
    }

    @Test
    void publishWithQualificationEnding1059FailsAtomically() throws Exception {
        // 题干示例：资格 10:59 结束 → 整单 422，站台（区段时隙）与人员均不占用
        String member = key("M");
        grant(member, 1, iso(8), iso(10, 59));
        String section = key("SEC");
        String scheduleKey = key("SCH");
        String publishKey = key("REQ");
        createPlan(scheduleKey, occ("G1", section, iso(10), iso(11)));

        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, member)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("CREW_QUALIFICATION_DENIED");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("QUALIFICATION_WINDOW_INSUFFICIENT");
        assertThat(detail.get("crewMemberId").asText()).isEqualTo(member);

        // 计划保持草稿，区段时隙未占用
        MvcResult plan = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(plan).get("status").asText()).isEqualTo("DRAFT");
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).isEmpty();
        // 乘务资源未占用：无发布快照
        mvc.perform(get("/api/v1/plans/{key}/crew-check", scheduleKey))
                .andExpect(status().isConflict());

        // 失败不缓存：撤销旧资格并授予覆盖区间的新资格后，同 requestKey 重发成功
        revoke(member);
        grant(member, 1, iso(8), iso(11));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, member)))
                .andExpect(status().isOk());
    }

    @Test
    void publishRejectsInsufficientLineGrade() throws Exception {
        String section = key("SEC");
        mvc.perform(put("/api/v1/sections/{s}/grade", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gradeBody(key("REQ"), 3)))
                .andExpect(status().isOk());
        String member = key("M");
        grant(member, 2, iso(8), iso(20));
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(10), iso(11)));

        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), member)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("CREW_QUALIFICATION_DENIED");
        assertThat(error.get("details").get(0).get("type").asText())
                .isEqualTo("LINE_GRADE_INSUFFICIENT");

        // 等级达标后可发布
        revoke(member);
        grant(member, 3, iso(8), iso(20));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), member)))
                .andExpect(status().isOk());
    }

    @Test
    void publishRejectsMissingAndRevokedQualification() throws Exception {
        // 资格不存在
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(10), iso(11)));
        MvcResult missing = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), key("M"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(missing).get("details").get(0).get("type").asText())
                .isEqualTo("QUALIFICATION_MISSING");

        // 资格已撤销
        String member = key("M");
        grant(member, 1, iso(8), iso(20));
        revoke(member);
        String scheduleKey2 = key("SCH");
        createPlan(scheduleKey2, occ("G1", key("SEC"), iso(10), iso(11)));
        MvcResult revoked = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), member)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(revoked).get("details").get(0).get("type").asText())
                .isEqualTo("QUALIFICATION_REVOKED");
    }
    // ---------- 人员冲突与端点交接 ----------

    @Test
    void crewConflictOnOverlappingPlansAndAdjacentHandover() throws Exception {
        String member = key("M");
        grant(member, 1, iso(0), iso(23, 59));
        // 计划 A：10:00-11:00 发布，占用该成员
        String planA = key("SCH");
        createPlan(planA, occ("G1", key("SEC"), iso(10), iso(11)));
        publishPlan(planA, key("REQ"), member);

        // 计划 B：10:30-11:30 与 A 重叠（不同区段避免时隙冲突干扰）→ 422 人员冲突
        String planB = key("SCH");
        createPlan(planB, occ("G2", key("SEC"), iso(10, 30), iso(11, 30)));
        MvcResult conflict = mvc.perform(post("/api/v1/plans/{key}/publish", planB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(key("REQ"), member)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("CREW_QUALIFICATION_DENIED");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("CREW_CONFLICT");
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(planA);
        MvcResult planBAfter = mvc.perform(get("/api/v1/plans/{key}", planB))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(planBAfter).get("status").asText()).isEqualTo("DRAFT");

        // 计划 C：11:00-12:00 与 A 端点相邻，交接合法
        String planC = key("SCH");
        createPlan(planC, occ("G3", key("SEC"), iso(11), iso(12)));
        publishPlan(planC, key("REQ"), member);

        // A 的逐人占用窗口展示为 10:00-11:00，当前无冲突计划
        MvcResult check = mvc.perform(get("/api/v1/plans/{key}/crew-check", planA))
                .andExpect(status().isOk()).andReturn();
        JsonNode item = read(check).get("members").get(0);
        assertThat(item.get("tripStartUtc").asText()).isEqualTo(iso(10));
        assertThat(item.get("tripEndUtc").asText()).isEqualTo(iso(11));
        assertThat(item.get("conflictingScheduleKeys")).isEmpty();
    }

    // ---------- 发布幂等与历史快照 ----------

    @Test
    void publishIdempotentReplayAndCrewChangeConflict() throws Exception {
        String memberA = key("M");
        String memberB = key("M");
        grant(memberA, 1, iso(8), iso(20));
        grant(memberB, 1, iso(8), iso(20));
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(10), iso(11)));
        String publishKey = key("REQ");
        String body = publishBody(publishKey, memberA);

        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放：返回首次人员与时隙分配
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        assertThat(read(replay).get("crewMembers").get(0).asText()).isEqualTo(memberA);

        // 同键换乘务组 → 409
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(publishKey, memberB)))
                .andExpect(status().isConflict());
    }

    @Test
    void historyKeepsQualificationVersionAndCrewAfterRevokeAndCancel() throws Exception {
        String member = key("M");
        grant(member, 1, iso(8), iso(20));
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(10), iso(11)));
        publishPlan(scheduleKey, key("REQ"), member);

        // 发布后撤销资格：历史发布保留当时资格版本与人员名单
        revoke(member);
        MvcResult check = mvc.perform(get("/api/v1/plans/{key}/crew-check", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode item = read(check).get("members").get(0);
        assertThat(item.get("qualificationVersion").asInt()).isEqualTo(1);
        assertThat(item.get("crewMemberId").asText()).isEqualTo(member);
        MvcResult plan = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(plan).get("crewMembers").get(0).asText()).isEqualTo(member);

        // 取消后快照仍保留
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        MvcResult checkAfterCancel = mvc.perform(
                        get("/api/v1/plans/{key}/crew-check", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode cancelled = read(checkAfterCancel);
        assertThat(cancelled.get("planStatus").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("members").get(0).get("qualificationVersion").asInt())
                .isEqualTo(1);
    }

    @Test
    void crewCheckSortsMembersStablyAndRequiresSnapshot() throws Exception {
        String memberB = "MB-" + UUID.randomUUID();
        String memberA = "MA-" + UUID.randomUUID();
        grant(memberA, 1, iso(8), iso(20));
        grant(memberB, 1, iso(8), iso(20));
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", key("SEC"), iso(10), iso(11)));
        // 按 [B, A] 顺序登记，响应保持提交顺序，crew-check 按人员 ID 稳定升序
        publishPlan(scheduleKey, key("REQ"), memberB, memberA);

        MvcResult plan = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode crew = read(plan).get("crewMembers");
        assertThat(crew.get(0).asText()).isEqualTo(memberB);
        assertThat(crew.get(1).asText()).isEqualTo(memberA);

        MvcResult check = mvc.perform(get("/api/v1/plans/{key}/crew-check", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode members = read(check).get("members");
        assertThat(members.get(0).get("crewMemberId").asText()).isEqualTo(memberA);
        assertThat(members.get(1).get("crewMemberId").asText()).isEqualTo(memberB);

        // 草稿无快照 → 409；计划不存在 → 404
        String draft = key("SCH");
        createPlan(draft, occ("G1", key("SEC"), iso(10), iso(11)));
        mvc.perform(get("/api/v1/plans/{key}/crew-check", draft))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/plans/{key}/crew-check", key("SCH")))
                .andExpect(status().isNotFound());
    }
    // ---------- 辅助 ----------

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, occupancies)))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey, String requestKey, String... members)
            throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(requestKey, members)))
                .andExpect(status().isOk());
    }

    private void grant(String member, int grade, String fromIso, String toIso) throws Exception {
        mvc.perform(post("/api/v1/crew-qualifications").contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(key("REQ"), member, grade, fromIso, toIso)))
                .andExpect(status().isCreated());
    }

    private void revoke(String member) throws Exception {
        mvc.perform(post("/api/v1/crew-qualifications/{m}/revoke", member)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
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
        Instant instant = DAY.atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
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

    private static String publishBody(String requestKey, String... members) {
        StringBuilder sb = new StringBuilder("{\"requestKey\":\"" + requestKey
                + "\",\"crewMembers\":[");
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
        return "{\"requestKey\":\"" + requestKey + "\",\"crewMemberId\":\"" + member
                + "\",\"lineGrade\":" + grade + ",\"validFromUtc\":\"" + fromIso
                + "\",\"validToUtc\":\"" + toIso + "\"}";
    }

    private static String gradeBody(String requestKey, int lineGrade) {
        return "{\"requestKey\":\"" + requestKey + "\",\"lineGrade\":" + lineGrade + "}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }
}