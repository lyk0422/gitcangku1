package com.example.starter.plan;

import static com.example.starter.plan.ConnectionApiTest.actionBody;
import static com.example.starter.plan.ConnectionApiTest.iso;
import static com.example.starter.plan.ConnectionApiTest.key;
import static com.example.starter.plan.ConnectionApiTest.occ;
import static com.example.starter.plan.ConnectionApiTest.registerBody;
import static org.assertj.core.api.Assertions.assertThat;
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
 * 接续登记的 422 校验分支（站点连续、分钟范围、计划状态、运营日/跨零点、
 * 登记时保障不足）与幂等边界测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConnectionValidationTest {

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
    void registerRejectsGuaranteeMinutesOutOfRange() throws Exception {
        String pred = publishedPlan("G1", key("SEC"), iso(8, 0), iso(10, 0));
        String succ = publishedPlan("G2", key("SEC"), iso(11, 0), iso(12, 0));

        MvcResult negative = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, -1, "西安北", "西安北", false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(negative).get("code").asText()).isEqualTo("INVALID_GUARANTEE_MINUTES");
        MvcResult tooLarge = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, 1441, "西安北", "西安北", false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(tooLarge).get("code").asText()).isEqualTo("INVALID_GUARANTEE_MINUTES");

        // 边界值 0 与 1440 合法（1440 需保障已满足：另建间隔足够的车次对）
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, 0, "西安北", "西安北", false)))
                .andExpect(status().isCreated());
        String pred2 = publishedPlan("G3", key("SEC"), iso(0, 30), iso(1, 0));
        String succ2 = key("SCH");
        createPlan(succ2, DAY.plusDays(1), occ("G4", key("SEC"), isoNextDay(1, 0), isoNextDay(2, 0)));
        publish(succ2);
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred2, succ2, 1440, "西安北", "西安北", true)))
                .andExpect(status().isCreated());
    }

    @Test
    void registerRejectsStationDiscontinuity() throws Exception {
        String pred = publishedPlan("G1", key("SEC"), iso(8, 0), iso(10, 0));
        String succ = publishedPlan("G2", key("SEC"), iso(11, 0), iso(12, 0));

        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, 30, "西安北", "宝鸡南", false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("STATION_DISCONTINUITY");
    }

    @Test
    void registerRejectsCancelledOrUnpublishedPlan() throws Exception {
        String cancelled = publishedPlan("G1", key("SEC"), iso(8, 0), iso(10, 0));
        mvc.perform(post("/api/v1/plans/{key}/cancel", cancelled)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        String published = publishedPlan("G2", key("SEC"), iso(11, 0), iso(12, 0));

        MvcResult byCancelled = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), cancelled, published, 30, "西安北", "西安北", false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(byCancelled).get("code").asText()).isEqualTo("PLAN_CANCELLED");

        String draft = key("SCH");
        createPlan(draft, DAY, occ("G3", key("SEC"), iso(13, 0), iso(14, 0)));
        MvcResult byDraft = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), published, draft, 30, "西安北", "西安北", false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(byDraft).get("code").asText()).isEqualTo("PLAN_NOT_PUBLISHED");
    }

    @Test
    void registerRejectsOpDateMismatchUnlessCrossesMidnight() throws Exception {
        String pred = publishedPlan("G1", key("SEC"), iso(22, 0), iso(23, 30));
        String succNextDay = key("SCH");
        createPlan(succNextDay, DAY.plusDays(1), occ("G2", key("SEC"), isoNextDay(0, 30), isoNextDay(2, 0)));
        publish(succNextDay);

        // 次日运营日但未声明跨零点 → 422
        MvcResult missing = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succNextDay, 30, "西安北", "西安北", false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(missing).get("code").asText()).isEqualTo("OP_DATE_MISMATCH");

        // 显式跨零点 → 201，实际保障 60 分钟
        MvcResult ok = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succNextDay, 30, "西安北", "西安北", true)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(ok).get("actualMinutes").asInt()).isEqualTo(60);

        // 同运营日却声明跨零点 → 422
        String predSame = publishedPlan("G3", key("SEC"), iso(8, 0), iso(9, 0));
        String succSame = publishedPlan("G4", key("SEC"), iso(10, 0), iso(11, 0));
        MvcResult sameDay = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), predSame, succSame, 30, "西安北", "西安北", true)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(sameDay).get("code").asText()).isEqualTo("OP_DATE_MISMATCH");

        // 相隔两天 → 422
        String succTwoDays = key("SCH");
        createPlan(succTwoDays, DAY.plusDays(2), occ("G5", key("SEC"), isoNextDay2(8, 0), isoNextDay2(9, 0)));
        publish(succTwoDays);
        MvcResult twoDays = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), predSame, succTwoDays, 30, "西安北", "西安北", true)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(twoDays).get("code").asText()).isEqualTo("OP_DATE_MISMATCH");
    }

    @Test
    void registerRejectsInsufficientGuaranteeWithAmounts() throws Exception {
        String pred = publishedPlan("G1", key("SEC"), iso(8, 0), iso(10, 0));
        String succ = publishedPlan("G2", key("SEC"), iso(10, 10), iso(12, 0));

        MvcResult result = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, 30, "西安北", "西安北", false)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("CONNECTION_GUARANTEE_INSUFFICIENT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("requiredMinutes").asInt()).isEqualTo(30);
        assertThat(detail.get("actualMinutes").asInt()).isEqualTo(10);
        assertThat(detail.get("deficitMinutes").asInt()).isEqualTo(20);
    }

    @Test
    void registerRejectsSamePlanAsBadRequest() throws Exception {
        String plan = publishedPlan("G1", key("SEC"), iso(8, 0), iso(10, 0));
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), plan, plan, 30, "西安北", "西安北", false)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerIdempotencyReplayConflictAndFailureNotConsumingKey() throws Exception {
        String pred = publishedPlan("G1", key("SEC"), iso(8, 0), iso(10, 0));
        String succ = publishedPlan("G2", key("SEC"), iso(11, 0), iso(12, 0));
        String requestKey = key("REQ");
        String body = registerBody(requestKey, pred, succ, 30, "西安北", "西安北", false);

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
                        .content(registerBody(requestKey, pred, succ, 45, "西安北", "西安北", false)))
                .andExpect(status().isConflict());
        // 同参数新键重复登记 → 409 CONNECTION_EXISTS
        MvcResult dup = mvc.perform(post("/api/v1/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), pred, succ, 30, "西安北", "西安北", false)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(dup).get("code").asText()).isEqualTo("CONNECTION_EXISTS");

        // 失败不占键：先用某键触发 422，再同键修正参数成功登记
        String failKey = key("REQ");
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(failKey, pred, succ, 30, "西安北", "宝鸡南", false)))
                .andExpect(status().isUnprocessableEntity());
        String pred2 = publishedPlan("G3", key("SEC"), iso(13, 0), iso(14, 0));
        String succ2 = publishedPlan("G4", key("SEC"), iso(15, 0), iso(16, 0));
        mvc.perform(post("/api/v1/connections").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(failKey, pred2, succ2, 30, "西安北", "西安北", false)))
                .andExpect(status().isCreated());
    }

    // ---------- 辅助 ----------

    private String publishedPlan(String train, String section, String startIso, String endIso) {
        String scheduleKey = key("SCH");
        try {
            createPlan(scheduleKey, DAY, occ(train, section, startIso, endIso));
            publish(scheduleKey);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return scheduleKey;
    }

    private void createPlan(String scheduleKey, LocalDate opDate, String occupancy) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\"" + scheduleKey
                                + "\",\"opDate\":\"" + opDate + "\",\"occupancies\":[" + occupancy + "]}"))
                .andExpect(status().isCreated());
    }

    private void publish(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 运营日次日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    static String isoNextDay(int hour, int minute) {
        return DAY.plusDays(1).atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    /** 运营日第三日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    static String isoNextDay2(int hour, int minute) {
        return DAY.plusDays(2).atTime(hour, minute).atZone(SH).toInstant().toString();
    }
}