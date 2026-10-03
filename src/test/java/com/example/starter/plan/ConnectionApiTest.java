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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 接续登记主流程、明细/历史/诊断查询与取消接续的端到端测试（H2 内存库）。
 * 各用例使用唯一 scheduleKey/requestKey/区段 ID，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConnectionApiTest {

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

    // ---------- 主流程与查询 ----------

    @Test
    void registerMainFlowAndQueriesAreReadOnly() throws Exception {
        String pred = key("SCH");
        String succ = key("SCH");
        // 前车 08:00-10:00 终到，后车 10:45-12:00 始发，实际保障 45 分钟
        createPlan(pred, occ("G1", key("SEC"), iso(8, 0), iso(10, 0)));
        publishPlan(pred);
        createPlan(succ, occ("G2", key("SEC"), iso(10, 45), iso(12, 0)));
        publishPlan(succ);

        MvcResult created = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, 30, "西安北", "西安北", false)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode view = read(created);
        long connectionId = view.get("id").asLong();
        assertThat(view.get("predecessorKey").asText()).isEqualTo(pred);
        assertThat(view.get("successorKey").asText()).isEqualTo(succ);
        assertThat(view.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(view.get("requiredMinutes").asInt()).isEqualTo(30);
        assertThat(view.get("actualMinutes").asInt()).isEqualTo(45);
        assertThat(view.get("deficitMinutes").asInt()).isEqualTo(-15);

        // 明细查询与列表过滤
        MvcResult detail = mvc.perform(get("/api/v1/connections/{id}", connectionId))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(view);
        MvcResult byPred = mvc.perform(get("/api/v1/connections")
                        .param("predecessorKey", pred))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(byPred)).hasSize(1);
        MvcResult bySucc = mvc.perform(get("/api/v1/connections")
                        .param("successorKey", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(bySucc).get(0).get("id").asLong()).isEqualTo(connectionId);
        MvcResult byPair = mvc.perform(get("/api/v1/connections")
                        .param("predecessorKey", pred).param("successorKey", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(byPair)).hasSize(1);

        // 历史：仅一条 REGISTERED，固化前车版本与时刻、要求/实际/差额
        MvcResult history = mvc.perform(get("/api/v1/connections/{id}/history", connectionId))
                .andExpect(status().isOk()).andReturn();
        JsonNode events = read(history);
        assertThat(events).hasSize(1);
        JsonNode registered = events.get(0);
        assertThat(registered.get("eventType").asText()).isEqualTo("REGISTERED");
        assertThat(registered.get("predecessorVersion").asInt()).isEqualTo(1);
        assertThat(registered.get("oldTimeUtc").asText()).isEqualTo(iso(10, 0));
        assertThat(registered.get("newTimeUtc").asText()).isEqualTo(iso(10, 0));
        assertThat(registered.get("requiredMinutes").asInt()).isEqualTo(30);
        assertThat(registered.get("actualMinutes").asInt()).isEqualTo(45);
        assertThat(registered.get("deficitMinutes").asInt()).isEqualTo(-15);
        assertThat(registered.get("windowStartUtc").isNull()).isTrue();

        // 诊断：无风险、无未解除时间窗；重复读取不改变任何状态
        JsonNode diagBefore = diagnostics(succ);
        assertThat(diagBefore.get("connectionRisk").asText()).isEqualTo("NONE");
        assertThat(diagBefore.get("connections")).hasSize(1);
        assertThat(diagBefore.get("openRiskWindows")).isEmpty();
        assertThat(diagnostics(succ)).isEqualTo(diagBefore);
        assertThat(read(history)).isEqualTo(events);
        MvcResult plan = mvc.perform(get("/api/v1/plans/{key}", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(plan).get("version").asInt()).isEqualTo(1);
    }

    // ---------- 取消接续与重新登记 ----------

    @Test
    void cancelConnectionKeepsRowAndHistoryAndReregisterRevives() throws Exception {
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", key("SEC"), iso(8, 0), iso(10, 0)));
        publishPlan(pred);
        createPlan(succ, occ("G2", key("SEC"), iso(11, 0), iso(12, 0)));
        publishPlan(succ);
        MvcResult created = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, 30, "西安北", "西安北", false)))
                .andExpect(status().isCreated()).andReturn();
        long connectionId = read(created).get("id").asLong();

        String cancelKey = key("REQ");
        MvcResult cancelled = mvc.perform(post("/api/v1/connections/{id}/cancel", connectionId)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(cancelKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(cancelled).get("status").asText()).isEqualTo("CANCELLED");

        // 同键重放返回首次结果；新键重复取消 409
        MvcResult replay = mvc.perform(post("/api/v1/connections/{id}/cancel", connectionId)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(cancelKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(cancelled));
        mvc.perform(post("/api/v1/connections/{id}/cancel", connectionId)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());

        // 行保留：明细仍可查，历史含 CANCELLED 事件
        MvcResult detail = mvc.perform(get("/api/v1/connections/{id}", connectionId))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("status").asText()).isEqualTo("CANCELLED");
        MvcResult history = mvc.perform(get("/api/v1/connections/{id}/history", connectionId))
                .andExpect(status().isOk()).andReturn();
        JsonNode events = read(history);
        assertThat(events).hasSize(2);
        assertThat(events.get(1).get("eventType").asText()).isEqualTo("CANCELLED");

        // 同一前后车对重新登记：复活原行（id 不变），历史追加不改写
        MvcResult revived = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, 45, "西安北", "西安北", false)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode revivedView = read(revived);
        assertThat(revivedView.get("id").asLong()).isEqualTo(connectionId);
        assertThat(revivedView.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(revivedView.get("guaranteeMinutes").asInt()).isEqualTo(45);
        MvcResult historyAfter = mvc.perform(get("/api/v1/connections/{id}/history", connectionId))
                .andExpect(status().isOk()).andReturn();
        JsonNode eventsAfter = read(historyAfter);
        assertThat(eventsAfter).hasSize(3);
        assertThat(eventsAfter.get(0)).isEqualTo(events.get(0));
        assertThat(eventsAfter.get(1)).isEqualTo(events.get(1));
        assertThat(eventsAfter.get(2).get("eventType").asText()).isEqualTo("REGISTERED");
        assertThat(eventsAfter.get(2).get("requiredMinutes").asInt()).isEqualTo(45);
    }

    // ---------- 不存在 404 ----------

    @Test
    void missingConnectionAndPlanReturn404() throws Exception {
        mvc.perform(get("/api/v1/connections/{id}", 999999999L))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/connections/{id}/history", 999999999L))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/connections/{id}/cancel", 999999999L)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound());

        String pred = key("SCH");
        createPlan(pred, occ("G1", key("SEC"), iso(8, 0), iso(10, 0)));
        publishPlan(pred);
        mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, key("SCH"), 30, "西安北", "西安北", false)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/connection-diagnostics", key("SCH")))
                .andExpect(status().isNotFound());
    }

    // ---------- 辅助 ----------

    private JsonNode diagnostics(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}/connection-diagnostics", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\"" + scheduleKey
                                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    static String registerBody(String requestKey, String pred, String succ, int guarantee,
                               String from, String to, boolean crossesMidnight) {
        return "{\"requestKey\":\"" + requestKey + "\",\"predecessorKey\":\"" + pred
                + "\",\"successorKey\":\"" + succ + "\",\"guaranteeMinutes\":" + guarantee
                + ",\"fromStation\":\"" + from + "\",\"toStation\":\"" + to
                + "\",\"crossesMidnight\":" + crossesMidnight + "}";
    }
}