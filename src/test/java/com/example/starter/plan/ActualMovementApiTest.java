package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 实际进出确认 API 测试（H2 内存库 + 可控时钟）：主流程、题干场景（A 未退出 B 409、
 * A 退出后 B 可进入）、窗口/状态/版本拒绝、幂等重放与取消/改签限制。
 * 各用例使用唯一 scheduleKey/requestId/区段 ID，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActualMovementApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 7);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    // ---------- 主流程 ----------

    @Test
    void enterThenExitMainFlow() throws Exception {
        String section = key("SEC");
        String sch = key("SCH");
        createPlan(sch, occ("G1", section, iso(10, 0), iso(10, 20)));
        publishPlan(sch);

        nowAt(10, 5);
        MvcResult entered = mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "dispatcher-1", 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode enterBody = read(entered);
        assertThat(enterBody.get("actualStatus").asText()).isEqualTo("INSIDE");
        assertThat(enterBody.get("serverUtc").asText()).isEqualTo(iso(10, 5));
        assertThat(enterBody.get("planVersion").asInt()).isEqualTo(1);
        assertThat(enterBody.get("trainNo").asText()).isEqualTo("G1");

        JsonNode actual = read(mvc.perform(get("/api/v1/plans/{k}/actual", sch))
                .andExpect(status().isOk()).andReturn());
        JsonNode occ0 = actual.get("occupancies").get(0);
        assertThat(occ0.get("actualStatus").asText()).isEqualTo("INSIDE");
        assertThat(occ0.get("enteredAt").asText()).isEqualTo(iso(10, 5));
        assertThat(occ0.get("exitedAt").isNull());

        JsonNode locks = read(mvc.perform(get("/api/v1/actual-locks")
                        .param("sectionId", section))
                .andExpect(status().isOk()).andReturn());
        assertThat(locks).hasSize(1);
        assertThat(locks.get(0).get("scheduleKey").asText()).isEqualTo(sch);
        assertThat(locks.get(0).get("enteredAt").asText()).isEqualTo(iso(10, 5));

        nowAt(10, 15);
        MvcResult exited = mvc.perform(post("/api/v1/plans/{k}/occupancies/0/exit", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "dispatcher-2", 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode exitBody = read(exited);
        assertThat(exitBody.get("actualStatus").asText()).isEqualTo("EXITED");
        assertThat(exitBody.get("serverUtc").asText()).isEqualTo(iso(10, 15));

        JsonNode after = read(mvc.perform(get("/api/v1/plans/{k}/actual", sch))
                .andExpect(status().isOk()).andReturn());
        JsonNode occAfter = after.get("occupancies").get(0);
        assertThat(occAfter.get("actualStatus").asText()).isEqualTo("EXITED");
        assertThat(occAfter.get("enteredAt").asText()).isEqualTo(iso(10, 5));
        assertThat(occAfter.get("exitedAt").asText()).isEqualTo(iso(10, 15));

        JsonNode locksAfter = read(mvc.perform(get("/api/v1/actual-locks")
                        .param("sectionId", section))
                .andExpect(status().isOk()).andReturn());
        assertThat(locksAfter).isEmpty();
    }

    // ---------- 题干场景：A 进入未退出，B 仍 409；A 退出后 B 可进入 ----------

    @Test
    void insideBlocksOtherTrainUntilExit() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createPlan(planA, occ("GA", section, iso(10, 0), iso(10, 10)));
        createPlan(planB, occ("GB", section, iso(10, 10), iso(10, 20)));
        publishPlan(planA);
        publishPlan(planB);

        nowAt(10, 5);
        mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", planA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-a", 1)))
                .andExpect(status().isOk());

        // A 未退出：B 时隙已开始（10:11 ∈ [10:10,10:20)）仍 409，且无进入记录
        String enterBRequestId = key("RID");
        nowAt(10, 11);
        MvcResult blocked = mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", planB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(enterBRequestId, "op-b", 1)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(blocked).get("code").asText()).isEqualTo("SECTION_OCCUPIED");
        JsonNode actualB = read(mvc.perform(get("/api/v1/plans/{k}/actual", planB))
                .andExpect(status().isOk()).andReturn());
        assertThat(actualB.get("occupancies").get(0).get("actualStatus").asText())
                .isEqualTo("NOT_ENTERED");
        assertThat(actualB.get("occupancies").get(0).get("enteredAt").isNull()).isTrue();

        // A 迟到退出（10:12 已过原时隙末 10:10）：允许，且计划时隙不倒改
        nowAt(10, 12);
        mvc.perform(post("/api/v1/plans/{k}/occupancies/0/exit", planA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-a", 1)))
                .andExpect(status().isOk());
        JsonNode planADetail = read(mvc.perform(get("/api/v1/plans/{k}", planA))
                .andExpect(status().isOk()).andReturn());
        assertThat(planADetail.get("occupancies").get(0).get("endUtc").asText())
                .isEqualTo(iso(10, 10));

        // A 退出后 B 可进入；失败不占键，沿用同一 requestId 成功
        MvcResult enteredB = mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", planB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(enterBRequestId, "op-b", 1)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(enteredB).get("actualStatus").asText()).isEqualTo("INSIDE");

        // A 历史进入时刻不变
        JsonNode actualA = read(mvc.perform(get("/api/v1/plans/{k}/actual", planA))
                .andExpect(status().isOk()).andReturn());
        assertThat(actualA.get("occupancies").get(0).get("enteredAt").asText())
                .isEqualTo(iso(10, 5));
        assertThat(actualA.get("occupancies").get(0).get("exitedAt").asText())
                .isEqualTo(iso(10, 12));
    }

    // ---------- 窗口与参数拒绝 ----------

    @Test
    void enterRejectsOutsideWindowAndFailureDoesNotBurnKey() throws Exception {
        String section = key("SEC");
        String sch = key("SCH");
        createPlan(sch, occ("G1", section, iso(10, 0), iso(10, 20)));
        publishPlan(sch);

        String requestId = key("RID");
        // 时隙未开始 → 409
        nowAt(9, 59);
        MvcResult early = mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(requestId, "op-1", 1)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(early).get("code").asText()).isEqualTo("OUTSIDE_SLOT_WINDOW");

        // 失败不占键：进入窗口后同一 requestId 成功（左闭边界 10:00 可进入）
        nowAt(10, 0);
        mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(requestId, "op-1", 1)))
                .andExpect(status().isOk());

        // 右开边界：另一占用 10:20 恰为结束时刻 → 409
        String sch2 = key("SCH");
        createPlan(sch2, occ("G2", key("SEC"), iso(10, 0), iso(10, 20)));
        publishPlan(sch2);
        nowAt(10, 20);
        MvcResult atEnd = mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", sch2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-1", 1)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(atEnd).get("code").asText()).isEqualTo("OUTSIDE_SLOT_WINDOW");
    }

    @Test
    void enterRejectsStateVersionAndParamErrors() throws Exception {
        String section = key("SEC");
        String sch = key("SCH");
        createPlan(sch, occ("G1", section, iso(10, 0), iso(10, 20)));
        nowAt(10, 5);

        // 草稿不可进入
        MvcResult draft = mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-1", 1)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(draft).get("code").asText()).isEqualTo("PLAN_STATE_CONFLICT");

        publishPlan(sch);
        // 期望版本不匹配
        MvcResult version = mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-1", 5)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(version).get("code").asText()).isEqualTo("VERSION_CONFLICT");

        // 操作者为空 / 缺 requestId → 400
        mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), " ", 1)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"op-1\",\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());

        // 计划不存在 → 404；占用号不存在 → 404
        mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", key("SCH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-1", 1)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/plans/{k}/occupancies/7/enter", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-1", 1)))
                .andExpect(status().isNotFound());
    }

    // ---------- 退出规则 ----------

    @Test
    void exitRequiresInsideAndNoReenterAfterExit() throws Exception {
        String section = key("SEC");
        String sch = key("SCH");
        createPlan(sch, occ("G1", section, iso(10, 0), iso(10, 20)));
        publishPlan(sch);
        nowAt(10, 5);

        // 未进入不可退出
        MvcResult earlyExit = mvc.perform(post("/api/v1/plans/{k}/occupancies/0/exit", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-1", 1)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(earlyExit).get("code").asText()).isEqualTo("ACTUAL_STATE_CONFLICT");

        mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-1", 1)))
                .andExpect(status().isOk());
        nowAt(10, 10);
        mvc.perform(post("/api/v1/plans/{k}/occupancies/0/exit", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-1", 1)))
                .andExpect(status().isOk());

        // 重复退出 → 409；退出后不可再进入 → 409
        MvcResult reExit = mvc.perform(post("/api/v1/plans/{k}/occupancies/0/exit", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-1", 1)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(reExit).get("code").asText()).isEqualTo("ACTUAL_STATE_CONFLICT");
        MvcResult reEnter = mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-1", 1)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(reEnter).get("code").asText()).isEqualTo("ACTUAL_STATE_CONFLICT");
    }

    // ---------- 幂等 ----------

    @Test
    void enterAndExitIdempotentReplayAndParamConflict() throws Exception {
        String section = key("SEC");
        String sch = key("SCH");
        createPlan(sch, occ("G1", section, iso(10, 0), iso(10, 20)));
        publishPlan(sch);

        String enterRid = key("RID");
        nowAt(10, 5);
        String enterBody = actualBody(enterRid, "op-1", 1);
        MvcResult first = mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", sch)
                        .contentType(MediaType.APPLICATION_JSON).content(enterBody))
                .andExpect(status().isOk()).andReturn();
        // 旧进入请求重放：返回首次 INSIDE 响应，不重新获取区段
        nowAt(10, 8);
        MvcResult replay = mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", sch)
                        .contentType(MediaType.APPLICATION_JSON).content(enterBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        assertThat(read(replay).get("serverUtc").asText()).isEqualTo(iso(10, 5));
        // 仅一条 ENTER 轨迹
        Integer enterEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_actual_event e JOIN rail_day_plan p ON p.id = e.plan_id"
                        + " WHERE p.schedule_key = ? AND e.event_type = 'ENTER'",
                Integer.class, sch);
        assertThat(enterEvents).isEqualTo(1);

        // 同键异参 → 409
        mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(enterRid, "op-other", 1)))
                .andExpect(status().isConflict());

        // 退出同样幂等
        String exitRid = key("RID");
        nowAt(10, 12);
        String exitBody = actualBody(exitRid, "op-1", 1);
        MvcResult exitFirst = mvc.perform(post("/api/v1/plans/{k}/occupancies/0/exit", sch)
                        .contentType(MediaType.APPLICATION_JSON).content(exitBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult exitReplay = mvc.perform(post("/api/v1/plans/{k}/occupancies/0/exit", sch)
                        .contentType(MediaType.APPLICATION_JSON).content(exitBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(exitReplay)).isEqualTo(read(exitFirst));
        Integer exitEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_actual_event e JOIN rail_day_plan p ON p.id = e.plan_id"
                        + " WHERE p.schedule_key = ? AND e.event_type = 'EXIT'",
                Integer.class, sch);
        assertThat(exitEvents).isEqualTo(1);
    }

    // ---------- 取消 / 改签限制 ----------

    @Test
    void cancelAndRescheduleRestrictedByActualStatus() throws Exception {
        String section = key("SEC");
        String sch = key("SCH");
        createPlan(sch, occ("G1", section, iso(10, 0), iso(10, 20)));
        publishPlan(sch);
        nowAt(10, 5);
        mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-1", 1)))
                .andExpect(status().isOk());

        // 含 INSIDE：取消与改签均 409
        MvcResult cancelInside = mvc.perform(post("/api/v1/plans/{k}/cancel", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(cancelInside).get("code").asText()).isEqualTo("PLAN_HAS_INSIDE");
        String newDraft = key("SCH");
        createPlan(newDraft, occ("G2", key("SEC"), iso(11, 0), iso(11, 20)));
        MvcResult reschInside = mvc.perform(post("/api/v1/plans/{k}/reschedule", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newDraft)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(reschInside).get("code").asText()).isEqualTo("PLAN_HAS_INSIDE");

        // 退出后：取消成功，仅撤销未进入部分，实际轨迹保留
        nowAt(10, 12);
        mvc.perform(post("/api/v1/plans/{k}/occupancies/0/exit", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-1", 1)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{k}/cancel", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isOk());
        JsonNode actual = read(mvc.perform(get("/api/v1/plans/{k}/actual", sch))
                .andExpect(status().isOk()).andReturn());
        assertThat(actual.get("status").asText()).isEqualTo("CANCELLED");
        JsonNode occ0 = actual.get("occupancies").get(0);
        assertThat(occ0.get("actualStatus").asText()).isEqualTo("EXITED");
        assertThat(occ0.get("enteredAt").asText()).isEqualTo(iso(10, 5));
        assertThat(occ0.get("exitedAt").asText()).isEqualTo(iso(10, 12));

        // 含 EXITED：不可改签
        String sch2 = key("SCH");
        createPlan(sch2, occ("G3", key("SEC"), iso(10, 0), iso(10, 20)));
        publishPlan(sch2);
        nowAt(10, 6);
        mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", sch2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-1", 1)))
                .andExpect(status().isOk());
        nowAt(10, 9);
        mvc.perform(post("/api/v1/plans/{k}/occupancies/0/exit", sch2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-1", 1)))
                .andExpect(status().isOk());
        String newDraft2 = key("SCH");
        createPlan(newDraft2, occ("G4", key("SEC"), iso(11, 0), iso(11, 20)));
        MvcResult reschExited = mvc.perform(post("/api/v1/plans/{k}/reschedule", sch2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newDraft2)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(reschExited).get("code").asText()).isEqualTo("PLAN_HAS_EXITED");
    }

    // ---------- 查询不自动退出 ----------

    @Test
    void queryDoesNotAutoExitAfterSlotEnd() throws Exception {
        String section = key("SEC");
        String sch = key("SCH");
        createPlan(sch, occ("G1", section, iso(10, 0), iso(10, 20)));
        publishPlan(sch);
        nowAt(10, 5);
        mvc.perform(post("/api/v1/plans/{k}/occupancies/0/enter", sch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("RID"), "op-1", 1)))
                .andExpect(status().isOk());

        // 时钟越过时隙末：查询仍显示 INSIDE 与实际锁，不自动退出
        nowAt(10, 30);
        JsonNode actual = read(mvc.perform(get("/api/v1/plans/{k}/actual", sch))
                .andExpect(status().isOk()).andReturn());
        assertThat(actual.get("occupancies").get(0).get("actualStatus").asText())
                .isEqualTo("INSIDE");
        JsonNode locks = read(mvc.perform(get("/api/v1/actual-locks")
                        .param("sectionId", section))
                .andExpect(status().isOk()).andReturn());
        assertThat(locks).hasSize(1);
    }

    // ---------- 辅助 ----------

    private void nowAt(int hour, int minute) {
        Mockito.when(clock.instant())
                .thenReturn(DAY.atTime(hour, minute).atZone(SH).toInstant());
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, occupancies)))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{k}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String createBody(String requestKey, String scheduleKey,
                                     String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":[" + String.join(",", occupancies)
                + "]}";
    }

    private static String actualBody(String requestId, String operator, int expectedVersion) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"expectedVersion\":" + expectedVersion + "}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":1,\"expectedNewVersion\":1}";
    }
}
