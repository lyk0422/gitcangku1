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
 * 车底交路衔接与最小周转校验的端到端测试（H2 内存库）：
 * 发布并入链校验、周转参数重校验、断链记录与待重排、改签回滚与幂等。
 * 各用例使用唯一 stockKey/scheduleKey/requestKey/区段 ID，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanTurnaroundApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程：登记车底 → 发布成链 → 链明细 ----------

    @Test
    void publishBuildsContinuousChainAndQueryDetail() throws Exception {
        String stock = key("STK");
        createStock(stock, 30);
        String planA = key("SCH");
        String planB = key("SCH");
        createPlanWithStock(planA, stock, "S1", "S2", occ("G1", key("SEC"), iso(8), iso(9)));
        createPlanWithStock(planB, stock, "S2", "S3", occ("G2", key("SEC"), iso(9, 30), iso(10, 30)));

        // 先发布后段再发布前段，并入时整体校验
        publishPlan(planB, key("REQ"));
        publishPlan(planA, key("REQ"));

        MvcResult chain = mvc.perform(get("/api/v1/rolling-stocks/{stock}/chain", stock)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(chain);
        assertThat(body.get("stockKey").asText()).isEqualTo(stock);
        assertThat(body.get("minTurnaroundMinutes").asInt()).isEqualTo(30);
        JsonNode segments = body.get("segments");
        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).get("scheduleKey").asText()).isEqualTo(planA);
        assertThat(segments.get(0).get("chainState").asText()).isEqualTo("NORMAL");
        JsonNode link = segments.get(0).get("linkToNext");
        assertThat(link.get("nextScheduleKey").asText()).isEqualTo(planB);
        assertThat(link.get("stationConnected").asBoolean()).isTrue();
        assertThat(link.get("actualGapMinutes").asLong()).isEqualTo(30);
        assertThat(link.get("requiredMinutes").asInt()).isEqualTo(30);
        assertThat(link.get("satisfied").asBoolean()).isTrue();
        assertThat(segments.get(1).get("linkToNext").isNull()).isTrue();
    }

    // ---------- 发布并入链校验 422 ----------

    @Test
    void publishRejectsStationMismatchWithBreakpointDetail() throws Exception {
        String stock = key("STK");
        createStock(stock, 30);
        String planA = key("SCH");
        String planB = key("SCH");
        createPlanWithStock(planA, stock, "S1", "S2", occ("G1", key("SEC"), iso(8), iso(9)));
        // B 始发站 S3 不等于 A 终到站 S2
        createPlanWithStock(planB, stock, "S3", "S4", occ("G2", key("SEC"), iso(9, 30), iso(10, 30)));
        publishPlan(planA, key("REQ"));

        String publishKey = key("REQ");
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", planB)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(publishKey)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("CHAIN_LINK_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("LINK_BROKEN");
        assertThat(detail.get("prevScheduleKey").asText()).isEqualTo(planA);
        assertThat(detail.get("nextScheduleKey").asText()).isEqualTo(planB);
        assertThat(detail.get("actualGapMinutes").asLong()).isEqualTo(30);
        assertThat(detail.get("requiredMinutes").asInt()).isEqualTo(30);

        // 计划保持草稿；失败不占键，修正站点后同键重发成功
        assertThat(getPlan(planB).get("status").asText()).isEqualTo("DRAFT");
        String fixed = key("SCH");
        createPlanWithStock(fixed, stock, "S2", "S4", occ("G3", key("SEC"), iso(9, 30), iso(10, 30)));
        publishPlan(fixed, key("REQ"));
    }

    @Test
    void publishRejectsInsufficientTurnaroundWithGapDetail() throws Exception {
        String stock = key("STK");
        createStock(stock, 30);
        String planA = key("SCH");
        String planB = key("SCH");
        createPlanWithStock(planA, stock, "S1", "S2", occ("G1", key("SEC"), iso(8), iso(9)));
        // 间隔仅 20 分钟，小于最小周转 30 分钟
        createPlanWithStock(planB, stock, "S2", "S3", occ("G2", key("SEC"), iso(9, 20), iso(10, 20)));
        publishPlan(planA, key("REQ"));

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", planB)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("CHAIN_LINK_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("TURNAROUND_INSUFFICIENT");
        assertThat(detail.get("prevScheduleKey").asText()).isEqualTo(planA);
        assertThat(detail.get("nextScheduleKey").asText()).isEqualTo(planB);
        assertThat(detail.get("actualGapMinutes").asLong()).isEqualTo(20);
        assertThat(detail.get("requiredMinutes").asInt()).isEqualTo(30);
        assertThat(getPlan(planB).get("status").asText()).isEqualTo("DRAFT");
    }

    // ---------- 创建草稿的车底登记参数校验 ----------

    @Test
    void createRejectsUnregisteredOrPartialStockInfo() throws Exception {
        // 车底未登记
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"), key("STK"), "S1", "S2",
                                occ("G1", key("SEC"), iso(8), iso(9)))))
                .andExpect(status().isBadRequest());
        // 仅车底标识，缺首末站
        String stock = key("STK");
        createStock(stock, 30);
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + key("SCH") + "\",\"opDate\":\"" + DAY + "\",\"stockKey\":\""
                                + stock + "\",\"occupancies\":["
                                + occ("G1", key("SEC"), iso(8), iso(9)) + "]}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 取消中间段：断链记录 + 待重排 ----------

    @Test
    void cancelMiddleSegmentWritesBreakRecordAndMarksPendingReplan() throws Exception {
        String stock = key("STK");
        createStock(stock, 30);
        String planA = key("SCH");
        String planB = key("SCH");
        String planC = key("SCH");
        createPlanWithStock(planA, stock, "S1", "S2", occ("G1", key("SEC"), iso(8), iso(9)));
        createPlanWithStock(planB, stock, "S2", "S3", occ("G2", key("SEC"), iso(9, 30), iso(10, 30)));
        createPlanWithStock(planC, stock, "S3", "S4", occ("G3", key("SEC"), iso(11), iso(12)));
        publishPlan(planA, key("REQ"));
        publishPlan(planB, key("REQ"));
        publishPlan(planC, key("REQ"));

        cancelPlan(planB, key("REQ"));

        // 断链记录：断点 A → C，被移除段为 B
        MvcResult breaks = mvc.perform(get("/api/v1/rolling-stocks/{stock}/breaks", stock))
                .andExpect(status().isOk()).andReturn();
        JsonNode records = read(breaks);
        assertThat(records).hasSize(1);
        JsonNode record = records.get(0);
        assertThat(record.get("stockKey").asText()).isEqualTo(stock);
        assertThat(record.get("cancelledScheduleKey").asText()).isEqualTo(planB);
        assertThat(record.get("prevScheduleKey").asText()).isEqualTo(planA);
        assertThat(record.get("nextScheduleKey").asText()).isEqualTo(planC);

        // 后续段 C 标记为待重排
        assertThat(getPlan(planC).get("chainState").asText()).isEqualTo("PENDING_REPLAN");
        assertThat(getPlan(planA).get("chainState").asText()).isEqualTo("NORMAL");

        // 补链发布衔接段后链恢复连续，待重排标记清除
        String bridge = key("SCH");
        createPlanWithStock(bridge, stock, "S2", "S3",
                occ("G4", key("SEC"), iso(9, 30), iso(10, 30)));
        publishPlan(bridge, key("REQ"));
        assertThat(getPlan(planC).get("chainState").asText()).isEqualTo("NORMAL");
    }

    // ---------- 周转参数修改：版本冲突 / 全链重校验 / 幂等 ----------

    @Test
    void updateTurnaroundRevalidatesAllPublishedLinks() throws Exception {
        String stock = key("STK");
        createStock(stock, 30);
        String planA = key("SCH");
        String planB = key("SCH");
        createPlanWithStock(planA, stock, "S1", "S2", occ("G1", key("SEC"), iso(8), iso(9)));
        createPlanWithStock(planB, stock, "S2", "S3", occ("G2", key("SEC"), iso(9, 30), iso(10, 30)));
        publishPlan(planA, key("REQ"));
        publishPlan(planB, key("REQ"));

        // expectedVersion 不匹配 → 409
        mvc.perform(put("/api/v1/rolling-stocks/{stock}/turnaround", stock)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(turnaroundBody(key("REQ"), 5, 40)))
                .andExpect(status().isConflict());

        // 新参数 40 分钟下间隔 30 分钟不满足 → 整次 422，参数不生效
        MvcResult rejected = mvc.perform(put("/api/v1/rolling-stocks/{stock}/turnaround", stock)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(turnaroundBody(key("REQ"), 1, 40)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("TURNAROUND_REVALIDATION_FAILED");
        JsonNode violation = error.get("details").get(0);
        assertThat(violation.get("type").asText()).isEqualTo("TURNAROUND_INSUFFICIENT");
        assertThat(violation.get("prevScheduleKey").asText()).isEqualTo(planA);
        assertThat(violation.get("nextScheduleKey").asText()).isEqualTo(planB);
        assertThat(violation.get("actualGapMinutes").asLong()).isEqualTo(30);
        assertThat(violation.get("requiredMinutes").asInt()).isEqualTo(40);

        // 参数未部分生效：版本与分钟数保持原值
        MvcResult chain = mvc.perform(get("/api/v1/rolling-stocks/{stock}/chain", stock)
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(chain).get("minTurnaroundMinutes").asInt()).isEqualTo(30);

        // 放宽到 20 分钟成功，版本加一
        MvcResult updated = mvc.perform(put("/api/v1/rolling-stocks/{stock}/turnaround", stock)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(turnaroundBody(key("REQ"), 1, 20)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(updated);
        assertThat(body.get("minTurnaroundMinutes").asInt()).isEqualTo(20);
        assertThat(body.get("version").asInt()).isEqualTo(2);
    }

    @Test
    void stockCreateAndTurnaroundIdempotentReplay() throws Exception {
        String stock = key("STK");
        String createKey = key("REQ");
        String createBody = stockBody(createKey, stock, 30);
        MvcResult first = mvc.perform(post("/api/v1/rolling-stocks")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放返回首次结果
        MvcResult replay = mvc.perform(post("/api/v1/rolling-stocks")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        // 同键不同参 → 409
        mvc.perform(post("/api/v1/rolling-stocks").contentType(MediaType.APPLICATION_JSON)
                        .content(stockBody(createKey, key("STK"), 30)))
                .andExpect(status().isConflict());
        // 重复登记不同键 → 409
        mvc.perform(post("/api/v1/rolling-stocks").contentType(MediaType.APPLICATION_JSON)
                        .content(stockBody(key("REQ"), stock, 30)))
                .andExpect(status().isConflict());

        // 周转修改幂等：同键重放不重复加版本
        String updateKey = key("REQ");
        String updateBody = turnaroundBody(updateKey, 1, 20);
        MvcResult updated = mvc.perform(put("/api/v1/rolling-stocks/{stock}/turnaround", stock)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult updatedReplay = mvc.perform(
                        put("/api/v1/rolling-stocks/{stock}/turnaround", stock)
                                .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(updatedReplay)).isEqualTo(read(updated));
        assertThat(read(updatedReplay).get("version").asInt()).isEqualTo(2);
        // 同键不同参 → 409
        mvc.perform(put("/api/v1/rolling-stocks/{stock}/turnaround", stock)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(turnaroundBody(updateKey, 1, 25)))
                .andExpect(status().isConflict());
    }

    @Test
    void missingStockReturns404() throws Exception {
        String missing = key("STK");
        mvc.perform(get("/api/v1/rolling-stocks/{stock}/chain", missing)
                        .param("date", DAY.toString()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/rolling-stocks/{stock}/breaks", missing))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/rolling-stocks/{stock}/turnaround", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(turnaroundBody(key("REQ"), 1, 30)))
                .andExpect(status().isNotFound());
    }

    @Test
    void turnaroundMinutesOutOfRangeRejected() throws Exception {
        String stock = key("STK");
        // 登记时 0 与 241 均非法
        mvc.perform(post("/api/v1/rolling-stocks").contentType(MediaType.APPLICATION_JSON)
                        .content(stockBody(key("REQ"), stock, 0)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/rolling-stocks").contentType(MediaType.APPLICATION_JSON)
                        .content(stockBody(key("REQ"), stock, 241)))
                .andExpect(status().isBadRequest());
        createStock(stock, 30);
        mvc.perform(put("/api/v1/rolling-stocks/{stock}/turnaround", stock)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(turnaroundBody(key("REQ"), 1, 300)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 改签：新草稿并入链校验，失败整单回滚 ----------

    @Test
    void rescheduleBreakingChainRollsBackWholeOperation() throws Exception {
        String stock = key("STK");
        createStock(stock, 30);
        String planA = key("SCH");
        String planB = key("SCH");
        createPlanWithStock(planA, stock, "S1", "S2", occ("G1", key("SEC"), iso(8), iso(9)));
        createPlanWithStock(planB, stock, "S2", "S3", occ("G2", key("SEC"), iso(9, 30), iso(10, 30)));
        publishPlan(planA, key("REQ"));
        publishPlan(planB, key("REQ"));

        // 改签 B → B'，B' 始发 09:10 距 A 终到仅 10 分钟，周转不足 → 422 整单回滚
        String planB2 = key("SCH");
        createPlanWithStock(planB2, stock, "S2", "S3", occ("G3", key("SEC"), iso(9, 10), iso(10, 10)));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/reschedule", planB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), planB2, 1, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("CHAIN_LINK_CONFLICT");
        assertThat(error.get("details").get(0).get("type").asText())
                .isEqualTo("TURNAROUND_INSUFFICIENT");

        // 原计划状态不变：B 仍发布、B' 仍草稿，无断链记录
        assertThat(getPlan(planB).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(planB2).get("status").asText()).isEqualTo("DRAFT");
        MvcResult breaks = mvc.perform(get("/api/v1/rolling-stocks/{stock}/breaks", stock))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(breaks)).isEmpty();

        // 合法改签成功：B'' 衔接满足，链保持连续
        String planB3 = key("SCH");
        createPlanWithStock(planB3, stock, "S2", "S3",
                occ("G4", key("SEC"), iso(9, 30), iso(10, 30)));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", planB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), planB3, 1, 1)))
                .andExpect(status().isOk());
        assertThat(getPlan(planB).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(getPlan(planB3).get("status").asText()).isEqualTo("PUBLISHED");
    }

    // ---------- 辅助 ----------

    private void createStock(String stockKey, int minutes) throws Exception {
        mvc.perform(post("/api/v1/rolling-stocks").contentType(MediaType.APPLICATION_JSON)
                        .content(stockBody(key("REQ"), stockKey, minutes)))
                .andExpect(status().isCreated());
    }

    private void createPlanWithStock(String scheduleKey, String stockKey, String origin,
                                     String dest, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, stockKey, origin, dest,
                                occupancies)))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private void cancelPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private JsonNode getPlan(String scheduleKey) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(detail);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String iso(int hour) {
        return iso(hour, 0);
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String stockBody(String requestKey, String stockKey, int minutes) {
        return "{\"requestKey\":\"" + requestKey + "\",\"stockKey\":\"" + stockKey
                + "\",\"minTurnaroundMinutes\":" + minutes + "}";
    }

    private static String turnaroundBody(String requestKey, int expectedVersion, int minutes) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"minTurnaroundMinutes\":" + minutes + "}";
    }

    private static String createBody(String requestKey, String scheduleKey, String stockKey,
                                     String origin, String dest, String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\",\"stockKey\":\"" + stockKey
                + "\",\"originStation\":\"" + origin + "\",\"destStation\":\"" + dest
                + "\",\"occupancies\":[" + String.join(",", occupancies) + "]}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }
}
