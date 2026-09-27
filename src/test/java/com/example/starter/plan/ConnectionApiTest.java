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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 接续登记/取消 API 主流程、422 失败分支、幂等与历史查询的端到端测试（H2 内存库）。
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

    // ---------- 主流程 ----------

    @Test
    void registerThenQueryDetailAndPlanConnections() throws Exception {
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", key("SEC"), "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", key("SEC"), "STA-X", "STA-B", iso(9, 30), iso(10, 30)));
        publishPlan(succ);

        MvcResult registered = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, "STA-X", 30, null)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(registered);
        long id = body.get("id").asLong();
        assertThat(body.get("predecessorKey").asText()).isEqualTo(pred);
        assertThat(body.get("successorKey").asText()).isEqualTo(succ);
        assertThat(body.get("transferStation").asText()).isEqualTo("STA-X");
        assertThat(body.get("guaranteeMinutes").asInt()).isEqualTo(30);
        assertThat(body.get("crossMidnight").asBoolean()).isFalse();
        assertThat(body.get("predecessorVersion").asInt()).isEqualTo(1);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");

        // 明细查询与登记结果一致，读取不改变状态
        MvcResult detail = mvc.perform(get("/api/v1/connections/{id}", id))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);

        // 计划维度查询：前车与后车均可见该接续
        for (String any : new String[]{pred, succ}) {
            MvcResult list = mvc.perform(get("/api/v1/plans/{key}/connections", any))
                    .andExpect(status().isOk()).andReturn();
            JsonNode items = read(list);
            assertThat(items).hasSize(1);
            assertThat(items.get(0).get("id").asLong()).isEqualTo(id);
        }
    }

    @Test
    void registerCrossMidnightRequiresExplicitFlag() throws Exception {
        String pred = key("SCH");
        String succ = key("SCH");
        // 前车 23:00-24:00（运营日当日），后车次日 00:30-01:30
        createPlan(pred, occ("G1", key("SEC"), "STA-A", "STA-X", iso(23), iso(0, 0, 1)));
        publishPlan(pred);
        createPlanOnDate(succ, DAY.plusDays(1),
                occ("G2", key("SEC"), "STA-X", "STA-B", iso(0, 30, 1), iso(1, 30, 1)));
        publishPlan(succ);

        // 未显式声明跨零点 → 422
        MvcResult rejected = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, "STA-X", 20, null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(rejected).get("code").asText()).isEqualTo("CONNECTION_OP_DATE_INVALID");

        // 显式声明跨零点 → 201
        MvcResult registered = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, "STA-X", 20, true)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(registered).get("crossMidnight").asBoolean()).isTrue();
    }

    // ---------- 参数非法 400 ----------

    @Test
    void registerRejectsSamePredecessorAndSuccessor() throws Exception {
        String plan = key("SCH");
        createPlan(plan, occ("G1", key("SEC"), "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(plan);
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), plan, plan, "STA-X", 30, null)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 保障分钟数范围 422 ----------

    @Test
    void registerRejectsGuaranteeMinutesOutOfRange() throws Exception {
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", key("SEC"), "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", key("SEC"), "STA-X", "STA-B", iso(10, 0), iso(11, 0)));
        publishPlan(succ);

        // 低于下限 5：响应携带实际值与要求范围
        MvcResult tooSmall = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, "STA-X", 4, null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(tooSmall);
        assertThat(error.get("code").asText()).isEqualTo("GUARANTEE_MINUTES_INVALID");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("actualMinutes").asInt()).isEqualTo(4);
        assertThat(detail.get("minMinutes").asInt()).isEqualTo(5);
        assertThat(detail.get("maxMinutes").asInt()).isEqualTo(720);

        // 高于上限 720
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, "STA-X", 721, null)))
                .andExpect(status().isUnprocessableEntity());

        // 失败不占键、不产生接续：边界值 5 可登记成功
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, "STA-X", 5, null)))
                .andExpect(status().isCreated());

        // 上限 720 合法（另一对计划）
        String pred2 = key("SCH");
        String succ2 = key("SCH");
        createPlan(pred2, occ("G3", key("SEC"), "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred2);
        createPlan(succ2, occ("G4", key("SEC"), "STA-X", "STA-B", iso(21, 0), iso(22, 0)));
        publishPlan(succ2);
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred2, succ2, "STA-X", 720, null)))
                .andExpect(status().isCreated());
    }

    // ---------- 站点不连续 422 ----------

    @Test
    void registerRejectsStationGap() throws Exception {
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", key("SEC"), "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", key("SEC"), "STA-X", "STA-B", iso(9, 30), iso(10, 30)));
        publishPlan(succ);

        // 换乘站与前车终到站不一致
        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, "STA-Y", 30, null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("CONNECTION_STATION_GAP");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("transferStation").asText()).isEqualTo("STA-Y");
        assertThat(detail.get("predecessorArrivalStation").asText()).isEqualTo("STA-X");
        assertThat(detail.get("successorDepartureStation").asText()).isEqualTo("STA-X");

        // 未登记站点的占用无法校验连续性 → 422
        String noStationPred = key("SCH");
        String noStationSucc = key("SCH");
        createPlan(noStationPred, occNoStation("G3", key("SEC"), iso(8), iso(9)));
        publishPlan(noStationPred);
        createPlan(noStationSucc, occNoStation("G4", key("SEC"), iso(9, 30), iso(10, 30)));
        publishPlan(noStationSucc);
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), noStationPred, noStationSucc, "STA-X",
                                30, null)))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 运营日约束 422 ----------

    @Test
    void registerRejectsInvalidOperationDates() throws Exception {
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", key("SEC"), "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        // 后车为前天后天（相隔两天），即使声明跨零点也非法
        createPlanOnDate(succ, DAY.plusDays(2),
                occ("G2", key("SEC"), "STA-X", "STA-B", iso(9, 30, 2), iso(10, 30, 2)));
        publishPlan(succ);
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, "STA-X", 30, true)))
                .andExpect(status().isUnprocessableEntity());

        // 后车运营日早于前车 → 422
        String pred2 = key("SCH");
        String succ2 = key("SCH");
        createPlanOnDate(pred2, DAY.plusDays(1),
                occ("G3", key("SEC"), "STA-A", "STA-X", iso(8, 0, 1), iso(9, 0, 1)));
        publishPlan(pred2);
        createPlan(succ2, occ("G4", key("SEC"), "STA-X", "STA-B", iso(9, 30), iso(10, 30)));
        publishPlan(succ2);
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred2, succ2, "STA-X", 30, true)))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 接续时间不足 422 ----------

    @Test
    void registerRejectsInsufficientGap() throws Exception {
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", key("SEC"), "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        // 实际接续 20 分钟 < 要求 30 分钟
        createPlan(succ, occ("G2", key("SEC"), "STA-X", "STA-B", iso(9, 20), iso(10, 20)));
        publishPlan(succ);

        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, "STA-X", 30, null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("CONNECTION_GAP_INSUFFICIENT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("requiredMinutes").asLong()).isEqualTo(30);
        assertThat(detail.get("actualMinutes").asLong()).isEqualTo(20);
        assertThat(detail.get("deficitMinutes").asLong()).isEqualTo(10);
    }

    // ---------- 计划状态 422/409 ----------

    @Test
    void registerRejectsCancelledPlanWith422AndDraftWith409() throws Exception {
        String pred = key("SCH");
        String cancelled = key("SCH");
        createPlan(pred, occ("G1", key("SEC"), "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        createPlan(cancelled, occ("G2", key("SEC"), "STA-X", "STA-B", iso(9, 30), iso(10, 30)));
        publishPlan(cancelled);
        cancelPlan(cancelled);

        // 计划已取消 → 422
        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, cancelled, "STA-X", 30, null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("CONNECTION_PLAN_CANCELLED");

        // 草稿计划 → 409
        String draft = key("SCH");
        createPlan(draft, occ("G3", key("SEC"), "STA-X", "STA-B", iso(9, 30), iso(10, 30)));
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, draft, "STA-X", 30, null)))
                .andExpect(status().isConflict());
    }

    // ---------- 不存在 404 ----------

    @Test
    void missingPlanOrConnectionReturns404() throws Exception {
        String pred = key("SCH");
        createPlan(pred, occ("G1", key("SEC"), "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);

        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, key("SCH"), "STA-X", 30, null)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/connections/{id}", 999999999L))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/connections/{id}/cancel", 999999999L)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/connections", key("SCH")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/connection-risks", key("SCH")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plans/{key}/guarantee-decisions", key("SCH")))
                .andExpect(status().isNotFound());
    }

    // ---------- 重复登记 409 与幂等 ----------

    @Test
    void registerIdempotentReplayAndDuplicateConflict() throws Exception {
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", key("SEC"), "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", key("SEC"), "STA-X", "STA-B", iso(9, 30), iso(10, 30)));
        publishPlan(succ);

        String requestKey = key("REQ");
        String body = registerBody(requestKey, pred, succ, "STA-X", 30, null);
        MvcResult first = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放返回首次结果
        MvcResult replay = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, pred, succ, "STA-X", 40, null)))
                .andExpect(status().isConflict());

        // 不同键重复登记同一对计划 → 409 CONNECTION_EXISTS
        MvcResult duplicate = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, "STA-X", 30, null)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(duplicate).get("code").asText()).isEqualTo("CONNECTION_EXISTS");
    }

    // ---------- 取消接续 ----------

    @Test
    void cancelConnectionKeepsHistoryAndAllowsReregister() throws Exception {
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", key("SEC"), "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", key("SEC"), "STA-X", "STA-B", iso(9, 30), iso(10, 30)));
        publishPlan(succ);
        long id = register(pred, succ, "STA-X", 30);

        String cancelKey = key("REQ");
        MvcResult cancelled = mvc.perform(post("/api/v1/connections/{id}/cancel", id)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(cancelKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(cancelled).get("status").asText()).isEqualTo("CANCELLED");

        // 同键重放返回首次结果
        MvcResult replay = mvc.perform(post("/api/v1/connections/{id}/cancel", id)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(cancelKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(cancelled));

        // 已取消不可再取消（新键）→ 409
        mvc.perform(post("/api/v1/connections/{id}/cancel", id)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());

        // 历史保留：明细与计划维度查询仍可见已取消接续
        MvcResult detail = mvc.perform(get("/api/v1/connections/{id}", id))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("status").asText()).isEqualTo("CANCELLED");
        MvcResult list = mvc.perform(get("/api/v1/plans/{key}/connections", pred))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(list)).hasSize(1);

        // 取消后可重新登记同一对计划
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, "STA-X", 30, null)))
                .andExpect(status().isCreated());
        MvcResult after = mvc.perform(get("/api/v1/plans/{key}/connections", pred))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after)).hasSize(2);
    }

    @Test
    void cancelPlanCancelsItsActiveConnections() throws Exception {
        String pred = key("SCH");
        String succ = key("SCH");
        createPlan(pred, occ("G1", key("SEC"), "STA-A", "STA-X", iso(8), iso(9)));
        publishPlan(pred);
        createPlan(succ, occ("G2", key("SEC"), "STA-X", "STA-B", iso(9, 30), iso(10, 30)));
        publishPlan(succ);
        long id = register(pred, succ, "STA-X", 30);

        cancelPlan(pred);

        MvcResult detail = mvc.perform(get("/api/v1/connections/{id}", id))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("status").asText()).isEqualTo("CANCELLED");
    }

    // ---------- 辅助 ----------

    private long register(String pred, String succ, String station, int guarantee) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, station, guarantee, null)))
                .andExpect(status().isCreated()).andReturn();
        return read(result).get("id").asLong();
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        createPlanOnDate(scheduleKey, DAY, occupancies);
    }

    private void createPlanOnDate(String scheduleKey, LocalDate opDate, String... occupancies)
            throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + opDate + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    private void cancelPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        return iso(hour, minute, 0);
    }

    /** 运营日当日 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour) {
        return iso(hour, 0, 0);
    }

    /** 运营日加 dayOffset 天后 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute, int dayOffset) {
        Instant instant = DAY.plusDays(dayOffset).atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String occ(String train, String section, String from, String to,
                              String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"fromStation\":\"" + from + "\",\"toStation\":\"" + to
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String occNoStation(String train, String section, String startIso,
                                       String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String registerBody(String requestKey, String pred, String succ,
                                       String station, int guarantee, Boolean crossMidnight) {
        return "{\"requestKey\":\"" + requestKey + "\",\"predecessorKey\":\"" + pred
                + "\",\"successorKey\":\"" + succ + "\",\"transferStation\":\"" + station
                + "\",\"guaranteeMinutes\":" + guarantee
                + (crossMidnight == null ? "" : ",\"crossMidnight\":" + crossMidnight) + "}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }
}
