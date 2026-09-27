package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 前车改签后的接续风险判定测试（H2 内存库，可控时钟）：
 * 保障不足的后车在同一事务内被标记 CONNECTION_AT_RISK 并生成可选改签时间窗，
 * 但不自动修改后车；风险记录为不可改写历史。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConnectionRiskTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    /** 运营日当日 00:00（Asia/Shanghai），早于全部测试计划时刻。 */
    private static final Instant BEFORE_DAY = DAY.atStartOfDay(SH).toInstant();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUpClock() {
        when(clock.instant()).thenReturn(BEFORE_DAY);
    }

    @Test
    void predecessorDelayMarksRiskAndKeepsSuccessorUntouched() throws Exception {
        String section1 = key("SEC");
        String section2 = key("SEC");
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", section1, "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", section2, "STA-X", "STA-B", iso(9, 30), iso(10, 30)));
        publishPlan(succ);
        long connectionId = register(pred, succ, "STA-X", 30);

        // 前车改签延后：终到 09:00 → 09:50，接续缺口 50 分钟
        String predNew = key("SCH");
        createPlan(predNew, occ("G1", section1, "STA-A", "STA-X", iso(8, 30), iso(9, 50)));
        reschedule(pred, predNew);

        // 后车被标记 CONNECTION_AT_RISK，但时刻不被自动修改
        JsonNode succDetail = planDetail(succ);
        assertThat(succDetail.get("connectionRisk").asText()).isEqualTo("CONNECTION_AT_RISK");
        assertThat(succDetail.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(succDetail.get("version").asInt()).isEqualTo(1);
        assertThat(succDetail.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(9, 30));

        // 风险记录固化：接续 id、前车版本、原新终到时刻、要求/实际/差额与可选改签时间窗
        JsonNode risks = risksOf(succ);
        assertThat(risks).hasSize(1);
        JsonNode risk = risks.get(0);
        assertThat(risk.get("connectionId").asLong()).isEqualTo(connectionId);
        assertThat(risk.get("predecessorKey").asText()).isEqualTo(predNew);
        assertThat(risk.get("predecessorVersion").asInt()).isEqualTo(1);
        assertThat(risk.get("successorKey").asText()).isEqualTo(succ);
        assertThat(risk.get("predecessorOldArrivalUtc").asText()).isEqualTo(iso(9));
        assertThat(risk.get("predecessorNewArrivalUtc").asText()).isEqualTo(iso(9, 50));
        assertThat(risk.get("requiredMinutes").asLong()).isEqualTo(30);
        assertThat(risk.get("actualMinutes").asLong()).isEqualTo(-20);
        assertThat(risk.get("deficitMinutes").asLong()).isEqualTo(50);
        // 可选改签时间窗：[09:50+30min, 次日 00:00)
        assertThat(risk.get("windowStartUtc").asText()).isEqualTo(iso(10, 20));
        assertThat(risk.get("windowEndUtc").asText()).isEqualTo(iso(0, 0, 1));

        // 接续随改签链指向新计划
        JsonNode connections = connectionsOf(succ);
        assertThat(connections).hasSize(1);
        assertThat(connections.get(0).get("predecessorKey").asText()).isEqualTo(predNew);
        assertThat(connections.get(0).get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void predecessorEarlierClearsFlagButKeepsRiskHistory() throws Exception {
        String section1 = key("SEC");
        String section2 = key("SEC");
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", section1, "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", section2, "STA-X", "STA-B", iso(9, 30), iso(10, 30)));
        publishPlan(succ);
        register(pred, succ, "STA-X", 30);

        String predV2 = key("SCH");
        createPlan(predV2, occ("G1", section1, "STA-A", "STA-X", iso(8, 30), iso(9, 50)));
        reschedule(pred, predV2);
        assertThat(planDetail(succ).get("connectionRisk").asText())
                .isEqualTo("CONNECTION_AT_RISK");

        // 前车再次改签提前：终到回到 09:00，保障恢复，标记清除
        String predV3 = key("SCH");
        createPlan(predV3, occ("G1", section1, "STA-A", "STA-X", iso(8), iso(9)));
        reschedule(predV2, predV3);
        JsonNode succDetail = planDetail(succ);
        assertThat(succDetail.get("connectionRisk").isNull()).isTrue();

        // 历史风险记录不改写、不删除
        JsonNode risks = risksOf(succ);
        assertThat(risks).hasSize(1);
        assertThat(risks.get(0).get("predecessorKey").asText()).isEqualTo(predV2);
        assertThat(risks.get(0).get("deficitMinutes").asLong()).isEqualTo(50);
    }

    @Test
    void cancelConnectionClearsFlagButKeepsRiskHistory() throws Exception {
        String section1 = key("SEC");
        String section2 = key("SEC");
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", section1, "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", section2, "STA-X", "STA-B", iso(9, 30), iso(10, 30)));
        publishPlan(succ);
        long connectionId = register(pred, succ, "STA-X", 30);

        String predNew = key("SCH");
        createPlan(predNew, occ("G1", section1, "STA-A", "STA-X", iso(8, 30), iso(9, 50)));
        reschedule(pred, predNew);
        assertThat(planDetail(succ).get("connectionRisk").asText())
                .isEqualTo("CONNECTION_AT_RISK");

        // 取消接续：风险标记按剩余生效接续重估（无接续则清除），历史风险记录不改写
        mvc.perform(post("/api/v1/connections/{id}/cancel", connectionId)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        assertThat(planDetail(succ).get("connectionRisk").isNull()).isTrue();
        assertThat(risksOf(succ)).hasSize(1);
    }

    @Test
    void startedPlanIsNotMarkedAsRescheduleCandidate() throws Exception {
        String section1 = key("SEC");
        String section2 = key("SEC");
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", section1, "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", section2, "STA-X", "STA-B", iso(9, 30), iso(10, 30)));
        publishPlan(succ);
        register(pred, succ, "STA-X", 30);

        // 时钟推进到后车始发之后：后车已开始，不可自动标记为候选改签
        when(clock.instant()).thenReturn(DAY.atTime(9, 45).atZone(SH).toInstant());
        String predNew = key("SCH");
        createPlan(predNew, occ("G1", section1, "STA-A", "STA-X", iso(8, 30), iso(9, 50)));
        reschedule(pred, predNew);

        // 风险记录仍生成（历史），但计划不标记 CONNECTION_AT_RISK
        assertThat(planDetail(succ).get("connectionRisk").isNull()).isTrue();
        JsonNode risks = risksOf(succ);
        assertThat(risks).hasSize(1);
        assertThat(risks.get(0).get("deficitMinutes").asLong()).isEqualTo(50);
    }

    // ---------- 辅助 ----------

    private JsonNode planDetail(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private JsonNode risksOf(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}/connection-risks", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private JsonNode connectionsOf(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}/connections", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private long register(String pred, String succ, String station, int guarantee) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"predecessorKey\":\""
                                + pred + "\",\"successorKey\":\"" + succ
                                + "\",\"transferStation\":\"" + station
                                + "\",\"guaranteeMinutes\":" + guarantee + "}"))
                .andExpect(status().isCreated()).andReturn();
        return read(result).get("id").asLong();
    }

    private void createPlan(String scheduleKey, String occupancy) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + occupancy + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    private void reschedule(String oldKey, String newKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"newScheduleKey\":\""
                                + newKey + "\",\"expectedOldVersion\":1,\"expectedNewVersion\":1}"))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour) {
        return iso(hour, 0, 0);
    }

    private static String iso(int hour, int minute) {
        return iso(hour, minute, 0);
    }

    private static String iso(int hour, int minute, int dayOffset) {
        return DAY.plusDays(dayOffset).atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String from, String to,
                              String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"fromStation\":\"" + from + "\",\"toStation\":\"" + to
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }
}
