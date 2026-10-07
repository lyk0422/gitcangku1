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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 实际进出确认主流程与拒绝分支测试（H2 内存库，可控时钟）。
 * 场景：A 时隙 10:00-10:10、B 为 10:10-10:20；A 进入未退出时 B 仍 409；
 * A 迟到驶出后 B 可进入，A 历史进入时刻不变，计划时隙不回改。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
class ActualMovementApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Test
    void enterExitMainFlowAcrossAdjacentPlans() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createPlan(planA, occ("GA", section, iso(10, 0), iso(10, 10)));
        publishPlan(planA);
        createPlan(planB, occ("GB", section, iso(10, 10), iso(10, 20)));
        publishPlan(planB);

        // A 在时隙内进入
        setClock(10, 1);
        MvcResult entered = mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", planA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("REQ"), "op-1", 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode enterBody = read(entered);
        assertThat(enterBody.get("actualStatus").asText()).isEqualTo("INSIDE");
        assertThat(enterBody.get("enteredAt").asText()).isEqualTo(iso(10, 1));
        assertThat(enterBody.get("planVersion").asInt()).isEqualTo(1);

        // A 未驶出，10:11 的 B 时隙已开始仍不得进入：409 且无进入记录
        setClock(10, 11);
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", planB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("REQ"), "op-2", 1)))
                .andExpect(status().isConflict());
        JsonNode bActual = read(mvc.perform(get("/api/v1/plans/{key}/actual-occupancies", planB))
                .andExpect(status().isOk()).andReturn());
        assertThat(bActual.get(0).get("actualStatus").asText()).isEqualTo("NOT_ENTERED");
        assertThat(bActual.get(0).get("enteredAt").isNull()).isTrue();

        // A 迟到驶出（已过计划结束时刻）：允许，计划时隙不回改
        setClock(10, 12);
        MvcResult exited = mvc.perform(post("/api/v1/plans/{key}/occupancies/0/exit", planA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("REQ"), "op-1", 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode exitBody = read(exited);
        assertThat(exitBody.get("actualStatus").asText()).isEqualTo("EXITED");
        assertThat(exitBody.get("enteredAt").asText()).isEqualTo(iso(10, 1));
        assertThat(exitBody.get("exitedAt").asText()).isEqualTo(iso(10, 12));
        assertThat(exitBody.get("exitPlanVersion").asInt()).isEqualTo(1);

        // 计划时隙保持 10:00-10:10 / 10:10-10:20 不变
        JsonNode slots = read(mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn());
        assertThat(slots).hasSize(2);

        // A 退出后 B 可进入
        MvcResult bEntered = mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", planB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("REQ"), "op-2", 1)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(bEntered).get("actualStatus").asText()).isEqualTo("INSIDE");

        // A 历史进入时刻不变
        JsonNode aActual = read(mvc.perform(get("/api/v1/plans/{key}/actual-occupancies", planA))
                .andExpect(status().isOk()).andReturn());
        assertThat(aActual.get(0).get("actualStatus").asText()).isEqualTo("EXITED");
        assertThat(aActual.get(0).get("enteredAt").asText()).isEqualTo(iso(10, 1));
        assertThat(aActual.get(0).get("exitedAt").asText()).isEqualTo(iso(10, 12));

        // 实际锁与计划占用分开显示：当前仅 B 持有实际锁
        JsonNode locks = read(mvc.perform(get("/api/v1/actual-locks")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn());
        assertThat(locks).hasSize(1);
        assertThat(locks.get(0).get("scheduleKey").asText()).isEqualTo(planB);
        assertThat(locks.get(0).get("enteredAt").asText()).isEqualTo(iso(10, 12));
    }

    @Test
    void enterRejectsOutsideSlotWindow() throws Exception {
        String section = key("SEC");
        String plan = key("SCH");
        createPlan(plan, occ("G1", section, iso(10, 0), iso(10, 10)));
        publishPlan(plan);

        // 时隙开始前 → 409
        setClock(9, 59);
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("REQ"), "op", 1)))
                .andExpect(status().isConflict());
        // 时隙结束时刻（右开）→ 409
        setClock(10, 10);
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("REQ"), "op", 1)))
                .andExpect(status().isConflict());
        // 开始时刻（左闭）合法
        setClock(10, 0);
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("REQ"), "op", 1)))
                .andExpect(status().isOk());
    }

    @Test
    void enterRejectsInvalidStateVersionAndMissing() throws Exception {
        String section = key("SEC");
        String plan = key("SCH");
        createPlan(plan, occ("G1", section, iso(10, 0), iso(10, 10)));
        setClock(10, 1);

        // 草稿不可进入
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("REQ"), "op", 1)))
                .andExpect(status().isConflict());
        publishPlan(plan);
        // 期望版本不匹配
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("REQ"), "op", 2)))
                .andExpect(status().isConflict());
        // 占用序号不存在
        mvc.perform(post("/api/v1/plans/{key}/occupancies/5/enter", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("REQ"), "op", 1)))
                .andExpect(status().isNotFound());
        // 计划不存在
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", key("SCH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("REQ"), "op", 1)))
                .andExpect(status().isNotFound());

        // 已取消计划不可进入
        String cancelled = key("SCH");
        createPlan(cancelled, occ("G2", key("SEC"), iso(10, 0), iso(10, 10)));
        publishPlan(cancelled);
        cancelPlan(cancelled);
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", cancelled)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actualBody(key("REQ"), "op", 1)))
                .andExpect(status().isConflict());
    }

    @Test
    void enterRejectsMissingFields() throws Exception {
        String plan = key("SCH");
        // 缺 requestId
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"op\",\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
        // 缺 operator
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/enter", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key("REQ") + "\",\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
        // 缺 expectedVersion
        mvc.perform(post("/api/v1/plans/{key}/occupancies/0/exit", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key("REQ") + "\",\"operator\":\"op\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 辅助 ----------

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

    private void cancelPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
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

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
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
}
