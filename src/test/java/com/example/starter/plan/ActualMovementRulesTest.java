package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 实际进出的驶出规则、幂等重放、失败不占键、取消/改签限制与查询行为测试（H2 内存库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
class ActualMovementRulesTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void exitRequiresInsideAndRejectsSecondExit() throws Exception {
        String plan = key("SCH");
        createPlan(plan, occ("G1", key("SEC"), iso(10, 0), iso(10, 10)));
        publishPlan(plan);
        setClock(10, 1);

        // 未进入不可驶出
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/exit", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("REQ"), "op", 1)))
                .andExpect(status().isConflict());

        enter(plan, key("REQ"));
        exit(plan, key("REQ"));

        // 已驶出不可再次驶出（新 requestId）
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/exit", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("REQ"), "op", 1)))
                .andExpect(status().isConflict());
        // 已驶出不可再次进入
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("REQ"), "op", 1)))
                .andExpect(status().isConflict());
    }

    @Test
    void enterIdempotentReplayAndKeyReuse() throws Exception {
        String section = key("SEC");
        String plan = key("SCH");
        createPlan(plan, occ("G1", section, iso(10, 0), iso(10, 10)));
        publishPlan(plan);
        setClock(10, 1);

        String requestId = key("REQ");
        String body = actualBody(requestId, "op-1", 1);
        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", plan)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同参重放返回首次完整响应，不重新获取区段
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", plan)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        assertThat(countEvents(plan)).isEqualTo(1);
        JsonNode locks = read(mvc.perform(get("/api/v1/actual-locks")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn());
        assertThat(locks).hasSize(1);

        // 同 requestId 异参 → 409：改操作者、改期望版本、改操作类型、改资源
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(requestId, "op-2", 1)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(requestId, "op-1", 2)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/exit", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(requestId, "op-1", 1)))
                .andExpect(status().isConflict());
    }

    @Test
    void failedEnterDoesNotConsumeRequestId() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createPlan(planA, occ("GA", section, iso(10, 0), iso(10, 10)));
        publishPlan(planA);
        createPlan(planB, occ("GB", section, iso(10, 10), iso(10, 20)));
        publishPlan(planB);

        setClock(10, 1);
        enter(planA, key("REQ"));

        // B 进入失败（区段被占用），requestId 不占键
        String requestId = key("REQ");
        setClock(10, 11);
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", planB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(requestId, "op-2", 1)))
                .andExpect(status().isConflict());
        assertThat(countActualWriteRecords(requestId)).isZero();

        // A 驶出后同 requestId 重试成功
        setClock(10, 12);
        exit(planA, key("REQ"));
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", planB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(requestId, "op-2", 1)))
                .andExpect(status().isOk());
        assertThat(countActualWriteRecords(requestId)).isEqualTo(1);
    }

    @Test
    void planWithInsideOrExitedRestrictsCancelAndReschedule() throws Exception {
        String section = key("SEC");
        String plan = key("SCH");
        createPlan(plan,
                occ("G1", section, iso(10, 0), iso(10, 10)),
                occ("G2", section, iso(10, 20), iso(10, 30)));
        publishPlan(plan);
        String newPlan = key("SCH");
        createPlan(newPlan, occ("G9", key("SEC"), iso(11, 0), iso(12, 0)));

        setClock(10, 1);
        enter(plan, key("REQ"));

        // 含 INSIDE：取消与改签均 409
        mvc.perform(post("/api/v1/plans/{key}/cancel", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/plans/{key}/reschedule", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newPlan, 1, 1)))
                .andExpect(status().isConflict());

        // 驶出后：含 EXITED 仍不可改签
        setClock(10, 5);
        exit(plan, key("REQ"));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newPlan, 1, 1)))
                .andExpect(status().isConflict());

        // 含 EXITED 可取消：仅撤销未进入部分，实际轨迹保留
        mvc.perform(post("/api/v1/plans/{key}/cancel", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isOk());
        JsonNode actual = read(mvc.perform(get("/api/v1/plans/{key}/actual-occupancies", plan))
                .andExpect(status().isOk()).andReturn());
        assertThat(actual.get(0).get("actualStatus").asText()).isEqualTo("EXITED");
        assertThat(actual.get(0).get("enteredAt").asText()).isEqualTo(iso(10, 1));
        assertThat(actual.get(0).get("exitedAt").asText()).isEqualTo(iso(10, 5));
        assertThat(actual.get(1).get("actualStatus").asText()).isEqualTo("NOT_ENTERED");
        assertThat(countEvents(plan)).isEqualTo(2);
    }

    @Test
    void queriesDoNotAutoExitExpiredInside() throws Exception {
        String section = key("SEC");
        String plan = key("SCH");
        createPlan(plan, occ("G1", section, iso(10, 0), iso(10, 10)));
        publishPlan(plan);
        setClock(10, 1);
        enter(plan, key("REQ"));

        // 时隙已过，查询不自动驶出
        setClock(11, 0);
        JsonNode actual = read(mvc.perform(get("/api/v1/plans/{key}/actual-occupancies", plan))
                .andExpect(status().isOk()).andReturn());
        assertThat(actual.get(0).get("actualStatus").asText()).isEqualTo("INSIDE");
        JsonNode locks = read(mvc.perform(get("/api/v1/actual-locks")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn());
        assertThat(locks).hasSize(1);

        // 迟到驶出仍允许
        exit(plan, key("REQ"));
    }

    // ---------- 辅助 ----------

    private void enter(String scheduleKey, String requestId) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(requestId, "op", 1)))
                .andExpect(status().isOk());
    }

    private void exit(String scheduleKey, String requestId) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/exit", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(requestId, "op", 1)))
                .andExpect(status().isOk());
    }

    private int countEvents(String scheduleKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_actual_event e JOIN rail_day_plan p ON p.id = e.plan_id"
                        + " WHERE p.schedule_key = ?",
                Integer.class, scheduleKey);
        return count == null ? 0 : count;
    }

    private int countActualWriteRecords(String requestId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM actual_write_record WHERE request_id = ?",
                Integer.class, requestId);
        return count == null ? 0 : count;
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void setClock(int hour, int minute) {
        clock.setInstant(DAY.atTime(hour, minute).atZone(SH).toInstant());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour, int minute) {
        Instant instant = DAY.atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String actualBody(String requestId, String operator, int expectedVersion) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator
                + "\",\"expectedVersion\":" + expectedVersion + "}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }
}
