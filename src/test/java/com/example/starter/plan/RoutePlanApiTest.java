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
 * 寻径发布 API 测试：主流程选路（最快被占则继续找其他路）、字典序平局、
 * 422/404/409/400 失败分支、幂等与换图后旧计划冻结（H2 内存库）。
 * 各用例站点与区段编号均唯一，图版本先读后写，互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoutePlanApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程 ----------

    @Test
    void directOccupiedFallsBackToViaAndPublishesBothLegs() throws Exception {
        String s = key("S");
        String t = key("T");
        String u = key("U");
        String direct = key("D1");
        String leg1 = key("U1");
        String leg2 = key("U2");
        int version = replaceGraph(stations(s, t, u),
                seg(direct, s, t, 5), seg(leg1, s, u, 3), seg(leg2, u, t, 5));

        // 直达区段 08:00-08:05 已被其他已发布计划占用
        publishBlockingPlan(direct, iso(8, 0), iso(8, 5));

        // 截止 08:08 允许经 U 共 8 分钟：应选择经 U 并一次发布两段
        MvcResult result = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), "tester", version, "G1", s, t,
                                iso(8, 0), iso(8, 8))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(body.get("graphVersion").asInt()).isEqualTo(version);
        assertThat(body.get("totalMinutes").asInt()).isEqualTo(8);
        assertThat(body.get("arriveUtc").asText()).isEqualTo(iso(8, 8));
        assertThat(body.get("legs")).hasSize(2);
        assertThat(body.get("legs").get(0).get("segmentId").asText()).isEqualTo(leg1);
        assertThat(body.get("legs").get(0).get("startUtc").asText()).isEqualTo(iso(8, 0));
        assertThat(body.get("legs").get(0).get("endUtc").asText()).isEqualTo(iso(8, 3));
        assertThat(body.get("legs").get(1).get("segmentId").asText()).isEqualTo(leg2);
        assertThat(body.get("legs").get(1).get("startUtc").asText()).isEqualTo(iso(8, 3));
        assertThat(body.get("legs").get(1).get("endUtc").asText()).isEqualTo(iso(8, 8));

        // 生成的日计划已发布，两段占用冻结
        String scheduleKey = body.get("scheduleKey").asText();
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan = read(detail);
        assertThat(plan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(plan.get("occupancies")).hasSize(2);
        assertThat(plan.get("occupancies").get(0).get("sectionId").asText()).isEqualTo(leg1);
        assertThat(plan.get("occupancies").get(1).get("sectionId").asText()).isEqualTo(leg2);

        // 经 U 两段时隙生效
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", leg1))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).hasSize(1);
    }

    @Test
    void fastestPathChosenWhenFree() throws Exception {
        String s = key("S");
        String t = key("T");
        String u = key("U");
        String direct = key("D1");
        int version = replaceGraph(stations(s, t, u),
                seg(direct, s, t, 5), seg(key("U1"), s, u, 3), seg(key("U2"), u, t, 5));

        MvcResult result = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), "tester", version, "G1", s, t,
                                iso(8, 0), iso(8, 8))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("totalMinutes").asInt()).isEqualTo(5);
        assertThat(body.get("legs")).hasSize(1);
        assertThat(body.get("legs").get(0).get("segmentId").asText()).isEqualTo(direct);
    }

    @Test
    void equalMinutesPicksLexicographicallySmallerSegmentSequence() throws Exception {
        String s = key("S");
        String t = key("T");
        String v = key("V");
        String direct = key("Z9");   // 直达 5 分钟，编号字典序靠后
        String via1 = key("A1");     // 经 V 共 5 分钟，首段编号字典序靠前
        String via2 = key("A2");
        int version = replaceGraph(stations(s, t, v),
                seg(direct, s, t, 5), seg(via1, s, v, 2), seg(via2, v, t, 3));

        MvcResult result = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), "tester", version, "G1", s, t,
                                iso(8, 0), iso(8, 5))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("totalMinutes").asInt()).isEqualTo(5);
        assertThat(body.get("legs")).hasSize(2);
        assertThat(body.get("legs").get(0).get("segmentId").asText()).isEqualTo(via1);
        assertThat(body.get("legs").get(1).get("segmentId").asText()).isEqualTo(via2);
    }

    // ---------- 失败分支 ----------

    @Test
    void noFeasiblePathReturns422AndKeyStaysReusable() throws Exception {
        String s = key("S");
        String t = key("T");
        String u = key("U");
        String direct = key("D1");
        String leg1 = key("U1");
        int version = replaceGraph(stations(s, t, u),
                seg(direct, s, t, 5), seg(leg1, s, u, 3), seg(key("U2"), u, t, 5));

        // 直达被占，经 U 到达 08:08 超出截止 08:05 → 无可行路径
        String blocking = publishBlockingPlan(direct, iso(8, 0), iso(8, 5));
        String requestKey = key("REQ");
        String body = routeBody(requestKey, "tester", version, "G1", s, t, iso(8, 0), iso(8, 5));
        MvcResult rejected = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("ROUTE_INFEASIBLE");
        assertThat(error.get("details")).isNotEmpty();

        // 不建计划或占用：经 U 区段无生效时隙
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", leg1))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).isEmpty();

        // 失败不占幂等键：取消阻塞计划后同键同参重试成功
        cancelPlan(blocking);
        MvcResult retried = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(retried).get("legs")).hasSize(1);
    }

    @Test
    void unknownStationReturns404() throws Exception {
        String s = key("S");
        String t = key("T");
        int version = replaceGraph(stations(s, t), seg(key("D1"), s, t, 5));

        MvcResult fromMissing = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), "tester", version, "G1", key("NOPE"), t,
                                iso(8, 0), iso(8, 5))))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(read(fromMissing).get("code").asText()).isEqualTo("STATION_NOT_FOUND");

        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), "tester", version, "G1", s, key("NOPE"),
                                iso(8, 0), iso(8, 5))))
                .andExpect(status().isNotFound());
    }

    @Test
    void staleGraphVersionReturns409() throws Exception {
        String s = key("S");
        String t = key("T");
        int version = replaceGraph(stations(s, t), seg(key("D1"), s, t, 5));

        // 图再替换一次后，旧版本请求 → 409
        replaceGraph(stations(s, t), seg(key("D2"), s, t, 6));
        MvcResult result = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), "tester", version, "G1", s, t,
                                iso(8, 0), iso(8, 5))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("GRAPH_VERSION_CONFLICT");
    }

    @Test
    void invalidTimeParamsReturn400() throws Exception {
        String s = key("S");
        String t = key("T");
        int version = replaceGraph(stations(s, t), seg(key("D1"), s, t, 5));

        // 出发非 UTC 整分钟
        String notWholeMinute = DAY.atTime(8, 0, 30).atZone(SH).toInstant().toString();
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), "tester", version, "G1", s, t,
                                notWholeMinute, iso(8, 5))))
                .andExpect(status().isBadRequest());
        // 截止不晚于出发
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), "tester", version, "G1", s, t,
                                iso(8, 0), iso(8, 0))))
                .andExpect(status().isBadRequest());
        // 起终站相同
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), "tester", version, "G1", s, s,
                                iso(8, 0), iso(8, 5))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pathCrossingOperationDayIsInfeasible() throws Exception {
        String s = key("S");
        String t = key("T");
        int version = replaceGraph(stations(s, t), seg(key("D1"), s, t, 5));

        // 23:58 出发行驶 5 分钟跨 Asia/Shanghai 运营日 → 422
        MvcResult result = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), "tester", version, "G1", s, t,
                                iso(23, 58), iso(0, 10, 1))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("ROUTE_INFEASIBLE");
        assertThat(error.get("details").get(0).get("type").asText()).isEqualTo("ROUTE_CROSS_DAY");
    }

    // ---------- 幂等与冻结 ----------

    @Test
    void routePublishIdempotentReplayAndParamConflict() throws Exception {
        String s = key("S");
        String t = key("T");
        int version = replaceGraph(stations(s, t), seg(key("D1"), s, t, 5));
        String requestKey = key("REQ");
        String body = routeBody(requestKey, "tester", version, "G1", s, t, iso(8, 0), iso(8, 5));

        MvcResult first = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放返回首次完整响应（同一 scheduleKey，不重复建计划）
        MvcResult replay = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/route-plans").contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(requestKey, "tester", version, "G2", s, t,
                                iso(9, 0), iso(9, 5))))
                .andExpect(status().isConflict());
    }

    @Test
    void graphReplacementDoesNotChangePublishedPlanAndCancelFollowsOriginalFlow()
            throws Exception {
        String s = key("S");
        String t = key("T");
        String segment = key("D1");
        int version = replaceGraph(stations(s, t), seg(segment, s, t, 5));

        MvcResult published = mvc.perform(post("/api/v1/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeBody(key("REQ"), "tester", version, "G1", s, t,
                                iso(8, 0), iso(8, 5))))
                .andExpect(status().isCreated()).andReturn();
        String scheduleKey = read(published).get("scheduleKey").asText();

        // 换图（删除原区段）不改旧占用
        replaceGraph(stations(s, t), seg(key("D9"), s, t, 9));
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan = read(detail);
        assertThat(plan.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(plan.get("occupancies")).hasSize(1);
        assertThat(plan.get("occupancies").get(0).get("sectionId").asText()).isEqualTo(segment);
        assertThat(plan.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(8, 0));

        // 取消走原流程，时隙立即释放
        cancelPlan(scheduleKey);
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", segment))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).isEmpty();
    }

    // ---------- 辅助 ----------

    private int currentVersion() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/route-graph")).andReturn();
        if (result.getResponse().getStatus() == 404) {
            return 0;
        }
        return read(result).get("version").asInt();
    }

    private int replaceGraph(String stationsJson, String... segmentsJson) throws Exception {
        int current = currentVersion();
        MvcResult result = mvc.perform(put("/api/v1/route-graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"operator\":\"tester\""
                                + ",\"expectedVersion\":" + current
                                + ",\"stations\":" + stationsJson
                                + ",\"segments\":[" + String.join(",", segmentsJson) + "]}"))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("version").asInt();
    }

    /** 创建并发布一个普通计划，占用指定区段 [startIso, endIso)，返回 scheduleKey。 */
    private String publishBlockingPlan(String sectionId, String startIso, String endIso)
            throws Exception {
        String scheduleKey = key("SCH");
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + "{\"trainNo\":\"BLK\",\"sectionId\":\"" + sectionId
                                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso
                                + "\"}]}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isOk());
        return scheduleKey;
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

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        return iso(hour, minute, 0);
    }

    /** 运营日加 dayOffset 天后 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute, int dayOffset) {
        Instant instant = DAY.plusDays(dayOffset).atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String stations(String... stations) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < stations.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(stations[i]).append('"');
        }
        return sb.append(']').toString();
    }

    private static String seg(String id, String from, String to, int minutes) {
        return "{\"segmentId\":\"" + id + "\",\"fromStation\":\"" + from
                + "\",\"toStation\":\"" + to + "\",\"minutes\":" + minutes + "}";
    }

    private static String routeBody(String requestKey, String operator, int graphVersion,
                                    String trainNo, String from, String to,
                                    String departIso, String arriveByIso) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"graphVersion\":" + graphVersion + ",\"trainNo\":\"" + trainNo
                + "\",\"fromStation\":\"" + from + "\",\"toStation\":\"" + to
                + "\",\"departUtc\":\"" + departIso + "\",\"arriveByUtc\":\"" + arriveByIso
                + "\"}";
    }
}