package com.example.starter.plan;

import static com.example.starter.plan.ConnectionApiTest.actionBody;
import static com.example.starter.plan.ConnectionApiTest.iso;
import static com.example.starter.plan.ConnectionApiTest.key;
import static com.example.starter.plan.ConnectionApiTest.occ;
import static com.example.starter.plan.ConnectionApiTest.registerBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 前车改签延后触发接续重算的端到端测试：风险标记、可选改签时间窗、
 * 历史事件固化（前车版本、原新时刻、保障差额）与"已开始不标记"边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConnectionRiskTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.setInstant(Instant.parse("2026-09-21T00:00:00Z"));
    }

    @Test
    void predecessorDelayedMarksSuccessorAtRiskWithWindowAndEvidence() throws Exception {
        String sectionA = key("SEC");
        String sectionB = key("SEC");
        String pred = publishedPlan("G1", sectionA, iso(8, 0), iso(10, 0));
        String succ = publishedPlan("G2", sectionB, iso(10, 45), iso(12, 0));
        long connectionId = register(pred, succ, 30);

        // 前车改签延后：终到 10:00 -> 10:30，保障 30 分钟只剩 15 分钟
        String predNew = key("SCH");
        createPlan(predNew, occ("G1", sectionA, iso(8, 0), iso(10, 30)));
        reschedule(pred, predNew);

        // 后车被标记风险，但时刻不被自动修改
        JsonNode diag = diagnostics(succ);
        assertThat(diag.get("connectionRisk").asText()).isEqualTo("CONNECTION_AT_RISK");
        JsonNode succPlan = plan(succ);
        assertThat(succPlan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(succPlan.get("version").asInt()).isEqualTo(1);
        assertThat(succPlan.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(10, 45));

        // 可选改签时间窗：最早 11:00（10:30+30min），最晚 22:45（运营日末-75min 时长）
        JsonNode windows = diag.get("openRiskWindows");
        assertThat(windows).hasSize(1);
        JsonNode window = windows.get(0);
        assertThat(window.get("connectionId").asLong()).isEqualTo(connectionId);
        assertThat(window.get("predecessorKey").asText()).isEqualTo(predNew);
        assertThat(window.get("windowStartUtc").asText()).isEqualTo(iso(11, 0));
        assertThat(window.get("windowEndUtc").asText()).isEqualTo(iso(22, 45));
        assertThat(window.get("requiredMinutes").asInt()).isEqualTo(30);
        assertThat(window.get("actualMinutes").asInt()).isEqualTo(15);
        assertThat(window.get("deficitMinutes").asInt()).isEqualTo(15);

        // 接续跟随改签指向新计划，实时差额同步
        JsonNode conn = connection(connectionId);
        assertThat(conn.get("predecessorKey").asText()).isEqualTo(predNew);
        assertThat(conn.get("actualMinutes").asInt()).isEqualTo(15);
        assertThat(conn.get("requiredMinutes").asInt()).isEqualTo(30);
        assertThat(conn.get("deficitMinutes").asInt()).isEqualTo(15);

        // 历史：REGISTERED 与 RISK_MARKED 两条，固化前车版本、原新终到时刻与保障差额
        JsonNode events = history(connectionId);
        assertThat(events).hasSize(2);
        JsonNode registered = events.get(0);
        assertThat(registered.get("eventType").asText()).isEqualTo("REGISTERED");
        assertThat(registered.get("predecessorKey").asText()).isEqualTo(pred);
        assertThat(registered.get("predecessorVersion").asInt()).isEqualTo(1);
        assertThat(registered.get("oldTimeUtc").asText()).isEqualTo(iso(10, 0));
        assertThat(registered.get("newTimeUtc").asText()).isEqualTo(iso(10, 0));
        assertThat(registered.get("actualMinutes").asInt()).isEqualTo(45);
        JsonNode risk = events.get(1);
        assertThat(risk.get("eventType").asText()).isEqualTo("RISK_MARKED");
        assertThat(risk.get("predecessorKey").asText()).isEqualTo(predNew);
        assertThat(risk.get("predecessorVersion").asInt()).isEqualTo(1);
        assertThat(risk.get("oldTimeUtc").asText()).isEqualTo(iso(10, 0));
        assertThat(risk.get("newTimeUtc").asText()).isEqualTo(iso(10, 30));
        assertThat(risk.get("requiredMinutes").asInt()).isEqualTo(30);
        assertThat(risk.get("actualMinutes").asInt()).isEqualTo(15);
        assertThat(risk.get("deficitMinutes").asInt()).isEqualTo(15);
        assertThat(risk.get("windowStartUtc").asText()).isEqualTo(iso(11, 0));
        assertThat(risk.get("windowEndUtc").asText()).isEqualTo(iso(22, 45));
    }

    @Test
    void riskClearedWhenPredecessorRescheduledEarlierAndHistoryNotRewritten() throws Exception {
        String sectionA = key("SEC");
        String pred = publishedPlan("G1", sectionA, iso(8, 0), iso(10, 0));
        String succ = publishedPlan("G2", key("SEC"), iso(10, 45), iso(12, 0));
        long connectionId = register(pred, succ, 30);
        String predDelayed = key("SCH");
        createPlan(predDelayed, occ("G1", sectionA, iso(8, 0), iso(10, 30)));
        reschedule(pred, predDelayed);
        assertThat(diagnostics(succ).get("connectionRisk").asText()).isEqualTo("CONNECTION_AT_RISK");
        JsonNode eventsBefore = history(connectionId);

        // 再次改签提前：终到回到 10:00，保障恢复，风险标记清除
        String predEarlier = key("SCH");
        createPlan(predEarlier, occ("G1", sectionA, iso(8, 0), iso(10, 0)));
        reschedule(predDelayed, predEarlier);

        JsonNode diag = diagnostics(succ);
        assertThat(diag.get("connectionRisk").asText()).isEqualTo("NONE");
        assertThat(diag.get("openRiskWindows")).isEmpty();
        // 历史风险记录不被改写：仍是原来两条，内容逐字段一致
        assertThat(history(connectionId)).isEqualTo(eventsBefore);
    }

    @Test
    void startedSuccessorIsNotMarkedAsCandidate() throws Exception {
        String sectionA = key("SEC");
        String pred = publishedPlan("G1", sectionA, iso(8, 0), iso(10, 0));
        String succ = publishedPlan("G2", key("SEC"), iso(10, 45), iso(12, 0));
        long connectionId = register(pred, succ, 30);

        // 时钟拨到后车始发之后（10:45 之后），计划已开始
        clock.setInstant(Instant.parse(iso(11, 0)));
        String predDelayed = key("SCH");
        createPlan(predDelayed, occ("G1", sectionA, iso(8, 0), iso(10, 30)));
        reschedule(pred, predDelayed);

        // 已开始计划不自动标记为候选改签，也不生成时间窗；风险判定仍固化进历史
        JsonNode diag = diagnostics(succ);
        assertThat(diag.get("connectionRisk").asText()).isEqualTo("NONE");
        assertThat(diag.get("openRiskWindows")).isEmpty();
        JsonNode events = history(connectionId);
        assertThat(events).hasSize(2);
        JsonNode risk = events.get(1);
        assertThat(risk.get("eventType").asText()).isEqualTo("RISK_MARKED");
        assertThat(risk.get("deficitMinutes").asInt()).isEqualTo(15);
        assertThat(risk.get("windowStartUtc").isNull()).isTrue();
        assertThat(risk.get("windowEndUtc").isNull()).isTrue();
    }

    @Test
    void cancelConnectionClearsMarkButKeepsRiskHistory() throws Exception {
        String sectionA = key("SEC");
        String pred = publishedPlan("G1", sectionA, iso(8, 0), iso(10, 0));
        String succ = publishedPlan("G2", key("SEC"), iso(10, 45), iso(12, 0));
        long connectionId = register(pred, succ, 30);
        String predDelayed = key("SCH");
        createPlan(predDelayed, occ("G1", sectionA, iso(8, 0), iso(10, 30)));
        reschedule(pred, predDelayed);
        assertThat(diagnostics(succ).get("connectionRisk").asText()).isEqualTo("CONNECTION_AT_RISK");

        // 取消接续：风险标记清除，历史风险记录保留不改写
        mvc.perform(post("/api/v1/connections/{id}/cancel", connectionId)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        JsonNode diag = diagnostics(succ);
        assertThat(diag.get("connectionRisk").asText()).isEqualTo("NONE");
        JsonNode events = history(connectionId);
        assertThat(events).hasSize(3);
        assertThat(events.get(1).get("eventType").asText()).isEqualTo("RISK_MARKED");
        assertThat(events.get(1).get("deficitMinutes").asInt()).isEqualTo(15);
        assertThat(events.get(2).get("eventType").asText()).isEqualTo("CANCELLED");
        assertThat(connection(connectionId).get("status").asText()).isEqualTo("CANCELLED");
    }

    // ---------- 辅助 ----------

    private String publishedPlan(String train, String section, String startIso, String endIso)
            throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ(train, section, startIso, endIso));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        return scheduleKey;
    }

    private void createPlan(String scheduleKey, String occupancy) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\"" + scheduleKey
                                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":[" + occupancy + "]}"))
                .andExpect(status().isCreated());
    }

    private long register(String pred, String succ, int guarantee) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, guarantee, "西安北", "西安北", false)))
                .andExpect(status().isCreated()).andReturn();
        return read(result).get("id").asLong();
    }

    private void reschedule(String oldKey, String newKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"newScheduleKey\":\"" + newKey
                                + "\",\"expectedOldVersion\":1,\"expectedNewVersion\":1}"))
                .andExpect(status().isOk());
    }

    private JsonNode diagnostics(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}/connection-diagnostics", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private JsonNode connection(long connectionId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/connections/{id}", connectionId))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private JsonNode history(long connectionId) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/connections/{id}/history", connectionId))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private JsonNode plan(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}