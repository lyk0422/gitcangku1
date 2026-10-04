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
 * 接续保障主流程端到端测试（H2 内存库）：登记、改签联动风险标记、
 * 批量保障改签、跨零点、已开始计划豁免与历史不可改写。
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
        clock.setInstant(TestClockConfig.DEFAULT_INSTANT);
    }

    // ---------- 主流程 ----------

    @Test
    void registerConnectionThenQueryDetailAndHistory() throws Exception {
        String sec1 = key("SEC");
        String sec2 = key("SEC");
        createSection(sec1, "Alpha", "Beta");
        createSection(sec2, "Beta", "Gamma");
        String pred = key("SCH");
        String succ = key("SCH");
        createAndPublish(pred, DAY, occJson("G1", sec1, iso(8, 0), iso(9, 0)));
        createAndPublish(succ, DAY, occJson("G2", sec2, iso(9, 30), iso(10, 30)));

        MvcResult registered = registerConnection(key("REQ"), pred, succ, 20, false)
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(registered);
        long connId = body.get("id").asLong();
        assertThat(body.get("predecessorKey").asText()).isEqualTo(pred);
        assertThat(body.get("successorKey").asText()).isEqualTo(succ);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("requiredMinutes").asInt()).isEqualTo(20);
        assertThat(body.get("actualMinutes").asInt()).isEqualTo(30);
        assertThat(body.get("marginMinutes").asInt()).isEqualTo(10);
        assertThat(body.get("cancelledAt").isNull()).isTrue();

        // 明细查询只读且与登记响应一致
        MvcResult detail = mvc.perform(get("/api/v1/connections/{id}", connId))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);

        // 计划关联查询
        MvcResult byPlan = mvc.perform(get("/api/v1/plans/{key}/connections", succ))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(byPlan)).hasSize(1);
        assertThat(read(byPlan).get(0).get("id").asLong()).isEqualTo(connId);

        // 历史：仅 REGISTERED，固化前车版本与登记时刻，时间窗为 null
        MvcResult history = mvc.perform(get("/api/v1/connections/{id}/history", connId))
                .andExpect(status().isOk()).andReturn();
        JsonNode events = read(history).get("events");
        assertThat(events).hasSize(1);
        JsonNode e0 = events.get(0);
        assertThat(e0.get("eventType").asText()).isEqualTo("REGISTERED");
        assertThat(e0.get("predecessorKey").asText()).isEqualTo(pred);
        assertThat(e0.get("predecessorVersion").asInt()).isEqualTo(1);
        assertThat(e0.get("oldTimeUtc").asText()).isEqualTo(iso(9, 0));
        assertThat(e0.get("newTimeUtc").asText()).isEqualTo(iso(9, 30));
        assertThat(e0.get("requiredMinutes").asInt()).isEqualTo(20);
        assertThat(e0.get("actualMinutes").asInt()).isEqualTo(30);
        assertThat(e0.get("shortfallMinutes").asInt()).isEqualTo(-10);
        assertThat(e0.get("windowStartUtc").isNull()).isTrue();
        assertThat(e0.get("windowEndUtc").isNull()).isTrue();
    }

    @Test
    void predecessorDelayMarksSuccessorAtRiskWithoutChangingTimes() throws Exception {
        String sec1 = key("SEC");
        String sec2 = key("SEC");
        createSection(sec1, "Alpha", "Beta");
        createSection(sec2, "Beta", "Gamma");
        String pred = key("SCH");
        String succ = key("SCH");
        createAndPublish(pred, DAY, occJson("G1", sec1, iso(8, 0), iso(9, 0)));
        createAndPublish(succ, DAY, occJson("G2", sec2, iso(9, 30), iso(10, 30)));
        long connId = read(registerConnection(key("REQ"), pred, succ, 20, false)
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();

        // 前车改签延后：终到从 09:00 延至 10:20，同一事务内重算直接接续
        String pred2 = key("SCH");
        createPlan(pred2, DAY, occJson("G1", sec1, iso(9, 20), iso(10, 20)));
        reschedule(pred, pred2);

        // 后车标记 CONNECTION_AT_RISK，但时刻不被自动修改
        MvcResult succDetail = mvc.perform(get("/api/v1/plans/{key}", succ))
                .andExpect(status().isOk()).andReturn();
        JsonNode succBody = read(succDetail);
        assertThat(succBody.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(succBody.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(9, 30));

        // 诊断查询：风险后车、保障差额与可选改签时间窗
        MvcResult risks = mvc.perform(get("/api/v1/connections/risks"))
                .andExpect(status().isOk()).andReturn();
        JsonNode risk = riskFor(read(risks), succ);
        assertThat(risk).isNotNull();
        assertThat(risk.get("scheduleKey").asText()).isEqualTo(succ);
        assertThat(risk.get("firstStartUtc").asText()).isEqualTo(iso(9, 30));
        JsonNode rc = risk.get("connections").get(0);
        assertThat(rc.get("connectionId").asLong()).isEqualTo(connId);
        assertThat(rc.get("predecessorKey").asText()).isEqualTo(pred2);
        assertThat(rc.get("requiredMinutes").asInt()).isEqualTo(20);
        assertThat(rc.get("actualMinutes").asInt()).isEqualTo(-50);
        assertThat(rc.get("shortfallMinutes").asInt()).isEqualTo(70);
        assertThat(rc.get("windowStartUtc").asText()).isEqualTo(iso(10, 40));
        assertThat(rc.get("windowEndUtc").asText()).isEqualTo(iso(23, 0));

        // 接续跟随到新计划
        MvcResult conn = mvc.perform(get("/api/v1/connections/{id}", connId))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(conn).get("predecessorKey").asText()).isEqualTo(pred2);

        // 历史追加 RISK：固化前车版本、原新时刻与保障差额
        JsonNode events = read(mvc.perform(get("/api/v1/connections/{id}/history", connId))
                .andExpect(status().isOk()).andReturn()).get("events");
        assertThat(events).hasSize(2);
        JsonNode riskEvent = events.get(1);
        assertThat(riskEvent.get("eventType").asText()).isEqualTo("RISK");
        assertThat(riskEvent.get("predecessorKey").asText()).isEqualTo(pred2);
        assertThat(riskEvent.get("predecessorVersion").asInt()).isEqualTo(1);
        assertThat(riskEvent.get("oldTimeUtc").asText()).isEqualTo(iso(9, 0));
        assertThat(riskEvent.get("newTimeUtc").asText()).isEqualTo(iso(10, 20));
        assertThat(riskEvent.get("requiredMinutes").asInt()).isEqualTo(20);
        assertThat(riskEvent.get("actualMinutes").asInt()).isEqualTo(-50);
        assertThat(riskEvent.get("shortfallMinutes").asInt()).isEqualTo(70);
        assertThat(riskEvent.get("windowStartUtc").asText()).isEqualTo(iso(10, 40));
        assertThat(riskEvent.get("windowEndUtc").asText()).isEqualTo(iso(23, 0));
    }
    @Test
    void batchGuaranteeRescheduleShiftsSuccessorAndClearsRisk() throws Exception {
        String sec1 = key("SEC");
        String sec2 = key("SEC");
        createSection(sec1, "Alpha", "Beta");
        createSection(sec2, "Beta", "Gamma");
        String pred = key("SCH");
        String succ = key("SCH");
        createAndPublish(pred, DAY, occJson("G1", sec1, iso(8, 0), iso(9, 0)));
        createAndPublish(succ, DAY, occJson("G2", sec2, iso(9, 30), iso(10, 30)));
        long connId = read(registerConnection(key("REQ"), pred, succ, 20, false)
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        String pred2 = key("SCH");
        createPlan(pred2, DAY, occJson("G1", sec1, iso(9, 20), iso(10, 20)));
        reschedule(pred, pred2);

        // 批量保障改签：后车整体平移 70 分钟，满足线路时隙与接续分钟数
        MvcResult batch = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ")
                                + "\",\"scheduleKeys\":[\"" + succ + "\"]}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(batch).get("items");
        assertThat(items).hasSize(1);
        JsonNode item = items.get(0);
        assertThat(item.get("scheduleKey").asText()).isEqualTo(succ);
        assertThat(item.get("version").asInt()).isEqualTo(2);
        assertThat(item.get("oldFirstStartUtc").asText()).isEqualTo(iso(9, 30));
        assertThat(item.get("newFirstStartUtc").asText()).isEqualTo(iso(10, 40));
        assertThat(item.get("shiftMinutes").asInt()).isEqualTo(70);

        // 已发布时刻平移、版本加一、风险标记清除
        JsonNode succBody = read(mvc.perform(get("/api/v1/plans/{key}", succ))
                .andExpect(status().isOk()).andReturn());
        assertThat(succBody.get("version").asInt()).isEqualTo(2);
        assertThat(succBody.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(succBody.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(10, 40));
        assertThat(succBody.get("occupancies").get(0).get("endUtc").asText())
                .isEqualTo(iso(11, 40));
        assertThat(riskFor(read(mvc.perform(get("/api/v1/connections/risks"))
                .andExpect(status().isOk()).andReturn()), succ)).isNull();

        // 时隙占用同步平移
        JsonNode slots = read(mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", sec2))
                .andExpect(status().isOk()).andReturn());
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).get("startUtc").asText()).isEqualTo(iso(10, 40));

        // 决策固化：RESCHEDULED 事件记录原新时刻与保障差额（满足后为 0）
        JsonNode events = read(mvc.perform(get("/api/v1/connections/{id}/history", connId))
                .andExpect(status().isOk()).andReturn()).get("events");
        assertThat(events).hasSize(3);
        JsonNode decision = events.get(2);
        assertThat(decision.get("eventType").asText()).isEqualTo("RESCHEDULED");
        assertThat(decision.get("predecessorKey").asText()).isEqualTo(pred2);
        assertThat(decision.get("predecessorVersion").asInt()).isEqualTo(1);
        assertThat(decision.get("oldTimeUtc").asText()).isEqualTo(iso(9, 30));
        assertThat(decision.get("newTimeUtc").asText()).isEqualTo(iso(10, 40));
        assertThat(decision.get("requiredMinutes").asInt()).isEqualTo(20);
        assertThat(decision.get("actualMinutes").asInt()).isEqualTo(20);
        assertThat(decision.get("shortfallMinutes").asInt()).isEqualTo(0);
        assertThat(decision.get("windowStartUtc").isNull()).isTrue();
    }

    @Test
    void batchGuaranteeRescheduleWithoutFeasibleWindowRollsBackAll() throws Exception {
        String sec1 = key("SEC");
        String sec2 = key("SEC");
        createSection(sec1, "Alpha", "Beta");
        createSection(sec2, "Beta", "Gamma");
        String pred = key("SCH");
        String succ = key("SCH");
        createAndPublish(pred, DAY, occJson("G1", sec1, iso(8, 0), iso(9, 0)));
        createAndPublish(succ, DAY, occJson("G2", sec2, iso(9, 30), iso(10, 30)));
        long connId = read(registerConnection(key("REQ"), pred, succ, 20, false)
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        String pred2 = key("SCH");
        createPlan(pred2, DAY, occJson("G1", sec1, iso(9, 20), iso(10, 20)));
        reschedule(pred, pred2);
        // 第三方计划占满 10:30 之后到运营日结束的区段，后车无可行时间窗
        String blocker = key("SCH");
        createAndPublish(blocker, DAY, occJson("G9", sec2, iso(10, 30), iso(23, 59)));

        MvcResult failed = mvc.perform(post("/api/v1/connections/guarantee-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ")
                                + "\",\"scheduleKeys\":[\"" + succ + "\"]}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(failed);
        assertThat(error.get("code").asText()).isEqualTo("NO_FEASIBLE_WINDOW");
        assertThat(error.get("details").get(0).get("scheduleKey").asText()).isEqualTo(succ);
        assertThat(error.get("details").get(0).get("requiredStartUtc").asText())
                .isEqualTo(iso(10, 40));

        // 整次回滚：已发布时刻、风险标记、时隙占用与历史均不变
        JsonNode succBody = read(mvc.perform(get("/api/v1/plans/{key}", succ))
                .andExpect(status().isOk()).andReturn());
        assertThat(succBody.get("version").asInt()).isEqualTo(1);
        assertThat(succBody.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(9, 30));
        JsonNode risks = read(mvc.perform(get("/api/v1/connections/risks"))
                .andExpect(status().isOk()).andReturn());
        assertThat(riskFor(risks, succ)).isNotNull();
        JsonNode slots = read(mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", sec2))
                .andExpect(status().isOk()).andReturn());
        assertThat(slots).hasSize(2);
        assertThat(slots.get(0).get("scheduleKey").asText()).isEqualTo(succ);
        assertThat(slots.get(0).get("startUtc").asText()).isEqualTo(iso(9, 30));
        JsonNode events = read(mvc.perform(get("/api/v1/connections/{id}/history", connId))
                .andExpect(status().isOk()).andReturn()).get("events");
        assertThat(events).hasSize(2);
        assertThat(events.get(1).get("eventType").asText()).isEqualTo("RISK");
    }
    @Test
    void crossMidnightConnectionRequiresExplicitFlag() throws Exception {
        String sec1 = key("SEC");
        String sec2 = key("SEC");
        createSection(sec1, "Alpha", "Beta");
        createSection(sec2, "Beta", "Gamma");
        String pred = key("SCH");
        String succ = key("SCH");
        createAndPublish(pred, DAY, occJson("G1", sec1, iso(23, 0), iso(23, 50)));
        createAndPublish(succ, DAY.plusDays(1),
                occJson("G2", sec2, isoNextDay(0, 20), isoNextDay(1, 0)));

        // 未明确跨零点：跨运营日登记返回 422
        MvcResult rejected = registerConnection(key("REQ"), pred, succ, 20, false)
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(rejected).get("code").asText()).isEqualTo("OP_DATE_MISMATCH");

        // 明确跨零点：登记成功，实际接续 30 分钟
        MvcResult registered = registerConnection(key("REQ"), pred, succ, 20, true)
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(registered);
        assertThat(body.get("crossesMidnight").asBoolean()).isTrue();
        assertThat(body.get("actualMinutes").asInt()).isEqualTo(30);
        assertThat(body.get("marginMinutes").asInt()).isEqualTo(10);
    }

    @Test
    void startedSuccessorIsNotMarkedAsRescheduleCandidate() throws Exception {
        String sec1 = key("SEC");
        String sec2 = key("SEC");
        createSection(sec1, "Alpha", "Beta");
        createSection(sec2, "Beta", "Gamma");
        String pred = key("SCH");
        String succ = key("SCH");
        createAndPublish(pred, DAY, occJson("G1", sec1, iso(8, 0), iso(9, 0)));
        createAndPublish(succ, DAY, occJson("G2", sec2, iso(9, 30), iso(10, 30)));
        long connId = read(registerConnection(key("REQ"), pred, succ, 20, false)
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();

        // 时钟推进到后车始发之后：已开始计划不可自动标记为候选改签
        clock.setInstant(DAY.atTime(9, 45).atZone(SH).toInstant());
        String pred2 = key("SCH");
        createPlan(pred2, DAY, occJson("G1", sec1, iso(9, 20), iso(10, 20)));
        reschedule(pred, pred2);

        assertThat(riskFor(read(mvc.perform(get("/api/v1/connections/risks"))
                .andExpect(status().isOk()).andReturn()), succ)).isNull();
        JsonNode events = read(mvc.perform(get("/api/v1/connections/{id}/history", connId))
                .andExpect(status().isOk()).andReturn()).get("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("eventType").asText()).isEqualTo("REGISTERED");
    }

    @Test
    void cancelConnectionKeepsHistoryAndClearsRiskMark() throws Exception {
        String sec1 = key("SEC");
        String sec2 = key("SEC");
        createSection(sec1, "Alpha", "Beta");
        createSection(sec2, "Beta", "Gamma");
        String pred = key("SCH");
        String succ = key("SCH");
        createAndPublish(pred, DAY, occJson("G1", sec1, iso(8, 0), iso(9, 0)));
        createAndPublish(succ, DAY, occJson("G2", sec2, iso(9, 30), iso(10, 30)));
        long connId = read(registerConnection(key("REQ"), pred, succ, 20, false)
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        String pred2 = key("SCH");
        createPlan(pred2, DAY, occJson("G1", sec1, iso(9, 20), iso(10, 20)));
        reschedule(pred, pred2);

        // 取消前历史快照
        JsonNode before = read(mvc.perform(get("/api/v1/connections/{id}/history", connId))
                .andExpect(status().isOk()).andReturn()).get("events");
        assertThat(before).hasSize(2);

        String cancelKey = key("REQ");
        MvcResult cancelled = mvc.perform(post("/api/v1/connections/{id}/cancel", connId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + cancelKey + "\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode cancelledBody = read(cancelled);
        assertThat(cancelledBody.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelledBody.get("cancelledAt").isNull()).isFalse();

        // 风险标记清除，历史只追加不改写：旧事件逐字段一致
        assertThat(riskFor(read(mvc.perform(get("/api/v1/connections/risks"))
                .andExpect(status().isOk()).andReturn()), succ)).isNull();
        JsonNode after = read(mvc.perform(get("/api/v1/connections/{id}/history", connId))
                .andExpect(status().isOk()).andReturn()).get("events");
        assertThat(after).hasSize(3);
        assertThat(after.get(0)).isEqualTo(before.get(0));
        assertThat(after.get(1)).isEqualTo(before.get(1));
        JsonNode cancelEvent = after.get(2);
        assertThat(cancelEvent.get("eventType").asText()).isEqualTo("CANCELLED");
        assertThat(cancelEvent.get("oldTimeUtc").isNull()).isTrue();
        assertThat(cancelEvent.get("newTimeUtc").isNull()).isTrue();
        assertThat(cancelEvent.get("requiredMinutes").isNull()).isTrue();

        // 同键重放返回首次结果；异键重复取消返回 409
        MvcResult replay = mvc.perform(post("/api/v1/connections/{id}/cancel", connId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + cancelKey + "\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(cancelledBody);
        mvc.perform(post("/api/v1/connections/{id}/cancel", connId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isConflict());
    }
    // ---------- 辅助 ----------

    private void createSection(String sectionId, String from, String to) throws Exception {
        mvc.perform(post("/api/v1/sections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sectionId\":\"" + sectionId + "\",\"fromStation\":\"" + from
                                + "\",\"toStation\":\"" + to + "\"}"))
                .andExpect(status().isCreated());
    }

    private void createPlan(String scheduleKey, LocalDate opDate, String... occupancies)
            throws Exception {
        mvc.perform(post("/api/v1/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + opDate
                                + "\",\"occupancies\":[" + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void createAndPublish(String scheduleKey, LocalDate opDate, String... occupancies)
            throws Exception {
        createPlan(scheduleKey, opDate, occupancies);
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isOk());
    }

    private void reschedule(String oldKey, String newKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"newScheduleKey\":\""
                                + newKey + "\",\"expectedOldVersion\":1,\"expectedNewVersion\":1}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions registerConnection(
            String requestKey, String pred, String succ, int guarantee, boolean crossesMidnight)
            throws Exception {
        return mvc.perform(post("/api/v1/connections")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestKey\":\"" + requestKey + "\",\"predecessorKey\":\"" + pred
                        + "\",\"successorKey\":\"" + succ + "\",\"guaranteeMinutes\":" + guarantee
                        + ",\"crossesMidnight\":" + crossesMidnight + "}"));
    }

    private static String occJson(String trainNo, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + trainNo + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String isoNextDay(int hour, int minute) {
        return DAY.plusDays(1).atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    /**
     * 在共享库的全局风险列表中按业务键过滤出本用例的风险项，无则返回 null。
     */
    private static JsonNode riskFor(JsonNode risks, String scheduleKey) {
        for (JsonNode r : risks) {
            if (r.get("scheduleKey").asText().equals(scheduleKey)) {
                return r;
            }
        }
        return null;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}